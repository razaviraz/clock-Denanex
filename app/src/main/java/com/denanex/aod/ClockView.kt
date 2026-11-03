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
import kotlin.math.sin

/**
 * نمای اصلی: پس‌زمینهٔ کاملاً سیاه (کم‌مصرف روی AMOLED)، ساعت درشت (دیجیتال یا عقربه‌ای)،
 * تاریخ خورشیدی، باتری، و چند دقیقه پیش از طلوع: سفیدی ملایم صبح در افق که کم‌کم روشن می‌شود
 * و سر وقت طلوع (افق شرعی شهر انتخابی) قرص خورشید از پشت افق درمی‌آید و ۲۰ دقیقه آرام بالا می‌رود.
 */
class ClockView(ctx: Context) : View(ctx) {

    var prefs = Prefs(ctx)
        private set

    /** شدت شفق/طلوع (۰ تا ۱) هر بار که عوض شود؛ برای بیشتر کردن نور صفحه. */
    var onSkyLevel: ((Float) -> Unit)? = null

    // ---------- زمان‌بندی ----------
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = Runnable {
        invalidate()
        scheduleNext()
    }

    // پیش‌نمایش طلوع: زمان مجازی با سرعت ۳۰ برابر از چند ثانیه پیش از شروع شفق
    private var demoActive = false
    private var demoReal = 0L
    private var demoBase = 0L

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
    private var horizonY = 0f
    private val cal = Calendar.getInstance()

    // حافظهٔ کوتاه‌مدت طلوع امروز و فردا (محاسبه فقط وقتی روز یا شهر عوض شود)
    private var cacheKey = ""
    private var sunriseToday = 0L
    private var sunriseTomorrow = 0L
    private var fajrToday = 0L
    private var fajrTomorrow = 0L

    // ماه (هر دقیقه یک بار حساب می‌شود)
    private var moonMinute = -1L
    private var moonInfo: SunCalc.Moon? = null
    // جای خورشید (هر دقیقه یک بار حساب می‌شود)
    private var sunMinute = -1L
    private var sunInfo: SunCalc.SunPos? = null
    private val moonPath = Path()
    private val moonOval = RectF()

    // ستاره‌های آسمان نیمه‌شب (جای ثابت)
    private val stars = FloatArray(3 * 46).also {
        val r = Random(7)
        for (i in 0 until 46) { it[3 * i] = r.nextFloat(); it[3 * i + 1] = r.nextFloat() * 0.8f; it[3 * i + 2] = r.nextFloat() }
    }

    /** محدودهٔ خط «راهنمای نماز شب» روی صفحه (برای باز کردن با ضربه). */
    val guideRect = RectF()

    private var batteryMinute = -1L
    private var batteryText = ""
    private var lastLevel = -1f

