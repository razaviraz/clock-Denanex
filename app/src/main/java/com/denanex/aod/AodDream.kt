package com.denanex.aod

import android.service.dreams.DreamService

/**
 * «محافظ صفحه» اندروید (Screen saver / Daydream): وقتی گوشی در حال شارژ است و صفحه خاموش می‌شود،
 * اندروید به‌جای خاموش کردن، همین ساعت را نشان می‌دهد. فعال‌سازی: تنظیمات اپ ← «تنظیم محافظ صفحه».
 */
class AodDream : DreamService() {

    private var view: ClockView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val v = ClockView(this)
        v.onSkyLevel = { v.prefs.applyBrightness(window, it) }
        view = v
        setContentView(v)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.let {
            it.reload()
            it.prefs.applyBrightness(window, 0f)
            it.start()
        }
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        view?.stop()
        view = null
        super.onDetachedFromWindow()
    }
}
