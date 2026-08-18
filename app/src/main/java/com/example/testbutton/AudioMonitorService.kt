package com.example.testbutton

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import data.AppPreferences
import utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * 后台音频监听服务 (前台服务)
 */
class AudioMonitorService : Service() {

    private var audioEngine: AudioEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val CHANNEL_ID = "audio_monitor_channel"
        private const val ALERT_CHANNEL_ID = "audio_alert_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 2002
    }

    override fun onCreate() {
        super.onCreate()
        
        // 获取唤醒锁，防止 CPU 休眠
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartRecorder:KeepAlive")
        wakeLock?.acquire()

        createNotificationChannel()
        val notification = createNotification("正在初始化...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        loadPreferences()
        observeState()
        startAlertChecker()
    }

    private fun loadPreferences() {
        val prefs = AppPreferences(this)
        AudioStateRepo.recordingMode.value = prefs.getRecordingMode()
        AudioStateRepo.triggerOffset.value = prefs.getTriggerOffset()
        AudioStateRepo.stopEvalDelayMs.value = prefs.getStopEvalDelay()
        AudioStateRepo.minSaveDurationMs.value = prefs.getMinSaveDuration()
        AudioStateRepo.isAlertModeEnabled.value = prefs.isAlertModeEnabled()
        AudioStateRepo.alertTimeThresholdMs.value = prefs.getAlertTimeThreshold()
        AudioStateRepo.lastValidRecordTime.value = prefs.getLastValidRecordTime()
    }

    private fun startAlertChecker() {
        serviceScope.launch {
            while (true) {
                delay(60 * 1000) // 每分钟检查一次
                if (AudioStateRepo.isAlertModeEnabled.value) {
                    val now = System.currentTimeMillis()
                    val lastRecord = AudioStateRepo.lastValidRecordTime.value
                    val threshold = AudioStateRepo.alertTimeThresholdMs.value
                    
                    if (now - lastRecord > threshold) {
                        sendAlertNotification(now - lastRecord)
                        AppLogger.log(this@AudioMonitorService, "🚨 警告：已超过 ${ (now - lastRecord) / (1000 * 60 * 60) } 小时未检测到有效声音，触发系统警报！")
                    }
                }
            }
        }
    }

    private fun sendAlertNotification(elapsedMs: Long) {
        val hours = elapsedMs / (1000 * 60 * 60)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("⚠️ 安全警报")
            .setContentText("设备已超过 $hours 小时未检测到有效声音，请确认安全！")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun observeState() {
        serviceScope.launch {
            combine(
                AudioStateRepo.currentDb,
                AudioStateRepo.currentThreshold,
                AudioStateRepo.currentStatus
            ) { db, threshold, status ->
                "音量: $db dB | 阈值: $threshold dB | $status"
            }.collect { info ->
                val notification = createNotification(info)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = AppPreferences(this)
        val uriString = prefs.getStorageUri()
        val uri = if (uriString != null) Uri.parse(uriString) else null
        
        if (audioEngine == null) {
            audioEngine = AudioEngine(this, uri)
            audioEngine?.start()
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        audioEngine?.stop()
        audioEngine = null
        
        wakeLock?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 普通监听通道
            val channel = NotificationChannel(
                CHANNEL_ID,
                "环境录音服务",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
            
            // 警报通道
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "安全警报通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "当长时间未检测到录音时发送的紧急警报"
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("voice黑匣子运行中")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true) // 防止频繁通知响铃/震动
            .build()
    }
}
