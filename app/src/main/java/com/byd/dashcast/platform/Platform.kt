package com.byd.dashcast.platform

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

import androidx.core.content.edit

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Platform — runtime detection of the BYD generation we are running on
 * (DiLink 3.0 / Android 10 vs DiLink 5.0 / Android 12, etc.).
 *
 * The class is initialised once at process start by
 * [com.byd.dashcast.DashCastApp.onCreate] and exposes a read-only snapshot.
 * It also honours a per-user override stored in the same SharedPreferences file as
 * `SettingsActivity` ("byd_app_prefs") so testers can force a given mode without
 * re-installing.
 *
 * Possible user-override states for the DiLink 5 mode:
 *  - **AUTO** (default before the user touches the switch) — effective value follows
 *    [isAutoDetectedDiLink5].
 *  - **FORCE_ON** — effective value is always true.
 *  - **FORCE_OFF** — effective value is always false.
 *
 * Until proven otherwise, the legacy DiLink 3 code path is the default.
 * Any failure during detection => [isDiLink5] returns `false`.
 *
 * Kotlin port note: this stays a CLASS with a private constructor rather than becoming an
 * `object`, because 20 files call `Platform.get()` and hold the instance. The two monitors are
 * also kept distinct on purpose — see [sResizeProbeLock].
 */
class Platform private constructor() {

    private val rawProductName: String = readProp("ro.product.name")   // ro.product.name
    private val rawModel: String = safe(Build.MODEL)                    // Build.MODEL
    private val rawBrand: String = safe(Build.BRAND)                    // Build.BRAND
    private val rawFingerprint: String = safe(Build.FINGERPRINT)        // Build.FINGERPRINT
    private val androidApi: Int = Build.VERSION.SDK_INT                 // Build.VERSION.SDK_INT

    /** pure auto-detection result */
    private val autoDiLink5: Boolean =
            detectDiLink5(rawProductName, rawModel, rawFingerprint, androidApi)

    /** pure auto-detection result (BYD-AUTO/DiLink4.0, API 29) */
    private val autoDiLink4: Boolean =
            detectDiLink4(rawProductName, rawModel, rawFingerprint, androidApi)

    /** explicit DL3 product-name detection (DiLink3.0, API 29) */
    private val autoDiLink3: Boolean =
            detectDiLink3(rawProductName, rawModel, rawFingerprint)

    /** pure auto-detection result (alps/MT6765/API 28) */
    private val autoDiLink2: Boolean =
            detectDiLink2(rawBrand, rawProductName, androidApi)

    // ── Raw snapshot accessors ────────────────────────────────────────────────

    fun rawProductName(): String = rawProductName
    fun rawModel(): String = rawModel
    fun rawBrand(): String = rawBrand
    fun rawFingerprint(): String = rawFingerprint
    fun androidApi(): Int = androidApi
    fun isAutoDetectedDiLink5(): Boolean = autoDiLink5
    fun isAutoDetectedDiLink4(): Boolean = autoDiLink4
    fun isAutoDetectedDiLink3(): Boolean = autoDiLink3
    fun isAutoDetectedDiLink2(): Boolean = autoDiLink2

    /**
     * Effective DiLink 4 mode — there is no user override for DL4 (no toggle in Settings).
     * DL4 is mutually exclusive with DL5: the [isDiLink5] getter neutralises any FORCE_ON DL5
     * override when `autoDiLink4` is true, so a mis-flipped switch in Settings does not push a
     * DL4 device onto the DL5 activation path (which calls the snake_case `auto_container`
     * binder that does not exist on the DL3/DL4 service namespace). The same guard now applies
     * to explicitly-named DL3 products via the `autoDiLink3` field.
     */
    fun isDiLink4(ctx: Context): Boolean = autoDiLink4

    /**
     * Effective DiLink 2 mode — there is no user override for DL2 (no toggle in Settings).
     * This is read every call so future override hooks can be added without API changes.
     * Safe to call from any thread; cached at process start via the singleton.
     */
    fun isDiLink2(ctx: Context): Boolean = autoDiLink2

    /**
     * Effective DiLink 3 mode — there is no user override for DL3 (it is the default /
     * fallback platform). Returns true when no other generation has been auto-detected
     * and the user has not forced DL5 on. Useful to gate DL3-only UI affordances
     * (e.g. the AOSP hotspot launcher in v1.2.36) without touching DL2/4/5 paths.
     */
    fun isDiLink3(ctx: Context): Boolean = !isDiLink2(ctx) && !isDiLink4(ctx) && !isDiLink5(ctx)

