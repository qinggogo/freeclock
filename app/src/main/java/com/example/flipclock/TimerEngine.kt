package com.example.flipclock

import kotlin.math.max

/** 番茄钟 / 正计时秒表 / 倒计时 的状态机。时间单位统一为毫秒。 */
class TimerEngine {

    var workMinutes = 25
    var restMinutes = 5

    private var pomoRemaining = workMinutes * 60_000L
    var pomoRunning = false
    var pomoIsWork = true
    var pomoSessions = 0

    var swElapsed = 0L
    var swRunning = false
    val swLaps = ArrayList<Long>()

    var cdDuration = 5 * 60_000L
    var cdRemaining = 0L
    var cdRunning = false
    var cdEndAt = 0L   // 绝对结束时刻(System.currentTimeMillis())，后台回来据此校正

    fun initPomodoro() {
        pomoRemaining = (if (pomoIsWork) workMinutes else restMinutes) * 60_000L
    }

    fun startPause(mode: Int) {
        when (mode) {
            FlipClockView.MODE_POMODORO -> {
                if (!pomoRunning && pomoRemaining <= 0) initPomodoro()
                pomoRunning = !pomoRunning
            }
            FlipClockView.MODE_STOPWATCH -> swRunning = !swRunning
            FlipClockView.MODE_COUNTDOWN -> {
                if (!cdRunning) {
                    if (cdRemaining <= 0) cdRemaining = cdDuration
                    cdEndAt = System.currentTimeMillis() + cdRemaining
                }
                cdRunning = !cdRunning
            }
        }
    }

    fun reset(mode: Int) {
        when (mode) {
            FlipClockView.MODE_POMODORO -> {
                pomoRunning = false
                pomoIsWork = true
                pomoSessions = 0
                initPomodoro()
            }
            FlipClockView.MODE_STOPWATCH -> {
                swRunning = false
                swElapsed = 0
                swLaps.clear()
            }
            FlipClockView.MODE_COUNTDOWN -> {
                cdRunning = false
                cdRemaining = 0
                cdEndAt = 0
            }
        }
    }

    fun addLap(): Boolean {
        if (!swRunning) return false
        swLaps.add(swElapsed)
        return true
    }

    /** 由 View 的定时器按真实流逝时间驱动。返回 true 表示有计时结束事件。 */
    fun tick(dt: Long): Boolean {
        var finished = false
        if (pomoRunning) {
            pomoRemaining -= dt
            if (pomoRemaining <= 0) {
                pomoRemaining = 0
                pomoRunning = false
                if (pomoIsWork) {
                    pomoSessions++
                    pomoIsWork = false
                } else {
                    pomoIsWork = true
                }
                initPomodoro()
                finished = true
            }
        }
        if (swRunning) {
            swElapsed += dt
        }
        if (cdRunning) {
            // 用绝对结束时刻校正，避免退后台 tick 暂停导致倒计时变慢
            cdRemaining = cdEndAt - System.currentTimeMillis()
            if (cdRemaining <= 0) {
                cdRemaining = 0
                cdRunning = false
                finished = true
            }
        }
        return finished
    }

    fun pomoTotal(): Long = (if (pomoIsWork) workMinutes else restMinutes) * 60_000L

    fun pomoProgress(): Float {
        val total = max(1L, pomoTotal())
        return (pomoRemaining.toFloat() / total).coerceIn(0f, 1f)
    }

    fun cdProgress(): Float {
        val total = max(1L, cdDuration)
        return (cdRemaining.toFloat() / total).coerceIn(0f, 1f)
    }

    fun pomoText(): String = formatClock(pomoRemaining, false)
    fun cdText(): String = formatClock(cdRemaining, false)
    fun swText(): String = formatClock(swElapsed, true)

    /** 倒计时设定值文本 */
    fun cdSettingText(): String = formatClock(cdDuration, false)

    fun changeCdDuration(deltaMinutes: Int) {
        cdDuration = (cdDuration + deltaMinutes * 60_000L).coerceIn(1000L, 24 * 3600_000L)
        cdRemaining = 0
        cdRunning = false
        cdEndAt = 0
    }

    /** 直接设置倒计时时长（毫秒），用于时间选择器精确自选 */
    fun applyCdDuration(ms: Long) {
        cdDuration = ms.coerceIn(1000L, 24 * 3600_000L)
        cdRemaining = 0
        cdRunning = false
        cdEndAt = 0
    }

    fun changeWorkMinutes(delta: Int) {
        workMinutes = (workMinutes + delta).coerceIn(1, 180)
        if (pomoIsWork && !pomoRunning) initPomodoro()
    }

    fun changeRestMinutes(delta: Int) {
        restMinutes = (restMinutes + delta).coerceIn(1, 60)
        if (!pomoIsWork && !pomoRunning) initPomodoro()
    }

    companion object {
        fun formatClock(ms: Long, hundredths: Boolean): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) {
                if (hundredths) String.format("%02d:%02d:%02d.%02d", h, m, s, (ms % 1000) / 10)
                else String.format("%02d:%02d:%02d", h, m, s)
            } else {
                if (hundredths) String.format("%02d:%02d.%02d", m, s, (ms % 1000) / 10)
                else String.format("%02d:%02d", m, s)
            }
        }
    }
}