    /** شمارهٔ نسخهٔ نصب‌شده، برای پایین صفحه. */
    private var versionText = ""

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
    }

    fun reload() {
        prefs = Prefs(context)
        versionText = Updater.currentName(context).let { if (it.isEmpty()) "" else "نسخهٔ " + Persian.digits(it) }
        cacheKey = ""
        batteryMinute = -1
        moonMinute = -1
        sunMinute = -1
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** پیش‌نمایش سریع شفق و طلوع (حدود یک دقیقه). */
    fun startDemo() {
        val now = System.currentTimeMillis()
        ensureSunrise(now)
        demoBase = sunriseToday - prefs.dawnMinutes * 60_000L - 4_000L
        demoReal = SystemClock.elapsedRealtime()
        demoActive = true
        handler.removeCallbacks(tick)
        if (running) handler.post(tick)
    }

    private fun nowMs(): Long {
        if (demoActive) {
            val v = demoBase + (SystemClock.elapsedRealtime() - demoReal) * DEMO_SPEED
            if (v - sunriseToday > (SKY_END_MIN + 0.3) * 60_000) demoActive = false else return v
        }
        return System.currentTimeMillis()
    }

    private fun scheduleNext() {
        if (!running) return
        val now = nowMs()
        ensureSunrise(now)
        val t = (now - sunriseToday) / 60_000.0
        val skyOn = prefs.sunrise && t >= -prefs.dawnMinutes && t <= SKY_END_MIN
        var delay: Long = when {
            demoActive -> 33L
            skyOn -> 100L
            prefs.seconds -> 1000L - now % 1000L
            else -> 60_000L - now % 60_000L + 15L
        }
        // شروع آسمان نیمه‌شب و شفق معمولاً وسط دقیقه است؛ از آن جا نمانیم
        if (!demoActive) {
            for (ev in longArrayOf(sunriseToday - prefs.dawnMinutes * 60_000L, fajrToday - NIGHT_BEFORE_FAJR_MS)) {
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
        fajrToday = SunCalc.fajr(noon, loc.lat, loc.lon) ?: (sunriseToday - 90 * 60_000L)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        sunriseTomorrow = SunCalc.sunrise(cal.timeInMillis, loc.lat, loc.lon) ?: (sunriseToday + 86_400_000L)
        fajrTomorrow = SunCalc.fajr(cal.timeInMillis, loc.lat, loc.lon) ?: (sunriseTomorrow - 90 * 60_000L)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        horizonY = h * (if (h >= w) 0.84f else 0.83f)
        // افق: کوه‌پایه‌های کم‌ارتفاع در دو طرف و افق صاف در وسط (جای طلوع خورشید)
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
        val now = nowMs()
        ensureSunrise(now)
        canvas.drawColor(Color.BLACK)

        // ۱) آسمان: نیمه‌شب پیش از اذان صبح، شفق و طلوع
        val night = if (prefs.nightSky) drawNight(canvas, now, w, h) else 0f
        val tMin = ((now - sunriseToday) / 60_000.0).toFloat()
        val level = if (prefs.sunrise) drawSky(canvas, tMin, w, h) else 0f
        if (night > 0f || level > 0f) {
            // زمین/کوه‌ها (سیاه) — پایین خورشید را می‌پوشاند تا از پشت افق بالا بیاید
            canvas.drawPath(ground, groundPaint)
        }
        // روی آسمان روشن صبح، سایهٔ تیرهٔ ملایم زیر نوشته‌ها تا خوانا بمانند
        if (level > 0.05f) {
            val sh = Color.argb((190 * level).toInt(), 0, 0, 0)
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
        val cx = w / 2f + dx
        // شکل «عقربه‌ای + دیجیتال درشت» بلندتر است؛ کمی بالاتر رسم شود
        val cy = (if (prefs.style == STYLE_ANALOG_DIGITAL) (if (portrait) h * 0.29f else h * 0.25f)
                  else (if (portrait) h * 0.36f else h * 0.30f)) + dy

        // ۳) هلال ماه و جای خورشید بالای ساعت
        if (prefs.moon) drawMoon(canvas, now, w, h, dx)
        // هنگام نمایش طلوع، خورشید بزرگ روی افق هست؛ نشانهٔ کوچک بالا تا پایان آن نمایش داده نمی‌شود
        if (prefs.sunPos && !(level > 0f && tMin >= 0f)) drawSunMarker(canvas, now, w, h, dx)

        cal.timeInMillis = now
        val color = prefs.mainColor()
        val bottom = when (prefs.style) {
            0 -> drawDigital(canvas, cx, cy, w, h, tfThin, color)
            1 -> drawDigital(canvas, cx, cy, w, h, tfBlack, color)
            2 -> drawStacked(canvas, cx, cy, w, h, color)
            else -> drawAnalog(canvas, cx, cy, w, h, color, prefs.style)
        }
        drawInfo(canvas, cx, bottom, w, h, now, color)
        drawFooter(canvas, cx, w, h)
    }

    private fun fa(s: String) = Persian.digits(s, prefs.persianDigits)
    private fun two(n: Int) = if (n < 10) "0$n" else n.toString()

    private fun hourValue(): Int {
        val hr = cal.get(Calendar.HOUR_OF_DAY)
        if (prefs.h24) return hr
        val x = hr % 12
        return if (x == 0) 12 else x
    }

    /** اندازهٔ قلم تا متن [sample] دقیقاً پهنای [targetW] شود، و ارتفاعش از [maxH] بیشتر نشود. */
    private fun fit(p: Paint, sample: String, targetW: Float, maxH: Float) {
        p.textSize = 100f
        val mw = p.measureText(sample)
        var size = if (mw > 0f) 100f * targetW / mw else 100f
        size = min(size, maxH / 0.75f)
        p.textSize = size
    }

    /** ساعت دیجیتال یک‌خطی؛ پایین بلوک را برمی‌گرداند. */
    private fun drawDigital(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, tf: Typeface, color: Int): Float {
        val portrait = h >= w
        timePaint.typeface = tf
        timePaint.color = color
        timePaint.alpha = 255
        val txt = fa(two(hourValue()) + ":" + two(cal.get(Calendar.MINUTE)))
        fit(timePaint, fa("00:00"), if (portrait) w * 0.88f else w * 0.55f, if (portrait) h * 0.20f else h * 0.30f)
        val fm = timePaint.fontMetrics
        val base = cy - (fm.ascent + fm.descent) / 2f
        drawFixed(c, txt, cx, base, timePaint)
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

    /** ساعت عقربه‌ای (چهار شکل؛ آخری با ساعت دیجیتال درشت زیرش). */
    private fun drawAnalog(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, color: Int, style: Int): Float {
        val portrait = h >= w
        val withDigital = style == STYLE_ANALOG_DIGITAL
        val r = when {
            withDigital && portrait -> min(w * 0.33f, h * 0.16f)
            withDigital -> min(h * 0.17f, w * 0.22f)
            portrait -> min(w * 0.40f, h * 0.23f)
            else -> min(h * 0.24f, w * 0.30f)
        }
        val sec = cal.get(Calendar.SECOND) + (if (prefs.seconds) cal.get(Calendar.MILLISECOND) / 1000f else 0f)
        val minute = cal.get(Calendar.MINUTE) + sec / 60f
        val hour = (cal.get(Calendar.HOUR_OF_DAY) % 12) + minute / 60f

        when (style) {
            3, STYLE_ANALOG_DIGITAL -> { // کلاسیک: خط‌های دقیقه و ساعت + اعداد ۱۲، ۳، ۶، ۹
                for (i in 0 until 60) {
                    val big = i % 5 == 0
                    strokePaint.color = color
                    strokePaint.alpha = if (big) 255 else 120
                    strokePaint.strokeWidth = if (big) r * 0.03f else r * 0.012f
                    val a = i * PI / 30
                    val r1 = if (big) r * 0.86f else r * 0.93f
                    c.drawLine(cx + (sin(a) * r1).toFloat(), cy - (cos(a) * r1).toFloat(),
                        cx + (sin(a) * r).toFloat(), cy - (cos(a) * r).toFloat(), strokePaint)
                }
                numbers(c, cx, cy, r * 0.70f, r * 0.17f, color, intArrayOf(12, 3, 6, 9), tfLight)
            }
            4 -> { // ساده: فقط نقطه‌ها
                fillPaint.color = color
                for (i in 0 until 12) {
                    val a = i * PI / 6
                    fillPaint.alpha = if (i % 3 == 0) 255 else 150
                    val dot = if (i % 3 == 0) r * 0.045f else r * 0.022f
                    c.drawCircle(cx + (sin(a) * r * 0.95f).toFloat(), cy - (cos(a) * r * 0.95f).toFloat(), dot, fillPaint)
                }
            }
            else -> { // اعداد فارسی ۱ تا ۱۲ + حلقهٔ نازک
                strokePaint.color = color
                strokePaint.alpha = 90
                strokePaint.strokeWidth = r * 0.012f
                c.drawCircle(cx, cy, r, strokePaint)
                val all = IntArray(12) { it + 1 }
                numbers(c, cx, cy, r * 0.80f, r * 0.16f, color, all, tfMedium, forcePersian = true)
            }
        }

        val thin = style == 4
        hand(c, cx, cy, hour * 30f, r * 0.52f, if (thin) r * 0.035f else r * 0.06f, color, 255)
        hand(c, cx, cy, minute * 6f, r * 0.80f, if (thin) r * 0.022f else r * 0.04f, color, 235)
        if (prefs.seconds) {
            val accent = Color.rgb(0xFF, 0x70, 0x43)
            hand(c, cx, cy, sec * 6f, r * 0.88f, r * 0.012f, accent, 255, tail = r * 0.15f)
            fillPaint.color = accent
        } else fillPaint.color = color
        fillPaint.alpha = 255
        c.drawCircle(cx, cy, r * 0.05f, fillPaint)
        fillPaint.color = Color.BLACK
        c.drawCircle(cx, cy, r * 0.018f, fillPaint)

        if (withDigital) return drawBigDigitalUnder(c, cx, cy + r, w, h, color)

        // زیر ساعت عقربه‌ای، ساعت دیجیتال (درشت‌تر از قبل)
        smallPaint.typeface = tfMedium
        smallPaint.color = color
        smallPaint.alpha = 220
        smallPaint.textSize = r * 0.30f
        var base = cy + r + smallPaint.textSize * 1.25f
        drawFixed(c, fa(two(hourValue()) + ":" + two(cal.get(Calendar.MINUTE))), cx, base, smallPaint)
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
        fit(timePaint, fa("00:00"), if (portrait) w * 0.80f else w * 0.36f, if (portrait) h * 0.105f else h * 0.15f)
        val fm = timePaint.fontMetrics
        val base = top + timePaint.textSize * 0.15f - fm.ascent * 0.80f
        drawFixed(c, fa(two(hourValue()) + ":" + two(cal.get(Calendar.MINUTE))), cx, base, timePaint)
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

    /** زیر ساعت: افق شهر، تاریخ (جمعه و تعطیل رسمی قرمز)، مانده به اذان صبح و طلوع آفتاب، باتری. */
    private fun drawInfo(c: Canvas, cx: Float, top: Float, w: Float, h: Float, now: Long, color: Int) {
        val base = min(w, h) * (if (h >= w) 0.048f else 0.040f)
        val big = base * 1.3f        // «دو سایز درشت‌تر»
        smallPaint.color = color
        var y = top + base * 1.5f

        // افق شرعی شهر، درست زیر ساعت (فقط وقتی اوقات شرعی نمایش داده می‌شود)
        if (prefs.showSunrise) {
            smallPaint.typeface = tfLight
            smallPaint.textSize = base * 0.9f
            smallPaint.alpha = 170
            c.drawText("به افق " + prefs.location().name, cx, y, smallPaint)
            y += big * 1.55f
        } else y += big * 0.5f

        if (prefs.showDate) {
            val gy = cal.get(Calendar.YEAR); val gm = cal.get(Calendar.MONTH) + 1; val gd = cal.get(Calendar.DAY_OF_MONTH)
            val j = Persian.toJalali(gy, gm, gd)
            val hj = Hijri.fromGregorian(gy, gm, gd, prefs.hijriOffset)
            val friday = cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
            val hol = Hijri.holiday(j[1], j[2], hj)
            val d = Persian.weekday(cal.get(Calendar.DAY_OF_WEEK)) + "  " +
                fa(j[2].toString()) + " " + Persian.months[j[1] - 1] + " " + fa(j[0].toString())
            val dateSize = big * 1.45f      // تاریخ خورشیدی درشت
            y += dateSize - big
            smallPaint.typeface = tfMedium
            smallPaint.textSize = dateSize
            if (friday || hol != null) smallPaint.color = HOLIDAY_RED
            smallPaint.alpha = 235
            c.drawText(d, cx, y, smallPaint)
            if (hol != null) {
                y += base * 1.25f
                smallPaint.typeface = tfLight
                smallPaint.textSize = base * 0.85f
                smallPaint.alpha = 220
                c.drawText(hol, cx, y, smallPaint)
            }
            smallPaint.color = color
            y += dateSize * 1.25f
        }

        if (prefs.showSunrise) {
            smallPaint.typeface = tfLight
            smallPaint.textSize = big * 0.92f
            smallPaint.alpha = 225
            val nextFajr = if (now < fajrToday) fajrToday else fajrTomorrow
            val nextSun = if (now < sunriseToday) sunriseToday else sunriseTomorrow
            c.drawText("اذان صبح " + hm(nextFajr) + "  •  " + remain(nextFajr - now), cx, y, smallPaint)
            y += big * 1.45f
            c.drawText("طلوع آفتاب " + hm(nextSun) + "  •  " + remain(nextSun - now), cx, y, smallPaint)
            y += big * 1.3f
        }
        if (prefs.showBattery) {
            val b = battery(now)
            if (b.isNotEmpty()) {
                // درصد باتری درشت و روشن
                smallPaint.typeface = tfMedium
                smallPaint.alpha = 200
                smallPaint.textSize = base * 1.5f
                y += base * 0.45f
                c.drawText(b, cx, y, smallPaint)
            }
        }
    }

    /** پایین صفحه (روی زمین سیاه): «راهنمای نماز شب» (با ضربه باز می‌شود)، نام تهیه‌کننده و شمارهٔ نسخه. */
    private fun drawFooter(c: Canvas, cx: Float, w: Float, h: Float) {
        val base = min(w, h) * (if (h >= w) 0.042f else 0.034f)
        val gap = h - horizonY
        smallPaint.typeface = tfMedium
        smallPaint.textSize = base
        smallPaint.color = GUIDE_GOLD
        smallPaint.alpha = 210
        smallPaint.isUnderlineText = true
        val gy = horizonY + gap * 0.36f
        c.drawText("🌙 راهنمای نماز شب", cx, gy, smallPaint)
        smallPaint.isUnderlineText = false
        guideRect.set(cx - w * 0.35f, gy - base * 1.6f, cx + w * 0.35f, gy + base * 0.9f)

        smallPaint.typeface = tfLight
        smallPaint.textSize = base * 0.8f
        smallPaint.color = Color.WHITE
        smallPaint.alpha = 120
        c.drawText(Prefs.CREDIT, cx, horizonY + gap * 0.66f, smallPaint)

        if (versionText.isNotEmpty()) {
            smallPaint.textSize = base * 0.62f
            smallPaint.alpha = 95
            c.drawText(versionText, cx, horizonY + gap * 0.88f, smallPaint)
        }
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

    // ============================== آسمان ==============================

    /**
     * [t] = دقیقه نسبت به لحظهٔ طلوع (منفی = پیش از طلوع).
     * از ‎-dawnMinutes (پیش‌فرض ۱۰ دقیقه) تا ۰: سفیدی ملایم صبح کم‌کم در افق پیدا و روشن می‌شود
     * (خاکستری‌آبی کم‌رنگ ← سفید ملایم، و نزدیک طلوع کمی طلایی در جای درآمدن خورشید).
     * سر وقت طلوع (۰ تا ۳ دقیقه): قرص خورشید از پشت افق درمی‌آید.
     * تا ۲۰ دقیقه پس از طلوع: خورشید آرام بالا می‌رود و آسمان آبی روشن صبح می‌شود؛ سپس در یک دقیقه محو می‌شود.
     * شدت کلی (۰ تا ۱) را برمی‌گرداند.
     */
    private fun drawSky(c: Canvas, t: Float, w: Float, h: Float): Float {
        val dawn = prefs.dawnMinutes.toFloat()
        if (t < -dawn || t > SKY_END_MIN) return 0f
        val hy = horizonY
        val sunX = w * 0.5f
        val r = min(w, h) * 0.085f

        val level: Float
        val cH: Int
        val cU: Int
        val glowH: Float
        if (t < 0f) {
            val p = (t + dawn) / dawn                    // ۰ ← شروع سفیدی صبح، ۱ ← لحظهٔ طلوع
            level = p.pow(1.3f)
            cH = if (p < 0.7f) lerpColor(DAWN_DIM, DAWN_WHITE, p / 0.7f)
                 else lerpColor(DAWN_WHITE, DAWN_GOLD, (p - 0.7f) / 0.3f)
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

        // نوار آسمان از افق به بالا (ملایم: کمی کم‌رنگ‌تر از رنگ کامل)
        skyPaint.shader = LinearGradient(0f, hy, 0f, hy - glowH,
            intArrayOf(withAlpha(cH, level * 0.85f), withAlpha(cU, level * 0.50f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, hy - glowH, w, hy + 2f, skyPaint)

        // هالهٔ پهن و تخت روی افق، همان‌جا که خورشید درمی‌آید
        c.save()
        c.scale(1f, 0.45f, sunX, hy)
        skyPaint.shader = RadialGradient(sunX, hy, w * 0.75f,
            intArrayOf(withAlpha(cH, level * 0.85f), withAlpha(cH, level * 0.30f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(sunX, hy, w * 0.75f, skyPaint)
        c.restore()

        // قرص خورشید: ۳ دقیقه درآمدن از پشت افق، سپس تا ۲۰ دقیقه آرام بالا رفتن
        if (t >= 0f) {
            val f = smooth(min(1f, t / SUN_EMERGE_MIN))
            var sy = hy + r * 1.02f - 2.04f * r * f
            if (t > SUN_EMERGE_MIN) sy -= (t - SUN_EMERGE_MIN) / (SUN_FULL_MIN - SUN_EMERGE_MIN) * SUN_RISE_RADII * r
            val k2 = min(1f, t / 10f)
            val core = lerpColor(0xFFFFD98A.toInt(), 0xFFFFFAE6.toInt(), k2)
            val edge = lerpColor(0xFFFF9440.toInt(), 0xFFFFD27A.toInt(), k2)
            skyPaint.shader = RadialGradient(sunX, sy, r * 3.4f,
                intArrayOf(withAlpha(edge, level * 0.55f), withAlpha(edge, level * 0.16f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(sunX, sy, r * 3.4f, skyPaint)
            skyPaint.shader = RadialGradient(sunX, sy, r,
                intArrayOf(withAlpha(core, level), withAlpha(edge, level)),
                floatArrayOf(0.2f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(sunX, sy, r, skyPaint)
        }
        skyPaint.shader = null

        // زمین سیاه روی پایین خورشید، پیش از خط روشن افق
        c.drawPath(ground, groundPaint)
        // خط نازک روشن روی افق
        strokePaint.color = cH
        strokePaint.alpha = (level * 90).toInt()
        strokePaint.strokeWidth = max(1f, h * 0.0015f)
        c.drawLine(w * 0.38f, hy, w * 0.64f, hy, strokePaint)
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

    /**
     * نشانهٔ کوچک خورشید بالای صفحه، مثل ماه: جای افقی از سمت (شرق چپ، غرب راست)،
     * ارتفاع از بالا بودن خورشید در آسمان. فقط وقتی خورشید بالای افق است.
     */
    private fun drawSunMarker(c: Canvas, now: Long, w: Float, h: Float, dx: Float) {
        val minute = now / 60_000L
        if (minute != sunMinute) {
            sunMinute = minute
            val loc = prefs.location()
            sunInfo = try { SunCalc.sunPosition(now, loc.lat, loc.lon) } catch (_: Exception) { null }
        }
        val s = sunInfo ?: return
        if (s.alt <= -0.5) return
        val r = min(w, h) * 0.030f
        val azN = (s.az / 110.0).coerceIn(-1.0, 1.0).toFloat()          // ‎-۱ شرق … ۰ جنوب … ۱ غرب
        val altN = (s.alt / 75.0).coerceIn(0.0, 1.0).toFloat()
        val sx = w * (0.5f + 0.40f * azN) + dx * 0.5f
        val sy = if (h >= w) h * (0.090f - 0.055f * altN) else h * (0.11f - 0.065f * altN)
        val core = Color.rgb(0xFF, 0xE7, 0x9A)
        val edge = Color.rgb(0xFF, 0xB0, 0x40)
        // هاله
        skyPaint.shader = RadialGradient(sx, sy, r * 2.8f,
            intArrayOf(withAlpha(edge, 0.35f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, r * 2.8f, skyPaint)
        skyPaint.shader = null
        // پرتوها
        strokePaint.color = edge
        strokePaint.alpha = 200
        strokePaint.strokeWidth = max(1.5f, r * 0.16f)
        for (i in 0 until 8) {
            val a = i * PI / 4
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(sx + ca * r * 1.35f, sy + sa * r * 1.35f, sx + ca * r * 1.85f, sy + sa * r * 1.85f, strokePaint)
        }
        // قرص
        fillPaint.color = core
        fillPaint.alpha = 245
        c.drawCircle(sx, sy, r, fillPaint)
        fillPaint.alpha = 255
    }

    /** هلال ماه کوچک بالای ساعت؛ جای افقی از سمت (شرق چپ، غرب راست)، ارتفاع از بالا بودن ماه در آسمان. */
    private fun drawMoon(c: Canvas, now: Long, w: Float, h: Float, dx: Float) {
        val minute = now / 60_000L
        if (minute != moonMinute) {
            moonMinute = minute
            val loc = prefs.location()
            moonInfo = try { SunCalc.moon(now, loc.lat, loc.lon) } catch (_: Exception) { null }
        }
        val m = moonInfo ?: return
        if (m.alt <= 0.0 || m.fraction < 0.015) return
        val r = min(w, h) * 0.028f
        val azN = (m.az / 90.0).coerceIn(-1.0, 1.0).toFloat()          // ‎-۱ شرق … ۰ جنوب … ۱ غرب
        val altN = (m.alt / 70.0).coerceIn(0.0, 1.0).toFloat()
        val mx = w * (0.5f + 0.38f * azN) + dx * 0.5f
        val my = if (h >= w) h * (0.085f - 0.05f * altN) else h * (0.10f - 0.06f * altN)
        val moonColor = Color.rgb(0xF4, 0xEC, 0xD2)
        // هاله
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
        /** پایان کامل (پس از یک دقیقه محو شدن). */
        const val SKY_END_MIN = 21f
        const val SUN_EMERGE_MIN = 3f
        /** خورشید از ۳ تا ۲۰ دقیقه چند برابر شعاعش بالا برود. */
        const val SUN_RISE_RADII = 2.8f
        const val STYLE_ANALOG_DIGITAL = 6
        /** رقم‌های لاتین و فارسی؛ برای رسم هم‌پهنا. */
        const val DIGITS = "0123456789۰۱۲۳۴۵۶۷۸۹"
        val DAWN_DIM = Color.rgb(0x2A, 0x33, 0x46)
        val DAWN_WHITE = Color.rgb(0xEE, 0xEC, 0xE6)
        val DAWN_GOLD = Color.rgb(0xFF, 0xE2, 0xB4)
        val MORNING_BLUE = Color.rgb(0x5E, 0x75, 0x98)
        const val DEMO_SPEED = 30L
        const val NIGHT_BEFORE_FAJR_MS = 20 * 60_000L
        val HOLIDAY_RED = Color.rgb(0xFF, 0x4D, 0x4D)
        val GUIDE_GOLD = Color.rgb(0xFF, 0xD5, 0x80)
    }
}
