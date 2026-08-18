package com.example.testbutton

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 后台音频监听服务 (前台服务)
 */
class AudioMonitorService : Service() {

    private var audioEngine: AudioEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val CHANNEL_ID = "audio_monitor_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        
        // 获取唤醒锁，防止 CPU 休眠
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartRecorder:KeepAlive")
        wakeLock?.acquire()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("正在初始化..."))
        
        observeState()
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "环境录音服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("智能录音运行中")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true) // 防止频繁通知响铃/震动
            .build()
    }
}
