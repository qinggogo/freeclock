package com.example.flipclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 闹钟响铃前台服务：到点立即播放铃声 + 振动，不依赖用户点击全屏通知。
 * 前台服务保证进程优先级，铃声不会被系统立刻杀掉。
 */
class AlarmSoundService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "flipclock_ringing"
        const val NOTIFY_ID = 5005
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val vibrateRunnable = object : Runnable {
        override fun run() {
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(700)
                }
            }
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSound()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startSound()
        }
        return START_NOT_STICKY
    }

    private fun startSound() {
        startForeground(NOTIFY_ID, buildNotification())

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.play()
        } catch (e: Exception) {
        }

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        handler.removeCallbacks(vibrateRunnable)
        handler.post(vibrateRunnable)
    }

    private fun stopSound() {
        handler.removeCallbacks(vibrateRunnable)
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "闹钟响铃", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "闹钟正在响铃" }
            nm.createNotificationChannel(ch)
        }
        val open = Intent(this, AlarmRingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 6006, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("闹钟")
            .setContentText("正在响铃，点此关闭")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }
}
