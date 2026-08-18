package com.example.testbutton

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 智能音频引擎 - 稳定性与精确度重构版
 */
class AudioEngine(
    private val context: Context,
    private val saveTreeUri: Uri?
) {
    private enum class AudioState {
        STATE_INIT, STATE_LISTENING, STATE_RECORDING
    }

    private var currentState = AudioState.STATE_INIT
    private var audioRecord: AudioRecord? = null
    private var running = false
    private var thread: Thread? = null
    private val sampleRate = 16000

    private val engineScope = CoroutineScope(Dispatchers.IO + Job())

    // =========================================================
    // 算法参数
    // =========================================================
    private val sampleIntervalMs = 100L    // 采样间隔 100ms
    private val triggerOffset = 8          // 触发偏移 8dB
    private val minRecordingDurationMs = 2000L // 最短录音 2 秒 (防跳变)
    
    private var triggerThreshold = -40     // 触发录音阈值
    private val ambientSamples = ArrayDeque<Int>() // 10秒滑动窗口 (100点)
    private val triggerSamples = ArrayDeque<Int>() // 15秒滑动窗口 (150点) - 用于开始和停止逻辑
    
    private var lastSampleTime = 0L
    private var recordingStartTime = 0L

    // =========================================================
    // 录音文件管理
    // =========================================================
    private var tempPcmFile: File? = null
    private var pcmOutputStream: FileOutputStream? = null
    
    private val preBuffer = ArrayDeque<ByteArray>()
    private val preBufferSeconds = 3
    private val preBufferBytes = sampleRate * 2 * preBufferSeconds

    fun start() {
        if (running) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize <= 0) return

        audioRecord = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
        } catch (e: SecurityException) { null }

        if (audioRecord == null) return
        try {
            audioRecord?.startRecording()
        } catch (e: SecurityException) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        running = true
        currentState = AudioState.STATE_INIT
        ambientSamples.clear()
        triggerSamples.clear()
        preBuffer.clear()
        
        AudioStateRepo.isRunning.value = true
        AudioStateRepo.currentStatus.value = "环境建模中..."

        thread = Thread {
            val buffer = ShortArray(bufferSize)
            while (running) {
                val read = try {
                    audioRecord?.read(buffer, 0, buffer.size) ?: 0
                } catch (e: SecurityException) { 0 }

                if (read <= 0) continue

                // 维护 3 秒预录缓冲区
                addPreBuffer(buffer, read)
                
                if (currentState == AudioState.STATE_RECORDING) {
                    writePcmToFile(buffer, read)
                }

                // 计算 RMS 分贝
                var sum = 0.0
                for (i in 0 until read) {
                    val value = buffer[i].toDouble()
                    sum += value * value
                }
                val rms = sqrt(sum / read)
                val db = if (rms > 0) (20.0 * log10(rms)).toInt() else -100

                AudioStateRepo.currentDb.value = db
                val now = System.currentTimeMillis()

                // 每 100ms 执行一次算法判定
                if (now - lastSampleTime >= sampleIntervalMs) {
                    lastSampleTime = now
                    processAlgorithm(db)
                }
            }
        }
        thread?.start()
    }

    private fun processAlgorithm(db: Int) {
        // 1. 更新环境底噪窗口 (10秒 = 100个采样点)
        ambientSamples.addLast(db)
        if (ambientSamples.size > 100) ambientSamples.removeFirst()

        // 2. 动态更新阈值: 10s内 4个最低值的平均分贝 + 8dB
        if (ambientSamples.size >= 4) {
            val sorted = ambientSamples.sorted()
            val lowestFour = sorted.take(4)
            val envNoise = lowestFour.average().toInt()
            triggerThreshold = envNoise + triggerOffset
            AudioStateRepo.currentThreshold.value = triggerThreshold
        }

        // 3. 更新触发/停止判定窗口 (15秒 = 150采样点)
        triggerSamples.addLast(db)
        if (triggerSamples.size > 150) triggerSamples.removeFirst()

        // 4. 状态机逻辑
        when (currentState) {
            AudioState.STATE_INIT -> {
                // 等待至少 5 秒数据，让底噪稳定
                if (ambientSamples.size >= 50) {
                    currentState = AudioState.STATE_LISTENING
                    AudioStateRepo.currentStatus.value = "正在监听"
                } else {
                    AudioStateRepo.currentStatus.value = "环境建模中(${ambientSamples.size}/50)"
                }
            }

            AudioState.STATE_LISTENING -> {
                // 判定触发：最近 5 秒内 (50个样本) >= 30% (15个) 超过阈值
                if (triggerSamples.size >= 50) {
                    val last5s = triggerSamples.takeLast(50)
                    val triggerCount = last5s.count { it > triggerThreshold }
                    if (triggerCount >= 15) {
                        startRecordingAction()
                        currentState = AudioState.STATE_RECORDING
                        AudioStateRepo.isRecording.value = true
                        AudioStateRepo.currentStatus.value = "正在录音"
                    }
                }
            }

            AudioState.STATE_RECORDING -> {
                // 判定停止：最近 15 秒内 (150个样本) >= 70% (105个) 低于或等于阈值
                if (triggerSamples.size >= 150) {
                    val silenceCount = triggerSamples.count { it <= triggerThreshold }
                    val now = System.currentTimeMillis()
                    // 必须满足停止比例，且录制时间超过 2 秒，才允许停止
                    if (silenceCount >= 105 && (now - recordingStartTime) >= minRecordingDurationMs) {
                        stopRecordingAction()
                        currentState = AudioState.STATE_LISTENING
                        AudioStateRepo.isRecording.value = false
                        AudioStateRepo.currentStatus.value = "正在监听"
                    }
                }
            }
        }
    }

    // =========================================================
    // 存储底层逻辑
    // =========================================================

    private fun addPreBuffer(buffer: ShortArray, count: Int) {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = buffer[i].toInt()
            bytes[i * 2] = (value and 0xff).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        synchronized(preBuffer) {
            preBuffer.addLast(bytes)
            var totalBytes = 0
            for (chunk in preBuffer) totalBytes += chunk.size
            
            while (totalBytes > preBufferBytes) {
                val removed = preBuffer.removeFirst()
                totalBytes -= removed.size
            }
        }
    }

    private fun startRecordingAction() {
        recordingStartTime = System.currentTimeMillis()
        try {
            tempPcmFile = File(context.cacheDir, "temp_recording.pcm")
            pcmOutputStream = FileOutputStream(tempPcmFile)
            
            // 写入预录数据
            synchronized(preBuffer) {
                for (chunk in preBuffer) {
                    pcmOutputStream?.write(chunk)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writePcmToFile(buffer: ShortArray, count: Int) {
        try {
            val bytes = ByteArray(count * 2)
            for (i in 0 until count) {
                val value = buffer[i].toInt()
                bytes[i * 2] = (value and 0xff).toByte()
                bytes[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
            }
            pcmOutputStream?.write(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRecordingAction() {
        try {
            pcmOutputStream?.flush()
            pcmOutputStream?.close()
            pcmOutputStream = null
            
            val fileToSave = tempPcmFile
            if (fileToSave != null && fileToSave.exists() && fileToSave.length() > 0) {
                finalizeWavFile(fileToSave)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun finalizeWavFile(pcmFile: File) {
        val timeString = SimpleDateFormat("yyyy.M.d.H.mm.ss", Locale.getDefault()).format(Date())
        val fileName = "$timeString.wav"
        
        engineScope.launch {
            try {
                if (saveTreeUri != null) {
                    val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(saveTreeUri, DocumentsContract.getTreeDocumentId(saveTreeUri))
                    val fileUri = DocumentsContract.createDocument(context.contentResolver, rootDocUri, "audio/wav", fileName)
                    
                    if (fileUri != null) {
                        context.contentResolver.openOutputStream(fileUri, "w")?.use { output ->
                            val pcmSize = pcmFile.length().toInt()
                            writeWavHeader(output, pcmSize)
                            
                            pcmFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        AudioStateRepo.lastSavedFile.value = fileName
                    }
                }
                // 清理临时文件
                pcmFile.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun writeWavHeader(output: java.io.OutputStream, dataLength: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLength = dataLength + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLength)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) 
        header.putShort(1) 
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataLength)

        output.write(header.array())
    }

    fun stop() {
        running = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        thread = null
        
        if (currentState == AudioState.STATE_RECORDING) {
            stopRecordingAction()
        }
        
        AudioStateRepo.isRunning.value = false
        AudioStateRepo.isRecording.value = false
        AudioStateRepo.currentStatus.value = "已停止"
    }
}
