package com.denanex.aod

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * نمایش تمام‌صفحهٔ ساعت (روی صفحهٔ قفل هم). صفحه روشن می‌ماند با نور کم.
 * نگه داشتن: تنظیمات — دو ضربه: خروج — ضربه روی آیکون آب‌وهوا: نام شهر و وضعیت هوا —
 * ضربه روی نوار پایین: راهنمای نماز شب / آیات / والدین. با آلبوم عکس، موزیک پس‌زمینهٔ انتخابی پخش می‌شود.
 */
class AodActivity : Activity() {

    private lateinit var view: ClockView
    private var music: MediaPlayer? = null

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
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        view = ClockView(this)
        view.onSkyLevel = { view.prefs.applyBrightness(window, it) }
        setContentView(view)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // لمس صفحه شکل ساعت را عوض نمی‌کند (شکل فقط از تنظیمات)
                when (val hit = view.hitTest(e.x, e.y)) {
                    null -> {}
                    "weather" -> Weather.showDialog(this@AodActivity)   // وضعیت الان و پیش‌بینی ۷ روزه
                    else -> startActivity(Intent(this@AodActivity, GuideActivity::class.java)
                        .putExtra(GuideActivity.EXTRA_KIND, hit))
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                finish()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                startActivity(Intent(this@AodActivity, SettingsActivity::class.java))
                finish()
            }
        })
        view.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }

        demo(intent)
        // با هر بار باز شدن ساعت هم نسخهٔ تازه بررسی شود (اگر بود، با انتخاب کاربر دانلود و نصب)
        if (savedInstanceState == null) Updater.check(this)
    }

    private fun demo(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_DEMO, false) == true) view.startDemo(sunset = false)
        else if (i?.getBooleanExtra(EXTRA_DEMO_SUNSET, false) == true) view.startDemo(sunset = true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        demo(intent)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        view.reload()
        view.prefs.applyBrightness(window, 0f)
        view.start()
        FajrBeep.scheduleAll(this)
        startMusic()
    }

    /** موزیک پس‌زمینهٔ آلبوم عکس (فایل انتخابی کاربر، تکرار پشت سر هم). */
    private fun startMusic() {
        stopMusic()
        val p = view.prefs
        if (!p.album || !p.albumMusic || p.albumMusicUri.isEmpty() || AlbumStore.count(this) == 0) return
        music = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@AodActivity, Uri.parse(p.albumMusicUri))
                isLooping = true
                setVolume(0.6f, 0.6f)
                prepare()
                start()
            }
        } catch (_: Exception) { null }
    }

    private fun stopMusic() {
        music?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        music = null
    }

    override fun onPause() {
        stopMusic()
        view.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    companion object {
        const val EXTRA_DEMO = "demo"
        const val EXTRA_DEMO_SUNSET = "demo_sunset"
    }
}
