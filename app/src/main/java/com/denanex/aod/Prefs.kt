package com.denanex.aod

import android.content.Context
import android.graphics.Color
import android.view.Window

/** همهٔ تنظیمات اپ (در SharedPreferences). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("aod", Context.MODE_PRIVATE)

    companion object {
        /** شمارهٔ شهر برای «موقعیت من» (مختصات ذخیره‌شده از GPS/شبکه). */
        const val CITY_MY_LOCATION = -1
        const val CREDIT = "تهیه‌کننده: محمد رضوی‌نژاد — التماس دعا 🤲"

        val styleNames = arrayOf(
            "دیجیتال باریک",
            "دیجیتال پررنگ",
            "دیجیتال دوطبقه (خیلی درشت)",
            "عقربه‌ای کلاسیک",
            "عقربه‌ای ساده (نقطه‌ای)",
            "عقربه‌ای با اعداد فارسی",
            "عقربه‌ای + دیجیتال درشت",
        )
        const val STYLE_COUNT = 7

        val colorNames = arrayOf("سفید", "کرم", "کهربایی", "آبی آسمانی", "سبز ملایم", "صورتی", "قرمز (شب)", "سبز فسفری")
        val colorValues = intArrayOf(
            Color.WHITE,
            Color.rgb(0xFF, 0xE9, 0xC4),
            Color.rgb(0xFF, 0xB7, 0x4D),
            Color.rgb(0x81, 0xD4, 0xFA),
            Color.rgb(0xA5, 0xD6, 0xA7),
            Color.rgb(0xF4, 0x8F, 0xB1),
            Color.rgb(0xFF, 0x6E, 0x6E),
            Color.rgb(0x39, 0xFF, 0x14),
        )
    }

    var city: Int
        get() = sp.getInt("city", 0)
        set(v) = sp.edit().putInt("city", v).apply()
    var myLat: Double
        get() = sp.getFloat("my_lat", 35.6892f).toDouble()
        set(v) = sp.edit().putFloat("my_lat", v.toFloat()).apply()
    var myLon: Double
        get() = sp.getFloat("my_lon", 51.3890f).toDouble()
        set(v) = sp.edit().putFloat("my_lon", v.toFloat()).apply()

    var style: Int
        get() = sp.getInt("style", 2).coerceIn(0, STYLE_COUNT - 1)
        set(v) = sp.edit().putInt("style", v).apply()
    var color: Int
        get() = sp.getInt("color", 0).coerceIn(0, colorValues.size - 1)
        set(v) = sp.edit().putInt("color", v).apply()

    var h24: Boolean
        get() = sp.getBoolean("h24", true)
        set(v) = sp.edit().putBoolean("h24", v).apply()
    var seconds: Boolean
        get() = sp.getBoolean("seconds", false)
        set(v) = sp.edit().putBoolean("seconds", v).apply()
    var persianDigits: Boolean
        get() = sp.getBoolean("fa_digits", true)
        set(v) = sp.edit().putBoolean("fa_digits", v).apply()
    var showDate: Boolean
        get() = sp.getBoolean("show_date", true)
        set(v) = sp.edit().putBoolean("show_date", v).apply()
    var showBattery: Boolean
        get() = sp.getBoolean("show_battery", true)
        set(v) = sp.edit().putBoolean("show_battery", v).apply()
    var showSunrise: Boolean
        get() = sp.getBoolean("show_sunrise", true)
        set(v) = sp.edit().putBoolean("show_sunrise", v).apply()

    /** چند دقیقه پیش از طلوع، سفیدی ملایم صبح شروع شود. */
    var dawnMinutes: Int
        get() = sp.getInt("dawn_min", 10).coerceIn(3, 40)
        set(v) = sp.edit().putInt("dawn_min", v).apply()
    /** نمایش سفیدی صبح و طلوع خورشید روشن باشد یا نه. */
    var sunrise: Boolean
        get() = sp.getBoolean("sunrise_fx", true)
        set(v) = sp.edit().putBoolean("sunrise_fx", v).apply()

    /** بیپ هر ۵ دقیقه از ۲۰ دقیقه پیش از اذان صبح تا خود اذان. */
    var fajrBeep: Boolean
        get() = sp.getBoolean("fajr_beep", true)
        set(v) = sp.edit().putBoolean("fajr_beep", v).apply()
    /** صدای خروس پیش از طلوع آفتاب (انتخابی). */
    var rooster: Boolean
        get() = sp.getBoolean("rooster", false)
        set(v) = sp.edit().putBoolean("rooster", v).apply()
    /** صدای خروس چند دقیقه پیش از طلوع آفتاب پخش شود (۰ = سر طلوع). */
    var roosterMinutes: Int
        get() = sp.getInt("rooster_min", 10).coerceIn(0, 60)
        set(v) = sp.edit().putInt("rooster_min", v).apply()

    /** اگر صفحهٔ تنظیمات ۲ دقیقه لمس نشد، ساعت خودکار باز شود. */
    var autoClock: Boolean
        get() = sp.getBoolean("auto_clock", true)
        set(v) = sp.edit().putBoolean("auto_clock", v).apply()

    /** از ۲۰ دقیقه پیش از اذان صبح تا طلوع، پس‌زمینه به رنگ آسمان نیمه‌شب (سرمه‌ای با ستاره). */
    var nightSky: Boolean
        get() = sp.getBoolean("night_sky", true)
        set(v) = sp.edit().putBoolean("night_sky", v).apply()
    /** هلال ماه بالای ساعت، هر وقت ماه بالای افق است. */
    var moon: Boolean
        get() = sp.getBoolean("moon", true)
        set(v) = sp.edit().putBoolean("moon", v).apply()
    /** تصحیح تاریخ قمری (روز) برای هماهنگی با تقویم رسمی ایران. */
    var hijriOffset: Int
        get() = sp.getInt("hijri_off", 0).coerceIn(-2, 2)
        set(v) = sp.edit().putInt("hijri_off", v).apply()

    /** نور صفحه هنگام نمایش ساعت (۱ تا ۱۰۰ درصد). */
    var brightness: Int
        get() = sp.getInt("brightness", 15).coerceIn(1, 100)
        set(v) = sp.edit().putInt("brightness", v).apply()
    /** هنگام شفق و طلوع کم‌کم نور صفحه بیشتر شود. */
    var brightenAtDawn: Boolean
        get() = sp.getBoolean("brighten", true)
        set(v) = sp.edit().putBoolean("brighten", v).apply()
    /** جابه‌جایی آرام ساعت هر دقیقه (جلوگیری از سوختن صفحهٔ AMOLED). */
    var burnIn: Boolean
        get() = sp.getBoolean("burn_in", true)
        set(v) = sp.edit().putBoolean("burn_in", v).apply()

    fun location(): City =
        if (city == CITY_MY_LOCATION) City("موقعیت من", myLat, myLon)
        else Cities.list.getOrElse(city) { Cities.list[0] }

    fun mainColor(): Int = colorValues[color]

    /** نور پنجره: پایه + (اگر روشن باشد) افزایش تدریجی با شدت شفق [sky] (۰ تا ۱). */
    fun applyBrightness(window: Window?, sky: Float) {
        window ?: return
        val base = brightness / 100f
        val target = if (brightenAtDawn) maxOf(base, 0.55f) else base
        val b = (base + (target - base) * sky.coerceIn(0f, 1f)).coerceIn(0.01f, 1f)
        val lp = window.attributes
        if (kotlin.math.abs(lp.screenBrightness - b) < 0.01f) return
        lp.screenBrightness = b
        window.attributes = lp
    }
}
