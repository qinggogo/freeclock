package com.example.flipclock

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 响铃界面：铃声由 AlarmSoundService 在到点时自动播放，本界面只负责显示与提供关闭/稍后按钮。
 */
class AlarmRingingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF000000.toInt())
        }

        val label = TextView(this).apply {
            text = "闹钟"
            setTextColor(0xFFFFB454.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val timeText = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 84f
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, (36 * density).toInt())
        }
        val snoozeBtn = Button(this).apply {
            text = "稍后提醒 5 分钟"
            textSize = 16f
        }
        val stopBtn = Button(this).apply {
            text = "关闭闹钟"
            textSize = 16f
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(snoozeBtn, LinearLayout.LayoutParams(
                (160 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (10 * density).toInt() })
            addView(stopBtn, LinearLayout.LayoutParams(
                (160 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        root.addView(label)
        root.addView(timeText)
        root.addView(btnRow)
        setContentView(root)

        stopBtn.setOnClickListener { stopAlarm() }
        snoozeBtn.setOnClickListener {
            stopService()
            AlarmHelper.snooze(this, 5)
            finish()
        }
    }

    private fun stopService() {
        AlarmReceiver.stopRinging()
        val svc = Intent(this, AlarmSoundService::class.java).setAction(AlarmSoundService.ACTION_STOP)
        try { stopService(svc) } catch (e: Exception) {}
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(3003)
    }

    private fun stopAlarm() {
        stopService()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 不允许用返回键绕过闹钟，必须点关闭
    }
}
