package com.denanex.aod

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Calendar

/**
 * صدای خروس (res/raw/rooster.mp3) چند دقیقه پیش از طلوع آفتاب (انتخابی، از تنظیمات).
 * مثل بیپ اذان با AlarmManager زمان‌بندی می‌شود تا وقتی اپ بسته است هم پخش شود.
 * صدا از کانال «زنگ هشدار» پخش می‌شود (بلندی آن = صدای زنگ هشدار گوشی).
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

    /** پخش صدای خروس؛ [done] پس از پایان صدا (یا خطا). */
    fun play(ctx: Context, done: (() -> Unit)? = null) {
        val h = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(mp: MediaPlayer?) {
            if (finished) return
            finished = true
            try { mp?.release() } catch (_: Exception) {}
            done?.invoke()
        }
        val mp = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                ctx.resources.openRawResourceFd(R.raw.rooster).use {
                    setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                prepare()
            }
        } catch (_: Exception) { null }
        if (mp == null) { finish(null); return }
        mp.setOnCompletionListener { finish(it) }
        mp.setOnErrorListener { p, _, _ -> finish(p); true }
        mp.start()
        // برای اطمینان، اگر «پایان پخش» نرسید، حداکثر پس از ۸ ثانیه رها کن (goAsync حدود ۱۰ ثانیه وقت دارد)
        h.postDelayed({ finish(mp) }, 8_000L)
    }
}

/** رسیدن زمان صدای خروس. */
class RoosterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        // اگر گوشی خواب بوده و هشدار خیلی دیر رسیده، بی‌موقع پخش نکن
        val now = System.currentTimeMillis()
        val onTime = Rooster.times(app, now - 3_600_000L).any { t -> now >= t - 60_000L && now <= t + 120_000L }
        if (!Prefs(app).rooster || !onTime) {
            Rooster.schedule(app)
            pending.finish()
            return
        }
        Rooster.play(app) {
            Rooster.schedule(app)
            pending.finish()
        }
    }
}
