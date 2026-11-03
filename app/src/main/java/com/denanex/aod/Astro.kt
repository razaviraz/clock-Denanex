package com.denanex.aod

import android.content.Context
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.tan
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** یک شهر با مختصات جغرافیایی (برای محاسبهٔ اوقات به افق همان شهر). */
data class City(val name: String, val lat: Double, val lon: Double, val province: String = "")

object Cities {
    /**
     * همهٔ شهرهای کشور، به ترتیب استان (از assets/cities.tsv؛ در هر استان اول مرکز استان).
     * یک بار خوانده می‌شود.
     */
    @Volatile private var all: List<City>? = null

    fun all(ctx: Context): List<City> {
        all?.let { return it }
        synchronized(this) {
            all?.let { return it }
            val out = ArrayList<City>(1200)
            try {
                ctx.applicationContext.assets.open("cities.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (ln in lines) {
                        if (ln.isBlank() || ln.startsWith("#")) continue
                        val f = ln.split('\t')
                        if (f.size < 4) continue
                        val lat = f[2].toDoubleOrNull() ?: continue
                        val lon = f[3].toDoubleOrNull() ?: continue
                        out.add(City(f[1], lat, lon, f[0]))
                    }
                }
            } catch (_: Exception) {}
            if (out.isEmpty()) out.add(TEHRAN)
            all = out
            return out
        }
    }

    fun provinces(ctx: Context): List<String> = all(ctx).map { it.province }.distinct()
    fun of(ctx: Context, province: String): List<City> = all(ctx).filter { it.province == province }

    fun find(ctx: Context, province: String, name: String): City? =
        all(ctx).firstOrNull { it.province == province && it.name == name }

    /** نزدیک‌ترین شهر به یک نقطه (برای GPS). */
    fun nearest(ctx: Context, lat: Double, lon: Double): City =
        all(ctx).minByOrNull { distKm(lat, lon, it.lat, it.lon) } ?: TEHRAN

    fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = PI / 180.0
        val dLat = (lat2 - lat1) * r
        val dLon = (lon2 - lon1) * r
        val a = sin(dLat / 2).let { it * it } + cos(lat1 * r) * cos(lat2 * r) * sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    val TEHRAN = City("تهران", 35.6893, 51.3896, "تهران")

    /** فهرست شهرهای نسخه‌های قبلی (تنظیم ذخیره‌شده شمارهٔ ردیف همین فهرست بود)؛ فقط برای انتقال تنظیم قدیمی. */
    val legacy = listOf(
        City("تهران", 35.6892, 51.3890), City("مشهد", 36.2605, 59.6168), City("اصفهان", 32.6546, 51.6680),
        City("شیراز", 29.5918, 52.5837), City("تبریز", 38.0800, 46.2919), City("کرج", 35.8400, 50.9391),
        City("قم", 34.6399, 50.8759), City("اهواز", 31.3183, 48.6706), City("کرمانشاه", 34.3142, 47.0650),
        City("ارومیه", 37.5527, 45.0760), City("رشت", 37.2808, 49.5832), City("زاهدان", 29.4963, 60.8629),
        City("همدان", 34.7983, 48.5148), City("کرمان", 30.2839, 57.0834), City("یزد", 31.8974, 54.3569),
        City("اردبیل", 38.2498, 48.2933), City("بندرعباس", 27.1832, 56.2666), City("اراک", 34.0954, 49.7013),
        City("زنجان", 36.6736, 48.4787), City("سنندج", 35.3140, 46.9988), City("قزوین", 36.2797, 50.0049),
        City("خرم‌آباد", 33.4878, 48.3558), City("گرگان", 36.8456, 54.4393), City("ساری", 36.5633, 53.0601),
        City("بجنورد", 37.4747, 57.3290), City("بوشهر", 28.9234, 50.8203), City("بیرجند", 32.8663, 59.2211),
        City("ایلام", 33.6374, 46.4227), City("شهرکرد", 32.3256, 50.8644), City("یاسوج", 30.6682, 51.5880),
        City("سمنان", 35.5729, 53.3971), City("کاشان", 33.9850, 51.4100), City("نیشابور", 36.2133, 58.7958),
        City("سبزوار", 36.2126, 57.6819), City("بابل", 36.5513, 52.6789), City("آمل", 36.4697, 52.3507),
        City("دزفول", 32.3830, 48.4000), City("آبادان", 30.3392, 48.3043), City("بروجرد", 33.8973, 48.7516),
        City("ملایر", 34.2969, 48.8235), City("ساوه", 35.0213, 50.3566), City("شاهرود", 36.4182, 54.9763),
        City("خوی", 38.5503, 44.9521), City("مراغه", 37.3917, 46.2397), City("سیرجان", 29.4520, 55.6810),
        City("بم", 29.1060, 58.3570), City("جیرفت", 28.6751, 57.7372), City("زابل", 31.0287, 61.5012),
        City("چابهار", 25.2919, 60.6430), City("کیش", 26.5333, 53.9833), City("لار", 27.6810, 54.3400),
        City("نجف‌آباد", 32.6324, 51.3650),
    )
}

