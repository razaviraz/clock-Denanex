package com.denanex.aod

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * نمای اصلی: پس‌زمینهٔ کاملاً سیاه (کم‌مصرف روی AMOLED)، ساعت درشت (دیجیتال یا عقربه‌ای)،
 * تاریخ (خورشیدی/قمری/میلادی)، باتری، آب‌وهوا، و بالای صفحه کمان کم‌رنگ مسیر خورشید با جای خورشید و ماه.
 * چند دقیقه پیش از طلوع: سفیدی و سرخی صبح در افق و سر وقت طلوع، خورشید از پشت افق بالا می‌آید؛
 * هنگام غروب: سرخی غروب و فرو رفتن خورشید. وسط ساعت عقربه‌ای: قطب‌نما و جهت قبله.
 *
 * [previewStyle] ≥ ۰ یعنی نمونهٔ کوچک ثابت (ساعت ۱۰:۱۰) از همان شکل، برای انتخاب شکل در تنظیمات.
 */
class ClockView(ctx: Context, private val previewStyle: Int = -1) : View(ctx) {

    var prefs = Prefs(ctx)
        private set

    private val preview get() = previewStyle >= 0

    /** شدت سفیدی صبح/طلوع (۰ تا ۱) هر بار که عوض شود؛ برای بیشتر کردن نور صفحه. */
    var onSkyLevel: ((Float) -> Unit)? = null

    // ---------- زمان‌بندی ----------
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = Runnable {
        invalidate()
        scheduleNext()
    }

    // پیش‌نمایش طلوع یا غروب: زمان مجازی با سرعت ۳۰ برابر
    private var demoActive = false
    private var demoReal = 0L
    private var demoBase = 0L
    private var demoEnd = 0L

    // ---------- رنگ‌قلم‌ها ----------
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    private val tfThin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val tfBlack = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val ground = Path()
    private val tmpPath = Path()
    private var horizonY = 0f
    private val cal = Calendar.getInstance()

    // حافظهٔ کوتاه‌مدت اوقات امروز و فردا (محاسبه فقط وقتی روز یا شهر عوض شود)
    private var cacheKey = ""
    private var sunriseToday = 0L
    private var sunriseTomorrow = 0L
    private var sunsetToday = 0L
    private var fajrToday = 0L
    private var fajrTomorrow = 0L
    /** مسیر امروز خورشید در آسمان: ارتفاع و سمت هر ۱۰ دقیقه از طلوع تا غروب. */
    private var pathAlt = FloatArray(0)
    private var pathAz = FloatArray(0)

    // ماه و خورشید (هر دقیقه یک بار حساب می‌شود)
    private var posMinute = -1L
    private var moonInfo: SunCalc.Moon? = null
    private var sunInfo: SunCalc.SunPos? = null
    private val moonPath = Path()
    private val moonOval = RectF()

    // آب‌وهوا (هر دقیقه یک بار از حافظه خوانده می‌شود)
    private var weatherMinute = -1L
    private var weatherNow: Weather.Now? = null

    // قطب‌نما و قبله
    private val compass = Compass(ctx) { postInvalidate() }
    private var qiblaDeg = 0.0

    // ستاره‌های آسمان نیمه‌شب (جای ثابت)
    private val stars = FloatArray(3 * 46).also {
        val r = Random(7)
        for (i in 0 until 46) { it[3 * i] = r.nextFloat(); it[3 * i + 1] = r.nextFloat() * 0.8f; it[3 * i + 2] = r.nextFloat() }
    }

    private var batteryMinute = -1L
    private var batteryText = ""
    private var lastLevel = -1f

