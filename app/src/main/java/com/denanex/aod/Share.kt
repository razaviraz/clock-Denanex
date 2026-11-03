package com.denanex.aod

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/** اشتراک‌گذاری برنامه: فرستادن خود فایل برنامه (APK) یا لینک دانلود، با پیام‌رسان‌ها و ... */
object Share {
    private const val AUTHORITY = "com.denanex.aod.apk"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** فرستادن فایل APK همین برنامهٔ نصب‌شده. */
    fun apk(act: Activity) {
        val uri = Uri.parse("content://$AUTHORITY/${ApkProvider.FILE_NAME}")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "برنامهٔ «ساعت همیشه‌روشن»")
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            act.startActivity(Intent.createChooser(send, "فرستادن برنامه با…"))
        } catch (_: Exception) {
            Toast.makeText(act, "برنامه‌ای برای فرستادن پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }

    /** فرستادن لینک دانلود آخرین نسخه. */
    fun link(act: Activity) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "ساعت همیشه‌روشن (ساعت، اوقات شرعی، قبله‌نما، اذان)\nدانلود: " + Updater.DOWNLOAD_URL)
        }
        try {
            act.startActivity(Intent.createChooser(send, "فرستادن لینک با…"))
        } catch (_: Exception) {
            Toast.makeText(act, "برنامه‌ای برای فرستادن پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * فقط-خواندنی: فایل APK نصب‌شدهٔ همین برنامه را به برنامهٔ مقصد (تلگرام، بلوتوث و ...) می‌دهد.
 * دسترسی فقط با مجوز موقتی که هنگام فرستادن داده می‌شود (exported=false).
 */
class ApkProvider : ContentProvider() {
    private fun apkFile(): File? = context?.applicationInfo?.sourceDir?.let { File(it) }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> FILE_NAME
                OpenableColumns.SIZE -> apkFile()?.length() ?: 0L
                else -> null
            }
        })
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = apkFile() ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val FILE_NAME = "ClockDenanex.apk"
    }
}
