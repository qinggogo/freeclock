package com.example.flipclock

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager

class MainActivity : Activity() {

    private lateinit var clock: FlipClockView
    private val prefs by lazy { getSharedPreferences("flipclock", Context.MODE_PRIVATE) }
    private var settingsDialog: AlertDialog? = null
    private val brightnessValues = floatArrayOf(0.15f, 0.35f, 0.60f)
    private var currentBrightness = -1f
    private var batteryRegistered = false
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                clock.batteryLevel = (level * 100 / scale).coerceIn(0, 100)
                clock.invalidate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        clock = FlipClockView(this)
        setContentView(clock)

        clock.is24Hour = prefs.getBoolean("key_24h", true)
        clock.showSeconds = prefs.getBoolean("key_sec", true)
        clock.powerSave = prefs.getBoolean("key_power", false)
        clock.keepAwake = prefs.getBoolean("key_awake", false)
        clock.saverDelayMs = prefs.getLong("key_delay", 15000L)
        clock.saverBrightness = prefs.getFloat("key_brightness", 0.35f)
        clock.theme = prefs.getInt("key_theme", FlipClockView.THEME_FLIP)
        clock.fontIndex = prefs.getInt("key_font", 0)
        clock.engine.workMinutes = prefs.getInt("key_pomo_work", 25)
        clock.engine.restMinutes = prefs.getInt("key_pomo_rest", 5)
        clock.engine.cdDuration = prefs.getLong("key_cd_duration", 5 * 60_000L)
        clock.engine.initPomodoro()
        clock.flipSound = prefs.getBoolean("key_flip_sound", true)
        clock.showBattery = prefs.getBoolean("key_show_battery", true)
        updateBattery()

        // Android 13+ 需要通知权限才能用全屏通知拉起闹钟响铃界面
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        clock.onBrightnessChange = { value ->
            currentBrightness = value
            applyBrightness(value)
        }
        clock.onBrightnessAdjust = { delta ->
            val base = if (currentBrightness < 0) 0.5f else currentBrightness
            val next = (base + delta).coerceIn(0.05f, 1f)
            currentBrightness = next
            applyBrightness(next)
            toast("亮度 ${(next * 100).toInt()}%")
        }
        clock.onKeepAwakeChange = { on ->
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        clock.onFullscreenRequest = { hideSystemUI() }
        clock.onSettingsRequest = { showSettings() }
        clock.onModeChanged = { m ->
            val name = when (m) {
                FlipClockView.MODE_CLOCK -> "时钟"
                FlipClockView.MODE_POMODORO -> "番茄钟"
                FlipClockView.MODE_STOPWATCH -> "正计时"
                else -> "倒计时"
            }
            toast(name)
        }
        clock.onTimerFinished = { m ->
            val msg = when (m) {
                FlipClockView.MODE_POMODORO -> "番茄钟结束，该休息一下了"
                FlipClockView.MODE_COUNTDOWN -> "倒计时结束"
                else -> "计时结束"
            }
            notifyTimerDone(msg)
        }
        clock.onCountdownSchedule = { endAt ->
            if (endAt > 0) AlarmHelper.scheduleTimer(this, endAt)
            else AlarmHelper.cancelTimer(this)
        }
        clock.onRingingTouched = {
            if (AlarmReceiver.isRinging) {
                stopRingingIfAny()
                true
            } else false
        }
        clock.applySettings()
    }

    override fun onResume() {
        super.onResume()
        clock.onResume()
        hideSystemUI()
    }

    override fun onPause() {
        clock.onPause()
        super.onPause()
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun updateBattery() {
        if (batteryRegistered) return
        val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
        batteryRegistered = true
    }

    override fun onDestroy() {
        if (batteryRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
            batteryRegistered = false
        }
        super.onDestroy()
    }

    private fun applyBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 倒计时时长选择器：时 / 分 / 秒三个滚轮，自选任意时长后保存 */
    private fun showCountdownPicker(anchor: android.widget.Button, onPicked: () -> Unit) {
        val density = resources.displayMetrics.density
        val cur = clock.engine.cdDuration
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        fun picker(max: Int, value: Int): android.widget.NumberPicker = android.widget.NumberPicker(this).apply {
            minValue = 0
            this.maxValue = max
            this.value = value.coerceIn(0, max)
        }
        val hP = picker(23, (cur / 3_600_000L).toInt())
        val mP = picker(59, ((cur % 3_600_000L) / 60_000L).toInt())
        val sP = picker(59, ((cur % 60_000L) / 1000L).toInt())
        fun unit(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFDDDDDD.toInt())
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
        }
        box.addView(hP); box.addView(unit("时"))
        box.addView(mP); box.addView(unit("分"))
        box.addView(sP); box.addView(unit("秒"))

        AlertDialog.Builder(this)
            .setTitle("倒计时时长")
            .setView(box)
            .setNegativeButton(R.string.alarm_cancel, null)
            .setPositiveButton(R.string.alarm_confirm) { _, _ ->
                val ms = (hP.value * 3_600_000L) + (mP.value * 60_000L) + (sP.value * 1000L)
                clock.engine.applyCdDuration(ms)
                prefs.edit().putLong("key_cd_duration", clock.engine.cdDuration).apply()
                onPicked()
                toast("倒计时已设为 ${TimerEngine.formatClock(clock.engine.cdDuration, false)}")
            }
            .show()
    }

