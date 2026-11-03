package com.denanex.aod

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * زنگ پیش از طلوع آفتاب (انتخابی، از تنظیمات): صدای خروس (res/raw/rooster.mp3) یا فایل صوتی
 * انتخابی کاربر، با یا بدون لرزش. مثل بیپ اذان با AlarmManager زمان‌بندی می‌شود تا وقتی اپ بسته
 * است هم پخش شود؛ خود پخش با [SoundService] است.
 */
object Rooster {
    private const val ACTION = "com.denanex.aod.ROOSTER"
    private const val REQ = 2

    /** زمان پخش برای امروز، فردا و پس‌فردا (طلوع منهای دقیقه‌های انتخابی). */
    fun times(ctx: Context, now: Long = System.currentTimeMillis()): LongArray {
        val p = Prefs(ctx)
        val loc = p.location()
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val out = LongArray(3)
        for (i in 0 until 3) {
            val sr = SunCalc.sunrise(c.timeInMillis, loc.lat, loc.lon) ?: (c.timeInMillis - 6 * 3_600_000L)
            out[i] = sr - p.roosterMinutes * 60_000L
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(ctx, REQ,
        Intent(ctx, RoosterReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** پخش بعدی را زمان‌بندی (یا اگر خاموش است، لغو) می‌کند. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intent(ctx)
        am.cancel(pi)
        if (!Prefs(ctx).rooster) return
        val now = System.currentTimeMillis()
        val next = times(ctx, now).filter { it > now + 1000L }.minOrNull() ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    /** پخش آزمایشی (یا واقعی) صدای انتخاب‌شده. */
    fun play(ctx: Context) = SoundService.start(ctx, SoundService.KIND_ROOSTER, "زنگ پیش از طلوع آفتاب")
}

/** رسیدن زمان زنگ پیش از طلوع. */
class RoosterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // اگر گوشی خواب بوده و هشدار خیلی دیر رسیده، بی‌موقع پخش نکن
        val now = System.currentTimeMillis()
        val onTime = Rooster.times(app, now - 3_600_000L).any { t -> now >= t - 60_000L && now <= t + 120_000L }
        if (Prefs(app).rooster && onTime) Rooster.play(app)
        Rooster.schedule(app)
    }
}
