package com.denanex.aod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * آلبوم عکس ساعت: عکس‌های انتخابی کاربر کوچک‌شده (حداکثر ۷۲۰ پیکسل) در پوشهٔ داخلی خود برنامه
 * (files/album) ذخیره می‌شوند؛ این پوشه و تنظیمات با به‌روزرسانی برنامه پاک نمی‌شوند.
 */
object AlbumStore {
    private const val MAX_SIDE = 720

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "album").apply { mkdirs() }

    fun files(ctx: Context): List<File> =
        (dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: emptyArray()).sortedBy { it.name }

    fun count(ctx: Context) = files(ctx).size

    fun clear(ctx: Context) { files(ctx).forEach { it.delete() } }

    /** عکس‌ها را (در رشتهٔ پس‌زمینه) کوچک و ذخیره می‌کند؛ [done] با تعداد ذخیره‌شده روی رشتهٔ اصلی صدا زده می‌شود. */
    fun import(ctx: Context, uris: List<Uri>, done: (Int) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            var ok = 0
            val stamp = System.currentTimeMillis()
            for ((i, u) in uris.withIndex()) {
                try {
                    val bmp = decodeScaled(app, u) ?: continue
                    val out = File(dir(app), "%d_%03d.jpg".format(stamp, i))
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                    bmp.recycle()
                    ok++
                } catch (_: Exception) {}
            }
            main.post { done(ok) }
        }.start()
    }

    private fun decodeScaled(ctx: Context, u: Uri): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        // چرخش درست عکس (EXIF)
        val rot = try {
            cr.openInputStream(u)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        val scale = min(1f, MAX_SIDE.toFloat() / max(bmp.width, bmp.height))
        if (rot != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r != bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }
}

/**
 * نمایش اسلاید عکس‌ها: هر ۴ ثانیه عکس بعدی با یکی از افکت‌ها (تصادفی)، در قاب دایره یا مربع؛
 * پس از آخرین عکس دوباره از اول. عکس بعدی پیشاپیش در پس‌زمینه خوانده می‌شود.
 */
class AlbumPlayer(private val ctx: Context, private val onReady: () -> Unit) {
    private var files: List<File> = emptyList()
    private var index = -1
    private var cur: Bitmap? = null
    private var prev: Bitmap? = null
    private var next: Bitmap? = null
    private var loadingNext = false
    private var shownAt = 0L
    private var effect = 0
    private val rnd = Random()
    private var io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val src = RectF()
    private val dst = RectF()
    private val m = Matrix()

    val isEmpty get() = files.isEmpty()

    /** فهرست عکس‌ها را دوباره می‌خواند (پس از تغییر آلبوم). */
    fun reload() {
        val f = AlbumStore.files(ctx)
        if (f.map { it.name } == files.map { it.name }) return
        files = f
        index = -1
        cur = null; prev = null; next = null
        if (files.isNotEmpty()) preload()
    }

    private fun preload() {
        if (loadingNext || files.isEmpty()) return
        loadingNext = true
        val i = (index + 1) % files.size
        val file = files[i]
        if (io.isShutdown) io = Executors.newSingleThreadExecutor()
        io.execute {
            val b = try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
            main.post {
                next = b
                loadingNext = false
                if (cur == null) { advance(System.currentTimeMillis()); onReady() }
            }
        }
    }

    private fun advance(now: Long) {
        val n = next ?: return
        prev = cur
        cur = n
        next = null
        index = (index + 1) % files.size
        shownAt = now
        effect = rnd.nextInt(EFFECTS)
        preload()
    }

    /** چند میلی‌ثانیه تا رسم بعدی لازم است (در حین افکت سریع، وگرنه تا عکس بعدی). */
    fun nextFrameDelay(now: Long): Long {
        if (cur == null) return 1000L
        val t = now - shownAt
        return if (t < TRANSITION_MS) 33L else max(16L, SLIDE_MS - t)
    }