    // ── Effective state (auto + user override) ────────────────────────────────

    /**
     * Effective DiLink 5 mode after applying the user override.
     * Read from SharedPreferences each call so changes are visible without
     * a process restart for non-critical UI bits. Hot code paths should
     * cache this value at process start.
     */
    fun isDiLink5(ctx: Context): Boolean {
        // Hard guard: a device auto-detected as DiLink 4 can never be DiLink 5,
        // regardless of the user override. The two generations share the BYD-AUTO
        // brand + DisplayManager primitives but differ on the AutoContainer binder
        // name (DL3/DL4 = "AutoContainer" PascalCase, DL5 = "auto_container"
        // snake_case). Field log BYD_RE_Sniffer_20260523_173033 caught a DL4 testeur
        // who had FORCE_ON DL5 enabled in Settings, which sent every projection
        // attempt at the non-existent snake_case binder. This guard absorbs the
        // mistake transparently so misconfigured Settings cannot break DL4 cars.
        if (autoDiLink4) return false
        // Same guard for explicitly-named DiLink 3 products. INC-20260613-175043
        // caught a DL3 user (product=DiLink3.0, Android 10) with FORCE_ON set:
        // sendInfo(16) via auto_container returned "Service does not exist", VD
        // creation timed out, and projection appeared at wrong size/position.
        if (autoDiLink3) return false
        sCachedIsDiLink5?.let { return it }
        synchronized(LOCK) {
            sCachedIsDiLink5?.let { return it }
            val ov = readOverride(ctx)
            val value = when (ov) {
                OV_FORCE_ON -> true
                OV_FORCE_OFF -> false
                else -> autoDiLink5
            }
            sCachedIsDiLink5 = value
            return value
        }
    }

    /** Short summary used for diagnostics ("AUTO=on", "FORCED off", …). */
    fun describeMode(ctx: Context): String {
        if (autoDiLink4) {
            val ov = readOverride(ctx)
            if (OV_FORCE_ON == ov) return "AUTO=off (DL4 detected — DL5 FORCE_ON ignored)"
            return "AUTO=off (DL4 detected)"
        }
        if (autoDiLink3) {
            val ov = readOverride(ctx)
            if (OV_FORCE_ON == ov) return "AUTO=off (DL3 detected — DL5 FORCE_ON ignored)"
            return "AUTO=off (DL3 detected)"
        }
        val ov = readOverride(ctx)
        if (OV_FORCE_ON == ov) return "FORCED on"
        if (OV_FORCE_OFF == ov) return "FORCED off"
        return "AUTO=" + (if (autoDiLink5) "on" else "off")
    }

    // ── Cluster-resize capability probe (DL5 only) ────────────────────────────

    /**
     * Returns `true` unless this device is DiLink 5 AND we have confirmed (via
     * `cmd activity set-task-windowing-mode`) that the resize verbs are stripped from the
     * `cmd activity` binary on this ROM.
     *
     * Non-DL5 platforms always return `true`: they use a different resize path
     * (`wm overscan` on DL3) and are not affected. On DL5 the value is cached process-wide
     * (volatile) and across cold starts (sticky SharedPreferences). The probe is cheap (single
     * `sh -c`, < 200 ms) but the result is invariant for a given ROM, so we never re-probe
     * unless the user wipes the prefs file.
     *
     * **Threading:** the probe forks a shell — never call from the UI thread on a cold cache.
     * [primeClusterResizeProbe] runs it on a worker at app startup so subsequent UI reads
     * return the cached value immediately.
     */
    fun isClusterTaskResizeSupported(ctx: Context): Boolean {
        if (!isDiLink5(ctx)) return true
        sCachedClusterResizeSupported?.let { return it }
        // Serialise the cache-miss path. Without this, two callers on a cold cache
        // (the startup prime worker and a UI read) can BOTH fork the shell probe
        // and BOTH write the sticky pref. Double-checked under a dedicated lock so
        // only one shell is spawned and one prefs write occurs per process.
        synchronized(sResizeProbeLock) {
            sCachedClusterResizeSupported?.let { return it }
            // Sticky pref takes precedence over a fresh probe (consistent across cold starts).
            val sticky = prefs(ctx).getString(PREF_CLUSTER_RESIZE_SUPPORTED, null)
            if ("yes" == sticky) { sCachedClusterResizeSupported = true; return true }
            if ("no" == sticky) { sCachedClusterResizeSupported = false; return false }
            val supported = probeSetTaskWindowingMode()
            sCachedClusterResizeSupported = supported
            prefs(ctx).edit { putString(PREF_CLUSTER_RESIZE_SUPPORTED, if (supported) "yes" else "no") }
            return supported
        }
    }

