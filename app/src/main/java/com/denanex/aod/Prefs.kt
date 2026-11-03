package com.denanex.aod

import android.content.Context
import android.graphics.Color
import android.view.Window

/** همهٔ تنظیمات اپ (در SharedPreferences). */
class Prefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("aod", Context.MODE_PRIVATE)

    companion object {
        /** شمارهٔ شهر برای «موقعیت من» در نسخه‌های قبل (فقط برای انتقال تنظیم قدیمی). */
        private const val LEGACY_MY_LOCATION = -1
        const val CREDIT = "تهیه‌کننده: محمد رضوی‌نژاد — التماس دعا 🤲"
        const val DEDICATION = "تقدیم به روح پاک سردار شهید سید جلال الدین توسلمند"

        val styleNames = arrayOf(
            "دیجیتال باریک",
            "دیجیتال پررنگ",
            "دیجیتال دوطبقه (خیلی درشت)",
            "عقربه‌ای کلاسیک",
            "عقربه‌ای ساده (نقطه‌ای)",
            "عقربه‌ای با اعداد فارسی",
            "عقربه‌ای + دیجیتال درشت (اعداد انگلیسی)",
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

    // ---------------- مکان (استان و شهر) ----------------

    /** شهر انتخابی (استان، نام و مختصات) را ذخیره می‌کند. */
    fun setCity(c: City) {
        sp.edit().putString("loc_prov", c.province).putString("loc_city", c.name)
            .putString("loc_lat", c.lat.toString()).putString("loc_lon", c.lon.toString()).apply()
    }

    /** شهر انتخابی؛ اگر از نسخهٔ قبل تنظیم قدیمی مانده، یک بار به استان و شهر تبدیل می‌شود. */
    fun location(): City {
        val name = sp.getString("loc_city", null)
        if (name != null) {
            val lat = sp.getString("loc_lat", null)?.toDoubleOrNull()
            val lon = sp.getString("loc_lon", null)?.toDoubleOrNull()
            if (lat != null && lon != null) return City(name, lat, lon, sp.getString("loc_prov", "") ?: "")
        }
        val c = migrateLegacy()
        setCity(c)
        return c
    }

    private fun migrateLegacy(): City {
        if (!sp.contains("city")) return Cities.find(app, "تهران", "تهران") ?: Cities.TEHRAN
        val idx = sp.getInt("city", 0)
        val old = if (idx == LEGACY_MY_LOCATION)
            City("", sp.getFloat("my_lat", 35.6892f).toDouble(), sp.getFloat("my_lon", 51.3890f).toDouble())
        else Cities.legacy.getOrElse(idx) { Cities.legacy[0] }
        val plain = old.name.replace("\u200c", " ").replace(" ", "")
        if (plain.isNotEmpty()) {
            Cities.all(app).firstOrNull { it.name.replace(" ", "") == plain && Cities.distKm(it.lat, it.lon, old.lat, old.lon) < 40 }
                ?.let { return it }
        }
        return Cities.nearest(app, old.lat, old.lon)
    }

    var style: Int
        get() = sp.getInt("style", 6).coerceIn(0, STYLE_COUNT - 1)   // پیش‌فرض: عقربه‌ای + دیجیتال درشت
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
    /** تاریخ خورشیدی (شمسی). */
    var showDate: Boolean
        get() = sp.getBoolean("show_date", true)
        set(v) = sp.edit().putBoolean("show_date", v).apply()
    /** تاریخ قمری. */
    var showHijri: Boolean
        get() = sp.getBoolean("show_hijri", false)
        set(v) = sp.edit().putBoolean("show_hijri", v).apply()
    /** تاریخ میلادی. */
    var showGregorian: Boolean
        get() = sp.getBoolean("show_greg", false)
        set(v) = sp.edit().putBoolean("show_greg", v).apply()
    /** آیکون وضعیت آب‌وهوا (از اینترنت، برای شهر انتخابی). */
    var weather: Boolean
        get() = sp.getBoolean("weather", true)
        set(v) = sp.edit().putBoolean("weather", v).apply()
    /** قبله‌نما و قطب‌نما وسط ساعت عقربه‌ای (با حسگر گوشی). */
    var qibla: Boolean
        get() = sp.getBoolean("qibla", true)
        set(v) = sp.edit().putBoolean("qibla", v).apply()
    /** کمان کم‌رنگ مسیر خورشید بالای صفحه، با طلوع و غروب خورشید و ماه. */
    var skyArc: Boolean
        get() = sp.getBoolean("sky_arc", true)
        set(v) = sp.edit().putBoolean("sky_arc", v).apply()
    // ---------------- نوار پایین صفحهٔ ساعت (راهنماها) ----------------
    var barNight: Boolean
        get() = sp.getBoolean("bar_night", false)
        set(v) = sp.edit().putBoolean("bar_night", v).apply()
    var barAyat: Boolean
        get() = sp.getBoolean("bar_ayat", false)
        set(v) = sp.edit().putBoolean("bar_ayat", v).apply()
    var barParents: Boolean
        get() = sp.getBoolean("bar_parents", false)
        set(v) = sp.edit().putBoolean("bar_parents", v).apply()

    // ---------------- GPS خودکار ----------------
    /** هر ۱۰ دقیقه یک بار مکان گرفته و نزدیک‌ترین شهر انتخاب شود (بعد GPS خاموش). */
    var gpsAuto: Boolean
        get() = sp.getBoolean("gps_auto", false)
        set(v) = sp.edit().putBoolean("gps_auto", v).apply()
    var gpsLastTry: Long
        get() = sp.getLong("gps_last", 0L)
        set(v) = sp.edit().putLong("gps_last", v).apply()

    /** هنگام اذان، «اذان به افق …» با صدا گفته شود (اگر موتور گفتار فارسی روی گوشی باشد). */
    var announceCity: Boolean
        get() = sp.getBoolean("announce_city", true)
        set(v) = sp.edit().putBoolean("announce_city", v).apply()

    // ---------------- آلبوم عکس ----------------
    var album: Boolean
        get() = sp.getBoolean("album", false)
        set(v) = sp.edit().putBoolean("album", v).apply()
    /** ۰ دایره، ۱ مربع. */
    var albumShape: Int
        get() = sp.getInt("album_shape", 0).coerceIn(0, 1)
        set(v) = sp.edit().putInt("album_shape", v).apply()
    var albumMusic: Boolean
        get() = sp.getBoolean("album_music", false)
        set(v) = sp.edit().putBoolean("album_music", v).apply()
    var albumMusicUri: String
        get() = sp.getString("album_music_uri", "") ?: ""
        set(v) = sp.edit().putString("album_music_uri", v).apply()
    var albumMusicName: String
        get() = sp.getString("album_music_name", "") ?: ""
        set(v) = sp.edit().putString("album_music_name", v).apply()

    /** گوشی افقی: ساعت دیجیتال تمام‌صفحه و خیلی درشت (ساعت چپ، دقیقه راست). */
    var landscapeBig: Boolean
        get() = sp.getBoolean("land_big", true)
        set(v) = sp.edit().putBoolean("land_big", v).apply()
    /** سرخی غروب و فرو رفتن خورشید هنگام غروب. */
    var sunsetFx: Boolean
        get() = sp.getBoolean("sunset_fx", true)
        set(v) = sp.edit().putBoolean("sunset_fx", v).apply()
    var showBattery: Boolean
        get() = sp.getBoolean("show_battery", true)
        set(v) = sp.edit().putBoolean("show_battery", v).apply()
    var showSunrise: Boolean
        get() = sp.getBoolean("show_sunrise", false)
        set(v) = sp.edit().putBoolean("show_sunrise", v).apply()
    /** نشانهٔ کوچک خورشید بالای صفحه، در جای واقعی‌اش در آسمان (هر وقت خورشید بالای افق است). */
    var sunPos: Boolean
        get() = sp.getBoolean("sun_pos", true)
        set(v) = sp.edit().putBoolean("sun_pos", v).apply()

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
    /** فایل صوتی انتخابی کاربر به‌جای صدای خروس (content://…)؛ خالی = صدای خروس. */
    var roosterUri: String
        get() = sp.getString("rooster_uri", "") ?: ""
        set(v) = sp.edit().putString("rooster_uri", v).apply()
    var roosterUriName: String
        get() = sp.getString("rooster_uri_name", "") ?: ""
        set(v) = sp.edit().putString("rooster_uri_name", v).apply()
    /** هنگام پخش صدای پیش از طلوع، گوشی بلرزد. */
    var roosterVibrate: Boolean
        get() = sp.getBoolean("rooster_vib", false)
        set(v) = sp.edit().putBoolean("rooster_vib", v).apply()

    /** پخش اذان: ۰ خاموش، ۱ شیعه (۳ وقت)، ۲ اهل سنت (۵ وقت). */
    var adhanMode: Int
        get() = sp.getInt("adhan_mode", Prayers.MODE_OFF).coerceIn(0, 2)
        set(v) = sp.edit().putInt("adhan_mode", v).apply()
    /** عصر به مذهب حنفی (سایه دو برابر). */
    var asrHanafi: Boolean
        get() = sp.getBoolean("asr_hanafi", false)
        set(v) = sp.edit().putBoolean("asr_hanafi", v).apply()
    /** فایل اذان انتخابی کاربر (content://…)؛ خالی = اذان همراه برنامه. */
    var adhanUri: String
        get() = sp.getString("adhan_uri", "") ?: ""
        set(v) = sp.edit().putString("adhan_uri", v).apply()
    var adhanUriName: String
        get() = sp.getString("adhan_uri_name", "") ?: ""
        set(v) = sp.edit().putString("adhan_uri_name", v).apply()
    var adhanVibrate: Boolean
        get() = sp.getBoolean("adhan_vib", false)
        set(v) = sp.edit().putBoolean("adhan_vib", v).apply()

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
