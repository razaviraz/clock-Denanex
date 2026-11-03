package com.denanex.aod

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * پنجرهٔ بازشو هنگام پخش اذان: «اذان صبح به افق تهران» با دو دکمه:
 * «قطع» (صدا قطع و پنجره بسته می‌شود) و «ادامهٔ اذان» (پنجره بسته و اذان ادامه می‌یابد).
 * روی صفحهٔ قفل هم می‌آید؛ با پایان اذان خودش بسته می‌شود.
 */
class AdhanActivity : Activity() {

    private val finished = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { finish() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setFinishOnTouchOutside(false)
        show(intent)
        val f = IntentFilter(SoundService.ACTION_FINISHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(finished, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(finished, f)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(i: Intent?) {
        val name = i?.getStringExtra(EXTRA_NAME) ?: "اذان"
        val city = i?.getStringExtra(EXTRA_CITY) ?: Prefs(this).location().name
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(0x0C, 0x14, 0x2C))
                setStroke(dp(2), Color.rgb(0xE8, 0xC2, 0x5A))
            }
        }
        box.addView(TextView(this).apply {
            text = "🕌"
            textSize = 40f
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = "$name\nبه افق $city"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(0xFF, 0xE2, 0xA0))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "■  قطع"
            textSize = 18f
            isAllCaps = false
            setOnClickListener { SoundService.stop(this@AdhanActivity); finish() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(Button(this).apply {
            text = "▶  ادامهٔ اذان"
            textSize = 18f
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val root = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
    }

    override fun onDestroy() {
        try { unregisterReceiver(finished) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_CITY = "city"

        fun intent(ctx: Context, name: String, city: String): Intent =
            Intent(ctx, AdhanActivity::class.java)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_CITY, city)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
