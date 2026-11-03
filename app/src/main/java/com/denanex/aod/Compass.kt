package com.denanex.aod

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * قطب‌نما با حسگر گوشی: سمت بالای صفحه نسبت به شمال جغرافیایی (درجه، ساعتگرد).
 * از حسگر «بردار چرخش» استفاده می‌کند (اگر نبود: شتاب‌سنج + مغناطیس‌سنج)، انحراف مغناطیسی محل را
 * اصلاح می‌کند و چرخش افقی/عمودی صفحه را در نظر می‌گیرد. فقط وقتی [start] شده کار می‌کند (مصرف کم).
 */
class Compass(private val ctx: Context, private val onChange: () -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val rotation = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnet = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val available: Boolean get() = rotation != null || (accel != null && magnet != null)

    /** سمت بالای صفحه از شمال جغرافیایی (درجه)، یا null اگر هنوز معلوم نیست. */
    var heading: Float? = null
        private set

    private var running = false
    private var declination = 0f
    private var sx = 0f
    private var sy = 0f
    private var lastNotified = Float.NaN
    private var lastNotifyAt = 0L
    private val rm = FloatArray(9)
    private val orient = FloatArray(3)
    private var gravity: FloatArray? = null
    private var geomag: FloatArray? = null

    fun start(lat: Double, lon: Double) {
        declination = try {
            GeomagneticField(lat.toFloat(), lon.toFloat(), 1000f, System.currentTimeMillis()).declination
        } catch (_: Exception) { 0f }
        if (running || sm == null) return
        running = true
        if (rotation != null) {
            sm.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
        } else if (accel != null && magnet != null) {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(this, magnet, SensorManager.SENSOR_DELAY_UI)
        } else running = false
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(this)
        heading = null
        sx = 0f; sy = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> SensorManager.getRotationMatrixFromVector(rm, e.values)
            Sensor.TYPE_ACCELEROMETER -> { gravity = e.values.clone(); if (!fromAccMag()) return }
            Sensor.TYPE_MAGNETIC_FIELD -> { geomag = e.values.clone(); if (!fromAccMag()) return }
            else -> return
        }
        SensorManager.getOrientation(rm, orient)
        var deg = Math.toDegrees(orient[0].toDouble()).toFloat() + declination + displayRotation()
        deg = ((deg % 360f) + 360f) % 360f
        // صاف کردن (میانگین روی دایره) تا عقربه نلرزد
        val a = Math.toRadians(deg.toDouble())
        if (sx == 0f && sy == 0f) { sx = cos(a).toFloat(); sy = sin(a).toFloat() }
        sx += (cos(a).toFloat() - sx) * 0.15f
        sy += (sin(a).toFloat() - sy) * 0.15f
        val h = ((Math.toDegrees(atan2(sy.toDouble(), sx.toDouble())).toFloat() + 360f) % 360f)
        heading = h
        val now = System.currentTimeMillis()
        val diff = if (lastNotified.isNaN()) 999f else kotlin.math.abs(((h - lastNotified + 540f) % 360f) - 180f)
        if (diff > 1f && now - lastNotifyAt > 60) {
            lastNotified = h
            lastNotifyAt = now
            onChange()
        }
    }

    private fun fromAccMag(): Boolean {
        val g = gravity ?: return false
        val m = geomag ?: return false
        return SensorManager.getRotationMatrix(rm, null, g, m)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Float = try {
        when ((ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) { 0f }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