    /**
     * عکس فعلی را در مربع (مرکز [cx]، [cy]، ضلع [size]) رسم می‌کند؛ [circle] قاب دایره، وگرنه مربع با گوشهٔ گرد.
     * [frameColor] رنگ قاب.
     */
    fun draw(c: Canvas, now: Long, cx: Float, cy: Float, size: Float, circle: Boolean, frameColor: Int) {
        if (files.isEmpty()) return
        if (now - shownAt >= SLIDE_MS && next != null) advance(now)
        val b = cur ?: return
        val half = size / 2f
        clip.reset()
        if (circle) clip.addCircle(cx, cy, half, Path.Direction.CW)
        else clip.addRoundRect(cx - half, cy - half, cx + half, cy + half, size * 0.06f, size * 0.06f, Path.Direction.CW)
        val p = ((now - shownAt).toFloat() / TRANSITION_MS).coerceIn(0f, 1f)
        val e = p * p * (3f - 2f * p)
        c.save()
        c.clipPath(clip)
        c.drawColor(Color.BLACK)
        val pb = prev
        if (e < 1f && pb != null) drawLayer(c, pb, cx, cy, size, 1f - e, outgoing = true)
        drawLayer(c, b, cx, cy, size, e, outgoing = false)
        c.restore()
        // قاب: حلقهٔ بیرونی پهن و حلقهٔ نازک درونی
        frame.color = frameColor
        frame.alpha = 230
        frame.strokeWidth = size * 0.035f
        val o = frame.strokeWidth / 2f
        if (circle) c.drawCircle(cx, cy, half + o, frame)
        else c.drawRoundRect(cx - half - o, cy - half - o, cx + half + o, cy + half + o, size * 0.07f, size * 0.07f, frame)
        frame.alpha = 120
        frame.strokeWidth = size * 0.008f
        val i = size * 0.025f
        if (circle) c.drawCircle(cx, cy, half - i, frame)
        else c.drawRoundRect(cx - half + i, cy - half + i, cx + half - i, cy + half - i, size * 0.05f, size * 0.05f, frame)
    }

    /**
     * یک لایهٔ عکس با افکت؛ [k] = ۰ تا ۱ (برای عکس تازه: پیشرفت آمدن، برای عکس قبلی: باقی‌ماندهٔ آن).
     * افکت‌ها: محو شدن، کشیدن از چپ/راست/پایین، بزرگ شدن، کوچک شدن، چرخش، ورق خوردن.
     */
    private fun drawLayer(c: Canvas, b: Bitmap, cx: Float, cy: Float, size: Float, k: Float, outgoing: Boolean) {
        // برش وسط عکس به شکل مربع
        val s = min(b.width, b.height).toFloat()
        src.set((b.width - s) / 2f, (b.height - s) / 2f, (b.width + s) / 2f, (b.height + s) / 2f)
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        m.setRectToRect(src, dst, Matrix.ScaleToFit.FILL)
        val inv = 1f - k
        paint.alpha = 255
        if (!outgoing) when (effect) {
            0 -> paint.alpha = (255 * k).toInt()                              // محو شدن
            1 -> m.postTranslate(-size * inv, 0f)                             // از چپ
            2 -> m.postTranslate(size * inv, 0f)                              // از راست
            3 -> m.postTranslate(0f, size * inv)                              // از پایین
            4 -> { m.postScale(0.3f + 0.7f * k, 0.3f + 0.7f * k, cx, cy); paint.alpha = (255 * k).toInt() } // بزرگ شدن
            5 -> { m.postScale(1.6f - 0.6f * k, 1.6f - 0.6f * k, cx, cy); paint.alpha = (255 * k).toInt() } // کوچک شدن
            6 -> { m.postRotate(-90f * inv, cx, cy); m.postScale(0.5f + 0.5f * k, 0.5f + 0.5f * k, cx, cy); paint.alpha = (255 * k).toInt() } // چرخش
            else -> m.postScale(max(0.02f, k), 1f, cx, cy)                    // ورق خوردن
        } else {
            when (effect) {
                1 -> m.postTranslate(size * (1f - k), 0f)
                2 -> m.postTranslate(-size * (1f - k), 0f)
                3 -> m.postTranslate(0f, -size * (1f - k))
                7 -> m.postScale(max(0.02f, k), 1f, cx, cy)
                else -> paint.alpha = (255 * k).toInt()
            }
        }
        c.drawBitmap(b, m, paint)
    }

    fun release() {
        io.shutdownNow()
        cur = null; prev = null; next = null
        files = emptyList()
        loadingNext = false
    }

    companion object {
        const val SLIDE_MS = 4000L
        const val TRANSITION_MS = 900L
        private const val EFFECTS = 8
    }
}
