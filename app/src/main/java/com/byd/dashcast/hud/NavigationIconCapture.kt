package com.byd.dashcast.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.graphics.createBitmap
import com.byd.dashcast.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One explicitly requested snapshot. No background capture, notification text, or OEM writes. */
internal object NavigationIconCapture {
    private const val MAX_NOTIFICATIONS = 4
    private const val MAX_EDGE = 256

    fun selectNotifications(active: Array<out StatusBarNotification?>): List<StatusBarNotification> =
        active.filterNotNull().filter {
            MapNotificationListenerService.isMapsPackage(it.packageName) &&
                MapNotificationListenerService.isNavigationNotification(it.notification)
        }.sortedByDescending { it.postTime }.take(MAX_NOTIFICATIONS)

    /** Must run on a worker; retained files use the existing shareable, bounded report store. */
    fun capture(ctx: Context, notifications: List<StatusBarNotification>): File {
        val selected = selectNotifications(notifications.toTypedArray())
        require(selected.isNotEmpty()) { "No active Maps navigation notification" }
        val capturedAt = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(capturedAt))
        val work = Files.createTempDirectory(ctx.cacheDir.toPath(), "hud_navicons_${stamp}_").toFile()
        try {
            val entries = JSONArray()
            selected.forEachIndexed { index, sbn ->
                val n = sbn.notification
                val entry = JSONObject()
                    .put("package", sbn.packageName)
                    .put("postTime", sbn.postTime)
                    .put("titleLength", n.extras?.getCharSequence("android.title")?.length ?: 0)
                    .put("textLength", n.extras?.getCharSequence("android.text")?.length ?: 0)
                    .put("bigTextLength", n.extras?.getCharSequence("android.bigText")?.length ?: 0)
                    .put("smallIcon", exportIcon(ctx, n.smallIcon, work, "${index + 1}_small.png"))
                    .put("largeIcon", exportIcon(ctx, n.getLargeIcon(), work, "${index + 1}_large.png"))
                entries.put(entry)
            }
            val manifest = JSONObject()
                .put("schema", 1)
                .put("capturedAtEpochMs", capturedAt)
                .put("version", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("buildFingerprint", Build.FINGERPRINT)
                .put("maxImageEdge", MAX_EDGE)
                .put("note", "Explicit snapshot; icons are unlabelled. No notification text or OEM writes.")
                .put("notifications", entries)
            File(work, "manifest.json").writeText(manifest.toString(2))
            return HudCaptureSupport.zipDirToStore(ctx, work)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun exportIcon(ctx: Context, icon: Icon?, work: File, name: String): JSONObject {
        if (icon == null) return JSONObject().put("status", "absent")
        val result = JSONObject().put("type", icon.type)
        if (icon.type == Icon.TYPE_RESOURCE) {
            try {
                result.put("resourceName", ctx.packageManager.getResourcesForApplication(icon.resPackage)
                    .getResourceEntryName(icon.resId))
            } catch (_: Exception) { result.put("resourceName", "unavailable") }
        }
        // Do not dereference URI/provider icons or record their potentially private paths.
        if (icon.type !in setOf(Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP, Icon.TYPE_RESOURCE)) {
            return result.put("status", "unsupported_type")
        }
        var bitmap: Bitmap? = null
        try {
            val drawable = icon.loadDrawable(ctx) ?: return result.put("status", "unavailable")
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 64
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 64
            val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
            val outWidth = (width * scale).toInt().coerceAtLeast(1)
            val outHeight = (height * scale).toInt().coerceAtLeast(1)
            val rendered = createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            bitmap = rendered
            drawable.setBounds(0, 0, outWidth, outHeight)
            drawable.draw(Canvas(rendered))
            File(work, name).outputStream().use {
                check(rendered.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" }
            }
            return result.put("status", "exported").put("file", name)
                .put("originalWidth", width).put("originalHeight", height)
                .put("width", outWidth).put("height", outHeight)
        } catch (e: Exception) {
            File(work, name).delete()
            return result.put("status", "failed").put("errorClass", e.javaClass.simpleName)
        } finally {
            // This bitmap is ours; the notification's original bitmap must remain usable.
            bitmap?.recycle()
        }
    }
}