    /** شمارهٔ نسخهٔ نصب‌شده، برای پایین صفحه. */
    private var versionText = ""

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = !preview
    }

    fun reload() {
        prefs = Prefs(context)
        versionText = Updater.currentName(context).let { if (it.isEmpty()) "" else "نسخهٔ " + Persian.digits(it) }
        cacheKey = ""
        batteryMinute = -1
        posMinute = -1
        weatherMinute = -1
        if (running) updateCompass()
        invalidate()
    }

    fun start() {
        if (preview || running) return
        running = true
        updateCompass()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        compass.stop()
    }

    /** قطب‌نما فقط وقتی لازم است روشن باشد (ساعت عقربه‌ای + گزینهٔ قبله‌نما). */
    private fun updateCompass() {
        if (prefs.qibla && prefs.style >= 3 && running) {
            val loc = prefs.location()
            qiblaDeg = Qibla.bearing(loc.lat, loc.lon)
            compass.start(loc.lat, loc.lon)
        } else compass.stop()
    }

    /** پیش‌نمایش سریع طلوع (یا غروب، اگر [sunset]) در حدود یک دقیقه. */
    fun startDemo(sunset: Boolean = false) {
        ensureSunrise(System.currentTimeMillis())
        if (sunset) {
            demoBase = sunsetToday - (SUNSET_START_MIN * 60_000L).toLong() - 4_000L
            demoEnd = sunsetToday + (SUNSET_END_MIN * 60_000L).toLong()
        } else {
            demoBase = sunriseToday - prefs.dawnMinutes * 60_000L - 4_000L
            demoEnd = sunriseToday + (SKY_END_MIN * 60_000L).toLong()
        }
        demoReal = SystemClock.elapsedRealtime()
        demoActive = true
        handler.removeCallbacks(tick)
        if (running) handler.post(tick)
    }

    private fun nowMs(): Long {
        if (demoActive) {
            val v = demoBase + (SystemClock.elapsedRealtime() - demoReal) * DEMO_SPEED
            if (v > demoEnd + 20_000L) demoActive = false else return v
        }
        return System.currentTimeMillis()
    }

    private fun sunriseWindow(now: Long): Boolean {
        val t = (now - sunriseToday) / 60_000.0
        return prefs.sunrise && t >= -prefs.dawnMinutes && t <= SKY_END_MIN
    }

    private fun sunsetWindow(now: Long): Boolean {
        val t = (now - sunsetToday) / 60_000.0
        return prefs.sunsetFx && t >= -SUNSET_START_MIN && t <= SUNSET_END_MIN
    }

    private fun scheduleNext() {
        if (!running) return
        val now = nowMs()
        ensureSunrise(now)
        var delay: Long = when {
            demoActive -> 33L
            sunriseWindow(now) || sunsetWindow(now) -> 100L
            prefs.seconds || prefs.style >= 3 -> 1000L - now % 1000L   // عقربه‌ای: عقربهٔ ثانیه همیشه هست
            else -> 60_000L - now % 60_000L + 15L
        }
        // شروع آسمان نیمه‌شب، سفیدی صبح و غروب معمولاً وسط دقیقه است؛ از آن جا نمانیم
        if (!demoActive) {
            for (ev in longArrayOf(sunriseToday - prefs.dawnMinutes * 60_000L, fajrToday - NIGHT_BEFORE_FAJR_MS,
                    sunsetToday - (SUNSET_START_MIN * 60_000L).toLong())) {
                if (ev > now && ev - now < delay) delay = ev - now + 20L
            }
        }
        handler.postDelayed(tick, max(16L, delay))
    }

    private fun ensureSunrise(now: Long) {
        val loc = prefs.location()
        cal.timeInMillis = now
        val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}-${loc.lat}-${loc.lon}-${cal.timeZone.id}"
        if (key == cacheKey) return
        cacheKey = key
        cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val noon = cal.timeInMillis
        sunriseToday = SunCalc.sunrise(noon, loc.lat, loc.lon) ?: (noon - 6 * 3_600_000L)
        sunsetToday = SunCalc.sunset(noon, loc.lat, loc.lon) ?: (noon + 6 * 3_600_000L)
        fajrToday = SunCalc.fajr(noon, loc.lat, loc.lon) ?: (sunriseToday - 90 * 60_000L)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        sunriseTomorrow = SunCalc.sunrise(cal.timeInMillis, loc.lat, loc.lon) ?: (sunriseToday + 86_400_000L)
        fajrTomorrow = SunCalc.fajr(cal.timeInMillis, loc.lat, loc.lon) ?: (sunriseTomorrow - 90 * 60_000L)
        // مسیر خورشید امروز
        val n = max(2, ((sunsetToday - sunriseToday) / 600_000L).toInt() + 1)
        pathAlt = FloatArray(n)
        pathAz = FloatArray(n)
        for (i in 0 until n) {
            val t = sunriseToday + (sunsetToday - sunriseToday) * i / (n - 1)
            val p = SunCalc.sunPosition(t, loc.lat, loc.lon)
            pathAlt[i] = p.alt.toFloat()
            pathAz[i] = p.az.toFloat()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        horizonY = h * (if (h >= w) 0.84f else 0.83f)
        // افق: کوه‌پایه‌های کم‌ارتفاع در دو طرف و افق صاف در وسط (جای طلوع و غروب خورشید)
        val hy = horizonY
        val bump = h * 0.022f
        ground.reset()
        ground.moveTo(0f, hy - bump * 0.7f)
        ground.quadTo(w * 0.10f, hy - bump * 1.6f, w * 0.22f, hy - bump * 0.4f)
        ground.quadTo(w * 0.30f, hy + bump * 0.2f, w * 0.38f, hy)
        ground.lineTo(w * 0.64f, hy)
        ground.quadTo(w * 0.72f, hy - bump * 0.1f, w * 0.80f, hy - bump * 1.1f)
        ground.quadTo(w * 0.90f, hy - bump * 1.9f, w.toFloat(), hy - bump * 0.5f)
        ground.lineTo(w.toFloat(), h.toFloat())
        ground.lineTo(0f, h.toFloat())
        ground.close()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ============================== رسم ==============================

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (preview) { drawPreview(canvas, w, h); return }
        val now = nowMs()
        ensureSunrise(now)
        canvas.drawColor(Color.BLACK)

        // ۱) آسمان: نیمه‌شب پیش از اذان صبح، سفیدی و سرخی صبح و طلوع، سرخی غروب
        val night = if (prefs.nightSky) drawNight(canvas, now, w, h) else 0f
        val tMin = ((now - sunriseToday) / 60_000.0).toFloat()
        val level = if (prefs.sunrise) drawSunrise(canvas, tMin, w, h) else 0f
        val dusk = if (prefs.sunsetFx) drawSunset(canvas, ((now - sunsetToday) / 60_000.0).toFloat(), w, h) else 0f
        if (night > 0f || level > 0f || dusk > 0f) {
            // زمین/کوه‌ها (سیاه) — پایین خورشید را می‌پوشاند تا از پشت افق بالا بیاید یا فرو برود
            canvas.drawPath(ground, groundPaint)
        }
        // روی آسمان روشن، سایهٔ تیرهٔ ملایم زیر نوشته‌ها تا خوانا بمانند
        val lit = max(level, dusk)
        if (lit > 0.05f) {
            val sh = Color.argb((190 * lit).toInt(), 0, 0, 0)
            val rad = min(w, h) * 0.012f
            timePaint.setShadowLayer(rad, 0f, 0f, sh)
            smallPaint.setShadowLayer(rad, 0f, 0f, sh)
        } else {
            timePaint.clearShadowLayer()
            smallPaint.clearShadowLayer()
        }
        if (kotlin.math.abs(level - lastLevel) > 0.01f || (level == 0f && lastLevel != 0f)) {
            lastLevel = level
            onSkyLevel?.invoke(level)
        }

        // ۲) جابه‌جایی خیلی آرام برای جلوگیری از سوختن صفحه: مسیر پیوسته (دورهٔ ۴۷ و ۳۱ دقیقه)،
        //    هر دقیقه فقط چند پیکسل؛ نه پرش ناگهانی که لرزش به نظر برسد
        var dx = 0f
        var dy = 0f
        if (prefs.burnIn) {
            val m = (now / 60_000L).toDouble()
            dx = (sin(m * 2 * PI / 47.0) * w * 0.025).toFloat()
            dy = (cos(m * 2 * PI / 31.0) * h * 0.012).toFloat()
        }
        val portrait = h >= w

        // ۳) بالای صفحه: کمان مسیر خورشید، جای خورشید و ماه، آب‌وهوا
        val bigSunOnHorizon = (level > 0f && tMin >= 0f) || (dusk > 0f && now < sunsetToday)
        drawSkyDome(canvas, now, w, h, dx, hideSun = bigSunOnHorizon)
        if (prefs.weather) drawWeather(canvas, now, w, h)

        // ۴) ساعت
        cal.timeInMillis = now
        val color = prefs.mainColor()
        val style = prefs.style
        val cx = w / 2f + dx
        val bottom: Float = when {
            !portrait && style <= 2 && prefs.landscapeBig -> drawSplitDigital(canvas, w, h, dx, dy, style, color)
            !portrait && style == STYLE_ANALOG_DIGITAL -> {
                // افقی: ساعت عقربه‌ای سمت راست و دیجیتال درشت سمت چپ
                val cy = h * 0.40f + dy
                val r = min(h * 0.30f, w * 0.20f)
                val aBottom = drawAnalog(canvas, w * 0.73f + dx, cy, r, color, style, w, h, digitalBelow = false)
                timePaint.typeface = tfMedium
                val dBottom = drawTimeLine(canvas, w * 0.29f + dx, cy, w * 0.42f, h * 0.30f, tfMedium, color)
                max(aBottom, dBottom)
            }
            else -> {
                // شکل «عقربه‌ای + دیجیتال درشت» بلندتر است؛ کمی بالاتر رسم شود
                val cy = (if (style == STYLE_ANALOG_DIGITAL) (if (portrait) h * 0.30f else h * 0.25f)
                          else (if (portrait) h * 0.37f else h * 0.30f)) + dy
                when (style) {
                    0 -> drawTimeLine(canvas, cx, cy, if (portrait) w * 0.88f else w * 0.55f,
                        if (portrait) h * 0.20f else h * 0.30f, tfThin, color)
                    1 -> drawTimeLine(canvas, cx, cy, if (portrait) w * 0.88f else w * 0.55f,
                        if (portrait) h * 0.20f else h * 0.30f, tfBlack, color)
                    2 -> drawStacked(canvas, cx, cy, w, h, color)
                    else -> drawAnalog(canvas, cx, cy, analogRadius(style, w, h), color, style, w, h)
                }
            }
        }
        drawInfo(canvas, cx, bottom, w, h, now, color, compact = !portrait)
        drawFooter(canvas, cx, w, h)
    }

    private fun analogRadius(style: Int, w: Float, h: Float): Float {
        val portrait = h >= w
        return when {
            style == STYLE_ANALOG_DIGITAL && portrait -> min(w * 0.33f, h * 0.16f)
            style == STYLE_ANALOG_DIGITAL -> min(h * 0.17f, w * 0.22f)
            portrait -> min(w * 0.40f, h * 0.23f)
            else -> min(h * 0.24f, w * 0.30f)
        }
    }

    /** نمونهٔ کوچک ثابت (۱۰:۱۰:۳۰) برای انتخاب شکل ساعت در تنظیمات. */
    private fun drawPreview(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.BLACK)
        timePaint.clearShadowLayer()
        smallPaint.clearShadowLayer()
        cal.timeInMillis = System.currentTimeMillis()
        cal.set(Calendar.HOUR_OF_DAY, 10); cal.set(Calendar.MINUTE, 10)
        cal.set(Calendar.SECOND, 30); cal.set(Calendar.MILLISECOND, 0)
        val color = prefs.mainColor()
        when (previewStyle) {
            0 -> drawTimeLine(c, w / 2f, h * 0.48f, w * 0.74f, h * 0.24f, tfThin, color)
            1 -> drawTimeLine(c, w / 2f, h * 0.48f, w * 0.74f, h * 0.24f, tfBlack, color)
            2 -> drawStacked(c, w / 2f, h * 0.50f, w * 0.80f, h * 0.80f, color)
            else -> drawAnalog(c, w / 2f, h * 0.38f, min(w, h) * 0.30f, color, previewStyle, w, h)
        }
    }

    private fun fa(s: String) = Persian.digits(s, prefs.persianDigits)
    private fun two(n: Int) = if (n < 10) "0$n" else n.toString()

    private fun hourValue(): Int {
        val hr = cal.get(Calendar.HOUR_OF_DAY)
        if (prefs.h24) return hr
        val x = hr % 12
        return if (x == 0) 12 else x
    }

    private fun timeText() = fa(two(hourValue()) + ":" + two(cal.get(Calendar.MINUTE)))

    /** اندازهٔ قلم تا متن [sample] دقیقاً پهنای [targetW] شود، و ارتفاعش از [maxH] بیشتر نشود. */
    private fun fit(p: Paint, sample: String, targetW: Float, maxH: Float) {
        p.textSize = 100f
        val mw = p.measureText(sample)
        var size = if (mw > 0f) 100f * targetW / mw else 100f
        size = min(size, maxH / 0.75f)
        p.textSize = size
    }

    /** ساعت دیجیتال یک‌خطی با مرکز ([cx], [cy])؛ پایین بلوک را برمی‌گرداند. */
    private fun drawTimeLine(c: Canvas, cx: Float, cy: Float, targetW: Float, maxH: Float, tf: Typeface, color: Int): Float {
        timePaint.typeface = tf
        timePaint.color = color
        timePaint.alpha = 255
        fit(timePaint, fa("00:00"), targetW, maxH)
        val fm = timePaint.fontMetrics
        val base = cy - (fm.ascent + fm.descent) / 2f
        drawFixed(c, timeText(), cx, base, timePaint)
        var bottom = base + fm.descent * 0.3f
        if (hasSubLine()) {
            smallPaint.typeface = tfLight
            smallPaint.color = color
            smallPaint.alpha = 200
            smallPaint.textSize = timePaint.textSize * 0.22f
            bottom += smallPaint.textSize * 1.25f
            drawSubLine(c, cx, bottom)
        }
        return bottom
    }

    /**
     * افقی، شکل‌های دیجیتال: تمام‌صفحه و خیلی درشت — ساعت سمت چپ و دقیقه سمت راست.
     * پایین بلوک را برمی‌گرداند.
     */
    private fun drawSplitDigital(c: Canvas, w: Float, h: Float, dx: Float, dy: Float, style: Int, color: Int): Float {
        timePaint.typeface = when (style) { 0 -> tfThin; 1 -> tfBlack; else -> tfMedium }
        timePaint.color = color
        timePaint.alpha = 255
        fit(timePaint, fa("00"), w * 0.40f, h * 0.46f)
        val fm = timePaint.fontMetrics
        val cy = h * 0.37f + dy
        val base = cy - (fm.ascent + fm.descent) / 2f
        drawFixed(c, fa(two(hourValue())), w * 0.26f + dx, base, timePaint)
        if (style == 2) timePaint.alpha = 190
        drawFixed(c, fa(two(cal.get(Calendar.MINUTE))), w * 0.74f + dx, base, timePaint)
        timePaint.alpha = 255
        if (style != 2) c.drawText(":", w / 2f + dx, base - timePaint.textSize * 0.05f, timePaint)
        var bottom = base + fm.descent * 0.3f
        if (hasSubLine()) {
            smallPaint.typeface = tfLight
            smallPaint.color = color
            smallPaint.alpha = 200
            smallPaint.textSize = timePaint.textSize * 0.16f
            bottom += smallPaint.textSize * 1.2f
            drawSubLine(c, w / 2f + dx, bottom)
        }
        return bottom
    }

    private fun hasSubLine() = !prefs.h24 || prefs.seconds

    /**
     * ثانیه و «ق.ظ/ب.ظ» (هر کدام فعال باشد) زیر ساعت دیجیتال، با [smallPaint].
     * ثانیه با رقم‌های هم‌پهنا رسم می‌شود تا با عوض شدن رقم، نوشته جابه‌جا نشود.
     */
    private fun drawSubLine(c: Canvas, cx: Float, y: Float) {
        val ampm = if (prefs.h24) "" else (if (cal.get(Calendar.AM_PM) == Calendar.AM) "ق.ظ" else "ب.ظ")
        val sec = if (prefs.seconds) fa(two(cal.get(Calendar.SECOND))) else ""
        if (sec.isEmpty()) { c.drawText(ampm, cx, y, smallPaint); return }
        if (ampm.isEmpty()) { drawFixed(c, sec, cx, y, smallPaint); return }
        // راست به چپ: «ق.ظ» سمت راست، ثانیه سمت چپ
        val a = smallPaint.measureText(ampm)
        val s = fixedWidth(sec, smallPaint)
        val gap = smallPaint.textSize * 1.2f
        val left = cx - (a + gap + s) / 2f
        drawFixed(c, sec, left + s / 2f, y, smallPaint)
        c.drawText(ampm, left + s + gap + a / 2f, y, smallPaint)
    }

    /** پهنای یک خانهٔ رقم: پهنای پهن‌ترین رقم (لاتین و فارسی) با همین قلم. */
    private fun digitCell(p: Paint): Float {
        var m = 0f
        for (ch in DIGITS) m = max(m, p.measureText(ch.toString()))
        return m
    }

    /** پهنای نوشته وقتی هر رقم در خانهٔ هم‌پهنا رسم شود. */
    private fun fixedWidth(s: String, p: Paint): Float {
        val cell = digitCell(p)
        var tw = 0f
        for (ch in s) tw += if (ch in DIGITS) cell else p.measureText(ch.toString())
        return tw
    }

    /**
     * نوشتهٔ رقمی (مثل ۱۲:۴۵) را با خانه‌های هم‌پهنا وسط [cx] رسم می‌کند؛ با عوض شدن رقم‌ها
     * پهنای کل و جای «:» ثابت می‌ماند و ساعت نمی‌لرزد. [p] باید textAlign = CENTER داشته باشد.
     */
    private fun drawFixed(c: Canvas, s: String, cx: Float, base: Float, p: Paint) {
        val cell = digitCell(p)
        var x = cx - fixedWidth(s, p) / 2f
        for (ch in s) {
            val str = ch.toString()
            val cw = if (ch in DIGITS) cell else p.measureText(str)
            c.drawText(str, x + cw / 2f, base, p)
            x += cw
        }
    }

    /** ساعت دوطبقه: ساعت بالا، دقیقه پایین؛ خیلی درشت (شبیه AOD سامسونگ). */
    private fun drawStacked(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, color: Int): Float {
        val portrait = h >= w
        timePaint.typeface = tfMedium
        timePaint.color = color
        fit(timePaint, fa("00"), if (portrait) w * 0.60f else w * 0.22f, if (portrait) h * 0.19f else h * 0.16f)
        val fm = timePaint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 0.86f
        val top = cy - lineH
        timePaint.alpha = 255
        drawFixed(c, fa(two(hourValue())), cx, top - fm.ascent * 0.92f, timePaint)
        timePaint.alpha = 190
        val base2 = top + lineH - fm.ascent * 0.92f
        drawFixed(c, fa(two(cal.get(Calendar.MINUTE))), cx, base2, timePaint)
        timePaint.alpha = 255
        var bottom = base2 + fm.descent * 0.3f
        if (hasSubLine()) {
            smallPaint.typeface = tfLight
            smallPaint.color = color
            smallPaint.alpha = 200
            smallPaint.textSize = timePaint.textSize * 0.18f
            bottom += smallPaint.textSize * 1.3f
            drawSubLine(c, cx, bottom)
        }
        return bottom
    }

    /**
     * ساعت عقربه‌ای (چهار شکل؛ آخری با ساعت دیجیتال درشت زیرش) با شعاع [r].
     * اگر [digitalBelow] باشد، ساعت دیجیتال زیرش هم رسم می‌شود. پایین بلوک را برمی‌گرداند.
     */
    private fun drawAnalog(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, style: Int, w: Float, h: Float,
                           digitalBelow: Boolean = true): Float {
        val sec = cal.get(Calendar.SECOND).toFloat()
        val minute = cal.get(Calendar.MINUTE) + sec / 60f
        val hour = (cal.get(Calendar.HOUR_OF_DAY) % 12) + minute / 60f

        when (style) {
            3, STYLE_ANALOG_DIGITAL -> { // کلاسیک: خط‌های دقیقه و ساعت + اعداد ۱۲، ۳، ۶، ۹
                for (i in 0 until 60) {
                    val quarter = i % 15 == 0          // ۱۲، ۳، ۶، ۹: برجسته‌تر
                    val big = i % 5 == 0
                    strokePaint.color = color
                    strokePaint.alpha = if (big) 255 else 120
                    strokePaint.strokeWidth = when { quarter -> r * 0.055f; big -> r * 0.03f; else -> r * 0.012f }
                    val a = i * PI / 30
                    val r1 = when { quarter -> r * 0.80f; big -> r * 0.86f; else -> r * 0.93f }
                    c.drawLine(cx + (sin(a) * r1).toFloat(), cy - (cos(a) * r1).toFloat(),
                        cx + (sin(a) * r).toFloat(), cy - (cos(a) * r).toFloat(), strokePaint)
                }
                numbers(c, cx, cy, r * 0.64f, r * 0.20f, color, intArrayOf(12, 3, 6, 9), tfMedium)
            }
            4 -> { // ساده: فقط نقطه‌ها
                fillPaint.color = color
                for (i in 0 until 12) {
                    val a = i * PI / 6
                    fillPaint.alpha = if (i % 3 == 0) 255 else 150
                    val dot = if (i % 3 == 0) r * 0.065f else r * 0.022f    // ۱۲، ۳، ۶، ۹ برجسته‌تر
                    c.drawCircle(cx + (sin(a) * r * 0.95f).toFloat(), cy - (cos(a) * r * 0.95f).toFloat(), dot, fillPaint)
                }
            }
            else -> { // اعداد فارسی ۱ تا ۱۲ + حلقهٔ نازک
                strokePaint.color = color
                strokePaint.alpha = 90
                strokePaint.strokeWidth = r * 0.012f
                c.drawCircle(cx, cy, r, strokePaint)
                // ۱۲، ۳، ۶، ۹ درشت‌تر و پررنگ‌تر؛ بقیه کوچک‌تر
                numbers(c, cx, cy, r * 0.80f, r * 0.15f, color, intArrayOf(1, 2, 4, 5, 7, 8, 10, 11), tfLight, forcePersian = true)
                numbers(c, cx, cy, r * 0.78f, r * 0.21f, color, intArrayOf(12, 3, 6, 9), tfBlack, forcePersian = true)
            }
        }

        // قطب‌نما و قبله، زیر عقربه‌ها
        if (!preview && prefs.qibla) drawQibla(c, cx, cy, r)

        val thin = style == 4
        hand(c, cx, cy, hour * 30f, r * 0.52f, if (thin) r * 0.035f else r * 0.06f, color, 255)
        hand(c, cx, cy, minute * 6f, r * 0.80f, if (thin) r * 0.022f else r * 0.04f, color, 235)
        // عقربهٔ ثانیه (همهٔ ساعت‌های عقربه‌ای)
        val accent = Color.rgb(0xFF, 0x70, 0x43)
        hand(c, cx, cy, sec * 6f, r * 0.88f, r * 0.012f, accent, 255, tail = r * 0.15f)
        fillPaint.color = accent
        fillPaint.alpha = 255
        c.drawCircle(cx, cy, r * 0.05f, fillPaint)
        fillPaint.color = Color.BLACK
        c.drawCircle(cx, cy, r * 0.018f, fillPaint)

        if (!digitalBelow) return cy + r
        if (style == STYLE_ANALOG_DIGITAL) return drawBigDigitalUnder(c, cx, cy + r, w, h, color)

        // زیر ساعت عقربه‌ای، ساعت دیجیتال
        smallPaint.typeface = tfMedium
        smallPaint.color = color
        smallPaint.alpha = 220
        smallPaint.textSize = r * 0.30f
        var base = cy + r + smallPaint.textSize * 1.25f
        drawFixed(c, timeText(), cx, base, smallPaint)
        if (!prefs.h24) {
            smallPaint.typeface = tfLight
            smallPaint.textSize = r * 0.15f
            base += smallPaint.textSize * 1.4f
            c.drawText(if (cal.get(Calendar.AM_PM) == Calendar.AM) "ق.ظ" else "ب.ظ", cx, base, smallPaint)
        }
        return base
    }

    /** ساعت دیجیتال با قلم درشت زیر ساعت عقربه‌ای ([top] = پایین صفحهٔ عقربه‌ای)؛ پایین بلوک را برمی‌گرداند. */
    private fun drawBigDigitalUnder(c: Canvas, cx: Float, top: Float, w: Float, h: Float, color: Int): Float {
        val portrait = h >= w
        timePaint.typeface = tfMedium
        timePaint.color = color
        timePaint.alpha = 255
        fit(timePaint, fa("00:00"), if (preview) w * 0.62f else if (portrait) w * 0.80f else w * 0.36f,
            if (preview) h * 0.14f else if (portrait) h * 0.105f else h * 0.15f)
        val fm = timePaint.fontMetrics
        val base = top + timePaint.textSize * 0.15f - fm.ascent * 0.80f
        drawFixed(c, timeText(), cx, base, timePaint)
        var bottom = base + fm.descent * 0.3f
        if (hasSubLine()) {
            smallPaint.typeface = tfLight
            smallPaint.color = color
            smallPaint.alpha = 200
            smallPaint.textSize = timePaint.textSize * 0.22f
            bottom += smallPaint.textSize * 1.2f
            drawSubLine(c, cx, bottom)
        }
        return bottom
    }

    /**
     * وسط ساعت عقربه‌ای: نشانهٔ شمال (مثلث قرمز با «ش») و کعبه در جهت قبله، با حسگر قطب‌نمای گوشی.
     * بالای صفحه = جهتی که گوشی رو به آن است.
     */
    private fun drawQibla(c: Canvas, cx: Float, cy: Float, r: Float) {
        val heading = compass.heading ?: return
        val rr = r * 0.40f
        // شمال
        val aN = Math.toRadians((-heading).toDouble())
        val nx = cx + (sin(aN) * rr).toFloat()
        val ny = cy - (cos(aN) * rr).toFloat()
        tmpPath.reset()
        val t = r * 0.07f
        val ux = sin(aN).toFloat(); val uy = -cos(aN).toFloat()     // رو به بیرون
        val px = -uy; val py = ux                                     // عمود
        tmpPath.moveTo(nx + ux * t * 1.4f, ny + uy * t * 1.4f)
        tmpPath.lineTo(nx - ux * t * 0.6f + px * t * 0.8f, ny - uy * t * 0.6f + py * t * 0.8f)
        tmpPath.lineTo(nx - ux * t * 0.6f - px * t * 0.8f, ny - uy * t * 0.6f - py * t * 0.8f)
        tmpPath.close()
        fillPaint.color = Color.rgb(0xFF, 0x52, 0x52)
        fillPaint.alpha = 220
        c.drawPath(tmpPath, fillPaint)
        smallPaint.typeface = tfMedium
        smallPaint.color = Color.rgb(0xFF, 0x52, 0x52)
        smallPaint.alpha = 200
        smallPaint.textSize = r * 0.10f
        c.drawText("ش", nx - ux * t * 2.4f, ny - uy * t * 2.4f + smallPaint.textSize * 0.35f, smallPaint)

        // قبله: خط کم‌رنگ از مرکز تا کعبه
        val aQ = Math.toRadians(qiblaDeg - heading)
        val kx = cx + (sin(aQ) * rr * 1.18f).toFloat()
        val ky = cy - (cos(aQ) * rr * 1.18f).toFloat()
        val aligned = kotlin.math.abs(((qiblaDeg - heading + 540.0) % 360.0) - 180.0) < 4.0
        strokePaint.color = if (aligned) Color.rgb(0x7C, 0xFF, 0x9A) else KAABA_GOLD
        strokePaint.alpha = if (aligned) 200 else 110
        strokePaint.strokeWidth = r * 0.014f
        c.drawLine(cx, cy, cx + (kx - cx) * 0.78f, cy + (ky - cy) * 0.78f, strokePaint)
        // آیکون کعبه: مکعب سیاه با لبهٔ روشن و نوار طلایی
        val k = r * 0.075f
        if (aligned) {
            skyPaint.shader = RadialGradient(kx, ky, k * 2.6f,
                intArrayOf(Color.argb(110, 0x7C, 0xFF, 0x9A), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(kx, ky, k * 2.6f, skyPaint)
            skyPaint.shader = null
        }
        fillPaint.color = Color.rgb(0x10, 0x10, 0x10)
        fillPaint.alpha = 255
        c.drawRect(kx - k, ky - k, kx + k, ky + k, fillPaint)
        strokePaint.color = Color.rgb(0xB0, 0xB0, 0xB0)
        strokePaint.alpha = 200
        strokePaint.strokeWidth = max(1f, k * 0.12f)
        c.drawRect(kx - k, ky - k, kx + k, ky + k, strokePaint)
        fillPaint.color = KAABA_GOLD
        c.drawRect(kx - k, ky - k * 0.55f, kx + k, ky - k * 0.25f, fillPaint)
        // در کعبه
        c.drawRect(kx + k * 0.25f, ky + k * 0.05f, kx + k * 0.6f, ky + k * 0.75f, fillPaint)
        // نوشتهٔ «قبله» جلوی کعبه (کمی بیرون‌تر در همان جهت)
        smallPaint.typeface = tfMedium
        smallPaint.color = if (aligned) Color.rgb(0x7C, 0xFF, 0x9A) else KAABA_GOLD
        smallPaint.alpha = 230
        smallPaint.textSize = r * 0.095f
        val lx = kx + (sin(aQ) * k * 2.7f).toFloat()
        val ly = ky - (cos(aQ) * k * 2.7f).toFloat()
        c.drawText("قبله", lx, ly + smallPaint.textSize * 0.35f, smallPaint)
    }

    private fun numbers(c: Canvas, cx: Float, cy: Float, rr: Float, size: Float, color: Int,
                        which: IntArray, tf: Typeface, forcePersian: Boolean = false) {
        smallPaint.typeface = tf
        smallPaint.color = color
        smallPaint.alpha = 230
        smallPaint.textSize = size
        val fm = smallPaint.fontMetrics
        val off = -(fm.ascent + fm.descent) / 2f
        for (n in which) {
            val a = n * PI / 6
            val s = Persian.digits(n.toString(), forcePersian || prefs.persianDigits)
            c.drawText(s, cx + (sin(a) * rr).toFloat(), cy - (cos(a) * rr).toFloat() + off, smallPaint)
        }
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, width: Float,
                     color: Int, alpha: Int, tail: Float = 0f) {
        val a = deg * PI / 180.0
        strokePaint.color = color
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = width
        val sx = (sin(a)).toFloat()
        val sy = (cos(a)).toFloat()
        c.drawLine(cx - sx * tail, cy + sy * tail, cx + sx * len, cy - sy * len, strokePaint)
    }

    /** تاریخ‌های انتخابی: اولی (با نام روز) درشت، بقیه در یک خط کوچک‌تر. */
    private fun dateLines(): List<String> {
        val gy = cal.get(Calendar.YEAR); val gm = cal.get(Calendar.MONTH) + 1; val gd = cal.get(Calendar.DAY_OF_MONTH)
        val out = ArrayList<String>(3)
        if (prefs.showDate) {
            val j = Persian.toJalali(gy, gm, gd)
            out.add(fa(j[2].toString()) + " " + Persian.months[j[1] - 1] + " " + fa(j[0].toString()))
        }
        if (prefs.showHijri) {
            val hj = Hijri.fromGregorian(gy, gm, gd, prefs.hijriOffset)
            out.add(fa(hj[2].toString()) + " " + Hijri.months[hj[1] - 1] + " " + fa(hj[0].toString()))
        }
        if (prefs.showGregorian) {
            out.add(fa(gd.toString()) + " " + Gregorian.months[gm - 1] + " " + fa(gy.toString()))
        }
        if (out.isNotEmpty()) out[0] = Persian.weekday(cal.get(Calendar.DAY_OF_WEEK)) + "  " + out[0]
        return out
    }

    /**
     * زیر ساعت: تاریخ (جمعه و تعطیل رسمی قرمز)، اوقات شرعی (انتخابی، با افق شهر)، باتری.
     * [compact] (گوشی افقی): تاریخ و باتری در یک خط و اوقات شرعی در یک خط.
     */
    private fun drawInfo(c: Canvas, cx: Float, top: Float, w: Float, h: Float, now: Long, color: Int, compact: Boolean) {
        val base = min(w, h) * (if (!compact) 0.048f else 0.040f)
        val big = base * 1.3f
        smallPaint.color = color
        var y = top + base * (if (compact) 1.0f else 1.5f)

        // افق شرعی شهر، درست زیر ساعت (فقط وقتی اوقات شرعی نمایش داده می‌شود)
        if (prefs.showSunrise && !compact) {
            smallPaint.typeface = tfLight
            smallPaint.textSize = base * 0.9f
            smallPaint.alpha = 170
            c.drawText("به افق " + prefs.location().name, cx, y, smallPaint)
            y += big * 1.55f
        } else if (!compact) y += big * 0.5f

        val gy = cal.get(Calendar.YEAR); val gm = cal.get(Calendar.MONTH) + 1; val gd = cal.get(Calendar.DAY_OF_MONTH)
        val j = Persian.toJalali(gy, gm, gd)
        val hol = Hijri.holiday(j[1], j[2], Hijri.fromGregorian(gy, gm, gd, prefs.hijriOffset))
        val friday = cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val dates = dateLines()
        val batt = if (prefs.showBattery) battery(now) else ""

        if (dates.isNotEmpty()) {
            val dateSize = if (compact) big * 1.05f else big * 1.45f      // تاریخ درشت
            y += dateSize - big
            smallPaint.typeface = tfMedium
            smallPaint.textSize = dateSize
            if (friday || hol != null) smallPaint.color = HOLIDAY_RED
            smallPaint.alpha = 235
            c.drawText(if (compact && batt.isNotEmpty()) dates[0] + "   •   " + batt else dates[0], cx, y, smallPaint)
            smallPaint.color = color
            if (dates.size > 1) {
                y += dateSize * 1.05f
                smallPaint.typeface = tfLight
                smallPaint.textSize = dateSize * 0.72f
                smallPaint.alpha = 210
                c.drawText(dates.drop(1).joinToString("   •   "), cx, y, smallPaint)
            }
            if (hol != null) {
                y += base * 1.25f
                smallPaint.typeface = tfLight
                smallPaint.textSize = base * 0.85f
                smallPaint.color = HOLIDAY_RED
                smallPaint.alpha = 220
                c.drawText(hol, cx, y, smallPaint)
                smallPaint.color = color
            }
            y += dateSize * (if (compact) 1.1f else 1.25f)
        }

        if (prefs.showSunrise) {
            smallPaint.typeface = tfLight
            smallPaint.textSize = big * (if (compact) 0.80f else 0.92f)
            smallPaint.alpha = 225
            val nextFajr = if (now < fajrToday) fajrToday else fajrTomorrow
            val nextSun = if (now < sunriseToday) sunriseToday else sunriseTomorrow
            val l1 = "اذان صبح " + hm(nextFajr) + "  •  " + remain(nextFajr - now)
            val l2 = "طلوع آفتاب " + hm(nextSun) + "  •  " + remain(nextSun - now)
            if (compact) {
                c.drawText("به افق " + prefs.location().name + "   —   اذان صبح " + hm(nextFajr) +
                    "   —   طلوع آفتاب " + hm(nextSun), cx, y, smallPaint)
                y += big * 1.2f
            } else {
                c.drawText(l1, cx, y, smallPaint)
                y += big * 1.45f
                c.drawText(l2, cx, y, smallPaint)
                y += big * 1.3f
            }
        }
        if (batt.isNotEmpty() && !(compact && dates.isNotEmpty())) {
            // درصد باتری درشت و روشن
            smallPaint.typeface = tfMedium
            smallPaint.alpha = 200
            smallPaint.textSize = base * (if (compact) 1.2f else 1.5f)
            y += base * 0.45f
            c.drawText(batt, cx, y, smallPaint)
        }
    }

    /** پایین صفحه (روی زمین سیاه): شمارهٔ نسخه. (نام تهیه‌کننده فقط در تنظیمات و راهنما.) */
    private fun drawFooter(c: Canvas, cx: Float, w: Float, h: Float) {
        if (versionText.isEmpty()) return
        val base = min(w, h) * (if (h >= w) 0.042f else 0.034f)
        smallPaint.typeface = tfLight
        smallPaint.textSize = base * 0.62f
        smallPaint.color = Color.WHITE
        smallPaint.alpha = 95
        c.drawText(versionText, cx, horizonY + (h - horizonY) * 0.70f, smallPaint)
    }

    private fun hm(ms: Long): String {
        val sc = Calendar.getInstance().apply { timeInMillis = ms }
        return fa(two(sc.get(Calendar.HOUR_OF_DAY)) + ":" + two(sc.get(Calendar.MINUTE)))
    }

    /** «۳ ساعت و ۱۲ دقیقه مانده» */
    private fun remain(ms: Long): String {
        val m = ((ms + 59_999L) / 60_000L).coerceAtLeast(0L)
        val hh = m / 60
        val mm = m % 60
        return when {
            hh == 0L -> fa(mm.toString()) + " دقیقه مانده"
            mm == 0L -> fa(hh.toString()) + " ساعت مانده"
            else -> fa(hh.toString()) + " ساعت و " + fa(mm.toString()) + " دقیقه مانده"
        }
    }

    private fun battery(now: Long): String {
        val minute = now / 60_000L
        if (minute == batteryMinute) return batteryText
        batteryMinute = minute
        val i: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) { null }
        batteryText = if (i == null) "" else {
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            if (lvl < 0 || scale <= 0) "" else (if (charging) "⚡ " else "باتری ") + fa((lvl * 100 / scale).toString()) + "٪"
        }
        return batteryText
    }

    // ============================== بالای صفحه: کمان آسمان ==============================

    /** پایین (افق) و ارتفاع کمان آسمان بالای صفحه. */
    private fun domeBase(w: Float, h: Float) = if (h >= w) h * 0.145f else h * 0.20f
    private fun domeHeight(w: Float, h: Float) = if (h >= w) h * 0.11f else h * 0.15f

    /** جای یک جرم آسمانی روی کمان: شرق چپ، غرب راست؛ هرچه بالاتر در آسمان، بالاتر روی کمان. */
    private fun domeX(az: Float, w: Float, dx: Float) = w * (0.5f + 0.44f * (az / 120f).coerceIn(-1f, 1f)) + dx * 0.5f
    private fun domeY(alt: Float, w: Float, h: Float) = domeBase(w, h) - domeHeight(w, h) * (alt / 75f).coerceIn(0f, 1f)

    /**
     * بالای صفحه، کم‌رنگ: مسیر امروز خورشید از طلوع (چپ) تا غروب (راست) با ساعت طلوع و غروب،
     * و جای الان خورشید و ماه روی آن. نزدیک طلوع و غروب، خورشید سرخ‌فام است.
     */
    private fun drawSkyDome(c: Canvas, now: Long, w: Float, h: Float, dx: Float, hideSun: Boolean) {
        val minute = now / 60_000L
        if (minute != posMinute) {
            posMinute = minute
            val loc = prefs.location()
            sunInfo = try { SunCalc.sunPosition(now, loc.lat, loc.lon) } catch (_: Exception) { null }
            moonInfo = try { SunCalc.moon(now, loc.lat, loc.lon) } catch (_: Exception) { null }
        }
        val base = min(w, h) * (if (h >= w) 0.048f else 0.040f)
        if (prefs.skyArc && pathAlt.size >= 2) {
            tmpPath.reset()
            for (i in pathAlt.indices) {
                val x = domeX(pathAz[i], w, dx)
                val y = domeY(pathAlt[i], w, h)
                if (i == 0) tmpPath.moveTo(x, y) else tmpPath.lineTo(x, y)
            }
            strokePaint.color = Color.rgb(0xFF, 0xD8, 0x9A)
            strokePaint.alpha = 45
            strokePaint.strokeWidth = max(1f, min(w, h) * 0.003f)
            c.drawPath(tmpPath, strokePaint)
            // خط افق کمان و ساعت طلوع و غروب
            val x0 = domeX(pathAz[0], w, dx)
            val x1 = domeX(pathAz[pathAz.size - 1], w, dx)
            val yb = domeBase(w, h)
            strokePaint.alpha = 25
            c.drawLine(x0 - w * 0.03f, yb, x1 + w * 0.03f, yb, strokePaint)
            smallPaint.typeface = tfLight
            smallPaint.textSize = base * 0.62f
            smallPaint.color = Color.rgb(0xFF, 0xD8, 0x9A)
            smallPaint.alpha = 110
            c.drawText("طلوع " + hm(sunriseToday), x0, yb + smallPaint.textSize * 1.3f, smallPaint)
            c.drawText("غروب " + hm(sunsetToday), x1, yb + smallPaint.textSize * 1.3f, smallPaint)
        }
        val r = min(w, h) * 0.026f
        val m = moonInfo
        if (prefs.moon && m != null && m.alt > 0.0 && m.fraction >= 0.015) {
            drawMoon(c, domeX(m.az.toFloat(), w, dx), domeY(m.alt.toFloat(), w, h), r * 1.05f, m)
        }
        val s = sunInfo
        if (prefs.sunPos && !hideSun && s != null && s.alt > -0.8) {
            drawSunDot(c, domeX(s.az.toFloat(), w, dx), domeY(s.alt.toFloat(), w, h), r, s.alt.toFloat())
        }
    }

    /** نشانهٔ کوچک خورشید؛ نزدیک افق ([alt] کم) سرخ‌فام، بالاتر طلایی. */
    private fun drawSunDot(c: Canvas, sx: Float, sy: Float, r: Float, alt: Float) {
        val k = (alt / 12f).coerceIn(0f, 1f)
        val core = lerpColor(0xFFFF8A5A.toInt(), 0xFFFFE79A.toInt(), k)
        val edge = lerpColor(0xFFE0401C.toInt(), 0xFFFFB040.toInt(), k)
        skyPaint.shader = RadialGradient(sx, sy, r * 2.8f,
            intArrayOf(withAlpha(edge, 0.35f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, r * 2.8f, skyPaint)
        skyPaint.shader = null
        strokePaint.color = edge
        strokePaint.alpha = 200
        strokePaint.strokeWidth = max(1.5f, r * 0.16f)
        for (i in 0 until 8) {
            val a = i * PI / 4
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(sx + ca * r * 1.35f, sy + sa * r * 1.35f, sx + ca * r * 1.85f, sy + sa * r * 1.85f, strokePaint)
        }
        fillPaint.color = core
        fillPaint.alpha = 245
        c.drawCircle(sx, sy, r, fillPaint)
        fillPaint.alpha = 255
    }

    /** هلال ماه با منزل واقعی. */
    private fun drawMoon(c: Canvas, mx: Float, my: Float, r: Float, m: SunCalc.Moon) {
        val moonColor = Color.rgb(0xF4, 0xEC, 0xD2)
        skyPaint.shader = RadialGradient(mx, my, r * 2.6f,
            intArrayOf(withAlpha(moonColor, 0.18f * m.fraction.toFloat() + 0.05f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, r * 2.6f, skyPaint)
        skyPaint.shader = null
        // بخش تاریک (کم‌رنگ)
        fillPaint.color = moonColor
        fillPaint.alpha = 28
        c.drawCircle(mx, my, r, fillPaint)
        // بخش روشن: نیم‌دایرهٔ سمت روشن + بیضی مرز سایه
        val k = cos(2 * PI * m.phase).toFloat()        // ۱ ماه نو، ۰ تربیع، ‎-۱ بدر
        val rx = max(0.5f, r * kotlin.math.abs(k))
        moonPath.reset()
        moonPath.moveTo(mx, my - r)
        moonOval.set(mx - r, my - r, mx + r, my + r)
        moonPath.arcTo(moonOval, -90f, 180f, false)
        moonOval.set(mx - rx, my - r, mx + rx, my + r)
        moonPath.arcTo(moonOval, 90f, if (k > 0f) -180f else 180f, false)
        moonPath.close()
        c.save()
        if (m.phase > 0.5) c.scale(-1f, 1f, mx, my)   // ماه رو به کاهش: سمت روشن چپ
        fillPaint.alpha = 240
        c.drawPath(moonPath, fillPaint)
        c.restore()
        fillPaint.alpha = 255
    }

    // ============================== آب‌وهوا ==============================

    /** گوشهٔ بالا-چپ: آیکون وضعیت هوا و دما (شهر انتخابی). */
    private fun drawWeather(c: Canvas, now: Long, w: Float, h: Float) {
        val minute = now / 60_000L
        if (minute != weatherMinute) {
            weatherMinute = minute
            val loc = prefs.location()
            weatherNow = Weather.get(context, loc.lat, loc.lon)
        }
        val wn = weatherNow ?: return
        val s = min(w, h) * 0.058f
        val ix = w * 0.07f + s * 0.5f
        val iy = (if (h >= w) h * 0.035f else h * 0.07f) + s * 0.5f
        drawWeatherIcon(c, ix, iy, s, Weather.kind(wn.code), wn.day)
        smallPaint.typeface = tfMedium
        smallPaint.textSize = s * 0.62f
        smallPaint.color = Color.WHITE
        smallPaint.alpha = 200
        smallPaint.textAlign = Paint.Align.LEFT
        c.drawText(fa(wn.temp.roundToInt().toString()) + "°", ix + s * 0.75f, iy + s * 0.22f, smallPaint)
        smallPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawWeatherIcon(c: Canvas, x: Float, y: Float, s: Float, kind: Int, day: Boolean) {
        when (kind) {
            Weather.CLEAR -> if (day) sunIcon(c, x, y, s) else moonIcon(c, x, y, s)
            Weather.PARTLY -> {
                if (day) sunIcon(c, x - s * 0.18f, y - s * 0.16f, s * 0.75f) else moonIcon(c, x - s * 0.18f, y - s * 0.16f, s * 0.75f)
                cloudIcon(c, x + s * 0.08f, y + s * 0.10f, s * 0.82f, Color.rgb(0xDD, 0xE2, 0xEA))
            }
            Weather.CLOUDY -> cloudIcon(c, x, y, s, Color.rgb(0xC8, 0xCE, 0xD8))
            Weather.FOG -> {
                strokePaint.color = Color.rgb(0xB8, 0xBE, 0xC8)
                strokePaint.alpha = 210
                strokePaint.strokeWidth = s * 0.09f
                for (i in -1..1) c.drawLine(x - s * 0.45f + i * s * 0.06f, y + i * s * 0.22f, x + s * 0.45f + i * s * 0.06f, y + i * s * 0.22f, strokePaint)
            }
            Weather.RAIN -> {
                cloudIcon(c, x, y - s * 0.12f, s, Color.rgb(0xB8, 0xC2, 0xD2))
                strokePaint.color = Color.rgb(0x6E, 0xB6, 0xFF)
                strokePaint.alpha = 230
                strokePaint.strokeWidth = s * 0.07f
                for (i in -1..1) c.drawLine(x + i * s * 0.25f, y + s * 0.30f, x + i * s * 0.25f - s * 0.08f, y + s * 0.52f, strokePaint)
            }
            Weather.SNOW -> {
                cloudIcon(c, x, y - s * 0.12f, s, Color.rgb(0xC8, 0xD0, 0xDC))
                fillPaint.color = Color.WHITE
                fillPaint.alpha = 240
                for (i in -1..1) c.drawCircle(x + i * s * 0.25f, y + s * 0.42f + (i and 1) * s * 0.06f, s * 0.06f, fillPaint)
            }
            Weather.STORM -> {
                cloudIcon(c, x, y - s * 0.12f, s, Color.rgb(0x8C, 0x94, 0xA4))
                tmpPath.reset()
                tmpPath.moveTo(x + s * 0.05f, y + s * 0.12f)
                tmpPath.lineTo(x - s * 0.14f, y + s * 0.38f)
                tmpPath.lineTo(x + s * 0.02f, y + s * 0.38f)
                tmpPath.lineTo(x - s * 0.08f, y + s * 0.62f)
                tmpPath.lineTo(x + s * 0.20f, y + s * 0.30f)
                tmpPath.lineTo(x + s * 0.04f, y + s * 0.30f)
                tmpPath.close()
                fillPaint.color = Color.rgb(0xFF, 0xD5, 0x4F)
                fillPaint.alpha = 240
                c.drawPath(tmpPath, fillPaint)
            }
        }
        fillPaint.alpha = 255
    }

    private fun sunIcon(c: Canvas, x: Float, y: Float, s: Float) {
        val col = Color.rgb(0xFF, 0xC8, 0x3D)
        strokePaint.color = col
        strokePaint.alpha = 220
        strokePaint.strokeWidth = s * 0.07f
        for (i in 0 until 8) {
            val a = i * PI / 4
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(x + ca * s * 0.34f, y + sa * s * 0.34f, x + ca * s * 0.48f, y + sa * s * 0.48f, strokePaint)
        }
        fillPaint.color = col
        fillPaint.alpha = 235
        c.drawCircle(x, y, s * 0.24f, fillPaint)
    }

    private fun moonIcon(c: Canvas, x: Float, y: Float, s: Float) {
        tmpPath.reset()
        tmpPath.addCircle(x, y, s * 0.32f, Path.Direction.CW)
        val cut = Path().apply { addCircle(x + s * 0.17f, y - s * 0.10f, s * 0.27f, Path.Direction.CW) }
        tmpPath.op(cut, Path.Op.DIFFERENCE)
        fillPaint.color = Color.rgb(0xF4, 0xEC, 0xD2)
        fillPaint.alpha = 230
        c.drawPath(tmpPath, fillPaint)
    }

    private fun cloudIcon(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        fillPaint.color = col
        fillPaint.alpha = 225
        c.drawCircle(x - s * 0.24f, y + s * 0.06f, s * 0.20f, fillPaint)
        c.drawCircle(x + s * 0.02f, y - s * 0.08f, s * 0.27f, fillPaint)
        c.drawCircle(x + s * 0.27f, y + s * 0.08f, s * 0.18f, fillPaint)
        c.drawRoundRect(x - s * 0.44f, y + s * 0.02f, x + s * 0.45f, y + s * 0.26f, s * 0.12f, s * 0.12f, fillPaint)
    }

    // ============================== افق: طلوع و غروب ==============================

    /** نوار آسمان از افق به بالا و هالهٔ پهن روی افق. */
    private fun horizonGlow(c: Canvas, w: Float, h: Float, cH: Int, cU: Int, glowH: Float, level: Float) {
        val hy = horizonY
        val sunX = w * 0.5f
        skyPaint.shader = LinearGradient(0f, hy, 0f, hy - glowH,
            intArrayOf(withAlpha(cH, level * 0.85f), withAlpha(cU, level * 0.50f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, hy - glowH, w, hy + 2f, skyPaint)
        c.save()
        c.scale(1f, 0.45f, sunX, hy)
        skyPaint.shader = RadialGradient(sunX, hy, w * 0.75f,
            intArrayOf(withAlpha(cH, level * 0.85f), withAlpha(cH, level * 0.30f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(sunX, hy, w * 0.75f, skyPaint)
        c.restore()
        skyPaint.shader = null
    }

    private fun sunDisc(c: Canvas, sx: Float, sy: Float, r: Float, core: Int, edge: Int, level: Float) {
        skyPaint.shader = RadialGradient(sx, sy, r * 3.4f,
            intArrayOf(withAlpha(edge, level * 0.55f), withAlpha(edge, level * 0.16f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, r * 3.4f, skyPaint)
        skyPaint.shader = RadialGradient(sx, sy, r,
            intArrayOf(withAlpha(core, level), withAlpha(edge, level)),
            floatArrayOf(0.2f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, r, skyPaint)
        skyPaint.shader = null
    }

    /** زمین سیاه روی پایین خورشید و خط نازک روشن روی افق. */
    private fun horizonLine(c: Canvas, w: Float, h: Float, cH: Int, level: Float) {
        c.drawPath(ground, groundPaint)
        strokePaint.color = cH
        strokePaint.alpha = (level * 90).toInt()
        strokePaint.strokeWidth = max(1f, h * 0.0015f)
        c.drawLine(w * 0.38f, horizonY, w * 0.64f, horizonY, strokePaint)
    }

    /**
     * طلوع. [t] = دقیقه نسبت به لحظهٔ طلوع (منفی = پیش از طلوع).
     * از ‎-dawnMinutes (پیش‌فرض ۱۰ دقیقه) تا ۰: اول سفیدی ملایم صبح در افق، بعد سرخی، و نزدیک طلوع
     * طلایی در جای درآمدن خورشید. سر وقت طلوع (۰ تا ۳ دقیقه): قرص خورشید از پشت افق درمی‌آید.
     * تا ۲۰ دقیقه پس از طلوع: خورشید آرام بالا می‌رود و آسمان آبی روشن صبح می‌شود؛ سپس در یک دقیقه محو می‌شود.
     * شدت کلی (۰ تا ۱) را برمی‌گرداند.
     */
    private fun drawSunrise(c: Canvas, t: Float, w: Float, h: Float): Float {
        val dawn = prefs.dawnMinutes.toFloat()
        if (t < -dawn || t > SKY_END_MIN) return 0f
        val r = min(w, h) * 0.085f
        val level: Float
        val cH: Int
        val cU: Int
        val glowH: Float
        if (t < 0f) {
            val p = (t + dawn) / dawn                    // ۰ ← شروع سفیدی صبح، ۱ ← لحظهٔ طلوع
            level = p.pow(1.3f)
            cH = when {
                p < 0.45f -> lerpColor(DAWN_DIM, DAWN_WHITE, p / 0.45f)
                p < 0.85f -> lerpColor(DAWN_WHITE, DAWN_RED, (p - 0.45f) / 0.40f)
                else -> lerpColor(DAWN_RED, DAWN_GOLD, (p - 0.85f) / 0.15f)
            }
            cU = lerpColor(0xFF0A1226.toInt(), MORNING_BLUE, p)
            glowH = h * (0.18f + 0.32f * p)
        } else {
            val k = min(1f, t / SUN_FULL_MIN)
            level = if (t <= SUN_FULL_MIN) 1f else max(0f, 1f - (t - SUN_FULL_MIN) / (SKY_END_MIN - SUN_FULL_MIN))
            cH = lerpColor(DAWN_GOLD, 0xFFFFF4DC.toInt(), k)
            cU = lerpColor(MORNING_BLUE, 0xFF86A9D6.toInt(), k)
            glowH = h * 0.50f
        }
        if (level <= 0.001f) return 0f
        horizonGlow(c, w, h, cH, cU, glowH, level)
        // قرص خورشید: ۳ دقیقه درآمدن از پشت افق، سپس تا ۲۰ دقیقه آرام بالا رفتن
        if (t >= 0f) {
            val f = smooth(min(1f, t / SUN_EMERGE_MIN))
            var sy = horizonY + r * 1.02f - 2.04f * r * f
            if (t > SUN_EMERGE_MIN) sy -= (t - SUN_EMERGE_MIN) / (SUN_FULL_MIN - SUN_EMERGE_MIN) * SUN_RISE_RADII * r
            val k2 = min(1f, t / 10f)
            sunDisc(c, w * 0.5f, sy, r, lerpColor(0xFFFFD98A.toInt(), 0xFFFFFAE6.toInt(), k2),
                lerpColor(0xFFFF9440.toInt(), 0xFFFFD27A.toInt(), k2), level)
        }
        horizonLine(c, w, h, cH, level)
        return level
    }

    /**
     * غروب. [t] = دقیقه نسبت به لحظهٔ غروب. از ۲۰ دقیقه پیش از غروب افق طلایی و کم‌کم سرخ می‌شود
     * و خورشید آرام پایین می‌آید؛ سر وقت غروب پشت افق فرو می‌رود؛ سرخی پس از غروب ۱۵ دقیقه آرام محو می‌شود.
     */
    private fun drawSunset(c: Canvas, t: Float, w: Float, h: Float): Float {
        if (t < -SUNSET_START_MIN || t > SUNSET_END_MIN) return 0f
        val r = min(w, h) * 0.085f
        val level: Float
        val cH: Int
        val cU: Int
        if (t < 0f) {
            val p = (t + SUNSET_START_MIN) / SUNSET_START_MIN      // ۰ ← شروع، ۱ ← لحظهٔ غروب
            level = smooth(min(1f, p * 3f)) * (0.75f + 0.25f * p)
            cH = lerpColor(0xFFFFC870.toInt(), 0xFFFF5A2E.toInt(), p)
            cU = lerpColor(0xFF4A6A98.toInt(), 0xFF6A2A50.toInt(), p)
        } else {
            val q = t / SUNSET_END_MIN                               // ۰ ← غروب، ۱ ← پایان
            level = 1f - smooth(q)
            cH = lerpColor(0xFFE0401C.toInt(), 0xFF6A1222.toInt(), q)
            cU = lerpColor(0xFF6A2A50.toInt(), 0xFF101634.toInt(), q)
        }
        if (level <= 0.001f) return 0f
        horizonGlow(c, w, h, cH, cU, h * 0.42f, level)
        if (t < 0.2f) {
            // خورشید: تا ۳ دقیقه مانده آرام پایین می‌آید، بعد پشت افق فرو می‌رود
            val sy = if (t < -SUN_EMERGE_MIN)
                horizonY - r * 1.02f - (-SUN_EMERGE_MIN - t) / (SUNSET_START_MIN - SUN_EMERGE_MIN) * 2.0f * r
            else horizonY - r * 1.02f + 2.04f * r * smooth(((t + SUN_EMERGE_MIN) / SUN_EMERGE_MIN).coerceIn(0f, 1f))
            val p = ((t + SUNSET_START_MIN) / SUNSET_START_MIN).coerceIn(0f, 1f)
            sunDisc(c, w * 0.5f, sy, r, lerpColor(0xFFFFE6A8.toInt(), 0xFFFF9A50.toInt(), p),
                lerpColor(0xFFFFA040.toInt(), 0xFFE0401C.toInt(), p), max(level, 0.6f))
        }
        horizonLine(c, w, h, cH, level)
        return level
    }

    /**
     * آسمان نیمه‌شب: از ۲۰ دقیقه پیش از اذان صبح، پس‌زمینه آرام سرمه‌ای با ستاره‌های کم‌نور می‌شود؛
     * بعد از اذان کمی روشن‌تر (آبی سحر) و پس از طلوع محو می‌شود. شدت (۰ تا ۱) را برمی‌گرداند.
     */
    private fun drawNight(c: Canvas, now: Long, w: Float, h: Float): Float {
        val start = fajrToday - NIGHT_BEFORE_FAJR_MS
        val end = sunriseToday + 10 * 60_000L
        if (now < start || now > end) return 0f
        var n = min(1f, (now - start) / 120_000f)                       // ۲ دقیقه آرام ظاهر می‌شود
        if (now > sunriseToday) n *= 1f - (now - sunriseToday) / 600_000f // ۱۰ دقیقه پس از طلوع محو
        if (n <= 0.001f) return 0f
        val dawnStart = sunriseToday - prefs.dawnMinutes * 60_000L
        val k = if (now <= fajrToday) 0f else min(1f, (now - fajrToday).toFloat() / max(1L, dawnStart - fajrToday))
        val top = lerpColor(0xFF000510.toInt(), 0xFF020A24.toInt(), k)
        val mid = lerpColor(0xFF06102C.toInt(), 0xFF0B1E4A.toInt(), k)
        val bot = lerpColor(0xFF0C1E4E.toInt(), 0xFF1C3A78.toInt(), k)
        skyPaint.shader = LinearGradient(0f, 0f, 0f, horizonY,
            intArrayOf(withAlpha(top, n), withAlpha(mid, n), withAlpha(bot, n)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, horizonY + 2f, skyPaint)
        skyPaint.shader = null
        // ستاره‌ها (بعد از اذان کم‌کم کم‌رنگ)
        fillPaint.color = Color.WHITE
        val sr = max(1.2f, min(w, h) * 0.0022f)
        for (i in 0 until stars.size / 3) {
            val a = (0.25f + 0.55f * stars[3 * i + 2]) * n * (1f - 0.8f * k)
            fillPaint.alpha = (a * 255).toInt()
            c.drawCircle(stars[3 * i] * w, stars[3 * i + 1] * horizonY, sr * (0.6f + stars[3 * i + 2]), fillPaint)
        }
        fillPaint.alpha = 255
        return n
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((Color.alpha(c) * a.coerceIn(0f, 1f)).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }

    companion object {
        /** خورشید تا چند دقیقه پس از طلوع در حال بالا آمدن نمایش داده شود. */
        const val SUN_FULL_MIN = 20f
        /** پایان کامل نمایش طلوع (پس از یک دقیقه محو شدن). */
        const val SKY_END_MIN = 21f
        const val SUN_EMERGE_MIN = 3f
        /** خورشید از ۳ تا ۲۰ دقیقه چند برابر شعاعش بالا برود. */
        const val SUN_RISE_RADII = 2.8f
        /** غروب: از چند دقیقه پیش از غروب شروع و تا چند دقیقه پس از آن ادامه. */
        const val SUNSET_START_MIN = 20f
        const val SUNSET_END_MIN = 15f
        const val STYLE_ANALOG_DIGITAL = 6
        /** رقم‌های لاتین و فارسی؛ برای رسم هم‌پهنا. */
        const val DIGITS = "0123456789۰۱۲۳۴۵۶۷۸۹"
        const val DEMO_SPEED = 30L
        const val NIGHT_BEFORE_FAJR_MS = 20 * 60_000L
        val HOLIDAY_RED = Color.rgb(0xFF, 0x4D, 0x4D)
        val KAABA_GOLD = Color.rgb(0xE8, 0xC2, 0x5A)
        val DAWN_DIM = Color.rgb(0x2A, 0x33, 0x46)
        val DAWN_WHITE = Color.rgb(0xEE, 0xEC, 0xE6)
        val DAWN_RED = Color.rgb(0xF2, 0x87, 0x6A)
        val DAWN_GOLD = Color.rgb(0xFF, 0xE2, 0xB4)
        val MORNING_BLUE = Color.rgb(0x5E, 0x75, 0x98)
    }
}
