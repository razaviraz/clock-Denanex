package com.denanex.aod

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/** صفحهٔ تنظیمات (همهٔ اجزا با کد ساخته می‌شوند؛ بدون فایل طرح‌بندی). */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var preview: ClockView
    private lateinit var sunInfo: TextView
    private lateinit var citySpinner: Spinner
    private lateinit var hijriInfo: TextView
    private lateinit var roosterInfo: TextView

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
        preview = ClockView(this).apply { keepScreenOn = false }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply {
            topMargin = dp(12); bottomMargin = dp(12)
        })

        root.addView(bigButton("▶  نمایش ساعت") {
            startActivity(Intent(this, AodActivity::class.java))
        })
        root.addView(bigButton("🌅  پیش‌نمایش سفیدی صبح و طلوع (حدود ۱ دقیقه)") {
            startActivity(Intent(this, AodActivity::class.java).putExtra(AodActivity.EXTRA_DEMO, true))
        })
        root.addView(hint("در صفحهٔ ساعت: نگه داشتن = تنظیمات • دو ضربه = خروج • " +
            "ضربه روی «راهنمای نماز شب» = باز شدن راهنما • شکل ساعت فقط از همین‌جا عوض می‌شود"))

        section(root, "ظاهر ساعت")
        root.addView(spinner("شکل ساعت", Prefs.styleNames, prefs.style) { prefs.style = it; refresh() })
        root.addView(spinner("رنگ", Prefs.colorNames, prefs.color) { prefs.color = it; refresh() })
        root.addView(switch("۲۴ ساعته", prefs.h24) { prefs.h24 = it; refresh() })
        root.addView(switch("نمایش ثانیه", prefs.seconds) { prefs.seconds = it; refresh() })
        root.addView(switch("رقم‌های فارسی", prefs.persianDigits) { prefs.persianDigits = it; refresh() })
        root.addView(switch("تاریخ خورشیدی", prefs.showDate) { prefs.showDate = it; refresh() })
        root.addView(switch("باتری", prefs.showBattery) { prefs.showBattery = it; refresh() })
        root.addView(switch("اذان صبح و طلوع آفتاب (با زمان مانده)", prefs.showSunrise) { prefs.showSunrise = it; refresh() })

        section(root, "سفیدی صبح و طلوع آفتاب")
        root.addView(switch("نمایش سفیدی صبح و طلوع خورشید", prefs.sunrise) { prefs.sunrise = it; refresh() })
        root.addView(hint("از چند دقیقه پیش از طلوع (پیش‌فرض ۱۰ دقیقه) سفیدی ملایم صبح در افق پیدا می‌شود؛ " +
            "سر وقت طلوع، خورشید از پشت افق درمی‌آید و تا ۲۰ دقیقه پس از طلوع آرام بالا می‌رود."))
        root.addView(switch("هلال ماه بالای ساعت", prefs.moon) { prefs.moon = it; refresh() })
        val cityNames = Cities.list.map { it.name } + "📍 موقعیت من (GPS)"
        val cityIndex = if (prefs.city == Prefs.CITY_MY_LOCATION) Cities.list.size else prefs.city
        val cityRow = spinner("افق شرعی شهر", cityNames.toTypedArray(), cityIndex) {
            if (it == Cities.list.size) useMyLocation() else { prefs.city = it; refresh(); FajrBeep.scheduleAll(this) }
        }
        citySpinner = cityRow.getChildAt(1) as Spinner
        root.addView(cityRow)
        sunInfo = hint("")
        root.addView(sunInfo)
        root.addView(seek("شروع سفیدی صبح، چند دقیقه پیش از طلوع", 3, 40, prefs.dawnMinutes, "دقیقه") {
            prefs.dawnMinutes = it; refresh()
        })

        section(root, "🐓 صدای خروس پیش از طلوع آفتاب")
        root.addView(switch("پخش صدای خروس (قوقولی‌قوقو)", prefs.rooster) {
            prefs.rooster = it; Rooster.schedule(this); refresh()
        })
        root.addView(seek("چند دقیقه پیش از طلوع آفتاب", 0, 60, prefs.roosterMinutes, "دقیقه",
            { v -> if (v == 0) "سر وقت طلوع" else Persian.digits("$v") + " دقیقه" }) {
            prefs.roosterMinutes = it; Rooster.schedule(this); refresh()
        })
        roosterInfo = hint("")
        root.addView(roosterInfo)
        root.addView(bigButton("🐓  آزمایش صدای خروس") { Rooster.play(this) })

        section(root, "🌙 نماز شب و سحر")
        root.addView(bigButton("🌙  راهنمای نماز شب") {
            startActivity(Intent(this, GuideActivity::class.java))
        })
        root.addView(hint("راهنما از صفحهٔ ساعت هم باز می‌شود: روی خط طلایی «راهنمای نماز شب» پایین صفحه ضربه بزنید."))
        root.addView(switch("آسمان نیمه‌شب (سرمه‌ای با ستاره) از ۲۰ دقیقه پیش از اذان صبح", prefs.nightSky) {
            prefs.nightSky = it; refresh()
        })
        root.addView(switch("بیپ هر ۵ دقیقه از ۲۰ دقیقه مانده به اذان صبح", prefs.fajrBeep) {
            prefs.fajrBeep = it; FajrBeep.schedule(this)
        })
        root.addView(hint("۲۰، ۱۵، ۱۰ و ۵ دقیقه مانده: دو بیپ کوتاه — سر اذان: سه بیپ بلند. " +
            "حتی وقتی اپ بسته است هم بیپ می‌زند. بلندی صدا = صدای «زنگ هشدار» گوشی."))
        root.addView(bigButton("🔔  آزمایش بیپ") { FajrBeep.play(5) })

        section(root, "تاریخ قمری و تعطیلات")
        hijriInfo = hint("")
        root.addView(hijriInfo)
        root.addView(seek("تصحیح تاریخ قمری", 0, 4, prefs.hijriOffset + 2, "", { v -> Persian.digits(
            when { v - 2 > 0 -> "+${v - 2} روز"; v - 2 < 0 -> "${v - 2} روز"; else -> "بدون تصحیح" }) }) {
            prefs.hijriOffset = it - 2; refresh()
        })

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

    private fun refresh() {
        preview.reload()
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
        val gy = now2.get(Calendar.YEAR); val gm = now2.get(Calendar.MONTH) + 1; val gd = now2.get(Calendar.DAY_OF_MONTH)
        val hj = Hijri.fromGregorian(gy, gm, gd, prefs.hijriOffset)
        val jj = Persian.toJalali(gy, gm, gd)
        val hol = Hijri.holiday(jj[1], jj[2], hj)
        hijriInfo.text = "امروز به قمری: " + Persian.digits("${hj[2]} ") + Hijri.months[hj[1] - 1] +
            Persian.digits(" ${hj[0]}") + (if (hol != null) " — تعطیل: $hol" else "") +
            "\nاگر با تقویم رسمی یک روز فرق داشت، با نوار زیر درستش کنید (تعطیلات مذهبی از روی همین حساب می‌شود)."
        if (sr == null) { sunInfo.text = ""; roosterInfo.text = ""; return }
        val s = Calendar.getInstance().apply { timeInMillis = sr }
        val d = Calendar.getInstance().apply { timeInMillis = sr - prefs.dawnMinutes * 60_000L }
        fun hm(c: Calendar) = Persian.digits(String.format(java.util.Locale.US, "%02d:%02d",
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
        val fj = SunCalc.fajr(cal.timeInMillis, loc.lat, loc.lon)
        val fjs = if (fj == null) "" else "اذان صبح: ${hm(Calendar.getInstance().apply { timeInMillis = fj })} — "
        sunInfo.text = "$day به افق ${loc.name}: ${fjs}طلوع آفتاب: ${hm(s)} — شروع سفیدی صبح: ${hm(d)}"
        val nextR = Rooster.times(this, now).filter { it > now }.minOrNull()
        val rs = Calendar.getInstance().apply { timeInMillis = nextR ?: now }
        val rDay = if (rs.get(Calendar.DAY_OF_YEAR) == now2.get(Calendar.DAY_OF_YEAR)) "امروز" else "فردا"
        roosterInfo.text = (if (prefs.rooster && nextR != null) "$rDay ساعت ${hm(rs)} پخش می‌شود. " else "خاموش است. ") +
            "حتی وقتی اپ بسته است هم پخش می‌شود. بلندی صدا = صدای «زنگ هشدار» گوشی."
    }

    // ---------------- موقعیت من ----------------

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
        if (best != null) {
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
            Toast.makeText(this, "موقعیت در دسترس نیست؛ موقعیت‌یاب گوشی را روشن کنید", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveLocation(l: Location) {
        prefs.myLat = l.latitude
        prefs.myLon = l.longitude
        prefs.city = Prefs.CITY_MY_LOCATION
        FajrBeep.scheduleAll(this)
        Toast.makeText(this, "موقعیت ذخیره شد", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) useMyLocation()
        else {
            Toast.makeText(this, "بدون اجازهٔ موقعیت، یک شهر را از فهرست انتخاب کنید", Toast.LENGTH_LONG).show()
            citySpinner.setSelection(if (prefs.city == Prefs.CITY_MY_LOCATION) Cities.list.size else prefs.city)
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

    private fun hint(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(Color.rgb(0xA0, 0xA0, 0xA8))
        setPadding(0, dp(4), 0, dp(4))
        textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) }
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        isChecked = value
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    private fun spinner(label: String, items: Array<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
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
        row.addView(sp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        return row
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
        private const val IDLE_MS = 2 * 60_000L
    }
}
