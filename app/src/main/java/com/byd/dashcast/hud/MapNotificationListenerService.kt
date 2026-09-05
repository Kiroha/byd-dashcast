package com.byd.dashcast.hud

import android.app.Notification
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.system.CanBusController
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.PackagePseudonymizer
import com.byd.dashcast.util.concurrent.LatestValueDispatcher

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * MapNotificationListenerService — parses Google Maps (and compatible) navigation notifications and
 * drives the [HudController] to update the BYD cluster HUD.
 *
 * The user must enable this service once in
 * **Settings → Apps → Special app access → Notification access → DashCast**. Once enabled it runs
 * as a system-bound service and receives all notifications automatically — no further interaction
 * required.
 *
 * Supported navigation apps:
 *  - `com.google.android.apps.maps` — Google Maps
 *  - `app.revanced.android.apps.maps` — Maps ReVanced
 *  - `com.waze` — Waze (best-effort text parsing)
 *
 * Parsing strategy (applied in order):
 *  1. Try to resolve the notification small icon resource name and map it to a BYD turn icon ID
 *     (most reliable, version-independent).
 *  2. Fall back to keyword-based text parsing of the notification title + text (handles all
 *     locales, but less precise for ambiguous instructions).
 *
 * Kotlin port note: the six regexes and the two 240-entry lookup tables were transposed by script
 * and diffed against a golden dump of the Java's own compiled Patterns. RX_ROAD_ONTO contains a
 * `$` anchor, which is string interpolation in Kotlin — an unescaped one would silently compile a
 * different regex.
 */
class MapNotificationListenerService : NotificationListenerService() {

    // ─── HUD write offloading ─────────────────────────────────────────────
    // All ProxyClient/CAN writes (HudController.updateNavigation/closeNavigation
    // → CanBusController.send* → ProxyClient binder round-trips + sendBroadcast)
    // run on this single-thread SERIAL executor so the system notification
    // dispatch thread never blocks on the daemon. Serial (not pooled) preserves
    // guidance-frame order → CAN frames are never reordered. LatestValueDispatcher
    // keeps at most one drain task and one pending state during notification bursts.
    // appContext is the process-scoped application context (safe to retain — not
    // the service instance, so no leak).
    private var hudDispatcher: LatestValueDispatcher<PendingHudUpdate>? = null

    @Volatile private var appContext: Context? = null

    private val hudDeliveryTracker = HudDeliveryTracker()

    // Notification-level deduplication — avoid reprocessing identical notification content
    // (same pattern as OpenBYD MapNotificationListenerService lastNotification* fields).
    private var lastTitle = ""
    private var lastText = ""
    private var lastBigText = ""
    private var lastSubText = ""
    private var lastNotificationKey: String? = null

    // Last logged (icon|road) so the NAV PARSE diagnostic (raw notification → parsed icon, captured
    // in the DashCast journal / bug report) is written once per distinct maneuver, not every second.
    private var lastLoggedNav = ""

    // Throttle for the "unsupported nav app" diagnostic — the same app is logged at most once per
    // ~30 s so a "no arrow" bug report always carries a RECENT line explaining why (e.g. Telenav).
    private var lastUnsupportedNavPkg = ""
    private var lastUnsupportedLogMs = 0L
    private var packageMarkerKey: ByteArray? = null

    // Cache of per-source-package Resources (Maps/Waze/ReVanced). A nav app's resources
    // don't change at runtime, so build the Context + AssetManager once per package instead
    // of on every notification (createPackageContext().getResources() is a PM lookup + asset
    // load that previously ran per nav frame on the dispatch thread).
    private val mResCache = ConcurrentHashMap<String, Resources>()

    // ─── NotificationListenerService callbacks ────────────────────────────

    override fun onCreate() {
        super.onCreate()
        val processContext = applicationContext
        appContext = processContext
        val executor = Executors.newSingleThreadExecutor { r ->
            val t = Thread(r, "hud-nav-writer")
            t.isDaemon = true
            t
        }
        hudDispatcher = LatestValueDispatcher(executor) { pending ->
            if (HudController.updateNavigation(processContext, pending.data)) {
                hudDeliveryTracker.markDelivered(pending.generation)
            }
        }
    }