/** جهت قبله (درجه از شمال جغرافیایی، ساعتگرد) از یک نقطه. */
object Qibla {
    private const val KAABA_LAT = 21.4225
    private const val KAABA_LON = 39.8262
    fun bearing(lat: Double, lon: Double): Double {
        val r = PI / 180.0
        val p1 = lat * r
        val p2 = KAABA_LAT * r
        val dl = (KAABA_LON - lon) * r
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) / r + 360.0) % 360.0
    }
}

/**
 * محاسبهٔ زمان طلوع آفتاب (الگوریتم نجومی استاندارد، دقت حدود یک دقیقه).
 * افق شرعی طلوع: لحظه‌ای که لبهٔ بالای قرص خورشید از افق بالا می‌آید = ارتفاع مرکز خورشید ‎-0.833°
 * (نیم‌قطر خورشید + شکست نور جو؛ همان معیار تقویم رسمی ایران).
 */
object SunCalc {
    private const val RAD = PI / 180.0
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val J0 = 0.0009
    private const val OBLIQUITY = RAD * 23.4397
    const val SUNRISE_ALT = -0.833
    /** اذان صبح (فجر صادق) با معیار تقویم رسمی ایران (مؤسسهٔ ژئوفیزیک دانشگاه تهران): خورشید ۱۷٫۷ درجه زیر افق. */
    const val FAJR_ALT = -17.7

    fun fajr(noonMs: Long, lat: Double, lon: Double): Long? = sunrise(noonMs, lat, lon, FAJR_ALT)

    private fun toDays(ms: Long) = ms / DAY_MS - 0.5 + J1970 - J2000
    private fun fromJulian(j: Double): Long = ((j + 0.5 - J1970) * DAY_MS).roundToLong()

    /** ظهر نجومی و میل خورشید برای روزی که [noonMs] در آن است. */
    private class Solar(val jNoon: Double, val dec: Double, val n: Double, val lw: Double, val phi: Double,
                        val m: Double, val l: Double)

    private fun solar(noonMs: Long, lat: Double, lon: Double): Solar {
        val lw = RAD * -lon
        val phi = RAD * lat
        val d = toDays(noonMs)
        val n = Math.round(d - J0 - lw / (2 * PI)).toDouble()
        val ds = J0 + lw / (2 * PI) + n
        val m = RAD * (357.5291 + 0.98560028 * ds)
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + RAD * 102.9372 + PI
        val dec = asin(sin(OBLIQUITY) * sin(l))
        val jNoon = J2000 + ds + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        return Solar(jNoon, dec, n, lw, phi, m, l)
    }

