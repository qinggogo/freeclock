package com.example.flipclock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FlipClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val THEME_FLIP = 0
        const val THEME_DIGITAL = 1
        const val THEME_MINIMAL = 2

        const val MODE_CLOCK = 0
        const val MODE_POMODORO = 1
        const val MODE_STOPWATCH = 2
        const val MODE_COUNTDOWN = 3
        const val MODE_COUNT = 4

        const val FONT_COUNT = 6
        // 字体族名：系统内置，无需打包字体文件
        const val FAMILY_CONDENSED = "sans-serif-condensed"
        const val FAMILY_NORMAL = "sans-serif"
        const val FAMILY_SERIF = "serif"
        const val FAMILY_MONO = "monospace"
    }

    /** 根据索引返回字体族、字重、横向压缩比；condensed 用于逼近参考图的窄体字形 */
    private fun fontSpec(index: Int): Triple<String, Int, Float> = when (index) {
        0 -> Triple(FAMILY_CONDENSED, Typeface.NORMAL, 1.0f) // 纤窄（推荐·类参考图）
        1 -> Triple(FAMILY_CONDENSED, Typeface.BOLD, 1.0f)   // 纤窄加粗
        2 -> Triple(FAMILY_NORMAL, Typeface.BOLD, 1.0f)      // 标准黑体
        3 -> Triple(FAMILY_NORMAL, Typeface.NORMAL, 1.0f)    // 标准常规
        4 -> Triple(FAMILY_SERIF, Typeface.NORMAL, 1.0f)     // 衬线
        else -> Triple(FAMILY_MONO, Typeface.NORMAL, 1.0f)   // 等宽
    }

    /** 把当前字体应用到所有数字画笔；textScaleX 也在此统一控制 */
    private fun applyFont() {
        val (family, style, scaleX) = fontSpec(fontIndex)
        val tf = Typeface.create(family, style)
        textPaint.typeface = tf
        textPaint.textScaleX = scaleX
        digitalPaint.typeface = tf
        digitalPaint.textScaleX = scaleX
        secDigitPaint.typeface = tf
        secDigitPaint.textScaleX = scaleX
        secPaint.typeface = tf
        secPaint.textScaleX = scaleX
        // 字号/间距按新字体重新自适应
        computeDigitLayout()
        invalidate()
    }

    var theme = THEME_FLIP
    var mode = MODE_CLOCK
    var is24Hour = true
    var showSeconds = true
    var powerSave = false
    var keepAwake = false
    var saverDelayMs = 15000L
    var saverBrightness = 0.35f
    var flipSound = false
    var showBattery = true
    var batteryLevel = -1   // 0..100，-1 表示未知
    /** 字体样式索引，见 fontSpec() */
    var fontIndex = 0
        set(value) { field = value.coerceIn(0, FONT_COUNT - 1); applyFont() }

    val engine = TimerEngine()

    private var flipSoundData: ShortArray? = null
    private var lastFlipSoundAt = 0L

    var onBrightnessChange: ((Float) -> Unit)? = null
    var onKeepAwakeChange: ((Boolean) -> Unit)? = null
    var onFullscreenRequest: (() -> Unit)? = null
    var onSettingsRequest: (() -> Unit)? = null
    var onModeChanged: ((Int) -> Unit)? = null
    var onTimerFinished: ((Int) -> Unit)? = null
    /** 倒计时开始/恢复：回调绝对结束时刻(毫秒时间戳)，由外部预约到点响铃；传 0 表示取消 */
    var onCountdownSchedule: ((Long) -> Unit)? = null
    /** 响铃中用户是否点了屏幕（用于轻触停止响铃），返回 true 表示已消费本次点击 */
    var onRingingTouched: (() -> Boolean)? = null

    var isSaver: Boolean = false
        private set
    var buttonsVisible = true
        private set

    private fun showingSeconds(): Boolean = showSeconds && !powerSave && !isSaver

    /** 生成一段短促的机械翻页"咔哒"声 PCM（噪声瞬态 + 低频"咚"），无需音频文件 */
    private fun buildFlipSound(): ShortArray {
        val rate = 44100
        val durationMs = 70
        val n = rate * durationMs / 1000
        val data = ShortArray(n)
        var seed = 12345L
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            // 两段包络：前 12ms 是"咔"的噪声瞬态，后面是短促的"哒"低频
            val clickEnv = kotlin.math.exp(-t * 210f)
            val bodyEnv = kotlin.math.exp(-t * 42f) * 0.75f
            seed = (seed * 1103515245 + 12345L) and 0x7fffffffL
            val noise = ((seed % 2000).toFloat() / 1000f - 1f)
            val tone = Math.sin(2.0 * Math.PI * 190.0 * t).toFloat()
            val sample = ((noise * clickEnv * 0.55f) + (tone * bodyEnv * 0.75f)).coerceIn(-1f, 1f)
            data[i] = (sample * 32767f * 0.92f).toInt().toShort()
        }
        return data
    }

    /**
     * 播放翻页声。
     * 关键点：必须带 FLAG_BYPASS_INTERRUPTION_POLICY，否则手机处于勿扰模式时
     * STREAM_MUSIC 被静音（实测 MIUI 勿扰屏蔽流 0x6f，包含 STREAM_MUSIC，
     * 但不包含 STREAM_ALARM——这正是闹钟能响而翻页没声的原因）。
     */
    private fun playFlipSound(test: Boolean = false) {
        if (!test && (!flipSound || theme != THEME_FLIP)) return
        val now = System.currentTimeMillis()
        if (!test && now - lastFlipSoundAt < 55L) return // 限流，避免同一秒多位同时翻页叠音
        lastFlipSoundAt = now
        try {
            if (flipSoundData == null) flipSoundData = buildFlipSound()
            val pcm = flipSoundData!!
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                // FLAG_BYPASS_INTERRUPTION_POLICY(0x1) + FLAG_BYPASS_MUTE(0x2)
                // SDK stub 未公开这两个常量，直接用数值
                .setFlags(0x1 or 0x2)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            // MODE_STATIC：一次性写入全部数据再 play，短音效最可靠
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            // MODE_STATIC 下 AudioTrack 构造后状态是 STATE_NO_STATIC_DATA(2)，
            // 必须写完 PCM 才会变成 STATE_INITIALIZED(1) —— 旧代码在这里判断 ==1 直接 return，
            // 导致 write()/play() 从未执行，这就是"翻页一直没声音"的真正原因。
            if (track.state != AudioTrack.STATE_UNINITIALIZED) {
                val written = track.write(pcm, 0, pcm.size)
                track.play()
                Log.d("FlipClock", "flipSound test=$test stateAfterWrite=${track.state} written=$written/${pcm.size} playState=${track.playState}")
                handler.postDelayed({
                    try { track.stop(); track.release() } catch (e: Exception) {}
                }, 160L)
            } else {
                Log.w("FlipClock", "flipSound AudioTrack UNINITIALIZED state=${track.state}")
                track.release()
            }
        } catch (e: Exception) {
            Log.e("FlipClock", "flipSound failed", e)
        }
    }

    /** 设置面板里的"测试声音"按钮调用，绕过开关与主题限制 */
    fun testFlipSound() = playFlipSound(test = true)

    private inner class FlipDigit {
        var current = '0'
        var previous = '0'
        var animStart = 0L
        var animating = false
        fun set(c: Char, now: Long, animate: Boolean) {
            if (c == current) return
            previous = current
            current = c
            if (animate) {
                animating = true
                animStart = now
                playFlipSound()
            } else animating = false
        }
        fun progress(now: Long): Float {
            if (!animating) return 1f
            val v = (now - animStart) / 320f
            if (v >= 1f) { animating = false; return 1f }
            if (v < 0f) return 0f
            return v
        }
    }

    private val digits = Array(6) { FlipDigit() }
    private var ampm = ""
    private var dateText = ""

    private val handler = Handler(Looper.getMainLooper())
    private val camera = Camera()
    private val matrix = Matrix()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E3E2E6")   // 参考图大数字颜色
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(FAMILY_CONDENSED, Typeface.NORMAL)
        textScaleX = 1f                       // 字体/缩放统一由 applyFont() 控制
    }
    private val digitalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F7F7F7")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB454")
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    // 分钟卡右下角的秒数小翻页格：两格独立底色 + 中缝 + 窄体数字（按参考图测量）
    private val secCellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333"); style = Paint.Style.FILL
    }
    private val secDigitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C5C6D0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val secSeamPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val colonSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A5A5A")
        textAlign = Paint.Align.CENTER
        strokeWidth = 2f
    }
    private val cardTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C2C2C"); style = Paint.Style.FILL
    }
    private val cardBottomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#191919"); style = Paint.Style.FILL
    }
    private val bigCardTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F1F1F"); style = Paint.Style.FILL
    }
    private val bigCardBottomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F1F1F"); style = Paint.Style.FILL
    }
    private val batteryFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B8B8B8"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val batteryFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9FE1CB"); style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDDDDD"); style = Paint.Style.FILL
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080"); textAlign = Paint.Align.CENTER
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB454"); style = Paint.Style.STROKE; strokeWidth = 10f
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A"); style = Paint.Style.STROKE; strokeWidth = 10f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6A6A6A"); textAlign = Paint.Align.CENTER
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
    }
    private val btnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E1E1E"); style = Paint.Style.FILL
    }
    private val dimPaint = Paint().apply { color = Color.parseColor("#A6000000"); style = Paint.Style.FILL }

    private var cardW = 0f
    private var cardH = 0f
    private var startX = 0f
    private var startY = 0f
    private var colonW = 0f
    private var gapPx = 0f

    // 翻页主题（截图样式）专用布局
    private var flipCardW = 0f
    private var flipCardH = 0f
    private var flipGap = 0f
    private var flipTopY = 0f
    private var flipDigitPaintSize = 0f
    private var flipDigitCellW = 0f   // 大数字单个字格的实测宽度（随字体自适应）
    private var flipSecCardW = 0f
    private var flipSecCardH = 0f
    private var flipPortrait = false
    private var flipSecondX = 0f   // 秒数小卡在分钟卡内的右下角坐标
    private var flipSecondY = 0f
    private var flipSecondSize = 0f
    private var flipSecCellW = 0f  // 单个秒格宽
    private var flipSecGap = 0f    // 两秒格间距
    private var flipSecRight = 0f  // 距分钟卡右边
    private var flipSecBottom = 0f // 距分钟卡下边
    private val btnRects = arrayOf(RectF(), RectF(), RectF())

    private var animatorRunning = false
    private var driftIndex = 0
    private var offsetX = 0f
    private var offsetY = 0f
    private val drifts = arrayOf(
        intArrayOf(0, 0), intArrayOf(6, -4), intArrayOf(0, -9),
        intArrayOf(-6, -4), intArrayOf(5, 6), intArrayOf(-5, 6)
    )
    private val weekDays = arrayOf("日", "一", "二", "三", "四", "五", "六")

    private var lastTickUptime = 0L
    private var finishedNotified = false
    private var lastCdRunning = false  // 用于检测倒计时开始/停止边沿，同步响铃预约

    /** 检测倒计时运行状态变化，通知外部预约或取消到点响铃 */
    private fun syncCountdownAlarm() {
        if (lastCdRunning != engine.cdRunning) {
            lastCdRunning = engine.cdRunning
            onCountdownSchedule?.invoke(if (engine.cdRunning) engine.cdEndAt else 0L)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    private fun computeLayout() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val pad = 16f * density
        gapPx = 5f * density
        val ratio = 0.30f
        val nDigits = if (showingSeconds()) 6 else 4
        val nColons = if (showingSeconds()) 2 else 1
        val elems = nDigits + nColons
        val avail = w - 2 * pad
        cardW = (avail - (elems - 1) * gapPx) / (nDigits + nColons * ratio)
        colonW = cardW * ratio
        cardH = min(cardW * 1.55f, h * 0.44f)
        val totalW = nDigits * cardW + nColons * colonW + (elems - 1) * gapPx
        startX = (w - totalW) / 2f
        startY = h * 0.28f

        flipPortrait = h > w
        if (!flipPortrait) {
            // 横屏：严格复刻参考截图（2340x1080 → 两卡各 827x827，y=127，间距 66px）
            // 不再用 topBar/bottomBar 压缩可用高度，那是之前卡片变小的根因
            flipGap = w * 0.0282f
            val maxByWidth = (w - 2 * pad - flipGap) / 2f
            val cardSize = min(h * 0.766f, maxByWidth)
            flipCardW = cardSize
            flipCardH = cardSize
            flipTopY = (h - cardSize) / 2f
            flipDigitPaintSize = cardSize * 0.80f
        } else {
            // 竖屏：两卡上下堆叠，宽度尽量用满
            val topBarH = max(44f * density, h * 0.09f)
            val bottomBarH = 70f * density
            val usableH = h - topBarH - bottomBarH
            flipGap = max(18f * density, h * 0.025f)
            val maxW = w - 2 * pad - 8f * density
            val cardWp = min(maxW, usableH * 0.52f)
            flipCardW = cardWp
            flipCardH = cardWp * 1.05f
            val totalH = flipCardH * 2 + flipGap
            flipTopY = topBarH + (usableH - totalH) / 2f
            flipDigitPaintSize = flipCardH * 0.62f
        }
        // 秒数小翻页格：总高约卡高 15%，格宽约格高 0.58，距右边/下边各约 4%
        flipSecondSize = flipCardH * 0.15f
        flipSecCellW = flipSecondSize * 0.58f
        flipSecGap = flipSecCellW * 0.16f
        flipSecRight = flipCardW * 0.04f
        flipSecBottom = flipCardH * 0.04f
        flipSecondX = flipCardW - flipSecRight - (flipSecCellW * 2 + flipSecGap)
        flipSecondY = flipCardH - flipSecBottom - flipSecondSize

        // 数字布局按当前字体的实测字形宽度自适应，避免换字体后间距错乱
        computeDigitLayout()

        val bw = 84f * density
        val bh = 32f * density
        // 横屏卡片下沿离屏幕底只有约 126px，按钮放到最底部避免压住卡片
        val by = if (!flipPortrait) h - bh - 8f * density else h - bh - 22f * density
        val totalBw = bw * 3 + gapPx * 2
        var bx = (w - totalBw) / 2f
        for (i in 0..2) {
            btnRects[i].set(bx, by, bx + bw, by + bh)
            bx += bw + gapPx
        }
    }

    private val digitBounds = Rect()

    /**
     * 按当前字体的真实「墨迹」比例计算大数字字号与字格宽度。
     * 关键：必须用 getTextBounds 量数字的实际墨迹高度，而不是 fontMetrics 的
     * ascent/descent（后者含行距，约为字号的 1.2 倍，会让反推的字号偏小 40%）。
     * 参考图实测：数字墨迹高 ≈ 卡高的 0.567，两位数字总宽 ≈ 卡宽的 0.62。
     * 取「高度目标字号」与「宽度不溢出字号」的较小值，兼顾饱满与安全。
     */
    private fun computeDigitLayout() {
        if (flipCardW <= 0f || flipCardH <= 0f) return
        val probe = 100f
        textPaint.textSize = probe
        val w100 = textPaint.measureText("0").coerceAtLeast(1f) // advance 宽，用于排布间距
        textPaint.getTextBounds("0", 0, 1, digitBounds)
        val inkH100 = digitBounds.height().toFloat().coerceAtLeast(1f) // 真实墨迹高

        // 以墨迹高度为准反推字号：字号 * (inkH100/probe) = 卡高 * 0.567
        val sizeByHeight = (flipCardH * 0.567f) / inkH100 * probe
        // 宽度约束：两位 advance + 6% 间距 ≤ 卡宽 * 0.80，留边距
        val digitWTarget = (flipCardW * 0.80f) / (2f + 0.06f)
        val sizeByWidth = digitWTarget / w100 * probe
        flipDigitPaintSize = min(sizeByHeight, sizeByWidth)
        flipDigitCellW = w100 / probe * flipDigitPaintSize
        // 极淡投影让数字微微浮起，增强翻页卡片的拟物层次（半径随字号缩放）
        textPaint.setShadowLayer(flipDigitPaintSize * 0.018f, 0f, flipDigitPaintSize * 0.016f,
            Color.parseColor("#4D000000"))
    }

    fun applySettings() {
        computeLayout()
        notifyKeepAwake()
        updateTime()
        invalidate()
    }

    private fun notifyKeepAwake() {
        onKeepAwakeChange?.invoke(keepAwake && !powerSave)
    }

    fun switchMode(newMode: Int) {
        if (newMode == mode || newMode !in 0 until MODE_COUNT) return
        // 离开倒计时时取消到点响铃预约并复位，避免看不见时仍在后台响
        if (mode == MODE_COUNTDOWN) {
            engine.reset(MODE_COUNTDOWN)
            lastCdRunning = false
            onCountdownSchedule?.invoke(0L)
        }
        mode = newMode
        finishedNotified = false
        buttonsVisible = true
        onModeChanged?.invoke(mode)
        computeLayout()
        startTimerLoop()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        if (mode == MODE_CLOCK) {
            when (theme) {
                THEME_FLIP -> drawTimeFlip(canvas, now)
                THEME_DIGITAL -> drawTimeDigital(canvas, now)
                else -> drawTimeMinimal(canvas, now)
            }
        } else {
            drawTimerMode(canvas)
        }
        canvas.restore()
        if (isSaver) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        } else if (buttonsVisible) {
            drawButtons(canvas)
        }
    }

    // ---------- 主题一：翻页（截图样式：顶部信息栏 + 两张大卡片 + 秒角标）----------
    private fun drawTimeFlip(canvas: Canvas, now: Long) {
        val alpha = if (isSaver) 165 else 255
        drawTopInfoBar(canvas, alpha)

        val c = Calendar.getInstance()
        val hour24 = c.get(Calendar.HOUR_OF_DAY)
        val hour = if (is24Hour) hour24 else { val v = hour24 % 12; if (v == 0) 12 else v }
        val hh = two(hour)
        val mm = two(c.get(Calendar.MINUTE))
        val ss = two(c.get(Calendar.SECOND))

        // 同步数字位（时分秒各两位）
        val animate = !(powerSave || isSaver)
        digits[0].set(hh[0], now, animate)
        digits[1].set(hh[1], now, animate)
        digits[2].set(mm[0], now, animate)
        digits[3].set(mm[1], now, animate)
        digits[4].set(ss[0], now, animate)
        digits[5].set(ss[1], now, animate)

        val hourLeft: Float
        val hourTop: Float
        val minLeft: Float
        val minTop: Float
        if (!flipPortrait) {
            val totalW = flipCardW * 2 + flipGap
            val left = (width - totalW) / 2f
            hourLeft = left
            hourTop = flipTopY
            minLeft = left + flipCardW + flipGap
            minTop = flipTopY
        } else {
            hourLeft = (width - flipCardW) / 2f
            hourTop = flipTopY
            minLeft = hourLeft
            minTop = flipTopY + flipCardH + flipGap
        }

        drawBigFlipCard(canvas, digits[0], digits[1], hourLeft, hourTop, flipCardW, flipCardH, now, alpha)
        drawBigFlipCard(canvas, digits[2], digits[3], minLeft, minTop, flipCardW, flipCardH, now, alpha)

        // 秒数：参考图是分钟卡右下角的"两格小翻页卡"，各有独立底色和中缝
        if (showingSeconds()) {
            drawSecondsSmallCard(
                canvas,
                minLeft + flipSecondX, minTop + flipSecondY,
                flipSecCellW * 2 + flipSecGap, flipSecondSize,
                now, alpha
            )
        }
    }

    /** 分钟卡右下角的两格小翻页秒卡（严格按参考截图的尺寸/配色） */
    private fun drawSecondsSmallCard(
        canvas: Canvas, l: Float, t: Float, w: Float, h: Float, now: Long, alpha: Int
    ) {
        val cellW = (w - flipSecGap) / 2f
        val mid = t + h / 2f
        val r = min(6f * resources.displayMetrics.density, cellW * 0.18f)
        secCellPaint.alpha = alpha
        secDigitPaint.alpha = alpha
        secSeamPaint.alpha = alpha
        for (i in 0..1) {
            val cl = l + i * (cellW + flipSecGap)
            // 上下两半，同色 #333333，圆角只在外侧
            canvas.drawRoundRect(RectF(cl, t, cl + cellW, mid + 1f), r, r, secCellPaint)
            canvas.drawRoundRect(RectF(cl, mid - 1f, cl + cellW, t + h), r, r, secCellPaint)
            drawSecDigit(canvas, digits[4 + i], cl, t, cellW, h, now, alpha)
            // 中缝：细黑线
            canvas.drawRect(cl + 1f, mid - 1f, cl + cellW - 1f, mid + 1f, secSeamPaint)
        }
    }

    private fun drawSecDigit(
        canvas: Canvas, d: FlipDigit, l: Float, t: Float,
        w: Float, h: Float, now: Long, alpha: Int
    ) {
        val p = d.progress(now)
        // 按实测字形比例自适应：数字高度约占格高 0.60，宽度不超过格宽 0.78
        val probe = 100f
        secDigitPaint.textSize = probe
        val w100 = secDigitPaint.measureText("0").coerceAtLeast(1f)
        val fm0 = secDigitPaint.fontMetrics
        val h100 = (fm0.descent - fm0.ascent).coerceAtLeast(1f)
        secDigitPaint.textSize = min((w * 0.78f) / w100 * probe, (h * 0.60f) / h100 * probe)
        drawSecHalf(canvas, d.current, l, t, w, h, true, alpha)
        val bottomChar = if (d.animating && p < 1f) d.previous else d.current
        drawSecHalf(canvas, bottomChar, l, t, w, h, false, alpha)
        if (d.animating && p < 1f) {
            if (p < 0.5f) drawSecFlap(canvas, d.previous, l, t, w, h, -90f * (p / 0.5f), true, alpha)
            else drawSecFlap(canvas, d.current, l, t, w, h, 90f * (1f - (p - 0.5f) / 0.5f), false, alpha)
        }
    }

    private fun drawSecHalf(
        canvas: Canvas, ch: Char, l: Float, t: Float,
        w: Float, h: Float, isTop: Boolean, alpha: Int
    ) {
        canvas.save()
        if (isTop) canvas.clipRect(l, t, l + w, t + h / 2f)
        else canvas.clipRect(l, t + h / 2f, l + w, t + h)
        val fm = secDigitPaint.fontMetrics
        val baseline = t + h / 2f - (fm.ascent + fm.descent) / 2f
        secDigitPaint.alpha = alpha
        canvas.drawText(ch.toString(), l + w / 2f, baseline, secDigitPaint)
        canvas.restore()
    }

    private fun drawSecFlap(
        canvas: Canvas, ch: Char, l: Float, t: Float,
        w: Float, h: Float, angle: Float, isTop: Boolean, alpha: Int
    ) {
        val cx = l + w / 2f
        val mid = t + h / 2f
        camera.save()
        camera.rotateX(angle)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-cx, -mid)
        matrix.postTranslate(cx, mid)
        canvas.save()
        if (isTop) canvas.clipRect(l, t - h, l + w, mid + 2)
        else canvas.clipRect(l, mid - 2, l + w, t + 2 * h)
        canvas.concat(matrix)
        val fm = secDigitPaint.fontMetrics
        val baseline = mid - (fm.ascent + fm.descent) / 2f
        secDigitPaint.alpha = alpha
        canvas.drawText(ch.toString(), cx, baseline, secDigitPaint)
        canvas.restore()
    }

    /** 顶部信息栏：月 / 日 / 周几 / 电量 */
    private fun drawTopInfoBar(canvas: Canvas, alpha: Int) {
        val density = resources.displayMetrics.density
        val c = Calendar.getInstance()
        val wd = weekDays[c.get(Calendar.DAY_OF_WEEK) - 1]
        var text = "${c.get(Calendar.MONTH) + 1} / ${c.get(Calendar.DAY_OF_MONTH)} / 周$wd"
        val paint = hintPaint
        paint.alpha = alpha
        paint.textSize = max(15f * density, height * 0.028f)
        paint.textAlign = Paint.Align.RIGHT
        paint.color = Color.parseColor("#B8B8B8")
        val y = max(30f * density, height * 0.055f)

        var rightEdge = width / 2f + paint.measureText(text) / 2f + 8f * density
        // 电量图标在日期右侧
        if (showBattery && batteryLevel >= 0) {
            val bw = 30f * density
            val bh = 15f * density
            val bx = rightEdge + 4f * density
            val by = y - bh / 2f
            batteryFramePaint.alpha = alpha
            canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 3f * density, 3f * density, batteryFramePaint)
            val innerW = (bw - 4f * density) * (batteryLevel / 100f)
            if (innerW > 0) {
                batteryFillPaint.color = if (batteryLevel <= 20) Color.parseColor("#E24B4A") else Color.parseColor("#9FE1CB")
                batteryFillPaint.alpha = alpha
                canvas.drawRoundRect(RectF(bx + 2f * density, by + 2f * density, bx + 2f * density + innerW, by + bh - 2f * density), 2f * density, 2f * density, batteryFillPaint)
            }
            // 电池正极凸起
            canvas.drawRoundRect(RectF(bx + bw, by + bh * 0.3f, bx + bw + 2.5f * density, by + bh * 0.7f), 1f * density, 1f * density, batteryFramePaint)
            rightEdge = bx + bw + 2.5f * density
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("$batteryLevel%", rightEdge + 30f * density, y + paint.textSize * 0.35f, paint)
        }
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.parseColor("#B8B8B8")
        canvas.drawText(text, width / 2f - (if (showBattery && batteryLevel >= 0) 34f * density else 0f), y + paint.textSize * 0.35f, paint)
    }

    /** 一张大翻页卡：两位数字并排，上下两半 + 中缝，数字翻转动画 */
    private fun drawBigFlipCard(
        canvas: Canvas, d1: FlipDigit, d2: FlipDigit,
        l: Float, t: Float, w: Float, h: Float, now: Long, alpha: Int
    ) {
        val r = max(10f * resources.displayMetrics.density, w * 0.045f)
        // 卡片上下两半（参考图实测同为 #1F1F1F，只有中缝是黑色）
        canvas.drawRoundRect(RectF(l, t, l + w, t + h / 2f + 2), r, r, bigCardTopPaint)
        canvas.drawRoundRect(RectF(l, t + h / 2f - 2, l + w, t + h), r, r, bigCardBottomPaint)

        textPaint.textSize = flipDigitPaintSize
        // 按实测字形宽度居中排布两位数字，间距随字体自适应，任何字体都不会挤在一起
        val gap = flipDigitCellW * 0.06f
        val pairW = flipDigitCellW * 2f + gap
        val pairLeft = l + (w - pairW) / 2f
        drawBigDigit(canvas, d1, pairLeft, t, flipDigitCellW, h, now, alpha)
        drawBigDigit(canvas, d2, pairLeft + flipDigitCellW + gap, t, flipDigitCellW, h, now, alpha)

        // 中缝：参考图约 11px 纯黑横带
        val seamH = h * 0.0133f
        canvas.drawRect(l + r * 0.35f, t + h / 2f - seamH / 2f,
            l + w - r * 0.35f, t + h / 2f + seamH / 2f, linePaint)
    }

    private fun drawBigDigit(
        canvas: Canvas, d: FlipDigit, l: Float, t: Float,
        w: Float, h: Float, now: Long, alpha: Int
    ) {
        val p = d.progress(now)
        textPaint.alpha = alpha
        drawHalf(canvas, d.current, l, t, w, h, true, alpha)
        val bottomChar = if (d.animating && p < 1f) d.previous else d.current
        drawHalf(canvas, bottomChar, l, t, w, h, false, alpha)
        if (d.animating && p < 1f) {
            if (p < 0.5f) drawFlap(canvas, d.previous, l, t, w, h, -90f * (p / 0.5f), true, alpha)
            else drawFlap(canvas, d.current, l, t, w, h, 90f * (1f - (p - 0.5f) / 0.5f), false, alpha)
        }
    }

    /** 秒角标小卡：两位并排，同款翻页 */
    private fun drawSmallFlipCard(
        canvas: Canvas, d1: FlipDigit, d2: FlipDigit,
        l: Float, t: Float, w: Float, h: Float, now: Long, alpha: Int
    ) {
        val r = max(5f * resources.displayMetrics.density, w * 0.06f)
        canvas.drawRoundRect(RectF(l, t, l + w, t + h / 2f + 1), r, r, bigCardTopPaint)
        canvas.drawRoundRect(RectF(l, t + h / 2f - 1, l + w, t + h), r, r, bigCardBottomPaint)
        val savedSize = textPaint.textSize
        textPaint.textSize = h * 0.72f
        val savedColor = textPaint.color
        textPaint.color = Color.parseColor("#C8C8C8")
        val cellW = w / 2f
        drawBigDigit(canvas, d1, l, t, cellW, h, now, alpha)
        drawBigDigit(canvas, d2, l + cellW, t, cellW, h, now, alpha)
        canvas.drawRect(l + 2f, t + h / 2f - 0.5f, l + w - 2f, t + h / 2f + 0.5f, linePaint)
        textPaint.textSize = savedSize
        textPaint.color = savedColor
    }

    private fun drawDigit(
        canvas: Canvas, d: FlipDigit, l: Float, t: Float,
        w: Float, h: Float, now: Long, alpha: Int
    ) {
        val r = w * 0.10f
        canvas.drawRoundRect(RectF(l, t, l + w, t + h / 2f + 2), r, r, cardTopPaint)
        canvas.drawRoundRect(RectF(l, t + h / 2f - 2, l + w, t + h), r, r, cardBottomPaint)
        val p = d.progress(now)
        drawHalf(canvas, d.current, l, t, w, h, true, alpha)
        val bottomChar = if (d.animating && p < 1f) d.previous else d.current
        drawHalf(canvas, bottomChar, l, t, w, h, false, alpha)
        if (d.animating && p < 1f) {
            if (p < 0.5f) drawFlap(canvas, d.previous, l, t, w, h, -90f * (p / 0.5f), true, alpha)
            else drawFlap(canvas, d.current, l, t, w, h, 90f * (1f - (p - 0.5f) / 0.5f), false, alpha)
        }
        canvas.drawRect(l, t + h / 2f - 1f, l + w, t + h / 2f + 1f, linePaint)
    }

    private fun drawHalf(
        canvas: Canvas, ch: Char, l: Float, t: Float,
        w: Float, h: Float, isTop: Boolean, alpha: Int
    ) {
        canvas.save()
        if (isTop) canvas.clipRect(l, t, l + w, t + h / 2f)
        else canvas.clipRect(l, t + h / 2f, l + w, t + h)
        val fm = textPaint.fontMetrics
        val baseline = t + h / 2f - (fm.ascent + fm.descent) / 2f
        textPaint.alpha = alpha
        canvas.drawText(ch.toString(), l + w / 2f, baseline, textPaint)
        canvas.restore()
    }

    private fun drawFlap(
        canvas: Canvas, ch: Char, l: Float, t: Float,
        w: Float, h: Float, angle: Float, isTop: Boolean, alpha: Int
    ) {
        val cx = l + w / 2f
        val mid = t + h / 2f
        camera.save()
        camera.rotateX(angle)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-cx, -mid)
        matrix.postTranslate(cx, mid)
        canvas.save()
        if (isTop) canvas.clipRect(l, t - h, l + w, mid + 2)
        else canvas.clipRect(l, mid - 2, l + w, t + 2 * h)
        canvas.concat(matrix)
        val fm = textPaint.fontMetrics
        val baseline = mid - (fm.ascent + fm.descent) / 2f
        textPaint.alpha = alpha
        canvas.drawText(ch.toString(), cx, baseline, textPaint)
        canvas.restore()
    }

    /** 数字 / 极简主题共用：HH:MM 大字 + 秒数角标放在分钟右下角 */
    private fun drawTimeWithSecondsCorner(canvas: Canvas, minimal: Boolean) {
        val alpha = if (isSaver) (if (minimal) 130 else 150) else 255
        val landscape = width > height
        val c = Calendar.getInstance()
        val hour24 = c.get(Calendar.HOUR_OF_DAY)
        val hour = if (is24Hour) hour24 else { val v = hour24 % 12; if (v == 0) 12 else v }
        val main = String.format("%02d:%02d", hour, c.get(Calendar.MINUTE))
        val secStr = String.format("%02d", c.get(Calendar.SECOND))

        // 按实测字形宽度反推字号：让主串（含秒角标占位）占屏宽的 targetRatio，
        // 同时不超过屏高的 heightRatio，任何字体都能自适应、不溢出
        val probe = 100f
        digitalPaint.textSize = probe
        val mainW100 = digitalPaint.measureText(main).coerceAtLeast(1f) / probe // 每单位字号的串宽
        val secW100 = digitalPaint.measureText("00").coerceAtLeast(1f) / probe
        val heightRatio = if (landscape) 0.52f else (if (minimal) 0.26f else 0.30f)
        val targetRatio = 0.82f
        // 秒角标字号约主串的 0.34，折算成主串字号下的宽度占位
        val secExtra = if (showingSeconds()) secW100 * 0.34f + 0.06f else 0f
        val baseSize = min(
            (width * targetRatio) / (mainW100 + secExtra), // 宽度约束
            height * heightRatio                            // 高度约束
        )
        digitalPaint.textSize = baseSize
        // 字体由 applyFont() 统一设置，这里不再硬编码，保证用户选择生效
        digitalPaint.color = if (minimal) Color.parseColor("#F2F2F2") else Color.parseColor("#FAFAFA")
        digitalPaint.alpha = alpha

        val fm = digitalPaint.fontMetrics
        val baseline = height * (if (landscape) 0.50f else 0.44f) - (fm.ascent + fm.descent) / 2f
        canvas.drawText(main, width / 2f, baseline, digitalPaint)

        // 秒数角标：贴在主串右侧、字号约主字 1/3，底部与主串基线对齐
        if (showingSeconds()) {
            val mainW = digitalPaint.measureText(main)
            secPaint.textSize = baseSize * 0.34f
            secPaint.alpha = alpha
            val secX = width / 2f + mainW / 2f + baseSize * 0.06f
            canvas.drawText(secStr, secX, baseline, secPaint)
        }

        val density = resources.displayMetrics.density
        if (minimal) {
            hintPaint.textSize = 14f * density
            hintPaint.alpha = alpha
            canvas.drawText(dateText, width / 2f, height * (if (landscape) 0.50f else 0.44f) + baseSize * 0.72f, hintPaint)
        } else {
            drawDateLine(canvas, baseline)
        }
    }

    // ---------- 主题二：大号数字 ----------
    private fun drawTimeDigital(canvas: Canvas, now: Long) = drawTimeWithSecondsCorner(canvas, minimal = false)

    // ---------- 主题三：极简 ----------
    private fun drawTimeMinimal(canvas: Canvas, now: Long) = drawTimeWithSecondsCorner(canvas, minimal = true)

    private fun drawDateLine(canvas: Canvas) {
        val density = resources.displayMetrics.density
        datePaint.textSize = max(cardH * 0.17f, 12f * density)
        datePaint.alpha = if (isSaver) 150 else 255
        val prefix = if (!is24Hour) "$ampm · " else ""
        canvas.drawText(prefix + dateText, width / 2f, startY + cardH + cardH * 0.30f, datePaint)
    }

    private fun drawDateLine(canvas: Canvas, mainBaseline: Float) {
        val density = resources.displayMetrics.density
        datePaint.textSize = max(18f * density, height * 0.026f)
        datePaint.alpha = if (isSaver) 150 else 210
        val prefix = if (!is24Hour) "$ampm · " else ""
        canvas.drawText(prefix + dateText, width / 2f, mainBaseline + datePaint.textSize * 2.6f, datePaint)
        // 细分隔线
        val lineY = mainBaseline + datePaint.textSize * 1.1f
        val half = min(width * 0.18f, 160f * density)
        canvas.drawLine(width / 2f - half, lineY, width / 2f + half, lineY, colonSmallPaint)
    }

    // ---------- 计时模式绘制 ----------
    private fun drawTimerMode(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height * 0.44f

        val label = when (mode) {
            MODE_POMODORO -> if (engine.pomoIsWork) "专注 · 第 ${engine.pomoSessions + 1} 轮" else "休息"
            MODE_STOPWATCH -> "正计时"
            else -> "倒计时"
        }
        hintPaint.textSize = 15f * density
        hintPaint.alpha = 255
        canvas.drawText(label, cx, cy - height * 0.20f, hintPaint)

        val progress = when (mode) {
            MODE_POMODORO -> engine.pomoProgress()
            MODE_STOPWATCH -> if (engine.swLaps.isNotEmpty()) 1f else 0.35f
            else -> engine.cdProgress()
        }
        val radius = min(width, height) * 0.30f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        // 进度从 12 点方向顺时针
        canvas.drawArc(rect, -90f, 360f * progress, false, accentPaint)

        val timeStr = when (mode) {
            MODE_POMODORO -> engine.pomoText()
            MODE_STOPWATCH -> engine.swText()
            else -> if (engine.cdRemaining > 0 || engine.cdRunning) engine.cdText() else engine.cdSettingText()
        }
        digitalPaint.textSize = min(radius * 0.62f, width / (timeStr.length * 0.60f))
        digitalPaint.alpha = 255
        val fm = digitalPaint.fontMetrics
        canvas.drawText(timeStr, cx, cy - (fm.ascent + fm.descent) / 2f, digitalPaint)

        // 秒表计次：最近一圈
        if (mode == MODE_STOPWATCH && engine.swLaps.isNotEmpty()) {
            val lastLap = engine.swLaps.last()
            hintPaint.textSize = 13f * density
            hintPaint.alpha = 200
            canvas.drawText("计次 ${engine.swLaps.size}  ·  ${TimerEngine.formatClock(lastLap, true)}",
                cx, cy + radius * 0.55f, hintPaint)
        }

        val running = when (mode) {
            MODE_POMODORO -> engine.pomoRunning
            MODE_STOPWATCH -> engine.swRunning
            else -> engine.cdRunning
        }
        val action = if (running) "轻触暂停" else "轻触开始"
        hintPaint.textSize = 13f * density
        hintPaint.alpha = 255
        canvas.drawText("$action   ·   双击重置   ·   左右滑切换", cx, height - 74f * density, hintPaint)
    }

    private fun drawButtons(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val radius = 16f * density
        btnPaint.textSize = 13f * density
        val labels = if (mode == MODE_CLOCK)
            arrayOf("设置", "屏保", "全屏")
        else
            arrayOf("设置", "重置", "开始")
        for (i in 0..2) {
            val rect = btnRects[i]
            canvas.drawRoundRect(rect, radius, radius, btnBgPaint)
            btnPaint.alpha = 255
            canvas.drawText(labels[i], rect.centerX(), rect.centerY() + btnPaint.textSize * 0.35f, btnPaint)
        }
    }

    private fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

    private fun updateTime() {
        val c = Calendar.getInstance()
        val hour24 = c.get(Calendar.HOUR_OF_DAY)
        val hour = if (is24Hour) hour24 else { val v = hour24 % 12; if (v == 0) 12 else v }
        val hs = two(hour)
        val ms = two(c.get(Calendar.MINUTE))
        val ss = two(c.get(Calendar.SECOND))
        ampm = if (hour24 < 12) "AM" else "PM"
        dateText = "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月" +
                "${c.get(Calendar.DAY_OF_MONTH)}日 星期${weekDays[c.get(Calendar.DAY_OF_WEEK) - 1]}"

        val animate = !(powerSave || isSaver)
        val now = System.currentTimeMillis()
        digits[0].set(hs[0], now, animate)
        digits[1].set(hs[1], now, animate)
        digits[2].set(ms[0], now, animate)
        digits[3].set(ms[1], now, animate)
        digits[4].set(ss[0], now, animate)
        digits[5].set(ss[1], now, animate)

        if (mode == MODE_CLOCK) {
            invalidate()
            val n = if (showingSeconds()) 6 else 4
            var any = false
            for (i in 0 until n) if (digits[i].progress(now) < 1f) any = true
            if (any) ensureAnimator()
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateTime()
            val delay = 1000L - (System.currentTimeMillis() % 1000L) + 5L
            handler.postAtTime(this, SystemClock.uptimeMillis() + delay)
        }
    }

    private val animator = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val n = if (showingSeconds()) 6 else 4
            var any = false
            for (i in 0 until n) if (digits[i].progress(now) < 1f) any = true
            if (any) { invalidate(); handler.postDelayed(this, 16L) }
            else animatorRunning = false
        }
    }

    private fun ensureAnimator() {
        if (!animatorRunning) { animatorRunning = true; handler.post(animator) }
    }

    /** 计时模式的高频 tick（约 50ms），按真实流逝时间推进 */
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (mode != MODE_CLOCK) {
                val nowUp = SystemClock.uptimeMillis()
                val dt = if (lastTickUptime > 0) nowUp - lastTickUptime else 0L
                lastTickUptime = nowUp
                val finished = engine.tick(dt)
                if (mode == MODE_COUNTDOWN) syncCountdownAlarm()
                if (finished && !finishedNotified) {
                    finishedNotified = true
                    onTimerFinished?.invoke(mode)
                }
                invalidate()
                handler.postDelayed(this, 50L)
            }
        }
    }

    private fun startTimerLoop() {
        handler.removeCallbacks(timerRunnable)
        lastTickUptime = 0L
        if (mode != MODE_CLOCK) handler.post(timerRunnable)
    }

    private val saverRunnable = Runnable { enterSaver() }

    private val driftRunnable = object : Runnable {
        override fun run() {
            driftIndex = (driftIndex + 1) % drifts.size
            val density = resources.displayMetrics.density
            offsetX = drifts[driftIndex][0] * density
            offsetY = drifts[driftIndex][1] * density
            invalidate()
            handler.postDelayed(this, 60000L)
        }
    }

    fun enterSaver() {
        if (isSaver) return
        isSaver = true
        buttonsVisible = false
        computeLayout()
        onBrightnessChange?.invoke(saverBrightness)
        notifyKeepAwake()
        handler.removeCallbacks(driftRunnable)
        handler.postDelayed(driftRunnable, 60000L)
        updateTime()
        invalidate()
    }

    fun exitSaver() {
        if (!isSaver) return
        isSaver = false
        buttonsVisible = true
        computeLayout()
        onBrightnessChange?.invoke(-1f)
        notifyKeepAwake()
        handler.removeCallbacks(driftRunnable)
        offsetX = 0f
        offsetY = 0f
        resetIdle()
        updateTime()
        invalidate()
    }

    fun resetIdle() {
        handler.removeCallbacks(saverRunnable)
        if (!isSaver && saverDelayMs > 0) handler.postDelayed(saverRunnable, saverDelayMs)
    }

    fun onResume() {
        resetIdle()
        handler.removeCallbacks(clockRunnable)
        val delay = 1000L - (System.currentTimeMillis() % 1000L) + 5L
        handler.postAtTime(clockRunnable, SystemClock.uptimeMillis() + delay)
        startTimerLoop()
        updateTime()
    }

    fun onPause() {
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(animator)
        handler.removeCallbacks(timerRunnable)
        handler.removeCallbacks(saverRunnable)
        handler.removeCallbacks(driftRunnable)
        animatorRunning = false
    }

    // ---------- 手势 ----------
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L
    private var isSwiping = false
    private var lastBrightnessY = 0f
    private var adjustingBrightness = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 响铃中轻触屏幕即停止响铃，并消费本次触摸
                if (onRingingTouched?.invoke() == true) return true
                downX = event.x; downY = event.y; downTime = System.currentTimeMillis()
                isSwiping = false
                if (event.pointerCount >= 2) {
                    adjustingBrightness = true
                    lastBrightnessY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    adjustingBrightness = true
                    lastBrightnessY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (adjustingBrightness && event.pointerCount >= 2) {
                    val y = (event.getY(0) + event.getY(1)) / 2f
                    val dy = lastBrightnessY - y
                    if (abs(dy) > 6f) {
                        onBrightnessAdjust?.invoke(dy / height * 1.6f)
                        lastBrightnessY = y
                        resetIdle()
                    }
                    return true
                }
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > 40f || abs(dy) > 40f) isSwiping = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (adjustingBrightness) {
                    adjustingBrightness = false
                    resetIdle()
                    performClick()
                    return true
                }
                val dx = event.x - downX
                val dy = event.y - downY
                val now = System.currentTimeMillis()

                if (isSaver) { exitSaver(); return true }

                // 上滑：设置；左右滑：切换模式
                if (dy < -80f && abs(dy) > abs(dx)) {
                    onSettingsRequest?.invoke()
                    resetIdle()
                    return true
                }
                if (abs(dx) > 80f && abs(dx) > abs(dy)) {
                    val step = if (dx > 0) -1 else 1
                    var next = (mode + step + MODE_COUNT) % MODE_COUNT
                    if (next == mode) next = (next + step + MODE_COUNT) % MODE_COUNT
                    switchMode(next)
                    resetIdle()
                    return true
                }

                // 单击（非滑动）
                if (!isSwiping && now - downTime < 500L) {
                    // 双击重置计时
                    if (mode != MODE_CLOCK && now - lastTapTime < 320L) {
                        engine.reset(mode)
                        finishedNotified = false
                        invalidate()
                        lastTapTime = 0L
                        resetIdle()
                        return true
                    }
                    lastTapTime = now

                    if (!buttonsVisible) { buttonsVisible = true; invalidate(); resetIdle(); return true }

                    val x = event.x
                    val y = event.y
                    for (i in 0..2) {
                        if (btnRects[i].contains(x, y)) {
                            when (i) {
                                0 -> onSettingsRequest?.invoke()
                                1 -> if (mode == MODE_CLOCK) enterSaver() else {
                                    engine.reset(mode); finishedNotified = false; invalidate()
                                }
                                2 -> if (mode == MODE_CLOCK) {
                                    buttonsVisible = false; onFullscreenRequest?.invoke(); invalidate()
                                } else {
                                    engine.startPause(mode); invalidate()
                                }
                            }
                            resetIdle()
                            performClick()
                            return true
                        }
                    }
                    if (mode != MODE_CLOCK) {
                        engine.startPause(mode)
                        invalidate()
                    }
                }
                resetIdle()
                performClick()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount < 2) adjustingBrightness = false
                return true
            }
        }
        return true
    }

    var onBrightnessAdjust: ((Float) -> Unit)? = null

    // 所有画笔初始化完成后应用一次默认字体
    init { applyFont() }

    override fun performClick(): Boolean { super.performClick(); return true }
}
