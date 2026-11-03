package com.denanex.aod

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** راهنمای کوتاه خواندن نماز شب یا نماز آیات (بدون اینترنت)؛ نوع با [EXTRA_KIND]. */
class GuideActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ayat = intent?.getStringExtra(EXTRA_KIND) == KIND_AYAT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(TextView(this).apply {
            text = if (ayat) "🌘 راهنمای نماز آیات" else "🌙 راهنمای نماز شب"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(0xFF, 0xD5, 0x80))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })
        root.addView(TextView(this).apply {
            text = if (ayat) AYAT else TEXT
            textSize = 18f
            setLineSpacing(0f, 1.35f)
            setTextColor(Color.rgb(0xEE, 0xEE, 0xF2))
            textDirection = View.TEXT_DIRECTION_RTL
        })
        root.addView(Button(this).apply {
            text = "توضیحات بیشتر در اینترنت"
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(
                            if (ayat) "طریقه خواندن نماز آیات" else "طریقه خواندن نماز شب"))))
                } catch (_: Exception) {}
            }
        })
        root.addView(Button(this).apply {
            text = "بازگشت"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = Prefs.CREDIT + "\n" + Prefs.DEDICATION
            textSize = 15f
            setTextColor(Color.rgb(0xA0, 0xA0, 0xA8))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0x05, 0x0A, 0x1E))
            addView(root)
        })
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val KIND_AYAT = "ayat"

        val AYAT = """
نماز آیات دو رکعت است و هر رکعت آن پنج رکوع دارد.

⏰ چه وقت واجب می‌شود:
• گرفتن خورشید (خورشیدگرفتگی) یا ماه (ماه‌گرفتگی)، حتی اگر کم باشد.
• زلزله، حتی اگر کسی نترسد.
• رعد و برق، بادهای سیاه و سرخ و مانند این‌ها، اگر بیشتر مردم بترسند.
وقت نماز خورشیدگرفتگی و ماه‌گرفتگی از شروع گرفتن است و بهتر است پیش از آن‌که شروع به باز شدن کند خوانده شود. در زلزله و مانند آن باید فوراً خوانده شود.

نیت: «دو رکعت نماز آیات می‌خوانم قربةً الی الله».

۱) روش اول (کامل)
در هر رکعت پنج بار این کار تکرار می‌شود:
سورهٔ حمد و یک سورهٔ کامل ← رکوع ← سر برداشتن.
پس از رکوع پنجم و سر برداشتن، دو سجده.
رکعت دوم هم همین‌طور؛ پس از دو سجده، تشهد و سلام.

۲) روش دوم (کوتاه‌تر)
پس از نیت و تکبیر، سورهٔ حمد را بخوانید و یک سوره (مثلاً توحید) را پنج قسمت کنید:
• حمد + قسمت اول سوره (مثلاً «بِسْمِ اللهِ الرَّحْمٰنِ الرَّحیمِ») ← رکوع
• قسمت دوم («قُلْ هُوَ اللهُ اَحَدٌ») ← رکوع
• قسمت سوم («اَللهُ الصَّمَدُ») ← رکوع
• قسمت چهارم («لَمْ یَلِدْ وَ لَمْ یولَدْ») ← رکوع
• قسمت پنجم («وَ لَمْ یَکُنْ لَهُ کُفُواً اَحَدٌ») ← رکوع پنجم ← دو سجده
در این روش سورهٔ حمد فقط یک بار، اول رکعت، خوانده می‌شود.
رکعت دوم را هم همین‌طور بخوانید؛ پس از دو سجده، تشهد و سلام.

📌 نکته‌ها
• مستحب است پیش از رکوع‌های دوم، چهارم، ششم، هشتم و دهم قنوت خوانده شود؛ یک قنوت پیش از رکوع دهم هم کافی است.
• می‌توان یک رکعت را به روش اول و رکعت دیگر را به روش دوم خواند.
• هر رکوع پیش از رفتن و پس از برخاستن، تکبیر مستحب دارد (جز پس از رکوع پنجم و دهم که «سَمِعَ اللهُ لِمَنْ حَمِدَه» گفته می‌شود).
• برای جزئیات (مثل قضای نماز آیات) به رسالهٔ مرجع تقلید خود مراجعه کنید.
""".trim()

        val TEXT = """
نماز شب ۱۱ رکعت است و مانند نماز صبح، دو رکعت دو رکعت خوانده می‌شود.

⏰ وقت: از نیمه‌شب شرعی تا اذان صبح؛ بهترین وقت آن، نزدیک اذان صبح (سحر) است.

۱) نافلهٔ شب — ۸ رکعت
چهار نماز دو رکعتی. نیت هر کدام: «دو رکعت نافلهٔ شب می‌خوانم قربةً الی الله».
در هر رکعت سورهٔ حمد (و اگر خواستید یک سوره)، و در رکعت دوم قنوت.

۲) نماز شَفع — ۲ رکعت
نیت: «دو رکعت نماز شفع می‌خوانم قربةً الی الله». مانند نماز صبح خوانده می‌شود (قنوت آن را به نیت رجاء می‌توان خواند).

۳) نماز وَتر — ۱ رکعت
نیت: «یک رکعت نماز وتر می‌خوانم قربةً الی الله».
حمد (و در صورت تمایل سورهٔ توحید سه بار، فلق و ناس)، سپس دست‌ها به قنوت. در قنوت وتر مستحب است:
• ۷۰ بار: «اَسْتَغْفِرُ اللهَ رَبّی وَ اَتوبُ اِلَیه»
• ۷ بار: «هذا مَقامُ العائِذِ بِکَ مِنَ النّار»
• ۳۰۰ بار: «اَلْعَفْو»
• دعا و طلب آمرزش برای چهل مؤمن: «اَللّهُمَّ اغْفِرْ لِ...» (نام هر نفر)
سپس رکوع، دو سجده، تشهد و سلام.

📌 نکته‌ها
• در همهٔ رکعت‌ها خواندن سورهٔ حمد به‌تنهایی کافی است.
• نماز شب را نشسته هم می‌توان خواند.
• اگر وقت کم است، می‌توان فقط شفع و وتر (۳ رکعت)، و اگر کمتر است فقط وتر را خواند.
• قنوت‌ها و ذکرها مستحب‌اند؛ کوتاه‌تر هم خوانده شوند نماز درست است.
• برای جزئیات به رسالهٔ مرجع تقلید خود مراجعه کنید.
""".trim()
    }
}