    /** لحظهٔ عصرگاهی که مرکز خورشید به ارتفاع [altDeg] می‌رسد (روز ژولینی)، یا null. */
    private fun jEvening(s: Solar, altDeg: Double): Double? {
        val cosW = (sin(altDeg * RAD) - sin(s.phi) * sin(s.dec)) / (cos(s.phi) * cos(s.dec))
        if (cosW < -1.0 || cosW > 1.0) return null
        val w = acos(cosW)
        val a = J0 + (w + s.lw) / (2 * PI) + s.n
        return J2000 + a + 0.0053 * sin(s.m) - 0.0069 * sin(2 * s.l)
    }

    /**
     * زمان طلوع (میلی‌ثانیهٔ جهانی) برای روزی که لحظهٔ [noonMs] (ظهر محلی آن روز) در آن است.
     * اگر خورشید آن روز طلوع نکند (عرض‌های قطبی) null.
     */
    fun sunrise(noonMs: Long, lat: Double, lon: Double, altDeg: Double = SUNRISE_ALT): Long? {
        val s = solar(noonMs, lat, lon)
        val jSet = jEvening(s, altDeg) ?: return null
        return fromJulian(s.jNoon - (jSet - s.jNoon))
    }

    /** غروب آفتاب (یا هر ارتفاع دیگر در عصر، مثل مغرب شرعی ‎-۴ درجه). */
    fun sunset(noonMs: Long, lat: Double, lon: Double, altDeg: Double = SUNRISE_ALT): Long? {
        val s = solar(noonMs, lat, lon)
        return jEvening(s, altDeg)?.let { fromJulian(it) }
    }

    /** ظهر شرعی (گذر خورشید از نصف‌النهار). */
    fun noon(noonMs: Long, lat: Double, lon: Double): Long = fromJulian(solar(noonMs, lat, lon).jNoon)

    /** عصر: سایهٔ هر چیز [factor] برابر خودش به‌اضافهٔ سایهٔ ظهر (۱ شافعی، ۲ حنفی). */
    fun asr(noonMs: Long, lat: Double, lon: Double, factor: Int = 1): Long? {
        val s = solar(noonMs, lat, lon)
        val alt = atan(1.0 / (factor + tan(abs(s.phi - s.dec)))) / RAD
        return jEvening(s, alt)?.let { fromJulian(it) }
    }

    // ---------------- جای خورشید ----------------

    /** جای خورشید در آسمان: [alt] ارتفاع از افق (درجه)، [az] سمت از جنوب به غرب (درجه؛ منفی = شرق). */
    class SunPos(val alt: Double, val az: Double)

