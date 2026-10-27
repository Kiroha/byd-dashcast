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
import kotlin.concurrent.withLock

/** Closed experiments sharing one ProxyDaemon journal/recovery lane. No OEM file is modified.
 * Status/restore calls do not wait on the command worker. A new daemon never replays a journal.
 */
internal object ClusterThemeLiveTest {
    private val directory = File("/data/local/tmp/dashcast-cluster-live")
    private val journal = File(directory, "journal.json")
    private val journalWriteLock = Any()
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
    @Volatile private var dashboardBootstrapStarted = false
    @Volatile private var dashboardSizeAdmitted: Boolean? = null
    @Volatile private var dashboardBootstrapRecoverySettled = false
    @Volatile private var dashboardSmallPanelSeen = false
    @Volatile private var dashboardGeometry = JSONObject()
    private val dashboardReadProbeActive = AtomicBoolean(false)
    private val fullAppAdasLock = java.util.concurrent.locks.ReentrantLock()
    private val fullAppAdasReads = java.util.concurrent.ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(), { r -> Thread(r, "full-app-adas-read").apply { isDaemon = true } })
    @Volatile private var fullAppAdasDebt = false
    @Volatile private var fullAppAdasRound = ""
    @Volatile private var fullAppAdasRestoring = false
    @Volatile private var fullAppAdasStage = "IDLE"
    private var fullAppAdasFrames = 0
    private var fullAppAdasRestore12 = false
    private var fullAppAdasRestore53 = false
    private var fullAppAdasProofUntil = 0L
    private var fullAppAdasProofTarget = ""
    @Volatile private var factoryApplyPending = false
    @Volatile private var factoryApplyCompleted = false
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
    @Volatile private var recoveredQuarantine = false
    @Volatile private var helperPid = 0
    @Volatile private var helperStart = ""
    @Volatile private var helperToken = ""
    @Volatile private var hashDeadline = Long.MAX_VALUE
    @Volatile private var transport = ClusterThemeTransport.STRICT_RENDERER
    @Volatile private var projectionValidity: android.os.IBinder? = null
    @Volatile private var retirement: ClusterThemeRetirement? = null
    @Volatile private var retirementEligibleUntil = 0L
    private val restartLease = com.byd.dashcast.clusterlive.ClusterRestartLease(SystemClock::elapsedRealtime)
    @Volatile private var restartDispatchPending = false
    @Volatile private var restartBootId = ""
    @Volatile private var restartDispatchRound = ""
    @Volatile private var restartCompleted: com.byd.dashcast.clusterlive.ClusterRestartCompletion? = null
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
        // Provenance is local to this daemon; a live failure never gains this read-only exception.
        recoveredQuarantine = journalTrusted && !saved.clean && saved.transport == ClusterThemeTransport.ANDROID_IVI
        cornerVerification = null
        loadRequestReceipt = "NOT_RECORDED"; loadSelectionReceipt = "NOT_RECORDED"; loadRegistrationReceipt = "NOT_RECORDED"
        defaultQueried = false; loadSettled = false; oemObserved = false
        markerObserved = false; themeObserved = false; carrierRemoved = false; deadline = 0
        transport = saved.transport; variant = saved.variant
        dashboardStage = saved.dashboardStage; dashboardNativeDebt = saved.dashboardNativeDebt
        dashboardSelectionReady = false; dashboardRound = ""
        dashboardBootstrapStarted = saved.dashboardBootstrapStarted
        dashboardSizeAdmitted = saved.dashboardSizeAdmitted
        dashboardBootstrapRecoverySettled = saved.dashboardBootstrapRecoverySettled
        dashboardSmallPanelSeen = saved.dashboardSmallPanelSeen
        fullAppAdasDebt = saved.fullAppAdasDebt; fullAppAdasRound = saved.fullAppAdasRound
        fullAppAdasRestoring = saved.fullAppAdasDebt
        fullAppAdasStage = if (saved.fullAppAdasDebt) "RECOVERY_REQUIRED" else "IDLE"
        fullAppAdasProofUntil = 0
        factoryApplyPending = saved.factoryApplyPending; factoryApplyCompleted = saved.factoryApplyCompleted
        dashboardGeometry = JSONObject() // Recovered geometry is never current admission evidence.
        dashboardHelperCommand = saved.dashboardHelperCommand
        dashboardHelperPid = saved.dashboardHelperPid; dashboardHelperStart = saved.dashboardHelperStart; dashboardHelperToken = saved.dashboardHelperToken
        retirement = saved.retirement; retirementEligibleUntil = 0
        restartDispatchPending = saved.restartDispatchPending
        restartBootId = saved.restartBootId
        restartDispatchRound = saved.restartRound; restartCompleted = saved.restartCompleted
        iviRecoverySettled = saved.iviRecoverySettled
        if (transport == ClusterThemeTransport.ANDROID_IVI) {
            filename = saved.filename; root = saved.root; identity = saved.identity
        }
        if (!saved.clean) {
            filename = saved.filename; renderer = saved.renderer; identity = saved.identity
            mutated = saved.mutated; uncertain = mutated || dashboardNativeDebt
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
            .put("recoveryPending", restoring.get() >= 0 || queryActive.get() && restoreRequested.get() && active.get())
            .put("recoveredQuarantine", recoveredQuarantine)
            .put("retirement", retirement?.json()).put("retirementAvailable", retirement == null && retirementEligibleUntil > SystemClock.elapsedRealtime())
            .put("carrierMovable", journalTrusted && active.get() && mutated && transport == ClusterThemeTransport.ANDROID_IVI &&
                retirement?.moved != true && createdInode >= 0 && ClusterThemePolicy.ownedName(filename) &&
                variant in com.byd.dashcast.clusterlive.ClusterRestartCohort.variants)
            .put("restartDispatchPending", restartDispatchPending)
            .apply { restartCompleted?.let { put("restartCompleted", it.json()) } }
            .put("journalSchema", if (retirement != null) "4" else if (transport == ClusterThemeTransport.ANDROID_IVI) "3" else "2")
            .put("carrierIdentity", "device=$createdDevice inode=$createdInode")
            .put("rccSafeNonce", if (transport == ClusterThemeTransport.ANDROID_IVI && journalTrusted && !active.get() &&
                state in setOf("CLEAN", "REFUSED") && ClusterThemePolicy.ownedName(filename)) filename.removePrefix(ClusterThemePolicy.PREFIX).removeSuffix(".rcc") else "")
            .put("daemonUid", Process.myUid()).put("renderer", renderer).put("identity", identity)
            .put("remainingMs", (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            .put("mutated", mutated).put("uncertain", uncertain).put("iviRecoverySettled", iviRecoverySettled).put("defaultQueried", defaultQueried)
            .put("carrierRemoved", carrierRemoved).put("markerObservedByUser", markerObserved).put("oemObservedByUser", oemObserved)
            .put("themeObservedByUser", themeObserved)
            .put("factoryApplyPending", factoryApplyPending).put("factoryApplyCompleted", factoryApplyCompleted)
            .put("fullAppAdas", JSONObject().put("debt", fullAppAdasDebt).put("round", fullAppAdasRound)
                .put("stage", fullAppAdasStage).put("restoring", fullAppAdasRestoring))
            .put("dashboard", JSONObject().put("stage", dashboardStage).put("nativeDebt", dashboardNativeDebt)
                .put("pending", dashboardPending.get()).put("helperCommand", dashboardHelperCommand)
                .put("selectionReady", dashboardSelectionReady).put("round", dashboardRound)
                .put("bootstrapStarted", dashboardBootstrapStarted).put("sizeAdmitted", dashboardSizeAdmitted ?: JSONObject.NULL)
                .put("bootstrapRecoverySettled", dashboardBootstrapRecoverySettled).put("smallPanelSeen", dashboardSmallPanelSeen)
                .put("geometry", dashboardGeometry)
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

    private fun checkQueryEligibility() {
        check(journalTrusted && (!active.get() || recoveredQuarantine && state == "QUARANTINE" &&
            transport == ClusterThemeTransport.ANDROID_IVI) && !helperUnreaped && helperPid == 0 &&
            dashboardHelperPid == 0 && !nativeMode.blocked() && restoring.get() == -1L && (!active.get() || !restoreRequested.get()) &&
            !dashboardPending.get() && !dashboardCloseQueued.get() && !dashboardReadProbeActive.get() &&
            observationPending.get() == 0 && !fullAppAdasLock.isLocked && productionModeCommands == 0) {
            "Live command, recovery, untrusted journal or recorded helper blocks read-only consultation; historical RCC debt is retained"
        }
    }

    @Synchronized fun querySelection(run: (() -> Unit) -> ClusterThemeQueryRunner.Result = ClusterThemeQueryRunner::run): String {
        initialize()
        checkQueryEligibility()
        check(queryActive.compareAndSet(false, true)) { "Read-only query already active or helper not reaped; no replay" }
        oemQuery = JSONObject().put("state", "PENDING").put("phase", "queued")
            .put("detail", "One read-only OEM selection query; no renderer discovery, carrier, load or restore.")
            .put("nativeIdentity", "UNKNOWN").put("receivedEpochMs", System.currentTimeMillis())
        worker.execute {
            var reaped = true
            try {
                val result = run { synchronized(this) { checkQueryEligibility() } }
                oemQuery = result.receipt; reaped = result.reaped
            } catch (error: Throwable) {
                reaped = false // An unexpected runner failure cannot establish that a child was reaped.
                oemQuery = JSONObject().put("state", "UNAVAILABLE").put("phase", "helper-start")
                    .put("detail", "${error.javaClass.simpleName}: ${error.message}; query child cleanup unconfirmed; further queries/tests blocked".take(768))
                    .put("nativeIdentity", "UNKNOWN").put("receivedEpochMs", System.currentTimeMillis())
            } finally {
                synchronized(this) {
                    if (reaped) {
                        queryActive.set(false)
                        if (restoreRequested.get() && active.get()) enqueueRestore()
                    }
                }
            }
        }
        return status()
    }

    @Synchronized fun start(source: ParcelFileDescriptor, parkedAssertion: Boolean,
        requestedVariant: ClusterThemeVariant = ClusterThemeVariant.MARKER,
        requestedTransport: ClusterThemeTransport = ClusterThemeTransport.STRICT_RENDERER,
        requestedNonce: String? = null, validity: android.os.IBinder? = null): String {
        if (requestedVariant.isRecoveryOnly) {
            source.close()
            val refused = JSONObject(status()).put("activationRefused", "Retired full-app profile is recovery-only; offline candidate is not admitted")
            if (requestedTransport == ClusterThemeTransport.ANDROID_IVI) refused.put("rccRejectedNonce", requestedNonce)
            return refused.toString()
        }
        initialize()
        try {
            val st = Os.fstat(source.fileDescriptor)
            check(ClusterThemePolicy.permitsCarrierSource(st.st_mode, st.st_size)) { "Source must be a bounded regular file (pipes/devices refused)" }
        } catch (t: Throwable) { source.close(); throw t }
        if (!parkedAssertion || !journalTrusted || helperUnreaped || productionModeCommands != 0 ||
            requestedVariant.isDashboard && dashboardSmallPanelSeen ||
            queryActive.get() || nativeMode.blocked() || !active.compareAndSet(false, true)) {
            source.close()
            return if (requestedTransport == ClusterThemeTransport.ANDROID_IVI) JSONObject(status()).put("rccRejectedNonce", requestedNonce).toString() else status()
        }
        val thisGeneration = generation.incrementAndGet()
        recoveredQuarantine = false
        restoreRequested.set(false); observationPending.set(0)
        mutated = false; uncertain = false; loadSettled = false; iviRecoverySettled = false; defaultQueried = false
        retirement = null; retirementEligibleUntil = 0
        resetRestartHistory()
        cornerVerification = null
        markerObserved = false; themeObserved = false; oemObserved = false; carrierRemoved = false; raw = ""
        loadRequestReceipt = "NOT_RECORDED"; loadSelectionReceipt = "NOT_RECORDED"; loadRegistrationReceipt = "NOT_RECORDED"
        variant = requestedVariant; transport = requestedTransport; projectionValidity = validity
        dashboardStage = "NONE"; dashboardNativeDebt = false; dashboardHelperCommand = -1
        dashboardSelectionReady = false; dashboardRound = ""
        dashboardBootstrapStarted = false; dashboardSizeAdmitted = false; dashboardBootstrapRecoverySettled = false
        dashboardGeometry = JSONObject()
        fullAppAdasDebt = false; fullAppAdasRound = ""; fullAppAdasRestoring = false; fullAppAdasStage = "IDLE"
        factoryApplyPending = false; factoryApplyCompleted = false
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
                    if (variant.isDashboard) prepareDashboardDisplay()
                    if (transport == ClusterThemeTransport.ANDROID_IVI) {
                        ownedFile(); check(validProjection()) { "Projection reservation expired before load" }
                    } else checkRendererIdentity()
                    checkDeadline(); check(!restoreRequested.get()) { "Restore requested; load cancelled" }
                    phase = "load"
                    awaitDashboardSwitch(false)
                    if (variant.isDashboard) requireDashboardDisplay()
                    phase = "load"
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
                    if (mutated || dashboardNativeDebt || dashboardHelperPid != 0) {
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
        check(parked && firmware && journalTrusted && (!queryActive.get() || active.get() && recoveredQuarantine) &&
            !nativeMode.blocked() && productionModeCommands == 0)
        projectionValidity = validity
        check(validProjection()) { "Idle projection reservation expired; no recovery request sent" }
        val expected = ClusterThemePolicy.PREFIX + nonce + ".rcc"
        if (!active.get()) {
            // QUEUED is durable before a start can fork. Idle trusted state proves no outstanding load.
            check(!helperUnreaped && helperPid == 0)
            transport = ClusterThemeTransport.ANDROID_IVI; filename = expected; renderer = ""; root = ""; identity = ""
            mutated = false; uncertain = false; defaultQueried = false; loadSettled = false; iviRecoverySettled = false
            retirement = null; retirementEligibleUntil = 0
            resetRestartHistory()
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

    private fun currentDashboardOpening(): Boolean = validProjection() && !restoreRequested.get() && !dashboardCloseQueued.get()

    private fun readDashboardGeometry(budgetMs: Long): com.byd.dashcast.clusterlive.ClusterDashboardDisplayPolicy.Evidence {
        check(dashboardReadProbeActive.compareAndSet(false, true)) { "A display snapshot is already active" }
        try {
            val result = ClusterThemeQueryRunner.dashboardDisplay(filename, budgetMs, ::currentDashboardOpening) {
                check(currentDashboardOpening()) { "Display query cancelled before GO" }
            }
            if (!result.reaped || result.receipt.optString("state") != "RECEIVED" || !result.receipt.has("geometry"))
                return com.byd.dashcast.clusterlive.ClusterDashboardDisplayPolicy.Evidence("UNAVAILABLE",
                    "Display snapshot unavailable: ${result.receipt.optString("detail").take(256)}", emptyList())
            return runCatching { com.byd.dashcast.clusterlive.ClusterDashboardDisplayPolicy.fromJson(result.receipt.getJSONObject("geometry")) }
                .getOrElse { com.byd.dashcast.clusterlive.ClusterDashboardDisplayPolicy.Evidence("UNAVAILABLE", "Malformed display snapshot", emptyList()) }
        } finally { dashboardReadProbeActive.set(false) }
    }

    /** Independent read-only lane: never hold the live-test monitor while a child runs. */
    fun currentDashboardDisplay(nonce: String): String {
        initialize()
        val expected = synchronized(this) {
            check(nonce.matches(Regex("[0-9a-f]{32}")) && filename == ClusterThemePolicy.PREFIX + nonce + ".rcc" &&
                variant.isDashboard && active.get() && dashboardStage == "OPEN_RECEIVED" &&
                !dashboardPending.get() && !uncertain && currentDashboardOpening()) { "Current dashboard opening required" }
            generation.get()
        }
        val evidence = readDashboardGeometry(8000)
        synchronized(this) {
            check(generation.get() == expected && dashboardStage == "OPEN_RECEIVED" && currentDashboardOpening() && !uncertain) {
                "Display snapshot belongs to a retired dashboard opening" }
            recordDashboardGeometry(evidence)
            return status()
        }
    }

    private fun recordDashboardGeometry(evidence: com.byd.dashcast.clusterlive.ClusterDashboardDisplayPolicy.Evidence,
        write: () -> Unit = { persist(state) }) {
        dashboardGeometry = evidence.json().put("receivedEpochMs", System.currentTimeMillis())
            .put("receivedElapsedMs", SystemClock.elapsedRealtime())
        if (evidence.verdict == "SMALL") dashboardSmallPanelSeen = true
        step("dashboard-display", dashboardGeometry.toString())
        write()
    }

    internal fun prepareDashboardDisplay(
        probe: (Long) -> com.byd.dashcast.clusterlive.ClusterDashboardDisplayPolicy.Evidence = ::readDashboardGeometry,
        bootstrap: () -> Unit = { runDashboardSequence(true, bootstrap = true,
            finished = { dashboardIntervals.nativeFinishedAfter(com.byd.dashcast.clusterlive.ClusterDashboardProfile.BOOTSTRAP_GAP_MS) }) },
        factory: () -> Boolean = { exchange("query").let { !it.unknown && it.native == 0 && it.query?.isDefault() == true } },
        write: () -> Unit = { persist(state) }, clock: () -> Long = SystemClock::elapsedRealtime,
        pause: (Long) -> Unit = Thread::sleep) {
        check(variant.isDashboard && !dashboardSmallPanelSeen && !mutated && currentDashboardOpening())
        phase = "dashboard-display-query"
        var evidence = probe(8000); recordDashboardGeometry(evidence, write)
        if (evidence.verdict == "ABSENT") {
            check(currentDashboardOpening() && factory()) { "Factory selection is not freshly confirmed before bootstrap" }
            check(currentDashboardOpening())
            bootstrap()
            phase = "dashboard-display-wait"; write()
            val until = clock() + com.byd.dashcast.clusterlive.ClusterDashboardProfile.DISPLAY_WAIT_MS
            var attempts = 0
            while (true) {
                check(currentDashboardOpening()) { "Display wait cancelled; bootstrap recovery remains required" }
                val remaining = until - clock()
                check(remaining > 0 && attempts++ < com.byd.dashcast.clusterlive.ClusterDashboardProfile.DISPLAY_PROBE_LIMIT) {
                    "OEM projection display did not appear within the bounded wait" }
                evidence = probe(minOf(8000L, remaining)); recordDashboardGeometry(evidence, write)
                check(currentDashboardOpening()) { "Display result belongs to a cancelled opening" }
                if (evidence.verdict != "ABSENT") break
                // One completed read at a time; no persistent background enumeration.
                val next = minOf(until, clock() + com.byd.dashcast.clusterlive.ClusterDashboardProfile.DISPLAY_PROBE_GAP_MS)
                while (clock() < next) {
                    check(currentDashboardOpening()) { "Display wait cancelled" }
                    pause(minOf(25, next - clock()))
                }
            }
        }
        check(evidence.ready && !dashboardSmallPanelSeen && currentDashboardOpening()) {
            "Projection display refused: ${evidence.verdict}: ${evidence.reason}" }
        phase = "dashboard-display-ready"; write()
    }

    private fun requireDashboardDisplay() {
        check(currentDashboardOpening() && !dashboardSmallPanelSeen)
        val evidence = readDashboardGeometry(8000)
        recordDashboardGeometry(evidence)
        check(evidence.ready && !dashboardSmallPanelSeen && currentDashboardOpening()) {
            "Current projection display refused: ${evidence.verdict}: ${evidence.reason}" }
    }

    /** A closed sequence, never an arbitrary caller-supplied AutoContainer verb. */
    /** One fixed step on the pinned diagnostic endpoint; no production shell escape hatch. */
    fun fullAppAdas(nonce: String, round: String, action: Int, value: Int, count: Int,
        recovering: Boolean, pkg: String, display: Int, validity: android.os.IBinder): String = fullAppAdasLock.withLock {
        initialize()
        synchronized(this) {
            check(!queryActive.get()) { "Read-only query helper must finish before an ADAS operation" }
        }
        check(recovering || !variant.isRecoveryOnly) { "New full-app masking is disabled; existing recovery debt is retained" }
        val api = com.byd.dashcast.clusterlive.ClusterFullAppAdas
        check(api.permits(action, value, count, recovering)) { "Unsupported fixed ADAS step" }
        check(journalTrusted && active.get() && variant.isFullApp &&
            filename == ClusterThemePolicy.PREFIX + nonce + ".rcc" &&
            round.matches(Regex("[0-9a-f]{32}"))) { "Owned full-app recovery context required" }
        fun current(): Boolean {
            if (!validity.isBinderAlive) return false
            val d = android.os.Parcel.obtain(); val r = android.os.Parcel.obtain()
            return try {
                d.writeInterfaceToken("com.byd.dashcast.RccReservation")
                validity.transact(1, d, r, 0) && run { r.readException(); r.dataAvail() == 4 && r.readInt() == 1 }
            } catch (_: Exception) { false } finally { d.recycle(); r.recycle() }
        }
        check(current()) { "Full-app reservation expired" }
        if (recovering) {
            check(fullAppAdasDebt && round == fullAppAdasRound) { "No matching ADAS recovery debt" }
        } else {
            check(dashboardRound == round && dashboardStage == "OPEN_RECEIVED" && dashboardSelectionReady &&
                !dashboardPending.get() && !dashboardCloseQueued.get() && !restoreRequested.get() &&
                restoring.get() == -1L && !uncertain && !fullAppAdasRestoring) { "Current full-app opening required" }
        }
        recoveredQuarantine = false
        var accepted = false
        try {
            when (action) {
                api.JOURNAL -> {
                    check(!fullAppAdasDebt || fullAppAdasRound == round)
                    fullAppAdasRound = round; fullAppAdasDebt = true; fullAppAdasStage = "MASKING"
                    persist(state) // Mutation authority exists only after this durable write.
                    accepted = true
                }
                api.RECOVERY -> {
                    fullAppAdasRestoring = true; fullAppAdasStage = "RESTORING"
                    fullAppAdasFrames = 0; fullAppAdasRestore12 = false; fullAppAdasRestore53 = false
                    fullAppAdasProofUntil = 0
                    persist(state); accepted = true
                }
                api.RESTORED -> {
                    check(fullAppAdasRestoring && fullAppAdasFrames >= 40 && fullAppAdasRestore12 && fullAppAdasRestore53)
                    fullAppAdasDebt = false; fullAppAdasRestoring = false; fullAppAdasStage = "RESTORE_DELIVERED"
                    persist(state); accepted = true
                }
                else -> {
                    check(fullAppAdasDebt && round == fullAppAdasRound)
                    check(!recovering || fullAppAdasRestoring) { "Begin explicit ADAS recovery first" }
                    val ctx = ProxyDaemonMain.adasControlContext() ?: error("Existing ADAS context unavailable")
                    check(com.byd.dashcast.cluster.AndroidDevice.supportsFirmware(android.os.Build.VERSION.SDK_INT,
                        variant.isFullApp, android.os.Build.FINGERPRINT)) { "Existing ADAS firmware contract differs" }
                    val receiver = ctx.packageManager.getReceiverInfo(com.byd.dashcast.cluster.NativeAdasWindowControl.RECEIVER, 0)
                    check(receiver.enabled && receiver.applicationInfo.enabled && receiver.exported && receiver.permission.isNullOrEmpty())
                    fun proveCurrent() {
                        check(current())
                        if (recovering) return
                        check(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && display > 0 && pkg != "com.byd.dashcast")
                        val target = "$round/$pkg/$display"
                        if (fullAppAdasProofTarget != target || SystemClock.elapsedRealtime() >= fullAppAdasProofUntil) {
                            val proofDeadline = SystemClock.elapsedRealtime() + 2_500
                            val probe = fullAppAdasReads.submit<Boolean> {
                                val dump = ProxyShell.exec("dumpsys activity activities", 2_000, 1_048_576)
                                dump.exit == 0 && com.byd.dashcast.cluster.ResumedProjection.matches(dump.output, display, pkg) &&
                                    AdasBodyworkReader.read(ctx, 0) == 2 && AdasBodyworkReader.read(ctx, 0x12d0002a) in setOf(2, 3)
                            }
                            // Recovery can revoke this hide while a vendor read is blocked.
                            fullAppAdasLock.unlock()
                            val proven = try { probe.get(2_500, TimeUnit.MILLISECONDS) }
                                finally { probe.cancel(true); fullAppAdasLock.lock() }
                            check(proven && !fullAppAdasRestoring && fullAppAdasDebt && round == fullAppAdasRound)
                            check(current() && SystemClock.elapsedRealtime() < proofDeadline)
                            fullAppAdasProofTarget = target; fullAppAdasProofUntil = SystemClock.elapsedRealtime() + 4_000
                        }
                    }
                    if (action == api.COMMAND) {
                        if (recovering) check(if (value == 12) fullAppAdasFrames >= 40 else fullAppAdasRestore12)
                        dashboardIntervals.awaitNative { current() }
                    }
                    proveCurrent() // Waiting for the shared native interval can expire source proof.
                    val command = when (action) {
                        api.INITIALIZE -> "cmd deviceidle whitelist +com.byd.clusterdebug >/dev/null 2>&1; am startservice -n com.byd.clusterdebug/.ClusterDebugService"
                        api.COMMAND -> "if service check AutoContainer | grep -q 'not found'; then service call auto_container 2 i32 1000 i32 $value s16 ''; else service call AutoContainer 2 i32 1000 i32 $value s16 ''; fi"
                        api.FRAMES -> "am broadcast -a com.byd.cluster.spi -p com.byd.clusterdebug --es wholeFrame '${api.frames[value]}' >/dev/null 2>&1"
                        else -> error("Unsupported ADAS mutation")
                    }
                    val batchDeadline = SystemClock.elapsedRealtime() + 4_000
                    accepted = true
                    var nativeAttempted = false
                    try {
                        repeat(if (action == api.FRAMES) count else 1) {
                            proveCurrent()
                            val remaining = batchDeadline - SystemClock.elapsedRealtime()
                            check(remaining > 0) { "ADAS batch deadline; remaining frames cancelled" }
                            if (action == api.COMMAND) nativeAttempted = true
                            val result = ProxyShell.exec("$command && echo DASHCAST_ADAS_OK", remaining, 8_192)
                            check(result.exit == 0 && com.byd.dashcast.cluster.AdasCommandResult.accepted(result.output) && current())
                        }
                    } finally { if (nativeAttempted) dashboardIntervals.nativeFinished() }
                    if (accepted && recovering) {
                        if (action == api.FRAMES) fullAppAdasFrames += count
                        if (action == api.COMMAND && value == 12) fullAppAdasRestore12 = true
                        if (action == api.COMMAND && value == 53) fullAppAdasRestore53 = true
                    }
                    if (!accepted) fullAppAdasStage = "RECOVERY_REQUIRED"
                }
            }
        } catch (error: Exception) {
            accepted = false
            if (action == api.RESTORED) fullAppAdasDebt = true
            fullAppAdasStage = "RECOVERY_REQUIRED"
            step("full-app-adas-unresolved", "action=$action delivery=UNKNOWN type=${error.javaClass.simpleName}")
        }
        step("full-app-adas-step", "action=$action recovery=$recovering accepted=$accepted debt=$fullAppAdasDebt pixels=UNKNOWN")
        JSONObject(status()).put("fullAppAdasAccepted", accepted).toString()
    }

    @Synchronized fun requestDashboard(open: Boolean, nonce: String, parked: Boolean, firmware: Boolean,
        validity: android.os.IBinder): String {
        initialize()
        check(!open || !variant.isRecoveryOnly) { "Retired full-app profile cannot open; close and Restore remain available" }
        check(parked && firmware && journalTrusted && active.get() && variant.isDashboard && mutated &&
            transport == ClusterThemeTransport.ANDROID_IVI && productionModeCommands == 0 && !queryActive.get() &&
            !nativeMode.blocked() && !helperUnreaped && restoring.get() == -1L && (!open || !restoreRequested.get())) {
            "Dashboard selection or recovery not settled; no native sequence sent"
        }
        check(filename == ClusterThemePolicy.PREFIX + nonce + ".rcc") { "Dashboard nonce differs" }
        check(!open || dashboardSelectionReady && phase == "observation" && !uncertain && dashboardStage in setOf("NONE", "CLOSED_RECEIVED")) { "Dashboard open requires a settled owned selection" }
        check(open || dashboardNativeDebt || dashboardPending.get()) { "No dashboard native mode debt to close" }
        projectionValidity = validity; check(validProjection())
        recoveredQuarantine = false
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
                if (open) requireDashboardDisplay()
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
        finished: () -> Unit = dashboardIntervals::nativeFinished, reap: () -> Unit = ::reapDashboardHelper,
        bootstrap: Boolean = false, factoryApply: Boolean = false) {
        check(variant.isDashboard && journalTrusted && validProjection() && !helperUnreaped)
        check(!fullAppAdasDebt) { "Complete the existing full-app ADAS restoration before native mode commands" }
        reap()
        check(!bootstrap || open && (!mutated || factoryApply) && !dashboardSmallPanelSeen)
        check(!factoryApply || factoryApplyPending && if (open) bootstrap && !dashboardNativeDebt else dashboardNativeDebt)
        val commands = when {
            factoryApply && !open -> if (dashboardSizeAdmitted == true) com.byd.dashcast.clusterlive.ClusterDashboardProfile.closeCommands
                else com.byd.dashcast.clusterlive.ClusterDashboardProfile.bootstrapCloseCommands
            bootstrap -> com.byd.dashcast.clusterlive.ClusterDashboardProfile.bootstrapCommands
            open -> com.byd.dashcast.clusterlive.ClusterDashboardProfile.openCommands
            dashboardSizeAdmitted == false -> com.byd.dashcast.clusterlive.ClusterDashboardProfile.bootstrapCloseCommands
            else -> com.byd.dashcast.clusterlive.ClusterDashboardProfile.closeCommands // Legacy receipt: do not invent prior admission.
        }
        dashboardStage = if (bootstrap) "NONE" else if (open) "OPENING" else "CLOSING"
        dashboardNativeDebt = true
        if (bootstrap && !factoryApply) dashboardBootstrapStarted = true
        dashboardBootstrapRecoverySettled = false
        oemObserved = false
        if (open && !bootstrap) { themeObserved = false; dashboardRound = UUID.randomUUID().toString().replace("-", "") }
        write()
        commands.forEach { command ->
            before { validProjection() && (!open || factoryApply || !restoreRequested.get() && !dashboardCloseQueued.get()) }
            check(validProjection() && (!open || factoryApply || !restoreRequested.get() && !dashboardCloseQueued.get())) { "Dashboard sequence retired before helper" }
            phase = if (bootstrap) "dashboard-bootstrap-$command" else "dashboard-native-$command"
            val previouslyAdmitted = dashboardSizeAdmitted
            val result = try { request(command, {
                check(validProjection() && (!open || factoryApply || !restoreRequested.get() && !dashboardCloseQueued.get())) { "Dashboard sequence retired before GO" }
                if (command == 31) dashboardSizeAdmitted = true // Durable conservative debt before GO.
                write() // Helper identity and native debt are durable before admission.
            }, { pid, start, token ->
                dashboardHelperPid = pid; dashboardHelperStart = start; dashboardHelperToken = token
                dashboardHelperCommand = command; write()
            }) } finally { finished() }
            if (result.reaped) {
                dashboardHelperPid = 0; dashboardHelperStart = ""; dashboardHelperToken = ""; dashboardHelperCommand = -1
            }
            val receipt = result.receipt
            if (command == 31 && result.reaped && receipt.has("attempted") && !receipt.getBoolean("attempted"))
                dashboardSizeAdmitted = previouslyAdmitted // The reaped helper proves it refused before sending.
            receipt.optJSONObject("geometry")?.let {
                recordDashboardGeometry(com.byd.dashcast.clusterlive.ClusterDashboardDisplayPolicy.fromJson(it), write)
            }
            step("dashboard-command-received", "command=$command reaped=${result.reaped} state=${receipt.optString("state")} native=${receipt.opt("native")} pixels=UNKNOWN")
            write()
            check(result.reaped && receipt.optString("state") == "RECEIVED" && receipt.optBoolean("attempted") &&
                receipt.has("native") && receipt.getInt("native") == 0) { "Native dashboard command $command unresolved; no replay" }
        }
        dashboardNativeDebt = open
        dashboardStage = if (bootstrap) "NONE" else if (open) "OPEN_RECEIVED" else "CLOSED_RECEIVED"
        phase = if (bootstrap) "dashboard-display-wait" else "observation"
        detail = if (bootstrap) "Factory projection bootstrap 16/35 returned; waiting for an observed OEM projection display. No custom RCC selected and no size-forcing command sent."
            else "Dashboard ${if (open) "opening" else "closing"} sequence returned. Native pixels and QML application require observation. Factory Restore is required before ordinary projection; older APK rollback remains unsupported."
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
        fun current() = journalTrusted && active.get() && variant.isDashboard && !variant.isRecoveryOnly && filename == name &&
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
        recoveredQuarantine = false
        cornerVerification = null
        restoreRequested.set(true)
        // The query owns its child until positively reaped; retain manual recovery intent meanwhile.
        if (active.get() && !queryActive.get()) enqueueRestore()
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
        recoveredQuarantine = false
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
        // No OEM receipt is required: a failed QML root can leave the OEM unable to answer. The worker reaps any stale helper first.
        check(!queryActive.get() && !nativeMode.blocked() && productionModeCommands == 0)
        // Outside the restart cohort no fission-all can follow, so the historical ACK3 admission remains.
        check(variant in com.byd.dashcast.clusterlive.ClusterRestartCohort.variants || retirement != null ||
            retirementEligibleUntil > SystemClock.elapsedRealtime()) { "First request Restore: a fresh ACK3 RESOURCE_IS_SAME and exact own selection are required" }
        check(retirement?.moved != true) { "Already retired: use Verify BYD return for a read-only check; no further move or OEM command" }
        recoveredQuarantine = false
        projectionValidity = validity; check(validProjection()) { "Projection reservation expired" }
        val expected = generation.get()
        check(restoring.compareAndSet(-1, expected)) { "Recovery worker already active" }
        restoreRequested.set(true); deadline = 0
        state = "RETIRING"; phase = "retirement-preflight"; defaultQueried = false; oemObserved = false; iviRecoverySettled = false
        worker.execute {
            try {
                check(generation.get() == expected && validProjection())
                reapRecoveredHelper(); hashDeadline = SystemClock.elapsedRealtime() + 60000
                // DataSource updateTranslateRccFromXml (0x21c068) resets a missing selected RCC to factory at its next start.
                check(validProjection())
                retirement = ClusterThemeRetiredStorage.move(filename, identity, createdDevice, createdInode, variant.carrierSha256,
                    retirement, { hash(it, ClusterThemePolicy.MAX_CARRIER) }) { checkpoint ->
                    check(validProjection()) { "Projection reservation expired during preparation" }
                    retirement = checkpoint; phase = if (checkpoint.moved) "carrier-retired" else "retirement-intent"
                    persist(state); check(validProjection()) { "Projection reservation expired after durable checkpoint" }
                    step(phase, "directory=${checkpoint.directory} device=${checkpoint.device} inode=${checkpoint.inode} fsync=PASS")
                }
                // After the move, so a failed close can never keep the faulty RCC selectable; failure keeps the debt.
                closeDashboardForFactoryRestore()
                retirementEligibleUntil = 0; uncertain = true
                state = "WAITING_OEM_REINITIALIZATION"; phase = "carrier-retired"
                detail = "Only our RCC moved and preserved. BYD DataSource resets a missing selected RCC to factory when it next starts; use the fission-all restart offered by Restore. Active graphics may remain until then; an Android/daemon reconnect does not restart DataSource."
                persist(state)
            } catch (error: Throwable) {
                uncertain = true; state = "QUARANTINE"; detail = "RCC retirement unconfirmed: ${error.message}".take(2000); persistQuietly()
            } finally { restoring.compareAndSet(expected, -1) }
        }
        return status()
    }

    /** The confirmation lease excludes every other diagnostic mutation on this daemon. */
    @Synchronized fun checkRestartOperation(operation: Int) {
        initialize()
        if (restartLease.held() || restartDispatchPending) check(operation in setOf(0, 27, 28, 29) ||
            retirement?.moved == true && operation in setOf(4, 13)) {
            "Cluster restoration owns recovery; cancel confirmation or verify the BYD return first"
        }
    }
    private fun verifyRestartFiles(nonce: String) {
        check(nonce.matches(Regex("[0-9a-f]{32}")) && filename == ClusterThemePolicy.PREFIX + nonce + ".rcc")
        check(journalTrusted && active.get() && mutated && transport == ClusterThemeTransport.ANDROID_IVI &&
            !queryActive.get() && restoring.get() == -1L && !helperUnreaped && helperPid == 0 && dashboardHelperPid == 0 &&
            !dashboardPending.get() && !dashboardCloseQueued.get() && !dashboardReadProbeActive.get() &&
            observationPending.get() == 0 && !fullAppAdasLock.isLocked && !fullAppAdasRestoring &&
            productionModeCommands == 0 && !nativeMode.blocked() && validProjection()) { "Recovery/helper/reservation state is not settled" }
        val retired = checkNotNull(retirement) { "Move our RCC first" }
        hashDeadline = SystemClock.elapsedRealtime() + 60000
        com.byd.dashcast.clusterlive.ClusterRestartCohort.verify(android.os.Build.VERSION.SDK_INT, variant) {
            hash(File(it), ClusterThemePolicy.MAX_ARTIFACT)
        }
        ClusterThemeRetiredStorage.verifyForRestart(filename, identity, createdDevice, createdInode, variant.carrierSha256,
            retired) { hash(it, ClusterThemePolicy.MAX_CARRIER) }
    }
    @Synchronized fun prepareRestart(nonce: String, validity: android.os.IBinder): String {
        initialize()
        check(!restartDispatchPending) { "A restart was already submitted; verify the physical BYD return, no replay" }
        projectionValidity = validity
        verifyRestartFiles(nonce)
        val round = restartLease.issue(UUID.randomUUID().toString().replace("-", ""))
        return JSONObject(status()).put("restartRound", round).toString()
    }
    @Synchronized fun confirmRestart(nonce: String, round: String): String {
        initialize()
        restartLease.consume(round, { verifyRestartFiles(nonce) }) {
            restartBootId = com.byd.dashcast.clusterlive.ClusterBootIdentity.read()
            restartDispatchRound = round
            restartDispatchPending = true; phase = "restart-dispatch-intent"
            // A replacement daemon recovers the retained debt; it never dispatches a restart itself.
            persist(state)
        }
        return JSONObject(status()).put("restartRound", round).put("restartAuthorized", true).toString()
    }
    @Synchronized fun cancelRestart(round: String): String {
        restartLease.cancel(round)
        return status()
    }
    internal fun resetRestartHistory() {
        check(!restartDispatchPending && !restartLease.held()) { "Outstanding restart intent cannot be discarded by a new session" }
        restartCompleted = null; restartBootId = ""; restartDispatchRound = ""
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
                if (!mutated && variant.isDashboard && dashboardBootstrapStarted) {
                    check(validProjection()) { "Explicit live reservation required for bootstrap recovery" }
                    hashDeadline = SystemClock.elapsedRealtime() + 60000
                    ownedFile()
                    restoreDashboardBootstrap()
                    maybeCleanup()
                    return@execute
                }
                if (!mutated) {
                    // Neither an RCC load nor a native bootstrap was admitted.
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
                    verifyRetired()
                    // A dashboard mode still open (close failed before the restart) is closed; never a load, restore or restart.
                    closeDashboardForFactoryRestore()
                    verifyRetired(); phase = "retired-read-only-query"
                    val queried = exchange("query")
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
                if (!(if (variant.isDashboard) selection.query.isFactoryConfigured() else selection.query.isDefault()) && !selection.query.selected(filename)) {
                    iviRecoverySettled = false
                    error("Another selection is active; no overwrite")
                }
                step("restore-prequery", "default=${selection.query.isDefault()} own=${selection.query.selected(filename)}")
                if (transport == ClusterThemeTransport.ANDROID_IVI && selection.query.selected(filename)) {
                    iviRecoverySettled = false
                    persist(state) // Revoke an older proof before a new mutating recovery attempt.
                }
                val restored = if (variant.isDashboard) restoreDashboardFactorySelection(selection)
                    else if (selection.query.isDefault()) selection else {
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

    internal fun restoreDashboardFactorySelection(selection: Exchange,
        selectFactory: () -> Exchange = {
            awaitDashboardSwitch(true)
            try { exchange("restore") } finally { finishDashboardSwitch() }
        }, close: () -> Unit = { closeDashboardForFactoryRestore() },
        query: () -> Exchange = { exchange("query") }, write: () -> Unit = { persist(state) }): Exchange {
        check(variant.isDashboard && !selection.unknown && selection.native == 0)
        val before = checkNotNull(selection.query)
        check(before.isFactoryConfigured() || before.selected(filename))
        var chosen = selection
        if (!before.isFactoryConfigured()) {
            factoryApplyPending = true; factoryApplyCompleted = false; write()
            chosen = selectFactory() // Select before the native close applies its scene transition.
        }
        // A sealed negative receipt authorizes only the explicit preservation path,
        // never a native close/apply sequence or a successful recovery checkpoint.
        if (chosen.retirementEligible) return chosen
        check(!chosen.unknown && chosen.native == 0 && chosen.query?.isFactoryConfigured() == true) {
            "Factory configuration is unresolved; native transition was not sent"
        }
        factoryApplyPending = chosen.query?.isDefault() != true; write()
        close()
        val after = query()
        factoryApplyPending = after.unknown || after.native != 0 || after.query?.isDefault() != true
        write()
        return after.copy(attempted = chosen.attempted, responses = chosen.responses + after.responses,
            retirementEligible = chosen.retirementEligible)
    }

    @Synchronized fun requestFactoryApply(nonce: String, parked: Boolean, firmware: Boolean,
        validity: android.os.IBinder): String {
        initialize()
        check(parked && firmware && journalTrusted && active.get() && variant.isDashboard && mutated &&
            filename == ClusterThemePolicy.PREFIX + nonce + ".rcc" && !dashboardNativeDebt &&
            !fullAppAdasDebt && factoryApplyPending && !helperUnreaped && dashboardHelperPid == 0 && !queryActive.get())
        projectionValidity = validity
        check(validProjection())
        recoveredQuarantine = false
        val expected = generation.get()
        check(restoring.compareAndSet(-1, expected)) { "Recovery is already pending" }
        restoreRequested.set(true)
        worker.execute {
            try {
                check(generation.get() == expected && validProjection())
                reapRecoveredHelper(); hashDeadline = SystemClock.elapsedRealtime() + 60000; ownedFile()
                val before = exchange("query")
                check(!before.unknown && before.native == 0 && before.query?.isFactoryConfigured() == true) {
                    "Fresh factory configuration required; no mode1 replay or native transition"
                }
                if (before.query?.isDefault() != true) {
                    factoryApplyCompleted = false; phase = "factory-apply-manual"; persist(state)
                    runDashboardSequence(true, bootstrap = true, factoryApply = true,
                        finished = { dashboardIntervals.nativeFinishedAfter(com.byd.dashcast.clusterlive.ClusterDashboardProfile.BOOTSTRAP_GAP_MS) })
                    runDashboardSequence(false, factoryApply = true)
                    factoryApplyCompleted = true; persist(state)
                }
                val after = exchange("query")
                factoryApplyPending = after.unknown || after.native != 0 || after.query?.isDefault() != true
                recordRestoreResult(checkNotNull(before.query), after, false)
                persist(state); maybeCleanup()
            } catch (error: Throwable) {
                factoryApplyPending = true; uncertain = true; state = "QUARANTINE"
                detail = "Manual factory application unresolved; no automatic retry: ${error.message}".take(2000)
                persistQuietly()
            } finally { restoring.compareAndSet(expected, -1) }
        }
        return status()
    }

    internal fun restoreDashboardBootstrap(query: () -> Exchange = { exchange("query") },
        close: () -> Unit = { closeDashboardForFactoryRestore() }, write: () -> Unit = { persist(state) }) {
        check(variant.isDashboard && dashboardBootstrapStarted && !mutated && validProjection())
        state = "RESTORING"; phase = "bootstrap-restore-query"; oemObserved = false
        dashboardBootstrapRecoverySettled = false; write()
        val before = query()
        check(!before.unknown && before.native == 0 && before.query?.isDefault() == true) {
            "Bootstrap recovery requires a fresh factory selection; no custom overwrite" }
        close()
        recordBootstrapRestoreResult(query())
        write()
    }

    internal fun recordBootstrapRestoreResult(queried: Exchange) {
        check(variant.isDashboard && dashboardBootstrapStarted && !mutated)
        defaultQueried = !queried.unknown && queried.native == 0 && queried.query?.isDefault() == true
        dashboardBootstrapRecoverySettled = defaultQueried && !dashboardNativeDebt && dashboardHelperPid == 0
        // This proof concerns only bootstrap 18/0 plus a fresh factory query.
        // It must never stand in for the custom-load recovery protocol.
        iviRecoverySettled = false
        state = if (dashboardBootstrapRecoverySettled) "DEFAULT_QUERIED" else "QUARANTINE"
        phase = "bootstrap-recovery-observation"
        detail = if (dashboardBootstrapRecoverySettled)
            "Bootstrap close returned and factory selection freshly queried. No custom RCC was selected; confirm the native instruments and warnings before closing recovery."
            else "Bootstrap recovery remains unresolved; native debt/helper retained. No custom RCC was selected."
        step("bootstrap-recovery-proof", "settled=$dashboardBootstrapRecoverySettled default=$defaultQueried nativeDebt=$dashboardNativeDebt")
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
                priorRecoveryProof && before.isDefault() && defaultQueried ||
                variant.isDashboard && defaultQueried && !dashboardNativeDebt && !fullAppAdasDebt &&
                    (factoryApplyCompleted || before.isDefault())
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
        // Same rule as the manual factory application: a fresh default report settles it.
        if (defaultQueried) factoryApplyPending = false
        state = if (defaultQueried) "DEFAULT_QUERIED" else "WAITING_OEM_REINITIALIZATION"
        detail = if (defaultQueried) "Retired inode/hash and absent original verified around fresh default query. Fresh physical OEM confirmation is still required. Preserved RCC will not be deleted."
            else "Original RCC absent and preserved; fresh default selection is not confirmed. No established manual reset procedure or automatic retry. Export a report; no additional RCC load or recovery command was sent. ${queried.error.orEmpty()}"
        step("retired-default-query", "native=${queried.native} unknown=${queried.unknown} default=$defaultQueried")
    }
    internal fun acceptOemObservation(expectedFilename: String): Boolean {
        if (!active.get() || !(mutated || dashboardBootstrapRecoverySettled) || !defaultQueried || filename != expectedFilename || !ClusterThemePolicy.ownedName(expectedFilename)) return false
        if (restartDispatchPending && runCatching { restartBootId == com.byd.dashcast.clusterlive.ClusterBootIdentity.read() }.getOrDefault(true)) return false
        oemObserved = true
        return true
    }

    @Synchronized fun observe(which: Int, expectedFilename: String, expectedVariant: ClusterThemeVariant = ClusterThemeVariant.MARKER): String {
        initialize()
        if (queryActive.get()) return status()
        if (which == 1 && variant.isCorner) return status()
        if (which == 1 && (variant != expectedVariant || state != "AWAITING_OBSERVATION" || uncertain || restoreRequested.get())) return status()
        // These are tester statements, never inferred from a callback or selection query.
        if (active.get() && (mutated || dashboardBootstrapRecoverySettled) && expectedFilename == filename && ClusterThemePolicy.ownedName(expectedFilename)) {
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
            if (restartDispatchPending && runCatching { restartBootId == com.byd.dashcast.clusterlive.ClusterBootIdentity.read() }.getOrDefault(true)) return
            if (fullAppAdasDebt || factoryApplyPending) return
            if (variant.isDashboard && (dashboardNativeDebt || dashboardHelperPid != 0)) return
            val recoveredIvi = transport == ClusterThemeTransport.ANDROID_IVI && iviRecoverySettled
            val recoveredBootstrap = variant.isDashboard && !mutated && dashboardBootstrapRecoverySettled && defaultQueried
            if (!carrierRemoved && oemObserved && (ClusterThemePolicy.canDeleteCarrier(defaultQueried, loadSettled, uncertain) || recoveredIvi && defaultQueried || recoveredBootstrap)) {
                check(cleanup()) { "Owned carrier cleanup identity unavailable; retained" }
                carrierRemoved = true
                detail = if (retirement != null) "Default queried; retired bytes preserved. A new test remains blocked until visual confirmation."
                    else "Default selection queried; only our verified carrier removed. Confirm OEM pixels; a new test remains blocked."
                write(state)
            }
            if (carrierRemoved && oemObserved && (!uncertain || recoveredIvi || recoveredBootstrap) && defaultQueried) {
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

    private fun persist(newState: String) = synchronized(journalWriteLock) {
        val completion = if (newState == "CLEAN" && restartDispatchPending)
            com.byd.dashcast.clusterlive.ClusterRestartCompletion(restartDispatchRound, restartBootId,
                com.byd.dashcast.clusterlive.ClusterBootIdentity.read()) else restartCompleted.takeIf { newState == "CLEAN" }
        if (newState == "CLEAN") {
            check(!restartDispatchPending || restartBootId != com.byd.dashcast.clusterlive.ClusterBootIdentity.read()) {
                "Restart outcome unknown: current boot has not changed; retain recovery debt"
            }
            check(!fullAppAdasDebt) { "Cannot close outstanding full-app ADAS recovery debt" }
            check(!factoryApplyPending) { "Factory selection still requires a manual application transition" }
            check(!helperUnreaped && helperPid == 0) { "Cannot close journal with an unreaped helper" }
            check(!variant.isDashboard || !dashboardNativeDebt && dashboardHelperPid == 0) { "Cannot close an outstanding dashboard mode debt" }
            check(!variant.isDashboard || !dashboardBootstrapStarted || mutated ||
                dashboardBootstrapRecoverySettled && defaultQueried && oemObserved) { "Bootstrap recovery requires fresh factory and human confirmation" }
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
            .put("restartDispatchPending", restartDispatchPending && newState != "CLEAN")
            .put("restartBootId", if (newState == "CLEAN") "" else restartBootId)
            .put("restartRound", if (newState == "CLEAN") "" else restartDispatchRound)
            .apply { completion?.let { put("restartCompleted", it.json()) } }
            .apply { if (variant.isDashboard) put("factoryApplyPending", factoryApplyPending).put("factoryApplyCompleted", factoryApplyCompleted) }
            .apply { if (variant.isFullApp) put("fullAppAdas", JSONObject().put("debt", fullAppAdasDebt).put("round", fullAppAdasRound)) }
            .apply { if (variant.isDashboard) put("dashboard", JSONObject().put("stage", dashboardStage)
                .put("nativeDebt", dashboardNativeDebt).put("helperCommand", dashboardHelperCommand)
                .put("helperPid", dashboardHelperPid).put("helperStart", dashboardHelperStart).put("helperToken", dashboardHelperToken)
                .put("bootstrapStarted", dashboardBootstrapStarted).put("sizeAdmitted", dashboardSizeAdmitted ?: JSONObject.NULL)
                .put("bootstrapRecoverySettled", dashboardBootstrapRecoverySettled).put("smallPanelSeen", dashboardSmallPanelSeen)) }
        val temporary = File(directory, "journal-${UUID.randomUUID()}.tmp")
        val fd = Os.open(temporary.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
        try {
            FileOutputStream(fd).use { it.write(j.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Os.rename(temporary.path, journal.path)
            val dirFd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(dirFd).st_mode)); Os.fsync(dirFd) } finally { Os.close(dirFd) }
            if (newState == "CLEAN") {
                restartCompleted = completion; restartDispatchPending = false; restartBootId = ""; restartDispatchRound = ""
                restartLease.recovered()
            }
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