    /** 计时结束提醒：真正响铃（复用闹钟的铃声+振动+通知，可点通知停止） */
    private fun notifyTimerDone(msg: String) {
        AlarmReceiver.ringNow(this, msg)
    }

    /** 用户主动停止响铃（计时界面轻触/返回时） */
    private fun stopRingingIfAny() {
        if (AlarmReceiver.isRinging) {
            AlarmReceiver.stopRinging()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }
    }

    private fun showSettings() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), 0)
        }

        // 主题选择
        val themeLabel = TextView(this).apply {
            text = getString(R.string.theme_label)
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        container.addView(themeLabel)
        val themeNames = arrayOf(
            getString(R.string.theme_flip),
            getString(R.string.theme_digital),
            getString(R.string.theme_minimal)
        )
        val themeSpinner = Spinner(this)
        val themeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themeNames)
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        themeSpinner.adapter = themeAdapter
        themeSpinner.setSelection(clock.theme.coerceIn(0, 2))
        themeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("key_theme", position).apply()
                clock.theme = position
                clock.applySettings()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        container.addView(themeSpinner)

        // 字体样式选择
        val fontLabel = TextView(this).apply {
            text = getString(R.string.font_label)
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(fontLabel)
        val fontNames = arrayOf(
            getString(R.string.font_condensed),
            getString(R.string.font_condensed_bold),
            getString(R.string.font_sans_bold),
            getString(R.string.font_sans),
            getString(R.string.font_serif),
            getString(R.string.font_mono)
        )
        val fontSpinner = Spinner(this)
        val fontAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fontNames)
        fontAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fontSpinner.adapter = fontAdapter
        fontSpinner.setSelection(clock.fontIndex.coerceIn(0, FlipClockView.FONT_COUNT - 1))
        fontSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("key_font", position).apply()
                clock.fontIndex = position
                clock.applySettings()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        container.addView(fontSpinner)

        // 计时参数设置
        val timerLabel = TextView(this).apply {
            text = getString(R.string.timer_settings)
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
            setPadding(0, (14 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(timerLabel)
        val pomoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val workBtn = Button(this).apply { text = "专注 ${clock.engine.workMinutes} 分"; textSize = 13f }
        val restBtn = Button(this).apply { text = "休息 ${clock.engine.restMinutes} 分"; textSize = 13f }
        fun cdLabel(): String {
            val ms = clock.engine.cdDuration
            val h = ms / 3_600_000L
            val m = (ms % 3_600_000L) / 60_000L
            val s = (ms % 60_000L) / 1000L
            return when {
                h > 0 -> "倒计时 ${h}时${m}分"
                s > 0 -> "倒计时 ${m}分${s}秒"
                else -> "倒计时 ${m} 分"
            }
        }
        val cdBtn = Button(this).apply {
            text = cdLabel()
            textSize = 13f
        }
        workBtn.setOnClickListener {
            clock.engine.changeWorkMinutes(if (it.tag == "+1") -1 else 1)
            it.tag = if (it.tag == "+1") null else "+1"
            workBtn.text = "专注 ${clock.engine.workMinutes} 分"
            prefs.edit().putInt("key_pomo_work", clock.engine.workMinutes).apply()
        }
        restBtn.setOnClickListener {
            clock.engine.changeRestMinutes(if (it.tag == "+1") -1 else 1)
            it.tag = if (it.tag == "+1") null else "+1"
            restBtn.text = "休息 ${clock.engine.restMinutes} 分"
            prefs.edit().putInt("key_pomo_rest", clock.engine.restMinutes).apply()
        }
        cdBtn.setOnClickListener { showCountdownPicker(cdBtn) { cdBtn.text = cdLabel() } }
        pomoRow.addView(workBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pomoRow.addView(restBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pomoRow.addView(cdBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(pomoRow)

        fun addCheck(label: String, key: String, default: Boolean, onChanged: (Boolean) -> Unit) {
            val cb = CheckBox(this).apply {
                text = label
                isChecked = prefs.getBoolean(key, default)
                setTextColor(0xFFDDDDDD.toInt())
                textSize = 15f
            }
            cb.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChanged(checked)
                clock.applySettings()
            }
            container.addView(cb)
        }

        addCheck(getString(R.string.opt_24h), "key_24h", true) { clock.is24Hour = it }
        addCheck(getString(R.string.opt_seconds), "key_sec", true) {
            clock.showSeconds = it
        }
        addCheck(getString(R.string.opt_power), "key_power", false) { clock.powerSave = it }
        addCheck(getString(R.string.opt_keep_awake), "key_awake", false) { clock.keepAwake = it }

        val label = TextView(this).apply {
            text = getString(R.string.opt_saver_delay)
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(label)

        val delayLabel = arrayOf(
            getString(R.string.sec_15), getString(R.string.sec_30),
            getString(R.string.sec_60), getString(R.string.never)
        )
        val delayValues = longArrayOf(15000L, 30000L, 60000L, 0L)
        val currentDelay = prefs.getLong("key_delay", 15000L)
        val selectedIndex = delayValues.indexOf(currentDelay).let { if (it < 0) 1 else it }

        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, delayLabel)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selectedIndex)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = delayValues[position]
                prefs.edit().putLong("key_delay", value).apply()
                clock.saverDelayMs = value
                clock.resetIdle()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        container.addView(spinner)

        // 屏保亮度
        val brightLabel = TextView(this).apply {
            text = getString(R.string.opt_saver_brightness)
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(brightLabel)
        val brightNames = arrayOf(
            getString(R.string.bright_low),
            getString(R.string.bright_medium),
            getString(R.string.bright_high)
        )
        val curBright = prefs.getFloat("key_brightness", 0.35f)
        val brightIndex = (brightnessValues.indices.firstOrNull { brightnessValues[it] == curBright } ?: 1)
        val brightSpinner = Spinner(this)
        val brightAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, brightNames)
        brightAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        brightSpinner.adapter = brightAdapter
        brightSpinner.setSelection(brightIndex)
        brightSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = brightnessValues[position]
                prefs.edit().putFloat("key_brightness", value).apply()
                clock.saverBrightness = value
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        container.addView(brightSpinner)

        // 闹钟条目
        val alarmState = if (AlarmHelper.isEnabled(this)) {
            val (h, m) = AlarmHelper.getTime(this)
            "${getString(R.string.alarm)}：${getString(R.string.alarm_on)}  ${String.format("%02d:%02d", h, m)}"
        } else {
            "${getString(R.string.alarm)}：${getString(R.string.alarm_off)}"
        }
        val alarmBtn = Button(this).apply {
            text = alarmState
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
        }
        alarmBtn.setOnClickListener { showAlarmDialog() }
        container.addView(alarmBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * density).toInt() })

        val note = TextView(this).apply {
            text = "手势：左右滑切换 时钟/番茄钟/正计时/倒计时；上滑开设置；双指上下调亮度。\n计时界面：轻触开始/暂停，双击重置。点「全屏」隐藏按钮，轻触恢复。"
            setTextColor(0xFF777777.toInt())
            textSize = 12f
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        container.addView(note)

        // 翻页声音开关
        addCheck(getString(R.string.opt_flip_sound), "key_flip_sound", true) { clock.flipSound = it }
        val testSoundBtn = Button(this).apply {
            text = getString(R.string.test_flip_sound)
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
        }
        testSoundBtn.setOnClickListener { clock.testFlipSound() }
        container.addView(testSoundBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() })
        // 电量显示开关
        addCheck(getString(R.string.opt_show_battery), "key_show_battery", true) { clock.showBattery = it }

        // 内容较多，用 ScrollView 包裹，避免秒数/闹钟等项被挤出屏幕
        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        settingsDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showAlarmDialog() {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val (saveH, saveM) = AlarmHelper.getTime(this)
        val picker = TimePicker(this).apply {
            setIs24HourView(true)
            hour = saveH
            minute = saveM
        }
        box.addView(picker)

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val toggle = CheckBox(this).apply {
            text = getString(R.string.alarm_enabled_label)
            isChecked = AlarmHelper.isEnabled(this@MainActivity)
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 15f
        }
        switchRow.addView(toggle)
        box.addView(switchRow)

        AlertDialog.Builder(this)
            .setTitle(R.string.alarm_set)
            .setView(box)
            .setPositiveButton(R.string.alarm_confirm) { _, _ ->
                if (toggle.isChecked) {
                    val h: Int
                    val m: Int
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        h = picker.hour; m = picker.minute
                    } else {
                        @Suppress("DEPRECATION")
                        h = picker.currentHour; @Suppress("DEPRECATION") m = picker.currentMinute
                    }
                    AlarmHelper.setAlarm(this, h, m)
                } else {
                    AlarmHelper.disableAlarm(this)
                }
                settingsDialog?.dismiss()
                // 重新打开设置，刷新闹钟状态文字
                showSettings()
            }
            .setNegativeButton(R.string.alarm_cancel, null)
            .show()
    }
}