    fun sunPosition(ms: Long, lat: Double, lon: Double): SunPos {
        val d = toDays(ms)
        val m = RAD * (357.5291 + 0.98560028 * d)
        val l = m + RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)) + RAD * 102.9372 + PI
        val sDec = decl(l, 0.0)
        val sRa = ra(l, 0.0)
        val lw = RAD * -lon
        val phi = RAD * lat
        val h = RAD * (280.16 + 360.9856235 * d) - lw - sRa
        val alt = asin(sin(phi) * sin(sDec) + cos(phi) * cos(sDec) * cos(h))
        val az = atan2(sin(h), cos(h) * sin(phi) - tan(sDec) * cos(phi))
        return SunPos(alt / RAD, az / RAD)
    }

    // ---------------- ماه ----------------

    /** [alt] ارتفاع از افق (درجه)، [az] سمت از جنوب به غرب (درجه؛ منفی = شرق)، [phase] ۰ ماه نو، ۰٫۵ بدر، [fraction] بخش روشن. */
    class Moon(val alt: Double, val az: Double, val phase: Double, val fraction: Double)

    private fun ra(l: Double, b: Double) = atan2(sin(l) * cos(OBLIQUITY) - tan(b) * sin(OBLIQUITY), cos(l))
    private fun decl(l: Double, b: Double) = asin(sin(b) * cos(OBLIQUITY) + cos(b) * sin(OBLIQUITY) * sin(l))

    fun moon(ms: Long, lat: Double, lon: Double): Moon {
        val d = toDays(ms)
        // مختصات ماه
        val lm = RAD * (218.316 + 13.176396 * d)
        val mm = RAD * (134.963 + 13.064993 * d)
        val fm = RAD * (93.272 + 13.229350 * d)
        val l = lm + RAD * 6.289 * sin(mm)
        val b = RAD * 5.128 * sin(fm)
        val dist = 385001.0 - 20905.0 * cos(mm)
        val mRa = ra(l, b)
        val mDec = decl(l, b)
        // ارتفاع و سمت
        val lw = RAD * -lon
        val phi = RAD * lat
        val h = RAD * (280.16 + 360.9856235 * d) - lw - mRa
        var alt = asin(sin(phi) * sin(mDec) + cos(phi) * cos(mDec) * cos(h))
        val az = atan2(sin(h), cos(h) * sin(phi) - tan(mDec) * cos(phi))
        val a0 = if (alt < 0) 0.0 else alt
        alt += 0.0002967 / tan(a0 + 0.00312536 / (a0 + 0.08901179))
        // روشنایی و منزل
        val sm = RAD * (357.5291 + 0.98560028 * d)
        val sl = sm + RAD * (1.9148 * sin(sm) + 0.02 * sin(2 * sm) + 0.0003 * sin(3 * sm)) + RAD * 102.9372 + PI
        val sDec = decl(sl, 0.0)
        val sRa = ra(sl, 0.0)
        val sDist = 149598000.0
        val psi = acos(sin(sDec) * sin(mDec) + cos(sDec) * cos(mDec) * cos(sRa - mRa))
        val inc = atan2(sDist * sin(psi), dist - sDist * cos(psi))
        val ang = atan2(cos(sDec) * sin(sRa - mRa), sin(sDec) * cos(mDec) - cos(sDec) * sin(mDec) * cos(sRa - mRa))
        val fraction = (1 + cos(inc)) / 2
        val phase = 0.5 + 0.5 * inc * (if (ang < 0) -1 else 1) / PI
        return Moon(alt / RAD, az / RAD, phase, fraction)
    }
}

/** تاریخ قمری (جدولی) و تعطیلات رسمی ایران. */
object Hijri {
    val months = arrayOf("محرم", "صفر", "ربیع‌الاول", "ربیع‌الثانی", "جمادی‌الاول", "جمادی‌الثانی",
        "رجب", "شعبان", "رمضان", "شوال", "ذی‌القعده", "ذی‌الحجه")

    private fun jdn(y: Int, m: Int, d: Int): Int {
        val a = (14 - m) / 12
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    }

    /** میلادی ← قمری {سال، ماه، روز}؛ [offset] روز برای هماهنگی با رؤیت هلال در ایران. */
    fun fromGregorian(gy: Int, gm: Int, gd: Int, offset: Int = 0): IntArray {
        var l = jdn(gy, gm, gd) + offset - 1948440 + 10632
        val n = (l - 1) / 10631
        l = l - 10631 * n + 354
        val j = ((10985 - l) / 5316) * ((50 * l) / 17719) + (l / 5670) * ((43 * l) / 15238)
        l = l - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29
        val m = (24 * l) / 709
        val d = l - (709 * m) / 24
        val y = 30 * n + j - 30
        return intArrayOf(y, m, d)
    }

    private val solar = mapOf(
        101 to "نوروز", 102 to "نوروز", 103 to "نوروز", 104 to "نوروز",
        112 to "روز جمهوری اسلامی", 113 to "روز طبیعت",
        314 to "رحلت امام خمینی", 315 to "قیام ۱۵ خرداد",
        1122 to "پیروزی انقلاب اسلامی", 1229 to "ملی شدن صنعت نفت",
    )
    private val lunar = mapOf(
        109 to "تاسوعا", 110 to "عاشورا", 220 to "اربعین", 228 to "رحلت پیامبر و شهادت امام حسن",
        308 to "شهادت امام حسن عسکری", 317 to "میلاد پیامبر و امام صادق",
        603 to "شهادت حضرت فاطمه", 713 to "ولادت امام علی", 727 to "مبعث",
        815 to "ولادت امام زمان", 921 to "شهادت امام علی", 1001 to "عید فطر", 1002 to "تعطیل عید فطر",
        1025 to "شهادت امام صادق", 1210 to "عید قربان", 1218 to "عید غدیر",
    )

