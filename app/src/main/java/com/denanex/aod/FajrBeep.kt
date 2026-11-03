package com.denanex.aod

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Calendar

/**
 * بیپ پیش از اذان صبح: ۲۰، ۱۵، ۱۰ و ۵ دقیقه مانده (دو بیپ کوتاه) و سر اذان (بیپ بلند).
 * با AlarmManager زمان‌بندی می‌شود تا حتی وقتی صفحهٔ ساعت باز نیست هم به صدا درآید.
 * صدا از کانال «زنگ هشدار» پخش می‌شود (بلندی آن = صدای زنگ هشدار گوشی).
 */
object FajrBeep {
    private const val ACTION = "com.denanex.aod.FAJR_BEEP"
    private const val EXTRA_MIN = "min_before"
    val beepMinutes = intArrayOf(20, 15, 10, 5, 0)

    /** زمان اذان صبح امروز و فردا برای مکان انتخابی. */
    fun fajrTimes(ctx: Context, now: Long = System.currentTimeMillis()): LongArray {
        val loc = Prefs(ctx).location()
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val out = LongArray(3)
        for (i in 0 until 3) {
            out[i] = SunCalc.fajr(c.timeInMillis, loc.lat, loc.lon) ?: (c.timeInMillis - 7 * 3_600_000L)
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }

    /** همهٔ هشدارها (بیپ اذان صبح و صدای خروس) را دوباره زمان‌بندی می‌کند. */
    fun scheduleAll(ctx: Context) {
        schedule(ctx)
        Rooster.schedule(ctx)
    }

    /** بیپ بعدی را زمان‌بندی (یا اگر خاموش است، لغو) می‌کند. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi0 = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, BeepReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi0)
        if (!Prefs(ctx).fajrBeep) return
        val now = System.currentTimeMillis()
        var best = Long.MAX_VALUE
        var bestMin = 0
        for (f in fajrTimes(ctx, now)) for (m in beepMinutes) {
            val t = f - m * 60_000L
            if (t > now + 1000L && t < best) { best = t; bestMin = m }
        }
        if (best == Long.MAX_VALUE) return
        val pi = PendingIntent.getBroadcast(ctx, 1,
            Intent(ctx, BeepReceiver::class.java).setAction(ACTION).putExtra(EXTRA_MIN, bestMin),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi)
        }
    }

    /** پخش بیپ؛ [minBefore] = چند دقیقه مانده به اذان (۰ = سر اذان). [done] پس از پایان صدا. */
    fun play(minBefore: Int, done: (() -> Unit)? = null) {
        val h = Handler(Looper.getMainLooper())
        val tg = try { ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (_: Exception) { null }
        if (tg == null) { done?.invoke(); return }
        val total: Long
        if (minBefore == 0) {
            // سر اذان: سه بیپ بلند
            for (i in 0 until 3) h.postDelayed({ tg.startTone(ToneGenerator.TONE_PROP_BEEP, 700) }, i * 1000L)
            total = 3200L
        } else {
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 220)
            h.postDelayed({ tg.startTone(ToneGenerator.TONE_PROP_BEEP, 220) }, 420L)
            total = 1000L
        }
        h.postDelayed({
            try { tg.release() } catch (_: Exception) {}
            done?.invoke()
        }, total)
    }

    fun minutesOf(i: Intent) = i.getIntExtra(EXTRA_MIN, 5)
}

/** رسیدن زمان بیپ. */
class BeepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        val min = FajrBeep.minutesOf(intent)
        // اگر گوشی خواب بوده و هشدار خیلی دیر رسیده، بی‌موقع بوق نزن
        val late = run {
            val now = System.currentTimeMillis()
            FajrBeep.fajrTimes(app, now - 3_600_000L).none { f ->
                val t = f - min * 60_000L
                now >= t - 60_000L && now <= t + 120_000L
            }
        }
        if (!Prefs(app).fajrBeep || late) {
            FajrBeep.schedule(app)
            pending.finish()
            return
        }
        FajrBeep.play(min) {
            FajrBeep.schedule(app)
            pending.finish()
        }
    }
}

/** پس از روشن شدن گوشی، تغییر ساعت/منطقهٔ زمانی یا به‌روزرسانی اپ: زمان‌بندی دوباره. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FajrBeep.scheduleAll(context.applicationContext)
    }
}
