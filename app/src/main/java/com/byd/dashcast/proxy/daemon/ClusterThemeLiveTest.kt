package com.byd.dashcast.proxy.daemon

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeProtocol
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.clusterlive.ClusterCornerVerification
import com.byd.dashcast.clusterlive.ClusterThemeTransport
import com.byd.dashcast.clusterlive.ClusterThemeJournal
import com.byd.dashcast.clusterlive.ClusterThemeRetirement
import com.byd.dashcast.clusterlive.ClusterRendererDiscovery
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/** Closed experiments sharing one ProxyDaemon journal/recovery lane. No OEM file is modified.
 * Status/restore calls do not wait on the command worker. A new daemon never replays a journal.
 */
internal object ClusterThemeLiveTest {
    private val directory = File("/data/local/tmp/dashcast-cluster-live")
    private val journal = File(directory, "journal.json")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cluster-live-command").apply { isDaemon = true } }
    private val nativeMode = ClusterNativeModeTest(worker)
    private var productionModeCommands = 0
    @Synchronized fun beginProductionModeCommand() {
        initialize()
        check(!nativeMode.blocked() && !active.get() && !queryActive.get()) { "NATIVE_CLUSTER_DIAGNOSTIC_ACTIVE: native/RCC diagnostic recovery required" }
        productionModeCommands++
    }
    @Synchronized fun endProductionModeCommand() { check(productionModeCommands > 0); productionModeCommands-- }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cluster-live-deadline").apply { isDaemon = true } }
    private val generation = AtomicLong(0)
    private val restoring = AtomicLong(-1)
    private val observationPending = AtomicInteger(0)
    private val restoreRequested = AtomicBoolean(false)
    private val dashboardPending = AtomicBoolean(false)
    private val dashboardCloseQueued = AtomicBoolean(false)
    @Volatile private var dashboardStage = "NONE"
    @Volatile private var dashboardNativeDebt = false
    @Volatile private var dashboardHelperCommand = -1
    @Volatile private var dashboardHelperPid = 0
    @Volatile private var dashboardHelperStart = ""
    @Volatile private var dashboardHelperToken = ""
    private val dashboardIntervals = com.byd.dashcast.clusterlive.ClusterDashboardIntervals(SystemClock::elapsedRealtime, Thread::sleep)
    @Volatile private var dashboardSelectionReady = false
    @Volatile private var dashboardRound = ""
    // Conservative even after a daemon replacement. Any recovered helper is
    // reaped before another interval starts; no wall-clock inference is used.
    private val active = AtomicBoolean(false)
    private val queryActive = AtomicBoolean(false)
    @Volatile private var oemQuery = JSONObject()
    @Volatile private var state = "IDLE"
    @Volatile private var detail = "No physical test started."
    @Volatile private var filename = ""
    @Volatile private var renderer = ""
    @Volatile private var identity = ""
    @Volatile private var root = ""
    @Volatile private var deadline = 0L
    @Volatile private var mutated = false
    @Volatile private var uncertain = false
    @Volatile private var loadSettled = false
    @Volatile private var iviRecoverySettled = false
    @Volatile private var defaultQueried = false
    @Volatile private var markerObserved = false
    @Volatile private var themeObserved = false
    @Volatile private var variant = ClusterThemeVariant.MARKER
    @Volatile private var phase = "idle"
    @Volatile private var oemObserved = false
    @Volatile private var carrierRemoved = false
    @Volatile private var raw = ""
    @Volatile private var initialized = false
    @Volatile private var createdDevice = -1L
    @Volatile private var createdInode = -1L
    @Volatile private var helperUnreaped = false
    @Volatile private var journalTrusted = true
    @Volatile private var helperPid = 0
    @Volatile private var helperStart = ""
    @Volatile private var helperToken = ""
    @Volatile private var hashDeadline = Long.MAX_VALUE
    @Volatile private var transport = ClusterThemeTransport.STRICT_RENDERER
    @Volatile private var projectionValidity: android.os.IBinder? = null
    @Volatile private var retirement: ClusterThemeRetirement? = null
    @Volatile private var retirementEligibleUntil = 0L
    @Volatile private var loadRequestReceipt = "NOT_RECORDED"
    @Volatile private var loadSelectionReceipt = "NOT_RECORDED"
    @Volatile private var loadRegistrationReceipt = "NOT_RECORDED"
    @Volatile private var cornerVerification: ClusterCornerVerification? = null
    private val trace = ArrayDeque<String>()
    @Synchronized private fun step(stage: String, extra: String = "") {
        val line = "epochMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} transport=${transport.id} nonce=${filename.removePrefix(ClusterThemePolicy.PREFIX).removeSuffix(".rcc")} stage=$stage ${extra.take(320)}"
        trace.addLast(line)
        while (trace.size > 48 || trace.sumOf { it.length } > 8192) trace.removeFirst()
        ProxyDaemonMain.log("ClusterRcc $line")
    }
    private fun validProjection(): Boolean {
        val binder = projectionValidity ?: return false
        if (!binder.isBinderAlive) return false
        val d = android.os.Parcel.obtain(); val r = android.os.Parcel.obtain()
        return try {
            d.writeInterfaceToken("com.byd.dashcast.RccReservation")
            binder.transact(1, d, r, 0) && run { r.readException(); r.dataAvail() == 4 && r.readInt() == 1 }
        } catch (_: Exception) { false } finally { r.recycle(); d.recycle() }
    }

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            if (runCatching { Os.lstat(journal.path); true }.getOrElse { if (it is ErrnoException && it.errno == OsConstants.ENOENT) false else throw it }) {
                // Never execute anything from a recovered journal. Only the fixed owned name is accepted.
                runCatching {
                    check(journal.length() <= 32768)
                    val journalFd = Os.open(journal.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
                    val j = FileInputStream(journalFd).use { input ->
                        val st = Os.fstat(input.fd)
                        check(OsConstants.S_ISREG(st.st_mode) && st.st_uid == Process.myUid() && st.st_size in 1..32768)
                        JSONObject(input.readBytes().toString(Charsets.UTF_8))
                    }
                    val saved = ClusterThemeJournal.recover(j)
                    applyRecoveredJournal(saved)
                }.onFailure {
                    journalTrusted = false; active.set(true); uncertain = true; state = "QUARANTINE"
                    detail = "Unreadable/incompatible previous journal; no new test or automatic action: ${it.message}".take(2000)
                }
            }
            initialized = true
            watchdog.scheduleWithFixedDelay({
                watchdogTick()
            }, 1, 1, TimeUnit.SECONDS)
        }
    }

    @Synchronized internal fun watchdogTick(now: Long = SystemClock.elapsedRealtime()) {
        if (active.get() && deadline > 0 && now >= deadline && !(variant.isPersistent && mutated))
            requestRestore(filename)
    }

    @Synchronized internal fun markLoadStarted(write: (String) -> Unit = ::persist) {
        checkDeadline(); check(!restoreRequested.get()) { "Restore requested before load admission" }
        mutated = true
        if (variant.isPersistent) deadline = 0
        write("LOAD_STARTED")
        step("load-durable-before-helper-GO") // Unknown until child returns; never replay.
    }

    internal fun applyRecoveredJournal(saved: ClusterThemeJournal.Recovery) {
        cornerVerification = null
        loadRequestReceipt = "NOT_RECORDED"; loadSelectionReceipt = "NOT_RECORDED"; loadRegistrationReceipt = "NOT_RECORDED"
        defaultQueried = false; loadSettled = false; oemObserved = false
        markerObserved = false; themeObserved = false; carrierRemoved = false; deadline = 0
        transport = saved.transport; variant = saved.variant
        dashboardStage = saved.dashboardStage; dashboardNativeDebt = saved.dashboardNativeDebt
        dashboardSelectionReady = false; dashboardRound = ""
        dashboardHelperCommand = saved.dashboardHelperCommand
        dashboardHelperPid = saved.dashboardHelperPid; dashboardHelperStart = saved.dashboardHelperStart; dashboardHelperToken = saved.dashboardHelperToken
        retirement = saved.retirement; retirementEligibleUntil = 0
        iviRecoverySettled = saved.iviRecoverySettled
        if (transport == ClusterThemeTransport.ANDROID_IVI) {
            filename = saved.filename; root = saved.root; identity = saved.identity
        }
        if (!saved.clean) {
            filename = saved.filename; renderer = saved.renderer; identity = saved.identity
            mutated = saved.mutated; uncertain = mutated
            helperPid = saved.helperPid; helperStart = saved.helperStart; helperToken = saved.helperToken
            createdDevice = saved.device; createdInode = saved.inode
            state = "QUARANTINE"; detail = "Previous daemon stopped. No replay. Recovery requires manual Restore; remote completion remains unknown."
            active.set(true)
        } else if (transport == ClusterThemeTransport.ANDROID_IVI) {
            state = "CLEAN"; phase = "recovered-clean"
            detail = "Durable completed RCC receipt recovered; no commands replayed; current pixels UNKNOWN."
        }
    }

    fun status(): String {
        initialize()
        return JSONObject().put("state", state).put("detail", detail).put("filename", filename)
            .put("variant", if (journalTrusted) variant.id else "UNKNOWN")
            .put("carrierSha256", if (journalTrusted) variant.carrierSha256 else "").put("phase", phase)
            .put("transport", transport.id).put("nativeIdentity", if (transport == ClusterThemeTransport.ANDROID_IVI) "UNKNOWN" else "STRICT_PROFILE")
            .put("trace", synchronized(this) { trace.joinToString("\n") }).put("rccDebt", active.get())
            .put("recoveryPending", restoring.get() >= 0)
            .put("retirement", retirement?.json()).put("retirementAvailable", retirement == null && retirementEligibleUntil > SystemClock.elapsedRealtime())
            .put("journalSchema", if (retirement != null) "4" else if (transport == ClusterThemeTransport.ANDROID_IVI) "3" else "2")
            .put("carrierIdentity", "device=$createdDevice inode=$createdInode")
            .put("rccSafeNonce", if (transport == ClusterThemeTransport.ANDROID_IVI && journalTrusted && !active.get() &&
                state in setOf("CLEAN", "REFUSED") && ClusterThemePolicy.ownedName(filename)) filename.removePrefix(ClusterThemePolicy.PREFIX).removeSuffix(".rcc") else "")
            .put("daemonUid", Process.myUid()).put("renderer", renderer).put("identity", identity)
            .put("remainingMs", (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            .put("mutated", mutated).put("uncertain", uncertain).put("iviRecoverySettled", iviRecoverySettled).put("defaultQueried", defaultQueried)
            .put("carrierRemoved", carrierRemoved).put("markerObservedByUser", markerObserved).put("oemObservedByUser", oemObserved)
            .put("themeObservedByUser", themeObserved)
            .put("dashboard", JSONObject().put("stage", dashboardStage).put("nativeDebt", dashboardNativeDebt)
                .put("pending", dashboardPending.get()).put("helperCommand", dashboardHelperCommand)
                .put("selectionReady", dashboardSelectionReady).put("round", dashboardRound)
                .put("pixels", if (themeObserved) "USER_REPORTED" else "UNKNOWN")
                .put("switchWaitMs", dashboardIntervals.rccRemaining()))
            .put("loadEvidence", JSONObject().put("send", loadRequestReceipt).put("selection", loadSelectionReceipt)
                .put("ack2", loadRegistrationReceipt).put("pixels", if (markerObserved || themeObserved) "USER_REPORTED" else "UNKNOWN"))
            .put("cornerVerification", cornerVerification?.let { JSONObject().put("round", it.round).put("observed", it.observed)
                .put("remainingMs", (it.expiresElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                .put("available", it.matches(filename, it.round, generation.get(), SystemClock.elapsedRealtime()) && !restoreRequested.get() && restoring.get() == -1L) })
            .put("raw", raw.takeLast(if (transport == ClusterThemeTransport.ANDROID_IVI) 3000 else 16000)).put("oemQuery", oemQuery).put("nativeMode", nativeMode.status()).toString()
    }

    @Synchronized fun nativeModeRequest(full: Boolean, nonce: String, parked: Boolean, firmware: Boolean,
        validity: android.os.IBinder): String {
        initialize()
        try {
            check(journalTrusted && !active.get() && !queryActive.get() && !helperUnreaped) { "RCC test/query/recovery active; native mode request refused" }
            check(productionModeCommands == 0) { "Production mode command in flight; native request refused" }
            nativeMode.request(full, nonce, parked, firmware, validity)
        } catch (error: Exception) {
            if (full && !nativeMode.blocked()) return JSONObject(status()).put("nativeRejectedNonce", nonce)
                .put("nativeRefusal", "${error.javaClass.simpleName}: ${error.message}".take(768)).toString()
            throw error
        }
        return status()
    }
    fun nativeBlocksProjection(): Boolean { initialize(); return nativeMode.blocked() || active.get() || queryActive.get() }
    @Synchronized fun nativeNormalObserved(nonce: String): String {
        nativeMode.observedNormal(nonce)
        return status()
    }

    @Synchronized fun querySelection(run: () -> ClusterThemeQueryRunner.Result = ClusterThemeQueryRunner::run): String {
        initialize()
        check(journalTrusted && !active.get() && !helperUnreaped && !nativeMode.blocked()) { "Native or RCC test/recovery/quarantine active; read-only query refused" }
        check(queryActive.compareAndSet(false, true)) { "Read-only query already active or helper not reaped; no replay" }
        oemQuery = JSONObject().put("state", "PENDING").put("phase", "queued")
            .put("detail", "One read-only OEM selection query; no renderer discovery, carrier, load or restore.")
            .put("nativeIdentity", "UNKNOWN").put("receivedEpochMs", System.currentTimeMillis())
        worker.execute {
            var reaped = true
            try {
                val result = run()
                oemQuery = result.receipt; reaped = result.reaped
            } catch (error: Throwable) {
                reaped = false // An unexpected runner failure cannot establish that a child was reaped.
                oemQuery = JSONObject().put("state", "UNAVAILABLE").put("phase", "helper-start")
                    .put("detail", "${error.javaClass.simpleName}: ${error.message}; query child cleanup unconfirmed; further queries/tests blocked".take(768))
                    .put("nativeIdentity", "UNKNOWN").put("receivedEpochMs", System.currentTimeMillis())
            } finally { if (reaped) queryActive.set(false) }
        }
        return status()
    }

    @Synchronized fun start(source: ParcelFileDescriptor, parkedAssertion: Boolean,
        requestedVariant: ClusterThemeVariant = ClusterThemeVariant.MARKER,
        requestedTransport: ClusterThemeTransport = ClusterThemeTransport.STRICT_RENDERER,
        requestedNonce: String? = null, validity: android.os.IBinder? = null): String {
        initialize()
        try {
            val st = Os.fstat(source.fileDescriptor)
            check(ClusterThemePolicy.permitsCarrierSource(st.st_mode, st.st_size)) { "Source must be a bounded regular file (pipes/devices refused)" }
        } catch (t: Throwable) { source.close(); throw t }
        if (!parkedAssertion || !journalTrusted || helperUnreaped || productionModeCommands != 0 ||
            queryActive.get() || nativeMode.blocked() || !active.compareAndSet(false, true)) {
            source.close()
            return if (requestedTransport == ClusterThemeTransport.ANDROID_IVI) JSONObject(status()).put("rccRejectedNonce", requestedNonce).toString() else status()
        }
        val thisGeneration = generation.incrementAndGet()
        restoreRequested.set(false); observationPending.set(0)
        mutated = false; uncertain = false; loadSettled = false; iviRecoverySettled = false; defaultQueried = false
        retirement = null; retirementEligibleUntil = 0
        cornerVerification = null
        markerObserved = false; themeObserved = false; oemObserved = false; carrierRemoved = false; raw = ""
        loadRequestReceipt = "NOT_RECORDED"; loadSelectionReceipt = "NOT_RECORDED"; loadRegistrationReceipt = "NOT_RECORDED"
        variant = requestedVariant; transport = requestedTransport; projectionValidity = validity
        dashboardStage = "NONE"; dashboardNativeDebt = false; dashboardHelperCommand = -1
        dashboardSelectionReady = false; dashboardRound = ""
        dashboardHelperPid = 0; dashboardHelperStart = ""; dashboardHelperToken = ""
        phase = if (transport == ClusterThemeTransport.ANDROID_IVI) "android-ivi-preflight" else "renderer-discovery"
        synchronized(this) { trace.clear() }
        renderer = ""; identity = ""; root = "" // A completed experiment cannot pin the next renderer generation.
        createdDevice = -1; createdInode = -1
        filename = ClusterThemePolicy.PREFIX + (requestedNonce ?: UUID.randomUUID().toString().replace("-", "")) + ".rcc"
        state = "PREFLIGHT"; detail = if (transport == ClusterThemeTransport.ANDROID_IVI)
            "User asserts parked and firmware. Android IVI profile: exact installed Java bridge/UI contract; native host identity UNKNOWN."
            else "User asserts parked. Checking actual renderer namespace and original artifacts."
        if (transport == ClusterThemeTransport.ANDROID_IVI) try {
            check(requestedNonce?.matches(Regex("[0-9a-f]{32}")) == true && validProjection()) { "Idle projection reservation expired" }
            prepareJournalDirectory(); persist("QUEUED")
            step("queued-durable", "mutation=false")
        } catch (error: Throwable) {
            source.close(); state = "REFUSED"; detail = "Admission refused before worker: ${error.message}".take(768)
            active.set(false)
            return JSONObject(status()).put("rccRejectedNonce", requestedNonce).toString()
        }
        deadline = SystemClock.elapsedRealtime() + 90000
        worker.execute {
            if (generation.get() != thisGeneration) { source.close(); return@execute }
            source.use { descriptor ->
                try {
                    prepareJournalDirectory()
                    if (transport == ClusterThemeTransport.ANDROID_IVI) {
                        check(validProjection()) { "Idle projection reservation expired" }
                        hashDeadline = SystemClock.elapsedRealtime() + 90000
                        phase = "installed-artifact-cohort"
                        val cohort = ClusterThemeCohortVerifier.verify(variant, hashDeadline) { step("cohort-artifact", it) }
                        step("cohort-verified", "id=$cohort installedFilesOnly=true loadedNativeIdentity=UNKNOWN")
                    } else identifyRenderer()
                    checkDeadline()
                    phase = "baseline-query"
                    val baseline = exchange("query")
                    check(!baseline.unknown && baseline.native == 0 && baseline.query?.isDefault() == true) {
                        "Baseline query missing/custom/incompatible; default/source=1 and exact UI version required; native=${baseline.native}; ${baseline.error.orEmpty()}"
                    }
                    step("baseline-default-received", "UI=${ClusterThemeProtocol.UI_VERSION} source=1 native=${baseline.native} nativeIdentity=${if (transport == ClusterThemeTransport.ANDROID_IVI) "UNKNOWN" else "strict-profile"}")
                    checkDeadline()
                    check(!restoreRequested.get()) { "Restore requested before staging; load cancelled" }
                    phase = "carrier-staging"
                    if (transport == ClusterThemeTransport.ANDROID_IVI) {
                        check(validProjection()) { "Projection reservation expired before staging" }
                        val storage = ClusterThemeAndroidStorage.directory(true)
                        root = storage.path; identity = storage.identity
                        step("android-storage-pinned", "path=$root directoryIdentity=$identity nativeIdentity=UNKNOWN")
                    }
                    persist("STAGING") // fsync BEFORE first created resource or OEM mutation.
                    withOwnedFile { destination ->
                    val fd = Os.open(destination.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 420)
                    val created = Os.fstat(fd)
                    createdDevice = created.st_dev; createdInode = created.st_ino
                    FileOutputStream(fd).use { output ->
                        persist("STAGING_CREATED")
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var total = 0L
                            while (true) {
                                checkDeadline(); check(!restoreRequested.get()) { "Restore requested during staging" }
                                val n = input.read(buffer); if (n < 0) break
                                total += n; check(total <= ClusterThemePolicy.MAX_CARRIER) { "Carrier exceeds size bound" }
                                digest.update(buffer, 0, n); output.write(buffer, 0, n)
                            }
                            check(total > 0 && hex(digest.digest()) == variant.carrierSha256) { "Carrier hash mismatch for ${variant.id}" }
                        }
                        output.fd.sync()
                    }
                    }
                    phase = "carrier-verification"
                    withOwnedFile {
                        val named = Os.lstat(it.path)
                        check(OsConstants.S_ISREG(named.st_mode) && named.st_dev == createdDevice && named.st_ino == createdInode) { "Staged carrier generation changed" }
                        check(hash(it, ClusterThemePolicy.MAX_CARRIER) == variant.carrierSha256)
                    }
                    step("carrier-hash-verified", "variant=${variant.id} SHA256=${variant.carrierSha256} device=$createdDevice inode=$createdInode")
                    if (transport == ClusterThemeTransport.ANDROID_IVI) {
                        ownedFile(); check(validProjection()) { "Projection reservation expired before load" }
                    } else checkRendererIdentity()
                    checkDeadline(); check(!restoreRequested.get()) { "Restore requested; load cancelled" }
                    phase = "load"
                    awaitDashboardSwitch(false)
                    markLoadStarted()
                    val load = try { exchange("load") } finally { finishDashboardSwitch() }
                    recordLoadEvidence(load)
                    uncertain = load.unknown || load.native != 0
                    step("load-result", "native=${load.native} unknown=${load.unknown} selectionOwned=${load.query?.selected(filename)}")
                    loadSettled = load.responses.any { ClusterThemePolicy.loadAckMatches(it, filename) }
                    dashboardSelectionReady = variant.isDashboard && !uncertain && load.native == 0 &&
                        load.query?.selected(filename) == true && !helperUnreaped && helperPid == 0
                    state = if (uncertain) "QUARANTINE" else if (variant.isCorner) "SELECTION_PENDING_OEM_APPLY" else "AWAITING_OBSERVATION"
                    detail = if (uncertain) "${variant.id} load completion UNKNOWN; native=${load.native}; ${load.error.orEmpty()}. No replay; recovery remains separate."
                        else if (variant.isCorner) "Owned IVI selection reported; source=${load.query?.ints?.get(3)}; physical display unconfirmed. Selection/configuration and ACK4 do not establish Qt registration or QML application. Use Verify RCC corner markers for a fresh read-only visual question. Recovery remains separate; no automatic reload or established manual reset procedure."
                        else if (load.query?.selected(filename) == true) "Selection query names ${variant.id}; pixels require your observation."
                        else "Load attempted for ${variant.id}; selection unconfirmed; native=${load.native}; ${load.error.orEmpty()}. Missing test graphics are inconclusive."
                    phase = "observation"
                    persist(state)
                    deadline = if (variant.isPersistent) 0 else SystemClock.elapsedRealtime() + 30000
                    if (restoreRequested.get() || uncertain && !variant.isPersistent) enqueueRestore(thisGeneration)
                } catch (t: Throwable) {
                    detail = "$phase: ${t.javaClass.simpleName}: ${t.message}".take(2000)
                    if (mutated) {
                        uncertain = true; state = "QUARANTINE"; persistQuietly()
                        if (variant.isPersistent) deadline = 0
                        if (!variant.isPersistent || restoreRequested.get()) enqueueRestore(thisGeneration)
                    }
                    else if (helperUnreaped) {
                        uncertain = true; state = "QUARANTINE"; deadline = 0
                        detail += "; previous owned helper not reaped; no new test"
                        persistQuietly()
                    } else {
                        // No OEM mutation began. Delete only an exclusively created, recorded own file.
                        try {
                            if (createdInode >= 0) check(cleanupOwned(allowPartial = true)) { "Owned partial cleanup unavailable" }
                            state = "REFUSED"; deadline = 0
                            persist("CLEAN"); active.set(false)
                        } catch (cleanup: Throwable) {
                            uncertain = true; state = "QUARANTINE"; deadline = 0
                            detail += "; pre-load cleanup/journal failed: ${cleanup.message}"; persistQuietly()
                        }
                    }
                }
            }
        }
        return status()
    }

    @Synchronized fun startAndroid(source: ParcelFileDescriptor, parked: Boolean, firmware: Boolean,
        nonce: String, validity: android.os.IBinder, variant: ClusterThemeVariant): String {
        if (!firmware || !nonce.matches(Regex("[0-9a-f]{32}"))) { source.close(); error("Explicit firmware assertion and fixed nonce required") }
        return start(source, parked, variant, ClusterThemeTransport.ANDROID_IVI, nonce, validity)
    }

    @Synchronized fun requestAndroidRestore(nonce: String, parked: Boolean, firmware: Boolean, validity: android.os.IBinder): String {
        initialize()
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        check(parked && firmware && journalTrusted && !queryActive.get() && !nativeMode.blocked() && productionModeCommands == 0)
        projectionValidity = validity
        check(validProjection()) { "Idle projection reservation expired; no recovery request sent" }
        val expected = ClusterThemePolicy.PREFIX + nonce + ".rcc"
        if (!active.get()) {
            // QUEUED is durable before a start can fork. Idle trusted state proves no outstanding load.
            check(!helperUnreaped && helperPid == 0)
            transport = ClusterThemeTransport.ANDROID_IVI; filename = expected; renderer = ""; root = ""; identity = ""
            mutated = false; uncertain = false; defaultQueried = false; loadSettled = false; iviRecoverySettled = false
            retirement = null; retirementEligibleUntil = 0
            markerObserved = false; themeObserved = false; oemObserved = false; carrierRemoved = false
            createdDevice = -1; createdInode = -1; variant = ClusterThemeVariant.MARKER
            state = "CLEAN"; phase = "no-admission-reconciliation"
            try { prepareJournalDirectory(); persist("CLEAN") } catch (error: Throwable) {
                journalTrusted = false; active.set(true); uncertain = true; state = "QUARANTINE"
                detail = "No-admission reconciliation could not persist; RCC admission remains blocked"
                throw error
            }
            detail = "No outstanding daemon load; durable pre-IPC app debt reconciled. No OEM command sent; pixels UNKNOWN."
            step("no-admission-reconciled")
            return status()
        }
        check(transport == ClusterThemeTransport.ANDROID_IVI && filename == expected) { "Active RCC transport/nonce differs; no overwrite" }
        return requestRestore(expected)
    }

    private fun awaitDashboardSwitch(recovery: Boolean) {
        if (!variant.isDashboard) return
        phase = "dashboard-rcc-compatibility-interval"
        dashboardIntervals.awaitRcc { validProjection() && (recovery || !restoreRequested.get()) }
    }

    private fun finishDashboardSwitch() {
        if (variant.isDashboard) dashboardIntervals.rccFinished()
    }

    /** A closed sequence, never an arbitrary caller-supplied AutoContainer verb. */
    @Synchronized fun requestDashboard(open: Boolean, nonce: String, parked: Boolean, firmware: Boolean,
        validity: android.os.IBinder): String {
        initialize()
        check(parked && firmware && journalTrusted && active.get() && variant.isDashboard && mutated &&
            transport == ClusterThemeTransport.ANDROID_IVI && productionModeCommands == 0 && !queryActive.get() &&
            !nativeMode.blocked() && !helperUnreaped && restoring.get() == -1L && (!open || !restoreRequested.get())) {
            "Dashboard selection or recovery not settled; no native sequence sent"
        }
        check(filename == ClusterThemePolicy.PREFIX + nonce + ".rcc") { "Dashboard nonce differs" }
        check(!open || dashboardSelectionReady && phase == "observation" && !uncertain && dashboardStage in setOf("NONE", "CLOSED_RECEIVED")) { "Dashboard open requires a settled owned selection" }
        check(open || dashboardNativeDebt || dashboardPending.get()) { "No dashboard native mode debt to close" }
        projectionValidity = validity; check(validProjection())
        if (open) check(dashboardPending.compareAndSet(false, true)) { "Dashboard sequence already pending" }
        else {
            check(dashboardCloseQueued.compareAndSet(false, true)) { "Dashboard close already queued" }
            dashboardPending.set(true)
        }
        val expected = generation.get()
        worker.execute {
            try {
                check(generation.get() == expected && validProjection() && (!open || !restoreRequested.get() && !dashboardCloseQueued.get()))
                check(!open || dashboardSelectionReady && phase == "observation" && !uncertain)
                ownedFile()
                val selection = exchange("query")
                check(!selection.unknown && selection.native == 0 &&
                    (selection.query?.selected(filename) == true || !open && selection.query?.isDefault() == true)) {
                    "Fresh query does not report the exact owned dashboard selection"
                }
                check(!open || dashboardSelectionReady && !uncertain && !restoreRequested.get() && !dashboardCloseQueued.get())
                runDashboardSequence(open)
                if (!open) maybeCleanup()
            } catch (error: Throwable) {
                dashboardStage = "UNKNOWN"; uncertain = true
                detail = "Dashboard sequence stopped: ${error.message}; no replay; Restore remains available.".take(2000)
                persistQuietly()
            } finally {
                if (!open) { dashboardCloseQueued.set(false); dashboardPending.set(false) }
                else if (!dashboardCloseQueued.get()) dashboardPending.set(false)
            }
        }
        return status()
    }

    internal fun runDashboardSequence(open: Boolean,
        request: (Int, () -> Unit, (Int, String, String) -> Unit) -> ClusterThemeQueryRunner.Result = { command, admit, ready ->
            ClusterThemeQueryRunner.dashboardMode(command, filename, admit, ready)
        }, write: () -> Unit = { persist(state) }, before: (() -> Boolean) -> Unit = dashboardIntervals::awaitNative,
        finished: () -> Unit = dashboardIntervals::nativeFinished, reap: () -> Unit = ::reapDashboardHelper) {
        check(variant.isDashboard && journalTrusted && validProjection() && !helperUnreaped)
        reap()
        val commands = if (open) com.byd.dashcast.clusterlive.ClusterDashboardProfile.openCommands
            else com.byd.dashcast.clusterlive.ClusterDashboardProfile.closeCommands
        dashboardStage = if (open) "OPENING" else "CLOSING"
        dashboardNativeDebt = true
        oemObserved = false
        if (open) { themeObserved = false; dashboardRound = UUID.randomUUID().toString().replace("-", "") }
        write()
        commands.forEach { command ->
            before { validProjection() && (!open || !restoreRequested.get() && !dashboardCloseQueued.get()) }
            check(validProjection() && (!open || !restoreRequested.get() && !dashboardCloseQueued.get())) { "Dashboard sequence retired before helper" }
            phase = "dashboard-native-$command"
            val result = try { request(command, {
                check(validProjection() && (!open || !restoreRequested.get() && !dashboardCloseQueued.get())) { "Dashboard sequence retired before GO" }
                write() // Helper identity and native debt are durable before admission.
            }, { pid, start, token ->
                dashboardHelperPid = pid; dashboardHelperStart = start; dashboardHelperToken = token
                dashboardHelperCommand = command; write()
            }) } finally { finished() }
            if (result.reaped) {
                dashboardHelperPid = 0; dashboardHelperStart = ""; dashboardHelperToken = ""; dashboardHelperCommand = -1
            }
            val receipt = result.receipt
            step("dashboard-command-received", "command=$command reaped=${result.reaped} state=${receipt.optString("state")} native=${receipt.opt("native")} pixels=UNKNOWN")
            write()
            check(result.reaped && receipt.optString("state") == "RECEIVED" && receipt.optBoolean("attempted") &&
                receipt.has("native") && receipt.getInt("native") == 0) { "Native dashboard command $command unresolved; no replay" }
        }
        dashboardNativeDebt = open
        dashboardStage = if (open) "OPEN_RECEIVED" else "CLOSED_RECEIVED"
        phase = "observation"
        detail = "Dashboard ${if (open) "opening" else "closing"} sequence returned. Native pixels and QML application require observation. RCC remains selected; factory Restore is required before ordinary projection or downgrade."
        write()
    }

    internal fun closeDashboardForFactoryRestore(close: () -> Unit = { runDashboardSequence(false) }, write: () -> Unit = ::persistQuietly) {
        if (variant.isDashboard && dashboardNativeDebt) runCatching(close).onFailure {
            dashboardStage = "UNKNOWN"; uncertain = true
            step("dashboard-close-unresolved", "${it.message}; native debt retained; factory RCC recovery continues independently")
            write()
        }
    }

    @Synchronized fun observeDashboard(name: String, round: String): String {
        fun current() = journalTrusted && active.get() && variant.isDashboard && filename == name &&
            round.matches(Regex("[0-9a-f]{32}")) && round == dashboardRound && dashboardStage == "OPEN_RECEIVED" &&
            !dashboardPending.get() && !restoreRequested.get() && restoring.get() == -1L && !uncertain &&
            !helperUnreaped && dashboardHelperPid == 0
        check(current()) { "Dashboard observation expired; no display claim recorded" }
        worker.execute {
            synchronized(this) {
                if (current()) {
                    themeObserved = true
                    step("dashboard-user-observation", "round=$round pixels=USER_REPORTED registerACKUnchanged=true")
                    persistQuietly()
                }
            }
        }
        return status()
    }

    @Synchronized fun requestRestore(expectedFilename: String? = null): String {
        initialize()
        if (nativeMode.blocked()) return status()
        if (expectedFilename != null && expectedFilename != filename) return status()
        cornerVerification = null
        restoreRequested.set(true)
        if (active.get()) enqueueRestore()
        return status()
    }

    @Synchronized fun requestCornerVerification(nonce: String, parked: Boolean, firmware: Boolean, validity: android.os.IBinder,
        verifyCarrier: () -> Unit = { ownedFile() }, query: () -> Exchange = { exchange("query") }): String {
        initialize()
        check(parked && firmware && nonce.matches(Regex("[0-9a-f]{32}")) && journalTrusted && active.get() && mutated &&
            transport == ClusterThemeTransport.ANDROID_IVI && variant.isCorner && retirement == null &&
            !defaultQueried && !iviRecoverySettled && !carrierRemoved && !oemObserved &&
            filename == ClusterThemePolicy.PREFIX + nonce + ".rcc") { "Trusted active corner RCC session required" }
        check(!queryActive.get() && !nativeMode.blocked() && productionModeCommands == 0 && !helperUnreaped && helperPid == 0)
        projectionValidity = validity; check(validProjection()) { "Projection reservation expired" }
        val expected = generation.get()
        check(restoring.compareAndSet(-1, expected)) { "Recovery or verification worker already active" }
        cornerVerification = null; markerObserved = false; restoreRequested.set(false)
        deadline = 0; phase = "corner-read-only-verification"
        worker.execute {
            try {
                check(generation.get() == expected && !restoreRequested.get() && validProjection())
                hashDeadline = SystemClock.elapsedRealtime() + 60000
                verifyCarrier()
                val selected = query() // Exactly one read-only selection query; never load/restore/restart.
                check(!selected.unknown && selected.native == 0 && selected.query != null) { "Fresh selection unavailable" }
                verifyCarrier()
                check(generation.get() == expected && !restoreRequested.get() && validProjection() && !helperUnreaped && helperPid == 0)
                cornerVerification = ClusterCornerVerification.issue(filename, expected, UUID.randomUUID().toString().replace("-", ""),
                    SystemClock.elapsedRealtime(), selected.query)
                step("corner-verification-ready", "round=${cornerVerification?.round} source=${selected.query.ints[3]} selectionOnly=true nativePropertyEmpty=${selected.query.ints[3] == 1} pixels=UNKNOWN")
                detail = "Fresh exact owned IVI selection; source=${selected.query.ints[3]}; file identity/hash rechecked. Source 1 means the separate native RCC property is empty, not default IVI selection or applied QML. Observe physical corner markers now; this only records your observation. All recovery debt, load settlement and historical uncertainty remain unchanged."
            } catch (error: Throwable) {
                cornerVerification = null
                detail = "Read-only corner verification refused: ${error.message}; no reload or recovery."
                step("corner-verification-refused", detail)
            } finally {
                restoring.compareAndSet(expected, -1)
                if (restoreRequested.get()) enqueueRestore(expected)
            }
        }
        return status()
    }

    @Synchronized fun observeCorners(expectedFilename: String, round: String): String {
        initialize()
        val verified = cornerVerification ?: return status()
        check(journalTrusted && active.get() && mutated && variant.isCorner &&
            transport == ClusterThemeTransport.ANDROID_IVI && retirement == null && !restoreRequested.get() &&
            restoring.get() == -1L && !queryActive.get() && !helperUnreaped && helperPid == 0 && validProjection() &&
            verified.matches(expectedFilename, round, generation.get(), SystemClock.elapsedRealtime())) { "Corner visual question expired or changed" }
        markerObserved = true; cornerVerification = verified.copy(observed = true)
        step("corner-observed-by-user", "round=$round recoveryDebtUnchanged=true loadSettlementUnchanged=true")
        return status()
    }

    @Synchronized fun requestAndroidRetirement(nonce: String, parked: Boolean, firmware: Boolean, validity: android.os.IBinder): String {
        initialize()
        check(parked && firmware && nonce.matches(Regex("[0-9a-f]{32}")) && journalTrusted && active.get() && mutated &&
            transport == ClusterThemeTransport.ANDROID_IVI && filename == ClusterThemePolicy.PREFIX + nonce + ".rcc")
        check(!queryActive.get() && !nativeMode.blocked() && productionModeCommands == 0 && !helperUnreaped)
        check(retirement != null || retirementEligibleUntil > SystemClock.elapsedRealtime()) { "First request Restore: a fresh ACK3 RESOURCE_IS_SAME and exact own selection are required" }
        check(retirement?.moved != true) { "Already retired: use Verify BYD return for a read-only check; no further move or OEM command" }
        projectionValidity = validity; check(validProjection()) { "Projection reservation expired" }
        val expected = generation.get()
        check(restoring.compareAndSet(-1, expected)) { "Recovery worker already active" }
        restoreRequested.set(true); deadline = 0
        state = "RETIRING"; phase = "retirement-preflight"; defaultQueried = false; oemObserved = false; iviRecoverySettled = false
        worker.execute {
            try {
                check(generation.get() == expected && validProjection())
                reapRecoveredHelper(); hashDeadline = SystemClock.elapsedRealtime() + 60000
                if (retirement == null || !ClusterThemeRetiredStorage.originalAbsent(filename, identity)) {
                    if (retirement == null) check(retirementEligibleUntil > SystemClock.elapsedRealtime()) { "ACK3 eligibility expired; request Restore again" }
                    val selection = exchange("query")
                    check(!selection.unknown && selection.native == 0 && selection.query?.selected(filename) == true && selection.query.ints[3] == 0) { "Own persisted selection changed; no retirement" }
                }
                check(validProjection())
                retirement = ClusterThemeRetiredStorage.move(filename, identity, createdDevice, createdInode, variant.carrierSha256,
                    retirement, { hash(it, ClusterThemePolicy.MAX_CARRIER) }) { checkpoint ->
                    check(validProjection()) { "Projection reservation expired during preparation" }
                    retirement = checkpoint; phase = if (checkpoint.moved) "carrier-retired" else "retirement-intent"
                    persist(state); check(validProjection()) { "Projection reservation expired after durable checkpoint" }
                    step(phase, "directory=${checkpoint.directory} device=${checkpoint.device} inode=${checkpoint.inode} fsync=PASS")
                }
                retirementEligibleUntil = 0; uncertain = true
                state = "WAITING_OEM_REINITIALIZATION"; phase = "carrier-retired"
                detail = "Only our RCC moved and preserved. Active graphics may remain. Use Verify BYD return for a fresh read-only check. No specific manual cluster reset procedure is established; Android/daemon reconnect does not prove native reinitialization. If the check is still pending, export a report."
                persist(state)
            } catch (error: Throwable) {
                uncertain = true; state = "QUARANTINE"; detail = "RCC retirement unconfirmed: ${error.message}".take(2000); persistQuietly()
            } finally { restoring.compareAndSet(expected, -1) }
        }
        return status()
    }

    private fun enqueueRestore(expectedGeneration: Long = generation.get()) {
        if (generation.get() != expectedGeneration) return
        while (true) {
            val before = restoring.get()
            if (before == expectedGeneration || before > expectedGeneration) return
            if (restoring.compareAndSet(before, expectedGeneration)) break
        }
        worker.execute {
            if (generation.get() != expectedGeneration) { restoring.compareAndSet(expectedGeneration, -1); return@execute }
            try {
                check(journalTrusted) { "Journal identity/mutation state unknown; no replay or mutation" }
                reapRecoveredHelper()
                deadline = 0
                if (!mutated) {
                    // Journal proves no native mutation began (LOAD_STARTED is fsynced first).
                    if (root.isEmpty() && renderer.isNotEmpty()) identifyRenderer()
                    if (createdInode >= 0) check(cleanupOwned(true)) { "Owned partial cleanup unavailable; retained" }
                    state = "CLEAN"; detail = "Cancelled before native load; only a positively owned partial carrier was removed."
                    persist(state); active.set(false)
                    return@execute
                }
                val priorRecoveryProof = iviRecoverySettled
                defaultQueried = false; oemObserved = false
                state = "RESTORING"; detail = if (retirement?.moved == true)
                    "Read-only retired-carrier and selection verification pending; no default request, reload or restart will be sent."
                    else "Recovery pre-query pending; a default request is sent only if our RCC is selected. No physical recovery claim."
                phase = "restore-query"
                persist(state)
                retirement?.let { retired ->
                    check(transport == ClusterThemeTransport.ANDROID_IVI && retired.moved && validProjection()) { "Retirement incomplete; use explicit preparation" }
                    hashDeadline = SystemClock.elapsedRealtime() + 60000
                    fun verifyRetired() = ClusterThemeRetiredStorage.verify(filename, identity, createdDevice, createdInode,
                        variant.carrierSha256, retired) { hash(it, ClusterThemePolicy.MAX_CARRIER) }
                    verifyRetired(); phase = "retired-read-only-query"
                    val queried = exchange("query") // Never load, restore or restart an OEM process after retirement.
                    verifyRetired(); check(validProjection())
                    recordRetiredQuery(queried)
                    persist(state); maybeCleanup()
                    return@execute
                }
                if (transport == ClusterThemeTransport.ANDROID_IVI) {
                    check(validProjection()) { "Explicit live projection reservation required for Android recovery" }
                    hashDeadline = SystemClock.elapsedRealtime() + 60000
                    ownedFile()
                } else if (root.isEmpty()) identifyRenderer() else checkRendererIdentity()
                val selection = exchange("query")
                if (selection.unknown || selection.native != 0 || selection.query == null) {
                    iviRecoverySettled = false
                    error("Recovery pre-query unavailable; no overwrite of unknown selection; ${selection.error.orEmpty()}")
                }
                if (!selection.query.isDefault() && !selection.query.selected(filename)) {
                    iviRecoverySettled = false
                    error("Another selection is active; no overwrite")
                }
                step("restore-prequery", "default=${selection.query.isDefault()} own=${selection.query.selected(filename)}")
                closeDashboardForFactoryRestore()
                if (transport == ClusterThemeTransport.ANDROID_IVI && selection.query.selected(filename)) {
                    iviRecoverySettled = false
                    persist(state) // Revoke an older proof before a new mutating recovery attempt.
                }
                val restored = if (selection.query.isDefault()) selection else {
                    awaitDashboardSwitch(true)
                    try { exchange("restore") } finally { finishDashboardSwitch() }
                }
                step("restore-result", "native=${restored.native} unknown=${restored.unknown} default=${restored.query?.isDefault()} ${restored.error.orEmpty()}")
                recordRestoreResult(selection.query, restored, priorRecoveryProof)
                if (transport == ClusterThemeTransport.ANDROID_IVI) {
                    ownedFile(); check(validProjection()) { "Projection reservation expired after recovery" }
                    step("ivi-recovery-proof", "settled=$iviRecoverySettled historicalUncertain=$uncertain defaultQueried=$defaultQueried")
                }
                persist(state)
                maybeCleanup()
            } catch (t: Throwable) {
                uncertain = true; state = "QUARANTINE"; detail = "Restore unknown: ${t.message}".take(2000); persistQuietly()
            } finally { restoring.compareAndSet(expectedGeneration, -1) }
        }
    }

    internal fun recordRestoreResult(before: ClusterThemeProtocol.Response, restored: Exchange, priorRecoveryProof: Boolean) {
        retirementEligibleUntil = if (transport == ClusterThemeTransport.ANDROID_IVI && retirement == null && restored.retirementEligible &&
            ClusterThemeRetirement.eligible(filename, restored.query, restored.responses, restored.native, restored.attempted))
            SystemClock.elapsedRealtime() + 120000 else 0
        uncertain = uncertain || restored.unknown || restored.native != 0
        defaultQueried = !restored.unknown && restored.native == 0 && restored.query?.isDefault() == true
        if (transport == ClusterThemeTransport.ANDROID_IVI) {
            // The IVI selection path precedes this ordered default request; neither proves pixels.
            iviRecoverySettled = ClusterThemePolicy.iviRecoveryProven(filename, before,
                restored.query, restored.native, restored.unknown, restored.attempted) ||
                priorRecoveryProof && before.isDefault() && defaultQueried
        }
        val recovered = defaultQueried && (loadSettled && !uncertain || iviRecoverySettled)
        state = if (recovered) "DEFAULT_QUERIED" else "QUARANTINE"
        detail = if (recovered) "OEM query reports default after ordered recovery. Historical load uncertainty retained; physical OEM recovery requires your fresh confirmation."
            else if (defaultQueried) "Default queried while load is unresolved; late load remains possible. No restoration established. Carrier retained; no new test."
            else "Default recovery is unconfirmed. ${restored.error.orEmpty()} No new test; keep parked and inspect the cluster.".take(2000)
    }
    internal fun recordRetiredQuery(queried: Exchange) {
        check(retirement?.moved == true && transport == ClusterThemeTransport.ANDROID_IVI)
        defaultQueried = !queried.unknown && queried.native == 0 && queried.query?.isDefault() == true
        iviRecoverySettled = defaultQueried
        state = if (defaultQueried) "DEFAULT_QUERIED" else "WAITING_OEM_REINITIALIZATION"
        detail = if (defaultQueried) "Retired inode/hash and absent original verified around fresh default query. Fresh physical OEM confirmation is still required. Preserved RCC will not be deleted."
            else "Original RCC absent and preserved; fresh default selection is not confirmed. No established manual reset procedure or automatic retry. Export a report; no additional RCC load or recovery command was sent. ${queried.error.orEmpty()}"
        step("retired-default-query", "native=${queried.native} unknown=${queried.unknown} default=$defaultQueried")
    }
    internal fun acceptOemObservation(expectedFilename: String): Boolean {
        if (!active.get() || !mutated || !defaultQueried || filename != expectedFilename || !ClusterThemePolicy.ownedName(expectedFilename)) return false
        oemObserved = true
        return true
    }

    fun observe(which: Int, expectedFilename: String, expectedVariant: ClusterThemeVariant = ClusterThemeVariant.MARKER): String {
        initialize()
        if (which == 1 && variant.isCorner) return status()
        if (which == 1 && (variant != expectedVariant || state != "AWAITING_OBSERVATION" || uncertain || restoreRequested.get())) return status()
        // These are tester statements, never inferred from a callback or selection query.
        if (active.get() && mutated && expectedFilename == filename && ClusterThemePolicy.ownedName(expectedFilename)) {
            if (which == 2 && !defaultQueried) return status() // Observation must follow the default query, not just a queued restore.
            val observedFilename = filename
            val observedGeneration = generation.get()
            val bit = if (which == 1) 1 else if (which == 2) 2 else return status()
            if ((which == 1 && (markerObserved || themeObserved)) || (which == 2 && oemObserved)) return status()
            while (true) {
                val before = observationPending.get()
                if (before and bit != 0) return status()
                if (observationPending.compareAndSet(before, before or bit)) break
            }
            worker.execute {
                try {
                    if (filename == observedFilename && active.get()) {
                        if (which == 1 && variant == expectedVariant && state == "AWAITING_OBSERVATION" && !uncertain && !restoreRequested.get()) {
                            if (variant.isMarker) markerObserved = true else themeObserved = true
                            loadSettled = true
                        }
                        if (which == 2) acceptOemObservation(observedFilename)
                        persistQuietly(); maybeCleanup()
                    }
                } catch (error: Throwable) {
                    uncertain = true; state = "QUARANTINE"
                    detail = "Observation cleanup/journal failed: ${error.message}".take(768); persistQuietly()
                } finally {
                    if (generation.get() == observedGeneration) observationPending.getAndUpdate { it and bit.inv() }
                }
            }
        }
        return status()
    }

    private fun maybeCleanup() = completeRecovery({ cleanupOwned(false) }, ::persist)

    internal fun completeRecovery(cleanup: () -> Boolean, write: (String) -> Unit) {
        try {
            if (variant.isDashboard && (dashboardNativeDebt || dashboardHelperPid != 0)) return
            val recoveredIvi = transport == ClusterThemeTransport.ANDROID_IVI && iviRecoverySettled
            if (!carrierRemoved && oemObserved && (ClusterThemePolicy.canDeleteCarrier(defaultQueried, loadSettled, uncertain) || recoveredIvi && defaultQueried)) {
                check(cleanup()) { "Owned carrier cleanup identity unavailable; retained" }
                carrierRemoved = true
                detail = if (retirement != null) "Default queried; retired bytes preserved. A new test remains blocked until visual confirmation."
                    else "Default selection queried; only our verified carrier removed. Confirm OEM pixels; a new test remains blocked."
                write(state)
            }
            if (carrierRemoved && oemObserved && (!uncertain || recoveredIvi) && defaultQueried) {
                state = "CLEAN"; detail = if (retirement != null) "Default queried; tester confirmed OEM display. Retired RCC preserved unchanged. No reboot guarantee."
                    else "Default queried; tester confirmed OEM display. Only our carrier deleted. No reboot guarantee."
                write(state); deadline = 0; active.set(false)
            }
        } catch (error: Throwable) {
            uncertain = true; state = "QUARANTINE"
            detail = "Owned cleanup/journal failed: ${error.message}".take(768)
            throw error
        }
    }

    private fun prepareJournalDirectory() {
        if (!directory.exists()) check(directory.mkdir()) { "Cannot create private daemon journal directory" }
        val st = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(st.st_mode) && st.st_uid == Process.myUid()) { "Journal directory owner/type mismatch" }
        Os.chmod(directory.path, 448)
        check(!journal.exists() || Os.lstat(journal.path).st_uid == Process.myUid()) { "Journal owner mismatch" }
    }

    private fun persist(newState: String) {
        if (newState == "CLEAN") {
            check(!helperUnreaped && helperPid == 0) { "Cannot close journal with an unreaped helper" }
            check(!variant.isDashboard || !dashboardNativeDebt && dashboardHelperPid == 0) { "Cannot close an outstanding dashboard mode debt" }
            helperStart = ""; helperToken = ""
        }
        val j = JSONObject().put("schema", if (retirement != null) 4 else if (transport == ClusterThemeTransport.ANDROID_IVI) 3 else 2).put("state", newState).put("filename", filename)
            .apply { retirement?.let { put("retirement", it.json()) } }
            .apply { if (transport == ClusterThemeTransport.ANDROID_IVI) put("transport", transport.id).put("root", root) }
            .put("variant", variant.id).put("carrierSha256", variant.carrierSha256)
            .put("renderer", renderer).put("identity", identity).put("mutated", mutated)
            .put("uncertain", uncertain).put("iviRecoverySettled", iviRecoverySettled).put("defaultQueried", defaultQueried)
            .put("createdDevice", createdDevice).put("createdInode", createdInode)
            .put("helperPid", helperPid).put("helperStart", helperStart).put("helperToken", helperToken)
            .apply { if (variant.isDashboard) put("dashboard", JSONObject().put("stage", dashboardStage)
                .put("nativeDebt", dashboardNativeDebt).put("helperCommand", dashboardHelperCommand)
                .put("helperPid", dashboardHelperPid).put("helperStart", dashboardHelperStart).put("helperToken", dashboardHelperToken)) }
        val temporary = File(directory, "journal-${UUID.randomUUID()}.tmp")
        val fd = Os.open(temporary.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
        try {
            FileOutputStream(fd).use { it.write(j.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Os.rename(temporary.path, journal.path)
            val dirFd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(dirFd).st_mode)); Os.fsync(dirFd) } finally { Os.close(dirFd) }
        } finally { temporary.delete() }
    }
    private fun persistQuietly() { if (!journalTrusted) return; runCatching { persist(state) }.onFailure { uncertain = true; state = "QUARANTINE"; detail += "; journal write failed" } }

    private fun processIdentity(pid: String): String {
        require(pid.matches(Regex("[1-9][0-9]{0,8}")))
        val stat = File("/proc/$pid/stat").readText()
        val start = stat.substringAfterLast(')').trim().split(Regex("\\s+"))[19]
        return "$pid:$start:${Os.readlink("/proc/$pid/ns/mnt")}"
    }
    private val rendererAccess = object : ClusterRendererDiscovery.Access<FileDescriptor> {
        override fun open(pid: String): FileDescriptor = Os.open("/proc/$pid/cmdline",
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        override fun read(fd: FileDescriptor, buffer: ByteArray, count: Int) = Os.read(fd, buffer, 0, count)
        override fun close(fd: FileDescriptor) = Os.close(fd)
    }
    private fun identifyRenderer() {
        identifyRenderer(ClusterRendererDiscovery.scan(
            entries = { visit -> Files.newDirectoryStream(Paths.get("/proc")).use { entries ->
                for (entry in entries) visit(entry.fileName.toString())
            } },
            access = rendererAccess,
            classify = ::procFailure,
            clock = SystemClock::elapsedRealtime,
        ))
    }
    private fun procFailure(error: Exception): ClusterRendererDiscovery.Failure {
        val cause = generateSequence<Throwable>(error) { it.cause }.take(16).filterIsInstance<ErrnoException>().firstOrNull()
            ?: return ClusterRendererDiscovery.failure(error)
        return when (cause.errno) {
            OsConstants.EACCES -> ClusterRendererDiscovery.Failure(ClusterRendererDiscovery.FailureKind.DENIED, "EACCES")
            OsConstants.EPERM -> ClusterRendererDiscovery.Failure(ClusterRendererDiscovery.FailureKind.DENIED, "EPERM")
            OsConstants.ENOENT -> ClusterRendererDiscovery.Failure(ClusterRendererDiscovery.FailureKind.VANISHED, "ENOENT")
            OsConstants.ESRCH -> ClusterRendererDiscovery.Failure(ClusterRendererDiscovery.FailureKind.VANISHED, "ESRCH")
            else -> ClusterRendererDiscovery.Failure(ClusterRendererDiscovery.FailureKind.IO, "errno_${cause.errno}")
        }
    }
    private fun identifyRenderer(discovery: ClusterRendererDiscovery.Result) {
        hashDeadline = SystemClock.elapsedRealtime() + 60000
        phase = "renderer-discovery"
        val pid = discovery.requireSingle()
        if (renderer.isNotEmpty()) check(renderer == pid && identity == processIdentity(pid)) { "Renderer changed since journal; recovery must be inspected" }
        renderer = pid; identity = processIdentity(pid); root = "/proc/$pid/root"
        phase = "renderer-maps"
        checkMappedArtifacts()
        for ((path, expected) in ClusterThemePolicy.originalHashes) {
            checkDeadline(allowRecovery = true)
            phase = "renderer-artifact"
            detail = "Hashing original $path through pinned renderer namespace."
            val actual = try { hash(File(root + path), ClusterThemePolicy.MAX_ARTIFACT) }
                catch (error: Throwable) { throw IllegalStateException("Cannot hash $path via $root: ${error.message}", error) }
            check(actual == expected) { "Live original hash differs: $path; expected=$expected; actual=$actual" }
        }
        checkRendererIdentity()
        checkMappedArtifacts()
        phase = "renderer-carrier-directory"
        val parent = File(root + ClusterThemePolicy.HOST_DIRECTORY)
        check(parent.isDirectory && parent.canWrite()) { "Verified renderer carrier directory unavailable/unwritable; no alias/permission fallback" }
    }
    private fun checkRendererIdentity() { check(identity == processIdentity(renderer)) { "Renderer/session changed" } }
    private fun checkMappedArtifacts() {
        val maps = File("/proc/$renderer/maps").readLines().map { it.trim().split(Regex("\\s+"), limit = 6) }
        for (path in ClusterThemePolicy.originalHashes.keys.filter { it.endsWith(".so") }) {
            val st = Os.stat(root + path)
            val rows = maps.filter { it.size == 6 && it[5] == path }
            check(rows.isNotEmpty() && maps.none { it.size == 6 && it[5].substringBefore(" (deleted)").substringAfterLast('/') == path.substringAfterLast('/') && it[5] != path }) { "Unexpected/deleted mapped artifact: $path" }
            for (row in rows) {
                val dev = row[3].split(':'); check(dev.size == 2)
                val major = (st.st_dev ushr 8 and 0xfff) or (st.st_dev ushr 32 and 0xfffff000L)
                val minor = (st.st_dev and 0xff) or (st.st_dev ushr 12 and 0xffffff00L)
                check(row[4].toLong() == st.st_ino && dev[0].toLong(16) == major && dev[1].toLong(16) == minor) { "Mapped artifact inode/device mismatch: $path" }
            }
        }
        val executable = Os.stat("/proc/$renderer/exe")
        val expected = Os.stat(root + "/system/bin/qtandroidnative")
        check(executable.st_dev == expected.st_dev && executable.st_ino == expected.st_ino) { "Renderer executable identity mismatch" }
    }
    private fun ownedFile(): File {
        if (transport == ClusterThemeTransport.ANDROID_IVI) {
            check(ClusterThemePolicy.ownedName(filename))
            val storage = ClusterThemeAndroidStorage.directory(false)
            check(root == storage.path && identity == storage.identity) { "Android carrier directory generation changed" }
            return File(root, filename)
        }
        check(ClusterThemePolicy.ownedName(filename) && root == "/proc/$renderer/root")
        checkRendererIdentity()
        return File(root + ClusterThemePolicy.HOST_DIRECTORY + filename)
    }
    private fun <T> withOwnedFile(action: (File) -> T): T {
        if (transport != ClusterThemeTransport.ANDROID_IVI) return action(ownedFile())
        check(ClusterThemePolicy.ownedName(filename))
        return ClusterThemeAndroidStorage.withDirectory { anchor, directory ->
            check(root == directory.path && identity == directory.identity) { "Android carrier parent identity changed" }
            action(File(anchor, filename))
        }
    }
    private fun cleanupOwned(allowPartial: Boolean): Boolean {
        if (root.isEmpty() || !ClusterThemePolicy.ownedName(filename) || createdInode < 0 || createdDevice < 0) return false
        retirement?.let {
            ClusterThemeRetiredStorage.verify(filename, identity, createdDevice, createdInode, variant.carrierSha256, it) { f -> hash(f, ClusterThemePolicy.MAX_CARRIER) }
            step("retired-carrier-preserved", "directory=${it.directory}")
            return true
        }
        return withOwnedFile { f ->
            val st = try { Os.lstat(f.path) } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) return@withOwnedFile true else throw error
            }
            check(if (transport == ClusterThemeTransport.ANDROID_IVI)
                OsConstants.S_ISREG(st.st_mode) && st.st_dev == createdDevice && st.st_ino == createdInode
                else ClusterThemePolicy.ownsObject(st.st_mode, st.st_uid, st.st_dev, st.st_ino, Process.myUid(), createdDevice, createdInode)) { "Owned carrier identity/type changed; retained" }
            check(allowPartial || hash(f, ClusterThemePolicy.MAX_CARRIER) == variant.carrierSha256) { "Owned carrier hash changed; retained" }
            val latest = Os.lstat(f.path)
            check(OsConstants.S_ISREG(latest.st_mode) && latest.st_dev == createdDevice && latest.st_ino == createdInode) { "Carrier name replaced before deletion; retained" }
            check(f.delete()) { "Owned carrier removal failed" }
            step("owned-carrier-removed", "device=$createdDevice inode=$createdInode")
            true
        }
    }

    private fun checkDeadline(allowRecovery: Boolean = false) {
        if (!allowRecovery || deadline > 0) check(deadline == 0L || SystemClock.elapsedRealtime() < deadline) { "Preflight deadline exceeded" }
    }
    private fun hash(file: File, limit: Long): String {
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        return FileInputStream(fd).use { input ->
            val before = Os.fstat(input.fd)
            check(OsConstants.S_ISREG(before.st_mode) && before.st_size in 1..limit) { "Unsupported artifact type/size: ${file.name}" }
            val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var total = 0L
            while (true) { checkDeadline(true); check(SystemClock.elapsedRealtime() < hashDeadline) { "Artifact hashing deadline exceeded" }; check(state != "PREFLIGHT" || !restoreRequested.get()) { "Restore requested during preflight" }; val n = input.read(buffer); if (n < 0) break; total += n; check(total <= limit); md.update(buffer, 0, n) }
            val after = Os.fstat(input.fd)
            check(total == before.st_size && after.st_size == before.st_size && after.st_mtime == before.st_mtime && after.st_ino == before.st_ino) { "Artifact changed while hashing" }
            hex(md.digest())
        }
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    internal data class Exchange(val native: Int?, val query: ClusterThemeProtocol.Response?, val responses: List<ClusterThemeProtocol.Response>,
        val unknown: Boolean, val error: String? = null, val attempted: Boolean = false, val retirementEligible: Boolean = false)
    internal fun recordLoadEvidence(load: Exchange) {
        loadRequestReceipt = "attempted=${load.attempted} native=${load.native ?: "UNKNOWN"} uncertain=${load.unknown}"
        loadSelectionReceipt = if (load.query?.selected(filename) == true) "OWN_PATH_REPORTED" else "NOT_CONFIRMED"
        loadRegistrationReceipt = if (load.responses.any { ClusterThemePolicy.loadAckMatches(it, filename) }) "RECEIVED_UNCORRELATED" else "NOT_RECEIVED"
    }
    internal fun recoveredGenerationPresent(probe: () -> Unit = { Os.lstat("/proc/$helperPid"); Unit },
        readStart: () -> String = { procStart(helperPid) }): Boolean {
        try { probe() } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return false else throw error
        }
        val start = try { readStart() } catch (error: Exception) {
            // A read failure is not evidence of exit; only an explicit second ENOENT is.
            try { probe() } catch (missing: ErrnoException) {
                if (missing.errno == OsConstants.ENOENT) return false else throw missing
            }
            throw error
        }
        return start == helperStart
    }
    private fun reapRecoveredHelper() {
        if (helperPid <= 0) { check(helperToken.isEmpty() || journalTrusted); return }
        helperUnreaped = true
        if (recoveredGenerationPresent()) {
            val path = File("/proc/$helperPid")
            val argv = File(path, "cmdline").inputStream().use { input ->
                val bytes = ByteArray(4097); var size = 0
                while (size < bytes.size) { val n = input.read(bytes, size, bytes.size - size); if (n < 0) break; size += n }
                check(size in 1..4096) { "Recorded helper argv unsupported" }
                bytes.copyOf(size).toString(Charsets.UTF_8).split('\u0000')
            }
            val helperClass = if (transport == ClusterThemeTransport.ANDROID_IVI) "com.byd.dashcast.proxy.daemon.ClusterThemeIviHelper" else "com.byd.dashcast.proxy.daemon.ClusterThemeHelper"
            check(Os.lstat(path.path).st_uid == Process.myUid() && argv.contains(helperClass) &&
                argv.contains(helperToken) && argv.contains(filename) && recoveredGenerationPresent()) { "Recovered helper identity mismatch; no kill or native call" }
            Process.killProcess(helperPid)
            val until = SystemClock.elapsedRealtime() + 1500
            while (recoveredGenerationPresent() && SystemClock.elapsedRealtime() < until) Thread.sleep(25)
            check(!recoveredGenerationPresent()) { "Recovered helper remains alive or unreadable" }
        }
        step("recovered-helper-gone", "pid=$helperPid start=$helperStart")
        helperPid = 0; helperStart = ""; helperToken = ""; helperUnreaped = false
        finishDashboardSwitch()
    }

    /** Independent native-mode ownership; RCC recovery never overwrites this debt. */
    private fun reapDashboardHelper() {
        val pid = dashboardHelperPid
        if (pid == 0) return
        fun present(): Boolean {
            try { Os.lstat("/proc/$pid") } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) return false else throw error
            }
            return try { procStart(pid) == dashboardHelperStart } catch (error: Exception) {
                try { Os.lstat("/proc/$pid") } catch (missing: ErrnoException) {
                    if (missing.errno == OsConstants.ENOENT) return false else throw missing
                }
                throw error
            }
        }
        if (present()) {
            val argv = File("/proc/$pid/cmdline").inputStream().use { input ->
                val bytes = ByteArray(4097); var count = 0
                while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; count += n }
                check(count in 1..4096)
                bytes.copyOf(count).toString(Charsets.UTF_8).split('\u0000')
            }
            check(Os.lstat("/proc/$pid").st_uid == Process.myUid() && present() &&
                argv.contains("com.byd.dashcast.proxy.daemon.ClusterNativeModeHelper") &&
                argv.contains(dashboardHelperToken) && argv.contains(dashboardHelperCommand.toString()) && argv.contains(filename)) {
                "Recorded native helper identity differs; no kill or replay"
            }
            Process.killProcess(pid)
            val until = SystemClock.elapsedRealtime() + 1500
            while (present() && SystemClock.elapsedRealtime() < until) Thread.sleep(25)
            check(!present()) { "Native dashboard helper remains alive/unreadable; native debt retained" }
        }
        dashboardHelperPid = 0; dashboardHelperStart = ""; dashboardHelperToken = ""; dashboardHelperCommand = -1
        dashboardIntervals.nativeFinished()
        persist(state)
    }

    private fun procStart(pid: Int): String = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))[19]
    private fun exchange(operation: String): Exchange {
        check(!helperUnreaped) { "Previous helper was not reaped; no further native IPC" }
        val apk = System.getenv("CLASSPATH") ?: error("Daemon APK classpath unavailable")
        check(apk.startsWith("/") && File(apk).isFile) { "Unsupported daemon classpath" }
        helperToken = UUID.randomUUID().toString().replace("-", "")
        helperPid = 0; helperStart = ""
        persist(state) // ARMING is durable before fork; helper cannot call OEM before GO.
        val record = File(directory, "helper-$helperToken.json")
        val args = mutableListOf("/system/bin/app_process64", "-Xnoimage-dex2oat", "/system/bin",
            if (transport == ClusterThemeTransport.ANDROID_IVI) "com.byd.dashcast.proxy.daemon.ClusterThemeIviHelper" else "com.byd.dashcast.proxy.daemon.ClusterThemeHelper",
            operation, filename, helperToken)
        if (transport == ClusterThemeTransport.ANDROID_IVI) {
            args.add(variant.id); args.add(createdDevice.toString()); args.add(createdInode.toString()); args.add(identity)
        }
        val child = try {
            ProcessBuilder(args).redirectErrorStream(true).apply { environment()["CLASSPATH"] = apk }.start().also { helperUnreaped = true }
        } catch (error: Throwable) { helperToken = ""; throw error }
        val capture = ByteArrayOutputStream()
        val overflow = AtomicBoolean(false)
        val reader = Thread({
            runCatching {
                child.inputStream.use { stream ->
                    val b = ByteArray(4096)
                    while (true) { val n = stream.read(b); if (n < 0) break; synchronized(capture) {
                        if (capture.size() + n <= 262144) capture.write(b, 0, n) else overflow.set(true)
                    } }
                }
            }
        }, "cluster-live-result").apply { isDaemon = true; start() }
        var terminated = false
        var traceCaptured = false
        var helperStage = "readiness"
        try {
            val readyUntil = SystemClock.elapsedRealtime() + 3000
            step("helper-readiness-wait", "operation=$operation deadlineMs=$readyUntil")
            val ready = ClusterThemeHelperReadiness.await(helperToken, filename, readyUntil, child::isAlive,
                { ClusterThemeHelperReadiness.read(record) }, SystemClock::elapsedRealtime, { Thread.sleep(25) })
            helperPid = ready.getInt("pid"); helperStart = ready.getString("start")
            check(helperPid > 0 && helperStart.matches(Regex("[0-9]+")) && procStart(helperPid) == helperStart && Os.stat("/proc/$helperPid").st_uid == Process.myUid())
            helperStage = "admission"
            if (transport == ClusterThemeTransport.ANDROID_IVI) {
                check(validProjection()) { "Projection reservation expired before helper GO" }
                if (operation == "load") check(!restoreRequested.get()) { "Restore requested before load GO" }
            }
            persist(state) // PID/start identity fsynced BEFORE permitting any OEM call.
            step("helper-GO", "operation=$operation pid=$helperPid start=$helperStart token=$helperToken")
            child.outputStream.write("GO\n".toByteArray()); child.outputStream.flush()
            helperStage = "result"
            val until = SystemClock.elapsedRealtime() + 8000
            while (SystemClock.elapsedRealtime() < until && !child.waitFor(100, TimeUnit.MILLISECONDS)) { /* owned bounded child */ }
            if (child.isAlive) {
                child.destroyForcibly(); terminated = child.waitFor(1000, TimeUnit.MILLISECONDS)
                uncertain = true
                val partial = synchronized(capture) { capture.toString("UTF-8") }.takeLast(12000)
                raw = (raw + "\n$operation: helper deadline; remote completion UNKNOWN; reaped=$terminated\n" + partial).takeLast(16000)
                partial.lineSequence().filter { it.startsWith("DASHCAST_IVI_STEP=") }.take(64).forEach { step("helper-partial", it.removePrefix("DASHCAST_IVI_STEP=")) }
                step("helper-timeout", "operation=$operation reaped=$terminated remoteCompletion=UNKNOWN")
                traceCaptured = true
                return Exchange(null, null, emptyList(), true)
            }
            terminated = true; reader.join(500)
            check(!reader.isAlive && !overflow.get()) { "Helper result incomplete/oversized" }
            val text = synchronized(capture) { capture.toString("UTF-8") }
            raw = (raw + "\n$operation: " + text.takeLast(12000)).takeLast(16000)
            val line = text.lineSequence().lastOrNull { it.startsWith("DASHCAST_RESULT=") } ?: error("Missing helper result")
            val j = JSONObject(line.removePrefix("DASHCAST_RESULT="))
            if (transport == ClusterThemeTransport.ANDROID_IVI) {
                check(j.getString("transport") == transport.id && j.getString("nativeIdentity") == "UNKNOWN")
                text.lineSequence().filter { it.startsWith("DASHCAST_IVI_STEP=javaIdentityVerified=") }.take(1)
                    .forEach { step("Java-identity", it.removePrefix("DASHCAST_IVI_STEP=")) }
                j.optJSONArray("trace")?.let { for (i in 0 until minOf(it.length(), 64)) step("helper", it.optString(i)) }
                traceCaptured = true
            }
            val responses = ArrayList<ClusterThemeProtocol.Response>()
            val array = j.optJSONArray("raw")
            if (array != null) for (i in 0 until array.length().coerceAtMost(32)) {
                runCatching { ClusterThemeProtocol.decode(Base64.decode(array.getString(i), Base64.NO_WRAP)) }.getOrNull()?.let(responses::add)
            }
            val post = j.optJSONArray("postQueryRaw")
            val queries = if (post == null) emptyList() else (0 until post.length().coerceAtMost(32)).mapNotNull {
                runCatching { ClusterThemeProtocol.decode(Base64.decode(post.getString(it), Base64.NO_WRAP)) }.getOrNull()
            }
            val queryOk = operation == "query" || j.optInt("queryNative", Int.MIN_VALUE) == 0
            return Exchange(if (j.has("native")) j.getInt("native") else null,
                if (queryOk) queries.lastOrNull { it.cmd == 1001 && it.sub == 4 } else null, responses, j.has("error"),
                if (j.has("error")) j.getString("error").take(1024) else null, j.optBoolean("attempted"), j.optBoolean("retirementEligible"))
        } catch (error: Throwable) {
            val partial = synchronized(capture) { capture.toString("UTF-8") }.takeLast(4000)
            raw = (raw + "\n$operation: helper stage=$helperStage failure=${error.javaClass.simpleName}: ${error.message}\n" + partial).takeLast(16000)
            step("helper-exchange-failed", "operation=$operation stage=$helperStage childAlive=${child.isAlive} outputBytes=${capture.size()} error=${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            if (!traceCaptured && transport == ClusterThemeTransport.ANDROID_IVI) {
                val partial = synchronized(capture) { capture.toString("UTF-8") }
                partial.lineSequence().filter { it.startsWith("DASHCAST_IVI_STEP=") }.take(64)
                    .forEach { step("helper-partial", it.removePrefix("DASHCAST_IVI_STEP=")) }
                step("helper-result-incomplete", "operation=$operation nativeCompletion=UNKNOWN")
            }
            if (!terminated) {
                child.destroyForcibly()
                if (!child.waitFor(1000, TimeUnit.MILLISECONDS)) {
                    helperUnreaped = true
                    error("Owned helper not reaped; no further native command permitted")
                }
                terminated = true
            }
            runCatching { child.inputStream.close() }; runCatching { child.outputStream.close() }; reader.join(200)
            if (terminated) helperUnreaped = false
            if (terminated && !helperUnreaped) {
                record.delete(); helperPid = 0; helperStart = ""; helperToken = ""; persistQuietly()
            }
        }
    }
}
