package com.example.flipclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmHelper {
    private const val PREF = "flipclock_alarm"
    const val KEY_ENABLED = "enabled"
    const val KEY_HOUR = "hour"
    const val KEY_MINUTE = "minute"
    const val ACTION_ALARM = "com.example.flipclock.ALARM"
    const val ACTION_SNOOZE = "com.example.flipclock.SNOOZE"
    const val ACTION_TIMER = "com.example.flipclock.TIMER_DONE"
    const val ACTION_STOP_TIMER = "com.example.flipclock.TIMER_STOP"
    const val EXTRA_SNOOZE = "snooze_minutes"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun getTime(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return Pair(p.getInt(KEY_HOUR, 7), p.getInt(KEY_MINUTE, 0))
    }

    fun setAlarm(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_HOUR, hour)
            .putInt(KEY_MINUTE, minute)
            .apply()
        schedule(context, hour, minute)
    }

    fun disableAlarm(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
        cancel(context)
    }

    /** 计算下一次触发时间并注册精确闹钟；若今天的时间已过则定到明天 */
    fun schedule(context: Context, hour: Int, minute: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent(context)
        // setAlarmClock 是系统级精确闹钟，不受低电耗/打盹限制，且会在状态栏显示闹钟图标
        val info = AlarmManager.AlarmClockInfo(cal.timeInMillis, pi)
        am.setAlarmClock(info, pi)
    }

    /** 稍后提醒：在 now+snoozeMinutes 后再响一次，不改变已保存的闹钟时间 */
    fun snooze(context: Context, minutes: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent(context)
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        val info = AlarmManager.AlarmClockInfo(trigger, pi)
        am.setAlarmClock(info, pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPendingIntent(context))
    }

    /** 开机后恢复已启用的闹钟 */
    fun restoreIfNeeded(context: Context) {
        if (!isEnabled(context)) return
        val (h, m) = getTime(context)
        schedule(context, h, m)
    }

    /** 预约一次性倒计时响铃（用绝对触发时刻，退到后台/锁屏也能到点响） */
    fun scheduleTimer(context: Context, triggerAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = timerPendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancelTimer(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(timerPendingIntent(context))
    }

    private fun timerPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_TIMER)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 1002, intent, flags)
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 1001, intent, flags)
    }
}
