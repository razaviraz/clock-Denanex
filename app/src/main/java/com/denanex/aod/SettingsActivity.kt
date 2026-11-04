package com.denanex.aod

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

/** صفحهٔ تنظیمات (همهٔ اجزا با کد ساخته می‌شوند؛ بدون فایل طرح‌بندی). */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var preview: ClockView
    private lateinit var sunInfo: TextView
    private lateinit var roosterInfo: TextView
    private lateinit var roosterSound: TextView
    private lateinit var albumInfo: TextView
    private lateinit var albumMusicButton: Button
    private lateinit var gpsSwitch: Switch
    private lateinit var albumSwitch: Switch
    private lateinit var albumMusicSwitch: Switch
    private lateinit var provSpinner: Spinner
    private lateinit var citySpinner: Spinner
    private val styleCards = ArrayList<LinearLayout>()
    private val stylePreviews = ArrayList<ClockView>()
    private val colorDots = ArrayList<View>()

    private var provinces: List<String> = emptyList()
    private var cities: List<City> = emptyList()
    /** هنگام پر کردن فهرست شهرها با کد، انتخاب خودکار اسپینر نادیده گرفته شود. */
    private var fillingCities = false

    // اگر صفحه ۲ دقیقه لمس نشد، ساعت خودکار باز شود
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRun = Runnable {
        if (prefs.autoClock && !isFinishing) startActivity(Intent(this, AodActivity::class.java))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0x10, 0x10, 0x14))
            addView(root)
        }

        root.addView(TextView(this).apply {
            text = "ساعت همیشه‌روشن"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        // پیش‌نمایش زنده
        // پیش‌نمایش زنده به شکل صفحهٔ گوشی (عمودی و کوچک، تا نوشته‌ها روی هم نیفتند)
        preview = ClockView(this).apply { keepScreenOn = false }
        root.addView(preview, LinearLayout.LayoutParams(dp(200), dp(420)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12); bottomMargin = dp(12)
        })

        root.addView(bigButton("▶  نمایش ساعت") {
            startActivity(Intent(this, AodActivity::class.java))
        })
        root.addView(bigButton("🕋  قبله‌نما، اوقات شرعی و سه سلام") {
            startActivity(Intent(this, PrayerActivity::class.java))
        })
        // شکل ساعت، درست زیر «نمایش ساعت»
        section(root, "شکل ساعت")
        root.addView(styleGallery())
        root.addView(bigButton("🌅  پیش‌نمایش طلوع (حدود ۱ دقیقه)") {
            startActivity(Intent(this, AodActivity::class.java).putExtra(AodActivity.EXTRA_DEMO, true))
        })
        root.addView(bigButton("🌇  پیش‌نمایش غروب (حدود ۱ دقیقه)") {
            startActivity(Intent(this, AodActivity::class.java).putExtra(AodActivity.EXTRA_DEMO_SUNSET, true))
        })
        // ---------------- مکان ----------------
        section(root, "📍 استان و شهر (افق شرعی، آب‌وهوا و قبله)")
        root.addView(locationRows())
        root.addView(bigButton("📍  تعیین شهر با GPS") { useMyLocation() })
        gpsSwitch = switch("GPS خودکار: هر ۱۰ دقیقه یک بار شهر به‌روز شود (بعد GPS خاموش می‌شود)", prefs.gpsAuto) {
            prefs.gpsAuto = it
            if (it) { prefs.gpsLastTry = 0L; LocationAuto.maybeUpdate(this) { showCity(prefs.location()); refresh() } }
        }
        root.addView(gpsSwitch)
        sunInfo = hint("")
        root.addView(sunInfo)

        // ---------------- ظاهر ----------------
        section(root, "رنگ")
        root.addView(colorRow())
        root.addView(hint("رنگ انتخابی: " + Prefs.colorNames[prefs.color]).also { colorName = it })

        section(root, "نمایش")
        root.addView(switch("۲۴ ساعته", prefs.h24) { prefs.h24 = it; refresh() })
        root.addView(switch("نمایش ثانیه در ساعت دیجیتال (ساعت آنالوگ همیشه عقربهٔ ثانیه دارد)", prefs.seconds) { prefs.seconds = it; refresh() })
        root.addView(switch("اعداد فارسی", prefs.persianDigits) { prefs.persianDigits = it; refresh() })
        root.addView(switch("گوشی افقی: ساعت دیجیتال تمام‌صفحه (ساعت چپ، دقیقه راست)", prefs.landscapeBig) {
            prefs.landscapeBig = it; refresh()
        })
        root.addView(hint("در حالت افقی، شکل «آنالوگ + دیجیتال درشت»: آنالوگ سمت راست و دیجیتال سمت چپ."))
        root.addView(switch("درصد باتری", prefs.showBattery) { prefs.showBattery = it; refresh() })
        root.addView(switch("آیکون کعبه کنار باتری (با ضربه، قبله‌نما باز می‌شود)", prefs.kaabaIcon) { prefs.kaabaIcon = it; refresh() })
        root.addView(switch("آب‌وهوای شهر (آیکون و دما، از اینترنت)", prefs.weather) { prefs.weather = it; refresh() })
        root.addView(switch("قبله‌نما و قطب‌نما وسط ساعت آنالوگ", prefs.qibla) { prefs.qibla = it; refresh() })
        root.addView(hint("کعبه در جهت قبله و «ش» (مثلث قرمز) سمت شمال نشان داده می‌شود؛ با حسگر قطب‌نمای گوشی و موقعیت شهر. " +
            "وقتی گوشی رو به قبله است، خط قبله سبز می‌شود. گوشی را صاف و دور از آهن‌ربا و فلز نگه دارید."))
        root.addView(switch("اوقات شرعی زیر ساعت: اذان صبح و طلوع آفتاب (با زمان مانده)", prefs.showSunrise) {
            prefs.showSunrise = it; refresh()
        })

        // ---------------- تاریخ ----------------
        section(root, "تاریخ")
        root.addView(checkRow(
            Triple("شمسی", prefs.showDate) { v: Boolean -> prefs.showDate = v; refresh() },
            Triple("قمری", prefs.showHijri) { v: Boolean -> prefs.showHijri = v; refresh() },
            Triple("میلادی", prefs.showGregorian) { v: Boolean -> prefs.showGregorian = v; refresh() }))
        // ---------------- اذان ----------------
        section(root, "🕌 پخش اذان")
        root.addView(spinner("اذان", arrayOf("خاموش", "شیعه — ۳ وقت (صبح، ظهر، مغرب)",
            "اهل سنت — ۵ وقت (صبح، ظهر، عصر، مغرب، عشا)"), prefs.adhanMode, green = true) {
            prefs.adhanMode = it; askNotifications(); Adhan.schedule(this); refresh()
        })
        root.addView(switch("اهل سنت: عصر به مذهب حنفی (سایه دو برابر)", prefs.asrHanafi) {
            prefs.asrHanafi = it; Adhan.schedule(this); refresh()
        })
        root.addView(bigButton("🎵  انتخاب فایل اذان از گوشی") { pickAudio(REQ_ADHAN_FILE) })
        root.addView(bigButton("↩  اذان همراه برنامه") { prefs.adhanUri = ""; prefs.adhanUriName = ""; refresh() })
        root.addView(switch("لرزش گوشی هنگام اذان", prefs.adhanVibrate) { prefs.adhanVibrate = it })
        root.addView(rowButtons("▶  آزمایش اذان" to {
            SoundService.start(this, SoundService.KIND_ADHAN, "آزمایش اذان")
        }, "■  قطع" to { SoundService.stop(this) }))

        // ---------------- زنگ پیش از طلوع ----------------
        section(root, "🐓 زنگ پیش از طلوع آفتاب")
        root.addView(switch("پخش زنگ پیش از طلوع", prefs.rooster) {
            prefs.rooster = it; if (it) askNotifications(); Rooster.schedule(this); refresh()
        })
        root.addView(seek("چند دقیقه پیش از طلوع آفتاب", 0, 60, prefs.roosterMinutes, "دقیقه",
            { v -> if (v == 0) "سر وقت طلوع" else Persian.digits("$v") + " دقیقه" }) {
            prefs.roosterMinutes = it; Rooster.schedule(this); refresh()
        })
        roosterSound = hint("")
        root.addView(roosterSound)
        root.addView(bigButton("🎵  انتخاب فایل صوتی از گوشی") { pickAudio(REQ_ROOSTER_FILE) })
        root.addView(bigButton("🐓  صدای خروس (قوقولی‌قوقو)") { prefs.roosterUri = ""; prefs.roosterUriName = ""; refresh() })
        root.addView(switch("لرزش گوشی هنگام پخش", prefs.roosterVibrate) { prefs.roosterVibrate = it })
        roosterInfo = hint("")
        root.addView(roosterInfo)
        root.addView(rowButtons("▶  آزمایش" to { Rooster.play(this) }, "■  قطع" to { SoundService.stop(this) }))

        // ---------------- نماز شب، نماز آیات و نماز والدین ----------------
        section(root, "🌙 نماز شب، نماز آیات و نماز والدین")
        root.addView(hint("راهنما:"))
        root.addView(rowButtons(
            "🌙 نماز شب" to { startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_KIND, GuideActivity.KIND_NIGHT)) },
            "🌘 نماز آیات" to { startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_KIND, GuideActivity.KIND_AYAT)) },
            "🤲 نماز والدین" to { startActivity(Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_KIND, GuideActivity.KIND_PARENTS)) }))
        root.addView(hint("نمایش در نوار پایین صفحهٔ ساعت:"))
        root.addView(checkRow(
            Triple("نماز شب", prefs.barNight) { v: Boolean -> prefs.barNight = v; refresh() },
            Triple("نماز آیات", prefs.barAyat) { v: Boolean -> prefs.barAyat = v; refresh() },
            Triple("نماز والدین", prefs.barParents) { v: Boolean -> prefs.barParents = v; refresh() }))
        root.addView(switch("بیپ هر ۵ دقیقه از ۲۰ دقیقه مانده به اذان صبح", prefs.fajrBeep) {
            prefs.fajrBeep = it; FajrBeep.schedule(this)
        })
        root.addView(hint("۲۰، ۱۵، ۱۰ و ۵ دقیقه مانده: دو بیپ کوتاه — سر اذان: سه بیپ بلند. " +
            "حتی وقتی اپ بسته است هم بیپ می‌زند. بلندی صدا = صدای «زنگ هشدار» گوشی."))

        // ---------------- آلبوم عکس ----------------
        section(root, "🖼 آلبوم عکس (اختیاری)")
        albumSwitch = switch("نمایش آلبوم عکس بالای صفحهٔ ساعت", prefs.album) { prefs.album = it; refresh() }
        root.addView(albumSwitch)
        root.addView(hint("هر ۵ ثانیه یک عکس با افکت تصادفی؛ پس از آخرین عکس دوباره از اول. عکس‌ها کوچک‌شده در پوشهٔ خود " +
            "برنامه ذخیره می‌شوند و با به‌روزرسانی برنامه پاک نمی‌شوند. (در حالت افقی نمایش داده نمی‌شود.)"))
        albumInfo = hint("")
        root.addView(albumInfo)
        root.addView(bigButton("➕  افزودن عکس از گالری") { pickImages() })
        root.addView(bigButton("🗑  پاک کردن همهٔ عکس‌های آلبوم") {
            android.app.AlertDialog.Builder(this)
                .setMessage("همهٔ عکس‌های آلبوم ساعت پاک شوند؟ (عکس‌های اصلی گالری دست نمی‌خورند.)")
                .setPositiveButton("پاک شود") { _, _ -> AlbumStore.clear(this); refresh() }
                .setNegativeButton("نه", null).show()
        })
        // شکل و نوع قاب در یک سطر
        val frameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        frameRow.addView(spinnerOnly(Prefs.ALBUM_SHAPES, prefs.albumShape) { prefs.albumShape = it; refresh() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        frameRow.addView(spinnerOnly(Prefs.ALBUM_FRAMES, prefs.albumFrame) { prefs.albumFrame = it; refresh() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(hint("قاب عکس (شکل و نوع):"))
        root.addView(frameRow)
        // موزیک پس‌زمینه و انتخاب موزیک در یک سطر
        val musicRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0)
        }
        albumMusicSwitch = switch("موزیک", prefs.albumMusic) { prefs.albumMusic = it; refresh() }
        musicRow.addView(albumMusicSwitch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        albumMusicButton = Button(this).apply {
            textSize = 14f
            isAllCaps = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { pickAudio(REQ_ALBUM_MUSIC) }
        }
        musicRow.addView(albumMusicButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        root.addView(musicRow)

        // ---------------- صفحه ----------------
        section(root, "صفحه")
        root.addView(seek("نور صفحه", 1, 100, prefs.brightness, "٪") { prefs.brightness = it })
        root.addView(switch("هنگام طلوع کم‌کم نور صفحه بیشتر شود", prefs.brightenAtDawn) { prefs.brightenAtDawn = it })
        root.addView(switch("جابه‌جایی آرام ساعت (جلوگیری از سوختن صفحه)", prefs.burnIn) { prefs.burnIn = it; refresh() })
        root.addView(switch("اگر ۲ دقیقه صفحه لمس نشد، ساعت خودکار باز شود", prefs.autoClock) {
            prefs.autoClock = it; resetIdle()
        })

        section(root, "نمایش خودکار هنگام شارژ")
        root.addView(hint("اگر این اپ را به‌عنوان «محافظ صفحه» (Screen saver) انتخاب کنید، هر وقت گوشی در شارژ است " +
            "و صفحه خاموش می‌شود، همین ساعت خودکار نمایش داده می‌شود. در سامسونگ: تنظیمات ← نمایشگر ← محافظ صفحه " +
            "← «ساعت همیشه‌روشن»، و در همان‌جا «زمان شروع: هنگام شارژ».\n" +
            "توجه: اپ‌های غیرسامسونگ نمی‌توانند جای Always On Display خود سامسونگ (با صفحهٔ خاموش) بنشینند؛ " +
            "این اپ صفحه را با نور کم و پس‌زمینهٔ کاملاً سیاه روشن نگه می‌دارد (روی صفحه‌های AMOLED کم‌مصرف است)."))
        root.addView(bigButton("⚙  تنظیم محافظ صفحه") {
            try {
                startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            }
        })

        section(root, "💾 پشتیبان تنظیمات")
        root.addView(hint("با به‌روزرسانی عادی، تنظیمات و آلبوم سر جایشان می‌مانند. فقط اگر برنامه حذف و دوباره نصب شود پاک می‌شوند؛ " +
            "برای آن وقت، تنظیمات را در یک فایل (مثلاً در پوشهٔ Download) ذخیره کنید و بعد از نصب، از همان فایل بازیابی کنید."))
        root.addView(rowButtons(
            "💾 ذخیره در فایل" to { createDoc(REQ_BACKUP_SAVE, "ClockDenanex-settings.json") },
            "📂 بازیابی از فایل" to { openDoc(REQ_BACKUP_LOAD) }))

        section(root, "📤 اشتراک‌گذاری برنامه")
        root.addView(bigButton("📤  فرستادن خود برنامه (فایل APK)") { Share.apk(this) })
        root.addView(bigButton("🔗  فرستادن لینک دانلود") { Share.link(this) })

        section(root, "به‌روزرسانی")
        root.addView(hint("نسخهٔ نصب‌شده: " + Persian.digits(Updater.currentName(this)) +
            "\nبا هر بار باز شدن اپ، نسخهٔ جدید بررسی می‌شود و اگر بود خبر می‌دهد."))
        root.addView(bigButton("⬆  بررسی به‌روزرسانی") { Updater.check(this, manual = true) })

        root.addView(TextView(this).apply {
            text = Prefs.CREDIT
            textSize = 15f
            setTextColor(Color.rgb(0xC8, 0xC8, 0xD0))
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = Prefs.dedication()
            textSize = 14f
            setTextColor(Color.rgb(0xC8, 0xC8, 0xD0))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "نسخهٔ " + Persian.digits(Updater.currentName(this@SettingsActivity))
            textSize = 13f
            setTextColor(Color.rgb(0x90, 0x90, 0x98))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })

        setContentView(scroll)
        refresh()
        FajrBeep.scheduleAll(this)
        if (savedInstanceState == null) Updater.check(this)
    }

    override fun onResume() {
        super.onResume()
        preview.reload()
        preview.start()
        refresh()
        Updater.resume(this)
        resetIdle()
    }

    override fun onPause() {
        idleHandler.removeCallbacks(idleRun)
        preview.stop()
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetIdle()
        return super.dispatchTouchEvent(ev)
    }

    /** شمارش ۲ دقیقهٔ بی‌لمسی را از نو شروع می‌کند. */
    private fun resetIdle() {
        idleHandler.removeCallbacks(idleRun)
        if (prefs.autoClock) idleHandler.postDelayed(idleRun, IDLE_MS)
    }

    private fun hmOf(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return Persian.digits(String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
    }

    private fun refresh() {
        preview.reload()
        for (p in stylePreviews) p.reload()
        updateStyleSelection()
        val loc = prefs.location()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, 0)
        val now = System.currentTimeMillis()
        var sr = SunCalc.sunrise(cal.timeInMillis, loc.lat, loc.lon)
        var day = "امروز"
        if (sr != null && sr < now) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            sr = SunCalc.sunrise(cal.timeInMillis, loc.lat, loc.lon)
            day = "فردا"
        }
        val now2 = Calendar.getInstance()
        albumInfo.text = "تعداد عکس‌های آلبوم: " + Persian.digits(AlbumStore.count(this).toString())
        albumMusicButton.text = "🎵 " + (if (prefs.albumMusicUri.isNotEmpty()) prefs.albumMusicName else "انتخاب موزیک")
        roosterSound.text = "صدا: " + if (prefs.roosterUri.isNotEmpty()) "فایل انتخابی «${prefs.roosterUriName}»" else "صدای خروس"

        if (sr == null) { sunInfo.text = ""; roosterInfo.text = ""; return }
        val d = sr - prefs.dawnMinutes * 60_000L
        val fj = SunCalc.fajr(cal.timeInMillis, loc.lat, loc.lon)
        val ss = SunCalc.sunset(cal.timeInMillis, loc.lat, loc.lon)
        sunInfo.text = "$day به افق ${loc.name} (${loc.province}): " +
            (if (fj == null) "" else "اذان صبح ${hmOf(fj)} — ") + "طلوع آفتاب ${hmOf(sr)} — شروع سفیدی صبح ${hmOf(d)}" +
            (if (ss == null) "" else " — غروب ${hmOf(ss)}")
        val nextR = Rooster.times(this, now).filter { it > now }.minOrNull()
        val rs = Calendar.getInstance().apply { timeInMillis = nextR ?: now }
        val rDay = if (rs.get(Calendar.DAY_OF_YEAR) == now2.get(Calendar.DAY_OF_YEAR)) "امروز" else "فردا"
        roosterInfo.text = (if (prefs.rooster && nextR != null) "$rDay ساعت ${hmOf(nextR)} پخش می‌شود. " else "خاموش است. ") +
            "حتی وقتی اپ بسته است هم پخش می‌شود. بلندی صدا = صدای «زنگ هشدار» گوشی."
    }

    // ---------------- شکل و رنگ (نمونه‌های تصویری) ----------------

    private var colorName: TextView? = null

    private fun styleGallery(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // اول ساعت آنالوگ پیش‌فرض، بعد بقیه
        val order = listOf(ClockView.STYLE_ANALOG_DIGITAL) + (0 until Prefs.STYLE_COUNT).filter { it != ClockView.STYLE_ANALOG_DIGITAL }
        for (i in order) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(6))
                isClickable = true
                tag = i       // شمارهٔ شکل (ترتیب نمایش با شماره فرق دارد)
                setOnClickListener { prefs.style = i; refresh() }
            }
            val pv = ClockView(this, previewStyle = i)
            pv.contentDescription = Prefs.styleNames[i]
            card.addView(pv, LinearLayout.LayoutParams(dp(104), dp(128)))
            card.addView(TextView(this).apply {
                text = Prefs.styleNames[i]
                textSize = 10f
                setTextColor(Color.rgb(0xD0, 0xD0, 0xD8))
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(3), 0, 0)
            }, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
            // لمس روی خود نمونه هم انتخاب کند
            pv.setOnClickListener { prefs.style = i; refresh() }
            stylePreviews.add(pv)
            styleCards.add(card)
            row.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun colorRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        for (i in Prefs.colorValues.indices) {
            val dot = View(this).apply {
                isClickable = true
                contentDescription = Prefs.colorNames[i]
                setOnClickListener {
                    prefs.color = i
                    colorName?.text = "رنگ انتخابی: " + Prefs.colorNames[i]
                    refresh()
                }
            }
            colorDots.add(dot)
            row.addView(dot, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun updateStyleSelection() {
        for (card in styleCards) {
            val i = card.tag as Int
            card.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(0x05, 0x05, 0x08))
                setStroke(dp(if (i == prefs.style) 3 else 1),
                    if (i == prefs.style) Color.rgb(0xFF, 0xA5, 0x48) else Color.rgb(0x30, 0x30, 0x38))
            }
        }
        for ((i, dot) in colorDots.withIndex()) {
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Prefs.colorValues[i])
                setStroke(dp(if (i == prefs.color) 4 else 1),
                    if (i == prefs.color) Color.rgb(0xFF, 0xA5, 0x48) else Color.rgb(0x50, 0x50, 0x58))
            }
        }
    }

    // ---------------- استان و شهر ----------------

    private fun locationRows(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        provinces = Cities.provinces(this)
        val loc = prefs.location()

        provSpinner = Spinner(this)
        provSpinner.adapter = darkAdapter(provSpinner, provinces, green = true)
        val pIdx = provinces.indexOf(loc.province).coerceAtLeast(0)
        provSpinner.setSelection(pIdx, false)
        citySpinner = Spinner(this)
        fillCities(provinces.getOrElse(pIdx) { "" }, loc.name)

        provSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val p = provinces[position]
                if (p == prefs.location().province) return
                fillCities(p, null)
                cities.firstOrNull()?.let { selectCity(it) }   // مرکز استان
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        citySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (fillingCities) return
                val c = cities.getOrNull(position) ?: return
                val cur = prefs.location()
                if (c.name == cur.name && c.province == cur.province) return
                selectCity(c)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // استان و شهر در یک سطر
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(provSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(citySpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        return box
    }

    private fun fillCities(province: String, select: String?) {
        fillingCities = true
        cities = Cities.of(this, province)
        citySpinner.adapter = darkAdapter(citySpinner, cities.map { it.name }, green = true)
        val i = if (select == null) 0 else cities.indexOfFirst { it.name == select }.coerceAtLeast(0)
        citySpinner.setSelection(i, false)
        // انتخاب خودکار اسپینر بعد از این خط اجرا می‌شود؛ پس از آن دوباره گوش بده
        citySpinner.post { fillingCities = false }
    }

    private fun selectCity(c: City) {
        // انتخاب دستی شهر، GPS خودکار را خاموش می‌کند
        if (prefs.gpsAuto) { prefs.gpsAuto = false; gpsSwitch.isChecked = false }
        prefs.setCity(c)
        FajrBeep.scheduleAll(this)
        refresh()
    }

    /** استان و شهر را روی [c] می‌گذارد (پس از GPS). */
    private fun showCity(c: City) {
        val pIdx = provinces.indexOf(c.province)
        if (pIdx >= 0) provSpinner.setSelection(pIdx, false)
        fillCities(c.province, c.name)
    }

    /**
     * فهرست کشویی با رنگ‌های صریح (مستقل از پوستهٔ گوشی، تا نوشته سیاه و ناخوانا نشود):
     * گزینهٔ انتخاب‌شده روی خود اسپینر با زمینهٔ تیره و نوشتهٔ سبز (اگر [green]) یا سفید؛
     * فهرست باز‌شده با زمینهٔ تیره و نوشتهٔ سفید، و گزینهٔ انتخاب‌شده سبز.
     */
    private fun darkAdapter(sp: Spinner, items: List<String>, green: Boolean): ArrayAdapter<String> {
        sp.setPopupBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.rgb(0x22, 0x22, 0x2A)); cornerRadius = dp(8).toFloat()
        })
        sp.background = GradientDrawable().apply {
            setColor(Color.rgb(0x1A, 0x22, 0x1C)); cornerRadius = dp(8).toFloat()
            setStroke(dp(1), if (green) Color.rgb(0x3E, 0x6E, 0x44) else Color.rgb(0x44, 0x44, 0x50))
        }
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            private fun row(position: Int, closed: Boolean): TextView = TextView(this@SettingsActivity).apply {
                text = getItem(position)
                textSize = 16f
                maxLines = 2
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val selected = position == sp.selectedItemPosition
                setTextColor(when {
                    closed && green -> SELECTED_GREEN
                    closed -> Color.WHITE
                    selected -> SELECTED_GREEN
                    else -> Color.rgb(0xEE, 0xEE, 0xF2)
                })
                if (closed && green) setTypeface(typeface, Typeface.BOLD)
                setBackgroundColor(if (closed) Color.TRANSPARENT
                    else if (selected) Color.rgb(0x26, 0x3A, 0x2A) else Color.rgb(0x22, 0x22, 0x2A))
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(position, true)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = row(position, false)
        }
    }

    private fun labeled(label: String, v: View): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))
        row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        return row
    }

    // ---------------- GPS ----------------

    private fun useMyLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        // موقعیت قدیمی (بیش از ۲ ساعت) را قبول نکن؛ تازه بگیر
        if (best != null && System.currentTimeMillis() - best.time < 2 * 3_600_000L) {
            saveLocation(best)
            return
        }
        Toast.makeText(this, "در حال یافتن موقعیت…", Toast.LENGTH_SHORT).show()
        val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER
        else LocationManager.GPS_PROVIDER
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    saveLocation(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, mainLooper)
        } catch (_: Exception) {
            if (best != null) saveLocation(best)
            else Toast.makeText(this, "موقعیت در دسترس نیست؛ موقعیت‌یاب گوشی را روشن کنید", Toast.LENGTH_LONG).show()
        }
    }

    /** نزدیک‌ترین شهر کشور به موقعیت گوشی را انتخاب می‌کند. */
    private fun saveLocation(l: Location) {
        // پس از تعیین شهر با GPS، هر ۱۰ دقیقه خودکار به‌روز شود
        prefs.gpsAuto = true
        prefs.gpsLastTry = System.currentTimeMillis()
        gpsSwitch.isChecked = true
        val c = Cities.nearest(this, l.latitude, l.longitude)
        val km = Cities.distKm(l.latitude, l.longitude, c.lat, c.lon)
        prefs.setCity(c)
        showCity(c)
        FajrBeep.scheduleAll(this)
        Toast.makeText(this, "شهر: ${c.name} (استان ${c.province})" +
            (if (km > 60) "\nنزدیک‌ترین شهر در فهرست؛ " + Persian.digits(km.toInt().toString()) + " کیلومتر فاصله" else ""),
            Toast.LENGTH_LONG).show()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) useMyLocation()
        else Toast.makeText(this, "بدون اجازهٔ موقعیت، استان و شهر را از فهرست انتخاب کنید", Toast.LENGTH_LONG).show()
    }

    // ---------------- فایل صوتی و اعلان ----------------

    private fun pickAudio(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        try {
            startActivityForResult(i, req)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "برنامهٔ انتخاب فایل پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }

    /** کلیدهای آلبوم را با تنظیم ذخیره‌شده هماهنگ می‌کند (پس از افزودن عکس یا موزیک). */
    private fun recreateAlbumSwitchState() {
        if (albumSwitch.isChecked != prefs.album) albumSwitch.isChecked = prefs.album
        if (albumMusicSwitch.isChecked != prefs.albumMusic) albumMusicSwitch.isChecked = prefs.albumMusic
    }

    private fun pickImages() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(Intent.createChooser(i, "انتخاب عکس‌ها"), REQ_ALBUM_IMAGES)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "برنامهٔ گالری پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_ALBUM_IMAGES) {
            val uris = ArrayList<Uri>()
            data?.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) } }
            if (uris.isEmpty()) data?.data?.let { uris.add(it) }
            if (uris.isEmpty()) return
            Toast.makeText(this, "در حال افزودن " + Persian.digits(uris.size.toString()) + " عکس…", Toast.LENGTH_SHORT).show()
            AlbumStore.import(this, uris) { n ->
                if (!prefs.album && n > 0) prefs.album = true
                Toast.makeText(this, Persian.digits(n.toString()) + " عکس به آلبوم اضافه شد", Toast.LENGTH_LONG).show()
                recreateAlbumSwitchState()
                refresh()
            }
            return
        }
        val uri = data?.data ?: return
        if (requestCode == REQ_BACKUP_SAVE) {
            Toast.makeText(this, if (Backup.export(this, uri)) "تنظیمات در فایل ذخیره شد ✓" else "ذخیرهٔ فایل ناموفق بود",
                Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode == REQ_BACKUP_LOAD) {
            val n = Backup.import(this, uri)
            if (n < 0) {
                Toast.makeText(this, "این فایل پشتیبان تنظیمات این برنامه نیست", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "تنظیمات بازیابی شد ✓", Toast.LENGTH_LONG).show()
                FajrBeep.scheduleAll(this)
                recreate()
            }
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        val name = displayName(uri)
        when (requestCode) {
            REQ_ROOSTER_FILE -> { prefs.roosterUri = uri.toString(); prefs.roosterUriName = name }
            REQ_ADHAN_FILE -> { prefs.adhanUri = uri.toString(); prefs.adhanUriName = name }
            REQ_ALBUM_MUSIC -> { prefs.albumMusicUri = uri.toString(); prefs.albumMusicName = name; prefs.albumMusic = true; recreateAlbumSwitchState() }
        }
        refresh()
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "فایل صوتی"
    } catch (_: Exception) { "فایل صوتی" }

    /** اندروید ۱۳ به بعد: اجازهٔ اعلان (برای دکمهٔ «قطع» هنگام پخش اذان و زنگ). */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    // ---------------- سازندهٔ اجزا ----------------

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(0xFF, 0xA5, 0x48))
            setPadding(0, dp(22), 0, dp(6))
        })
    }

    /**
     * توضیح خاکستری. اگر بیشتر از یک سطر باشد، فقط سطر اول (با «…») نمایش داده می‌شود و با ضربه باز و بسته می‌شود.
     */
    private fun hint(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(Color.rgb(0xA0, 0xA0, 0xA8))
        setPadding(0, dp(4), 0, dp(4))
        textDirection = View.TEXT_DIRECTION_RTL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setOnClickListener {
            val open = maxLines == 1
            maxLines = if (open) Int.MAX_VALUE else 1
            ellipsize = if (open) null else android.text.TextUtils.TruncateAt.END
        }
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) }
    }

    /** چند تیک (CheckBox) در یک سطر. */
    private fun checkRow(vararg items: Triple<String, Boolean, (Boolean) -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        for ((label, value, onChange) in items) {
            row.addView(android.widget.CheckBox(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                isChecked = value
                setOnCheckedChangeListener { _, b -> onChange(b) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    /** فقط فهرست کشویی (بدون برچسب) با رنگ‌های تیره. */
    private fun spinnerOnly(items: Array<String>, selected: Int, onPick: (Int) -> Unit): Spinner {
        val sp = Spinner(this)
        sp.adapter = darkAdapter(sp, items.toList(), false)
        sp.setSelection(selected.coerceIn(0, items.size - 1), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var cur = selected
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == cur) return
                cur = position
                onPick(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return sp
    }

    private fun createDoc(req: Int, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, name)
        }
        try { startActivityForResult(i, req) } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "برنامهٔ مدیریت فایل پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }

    private fun openDoc(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        try { startActivityForResult(i, req) } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "برنامهٔ مدیریت فایل پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }

    private fun rowButtons(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in items) {
            row.addView(Button(this).apply {
                text = label
                textSize = if (items.size > 2) 13f else 16f
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(6) })
        }
        return row
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        isChecked = value
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    private fun spinner(label: String, items: Array<String>, selected: Int, green: Boolean = false,
                        onPick: (Int) -> Unit): LinearLayout {
        val sp = Spinner(this)
        sp.adapter = darkAdapter(sp, items.toList(), green)
        sp.setSelection(selected.coerceIn(0, items.size - 1), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var cur = selected
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == cur) return   // انتخاب اولیه هنگام ساخت صفحه
                cur = position
                onPick(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return labeled(label, sp)
    }

    private fun seek(label: String, minV: Int, maxV: Int, value: Int, unit: String,
                     format: ((Int) -> String)? = null, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val tv = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        fun show(v: Int) { tv.text = if (format != null) "$label: ${format(v)}" else "$label: ${Persian.digits(v.toString())} $unit" }
        show(value)
        val sb = SeekBar(this).apply {
            max = maxV - minV
            progress = (value - minV).coerceIn(0, maxV - minV)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    show(p + minV)
                    if (fromUser) onChange(p + minV)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv)
        box.addView(sb)
        return box
    }

    companion object {
        private const val REQ_LOC = 7
        private const val REQ_NOTIF = 8
        private const val REQ_ROOSTER_FILE = 21
        private const val REQ_ADHAN_FILE = 22
        private const val REQ_ALBUM_IMAGES = 23
        private const val REQ_ALBUM_MUSIC = 24
        private const val REQ_BACKUP_SAVE = 25
        private const val REQ_BACKUP_LOAD = 26
        private const val IDLE_MS = 2 * 60_000L
        /** سبز فسفری کم‌رنگ برای استان و شهر انتخاب‌شده. */
        private val SELECTED_GREEN = Color.rgb(0x9C, 0xF0, 0x8A)
    }
}
