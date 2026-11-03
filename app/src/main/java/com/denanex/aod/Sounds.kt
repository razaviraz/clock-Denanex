package com.denanex.aod

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Calendar
import java.util.Locale

/**
 * پخش صدا در پس‌زمینه (اذان، صدای خروس یا فایل انتخابی کاربر) با «سرویس پیش‌زمینه»،
 * تا صدای طولانی (مثل اذان کامل) حتی وقتی اپ بسته است تا آخر پخش شود.
 * در اعلان، دکمهٔ «قطع» هست. صدا از کانال «زنگ هشدار» پخش می‌شود.
 */
class SoundService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopAll = Runnable { stopSelf() }
    private var tts: TextToSpeech? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val kind = intent?.getStringExtra(EXTRA_KIND) ?: KIND_ROOSTER
        val prefs = Prefs(this)
        val city = prefs.location().name
        val name = intent?.getStringExtra(EXTRA_TITLE) ?: "ساعت همیشه‌روشن"
        // اذان: «اذان صبح به افق تهران» در اعلان و پنجرهٔ بازشو
        val title = if (kind == KIND_ADHAN) "$name به افق $city" else name
        goForeground(title, if (kind == KIND_ADHAN) AdhanActivity.intent(this, name, city) else null)
        release()
        val vibrate = if (kind == KIND_ADHAN) prefs.adhanVibrate else prefs.roosterVibrate
        val mp = createPlayer(sourcesFor(kind, prefs))
        if (mp == null) { stopSelf(); return START_NOT_STICKY }
        player = mp
        mp.setOnCompletionListener { stopSelf() }
        mp.setOnErrorListener { _, _, _ -> stopSelf(); true }
        handler.removeCallbacks(stopAll)
        handler.postDelayed(stopAll, MAX_MS)
        started = false
        if (kind == KIND_ADHAN) {
            // پنجرهٔ «اذان به افق …» با دکمه‌های قطع و ادامه (اگر اندروید اجازه دهد مستقیم باز می‌شود؛
            // وگرنه از راه اعلان تمام‌صفحه)
            try { startActivity(AdhanActivity.intent(this, name, city)) } catch (_: Exception) {}
        }
        if (kind == KIND_ADHAN && prefs.announceCity) announce("$name به افق $city") { begin(vibrate) }
        else begin(vibrate)
        return START_NOT_STICKY
    }

    private fun begin(vibrate: Boolean) {
        if (started) return
        started = true
        try { player?.start() } catch (_: Exception) { stopSelf(); return }
        if (vibrate) startVibration()
    }

    /**
     * گفتن [text] با موتور گفتار فارسی گوشی، سپس [then]. اگر گفتار فارسی روی گوشی نبود یا ۶ ثانیه
     * طول کشید، بی‌صدا رد می‌شود.
     */
    private fun announce(text: String, then: () -> Unit) {
        val guard = Runnable { then() }
        handler.postDelayed(guard, 6_000L)
        val done = { handler.removeCallbacks(guard); handler.post { then() }; Unit }
        try {
            tts = TextToSpeech(this) { status ->
                val t = tts
                if (status != TextToSpeech.SUCCESS || t == null ||
                    t.setLanguage(Locale("fa", "IR")) < TextToSpeech.LANG_AVAILABLE) { done(); return@TextToSpeech }
                t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { handler.removeCallbacks(guard) }
                    override fun onDone(utteranceId: String?) { done() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { done() }
                })
                if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "city") != TextToSpeech.SUCCESS) done()
            }
        } catch (_: Exception) { done() }
    }

    /** منبع‌های صدا به ترتیب اولویت: فایل انتخابی کاربر، فایل همراه برنامه، زنگ هشدار گوشی. */
    private fun sourcesFor(kind: String, p: Prefs): List<Any> {
        val out = ArrayList<Any>(3)
        if (kind == KIND_ADHAN) {
            if (p.adhanUri.isNotEmpty()) out.add(Uri.parse(p.adhanUri))
            val name = if (p.adhanMode == Prayers.MODE_SUNNI) "adhan_sunni" else "adhan_shia"
            val id = resources.getIdentifier(name, "raw", packageName)
            if (id != 0) out.add(id)
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { out.add(it) }
        } else {
            if (p.roosterUri.isNotEmpty()) out.add(Uri.parse(p.roosterUri))
            out.add(R.raw.rooster)
        }
        return out
    }

    private fun createPlayer(sources: List<Any>): MediaPlayer? {
        for (src in sources) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                when (src) {
                    is Uri -> mp.setDataSource(this, src)
                    is Int -> resources.openRawResourceFd(src).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                }
                mp.prepare()
                return mp
            } catch (_: Exception) {
                try { mp.release() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager?)?.defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?)
        if (v == null || !v.hasVibrator()) return
        vibrator = v
        val pattern = longArrayOf(0, 700, 500)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                    android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createWaveform(pattern, 0), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        } catch (_: Exception) {}
    }

    private fun goForeground(title: String, popup: Intent?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "اذان و زنگ طلوع", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null); enableVibration(false) })
        }
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, SoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("برای قطع صدا «قطع» را بزنید")
            .setOngoing(true)
            .setDeleteIntent(stopPi)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(Notification.Action.Builder(null, "قطع", stopPi).build())
        if (popup != null) {
            val pi = PendingIntent.getActivity(this, 1, popup,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.setContentIntent(pi).setFullScreenIntent(pi, true)
        }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
    }

    private fun release() {
        player?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        release()
        // پنجرهٔ اذان (اگر باز است) بسته شود
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
        super.onDestroy()
    }

    companion object {
        const val KIND_ADHAN = "adhan"
        const val KIND_ROOSTER = "rooster"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TITLE = "title"
        private const val ACTION_STOP = "com.denanex.aod.STOP_SOUND"
        const val ACTION_FINISHED = "com.denanex.aod.SOUND_FINISHED"
        private const val CHANNEL = "sounds2"   // اهمیت «زیاد» تا پنجرهٔ اذان روی صفحه بیاید
        private const val NOTIF_ID = 41
        /** حداکثر زمان پخش (برای فایل‌های خیلی طولانی). */
        private const val MAX_MS = 10 * 60_000L

        fun start(ctx: Context, kind: String, title: String) {
            val i = Intent(ctx, SoundService::class.java).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_TITLE, title)
            try { ctx.startForegroundService(i) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            try { ctx.startService(Intent(ctx, SoundService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {}
        }
    }
}

/** زمان‌بندی اذان (۳ وقت شیعه یا ۵ وقت اهل سنت، به انتخاب کاربر) با AlarmManager. */
object Adhan {
    private const val ACTION = "com.denanex.aod.ADHAN"
    private const val EXTRA_NAME = "name"
    private const val EXTRA_AT = "at"
    private const val REQ = 3

    /** اوقات اذان امروز، فردا و پس‌فردا. */
    fun upcoming(ctx: Context, now: Long = System.currentTimeMillis()): List<Prayers.Time> {
        val p = Prefs(ctx)
        if (p.adhanMode == Prayers.MODE_OFF) return emptyList()
        val loc = p.location()
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val out = ArrayList<Prayers.Time>()
        for (i in 0 until 3) {
            out.addAll(Prayers.of(c.timeInMillis, loc.lat, loc.lon, p.adhanMode, p.asrHanafi))
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(PendingIntent.getBroadcast(ctx, REQ, Intent(ctx, AdhanReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val now = System.currentTimeMillis()
        val next = upcoming(ctx, now).filter { it.ms > now + 1000L }.minByOrNull { it.ms } ?: return
        val pi = PendingIntent.getBroadcast(ctx, REQ,
            Intent(ctx, AdhanReceiver::class.java).setAction(ACTION)
                .putExtra(EXTRA_NAME, next.name).putExtra(EXTRA_AT, next.ms),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.ms, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.ms, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.ms, pi)
        }
    }

    fun nameOf(i: Intent) = i.getStringExtra(EXTRA_NAME) ?: "اذان"
    fun timeOf(i: Intent) = i.getLongExtra(EXTRA_AT, 0L)
}

/** رسیدن وقت اذان. */
class AdhanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val at = Adhan.timeOf(intent)
        // اگر گوشی خواب بوده و هشدار خیلی دیر رسیده، بی‌موقع پخش نکن
        if (Prefs(app).adhanMode != Prayers.MODE_OFF && now >= at - 60_000L && now <= at + 120_000L) {
            SoundService.start(app, SoundService.KIND_ADHAN, Adhan.nameOf(intent))
        }
        Adhan.schedule(app)
    }
}
