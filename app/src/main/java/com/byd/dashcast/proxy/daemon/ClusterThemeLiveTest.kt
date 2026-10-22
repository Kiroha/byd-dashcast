package com.byd.dashcast.proxy.daemon

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeProtocol
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cluster-live-deadline").apply { isDaemon = true } }
    private val generation = AtomicLong(0)
    private val restoring = AtomicLong(-1)
    private val observationPending = AtomicInteger(0)
    private val restoreRequested = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
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

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            if (journal.exists()) {
                // Never execute anything from a recovered journal. Only the fixed owned name is accepted.
                runCatching {
                    check(journal.length() <= 32768)
                    val journalFd = Os.open(journal.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
                    val j = FileInputStream(journalFd).use { input ->
                        val st = Os.fstat(input.fd)
                        check(OsConstants.S_ISREG(st.st_mode) && st.st_uid == Process.myUid() && st.st_size in 1..32768)
                        JSONObject(input.readBytes().toString(Charsets.UTF_8))
                    }
                    variant = ClusterThemeVariant.recover(j.get("schema"),
                        if (j.has("variant")) j.getString("variant") else null,
                        if (j.has("carrierSha256")) j.getString("carrierSha256") else null)
                    val recordedMutation = j.getBoolean("mutated")
                    if (j.getString("state") != "CLEAN") {
                        filename = j.getString("filename"); check(ClusterThemePolicy.ownedName(filename))
                        renderer = j.optString("renderer"); identity = j.optString("identity")
                        mutated = recordedMutation; uncertain = mutated
                        helperPid = j.getInt("helperPid"); helperStart = j.getString("helperStart"); helperToken = j.getString("helperToken")
                        createdDevice = j.optLong("createdDevice", -1); createdInode = j.optLong("createdInode", -1)
                        state = "QUARANTINE"; detail = "Previous daemon stopped. No replay. Recovery requires manual Restore; remote completion remains unknown."
                        active.set(true)
                    }
                }.onFailure {
                    journalTrusted = false; active.set(true); uncertain = true; state = "QUARANTINE"
                    detail = "Unreadable/incompatible previous journal; no new test or automatic action: ${it.message}".take(2000)
                }
            }
            initialized = true
            watchdog.scheduleWithFixedDelay({
                val name = filename
                if (active.get() && deadline > 0 && SystemClock.elapsedRealtime() >= deadline) requestRestore(name)
            }, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun status(): String {
        initialize()
        return JSONObject().put("state", state).put("detail", detail).put("filename", filename)
            .put("variant", if (journalTrusted) variant.id else "UNKNOWN")
            .put("carrierSha256", if (journalTrusted) variant.carrierSha256 else "").put("phase", phase)
            .put("daemonUid", Process.myUid()).put("renderer", renderer).put("identity", identity)
            .put("remainingMs", (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            .put("mutated", mutated).put("uncertain", uncertain).put("defaultQueried", defaultQueried)
            .put("carrierRemoved", carrierRemoved).put("markerObservedByUser", markerObserved).put("oemObservedByUser", oemObserved)
            .put("themeObservedByUser", themeObserved)
            .put("raw", raw.takeLast(16000)).toString()
    }

    @Synchronized fun start(source: ParcelFileDescriptor, parkedAssertion: Boolean,
        requestedVariant: ClusterThemeVariant = ClusterThemeVariant.MARKER): String {
        initialize()
        try {
            val st = Os.fstat(source.fileDescriptor)
            check(ClusterThemePolicy.permitsCarrierSource(st.st_mode, st.st_size)) { "Source must be a bounded regular file (pipes/devices refused)" }
        } catch (t: Throwable) { source.close(); throw t }
        if (!parkedAssertion || !active.compareAndSet(false, true)) {
            source.close(); return status()
        }
        val thisGeneration = generation.incrementAndGet()
        restoreRequested.set(false); observationPending.set(0)
        mutated = false; uncertain = false; loadSettled = false; defaultQueried = false
        markerObserved = false; themeObserved = false; oemObserved = false; carrierRemoved = false; raw = ""
        variant = requestedVariant; phase = "renderer-discovery"
        renderer = ""; identity = ""; root = "" // A completed experiment cannot pin the next renderer generation.
        createdDevice = -1; createdInode = -1
        filename = ClusterThemePolicy.PREFIX + UUID.randomUUID().toString().replace("-", "") + ".rcc"
        state = "PREFLIGHT"; detail = "User asserts parked. Checking actual renderer namespace and original artifacts."
        deadline = SystemClock.elapsedRealtime() + 90000
        worker.execute {
            if (generation.get() != thisGeneration) { source.close(); return@execute }
            source.use { descriptor ->
                try {
                    prepareJournalDirectory()
                    identifyRenderer()
                    checkDeadline()
                    phase = "baseline-query"
                    val baseline = exchange("query")
                    check(!baseline.unknown && baseline.native == 0 && baseline.query?.isDefault() == true) {
                        "Baseline query missing/custom/incompatible; default/source=1 and exact UI version required; native=${baseline.native}; ${baseline.error.orEmpty()}"
                    }
                    checkDeadline()
                    check(!restoreRequested.get()) { "Restore requested before staging; load cancelled" }
                    phase = "carrier-staging"
                    persist("STAGING") // fsync BEFORE first created resource or OEM mutation.
                    val destination = ownedFile()
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
                    phase = "carrier-verification"
                    check(hash(destination, ClusterThemePolicy.MAX_CARRIER) == variant.carrierSha256)
                    checkRendererIdentity()
                    checkDeadline(); check(!restoreRequested.get()) { "Restore requested; load cancelled" }
                    phase = "load"
                    mutated = true; persist("LOAD_STARTED") // unknown until child returns; never replay.
                    val load = exchange("load")
                    uncertain = load.unknown || load.native != 0
                    loadSettled = load.responses.any { ClusterThemePolicy.loadAckMatches(it, filename) }
                    state = if (uncertain) "QUARANTINE" else "AWAITING_OBSERVATION"
                    detail = if (uncertain) "${variant.id} load completion UNKNOWN; native=${load.native}; ${load.error.orEmpty()}. No replay; recovery remains separate."
                        else if (load.query?.selected(filename) == true) "Selection query names ${variant.id}; pixels require your observation."
                        else "Load attempted for ${variant.id}; selection unconfirmed; native=${load.native}; ${load.error.orEmpty()}. Missing test graphics are inconclusive."
                    phase = "observation"
                    persist(state)
                    deadline = SystemClock.elapsedRealtime() + 30000
                    if (restoreRequested.get() || uncertain) enqueueRestore(thisGeneration)
                } catch (t: Throwable) {
                    detail = "$phase: ${t.javaClass.simpleName}: ${t.message}".take(2000)
                    if (mutated) { uncertain = true; state = "QUARANTINE"; persistQuietly(); enqueueRestore(thisGeneration) }
                    else if (helperUnreaped) {
                        uncertain = true; state = "QUARANTINE"; deadline = 0
                        detail += "; previous owned helper not reaped; no new test"
                        persistQuietly()
                    } else {
                        // No OEM mutation began. Delete only an exclusively created, recorded own file.
                        runCatching { cleanupOwned(allowPartial = true) }
                        state = "REFUSED"; deadline = 0
                        runCatching { persist("CLEAN") }
                        active.set(false)
                    }
                }
            }
        }
        return status()
    }

    @Synchronized fun requestRestore(expectedFilename: String? = null): String {
        initialize()
        if (expectedFilename != null && expectedFilename != filename) return status()
        restoreRequested.set(true)
        if (active.get()) enqueueRestore()
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
                    cleanupOwned(true)
                    state = "CLEAN"; detail = "Cancelled before native load; only a positively owned partial carrier was removed."
                    persist(state); active.set(false)
                    return@execute
                }
                state = "RESTORING"; detail = "One default-selection request; no physical recovery claim."
                phase = "restore-query"
                persistQuietly()
                if (root.isEmpty()) identifyRenderer() else checkRendererIdentity()
                val selection = exchange("query")
                check(!selection.unknown && selection.native == 0 && selection.query != null) { "Recovery pre-query unavailable; no overwrite of unknown selection; ${selection.error.orEmpty()}" }
                check(selection.query.isDefault() || selection.query.selected(filename)) { "Another selection is active; no overwrite" }
                val restored = if (selection.query.isDefault()) selection else exchange("restore")
                uncertain = uncertain || restored.unknown || restored.native != 0
                defaultQueried = restored.query?.isDefault() == true
                state = if (uncertain || !defaultQueried || !loadSettled) "QUARANTINE" else "DEFAULT_QUERIED"
                detail = if (defaultQueried && loadSettled && !uncertain) "OEM query reports default selection. Physical OEM recovery still requires your confirmation."
                    else if (defaultQueried) "Default queried while load is unresolved; late load remains possible. No restoration established. Carrier retained; no new test."
                    else "Default recovery is unconfirmed. No new test; keep parked and inspect the cluster."
                persistQuietly()
                maybeCleanup()
            } catch (t: Throwable) {
                uncertain = true; state = "QUARANTINE"; detail = "Restore unknown: ${t.message}".take(2000); persistQuietly()
            } finally { restoring.compareAndSet(expectedGeneration, -1) }
        }
    }

    fun observe(which: Int, expectedFilename: String, expectedVariant: ClusterThemeVariant = ClusterThemeVariant.MARKER): String {
        initialize()
        if (which == 1 && variant != expectedVariant) return status()
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
                if (filename == observedFilename && active.get()) {
                    if (which == 1 && variant == expectedVariant && !restoreRequested.get()) {
                        if (variant == ClusterThemeVariant.MARKER) markerObserved = true else themeObserved = true
                        loadSettled = true
                    }
                    if (which == 2 && defaultQueried) oemObserved = true
                    persistQuietly(); maybeCleanup()
                }
                if (generation.get() == observedGeneration) observationPending.getAndUpdate { it and bit.inv() }
            }
        }
        return status()
    }

    private fun maybeCleanup() {
        if (!carrierRemoved && ClusterThemePolicy.canDeleteCarrier(defaultQueried, loadSettled, uncertain)) {
            check(cleanupOwned(false)) { "Owned carrier cleanup identity unavailable; retained" }
            carrierRemoved = true
            detail = "Default selection queried; only our verified carrier removed. Confirm OEM pixels; a new test remains blocked."
            persist(state)
        }
        if (carrierRemoved && oemObserved && !uncertain) {
            state = "CLEAN"; detail = "Default queried; tester confirmed OEM display. Only our carrier deleted. No reboot guarantee."
            persist(state); deadline = 0; active.set(false)
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
        val j = JSONObject().put("schema", 2).put("state", newState).put("filename", filename)
            .put("variant", variant.id).put("carrierSha256", variant.carrierSha256)
            .put("renderer", renderer).put("identity", identity).put("mutated", mutated)
            .put("uncertain", uncertain).put("defaultQueried", defaultQueried)
            .put("createdDevice", createdDevice).put("createdInode", createdInode)
            .put("helperPid", helperPid).put("helperStart", helperStart).put("helperToken", helperToken)
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
    private fun identifyRenderer() {
        hashDeadline = SystemClock.elapsedRealtime() + 60000
        phase = "renderer-discovery"
        val candidates = File("/proc").listFiles()?.filter { it.name.matches(Regex("[1-9][0-9]{0,8}")) }.orEmpty()
            .filter { runCatching { File(it, "cmdline").readText().split('\u0000').first().substringAfterLast('/') == "qtandroidnative" }.getOrDefault(false) }
        check(candidates.size == 1) { "Expected one readable qtandroidnative renderer; found ${candidates.size}. No UID/namespace fallback." }
        val pid = candidates.single().name
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
        check(ClusterThemePolicy.ownedName(filename) && root == "/proc/$renderer/root")
        checkRendererIdentity()
        return File(root + ClusterThemePolicy.HOST_DIRECTORY + filename)
    }
    private fun cleanupOwned(allowPartial: Boolean): Boolean {
        if (root.isEmpty() || !ClusterThemePolicy.ownedName(filename) || createdInode < 0 || createdDevice < 0) return false
        val f = ownedFile()
        if (!f.exists()) return true
        val st = Os.lstat(f.path)
        check(ClusterThemePolicy.ownsObject(st.st_mode, st.st_uid, st.st_dev, st.st_ino, Process.myUid(), createdDevice, createdInode)) { "Owned carrier identity/type changed; retained" }
        check(allowPartial || hash(f, ClusterThemePolicy.MAX_CARRIER) == variant.carrierSha256) { "Owned carrier hash changed; retained" }
        check(f.delete()) { "Owned carrier cleanup failed" }
        return true
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

    private data class Exchange(val native: Int?, val query: ClusterThemeProtocol.Response?, val responses: List<ClusterThemeProtocol.Response>,
        val unknown: Boolean, val error: String? = null)
    private fun reapRecoveredHelper() {
        if (helperPid <= 0) { check(helperToken.isEmpty() || journalTrusted); return }
        val proc = File("/proc/$helperPid")
        if (proc.exists()) {
            val start = procStart(helperPid)
            if (start == helperStart) {
                val args = File(proc, "cmdline").readText().split('\u0000')
                check(Os.stat(proc.path).st_uid == Process.myUid() &&
                    args.contains("com.byd.dashcast.proxy.daemon.ClusterThemeHelper") && args.contains(helperToken) && args.contains(filename)) { "Recovered helper identity mismatch; no kill or native call" }
                Process.killProcess(helperPid)
                val until = SystemClock.elapsedRealtime() + 1500
                while (proc.exists() && runCatching { procStart(helperPid) == helperStart }.getOrDefault(false) && SystemClock.elapsedRealtime() < until) Thread.sleep(25)
                check(!proc.exists() || runCatching { procStart(helperPid) != helperStart }.getOrDefault(true)) { "Recovered helper remains alive" }
            }
        }
        helperPid = 0; helperStart = ""; helperToken = ""; helperUnreaped = false
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
        val args = mutableListOf("/system/bin/app_process64", "-Xnoimage-dex2oat", "/system/bin", "com.byd.dashcast.proxy.daemon.ClusterThemeHelper", operation, filename, helperToken)
        val child = ProcessBuilder(args).redirectErrorStream(true).apply { environment()["CLASSPATH"] = apk }.start()
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
        try {
            val readyUntil = SystemClock.elapsedRealtime() + 3000
            while (!record.exists() && child.isAlive && SystemClock.elapsedRealtime() < readyUntil) Thread.sleep(25)
            check(record.isFile && record.length() in 1..1024) { "Helper failed to identify before admission" }
            val ready = JSONObject(record.readText())
            check(ready.getString("token") == helperToken && ready.getString("filename") == filename)
            helperPid = ready.getInt("pid"); helperStart = ready.getString("start")
            check(helperPid > 0 && procStart(helperPid) == helperStart && Os.stat("/proc/$helperPid").st_uid == Process.myUid())
            persist(state) // PID/start identity fsynced BEFORE permitting any OEM call.
            child.outputStream.write("GO\n".toByteArray()); child.outputStream.flush()
            val until = SystemClock.elapsedRealtime() + 8000
            while (SystemClock.elapsedRealtime() < until && !child.waitFor(100, TimeUnit.MILLISECONDS)) { /* owned bounded child */ }
            if (child.isAlive) {
                child.destroyForcibly(); terminated = child.waitFor(1000, TimeUnit.MILLISECONDS)
                uncertain = true
                raw = (raw + "\n$operation: helper deadline; remote completion UNKNOWN; reaped=$terminated").takeLast(16000)
                return Exchange(null, null, emptyList(), true)
            }
            terminated = true; reader.join(500)
            check(!reader.isAlive && !overflow.get()) { "Helper result incomplete/oversized" }
            val text = synchronized(capture) { capture.toString("UTF-8") }
            raw = (raw + "\n$operation: " + text.takeLast(12000)).takeLast(16000)
            val line = text.lineSequence().lastOrNull { it.startsWith("DASHCAST_RESULT=") } ?: error("Missing helper result")
            val j = JSONObject(line.removePrefix("DASHCAST_RESULT="))
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
                if (j.has("error")) j.getString("error").take(1024) else null)
        } finally {
            if (!terminated) {
                child.destroyForcibly()
                if (!child.waitFor(1000, TimeUnit.MILLISECONDS)) {
                    helperUnreaped = true
                    error("Owned helper not reaped; no further native command permitted")
                }
                terminated = true
            }
            runCatching { child.inputStream.close() }; runCatching { child.outputStream.close() }; reader.join(200)
            if (terminated && !helperUnreaped) {
                record.delete(); helperPid = 0; helperStart = ""; helperToken = ""; persistQuietly()
            }
        }
    }
}
