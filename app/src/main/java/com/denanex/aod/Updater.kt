package com.denanex.aod

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * به‌روزرسانی خودکار از drive.denanex.ir/update (همان‌جا که تسک publishApk می‌فرستد).
 * با هر بار باز شدن اپ، aod.json را می‌خواند؛ اگر نسخهٔ تازه‌تری بود خبر می‌دهد و با یک دکمه
 * دانلود و نصب می‌کند (بدون مرورگر؛ اندروید فقط یک بار «نصب» را از کاربر می‌پرسد).
 */
object Updater {
    private const val BASE = "https://drive.denanex.ir/update/"
    private const val JSON_URL = BASE + "aod.json"
    /** لینک دانلود آخرین نسخه (برای اشتراک‌گذاری). */
    const val DOWNLOAD_URL = BASE + "aod.apk"
    private const val ACTION_STATUS = "com.denanex.aod.INSTALL_STATUS"

    class Info(val code: Long, val name: String, val apk: String, val notes: String)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    @Volatile private var checking = false
    private var offerShowing = false
    private var offeredThisRun = 0L
    /** اگر برای اجازهٔ «نصب از منابع نامعلوم» به تنظیمات رفتیم، پس از برگشت ادامه بده. */
    private var pending: Info? = null

    fun currentCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) { 0L }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    /** نتیجهٔ بررسی: یا [info] یا توضیح فارسی خطا در [error]. */
    private class Fetch(val info: Info?, val error: String?)

    private fun fetchOnce(): Fetch {
        var c: HttpURLConnection? = null
        return try {
            c = URL(JSON_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
            c.connectTimeout = 12_000
            c.readTimeout = 15_000
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            if (code != 200) {
                Fetch(null, when (code) {
                    404 -> "فایل aod.json روی سرور پیدا نشد (کد ۴۰۴). یک بار publish.bat را اجرا کنید " +
                        "و مطمئن شوید پوشهٔ target همان پوشهٔ update سایت است."
                    403 -> "سرور اجازهٔ دسترسی نداد (کد ۴۰۳). اگر VPN روشن است خاموش کنید."
                    in 500..599 -> "سرور خطا داد (کد ${Persian.digits(code.toString())})."
                    else -> "سرور پاسخ نامعمول داد (کد ${Persian.digits(code.toString())})."
                })
            } else {
                val t = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val o = JSONObject(t)
                val apk = o.optString("apk", "aod.apk")
                Fetch(Info(o.optLong("version_code"), o.optString("version_name", "?"),
                    if (apk.startsWith("http")) apk else BASE + apk, o.optString("notes", "")), null)
            }
        } catch (e: UnknownHostException) {
            Fetch(null, "نشانی سرور پیدا نشد. اینترنت گوشی وصل است؟")
        } catch (e: SocketTimeoutException) {
            Fetch(null, "سرور جواب نداد (زمان تمام شد). اگر VPN روشن است خاموش کنید و دوباره امتحان کنید.")
        } catch (e: ConnectException) {
            Fetch(null, "اتصال به سرور رد شد. اگر VPN روشن است خاموش کنید و دوباره امتحان کنید.")
        } catch (e: SSLException) {
            Fetch(null, "مشکل گواهی امنیتی (SSL) سرور: ${e.message ?: ""}\n" +
                "گواهی سرور باید کامل (همراه گواهی میانی) و معتبر باشد.")
        } catch (e: JSONException) {
            Fetch(null, "فایل aod.json روی سرور خراب است یا صفحهٔ دیگری به‌جایش برمی‌گردد.")
        } catch (e: Exception) {
            Fetch(null, "خطا: ${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    /** یک بار دوباره هم امتحان می‌کند (شبکه‌های موبایل گاهی بار اول وصل نمی‌شوند). */
    private fun fetch(): Fetch {
        val first = fetchOnce()
        if (first.info != null) return first
        try { Thread.sleep(1500) } catch (_: InterruptedException) {}
        return fetchOnce()
    }

    /** بررسی نسخهٔ تازه؛ [manual] = کاربر خودش دکمه را زده (پیام «به‌روز است» یا علت خطا هم نشان داده شود). */
    fun check(act: Activity, manual: Boolean = false) {
        if (busy) return
        // یک بررسی در هر لحظه (صفحهٔ تنظیمات و صفحهٔ ساعت هر دو بررسی می‌کنند)
        if (checking && !manual) return
        checking = true
        if (manual) offeredThisRun = 0L     // دکمهٔ «بررسی به‌روزرسانی»: دوباره نشان بده
        if (manual) Toast.makeText(act, "در حال بررسی به‌روزرسانی…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = fetch()
            main.post {
                checking = false
                if (act.isFinishing || act.isDestroyed) return@post
                val info = r.info
                when {
                    info == null -> if (manual) showError(act, r.error ?: "")
                    isNewer(act, info) && (manual || info.code != declinedCode(act)) -> offer(act, info)
                    manual -> Toast.makeText(act, "نسخهٔ شما به‌روز است ✓", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** نسخهٔ سرور تازه‌تر است؟ (اگر نام نسخه دقیقاً همان نسخهٔ نصب‌شده باشد، تازه نیست.) */
    private fun isNewer(ctx: Context, info: Info): Boolean =
        info.code > currentCode(ctx) && info.name != currentName(ctx)

    /** نسخه‌ای که کاربر برایش «بعداً» زده؛ خودکار دوباره پیشنهاد نمی‌شود (فقط با دکمهٔ «بررسی به‌روزرسانی»). */
    private fun declinedCode(ctx: Context): Long =
        ctx.getSharedPreferences("aod", Context.MODE_PRIVATE).getLong("upd_declined", 0L)

    private fun setDeclined(ctx: Context, code: Long) =
        ctx.getSharedPreferences("aod", Context.MODE_PRIVATE).edit().putLong("upd_declined", code).apply()

    /** علت خطا، با دکمهٔ باز کردن نشانی در مرورگر (برای آزمایش یا دریافت دستی). */
    private fun showError(act: Activity, why: String) {
        AlertDialog.Builder(act)
            .setTitle("اتصال به سرور به‌روزرسانی برقرار نشد")
            .setMessage(why + "\n\nنشانی: " + JSON_URL +
                "\n\nبرای آزمایش، «باز کردن در مرورگر» را بزنید: اگر در مرورگر هم باز نشد، مشکل از سرور یا اینترنت است.")
            .setPositiveButton("باز کردن در مرورگر") { _, _ ->
                try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(JSON_URL))) } catch (_: Exception) {}
            }
            .setNeutralButton("دریافت دستی APK") { _, _ ->
                try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BASE + "aod.apk"))) } catch (_: Exception) {}
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun offer(act: Activity, info: Info) {
        // فقط یک پنجرهٔ به‌روزرسانی در هر لحظه، و هر نسخه فقط یک بار در هر اجرای برنامه
        if (offerShowing || offeredThisRun == info.code) return
        offerShowing = true
        offeredThisRun = info.code
        val msg = "نسخهٔ جدید «ساعت همیشه‌روشن» آماده است.\n\nنسخهٔ شما: ${Persian.digits(currentName(act))}" +
            "\nنسخهٔ جدید: ${Persian.digits(info.name)}" +
            (if (info.notes.isNotBlank()) "\n\n${info.notes}" else "")
        AlertDialog.Builder(act)
            .setTitle("⬆ به‌روزرسانی")
            .setMessage(msg)
            .setPositiveButton("دانلود و نصب") { _, _ -> install(act, info) }
            .setNegativeButton("بعداً") { _, _ -> setDeclined(act, info.code) }
            .setOnDismissListener { offerShowing = false }
            .show()
    }

    /** پس از برگشت از تنظیمات «نصب از منابع نامعلوم» صدا زده شود. */
    fun resume(act: Activity) {
        val p = pending ?: return
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) return
        pending = null
        install(act, p)
    }

    private fun install(act: Activity, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            pending = info
            Toast.makeText(act, "یک بار اجازهٔ «نصب برنامه‌های ناشناس» را برای این اپ روشن کنید و برگردید",
                Toast.LENGTH_LONG).show()
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
            } catch (_: Exception) {}
            return
        }
        if (busy) return
        busy = true
        val dlg = AlertDialog.Builder(act)
            .setTitle("در حال دریافت نسخهٔ جدید")
            .setMessage("۰٪")
            .setCancelable(false)
            .show()
        val app = act.applicationContext
        Thread {
            var error: String? = null
            try {
                val c = URL(info.apk + "?v=" + info.code).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.useCaches = false
                if (c.responseCode != 200) throw Exception("HTTP " + c.responseCode)
                val total = c.contentLengthLong
                val pkgInst = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                val sid = pkgInst.createSession(params)
                pkgInst.openSession(sid).use { s ->
                    s.openWrite("aod.apk", 0, if (total > 0) total else -1).use { out ->
                        c.inputStream.use { inp ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        main.post { dlg.setMessage(Persian.digits("$pct") + "٪") }
                                    }
                                }
                            }
                        }
                        s.fsync(out)
                    }
                    c.disconnect()
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, sid,
                        Intent(app, InstallReceiver::class.java).setAction(ACTION_STATUS), flags)
                    s.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            main.post {
                busy = false
                try { dlg.dismiss() } catch (_: Exception) {}
                if (error != null) Toast.makeText(app, "دریافت نسخهٔ جدید ناموفق بود: $error", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}

/** پاسخ نصب‌کنندهٔ اندروید: پرسیدن تأیید از کاربر یا گزارش خطا. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(context, "نصب انجام نشد. $m", Toast.LENGTH_LONG).show()
            }
        }
    }
}