    /** نام تعطیلی رسمی (یا null). [jm]/[jd] ماه و روز خورشیدی، [h] تاریخ قمری. */
    fun holiday(jm: Int, jd: Int, h: IntArray): String? {
        solar[jm * 100 + jd]?.let { return it }
        lunar[h[1] * 100 + h[2]]?.let { return it }
        // آخر صفر (۲۹ یا ۳۰): شهادت امام رضا
        if (h[1] == 2 && h[2] >= 29) return "شهادت امام رضا"
        return null
    }
}

/** تبدیل تاریخ میلادی به خورشیدی و نوشتن عدد با رقم فارسی. */
object Persian {
    val months = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")

    /** نام روز هفته؛ ورودی همان Calendar.DAY_OF_WEEK (۱ = یکشنبه). */
    fun weekday(calDow: Int) = when (calDow) {
        1 -> "یکشنبه"; 2 -> "دوشنبه"; 3 -> "سه‌شنبه"; 4 -> "چهارشنبه"
        5 -> "پنجشنبه"; 6 -> "جمعه"; else -> "شنبه"
    }

    fun toJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
        val jd = 1 + if (days < 186) days % 31 else (days - 186) % 30
        return intArrayOf(jy, jm, jd)
    }

    fun digits(s: String, on: Boolean = true): String {
        if (!on) return s
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '0'..'9') ('۰' + (ch - '0')) else ch)
        return sb.toString()
    }
}

/** نام ماه‌های میلادی به فارسی. */
object Gregorian {
    val months = arrayOf("ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن",
        "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر")
}

/**
 * اوقات اذان.
 * - شیعه (۳ وقت): صبح (‎-۱۷٫۷°)، ظهر، مغرب (‎-۴°) — معیار مؤسسهٔ ژئوفیزیک دانشگاه تهران (تقویم رسمی).
 * - اهل سنت (۵ وقت): صبح (‎-۱۸°)، ظهر، عصر (سایه = ۱ یا ۲ برابر)، مغرب (غروب)، عشا (‎-۱۷°) — معیار رابطة العالم الإسلامی.
 */
object Prayers {
    const val MODE_OFF = 0
    const val MODE_SHIA = 1
    const val MODE_SUNNI = 2

    class Time(val name: String, val ms: Long)

    /** اوقات اذان روزی که [noonMs] (ظهر محلی) در آن است. */
    fun of(noonMs: Long, lat: Double, lon: Double, mode: Int, hanafi: Boolean = false): List<Time> {
        val out = ArrayList<Time>(5)
        fun add(n: String, t: Long?) { if (t != null) out.add(Time(n, t)) }
        when (mode) {
            MODE_SHIA -> {
                add("اذان صبح", SunCalc.sunrise(noonMs, lat, lon, SunCalc.FAJR_ALT))
                add("اذان ظهر", SunCalc.noon(noonMs, lat, lon))
                add("اذان مغرب", SunCalc.sunset(noonMs, lat, lon, -4.0))
            }
            MODE_SUNNI -> {
                add("اذان صبح", SunCalc.sunrise(noonMs, lat, lon, -18.0))
                add("اذان ظهر", SunCalc.noon(noonMs, lat, lon) + 60_000L)
                add("اذان عصر", SunCalc.asr(noonMs, lat, lon, if (hanafi) 2 else 1))
                add("اذان مغرب", SunCalc.sunset(noonMs, lat, lon))
                add("اذان عشا", SunCalc.sunset(noonMs, lat, lon, -17.0))
            }
        }
        return out
    }
}
