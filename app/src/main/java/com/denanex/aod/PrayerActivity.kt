package com.denanex.aod

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * قبله‌نما، اوقات شرعی و سه سلام (برگرفته از صفحهٔ «قطب‌نمای بزرگ» برنامهٔ رانندگی هوشمند):
 * قطب‌نمای بزرگ با جهت قبله، مشهد و کربلا؛ اوقات شرعی امروز به افق شهر انتخابی؛ پخش آزمایشی اذان؛
 * سه سلام (رو به کربلا، مشهد و قبله؛ با ضربه باز و بسته می‌شود، با دو انگشت بزرگ و کوچک)؛
 * دعای خاصهٔ امام رضا (ع) و صلوات خاصهٔ امام زمان (عج).
 */
class PrayerActivity : Activity() {

    private lateinit var compassView: PilgrimCompassView
    private lateinit var compass: Compass
    private lateinit var adhkarText: TextView
    private lateinit var btnAdhan: Button
    private lateinit var timesText: TextView

    /** کدام‌یک از سه سلام باز (متن کامل) است؛ پیش‌فرض همه بسته. */
    private val salamExpanded = BooleanArray(3)
    /** محدودهٔ متنی هر سلام، برای فهمیدن این‌که روی کدام ضربه زده شده. */
    private val salamRanges = arrayOfNulls<IntRange>(3)
    private val adhkarBaseSp = 17f
    private var adhkarZoom = 1f
    private var adhanPlaying = false

    private val soundFinished = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { adhanPlaying = false; updateAdhanButton() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val loc = prefs.location()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }

        compassView = PilgrimCompassView(this).apply {
            qiblaBearing = Qibla.bearing(loc.lat, loc.lon).toFloat()
            mashhadBearing = bearingTo(loc.lat, loc.lon, MASHHAD_LAT, MASHHAD_LNG)
            karbalaBearing = bearingTo(loc.lat, loc.lon, KARBALA_LAT, KARBALA_LNG)
        }
        root.addView(compassView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        compass = Compass(this) { compassView.post { compassView.azimuth = compass.heading ?: compassView.azimuth } }

        root.addView(TextView(this).apply {
            text = if (compass.available) "⚠️ راهنمای کالیبره قطب‌نما و قبله‌نما (لمس کنید)"
                   else "این گوشی حسگر قطب‌نما ندارد؛ جهت‌ها نسبت به بالای گوشی درست نیستند."
            textSize = 13f
            setTextColor(Color.rgb(0xFF, 0xB7, 0x4D))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showCalibration() }
        })

