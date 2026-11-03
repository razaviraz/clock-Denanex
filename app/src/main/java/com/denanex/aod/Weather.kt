package com.denanex.aod

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * وضعیت آب‌وهوای شهر انتخابی از Open-Meteo (رایگان، بدون کلید).
 * هر ۳۰ دقیقه یک بار در پس‌زمینه گرفته و در SharedPreferences نگه داشته می‌شود؛
 * اگر اینترنت نباشد، آخرین وضعیت (تا ۳ ساعت) نمایش داده می‌شود.
 */
object Weather {
    /** [code] کد وضعیت WMO، [temp] دما (سانتی‌گراد)، [day] روز است یا شب. */
    class Now(val code: Int, val temp: Double, val day: Boolean, val at: Long)

    private const val REFRESH_MS = 30 * 60_000L
    private const val RETRY_MS = 5 * 60_000L
    private const val MAX_AGE_MS = 3 * 3_600_000L

    @Volatile private var busy = false
    @Volatile private var lastTry = 0L

    private fun key(lat: Double, lon: Double) = String.format(Locale.US, "%.2f,%.2f", lat, lon)

    /** آخرین وضعیت ذخیره‌شده برای این مکان (یا null). اگر کهنه بود، گرفتن تازه را شروع می‌کند. */
    fun get(ctx: Context, lat: Double, lon: Double): Now? {
        val sp = ctx.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val k = key(lat, lon)
        val sameLoc = sp.getString("key", "") == k
        val at = if (sameLoc) sp.getLong("at", 0L) else 0L
        if (now - at > REFRESH_MS) refresh(ctx.applicationContext, lat, lon)
        if (!sameLoc || at == 0L || now - at > MAX_AGE_MS) return null
        return Now(sp.getInt("code", 0), sp.getFloat("temp", 0f).toDouble(), sp.getBoolean("day", true), at)
    }

    private fun refresh(app: Context, lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        if (busy || now - lastTry < RETRY_MS) return
        busy = true
        lastTry = now
        Thread {
            try {
                val url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day",
                    lat, lon)
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (c.responseCode == 200) {
                    val t = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val cur = JSONObject(t).getJSONObject("current")
                    app.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
                        .putString("key", key(lat, lon))
                        .putLong("at", System.currentTimeMillis())
                        .putInt("code", cur.optInt("weather_code", 0))
                        .putFloat("temp", cur.optDouble("temperature_2m", 0.0).toFloat())
                        .putBoolean("day", cur.optInt("is_day", 1) == 1)
                        .apply()
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
}
