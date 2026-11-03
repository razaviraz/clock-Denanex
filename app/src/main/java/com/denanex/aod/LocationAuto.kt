package com.denanex.aod

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * تعیین خودکار شهر با GPS: اگر روشن باشد، هر ۱۰ دقیقه یک بار (فقط وقتی ساعت یا تنظیمات باز است)
 * یک موقعیت گرفته می‌شود و بلافاصله گوش دادن به GPS خاموش می‌شود تا باتری مصرف نشود.
 * اگر نزدیک‌ترین شهر عوض شده بود، شهر انتخابی و زمان‌بندی‌ها به‌روز می‌شوند.
 */
object LocationAuto {
    private const val EVERY_MS = 10 * 60_000L
    private const val TIMEOUT_MS = 60_000L
    @Volatile private var busy = false

    /** اگر وقتش رسیده، یک بار موقعیت می‌گیرد. [changed] پس از عوض شدن شهر صدا زده می‌شود. */
    fun maybeUpdate(ctx: Context, changed: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        if (!p.gpsAuto || busy) return
        val now = System.currentTimeMillis()
        if (now - p.gpsLastTry < EVERY_MS) return
        if (app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        p.gpsLastTry = now
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager? ?: return
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        busy = true
        val h = Handler(Looper.getMainLooper())
        var listener: LocationListener? = null
        val giveUp = Runnable {
            listener?.let { try { lm.removeUpdates(it) } catch (_: Exception) {} }
            busy = false
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try { lm.removeUpdates(this) } catch (_: Exception) {}
                h.removeCallbacks(giveUp)
                busy = false
                apply(app, location, changed)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            h.postDelayed(giveUp, TIMEOUT_MS)
        } catch (_: Exception) {
            busy = false
        }
    }

    private fun apply(app: Context, l: Location, changed: (() -> Unit)?) {
        val p = Prefs(app)
        val c = Cities.nearest(app, l.latitude, l.longitude)
        val cur = p.location()
        if (c.name == cur.name && c.province == cur.province) return
        p.setCity(c)
        FajrBeep.scheduleAll(app)
        changed?.invoke()
    }
}