        // اوقات شرعی امروز
        root.addView(TextView(this).apply {
            text = "اوقات شرعی امروز — به افق ${loc.name}"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(0x80, 0xDE, 0xEA))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        })
        timesText = TextView(this)
        root.addView(timesText.apply {
            text = prayerTimesText(loc.lat, loc.lon, prefs.adhanMode, prefs.asrHanafi)
            textSize = 16f
            setTextColor(Color.rgb(0xEE, 0xEE, 0xF2))
            gravity = Gravity.CENTER
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = card(Color.rgb(0x1A, 0x1E, 0x2A))
        })

        // پخش آزمایشی اذان
        btnAdhan = Button(this).apply {
            textSize = 15f
            isAllCaps = false
            setOnClickListener {
                if (adhanPlaying) SoundService.stop(this@PrayerActivity)
                else SoundService.start(this@PrayerActivity, SoundService.KIND_ADHAN, "پخش آزمایشی اذان")
                adhanPlaying = !adhanPlaying
                updateAdhanButton()
            }
        }
        // پخش اذان: خاموش / شیعه / اهل سنت (همان تنظیم صفحهٔ تنظیمات؛ همین‌جا ذخیره می‌شود)
        root.addView(TextView(this).apply {
            text = "🕌 پخش اذان در وقت نماز:"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(0xFF, 0xE2, 0xA0))
            setPadding(0, dp(16), 0, dp(4))
        })
        val modes = arrayOf("خاموش", "شیعه (۳ وقت)", "اهل سنت (۵ وقت)")
        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = card(Color.rgb(0x1A, 0x22, 0x1C))
        }
        for ((i, m) in modes.withIndex()) {
            group.addView(android.widget.RadioButton(this).apply {
                id = 100 + i
                text = m
                textSize = 14f
                setTextColor(Color.WHITE)
            }, android.widget.RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.check(100 + prefs.adhanMode)
        group.setOnCheckedChangeListener { _, checkedId ->
            val p = Prefs(this)
            p.adhanMode = (checkedId - 100).coerceIn(0, 2)
            Adhan.schedule(this)
            val l = p.location()
            timesText.text = prayerTimesText(l.lat, l.lon, p.adhanMode, p.asrHanafi)
            android.widget.Toast.makeText(this, "ذخیره شد: پخش اذان " + modes[p.adhanMode], android.widget.Toast.LENGTH_SHORT).show()
        }
        root.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(btnAdhan, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        updateAdhanButton()

        // سه سلام
        adhkarText = TextView(this).apply {
            textSize = adhkarBaseSp
            setTextColor(Color.rgb(0xC8, 0xE6, 0xC9))
            textDirection = View.TEXT_DIRECTION_RTL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            gravity = Gravity.END
            if (Build.VERSION.SDK_INT >= 26) justificationMode = Layout.JUSTIFICATION_MODE_INTER_WORD
            setLineSpacing(dp(8).toFloat(), 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = card(Color.rgb(0x12, 0x24, 0x18))
        }
        adhkarText.text = buildAdhkar()
        root.addView(adhkarText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(22) })
        setupAdhkarTouch()

        // دعای خاصه
        root.addView(linkRow("📿  دعای خاصهٔ امام رضا (ع)") { showText("دعای خاصهٔ امام رضا (ع)", DUA_REZA) })
        root.addView(linkRow("📿  صلوات خاصهٔ امام زمان (عج)") { showText("صلوات خاصهٔ امام زمان (عج)", DUA_ZAMAN) })

        root.addView(Button(this).apply {
            text = "بازگشت"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0x0C, 0x0E, 0x14))
            addView(root)
        })

        val f = IntentFilter(SoundService.ACTION_FINISHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(soundFinished, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(soundFinished, f)
    }

    override fun onResume() {
        super.onResume()
        val loc = Prefs(this).location()
        compass.start(loc.lat, loc.lon)
    }

    override fun onPause() {
        compass.stop()
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(soundFinished) } catch (_: Exception) {}
        // با بستن این صفحه، پخش آزمایشی اذان هم قطع می‌شود
        if (adhanPlaying) SoundService.stop(this)
        super.onDestroy()
    }

    private fun updateAdhanButton() {
        btnAdhan.text = if (adhanPlaying) "⏹  قطع اذان" else "▶  پخش آزمایشی اذان"
    }

    private fun card(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }

    private fun linkRow(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(0x90, 0xCA, 0xF9))
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(14), dp(8), dp(6))
        setOnClickListener { onClick() }
    }

    // ---------------- اوقات شرعی ----------------

    private fun prayerTimesText(lat: Double, lon: Double, mode: Int, hanafi: Boolean): String {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val noon = c.timeInMillis
        fun hm(ms: Long?): String {
            if (ms == null) return "—"
            val k = Calendar.getInstance().apply { timeInMillis = ms }
            return Persian.digits(String.format(Locale.US, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE)))
        }
        val sunni = mode == Prayers.MODE_SUNNI
        val fajr = SunCalc.sunrise(noon, lat, lon, if (sunni) -18.0 else SunCalc.FAJR_ALT)
        val rise = SunCalc.sunrise(noon, lat, lon)
        val dhuhr = SunCalc.noon(noon, lat, lon)
        val asr = SunCalc.asr(noon, lat, lon, if (sunni && hanafi) 2 else 1)
        val set = SunCalc.sunset(noon, lat, lon)
        val maghrib = if (sunni) set else SunCalc.sunset(noon, lat, lon, -4.0)
        val isha = SunCalc.sunset(noon, lat, lon, if (sunni) -17.0 else -14.0)
        return "اذان صبح: ${hm(fajr)}      طلوع آفتاب: ${hm(rise)}\n" +
            "اذان ظهر: ${hm(dhuhr)}      عصر: ${hm(asr)}\n" +
            "غروب آفتاب: ${hm(set)}      اذان مغرب: ${hm(maghrib)}\n" +
            "عشا: ${hm(isha)}"
    }

    // ---------------- سه سلام ----------------

    /** یک ضربه روی هر سلام، متن کاملش را باز/بسته می‌کند؛ با دو انگشت (پینچ) قلم بزرگ و کوچک می‌شود. */
    private fun setupAdhkarTouch() {
        val scale = android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                    adhkarZoom = (adhkarZoom * d.scaleFactor).coerceIn(1f, 3f)
                    adhkarText.textSize = adhkarBaseSp * adhkarZoom
                    return true
                }
            })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val off = adhkarText.getOffsetForPosition(e.x, e.y)
                val i = salamRanges.indexOfFirst { it != null && off in it }
                if (i >= 0) {
                    salamExpanded[i] = !salamExpanded[i]
                    adhkarText.text = buildAdhkar()
                }
                return true
            }
        })
        adhkarText.setOnTouchListener { v, ev ->
            scale.onTouchEvent(ev)
            tap.onTouchEvent(ev)
            // هنگام پینچ، صفحه اسکرول نشود
            v.parent?.requestDisallowInterceptTouchEvent(ev.pointerCount > 1)
            true
        }
    }

    private fun buildAdhkar(): CharSequence {
        val b = SpannableStringBuilder()
        fun heading(text: String, icon: Bitmap) {
            val start = b.length
            b.append(text).append(" ")
            val iconPos = b.length
            b.append(" ")
            b.setSpan(ImageSpan(this, icon, ImageSpan.ALIGN_BOTTOM), iconPos, iconPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(StyleSpan(Typeface.BOLD), start, iconPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(ForegroundColorSpan(Color.rgb(0x90, 0xCA, 0xF9)), start, iconPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append("\n")
        }
        fun body(text: String) {
            val start = b.length
            b.append(text)
            b.setSpan(RelativeSizeSpan(1.15f), start, b.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append("\n")
        }
        fun salam(i: Int, title: String, icon: Bitmap, text: String) {
            val start = b.length
            heading((if (salamExpanded[i]) "▾ " else "◂ ") + title, icon)
            if (salamExpanded[i]) body(text)
            salamRanges[i] = start until b.length
        }
        val hs = b.length
        b.append("برای دیدن متن هر سلام، روی آن ضربه بزنید\n")
        b.setSpan(RelativeSizeSpan(0.8f), hs, b.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.setSpan(ForegroundColorSpan(Color.rgb(0x9E, 0x9E, 0x9E)), hs, b.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        salam(0, "۱- رو به سمت کربلا و سلام به امام حسین علیه‌السلام", iconBitmap(0), SALAM_HUSSEIN)
        salam(1, "۲- رو به سوی مشهد مقدس و سلام به امام رضا علیه‌السلام", iconBitmap(1), SALAM_REZA)
        salam(2, "۳- رو به قبله و سلام به امام زمان عجل‌الله‌تعالی‌فرجه‌الشریف", iconBitmap(2), SALAM_ZAMAN)
        return b
    }

    /** آیکون کوچک کربلا (۰)، مشهد (۱) یا قبله (۲)، همان‌هایی که دور قطب‌نما کشیده می‌شوند. */
    private fun iconBitmap(kind: Int): Bitmap {
        val size = dp(40)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = size * 0.16f
        when (kind) {
            0 -> PilgrimCompassView.drawKarbala(c, size / 2f, size * 0.68f, u, resources.displayMetrics.density)
            1 -> PilgrimCompassView.drawMashhad(c, size / 2f, size * 0.68f, u)
            else -> PilgrimCompassView.drawKaaba(c, size / 2f, size / 2f, size * 0.94f)
        }
        return bmp
    }

    // ---------------- پنجره‌ها ----------------

    private fun showText(title: String, body: String) {
        val tv = TextView(this).apply {
            text = body
            textSize = 17f
            setTextColor(Color.rgb(0xEE, 0xEE, 0xF2))
            textDirection = View.TEXT_DIRECTION_RTL
            gravity = Gravity.END
            if (Build.VERSION.SDK_INT >= 26) justificationMode = Layout.JUSTIFICATION_MODE_INTER_WORD
            setLineSpacing(dp(8).toFloat(), 1f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("بستن", null)
            .create()
        dlg.window?.setBackgroundDrawable(card(Color.rgb(0x14, 0x18, 0x24)))
        dlg.show()
    }

    private fun showCalibration() = showText("راهنمای کالیبره قطب‌نما و قبله‌نما",
        "اگر جهت قطب‌نما درست به نظر نمی‌رسد:\n\n" +
        "۱. گوشی را در دست بگیرید و چند بار به شکل عدد ۸ در هوا بچرخانید (بالا، پایین، چپ، راست).\n" +
        "۲. این کار را حدود ۵ تا ۱۰ ثانیه ادامه دهید.\n" +
        "۳. از اشیای فلزی، آهن‌ربا، بند فلزی ساعت یا نزدیکی شارژر و باتری فاصله بگیرید؛ این‌ها حسگر جهت‌یاب را دچار خطا می‌کنند.\n" +
        "۴. بعد از چند ثانیه، عقربه باید ثابت و درست بایستد.\n\n" +
        "این کالیبراسیون روی دقت قطب‌نما و جهت قبله، مشهد و کربلا اثر می‌گذارد، چون همه از یک حسگر استفاده می‌کنند.")

    private fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    companion object {
        // مختصات تقریبی حرم امام رضا (ع)، مشهد، و حرم امام حسین (ع)، کربلا
        private const val MASHHAD_LAT = 36.2880
        private const val MASHHAD_LNG = 59.6157
        private const val KARBALA_LAT = 32.6160
        private const val KARBALA_LNG = 44.0245

        const val SALAM_HUSSEIN = "اَلسَّلامُ عَلَیکَ یا اَباعَبدِالله اَلسَّلامُ عَلَیکَ یَابنَ رَسوُلِ الله اَلسَّلامُ عَلَیکَ یَابنَ اَمیرِالمُؤمِنینَ وَابنَ فاطِمَةَ الزَّهراء سَیِّدَةَ نِساءِ العالَمینَ وَ رَحمَةُ اللهِ وَ بَرَکاتُهُ."
        const val SALAM_REZA = "اَلسَّلامُ عَلَیکَ یا غَریبَ الغُرَباء اَلسَّلامُ عَلَیکَ یا مُعینَ الضُّعَفاءِ وَ الفُقَراء اَلسُّلطانَ اَبَاالحَسَنِ عَلیَّ بنَ موُسَی الرِّضا وَ رَحمَةُ اللهِ وَ بَرَکاتُهُ."
        const val SALAM_ZAMAN = "اَلسَّلامُ عَلَیکَ یا مَولانا یا صاحِبَ الزَّمان اَلسَّلامُ عَلَیکَ یا خَلیفَةَ الرَّحمنِ اَلسَّلامُ عَلَیکَ یاشَریکَ القُرآنِ عَجَّلَ اللهُ تَعالی فَرَجَهُ وَ سَهَّلَ اللهُ مَخرَجَهُ وَ رَحمَةُ اللهِ وَ بَرَکاتُهُ."

        const val DUA_REZA = "اَللَّهُمَّ صَلِّ عَلَى عَلِيِّ بْنِ مُوسَى الرِّضَا الْمُرْتَضَى الْإِمَامِ التَّقِيِّ النَّقِيِّ\n" +
            "بارالها درود و رحمت فرست بر على بن موسى الرضا، پسندیده، پیشوای پارسا و منزه\n\n" +
            "وَ حُجَّتِكَ عَلَى مَنْ فَوْقَ الْأَرْضِ وَ مَنْ تَحْتَ الثَّرَى الصِّدِّيقِ الشَّهِيدِ\n" +
            "و حجت تو بر هر که روی زمین است و هر که زیر خاک، بسیار راستگو و شهید\n\n" +
            "صَلاَةً كَثِيرَةً تَامَّةً زَاكِيَةً مُتَواصلَةً مُتَواتِرَةً مُتَرادِفَةً كَأَفْضَلِ مَا صَلَّيْتَ عَلَى أَحَدٍ مِنْ أَوْلِيَائِكَ\n" +
            "درود و رحمتی فراوان، کامل، با برکت، متصل، پیوسته و پیاپی؛ همچون بهترین رحمتی که بر یکی از اولیائت فرستادی"

        const val DUA_ZAMAN = "اَللَّهُمَّ صَلِّ عَلَى وَلِيِّكَ وَ ابْنِ أَوْلِيَائِكَ الَّذِينَ فَرَضْتَ طَاعَتَهُمْ وَ أَوْجَبْتَ حَقَّهُمْ وَ أَذْهَبْتَ عَنْهُمُ الرِّجْسَ وَ طَهَّرْتَهُمْ تَطْهِيراً\n" +
            "اللَّهُمَّ انْصُرْهُ وَ انْتَصِرْ بِهِ لِدِينِكَ وَ انْصُرْ بِهِ أَوْلِيَاءَكَ وَ أَوْلِيَاءَهُ وَ شِيعَتَهُ وَ أَنْصَارَهُ وَ اجْعَلْنَا مِنْهُمْ\n" +
            "اللَّهُمَّ أَعِذْهُ مِنْ شَرِّ كُلِّ بَاغٍ وَ طَاغٍ وَ مِنْ شَرِّ جَمِيعِ خَلْقِكَ وَ احْفَظْهُ مِنْ بَيْنِ يَدَيْهِ وَ مِنْ خَلْفِهِ وَ عَنْ يَمِينِهِ وَ عَنْ شِمَالِهِ " +
            "وَ احْرُسْهُ وَ امْنَعْهُ أَنْ يُوصَلَ إِلَيْهِ بِسُوءٍ وَ احْفَظْ فِيهِ رَسُولَكَ وَ آلَ رَسُولِكَ وَ أَظْهِرْ بِهِ الْعَدْلَ وَ أَيِّدْهُ بِالنَّصْرِ وَ انْصُرْ نَاصِرِيهِ " +
            "وَ اخْذُلْ خَاذِلِيهِ وَ اقْصِمْ بِهِ جَبَابِرَةَ الْكُفْرِ وَ اقْتُلْ بِهِ الْكُفَّارَ وَ الْمُنَافِقِينَ وَ جَمِيعَ الْمُلْحِدِينَ حَيْثُ كَانُوا مِنْ مَشَارِقِ الْأَرْضِ وَ مَغَارِبِهَا " +
            "وَ بَرِّهَا وَ بِحْرِهَا وَ امْلَأْ بِهِ الْأَرْضَ عَدْلاً وَ أَظْهِرْ بِهِ دِينَ نَبِيِّكَ عَلَيْهِ وَ آلِهِ السَّلاَمُ وَ اجْعَلْنِي اللَّهُمَّ مِنْ أَنْصَارِهِ وَ أَعْوَانِهِ وَ أَتْبَاعِهِ وَ شِيعَتِهِ " +
            "وَ أَرِنِي فِي آلِ مُحَمَّدٍ مَا يَأْمُلُونَ وَ فِي عَدُوِّهِمْ مَا يَحْذَرُونَ إِلَهَ الْحَقِّ آمِينَ\n\n" +
            "ای خدا درود فرست بر ولی خود، فرزند دوستان خاص خود، آنان که طاعت و حقوق آن‌ها را بر خلق فرض و واجب گردانیدی و از آن ارواح قدسی الهی هر رجس و پلیدی شرک را دور ساختی و آنان را کاملاً پاک و منزه از هر عیب و عصیان گردانیدی.\n" +
            "پروردگارا، تو او را یاری کن و به وجود او اهل دین خود را منصور و مظفر فرما، و دوستان خود و دوستان و شیعیان و یاران او را به‌واسطهٔ او ظفر و پیروزی عطا کن و ما را هم از آن یاران و شیعیانش قرار ده.\n" +
            "ای خدا، آن بزرگوار را از شر و آزار هر ظالم و طاغی سرکش در پناه خود گیر، و از شر جمیع اهل شر که پیش روی او یا از پشت سر وی یا از راست و چپ او درآیند محفوظش بدار، و از آنکه دست آزار و سوءقصد کسی به او رسد آن وجود مبارک را نگهداری کن و منع فرما، " +
            "و هم به‌واسطهٔ سلطنت الهیهٔ او حق رسول و آل رسولت را محفوظ گردان و حکومت عدل کامل را به‌وجود او آشکار گردان، و تو او را مؤید و منصور فرما و یاران او را هم نصرت فرما و دشمنانش را مخذول و ذلیل ساز، " +
            "و به قدرت او پشت جباران و سرکشان عالم را بشکن و کفار و منافقان و جمیع ملحدان را از مشرق و مغرب روی زمین و بر و بحر دنیا همه به شمشیر قهر او به خاک هلاک انداز، و زمین را در سلطنتش پر از عدل گردان، " +
            "و دین پیغمبر اکرمت (ص) را ظاهر و غالب فرما.\n" +
            "و پروردگارا، مرا هم از انصار و یاوران و پیروان و شیعیان آن بزرگوار قرار ده، و آنچه آرزوی آل محمد است از ظهور سلطنت عدل الهی دربارهٔ آن‌ها به من بنما، و دربارهٔ دشمنان آل رسول هم آن هلاکت و ذلت و انتقامی که از آن می‌ترسیدند به من بنما، ای خدای حق، این دعای مرا مستجاب فرما."
    }
}

/**
 * قطب‌نمای بزرگ با عقربه‌ها و آیکون‌های قبله، مشهد و کربلا (همان طرح برنامهٔ رانندگی هوشمند، با رنگ‌های
 * تیره). [azimuth] = سمت بالای گوشی از شمال؛ بقیه = سمت مقصد از شمال، از موقعیت شهر انتخابی.
 */
class PilgrimCompassView(ctx: Context) : View(ctx) {
    var azimuth = 0f
        set(v) { field = v; invalidate() }
    var mashhadBearing: Float? = null
        set(v) { field = v; invalidate() }
    var karbalaBearing: Float? = null
        set(v) { field = v; invalidate() }
    var qiblaBearing: Float? = null
        set(v) { field = v; invalidate() }

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = Color.rgb(0x90, 0xA4, 0xAE) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(0x60, 0x7D, 0x8B) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.rgb(0xEC, 0xEF, 0xF1); isFakeBoldText = true }
    private val northLabel = Paint(label).apply { color = Color.rgb(0xFF, 0x52, 0x52) }
    private val southLabel = Paint(label).apply { color = Color.rgb(0x64, 0xB5, 0xF6) }
    private val needleN = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE5, 0x39, 0x35) }
    private val needleS = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xEC, 0xEF, 0xF1) }
    private val needleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.rgb(0x26, 0x32, 0x38) }
    private val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xB0, 0xBE, 0xC5) }
    private val qiblaNeedle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x21, 0x21, 0x21) }
    private val qiblaNeedleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.rgb(0xD4, 0xAF, 0x37) }
    private val mashhadNeedle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x43, 0xA0, 0x47) }
    private val karbalaNeedle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD4, 0xAF, 0x37) }
    private val markerLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // روی صفحه‌های کوتاه، دایره کوچک‌تر می‌شود تا بقیهٔ صفحه هم دیده شود
        val maxByHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        val size = if (w > 0) minOf(w, maxByHeight) else maxByHeight
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(size), size)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f * 0.66f
        label.textSize = radius * 0.11f
        northLabel.textSize = radius * 0.15f
        southLabel.textSize = radius * 0.11f
        markerLabel.textSize = radius * 0.15f

        c.drawCircle(cx, cy, radius, circle)
        c.drawCircle(cx, cy, radius * 0.97f, tick)
        for (deg in 0 until 360 step 10) {
            val len = if (deg % 30 == 0) radius * 0.10f else radius * 0.05f
            val a = Math.toRadians((deg - azimuth).toDouble())
            c.drawLine(cx + (radius * sin(a)).toFloat(), cy - (radius * cos(a)).toFloat(),
                cx + ((radius - len) * sin(a)).toFloat(), cy - ((radius - len) * cos(a)).toFloat(), tick)
        }
        dirLabel(c, cx, cy, radius * 0.82f, 0f, "N", northLabel)
        dirLabel(c, cx, cy, radius * 0.82f, 90f, "E", label)
        dirLabel(c, cx, cy, radius * 0.82f, 180f, "S", southLabel)
        dirLabel(c, cx, cy, radius * 0.82f, 270f, "W", label)

        qiblaBearing?.let { needle(c, cx, cy, radius * 0.62f, it - azimuth, qiblaNeedle, qiblaNeedleLine) }
        mashhadBearing?.let { needle(c, cx, cy, radius * 0.62f, it - azimuth, mashhadNeedle, null) }
        karbalaBearing?.let { needle(c, cx, cy, radius * 0.62f, it - azimuth, karbalaNeedle, null) }

        val u = radius * 0.24f
        mashhadBearing?.let {
            val (x, y) = around(cx, cy, radius * 1.22f, it - azimuth)
            drawMashhad(c, x, y, u)
            markerLabel.color = Color.rgb(0x81, 0xC7, 0x84)
            c.drawText("مشهد", x, y + u * 1.6f, markerLabel)
        }
        karbalaBearing?.let {
            val (x, y) = around(cx, cy, radius * 1.22f, it - azimuth)
            drawKarbala(c, x, y, u, resources.displayMetrics.density)
            markerLabel.color = Color.rgb(0xE8, 0xC2, 0x5A)
            c.drawText("کربلا", x, y + u * 1.6f, markerLabel)
        }
        qiblaBearing?.let {
            val (x, y) = around(cx, cy, radius * 1.22f, it - azimuth)
            val s = radius * 0.42f
            drawKaaba(c, x, y, s)
            markerLabel.color = Color.rgb(0xE8, 0xC2, 0x5A)
            c.drawText("قبله", x, y + s * 1.0f, markerLabel)
        }

        mainNeedle(c, cx, cy, radius * 0.66f)
        val lr = radius * 0.66f + northLabel.textSize * 0.9f
        val a = Math.toRadians((0f - azimuth).toDouble())
        c.drawText("شمال", cx + (lr * sin(a)).toFloat(),
            cy - (lr * cos(a)).toFloat() - (northLabel.descent() + northLabel.ascent()) / 2, northLabel)
        c.drawCircle(cx, cy, radius * 0.045f, pin)
    }

    private fun around(cx: Float, cy: Float, r: Float, deg: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return (cx + (r * sin(a)).toFloat()) to (cy - (r * cos(a)).toFloat())
    }

    private fun dirLabel(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, t: String, p: Paint) {
        val a = Math.toRadians((deg - azimuth).toDouble())
        c.drawText(t, cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat() - (p.descent() + p.ascent()) / 2, p)
    }

    private fun needle(c: Canvas, cx: Float, cy: Float, len: Float, deg: Float, fill: Paint, line: Paint?) {
        val a = Math.toRadians(deg.toDouble())
        val tx = cx + (len * sin(a)).toFloat(); val ty = cy - (len * cos(a)).toFloat()
        val pa = a + Math.PI / 2
        val w = len * 0.13f
        val p = Path().apply {
            moveTo(tx, ty)
            lineTo(cx + (w * sin(pa)).toFloat(), cy - (w * cos(pa)).toFloat())
            lineTo(cx, cy)
            lineTo(cx - (w * sin(pa)).toFloat(), cy + (w * cos(pa)).toFloat())
            close()
        }
        c.drawPath(p, fill)
        line?.let { c.drawPath(p, it) }
    }

    private fun mainNeedle(c: Canvas, cx: Float, cy: Float, len: Float) {
        val a = Math.toRadians((0f - azimuth).toDouble())
        val ta = Math.toRadians((180f - azimuth).toDouble())
        val pa = a + Math.PI / 2
        val w = len * 0.11f
        val lx = cx + (w * sin(pa)).toFloat(); val ly = cy - (w * cos(pa)).toFloat()
        val rx = cx - (w * sin(pa)).toFloat(); val ry = cy + (w * cos(pa)).toFloat()
        val north = Path().apply {
            moveTo(cx + (len * sin(a)).toFloat(), cy - (len * cos(a)).toFloat()); lineTo(lx, ly); lineTo(cx, cy); lineTo(rx, ry); close()
        }
        val south = Path().apply {
            moveTo(cx + (len * sin(ta)).toFloat(), cy - (len * cos(ta)).toFloat()); lineTo(lx, ly); lineTo(cx, cy); lineTo(rx, ry); close()
        }
        c.drawPath(south, needleS); c.drawPath(south, needleLine)
        c.drawPath(north, needleN); c.drawPath(north, needleLine)
    }

    companion object {
        private val flag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE5, 0x39, 0x35) }

        /** گنبد سبز + دو مناره + پرچم قرمز (مشهد)، مرکز ([x]،[y])، واحد اندازه [u]. */
        fun drawMashhad(c: Canvas, x: Float, y: Float, u: Float) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x43, 0xA0, 0x47) }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x81, 0xC7, 0x84); strokeWidth = u * 0.06f }
            c.save(); c.translate(x, y)
            for (m in floatArrayOf(-u * 1.05f, -u * 0.60f)) {
                c.drawRoundRect(m - u * 0.10f, -u * 0.15f, m + u * 0.10f, u * 0.75f, u * 0.05f, u * 0.05f, fill)
                c.drawCircle(m, -u * 0.25f, u * 0.13f, fill)
            }
            c.drawCircle(u * 0.15f, u * 0.15f, u * 0.60f, fill)
            c.drawRect(u * 0.15f - u * 0.60f, u * 0.15f, u * 0.15f + u * 0.60f, u * 0.75f, fill)
            c.drawLine(u * 0.15f, -u * 0.45f, u * 0.15f, -u * 0.80f, line)
            c.drawPath(Path().apply {
                moveTo(u * 0.15f, -u * 0.80f); lineTo(u * 0.60f, -u * 0.68f); lineTo(u * 0.15f, -u * 0.58f); close()
            }, flag)
            c.restore()
        }

        /** طرح خطی یک مناره و یک گنبد با پرچم (کربلا). */
        fun drawKarbala(c: Canvas, x: Float, y: Float, u: Float, density: Float) {
            val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.8f * density
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = Color.rgb(0xE8, 0xC2, 0x5A)
            }
            c.save(); c.translate(x, y)
            c.drawRoundRect(-u * 0.94f, -u * 0.10f, -u * 0.76f, u * 0.70f, u * 0.04f, u * 0.04f, s)
            c.drawCircle(-u * 0.85f, -u * 0.20f, u * 0.11f, s)
            c.drawCircle(u * 0.10f, u * 0.15f, u * 0.55f, s)
            c.drawLine(u * 0.10f - u * 0.55f, u * 0.70f, u * 0.10f + u * 0.55f, u * 0.70f, s)
            c.drawLine(u * 0.10f, -u * 0.40f, u * 0.10f, -u * 0.75f, s)
            c.drawPath(Path().apply {
                moveTo(u * 0.10f, -u * 0.75f); lineTo(u * 0.50f, -u * 0.63f); lineTo(u * 0.10f, -u * 0.53f); close()
            }, flag)
            c.restore()
        }

        /** مکعب سیاه با نوار طلایی و لبهٔ روشن (نماد قبله)، اندازهٔ [s]. */
        fun drawKaaba(c: Canvas, x: Float, y: Float, s: Float) {
            val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x10, 0x10, 0x10) }
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.03f; color = Color.rgb(0xB0, 0xB0, 0xB0) }
            val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD4, 0xAF, 0x37) }
            val l = x - s * 0.32f; val t = y - s * 0.28f; val r = x + s * 0.32f; val b = y + s * 0.28f
            c.drawRect(l, t, r, b, black)
            c.drawRect(l, t, r, b, edge)
            c.drawRect(l, t + (b - t) * 0.32f, r, t + (b - t) * 0.42f, gold)
        }
    }
}
