package com.denanex.aod

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * پشتیبان تنظیمات کاربر در یک فایل (مثلاً در پوشهٔ Download یا Google Drive)، برای وقتی که برنامه
 * حذف و دوباره نصب می‌شود (با به‌روزرسانی عادی تنظیمات خودبه‌خود می‌مانند).
 * همهٔ مقدارهای SharedPreferences «aod» با نوعشان ذخیره می‌شوند. عکس‌های آلبوم در این فایل نیستند.
 */
object Backup {
    private const val PREFS = "aod"
    /** کلیدهای موقتی که بازگرداندنشان معنی ندارد. */
    private val SKIP = setOf("gps_last", "upd_declined")

    fun export(ctx: Context, uri: Uri): Boolean = try {
        val all = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val p = JSONObject()
        for ((k, v) in all) {
            if (k in SKIP || v == null) continue
            val (t, value) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is String -> "s" to v
                else -> continue
            }
            p.put(k, JSONObject().put("t", t).put("v", value))
        }
        val root = JSONObject().put("app", "ClockDenanex").put("version", 1)
            .put("saved", System.currentTimeMillis()).put("prefs", p)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        true
    } catch (_: Exception) { false }

    /** تنظیمات را از فایل برمی‌گرداند؛ تعداد مقدارهای بازگردانده‌شده، یا ‎-۱ اگر فایل درست نبود. */
    fun import(ctx: Context, uri: Uri): Int = try {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val root = JSONObject(text)
        if (root.optString("app") != "ClockDenanex") -1 else {
            val p = root.getJSONObject("prefs")
            val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            var n = 0
            for (k in p.keys()) {
                if (k in SKIP) continue
                val o = p.getJSONObject(k)
                when (o.optString("t")) {
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "i" -> e.putInt(k, o.getInt("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> e.putString(k, o.getString("v"))
                    else -> continue
                }
                n++
            }
            e.apply()
            n
        }
    } catch (_: Exception) { -1 }
}
