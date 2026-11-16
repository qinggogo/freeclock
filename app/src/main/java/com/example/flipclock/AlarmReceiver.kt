package com.example.flipclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "flipclock_alarm"
        const val EXTRA_LABEL = "timer_label"
        // 静态持有，保证响铃期间不被回收
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
        @Volatile var isRinging = false

        /** 立即响铃并显示"时间到"通知；供前台计时到点直接调用 */
        fun ringNow(context: Context, label: String) {
            val receiver = AlarmReceiver()
            receiver.startRinging(context)
            receiver.notifyTimerDone(context, label)
        }

        fun stopRinging() {
            isRinging = false
            handler.removeCallbacks(vibrateRunnable)
            handler.removeCallbacks(autoStopRunnable)
            ringtone?.stop()
            ringtone = null
            vibrator?.cancel()
            vibrator = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmHelper.ACTION_ALARM -> {
                // 1) 直接播放铃声 + 振动，不依赖 Activity 或服务，保证必响
                startRinging(context)
                // 2) 发闹钟通知（含全屏 intent 兜底拉起界面）
                notifyAlarm(context)
                Toast.makeText(context, "闹钟响了", Toast.LENGTH_SHORT).show()
            }
            AlarmHelper.ACTION_TIMER -> {
                startRinging(context)
                notifyTimerDone(context, intent.getStringExtra(EXTRA_LABEL) ?: "计时结束")
            }
            AlarmHelper.ACTION_STOP_TIMER -> {
                stopRinging()
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(4005)
            }
        }
    }

    private fun notifyTimerDone(context: Context, label: String) {
        val stopIntent = Intent(context, AlarmReceiver::class.java).setAction(AlarmHelper.ACTION_STOP_TIMER)
        val stopPi = PendingIntent.getBroadcast(
            context, 1004, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "闹钟 / 计时", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "翻页时钟响铃" }
            nm.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("时间到")
            .setContentText(label)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(stopPi)
            .addAction(android.R.drawable.ic_delete, "停止响铃", stopPi)
            .build()
        nm.notify(4005, notification)
    }

    private fun startRinging(context: Context) {
        stopRinging()
        isRinging = true
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            ringtone?.play()
        } catch (e: Exception) {
        }
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        handler.removeCallbacks(vibrateRunnable)
        handler.post(vibrateRunnable)
        // 兜底：最多响 60 秒自动停止，避免未能手动关闭时铃声/振动一直占用
        handler.removeCallbacks(autoStopRunnable)
        handler.postDelayed(autoStopRunnable, 60_000L)
    }

    private val autoStopRunnable = Runnable { stopRinging() }

    private fun notifyAlarm(context: Context) {
        val openIntent = Intent(context, AlarmRingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullScreenPi = PendingIntent.getActivity(context, 2002, openIntent, flags)
        val contentPi = PendingIntent.getActivity(context, 2003, openIntent, flags)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "闹钟", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "翻页时钟闹钟响铃" }
            nm.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("闹钟")
            .setContentText("时间到了，点击关闭")
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPi)
            .setFullScreenIntent(fullScreenPi, true)
            .build()
        nm.notify(3003, notification)
    }
}
