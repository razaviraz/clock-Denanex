package com.denanex.aod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
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
 * نمایش اسلاید عکس‌ها: هر ۵ ثانیه عکس بعدی با یکی از افکت‌ها (تصادفی)، در قاب دایره یا مربع؛
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
    // عکس‌ها کمی کم‌نورتر (حدود ۸۰٪) تا روی صفحهٔ تیره چشم را نزنند
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = LightingColorFilter(0xCCCCCC, 0)
    }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val src = RectF()
    private val box = RectF()
    private val rr = RectF()
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
     * عکس فعلی را در قاب رسم می‌کند؛ مرکز ([cx]، [cy]) و اندازهٔ پایهٔ [size].
     * [shape]: ۰ دایره، ۱ مربع، ۲ مستطیل افقی، ۳ مستطیل عمودی، ۴ بیضی.
     * [frameStyle]: ۰ طلایی، ۱ نقره‌ای، ۲ چوبی، ۳ سفید پهن، ۴ دوخطی رنگی ([accent])، ۵ بدون قاب.
     * عکس‌ها کمی کم‌نورتر از اصل نمایش داده می‌شوند.
     */
    fun draw(c: Canvas, now: Long, cx: Float, cy: Float, size: Float, shape: Int, frameStyle: Int, accent: Int) {
        if (files.isEmpty()) return
        if (now - shownAt >= SLIDE_MS && next != null) advance(now)
        val b = cur ?: return
        val (bw, bh) = when (shape) {
            2 -> size * 1.35f to size * 0.90f
            3 -> size * 0.80f to size * 1.10f
            4 -> size * 1.25f to size * 0.90f
            else -> size to size
        }
        box.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        val corner = min(bw, bh) * 0.06f
        clip.reset()
        when (shape) {
            0 -> clip.addCircle(cx, cy, bw / 2f, Path.Direction.CW)
            4 -> clip.addOval(box, Path.Direction.CW)
            else -> clip.addRoundRect(box, corner, corner, Path.Direction.CW)
        }
        val p = ((now - shownAt).toFloat() / TRANSITION_MS).coerceIn(0f, 1f)
        val e = p * p * (3f - 2f * p)
        c.save()
        c.clipPath(clip)
        c.drawColor(Color.BLACK)
        val pb = prev
        if (e < 1f && pb != null) drawLayer(c, pb, cx, cy, 1f - e, outgoing = true)
        drawLayer(c, b, cx, cy, e, outgoing = false)
        c.restore()
        drawFrame(c, shape, frameStyle, accent, min(bw, bh))
    }

    /** قاب دور [box] به شکل [shape]. */
    private fun drawFrame(c: Canvas, shape: Int, style: Int, accent: Int, ref: Float) {
        if (style == 5) return
        val (outer, inner) = when (style) {
            0 -> Color.rgb(0xE8, 0xC2, 0x5A) to Color.rgb(0xFF, 0xE9, 0xA8)   // طلایی
            1 -> Color.rgb(0xC8, 0xCC, 0xD4) to Color.rgb(0xF2, 0xF4, 0xF8)   // نقره‌ای
            2 -> Color.rgb(0x7A, 0x4A, 0x22) to Color.rgb(0xC2, 0x8A, 0x52)   // چوبی
            3 -> Color.rgb(0xF4, 0xF2, 0xEC) to Color.TRANSPARENT              // سفید پهن
            else -> accent to accent                                            // دوخطی رنگی
        }
        val outerW = ref * when (style) { 2 -> 0.075f; 3 -> 0.07f; 4 -> 0.012f; else -> 0.035f }
        frame.color = outer
        frame.alpha = 235
        frame.strokeWidth = outerW
        shapeStroke(c, shape, outerW / 2f)
        if (inner != Color.TRANSPARENT) {
            frame.color = inner
            frame.alpha = if (style == 4) 235 else 130
            frame.strokeWidth = ref * (if (style == 4) 0.012f else 0.008f)
            // دوخطی: خط دوم بیرون‌تر با فاصله؛ بقیه: خط نازک درونی
            shapeStroke(c, shape, if (style == 4) ref * 0.045f else -ref * 0.025f)
        }
    }

    /** خط دور شکل، [off] پیکسل بیرون‌تر (مثبت) یا درون‌تر (منفی) از لبهٔ عکس. */
    private fun shapeStroke(c: Canvas, shape: Int, off: Float) {
        rr.set(box.left - off, box.top - off, box.right + off, box.bottom + off)
        val corner = min(rr.width(), rr.height()) * 0.065f
        when (shape) {
            0 -> c.drawCircle(rr.centerX(), rr.centerY(), rr.width() / 2f, frame)
            4 -> c.drawOval(rr, frame)
            else -> c.drawRoundRect(rr, corner, corner, frame)
        }
    }

    /**
     * یک لایهٔ عکس با افکت؛ [k] = ۰ تا ۱ (برای عکس تازه: پیشرفت آمدن، برای عکس قبلی: باقی‌ماندهٔ آن).
     * افکت‌ها: محو شدن، کشیدن از چپ/راست/پایین، بزرگ شدن، کوچک شدن، چرخش، ورق خوردن.
     */
    private fun drawLayer(c: Canvas, b: Bitmap, cx: Float, cy: Float, k: Float, outgoing: Boolean) {
        // برش وسط عکس به نسبت قاب
        val bw = box.width(); val bh = box.height()
        val ar = bw / bh
        var sw = b.width.toFloat(); var sh = b.height.toFloat()
        if (sw / sh > ar) sw = sh * ar else sh = sw / ar
        src.set((b.width - sw) / 2f, (b.height - sh) / 2f, (b.width + sw) / 2f, (b.height + sh) / 2f)
        m.setRectToRect(src, box, Matrix.ScaleToFit.FILL)
        val size = bw
        val inv = 1f - k
        paint.alpha = 255
        if (!outgoing) when (effect) {
            0 -> paint.alpha = (255 * k).toInt()                              // محو شدن
            1 -> m.postTranslate(-size * inv, 0f)                             // از چپ
            2 -> m.postTranslate(size * inv, 0f)                              // از راست
            3 -> m.postTranslate(0f, bh * inv)                                // از پایین
            4 -> { m.postScale(0.3f + 0.7f * k, 0.3f + 0.7f * k, cx, cy); paint.alpha = (255 * k).toInt() } // بزرگ شدن
            5 -> { m.postScale(1.6f - 0.6f * k, 1.6f - 0.6f * k, cx, cy); paint.alpha = (255 * k).toInt() } // کوچک شدن
            6 -> { m.postRotate(-90f * inv, cx, cy); m.postScale(0.5f + 0.5f * k, 0.5f + 0.5f * k, cx, cy); paint.alpha = (255 * k).toInt() } // چرخش
            else -> m.postScale(max(0.02f, k), 1f, cx, cy)                    // ورق خوردن
        } else {
            when (effect) {
                1 -> m.postTranslate(size * (1f - k), 0f)
                2 -> m.postTranslate(-size * (1f - k), 0f)
                3 -> m.postTranslate(0f, -bh * (1f - k))
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
        const val SLIDE_MS = 5000L
        const val TRANSITION_MS = 900L
        private const val EFFECTS = 8
    }
}
