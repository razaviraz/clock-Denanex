package com.denanex.aod

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * وضعیت آب‌وهوای شهر انتخابی و پیش‌بینی ۷ روزه از Open-Meteo (رایگان، بدون کلید).
 * هر ۳۰ دقیقه یک بار در پس‌زمینه گرفته و در SharedPreferences نگه داشته می‌شود؛
 * اگر اینترنت نباشد، آخرین وضعیت (تا ۳ ساعت) و آخرین پیش‌بینی نمایش داده می‌شود.
 */
object Weather {
    /** [code] کد وضعیت WMO، [temp] دما (سانتی‌گراد)، [day] روز است یا شب. */
    class Now(val code: Int, val temp: Double, val day: Boolean, val at: Long)

    /** یک روز پیش‌بینی: تاریخ میلادی ([y]،[m]،[d])، کد وضعیت، بیشینه و کمینهٔ دما. */
    class Day(val y: Int, val m: Int, val d: Int, val code: Int, val max: Double, val min: Double)

    private const val REFRESH_MS = 30 * 60_000L
    private const val RETRY_MS = 5 * 60_000L
    private const val MAX_AGE_MS = 3 * 3_600_000L

    @Volatile private var busy = false
    @Volatile private var lastTry = 0L

    private fun key(lat: Double, lon: Double) = String.format(Locale.US, "%.2f,%.2f", lat, lon)
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)

    /** آخرین وضعیت ذخیره‌شده برای این مکان (یا null). اگر کهنه بود، گرفتن تازه را شروع می‌کند. */
    fun get(ctx: Context, lat: Double, lon: Double): Now? {
        val sp = sp(ctx)
        val now = System.currentTimeMillis()
        val k = key(lat, lon)
        val sameLoc = sp.getString("key", "") == k
        val at = if (sameLoc) sp.getLong("at", 0L) else 0L
        if (now - at > REFRESH_MS || (sameLoc && !sp.contains("daily"))) refresh(ctx.applicationContext, lat, lon)
        if (!sameLoc || at == 0L || now - at > MAX_AGE_MS) return null
        return Now(sp.getInt("code", 0), sp.getFloat("temp", 0f).toDouble(), sp.getBoolean("day", true), at)
    }

    /** پیش‌بینی ۷ روزهٔ ذخیره‌شده برای این مکان (از امروز)، یا فهرست خالی. */
    fun forecast(ctx: Context, lat: Double, lon: Double): List<Day> {
        val sp = sp(ctx)
        if (sp.getString("key", "") != key(lat, lon)) return emptyList()
        val raw = sp.getString("daily", null) ?: return emptyList()
        return try {
            val o = JSONObject(raw)
            val t = o.getJSONArray("time"); val c = o.getJSONArray("weather_code")
            val hi = o.getJSONArray("temperature_2m_max"); val lo = o.getJSONArray("temperature_2m_min")
            val today = Calendar.getInstance()
            val todayKey = today.get(Calendar.YEAR) * 10000 + (today.get(Calendar.MONTH) + 1) * 100 + today.get(Calendar.DAY_OF_MONTH)
            (0 until t.length()).mapNotNull { i ->
                val p = t.getString(i).split("-")
                if (p.size != 3) return@mapNotNull null
                val y = p[0].toInt(); val m = p[1].toInt(); val d = p[2].toInt()
                if (y * 10000 + m * 100 + d < todayKey) null
                else Day(y, m, d, c.optInt(i, 0), hi.optDouble(i, 0.0), lo.optDouble(i, 0.0))
            }.take(7)
        } catch (_: Exception) { emptyList() }
    }

    private fun refresh(app: Context, lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        if (busy || now - lastTry < RETRY_MS) return
        busy = true
        lastTry = now
        Thread {
            try {
                val url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                        "&current=temperature_2m,weather_code,is_day" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=8&timezone=auto",
                    lat, lon)
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (c.responseCode == 200) {
                    val t = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val o = JSONObject(t)
                    val cur = o.getJSONObject("current")
                    val e = sp(app).edit()
                        .putString("key", key(lat, lon))
                        .putLong("at", System.currentTimeMillis())
                        .putInt("code", cur.optInt("weather_code", 0))
                        .putFloat("temp", cur.optDouble("temperature_2m", 0.0).toFloat())
                        .putBoolean("day", cur.optInt("is_day", 1) == 1)
                    o.optJSONObject("daily")?.let { e.putString("daily", it.toString()) }
                    e.apply()
                }
                c.disconnect()
            } catch (_: Exception) {
            } finally {
                busy = false
            }
        }.start()
    }

    // گروه‌بندی کدهای WMO برای آیکون
    const val CLEAR = 0
    const val PARTLY = 1
    const val CLOUDY = 2
    const val FOG = 3
    const val RAIN = 4
    const val SNOW = 5
    const val STORM = 6

    fun kind(code: Int): Int = when (code) {
        0 -> CLEAR
        1, 2 -> PARTLY
        3 -> CLOUDY
        45, 48 -> FOG
        in 51..67, in 80..82 -> RAIN
        in 71..77, 85, 86 -> SNOW
        in 95..99 -> STORM
        else -> CLOUDY
    }

    fun desc(kind: Int) = when (kind) {
        CLEAR -> "صاف"; PARTLY -> "نیمه‌ابری"; CLOUDY -> "ابری"; FOG -> "مه"
        RAIN -> "بارانی"; SNOW -> "برفی"; else -> "رعد و برق"
    }

    fun emoji(kind: Int) = when (kind) {
        CLEAR -> "☀️"; PARTLY -> "⛅"; CLOUDY -> "☁️"; FOG -> "🌫"
        RAIN -> "🌧"; SNOW -> "❄️"; else -> "⛈"
    }

    /** پنجرهٔ آب‌وهوای شهر انتخابی: وضعیت الان و پیش‌بینی ۷ روزه. */
    fun showDialog(act: Activity) {
        val p = Prefs(act)
        val loc = p.location()
        val now = get(act, loc.lat, loc.lon)
        val days = forecast(act, loc.lat, loc.lon)
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
        fun fa(n: Double) = Persian.digits(n.roundToInt().toString())
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(16), dp(18), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x10, 0x16, 0x26)); cornerRadius = dp(14).toFloat()
            }
        }
        box.addView(TextView(act).apply {
            text = "آب‌وهوای ${loc.name}" + (if (loc.province.isNotEmpty()) " (استان ${loc.province})" else "")
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(0xFF, 0xE2, 0xA0))
            gravity = Gravity.CENTER
        })
        box.addView(TextView(act).apply {
            text = if (now == null) "اطلاعات هنوز دریافت نشده (اینترنت لازم است)"
                   else "اکنون: " + emoji(kind(now.code)) + "  " + fa(now.temp) + "°  " + desc(kind(now.code))
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(10))
        })
        val today = Calendar.getInstance()
        for ((i, d) in days.withIndex()) {
            val c = Calendar.getInstance().apply { set(d.y, d.m - 1, d.d) }
            val j = Persian.toJalali(d.y, d.m, d.d)
            val name = when {
                i == 0 && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "امروز"
                else -> Persian.weekday(c.get(Calendar.DAY_OF_WEEK))
            }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(7), dp(6), dp(7))
                if (i % 2 == 0) setBackgroundColor(Color.rgb(0x18, 0x20, 0x34))
            }
            fun cell(t: String, wgt: Float, col: Int = Color.rgb(0xEE, 0xEE, 0xF2), g: Int = Gravity.START) =
                row.addView(TextView(act).apply { text = t; textSize = 15f; setTextColor(col); gravity = g },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, wgt))
            cell(name + "  " + Persian.digits(j[2].toString()) + " " + Persian.months[j[1] - 1], 1.6f)
            cell(emoji(kind(d.code)) + " " + desc(kind(d.code)), 1.3f)
            cell(fa(d.max) + "°", 0.5f, Color.rgb(0xFF, 0xB0, 0x70), Gravity.CENTER)
            cell(fa(d.min) + "°", 0.5f, Color.rgb(0x8C, 0xC8, 0xFF), Gravity.CENTER)
            box.addView(row)
        }
        if (days.isEmpty()) box.addView(TextView(act).apply {
            text = "پیش‌بینی ۷ روزه هنوز دریافت نشده؛ چند دقیقه بعد با اینترنت دوباره امتحان کنید."
            textSize = 14f
            setTextColor(Color.rgb(0xA0, 0xA0, 0xA8))
            gravity = Gravity.CENTER
        })
        box.addView(TextView(act).apply {
            text = "منبع: Open-Meteo"
            textSize = 11f
            setTextColor(Color.rgb(0x80, 0x80, 0x88))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        val dlg = AlertDialog.Builder(act)
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("بستن", null)
            .create()
        dlg.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.rgb(0x10, 0x16, 0x26)); cornerRadius = dp(14).toFloat()
        })
        dlg.show()
    }
}