    override fun onDestroy() {
        clearTrackedNavigation()
        val dispatcher = hudDispatcher
        hudDispatcher = null
        val ctx = appContext
        appContext = null
        if (dispatcher != null && ctx != null) {
            // close() drops queued guidance, waits behind any update already executing, then
            // clears the HUD before gracefully terminating the writer. The Runnable captures
            // only application Context, not this service instance.
            dispatcher.close { HudController.closeNavigation(ctx) }
        }
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // The system (re)bound us — possibly mid-route, after a process restart or a rebind.
        // onNotificationPosted only fires on NEW posts, so an ALREADY-posted ongoing nav notification
        // stays invisible until the nav app next changes its content: the HUD would not resume, and
        // the bug reporter would wrongly conclude "no route is running". Replay what is already on
        // screen through the normal pipeline. Guarded — a listener callback must never crash.
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                if (sbn != null && isNavPackage(sbn.packageName)) {
                    onNotificationPosted(sbn)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onListenerConnected rescan failed: " + t.message)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // System unbound the listener (e.g. permission revoked, system crash).
        // Close the HUD so the cluster doesn't stay frozen on the last nav state.
        clearTrackedNavigation()
        postNavClose()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val n = sbn.notification
        if (!isNavPackage(sbn.packageName)) {
            maybeLogUnsupportedNav(sbn.packageName, n)
            return
        }
        if (n == null) return

        if (!isNavigationNotification(n)) return

        // NOTE: whether this counts as nav activity is decided LOWER DOWN, once we know if it looks
        // like guidance (a resolved maneuver or a real distance) — see the guidance-signal gate after
        // distance parsing. A nav app's NON-guidance ongoing notification (e.g. Waze's
        // CLOSE_WAZE_CHANNEL foreground/close prompt) must NOT be recorded as a nav frame.

        val extras = n.extras ?: return

        val title = charSeqToString(extras.getCharSequence("android.title"))
        val text = charSeqToString(extras.getCharSequence("android.text"))
        val bigText = charSeqToString(extras.getCharSequence("android.bigText"))
        val subText = charSeqToString(extras.getCharSequence("android.subText"))

        // Notification-level deduplication: skip if content hasn't changed since last call.
        if (isSameNotificationContent(
                        sbn.key, lastNotificationKey,
                        title, lastTitle, text, lastText,
                        bigText, lastBigText, subText, lastSubText)) {
            // AUD-003 — but first, keep the HUD alive. This is a re-post of the very frame already
            // displayed, so the arrow up there is still correct and the staleness watchdog must not
            // read "no update" as "frozen". Only when that frame actually reached the HUD: an
            // identical re-post of something that never parsed proves nothing, and letting it
            // refresh liveness would disarm the watchdog for the case it exists for.
            if (hudDeliveryTracker.currentContentWasDelivered()) {
                HudController.noteNavFrameSeen()
            }
            return
        }
        lastTitle = title
        lastText = text
        lastBigText = bigText
        lastSubText = subText
        lastNotificationKey = sbn.key
        // New content gets a generation before parsing. It is acknowledged only after the serial
        // writer confirms a guidance output; unparseable or failed frames remain unacknowledged.
        val deliveryGeneration = hudDeliveryTracker.beginContent()

        // OPT-IN raw capture (Diagnostics → "Capture raw nav-notification text"): the ACTUAL text a
        // supported nav app posts, clipped — logged here for EVERY distinct notification content,
        // BEFORE the skip / guidance gates below, so notifications that FAIL to parse (the Waze case
        // we need to diagnose: no matchable distance/icon → early return) are still captured. Location
        // PII; OFF by default; deduped on content by the check just above. See KEY_NAV_RAW_CAPTURE.
        if (ClusterPrefs.isNavRawCaptureEnabled(this)) {
            AppLogger.i(TAG, "NAV RAW pkg=" + sbn.packageName +
                    " title='" + clip(title) + "'" +
                    " text='" + clip(text) + "'" +
                    (if (bigText.isEmpty()) "" else " big='" + clip(bigText) + "'") +
                    (if (subText.isEmpty()) "" else " sub='" + clip(subText) + "'"))
        }

        // Skip noise notifications (OpenBYD MapNotificationListenerService pattern).
        // These are ongoing navigation notifications that carry no maneuver data.
        val lowerText = text.lowercase(Locale.ROOT)
        val lowerTitle = title.lowercase(Locale.ROOT)
        for (skip in SKIP_STRINGS) {
            if (lowerText.contains(skip) || lowerTitle.contains(skip)) return
        }

        // Combine title + text for pattern matching.
        // Normalise Eastern Arabic-Indic (U+0660-U+0669) and Extended (U+06F0-U+06F9) digits
        // to ASCII before anything parses this. The maneuver-keyword table below has a full
        // Arabic section, so on an Arabic head unit the ARROW resolves — while \d in the
        // distance and time patterns is ASCII-only, so the DISTANCE never did. An arrow with
        // no distance is the worst of the three states: the driver sees a turn and no idea when.
        // normaliseDigits keeps its nullable signature because ArabicNavParsingTest
        // asserts normaliseDigits(null) == null; the argument here is a concatenation of
        // non-null Strings, so the result cannot be null.
        val combined = normaliseDigits((title + " " + text + " " + bigText).trim())!!
        val lower = combined.lowercase(Locale.ROOT)

        if (combined.isEmpty()) return

        // 1. Turn icon — try icon resource name first, then text; track WHICH source resolved it so
        //    the NAV PARSE diagnostic shows whether we read the direction from the small-icon
        //    resource, a text keyword, or fell back to the straight default.
        val iconResName = smallIconResName(sbn.packageName, n.smallIcon)
        var iconId = resolveIconFromResource(sbn.packageName, n.smallIcon)
        val iconSrc: String
        if (iconId > 0) {
            iconSrc = "resource"
        } else {
            iconId = resolveIconFromText(lower)
            if (iconId > 0) {
                iconSrc = "text"
            } else {
                iconId = -1
                iconSrc = "unresolved"
            }
        }

        // 1b. Roundabout exit number (OEM parity): a generic roundabout icon carries no exit count.
        //     If the instruction names an exit ("3rd exit" / "sortie 3"), promote it to the OEM
        //     exit-numbered icon — CCW 25-34 / CW 35-44 (exit N = base+N). Handedness comes from the
        //     resolved generic icon (CW is the parser's default); a notification doesn't expose it.
        if (iconId == CanBusController.ICON_ROUNDABOUT_CW_1_LAP
                || iconId == CanBusController.ICON_ROUNDABOUT_CCW_1_LAP) {
            val exit = parseRoundaboutExit(lower)
            if (exit > 0) {
                val base = if (iconId == CanBusController.ICON_ROUNDABOUT_CCW_1_LAP) 24 else 34
                iconId = base + exit
            }
        }

        // 2. Distance to next turn — scan title then text then combined.
        val distance = parseFirstDistance(combined)

        // GUIDANCE-SIGNAL GATE: only treat this as a navigation frame if it actually looks like
        // guidance — a resolved maneuver icon OR a real forward distance (> 0). This rejects a nav
        // app's NON-guidance ongoing notifications (e.g. Waze's CLOSE_WAZE_CHANNEL foreground/close
        // prompt: unresolved icon + no distance), which otherwise (a) were pushed to the HUD as a
        // bogus "straight, 0 m" arrow and (b) recorded as a false "parse-fail" that mislabelled a
        // no-route case as a parser bug (INC-20260726-140441). Nothing recorded here ⇒ NavSeen stays
        // "no", so the reporter correctly tells the driver to start a route.
        val looksLikeGuidance = hasGuidanceSignal(iconId, distance)
        if (!looksLikeGuidance) return

        // A supported nav app posted a GUIDANCE frame ⇒ a route IS running. Recorded now (before the
        // parse below can still bail) so the reporter can tell "no route" from "route running but we
        // could not fully parse it" — the latter is a real parser bug, never gated away as user error.
        noteNavActivity(sbn.packageName, false)

        if (!isCompleteGuidance(iconId, distance)) {
            if (iconId <= 0) {
                if (ClusterPrefs.isNavRawCaptureEnabled(this)) {
                    Log.d(TAG, "no maneuver found in: $combined")
                } else {
                    Log.d(TAG, "no maneuver found (len=" + combined.length +
                            ", raw nav capture off — enable it in Diagnostics to see the text)")
                }
            } else {
                // This printed the whole notification — current road, upcoming street,
                // destination, ETA — with no gate. Preserve the opt-in privacy boundary.
                if (ClusterPrefs.isNavRawCaptureEnabled(this)) {
                    Log.d(TAG, "no distance found in: $combined")
                } else {
                    Log.d(TAG, "no distance found (len=" + combined.length +
                            ", raw nav capture off — enable it in Diagnostics to see the text)")
                }
            }
            return
        }

        // 3. Road name — look for "onto X" / "sur X" pattern.
        val roadName = parseRoadName(combined)

        // 4. Remaining route (from subText or parenthetical in text).
        val routeSrc = if (subText.isEmpty()) text else subText
        val remainSec = parseRemainingSeconds(routeSrc)
        val remainDist = parseRemainingMeters(routeSrc)

        // 5. Arrival wall-clock ETA (OEM EXPECTED_ARRIVE_* family) — best-effort from the summary
        //    line; distinct from the remaining DURATION above. A notification carries no day-code.
        val eta = parseEtaClock(routeSrc)
        val etaHour = eta?.get(0)
        val etaMinute = eta?.get(1)

        val data = HudNavigationData(
                iconId, distance, roadName, remainDist, remainSec, etaHour, etaMinute)

        // NAV PARSE diagnostic (raw notification → parsed icon), written to the DashCast journal so
        // every bug report shows GROUND TRUTH: what the nav app's notification actually said vs the
        // arrow we produced — the way to verify the direction mapping on real Maps/Waze notifications.
        // Deduped per distinct (icon|road) maneuver so it never floods the ~1 Hz distance ticks.
        val navKey = "$iconId|$roadName"
        if (navKey != lastLoggedNav) {
            lastLoggedNav = navKey
            // The raw notification title/text/bigText and the parsed road name are location PII
            // (destination / current road / ETA / upcoming street) and this journal line flows
            // into every bug report. Log only what the icon-mapping diagnostic needs — the icon
            // source + resource name + distance — plus non-PII length/found flags.
            AppLogger.i(TAG, "NAV PARSE icon=" + iconId + " src=" + iconSrc +
                    " smallIcon='" + iconResName + "'" +
                    " titleLen=" + title.length + " textLen=" + text.length +
                    (if (bigText.isEmpty()) "" else " bigLen=" + bigText.length) +
                    " -> dist=" + distance + " road=" + (if (roadName.isEmpty()) "no" else "yes") +
                    " eta=" + (if (eta != null) "yes" else "no"))
        }
        Log.d(TAG, "nav update: icon=" + iconId + " dist=" + distance +
                " road=" + (if (roadName.isEmpty()) "no" else "yes") +
                " remDist=" + remainDist + " remSec=" + remainSec)

        // Fully parsed into a HudNavigationData ⇒ we understood the frame, not just received it.
        noteNavActivity(sbn.packageName, true)

        // Offload the ProxyClient/CAN write off the notification dispatch thread.
        // Remember WHICH notification is driving the HUD, so the removal path can tell it apart
        // from the nav app's other ongoing notifications. See onNotificationRemoved.
        sDrivingKey = sbn.key
        postNavUpdate(data, deliveryGeneration)
    }

    /**
     * The removal path needs the same gate the posting path has.
     *
     * onNotificationPosted carries a GUIDANCE-SIGNAL GATE, added because a nav app's non-guidance
     * ongoing notifications — Waze's CLOSE_WAZE_CHANNEL prompt is the documented one — were being
     * pushed to the HUD as a bogus "straight, 0 m" arrow (INC-20260726-140441). This method had no
     * such gate: ANY ongoing notification from a nav package tore the HUD down. So the very prompt
     * that gate was written to reject would, on disappearing, kill live guidance mid-route.
     *
     * Rather than duplicate the gate's logic, this reuses its verdict: the key of the last
     * notification that actually passed it and reached the HUD. Only that one closing means the
     * route is over. As a side effect it also fixes the two-nav-apps case, where one app's teardown
     * used to cancel the other's guidance.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || !isNavPackage(sbn.packageName)) return
        val n = sbn.notification
        if (!isNavigationNotification(n)) return

        val key = sbn.key
        val driving = sDrivingKey
        if (driving != null && driving != key) {
            // A different ongoing notification from the same nav app. Not our guidance frame.
            Log.d(TAG, "nav notification removed but it was not the one driving the HUD — ignored")
            return
        }
        Log.d(TAG, "nav notification removed → closeNavigation")
        sDrivingKey = null
        if (replayRemainingNavigation(key)) return
        postNavClose()
        resetNotificationIdentity()
    }

    private fun replayRemainingNavigation(removedKey: String?): Boolean {
        val active: Array<out StatusBarNotification?>? = try {
            activeNotifications
        } catch (t: Throwable) {
            Log.w(TAG, "remaining navigation rescan failed: " + t.message)
            return false
        }
        for (candidate in remainingNavigationNotifications(active, removedKey)) {
            resetNotificationIdentity()
            onNotificationPosted(candidate)
            if (sDrivingKey != null) {
                Log.d(TAG, "restored remaining navigation notification after removal")
                return true
            }
        }
        return false
    }

    private fun resetNotificationIdentity() {
        lastTitle = ""
        lastText = ""
        lastBigText = ""
        lastSubText = ""
        lastNotificationKey = null
        hudDeliveryTracker.invalidate()
    }

    private fun clearTrackedNavigation() {
        sDrivingKey = null
        resetNotificationIdentity()
    }

    // ─── HUD write dispatch (serial writer thread) ────────────────────────

    /**
     * Queue a nav update on the serial HUD writer thread. If updates arrive faster than the daemon
     * drains them, only the newest pending state survives and at most one drain task stays queued.
     */
    private fun postNavUpdate(data: HudNavigationData?, generation: Long) {
        if (data == null) return
        hudDispatcher?.submit(PendingHudUpdate(data, generation))
    }

    /**
     * Queue a HUD close on the serial writer thread. Cancels any queued-but-unrun guidance frame
     * first so a stale update cannot re-open the HUD after removal. Falls back to a synchronous
     * close ONLY when the executor is already gone or rejects (service teardown) so the cluster
     * never freezes on the last nav state.
     */
    private fun postNavClose() {
        val ctx = appContext ?: return
        val dispatcher = hudDispatcher
        if (dispatcher == null) {
            HudController.closeNavigation(ctx)
            return
        }
        if (!dispatcher.cancelPendingAndExecute { HudController.closeNavigation(ctx) }) {
            HudController.closeNavigation(ctx)
        }
    }

    private class PendingHudUpdate(val data: HudNavigationData, val generation: Long)

    // ─── Icon resolution ──────────────────────────────────────────────────

    /**
     * Try to get the icon resource entry name from the source package and map it to a BYD turn icon
     * ID. Returns ≤ 0 on failure (caller falls back to text).
     */
    private fun resolveIconFromResource(pkg: String?, icon: Icon?): Int {
        if (icon == null || icon.type != Icon.TYPE_RESOURCE) return -1
        try {
            var res = mResCache[pkg]
            if (res == null) {
                res = createPackageContext(pkg, 0).resources
                mResCache[pkg!!] = res
            }
            val resId = icon.resId
            if (resId == 0) return -1
            val name = res.getResourceEntryName(resId).lowercase(Locale.ROOT)
            for (entry in ICON_NAME_MAP) {
                if (name.contains(entry[0] as String)) return entry[1] as Int
            }
        } catch (t: Throwable) {
            Log.d(TAG, "icon res lookup failed: " + t.message)
        }
        return -1
    }

    /**
     * The small-icon resource entry name (e.g. `ic_maneuver_turn_right`) or `""` — for the NAV PARSE
     * diagnostic, so on a real car we can see what the nav app's small icon actually is (and whether
     * it carries the maneuver at all, or is generic → we must rely on text parsing).
     */
    private fun smallIconResName(pkg: String?, icon: Icon?): String {
        if (icon == null || icon.type != Icon.TYPE_RESOURCE) return ""
        return try {
            var res = mResCache[pkg]
            if (res == null) {
                res = createPackageContext(pkg, 0).resources
                mResCache[pkg!!] = res
            }
            val resId = icon.resId
            if (resId == 0) "" else res.getResourceEntryName(resId)
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Logs (throttled, once per ~30 s per app) when a notification comes from a KNOWN unsupported
     * nav app, or any app whose notification is `category=navigation`. This makes a "no arrow in
     * HUD" bug report self-explanatory — DashCast only reads Google Maps / Maps ReVanced / Waze
     * notifications, so an EU Telenav user gets no arrow and, before this, no clue why (field report
     * INC-20260715-161802). Written to the DashCast journal → into every report.
     */
    private fun maybeLogUnsupportedNav(pkg: String?, n: Notification?) {
        if (pkg == null || n == null) return
        val navCategory = Notification.CATEGORY_NAVIGATION == n.category
        var knownNav = false
        for (p in KNOWN_UNSUPPORTED_NAV) {
            if (pkg.startsWith(p)) { knownNav = true; break }
        }
        if (!navCategory && !knownNav) return
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastUnsupportedNavPkg && now - lastUnsupportedLogMs < 30_000L) return
        lastUnsupportedNavPkg = pkg
        lastUnsupportedLogMs = now
        // Privacy: the package name is written to the DashCast journal → every bug report → Telegram.
        // For a KNOWN unsupported nav app the package comes from our own hardcoded list (below), so
        // naming it discloses nothing new and is diagnostically essential (e.g. the EU Telenav "no
        // arrow" field report). But the category=navigation branch fires for ANY installed app, so
        // logging its raw package would leak the driver's app inventory off-device. Emit a keyed,
        // per-install marker instead — enough to correlate recurring reports from this installation
        // without enabling a global package-name dictionary lookup.
        val who = if (knownNav) ("app=" + pkg + " (known nav app)")
                  else ("pkgHash=" + coarsePkgMarker(pkg) + " (category=navigation)")
        AppLogger.i(TAG, "NAV UNSUPPORTED " + who +
                " — DashCast HUD nav only reads Google Maps / Maps ReVanced / Waze")
    }

    /** Per-install HMAC marker for an unknown package. Falls back to a fixed token on any failure. */
    private fun coarsePkgMarker(pkg: String?): String {
        if (pkg == null) return "?"
        return try {
            PackagePseudonymizer.marker(getOrCreatePackageMarkerKey(), pkg)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Package marker unavailable: " + e.javaClass.simpleName)
            "marker-na"
        }
    }

    /** Loads or atomically creates a non-backed-up 256-bit key scoped to this installation. */
    @Synchronized
    @Throws(IOException::class)
    private fun getOrCreatePackageMarkerKey(): ByteArray {
        packageMarkerKey?.let { return it }
        val dir = noBackupFilesDir
        val keyFile = File(dir, PACKAGE_MARKER_KEY_FILE)
        val existing = readPackageMarkerKey(keyFile)
        if (existing != null) {
            packageMarkerKey = existing
            return existing
        }
        if (keyFile.exists() && !keyFile.delete()) {
            throw IOException("cannot replace invalid package marker key")
        }

        var generated = ByteArray(PACKAGE_MARKER_KEY_BYTES)
        SecureRandom().nextBytes(generated)
        val temp = File.createTempFile("nav_package_marker_", ".tmp", dir)
        var published = false
        try {
            FileOutputStream(temp).use { out ->
                out.write(generated)
                out.flush()
                out.fd.sync()
            }
            published = temp.renameTo(keyFile)
            if (!published) {
                val raced = readPackageMarkerKey(keyFile)
                        ?: throw IOException("cannot publish package marker key")
                generated = raced
            }
        } finally {
            if (!published) temp.delete()
        }
        packageMarkerKey = generated
        return generated
    }

    companion object {

        private const val TAG = "MapNavListener"

        private const val PACKAGE_MARKER_KEY_FILE = "nav_package_marker.key"
        private const val PACKAGE_MARKER_KEY_BYTES = 32

        private const val PKG_MAPS = "com.google.android.apps.maps"
        private const val PKG_MAPS_REVANCED = "app.revanced.android.apps.maps"
        private const val PKG_WAZE = "com.waze"

        /**
         * How recently a supported nav app must have been active for the bug reporter to accept
         * that a route was running. Deliberately generous: the wizard is normally used AFTER the
         * drive, so a tight window would wrongly accuse a driver who has just parked. A window
         * (rather than a latch-forever flag) also stops one route from making every later report
         * claim a route was live.
         */
        private const val NAV_RECENT_MS = 30L * 60L * 1000L

        /**
         * Per supported nav package: `{last guidance notification POSTED, last frame fully PARSED}`
         * as `elapsedRealtime`.
         *
         * Two separate timestamps because they answer different questions, and conflating them is
         * exactly what made the first version of this gate harmful:
         *  - **posted** — the nav app IS guiding (it only posts turn-by-turn while a route runs).
         *  - **parsed** — and DashCast understood it well enough to drive the HUD.
         *
         * "posted but never parsed" is a DashCast *parser* bug — the single most valuable "no arrow"
         * report there is — so it must never be gated away as "you did not start a route". Tracked
         * per package so a Waze parse failure is not masked by an earlier Google Maps route.
         *
         * Everything runs in one app process (no `android:process` in the manifest), so this is
         * shared with [com.byd.dashcast.report.BugWizardActivity].
         */
        private val sNavActivity = ConcurrentHashMap<String, LongArray>()

        /** A supported nav app recently posted AND we parsed it — a route is (or just was) running. */
        const val NAV_PARSED = "parsed"

        /** It posted guidance but nothing parsed — a real DashCast parser bug; never gate this away. */
        const val NAV_PARSE_FAIL = "parse-fail"

        /** Nothing recent at all — most likely no route was ever started. */
        const val NAV_NONE = "none"

        /**
         * Key of the notification currently driving the HUD, or null when none is.
         *
         * Written only after the guidance gate has accepted a frame, so it identifies the ONE
         * notification whose disappearance means the route ended. Null is treated as "unknown,
         * close anyway": a listener that reconnects mid-route has no key yet, and failing to close
         * a HUD is worse than closing one that was already stopping.
         */
        @Volatile private var sDrivingKey: String? = null

        /** Records that `pkg` posted a guidance notification, and whether we fully parsed it. */
        private fun noteNavActivity(pkg: String?, parsed: Boolean) {
            if (pkg == null) return
            val slot = sNavActivity.computeIfAbsent(pkg) { LongArray(2) }
            slot[if (parsed) 1 else 0] = SystemClock.elapsedRealtime()
        }

        /** Whether `pkg` is the app the driver named in the wizard ("maps"/"waze"; empty = any). */
        private fun matchesNavKey(navAppKey: String?, pkg: String): Boolean {
            if (navAppKey == null || navAppKey.isEmpty()) return true
            if ("waze" == navAppKey) return PKG_WAZE == pkg
            if ("maps" == navAppKey) return PKG_MAPS == pkg || PKG_MAPS_REVANCED == pkg
            return true
        }

        /** Newest {posted, parsed} pair across the packages matching `navAppKey`. */
        private fun newestFor(navAppKey: String?): LongArray {
            var posted = 0L
            var parsed = 0L
            for (e in sNavActivity.entries) {
                if (!matchesNavKey(navAppKey, e.key)) continue
                posted = maxOf(posted, e.value[0])
                parsed = maxOf(parsed, e.value[1])
            }
            return longArrayOf(posted, parsed)
        }

        /**
         * What we have recently seen from the nav app the driver says they use — [NAV_PARSED],
         * [NAV_PARSE_FAIL] or [NAV_NONE]. Only `NAV_NONE` means "no route running".
         */
        @JvmStatic
        fun recentNavStatus(navAppKey: String?): String {
            val now = SystemClock.elapsedRealtime()
            val t = newestFor(navAppKey)
            if (t[1] != 0L && now - t[1] <= NAV_RECENT_MS) return NAV_PARSED
            if (t[0] != 0L && now - t[0] <= NAV_RECENT_MS) return NAV_PARSE_FAIL
            return NAV_NONE
        }

        /** Compact caption line for triage, e.g. `"yes (3m ago)"` / `"parse-fail (12s ago)"`. */
        @JvmStatic
        fun navSeenSummary(navAppKey: String?): String {
            val now = SystemClock.elapsedRealtime()
            val t = newestFor(navAppKey)
            if (t[1] != 0L && now - t[1] <= NAV_RECENT_MS) return "yes (" + ago(now - t[1]) + ")"
            if (t[0] != 0L && now - t[0] <= NAV_RECENT_MS) return "parse-fail (" + ago(now - t[0]) + ")"
            val newest = maxOf(t[0], t[1])
            // "stale" ≠ "never": tells triage a route ran, just not recently enough to explain this report.
            if (newest != 0L) return "stale (" + ago(now - newest) + ")"
            return "no"
        }

        private fun ago(ms: Long): String {
            val s = ms / 1000L
            return if (s < 90L) (s.toString() + "s ago") else ((s / 60L).toString() + "m ago")
        }

        // ─── Distance: "300 m", "1.2 km", "1,2 km", "500 m", "3 км" ─
        // Mirrors OpenBYD regex: \b(\d+[.,]?\d*)[\s ]*(km|км|m|м)\b
        // Handles ASCII and Cyrillic unit suffixes, optional decimal, optional NBSP.
        // IMPERIAL + "mt" added 1.6.144: the metric-only pattern meant that in a UK/US locale (Maps and
        // Waze post "in 500 ft" / "0.5 mi") NO frame of an entire route could ever parse, so the HUD arrow
        // never worked there at all — and the 1.6.143 gate then told those drivers they had not started a
        // route. Italian "300 mt" failed the same way. Alternation is longest-first so "mi"/"mt" are tried
        // before "m"; "25 min" still cannot match (the trailing \b fails on "min" for every alternative),
        // so remaining-time text is never misread as a distance.
        // PORTABILITY (2026-09) — \b replaced by explicit lookarounds in RX_DIST / RX_HOURS / RX_MINS.
        //
        // \b is defined on ASCII word chars, so a boundary that follows a non-ASCII unit token
        // (Arabic "م", Cyrillic "м") is engine-dependent. Proven with the identical pattern on two JVMs:
        // JDK 17 matched "400 م" and "300 м"; JDK 21 matched NEITHER, while ASCII "300 m" matched on
        // both. That is not a formatting nuance - the parser returns nothing, and an arrow with no
        // distance is the worst state for a driver.
        //
        // (?<![\p{L}\p{N}]) ... (?![\p{L}\p{N}]) says exactly what \b was meant to say here and
        // gives the same answer on both JVMs. It deliberately does NOT use UNICODE_CHARACTER_CLASS,
        // which would also make \d match Arabic-Indic digits directly and bypass normaliseDigits().
        // The "25 min must not parse as a distance" guarantee above is preserved: after "m" the next
        // char is "i", a letter, so the lookahead fails exactly as the trailing \b did.
        private val RX_DIST: Pattern =
                Pattern.compile("(?<![\\p{L}\\p{N}])(\\d+[.,]?\\d*)[\\s\\u00A0]*(km|км|كم|mi|ft|yd|mt|m|м|م)(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE)

        // ─── Road name: "onto X", "sur X", "on X" ────────────────────────────
        private val RX_ROAD_ONTO: Pattern =
                Pattern.compile("(?:onto|on|sur|vers)\\s+(.+?)(?:\\s+in\\s+\\d|\\s+dans\\s+\\d|\$)",
                        Pattern.CASE_INSENSITIVE)

        // ─── Remaining time — two-pattern approach matching OpenBYD smali ─────
        // Hours: \b(\d+)[\s ]*(?:h|hr|hrs|hour|hours|ч|ч\.)\b
        // Mins:  \b(\d+)[\s ]*(?:min|mins|мин)\b
        // Both handle ASCII and Cyrillic units plus non-breaking space.
        private val RX_HOURS: Pattern =
                Pattern.compile("(?<![\\p{L}\\p{N}])(\\d+)[\\s\\u00A0]*(?:h|hr|hrs|hour|hours|ч|ч\\.|ساعة|س)(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE)
        private val RX_MINS: Pattern =
                Pattern.compile("(?<![\\p{L}\\p{N}])(\\d+)[\\s\\u00A0]*(?:min|mins|мин|دقيقة|د)(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE)

        // ─── Arrival wall-clock ETA — "· 14:32", "2:05 pm" (OEM EXPECTED_ARRIVE_*) ─
        // Best-effort: a bare HH:MM in a nav notification is the arrival clock — remaining DURATION here
        // is always word-unit ("25 min", "1 hr"), never colon-separated. g1=hour, g2=minute, g3=am/pm.
        private val RX_ETA_CLOCK: Pattern =
                Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\s*(am|pm)?\\b",
                        Pattern.CASE_INSENSITIVE)
            private val RX_ETA_MARKER: Pattern = Pattern.compile(
                "(?iu)(?<![\\p{L}\\p{N}])(?:eta|arriv(?:e|al|ée?|ee|o|ato|ata)|llegada|ankunft|"
                    + "прибыт\\p{L}*|прибут\\p{L}*|прыбы\\p{L}*|przyjazd|varış|varis|"
                    + "الوصول|келу|yetib)(?![\\p{L}\\p{N}])")

        // ─── Roundabout exit number — "3rd exit", "take the 2nd exit", "sortie 3" ─
        private val RX_ROUNDABOUT_EXIT: Pattern =
                Pattern.compile(
                        "(?:(\\d+)(?:st|nd|rd|th|er|e|ème)?[\\s\\u00A0]+(?:exit|sortie)"
                                + "|(?:exit|sortie)[\\s\\u00A0]+(\\d+))",
                        Pattern.CASE_INSENSITIVE)

        // ─── Noise-notification skip strings (from OpenBYD smali) ────────────
        // Matched (lowercase) against both title and text. These appear in ongoing
        // navigation notifications that carry no maneuver data (GPS acquiring, etc.).
        private val SKIP_STRINGS = arrayOf(
            "tap to open",
            "running in the background",
            "app is running",
            "searching for gps",
            "gps signal lost",
        )

        // ─── Google Maps icon resource name → BYD turn icon ID ───────────────
        // Names observed across Maps versions 10.x–11.x (as of 2024-2025).
        // Partial match is used (contains check) so minor version suffixes don't break it.
        private val ICON_NAME_MAP = arrayOf(
            arrayOf<Any>("arrow_right", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("arrow_left", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("slight_right", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("slight_left", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("sharp_right", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("sharp_left", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("u_turn_right", CanBusController.ICON_U_TURN_RIGHT),
            arrayOf<Any>("u_turn_left", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("u_turn", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("uturn_right", CanBusController.ICON_U_TURN_RIGHT),
            arrayOf<Any>("uturn_left", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("straight", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("continue", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("roundabout_cw", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("roundabout_ccw", CanBusController.ICON_ROUNDABOUT_CCW_1_LAP),
            arrayOf<Any>("roundabout", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("destination", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("arrive", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("finish", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("merge_right", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("merge_left", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("merge", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("ramp_right", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("ramp_left", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("fork_right", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("fork_left", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("exit_right", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("exit_left", CanBusController.ICON_DETOUR_LEFT),
            arrayOf<Any>("tollbooth", CanBusController.ICON_TOLLBOOTH),
            arrayOf<Any>("tunnel", CanBusController.ICON_TUNNEL),
        )

        // ─── Text keyword → BYD turn icon ID (EN + FR + DE) ─────────────────
        // Evaluated in order; first match wins. More specific patterns come first.
        // All keywords are lowercase — matched against combined.toLowerCase(Locale.ROOT).
        private val TEXT_KEYWORD_MAP = arrayOf(
            // Destination / arrival (must be before generic "right"/"left")
            arrayOf<Any>("you have arrived", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("you've arrived", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("vous êtes arrivé", CanBusController.ICON_DESTINATION), // "Vous êtes arrivé(e)"
            arrayOf<Any>("destination", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("sie haben ihr ziel", CanBusController.ICON_DESTINATION), // DE
            // U-turn (most specific first — must precede plain "right"/"left")
            arrayOf<Any>("u-turn right", CanBusController.ICON_U_TURN_RIGHT),
            arrayOf<Any>("u-turn left", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("u-turn", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("uturn", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("faites demi-tour", CanBusController.ICON_U_TURN_LEFT), // exact Google Maps FR
            arrayOf<Any>("demi-tour", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("kehren sie um", CanBusController.ICON_U_TURN_LEFT), // DE
            // Sharp (before slight/standard to avoid substring matches)
            arrayOf<Any>("sharp right", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("sharp left", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("virez fortement à droite", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("virez fortement à gauche", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("scharf rechts", CanBusController.ICON_SHARP_RIGHT), // DE
            arrayOf<Any>("scharf links", CanBusController.ICON_SHARP_LEFT), // DE
            // Slight / keep (before standard turns)
            arrayOf<Any>("slight right", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("slight left", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("légèrement à droite", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("légèrement à gauche", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("keep right", CanBusController.ICON_SLIGHT_RIGHT_ALT),
            arrayOf<Any>("keep left", CanBusController.ICON_SLIGHT_LEFT_ALT),
            arrayOf<Any>("restez à droite", CanBusController.ICON_SLIGHT_RIGHT_ALT),
            arrayOf<Any>("restez à gauche", CanBusController.ICON_SLIGHT_LEFT_ALT),
            arrayOf<Any>("halbrechts", CanBusController.ICON_SLIGHT_RIGHT), // DE
            arrayOf<Any>("halblinks", CanBusController.ICON_SLIGHT_LEFT), // DE
            // Standard turns
            arrayOf<Any>("turn right", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("turn left", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("tournez à droite", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("tournez à gauche", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("rechts abbiegen", CanBusController.ICON_TURN_RIGHT), // DE
            arrayOf<Any>("links abbiegen", CanBusController.ICON_TURN_LEFT), // DE
            // Roundabout — BEFORE the exit block, and this order is load-bearing.
            //
            // The lookup is first-match-wins over this array (see the loop that scans it), and a real
            // roundabout instruction almost always names an exit: "At the roundabout, take the 3rd
            // exit", "Au rond-point, prenez la 3e sortie", "Im Kreisverkehr, nehmen Sie die 3.
            // Ausfahrt". With "exit"/"sortie"/"ausfahrt" tested first, every one of those drew a
            // motorway-exit arrow on the instrument cluster instead of a roundabout — the wrong glyph,
            // in front of a driver, at the moment they need it. The same specificity rule the rest of
            // this table already documents ("most specific first", "must not shadow more specific
            // keys") simply had not been applied to this pair.
            arrayOf<Any>("roundabout", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("rond-point", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("kreisverkehr", CanBusController.ICON_ROUNDABOUT_CW_1_LAP), // DE
            // Exit / ramp
            arrayOf<Any>("exit right", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("exit left", CanBusController.ICON_DETOUR_LEFT),
            arrayOf<Any>("take the exit", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("exit", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("prenez la sortie", CanBusController.ICON_DETOUR_RIGHT), // "Prenez la sortie n°5"
            arrayOf<Any>("sortie", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("ausfahrt", CanBusController.ICON_DETOUR_RIGHT), // DE
            // Merge / ramp
            arrayOf<Any>("merge right", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("merge left", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("merge", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("rejoin", CanBusController.ICON_SLIGHT_RIGHT), // "rejoindre" and "rejoignez"
            // Straight / continue (last — very generic, must not shadow more specific keys)
            arrayOf<Any>("head north", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("head south", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("head east", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("head west", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("head toward", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("continue straight", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("continue", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("continuez tout droit", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("continuez", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("tout droit", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("straight", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("geradeaus", CanBusController.ICON_STRAIGHT_SOLID), // DE
            // Tollbooth / tunnel
            arrayOf<Any>("tollbooth", CanBusController.ICON_TOLLBOOTH),
            arrayOf<Any>("péage", CanBusController.ICON_TOLLBOOTH),
            arrayOf<Any>("maut", CanBusController.ICON_TOLLBOOTH), // DE
            arrayOf<Any>("tunnel", CanBusController.ICON_TUNNEL),
            arrayOf<Any>("galleria", CanBusController.ICON_TUNNEL), // IT (tunnel in road)

            // ── ES (Spanish) ─────────────────────────────────────────────────
            arrayOf<Any>("has llegado a tu destino", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("llegaste a tu destino", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("da media vuelta a la derecha", CanBusController.ICON_U_TURN_RIGHT),
            arrayOf<Any>("da media vuelta a la izquierda", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("da media vuelta", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("gira bruscamente a la derecha", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("gira bruscamente a la izquierda", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("dobla levemente a la derecha", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("dobla levemente a la izquierda", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("mantente a la derecha", CanBusController.ICON_SLIGHT_RIGHT_ALT),
            arrayOf<Any>("mantente a la izquierda", CanBusController.ICON_SLIGHT_LEFT_ALT),
            arrayOf<Any>("gira a la derecha", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("dobla a la derecha", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("gira a la izquierda", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("dobla a la izquierda", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("toma la salida a la derecha", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("toma la salida a la izquierda", CanBusController.ICON_DETOUR_LEFT),
            arrayOf<Any>("toma la salida", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("salida", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("incorpórate", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("glorieta", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("continúa recto", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("continúa", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("sigue recto", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("peaje", CanBusController.ICON_TOLLBOOTH),
            arrayOf<Any>("túnel", CanBusController.ICON_TUNNEL),

            // ── IT (Italian) ─────────────────────────────────────────────────
            arrayOf<Any>("sei arrivato a destinazione", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("hai raggiunto la destinazione", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("fai inversione", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("inversione a u", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("svolta nettamente a destra", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("svolta nettamente a sinistra", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("svolta leggermente a destra", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("svolta leggermente a sinistra", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("mantieni la destra", CanBusController.ICON_SLIGHT_RIGHT_ALT),
            arrayOf<Any>("mantieni la sinistra", CanBusController.ICON_SLIGHT_LEFT_ALT),
            arrayOf<Any>("svolta a destra", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("svolta a sinistra", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("prendi l'uscita a destra", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("prendi l'uscita a sinistra", CanBusController.ICON_DETOUR_LEFT),
            arrayOf<Any>("prendi l'uscita", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("uscita", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("immettiti", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("rotatoria", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("prosegui dritto", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("continua dritto", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("casello", CanBusController.ICON_TOLLBOOTH),
            arrayOf<Any>("pedaggio", CanBusController.ICON_TOLLBOOTH),

            // ── RU (Russian) ─────────────────────────────────────────────────
            arrayOf<Any>("вы достигли места назначения", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("вы прибыли", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("развернитесь", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("сделайте разворот", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("круто поверните направо", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("круто поверните налево", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("немного поверните направо", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("немного поверните налево", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("держитесь правее", CanBusController.ICON_SLIGHT_RIGHT_ALT),
            arrayOf<Any>("держитесь левее", CanBusController.ICON_SLIGHT_LEFT_ALT),
            arrayOf<Any>("поверните направо", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("поверните налево", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("кольцевая развязка", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("езжайте прямо", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("продолжайте движение", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("пункт оплаты", CanBusController.ICON_TOLLBOOTH),
            arrayOf<Any>("тоннель", CanBusController.ICON_TUNNEL),

            // ── PL (Polish) ──────────────────────────────────────────────────
            arrayOf<Any>("dotarłeś do celu", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("jesteś na miejscu", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("zawróć", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("ostry skręt w prawo", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("ostry skręt w lewo", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("łagodny skręt w prawo", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("łagodny skręt w lewo", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("trzymaj się prawej", CanBusController.ICON_SLIGHT_RIGHT_ALT),
            arrayOf<Any>("trzymaj się lewej", CanBusController.ICON_SLIGHT_LEFT_ALT),
            arrayOf<Any>("skręć w prawo", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("skręć w lewo", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("włącz się do ruchu", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("rondo", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("jedź prosto", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("myto", CanBusController.ICON_TOLLBOOTH),
            arrayOf<Any>("tunel", CanBusController.ICON_TUNNEL),

            // ── UK (Ukrainian) ───────────────────────────────────────────────
            arrayOf<Any>("ви досягли пункту призначення", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("ви прибули", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("розгорніться", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("різко поверніть праворуч", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("різко поверніть ліворуч", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("трохи поверніть праворуч", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("трохи поверніть ліворуч", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("тримайтесь правіше", CanBusController.ICON_SLIGHT_RIGHT_ALT),
            arrayOf<Any>("тримайтесь лівіше", CanBusController.ICON_SLIGHT_LEFT_ALT),
            arrayOf<Any>("поверніть праворуч", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("поверніть ліворуч", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("кільцева розв'язка", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("їдьте прямо", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("продовжуйте рух", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("тунель", CanBusController.ICON_TUNNEL),

            // ── BE (Belarusian) ──────────────────────────────────────────────
            arrayOf<Any>("вы дасягнулі месца прызначэння", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("разгарніцеся", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("паварачвайце направа", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("паварачвайце налева", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("кальцавая развязка", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("едзьце прама", CanBusController.ICON_STRAIGHT_SOLID),

            // ── TR (Turkish) ─────────────────────────────────────────────────
            arrayOf<Any>("hedefinize ulaştınız", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("u dönüşü yapın", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("geri dönün", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("sert sağa dönün", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("sert sola dönün", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("hafifçe sağa dönün", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("hafifçe sola dönün", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("sağ şeritte kalın", CanBusController.ICON_SLIGHT_RIGHT_ALT),
            arrayOf<Any>("sol şeritte kalın", CanBusController.ICON_SLIGHT_LEFT_ALT),
            arrayOf<Any>("sağa dönün", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("sola dönün", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("çıkışı alın", CanBusController.ICON_DETOUR_RIGHT),
            arrayOf<Any>("dönel kavşak", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("düz gidin", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("ücretli gişe", CanBusController.ICON_TOLLBOOTH),
            arrayOf<Any>("tünel", CanBusController.ICON_TUNNEL),

            // ── AR (Arabic) ──────────────────────────────────────────────────
            arrayOf<Any>("لقد وصلت إلى وجهتك", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("وصلت إلى وجهتك", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("استدر", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("انعطف بشدة يمينًا", CanBusController.ICON_SHARP_RIGHT),
            arrayOf<Any>("انعطف بشدة يسارًا", CanBusController.ICON_SHARP_LEFT),
            arrayOf<Any>("انعطف قليلاً يمينًا", CanBusController.ICON_SLIGHT_RIGHT),
            arrayOf<Any>("انعطف قليلاً يسارًا", CanBusController.ICON_SLIGHT_LEFT),
            arrayOf<Any>("التزم اليمين", CanBusController.ICON_SLIGHT_RIGHT_ALT),
            arrayOf<Any>("التزم اليسار", CanBusController.ICON_SLIGHT_LEFT_ALT),
            arrayOf<Any>("اتجه يمينًا", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("اتجه يسارًا", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("دوار", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("سر مستقيمًا", CanBusController.ICON_STRAIGHT_SOLID),
            arrayOf<Any>("رسوم", CanBusController.ICON_TOLLBOOTH),
            arrayOf<Any>("نفق", CanBusController.ICON_TUNNEL),

            // ── KK (Kazakh) ──────────────────────────────────────────────────
            arrayOf<Any>("сіз межеңізге жеттіңіз", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("артқа бұрылыңыз", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("оңға бұрылыңыз", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("солға бұрылыңыз", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("кольцелік қиылыс", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("тура жүріңіз", CanBusController.ICON_STRAIGHT_SOLID),

            // ── UZ (Uzbek) ───────────────────────────────────────────────────
            arrayOf<Any>("manzilga yetib keldingiz", CanBusController.ICON_DESTINATION),
            arrayOf<Any>("orqaga buriling", CanBusController.ICON_U_TURN_LEFT),
            arrayOf<Any>("o'ngga buriling", CanBusController.ICON_TURN_RIGHT),
            arrayOf<Any>("chapga buriling", CanBusController.ICON_TURN_LEFT),
            arrayOf<Any>("aylana chorraha", CanBusController.ICON_ROUNDABOUT_CW_1_LAP),
            arrayOf<Any>("to'g'ri boring", CanBusController.ICON_STRAIGHT_SOLID),
        )
        /** A partial frame still proves that navigation is active, but must not drive the HUD. */
        internal fun hasGuidanceSignal(iconId: Int, distanceMeters: Int): Boolean =
                iconId > 0 || distanceMeters > 0

        /** Direction and distance must both be known before emitting driver-facing guidance. */
        internal fun isCompleteGuidance(iconId: Int, distanceMeters: Int): Boolean =
                iconId > 0 && distanceMeters >= 0

        internal fun isSameNotificationContent(
                key: String?, previousKey: String?,
                title: String, previousTitle: String,
                text: String, previousText: String,
                bigText: String, previousBigText: String,
                subText: String, previousSubText: String): Boolean =
                Objects.equals(key, previousKey) &&
                        title == previousTitle &&
                        text == previousText &&
                        bigText == previousBigText &&
                        subText == previousSubText

        /** Same eligibility rule for posting and removal, including non-ongoing nav categories. */
        internal fun isNavigationNotification(notification: Notification?): Boolean =
                notification != null &&
                        ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
                                Notification.CATEGORY_NAVIGATION == notification.category)

        internal fun remainingNavigationNotifications(
                // `out …?` keeps the Java's null-element guard meaningful (the framework
                // array can hold nulls) while still accepting the test's arrayOf() of
                // non-null notifications.
                active: Array<out StatusBarNotification?>?,
                removedKey: String?): List<StatusBarNotification> {
            val candidates = ArrayList<StatusBarNotification>()
            if (active == null) return candidates
            for (notification in active) {
                if (notification == null || Objects.equals(removedKey, notification.key)) continue
                if (!isNavPackage(notification.packageName)) continue
                if (!isNavigationNotification(notification.notification)) continue
                candidates.add(notification)
            }
            candidates.sortWith { left, right -> right.postTime.compareTo(left.postTime) }
            return candidates
        }

        /**
         * internal, not private, so NavKeywordOrderTest can drive it. The order of
         * TEXT_KEYWORD_MAP is load-bearing — first match wins — and an ordering mistake there puts
         * the wrong arrow on a driver's instrument cluster with nothing anywhere to say so.
         */
        internal fun resolveIconFromText(lower: String): Int {
            for (entry in TEXT_KEYWORD_MAP) {
                if (lower.contains(entry[0] as String)) return entry[1] as Int
            }
            return -1
        }

        // ─── Distance parsing ─────────────────────────────────────────────────

        /**
         * Maps Arabic-Indic digits onto ASCII, leaving everything else untouched.
         *
         * Two ranges, because both are in use: U+0660-U+0669 (Arabic-Indic) and U+06F0-U+06F9
         * (Extended Arabic-Indic, Persian/Urdu). Cheap enough to run on every notification — one
         * scan of a short string, allocating only when it actually finds a digit to convert.
         */
        internal fun normaliseDigits(s: String?): String? {
            if (s == null || s.isEmpty()) return s
            var sb: StringBuilder? = null
            for (i in s.indices) {
                val c = s[i]
                var v = -1
                if (c >= '٠' && c <= '٩') v = c - '٠'
                else if (c >= '۰' && c <= '۹') v = c - '۰'
                if (v >= 0) {
                    if (sb == null) sb = StringBuilder(s)
                    sb.setCharAt(i, ('0' + v))
                }
            }
            return sb?.toString() ?: s
        }

        @JvmStatic
        private fun parseFirstDistance(text: String): Int = parseMeters(text, -1)

        /** Returns the first distance found (in metres) or null. */
        @JvmStatic
        private fun parseRemainingMeters(text: String?): Int? {
            if (text == null || text.isEmpty()) return null
            val v = parseMeters(text, Int.MIN_VALUE)
            return if (v == Int.MIN_VALUE) null else v
        }

        /**
         * Core distance parser matching the OpenBYD smali regex. Unit determines conversion;
         * returns the `notFound` sentinel on failure.
         */
        @JvmStatic
        private fun parseMeters(text: String, notFound: Int): Int {
            val m = RX_DIST.matcher(text)
            if (!m.find()) return notFound
            val unit = m.group(2)!!.lowercase(Locale.ROOT)
            return try {
                val v = m.group(1)!!.replace(',', '.').toFloat()
                when (unit) {
                    "km", "км" -> Math.round(v * 1000f)
                    "mi" -> Math.round(v * 1609.344f)   // statute mile
                    "ft" -> Math.round(v * 0.3048f)
                    "yd" -> Math.round(v * 0.9144f)
                    else -> Math.round(v)               // m / м / mt
                }
            } catch (ignore: NumberFormatException) {
                notFound
            }
        }

        /**
         * Parses remaining time using two separate patterns (hours + minutes), matching the OpenBYD
         * smali approach. Supports "12 min", "1h 5m", "1 h 05 min", "45 мин", "2 ч 10 мин".
         * Returns null if nothing found.
         */
        @JvmStatic
        private fun parseRemainingSeconds(text: String?): Int? {
            if (text == null || text.isEmpty()) return null
            var totalSeconds = 0
            var found = false
            val mh = RX_HOURS.matcher(text)
            if (mh.find()) {
                try { totalSeconds += mh.group(1)!!.toInt() * 3600; found = true }
                catch (ignore: NumberFormatException) {}
            }
            val mm = RX_MINS.matcher(text)
            if (mm.find()) {
                try { totalSeconds += mm.group(1)!!.toInt() * 60; found = true }
                catch (ignore: NumberFormatException) {}
            }
            return if (found) totalSeconds else null
        }

        // ─── Road name ────────────────────────────────────────────────────────

        @JvmStatic
        private fun parseRoadName(text: String): String {
            val m = RX_ROAD_ONTO.matcher(text)
            if (m.find()) {
                val name = m.group(1)
                return name?.trim() ?: ""
            }
            return ""
        }

        // ─── Arrival ETA clock ────────────────────────────────────────────────

        /** Parses an arrival wall-clock "HH:MM" (optionally am/pm) → {hour24, minute}, or null. */
        @JvmStatic
        private fun parseEtaClock(text: String?): IntArray? {
            if (text == null) return null
            val m = RX_ETA_CLOCK.matcher(text)
            val candidates = ArrayList<EtaClockCandidate>()
            while (m.find()) {
                try {
                    var h = m.group(1)!!.toInt()
                    val min = m.group(2)!!.toInt()
                    var ap = m.group(3)
                    if (ap != null) {
                        ap = ap.lowercase(Locale.ROOT)
                        if (ap == "pm" && h < 12) h += 12
                        else if (ap == "am" && h == 12) h = 0
                    }
                    if (h in 0..23 && min in 0..59) {
                        candidates.add(EtaClockCandidate(h, min, m.start(), m.end()))
                    }
                } catch (ignored: NumberFormatException) { }
            }
            if (candidates.isEmpty()) return null
            if (candidates.size == 1) return candidates[0].value()

            var closest: EtaClockCandidate? = null
            var closestGap = Int.MAX_VALUE
            val marker = RX_ETA_MARKER.matcher(text)
            while (marker.find()) {
                for (candidate in candidates) {
                    val gap = if (candidate.end <= marker.start())
                        marker.start() - candidate.end
                    else if (candidate.start >= marker.end())
                        candidate.start - marker.end()
                    else 0
                    if (gap < closestGap) {
                        closest = candidate
                        closestGap = gap
                    }
                }
            }
            // Summary layouts without an arrival label conventionally place ETA last. Keeping that
            // fallback preserves bare vendor formats while no longer mistaking a leading duration.
            return (closest ?: candidates[candidates.size - 1]).value()
        }

        private class EtaClockCandidate(
            val hour: Int,
            val minute: Int,
            val start: Int,
            val end: Int
        ) {
            fun value(): IntArray = intArrayOf(hour, minute)
        }

        // ─── Roundabout exit ──────────────────────────────────────────────────

        /** Parses a roundabout exit number (1-10) from the instruction text, or 0 if none/out of range. */
        @JvmStatic
        private fun parseRoundaboutExit(lower: String?): Int {
            if (lower == null) return 0
            val m = RX_ROUNDABOUT_EXIT.matcher(lower)
            if (!m.find()) return 0
            val g = m.group(1) ?: m.group(2)
            return try {
                val n = g!!.toInt()
                if (n in 1..10) n else 0
            } catch (e: NumberFormatException) {
                0
            }
        }

        // ─── Helpers ──────────────────────────────────────────────────────────

        private fun isNavPackage(pkg: String?): Boolean =
                PKG_MAPS == pkg || PKG_MAPS_REVANCED == pkg || PKG_WAZE == pkg

        // Known navigation apps we do NOT support: their guidance is not exposed as a parseable
        // notification (e.g. Telenav delivers binary NaviInfo AIDL, not text). Prefix match on the pkg.
        // Public + single source of truth so the bug reporter can also probe the running-process list and
        // self-diagnose a "no arrow on HUD" report even when the unsupported nav posts NO notification at
        // all — Telenav is the archetype: it never triggers the notification-driven maybeLogUnsupportedNav
        // below, so before the presence probe such reports were mute about the real culprit
        // (INC-20260718-114114).
        @JvmField
        val KNOWN_UNSUPPORTED_NAV = arrayOf(
            "com.telenav",              // Telenav (EU OEM nav: .app.arp / .app.isa)
            "com.sealnav",              // BYD Seal nav
            "com.autonavi", "com.amap", // AMap / AutoNavi
            "com.baidu.baidumaps",      // Baidu Maps
            "ru.yandex.yandexnavi", "ru.yandex.yandexmaps"  // Yandex
        )

        /**
         * Scans a `ps -A`-style process listing and returns the first running process whose name
         * matches a [KNOWN_UNSUPPORTED_NAV] prefix, or `null` if none is running. Lets the bug
         * reporter record which unsupported OEM nav (if any) is live when a HUD "no arrow" report is
         * filed — because such navs post no notification, this presence probe is the only way the
         * report can name the culprit. Matches whole whitespace-delimited tokens so a substring
         * can't false-hit.
         */
        @JvmStatic
        fun firstUnsupportedNavProcess(procListing: String?): String? {
            if (procListing == null) return null
            for (line in procListing.split(Regex("\\R"))) {
                for (tok in line.trim().split(Regex("\\s+"))) {
                    for (p in KNOWN_UNSUPPORTED_NAV) {
                        if (tok.startsWith(p)) return tok
                    }
                }
            }
            return null
        }

        private fun readPackageMarkerKey(keyFile: File): ByteArray? {
            if (!keyFile.isFile || keyFile.length() != PACKAGE_MARKER_KEY_BYTES.toLong()) return null
            val key = ByteArray(PACKAGE_MARKER_KEY_BYTES)
            try {
                FileInputStream(keyFile).use { input ->
                    var offset = 0
                    while (offset < key.size) {
                        val read = input.read(key, offset, key.size - offset)
                        if (read < 0) return null
                        offset += read
                    }
                    return if (input.read() == -1) key else null
                }
            } catch (e: IOException) {
                return null
            }
        }

        private fun charSeqToString(cs: CharSequence?): String = cs?.toString() ?: ""

        /** One-line, length-bounded form of a notification string for the NAV PARSE diagnostic. */
        private fun clip(s: String?): String {
            if (s == null) return ""
            val t = s.replace('\n', ' ').trim()
            return if (t.length > 80) t.substring(0, 80) + "…" else t
        }
    }
}
