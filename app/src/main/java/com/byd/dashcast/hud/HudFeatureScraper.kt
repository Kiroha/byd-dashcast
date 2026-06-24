package com.byd.dashcast.hud

import com.byd.dashcast.cluster.mirror.ClusterMirrorManager
import java.lang.reflect.Modifier

/**
 * Reflection scraper for the BYD instrument / navigation feature-ID constants.
 *
 * The HUD nav write registers (`INSTRUMENT_*`) live in the car framework
 * (`android.hardware.bydauto.*`), and their numeric IDs **differ between DiLink
 * versions**. Hardcoded values reverse-engineered from another version (e.g.
 * OpenBYD's DiLink 5.1) can write into the wrong registers on DL3 → the SDK call
 * "succeeds" (rc=0) but nothing shows. This dumps the REAL static constants
 * present on THIS car so we can use the correct DL3 IDs.
 *
 * Pure reflection on framework classes — needs no permission or system context;
 * hidden-API access is already exempted by [ClusterMirrorManager.unlockHiddenApis]
 * (the `Landroid/` exemption prefix covers `android.hardware.bydauto.*`).
 *
 * Credit: feature-ID class names cross-referenced with the open-source
 * Open-BYD project (MIT) — https://github.com/SergioRt1/Open-BYD
 */
object HudFeatureScraper {

    /** BYD framework classes likely to hold instrument / nav / setting feature IDs. */
    val DEFAULT_CLASSES: List<String> = listOf(
        "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
        "android.hardware.bydauto.setting.BYDAutoSettingDevice",
        "android.hardware.bydauto.BYDAutoFeatureIds",
        "android.hardware.bydauto.instrument.Instrument",
        "android.hardware.bydauto.BYDAutoEventValue"
    )

    fun scrape(classNames: List<String> = DEFAULT_CLASSES): String {
        // Idempotent — make sure reflection on hidden framework classes is allowed.
        try { ClusterMirrorManager.unlockHiddenApis() } catch (_: Throwable) { /* best effort */ }

        val sb = StringBuilder()
        sb.appendLine("=== BYD HUD / INSTRUMENT FEATURE-ID SCRAPER ===")
        sb.appendLine("Build: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                + " (${android.os.Build.PRODUCT}, API ${android.os.Build.VERSION.SDK_INT})")
        for (name in classNames) {
            sb.appendLine()
            sb.appendLine("── $name ──")
            val clazz = try {
                Class.forName(name)
            } catch (t: Throwable) {
                sb.appendLine("  (not present: ${t.javaClass.simpleName}: ${t.message})")
                continue
            }
            dumpStaticFields(clazz, sb)
            for (inner in clazz.declaredClasses) {
                sb.appendLine("  ┌ nested: ${inner.name}")
                dumpStaticFields(inner, sb)
            }
        }
        return sb.toString()
    }

    private fun dumpStaticFields(clazz: Class<*>, sb: StringBuilder) {
        var count = 0
        for (f in clazz.declaredFields.sortedBy { it.name }) {
            if (!Modifier.isStatic(f.modifiers)) continue
            try {
                f.isAccessible = true
                val v = f.get(null)
                val rendered = when (v) {
                    is Int  -> "0x%08X (%d)".format(v, v)
                    is Long -> "0x%X (%d)".format(v, v)
                    null    -> "null"
                    else    -> v.toString()
                }
                sb.appendLine("  ${f.type.simpleName} ${f.name} = $rendered")
                count++
            } catch (t: Throwable) {
                sb.appendLine("  ${f.name} = <read failed: ${t.message}>")
            }
        }
        if (count == 0) sb.appendLine("  (no readable static fields)")
    }
}