    /**
     * Background-prime the cluster-resize probe so UI reads never block on shell
     * I/O. Safe to call multiple times — no-op once the cache is populated.
     */
    fun primeClusterResizeProbe(ctx: Context) {
        if (!isDiLink5(ctx)) return
        if (sCachedClusterResizeSupported != null) return
        if (prefs(ctx).contains(PREF_CLUSTER_RESIZE_SUPPORTED)) {
            // Force a one-shot read to populate the volatile cache.
            isClusterTaskResizeSupported(ctx)
            return
        }
        val app = ctx.applicationContext
        val t = Thread({ isClusterTaskResizeSupported(app) }, "platform-resize-probe")
        t.isDaemon = true
        t.start()
    }

    companion object {

        private const val PREFS_NAME = "byd_app_prefs"

        /** Tri-state: AUTO (no user override), FORCE_ON, FORCE_OFF. */
        const val PREF_DILINK5_OVERRIDE = "platform_dilink5_override"
        const val OV_AUTO = "AUTO"
        const val OV_FORCE_ON = "FORCE_ON"
        const val OV_FORCE_OFF = "FORCE_OFF"

        /**
         * Sticky capability probe — does `cmd activity set-task-windowing-mode` exist on this
         * ROM? Set to "yes" / "no" after the first probe, never re-probed unless the user wipes
         * the SharedPreferences. Introduced v1.2.59-beta after the DL5 fission test report
         * (byd_report_20260528_081206.log) confirmed F10 returns
         * `"Unknown command: set-task-windowing-mode"` on BYD DiLink 5.0 (Android 12, build
         * SKQ1.230128.001) and that the AOSP fallback `cmd activity task resize` returns exit=0
         * with zero visible effect on the fission Presentation VirtualDisplay (F11/F12). On
         * DL2/DL3/DL4 we do not probe — those platforms use a different resize path
         * (wm overscan on DL3 / overlay on DL2 / no-op on DL4).
         */
        const val PREF_CLUSTER_RESIZE_SUPPORTED = "platform_cluster_resize_supported"

        /** The monitor `get()` and the isDiLink5 cache-fill share — the Java `Platform.class`. */
        private val LOCK = Platform::class.java

        @Volatile private var INSTANCE: Platform? = null
        @Volatile private var sCachedIsDiLink5: Boolean? = null
        @Volatile private var sCachedClusterResizeSupported: Boolean? = null

        // Runtime/persisted single-OS verdict — set when the app can't read the prop in-process
        // (SELinux prop context) but the shell can, or when AutoContainer reports "no
        // AutoContainerNative". null = unknown; TRUE = confirmed single-OS. See isClusterSingleOs().
        @Volatile private var sClusterSingleOsRuntime: Boolean? = null
        private const val PREF_CLUSTER_SINGLE_OS = "cluster_single_os_detected"

        /**
         * Guards the one-shot cluster-resize probe so the shell is forked (and the sticky pref
         * written) at most once, even when the startup prime worker and a UI read race on a cold
         * cache. A dedicated monitor — **not** `Platform.class` — so the sub-1.5s shell block can
         * never stall a concurrent [isDiLink5] cache-fill.
         */
        private val sResizeProbeLock = Any()

        /** Cached reflection handle for android.os.SystemProperties#get — resolved once. */
        @Volatile private var sCachedSysPropGet: Method? = null

        /** Lazy init — call from `DashCastApp.onCreate()` once. */
        @JvmStatic
        fun get(): Platform {
            INSTANCE?.let { return it }
            synchronized(LOCK) {
                INSTANCE?.let { return it }
                val p = Platform()
                INSTANCE = p
                return p
            }
        }

        // ── Auto-detection (pure, no Context dependency) ──────────────────────────

        private fun detectDiLink5(product: String?, model: String?,
                                  fingerprint: String?, api: Int): Boolean {
            // Primary signal: ro.product.name contains "DiLink5"
            val p = (product ?: "").lowercase(Locale.ROOT)
            val m = (model ?: "").lowercase(Locale.ROOT)
            val f = (fingerprint ?: "").lowercase(Locale.ROOT)
            if (p.contains("dilink5") || m.contains("dilink5") || f.contains("dilink5")) return true
            if (p.contains("dilink_5") || m.contains("dilink 5") || f.contains("dilink 5")) return true
            // DX_BYD_AUTO: BYD's DiLink 5.0 variant whose ro.product.name is "DX_BYD_AUTO"
            // (not "DiLink5"). It ships Android 11 (API 30), so the api>=31 heuristic below
            // misses it — without this signal it auto-detects as DL3 and the whole DL5 launch
            // path (daemon launchAndForce, overscan skip) is disabled. Confirmed DiLink 5.0 by
            // the user and by field reports INC-20260620-191420 / -205757 / INC-20260621-073318
            // / -201303 / -210703. Build.MODEL is the generic "BYD AUTO" (shared with DL3), so
            // we match the product-name signature "dx_byd" only.
            if (p.contains("dx_byd") || m.contains("dx_byd") || f.contains("dx_byd")) return true
            // Secondary signal: Android 12+ on BYD device strongly implies DiLink 5
            if (api >= 31 && (m.contains("byd") || f.contains("byd-auto") || f.contains("/dilink"))) {
                return true
            }
            return false
        }

        /**
         * DiLink 4 auto-detection — based on the field report of a BYD-AUTO / DiLink4.0
         * test vehicle (23/05/2026) running Android 10 / API 29:
         *   ro.product.name = "DiLink4.0", Build.MODEL = "DiLink4.0 For BYD AUTO",
         *   Build.FINGERPRINT = "BYD-AUTO/DiLink4.0/DiLink4.0:10/...", API 29.
         * Conservative: requires "dilink4" / "dilink 4" / "dilink_4" substring AND API 29
         * (API 32 would already have been claimed by DL5). Returns false on any uncertainty
         * so DL3/DL5 logic stays unaffected on devices we have not field-validated.
         */
        private fun detectDiLink4(product: String?, model: String?,
                                  fingerprint: String?, api: Int): Boolean {
            val p = (product ?: "").lowercase(Locale.ROOT)
            val m = (model ?: "").lowercase(Locale.ROOT)
            val f = (fingerprint ?: "").lowercase(Locale.ROOT)
            val nameHit = p.contains("dilink4") || m.contains("dilink4") || f.contains("dilink4") ||
                    p.contains("dilink_4") || m.contains("dilink 4") || f.contains("dilink 4")
            if (!nameHit) return false
            // DL4 ships Android 10 (API 29). If someone names a future Android 12 ROM
            // "DiLink4" we don't want to mis-route it here, hence the API gate.
            return api == 29 || api == 28
        }

        /**
         * DiLink 3 explicit name detection.
         *
         * Returns true when the product name, model, or fingerprint explicitly contains
         * "DiLink3" (case-insensitive). Used only as a hard guard inside [isDiLink5] to prevent
         * a user-forced FORCE_ON from routing a genuine DL3 device onto the `auto_container`
         * (snake_case) activation path that does not exist on DL3. See INC-20260613-175043
         * (DiLink3.0, Android 10): same symptom as the DL4 incident
         * (BYD_RE_Sniffer_20260523_173033) — user had FORCE_ON set, sendInfo(16) via
         * auto_container returned "Service does not exist", VD creation timed out, projection
         * appeared at wrong size/position.
         *
         * Conservative: only fires when the product explicitly announces "dilink3". Generic
         * Android 10 devices that DashCast cannot identify as any generation are unaffected and
         * may still use the FORCE_ON override.
         */
        private fun detectDiLink3(product: String?, model: String?, fingerprint: String?): Boolean {
            val p = (product ?: "").lowercase(Locale.ROOT)
            val m = (model ?: "").lowercase(Locale.ROOT)
            val f = (fingerprint ?: "").lowercase(Locale.ROOT)
            return p.contains("dilink3") || m.contains("dilink3") || f.contains("dilink3")
        }

        /**
         * DiLink 2 auto-detection — based on the alps / k65v1_64_bsp / MT6765 / Android 9
         * signature confirmed by two field reports (21/05/2026):
         *   Build.BRAND = "alps", ro.product.name contains "k65v1", Build.VERSION.SDK_INT == 28.
         * Conservative: requires brand=alps + product containing "k65" + API 28-29.
         * Returns false on any uncertainty so DL3/DL5 logic stays unaffected.
         */
        private fun detectDiLink2(brand: String?, product: String?, api: Int): Boolean {
            val b = (brand ?: "").lowercase(Locale.ROOT)
            val p = (product ?: "").lowercase(Locale.ROOT)
            if ("alps" != b) return false
            if (!p.contains("k65")) return false
            return api == 28 || api == 29
        }

        @JvmStatic
        fun readOverride(ctx: Context): String =
                prefs(ctx).getString(PREF_DILINK5_OVERRIDE, OV_AUTO) ?: OV_AUTO

        @JvmStatic
        fun setOverride(ctx: Context, value: String?) {
            var v = value
            if (OV_AUTO != v && OV_FORCE_ON != v && OV_FORCE_OFF != v) {
                v = OV_AUTO
            }
            sCachedIsDiLink5 = null
            prefs(ctx).edit { putString(PREF_DILINK5_OVERRIDE, v) }
        }

        /** Convenience used by the Settings switch (boolean-driven). */
        @JvmStatic
        fun setForcedBoolean(ctx: Context, enabled: Boolean) {
            setOverride(ctx, if (enabled) OV_FORCE_ON else OV_FORCE_OFF)
        }

        private fun prefs(ctx: Context): SharedPreferences =
                ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /**
         * Returns true if `cmd activity set-task-windowing-mode` is reachable.
         * False if the binary prints `Unknown command:` or exits non-zero with a known
         * stripped-verb pattern. Any unexpected outcome (timeout, exec failure) is treated as
         * `true` so we never disable resize on a device the probe couldn't characterise.
         */
        private fun probeSetTaskWindowingMode(): Boolean {
            var p: Process? = null
            var reader: Thread? = null
            try {
                // No taskId arg — we only care whether the verb itself is known.
                // The command will fail with "Bad arg" or similar on a healthy ROM
                // (which is exactly what we want — verb known ⇒ supported).
                // redirectErrorStream(true) folds stderr into stdout so there is no
                // second pipe fd to leak, and "cmd" prints "Unknown command" to stderr.
                val pb = ProcessBuilder(
                    "sh", "-c",
                    "cmd activity set-task-windowing-mode 2>&1; echo __exit=\$?")
                pb.redirectErrorStream(true)
                p = pb.start()
                // stdin is unused — close the write-end immediately so we neither hold
                // the fd open nor let the child block waiting on input.
                try { p.outputStream.close() } catch (ignore: Throwable) {}
                val proc = p
                val baos = ByteArrayOutputStream(256)
                reader = Thread({
                    val buf = ByteArray(1024)
                    try {
                        val ins: InputStream = proc.inputStream
                        ins.use {
                            var n = it.read(buf)
                            while (n > 0) {
                                // ByteArrayOutputStream.write() is synchronized — safe from reader thread
                                baos.write(buf, 0, n)
                                n = it.read(buf)
                            }
                        }
                    } catch (ignore: Throwable) {}
                }, "platform-probe-reader")
                reader.isDaemon = true
                reader.start()
                val finished = p.waitFor(1500, TimeUnit.MILLISECONDS)
                if (!finished) {
                    // timeout → assume supported, don't downgrade UX.
                    // Process kill + reader join happen in finally, so nothing leaks.
                    return true
                }
                reader.join(200)
                // Decode only after join() — reader thread has stopped writing.
                val s = baos.toString()
                if (s.contains("Unknown command")) return false
                // exit=255 with "Unknown command" wording is the canonical strip
                // signature on AOSP. Plain exit=255 without the wording could also
                // mean "missing args" on a healthy ROM, so we don't fail on it.
                return true
            } catch (t: Throwable) {
                return true
            } finally {
                if (p != null) {
                    // destroyForcibly() closes the process streams, which unblocks the
                    // reader's is.read() so its bounded join() below returns promptly.
                    try { p.destroyForcibly() } catch (ignore: Throwable) {}
                }
                if (reader != null) {
                    // Bounded join so a wedged reader can never outlive the probe; it is
                    // a daemon, so even a missed join cannot keep the process alive.
                    try { reader.join(200) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }

        // ── SystemProperties.get() via reflection (no compile-time dep) ──────────

        private fun readProp(key: String): String {
            return try {
                var m = sCachedSysPropGet
                if (m == null) {
                    val sp = Class.forName("android.os.SystemProperties")
                    m = sp.getMethod("get", String::class.java, String::class.java)
                    sCachedSysPropGet = m
                }
                val v = m.invoke(null, key, "")
                v?.toString() ?: ""
            } catch (t: Throwable) {
                ""
            }
        }

        private fun safe(s: String?): String = s ?: ""

        /**
         * True iff the cluster runs in **single-OS fission** mode
         * (`ro.build.system.fission_single_os == "1"`). On a DL3 product this means the
         * instrument cluster is rendered natively (Qt/fission), with NO projectable Android
         * display — AutoContainer app projection is impossible there (proven on-car: the working
         * 1-for-2 car has `=0` + a cluster VirtualDisplay; the single-OS car has `=1`, only
         * Display 0, and "no AutoContainerNative"). Read-only prop, immutable after boot.
         *
         * **Fail-open:** returns `false` if the prop can't be read (never gate a car we can't
         * classify). Note `=1` alone is NOT enough to gate — combine with [isDiLink3] so a real
         * DL5.1 (also single-OS) is unaffected.
         *
         * The in-process [readProp] often returns "" for this prop on the very cars that are
         * single-OS (the app's SELinux domain can't read the vendor prop, though the uid-2000
         * shell can — INC-20260715-140107). So this also honours a runtime verdict recorded by
         * [noteClusterSingleOsDetected] and persisted across restarts by [primeClusterSingleOs].
         */
        @JvmStatic
        fun isClusterSingleOs(): Boolean {
            if ("1" == readProp("ro.build.system.fission_single_os")) return true
            val rt = sClusterSingleOsRuntime
            return rt != null && rt
        }

        /**
         * Records — and persists — that this device's cluster is single-OS (no projectable
         * VirtualDisplay). Call ONLY once single-OS is confirmed on a DL3 via the AUTHORITATIVE,
         * race-free read of `ro.build.system.fission_single_os == "1"` (in-process or a shell
         * `getprop`); the caller is responsible for the [isDiLink3] check so DL5.1 (also
         * single-OS) is never marked. Do NOT call this from a transient signal such as a
         * boot-race "no AutoContainerNative" reply — that could permanently disable a working
         * 1-for-2.
         */
        @JvmStatic
        fun noteClusterSingleOsDetected(ctx: Context) {
            sClusterSingleOsRuntime = true
            try { prefs(ctx).edit { putBoolean(PREF_CLUSTER_SINGLE_OS, true) } }
            catch (ignore: Throwable) { /* best-effort persistence */ }
        }

        /**
         * Loads the persisted single-OS verdict into the in-memory cache. Call once at startup
         * (ClusterService.onCreate) so the guards fire immediately on a car already known to be
         * single-OS, without wasting an activation cycle first.
         */
        @JvmStatic
        fun primeClusterSingleOs(ctx: Context) {
            try {
                if (prefs(ctx).getBoolean(PREF_CLUSTER_SINGLE_OS, false)) {
                    sClusterSingleOsRuntime = true
                }
            } catch (ignore: Throwable) { /* fail-open: stay unknown */ }
        }

        /**
         * Raw HUD/MCU firmware id — `apps.setting.product.inswver`, e.g.
         * `"6125f_1for2_USER_SIGN_SX326_202602032334_Q2700"`. Empty if unreadable.
         *
         * On DL3 the embedded `SX<NNN>` revision code + build date discriminate the
         * instrument-MCU firmware, which (per the on-car RE) decides whether the windshield HUD
         * can draw turn-by-turn nav arrows at all (older firmware cannot; a newer one can).
         * Read-only system property, readable without any permission — used by the HUD bench to
         * report the firmware alongside the tester's arrow observation so the capability
         * threshold can be pinned.
         */
        @JvmStatic
        fun hudFirmwareVersion(): String = readProp("apps.setting.product.inswver")
    }
}
