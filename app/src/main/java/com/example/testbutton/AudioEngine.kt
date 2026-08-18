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
import kotlinx.coroutines.delay
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
import utils.AppLogger
import data.RecordingMode

/**
 * 智能音频引擎 - 实时全状态动态追踪版
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
    
    private var triggerThreshold = 30      // 触发录音阈值
    private var envNoise = 30.0            // 动态追踪底噪基线
    private val triggerSamples = ArrayDeque<Int>() // 15秒滑动窗口 (150点) - 用于分布统计和逻辑判定

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
        triggerSamples.clear()
        preBuffer.clear()
        envNoise = 30.0 
        
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
                val db = if (rms > 0) (20.0 * log10(rms)).toInt().coerceIn(0, 120) else 0

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
        val currentMode = AudioStateRepo.recordingMode.value
        val offset = AudioStateRepo.triggerOffset.value
        val currentDbVal = db.toDouble()

        // 1. 全程动态底噪追踪 (不论监听还是录音，均实时更新)
        if (currentDbVal < envNoise) {
            // 快降：环境变安静，迅速跟进
            val fallWeight = (AudioStateRepo.fallSpeed.value * 0.04).coerceIn(0.01, 0.9)
            envNoise = envNoise * (1.0 - fallWeight) + currentDbVal * fallWeight
        } else {
            // 能量分布自适应加速 (核心：区分噪音与人声)
            if (triggerSamples.size >= 30) {
                val highCount = triggerSamples.count { it > envNoise }
                val ratio = highCount.toDouble() / triggerSamples.size
                
                // 1. 获取用户设置的变率倍率 (1-10, 5为标准1.0x)
                val userSpeedFactor = AudioStateRepo.trackingSpeed.value / 5.0
                
                // 2. 录音保护因子：录音中追踪速度削减至 5%，确保持久录音不断
                val recordingProtection = if (currentState == AudioState.STATE_RECORDING) 0.05 else 1.0
                
                val finalFactor = userSpeedFactor * recordingProtection

                // 3. 极致加速梯度 (全线应用 finalFactor)
                val riseWeight = when {
                    ratio >= 0.98 -> 0.35 * finalFactor  // 0.5秒内对齐
                    ratio >= 0.90 -> 0.15 * finalFactor  // 1秒内对齐
                    ratio >= 0.80 -> 0.06 * finalFactor
                    else -> 0.001 * finalFactor         // 极慢：保护人声录制
                }
                
                envNoise = envNoise * (1.0 - riseWeight.coerceAtMost(0.9)) + currentDbVal * riseWeight.coerceAtMost(0.9)
            } else {
                // 初始化阶段：常规跟进
                envNoise = envNoise * 0.95 + currentDbVal * 0.05
            }
        }

        // 2. 实时更新触发阈值
        triggerThreshold = envNoise.toInt() + offset
        AudioStateRepo.currentThreshold.value = triggerThreshold

        // 3. 更新判定窗口 (15s)
        triggerSamples.addLast(db)
        if (triggerSamples.size > 150) triggerSamples.removeFirst()

        // 4. 状态机逻辑
        when (currentState) {
            AudioState.STATE_INIT -> {
                if (triggerSamples.size >= 10) {
                    currentState = AudioState.STATE_LISTENING
                    AudioStateRepo.currentStatus.value = "正在监听"
                    AppLogger.log(context, "🔄 建模完成 [${currentMode.name}] 基线：${envNoise.toInt()}dB")
                } else {
                    AudioStateRepo.currentStatus.value = "初始化(${triggerSamples.size}/10)"
                }
            }

            AudioState.STATE_LISTENING -> {
                if (triggerSamples.size >= 50) {
                    val last5s = triggerSamples.takeLast(50)
                    val triggerCount = last5s.count { it > triggerThreshold }
                    if (triggerCount >= 15) {
                        startRecordingAction()
                        currentState = AudioState.STATE_RECORDING
                        AudioStateRepo.isRecording.value = true
                        AudioStateRepo.currentStatus.value = "正在录音"
                        AppLogger.log(context, "🎤 [突发] 触发录音 (阈值:$triggerThreshold dB)")
                    }
                }
            }

            AudioState.STATE_RECORDING -> {
                val now = System.currentTimeMillis()
                val recordingDuration = now - recordingStartTime
                val evalDelay = AudioStateRepo.stopEvalDelayMs.value
                
                if (recordingDuration >= evalDelay) {
                    if (triggerSamples.size >= 150) {
                        val silenceCount = triggerSamples.count { it <= triggerThreshold }
                        if (silenceCount >= 105) {
                            stopRecordingAction()
                            currentState = AudioState.STATE_LISTENING
                            AudioStateRepo.isRecording.value = false
                            AudioStateRepo.currentStatus.value = "正在监听"
                        }
                    }
                } else {
                    AudioStateRepo.currentStatus.value = "录音中(锁定:${(evalDelay - recordingDuration) / 1000}s)"
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
        val duration = System.currentTimeMillis() - recordingStartTime
        val minSaveDuration = AudioStateRepo.minSaveDurationMs.value
        
        try {
            pcmOutputStream?.flush()
            pcmOutputStream?.close()
            pcmOutputStream = null
            
            val fileToSave = tempPcmFile
            if (fileToSave != null && fileToSave.exists()) {
                if (duration >= minSaveDuration) {
                    finalizeWavFile(fileToSave)
                    val now = System.currentTimeMillis()
                    AudioStateRepo.lastValidRecordTime.value = now
                    data.AppPreferences(context).saveLastValidRecordTime(now)
                    AppLogger.log(context, "✅ 生成有效录音，时长：${duration/1000}秒，已保存")
                } else {
                    fileToSave.delete()
                    AudioStateRepo.currentStatus.value = "录音过短(${duration/1000}s),已舍弃"
                    AppLogger.log(context, "🗑️ 产生未保存的短时录音，时长：${duration/1000}秒，已自动丢弃")
                }
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
