package com.byd.dashcast.proxy.daemon

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/** Separate operational debt. Nothing here reads, clears or substitutes the RCC journal. */
internal class ClusterNativeModeTest(private val worker: Executor,
    private val directory: File = File("/data/local/tmp/dashcast-native-mode"),
    private val journalAccess: Journal? = null,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val pause: (Long) -> Unit = { Thread.sleep(it) }) {
    interface Journal { fun load(): JSONObject?; fun save(receipt: JSONObject) }
    private val journal = File(directory, "native-journal.json")
    @Volatile private var initialized = false
    @Volatile private var trusted = true
    @Volatile private var nonce = ""
    @Volatile private var debt = false
    @Volatile private var pending = 0
    @Volatile private var returnQueued = false
    @Volatile private var cancelFull = false
    @Volatile private var normalSettled = false
    @Volatile private var uncertain = false
    @Volatile private var state = "IDLE"
    @Volatile private var detail = "No native Full-mode test recorded; physical pixels UNKNOWN."
    @Volatile private var native: Int? = null
    @Volatile private var command = 0
    @Volatile private var quietUntil = 0L
    @Volatile private var helperPid = 0
    @Volatile private var helperStart = ""
    @Volatile private var helperToken = ""
    @Volatile private var helperUnreaped = false
    @Volatile private var observedByUser = false
    @Volatile private var receivedEpochMs = 0L
    @Volatile private var admittedElapsedMs = 0L
    @Volatile private var queuedElapsedMs = 0L
    @Volatile private var transactionAttempted = "UNKNOWN"
    private val trace = ArrayDeque<String>()
    private var fullReceipt = JSONObject()
    private var normalReceipt = JSONObject()
    @Synchronized private fun step(stage: String, extra: String = "") {
        val line = "epochMs=${System.currentTimeMillis()} elapsedMs=${elapsed()} nonce=$nonce command=$command stage=$stage ${extra.take(384)}"
        trace.addLast(line); while (trace.size > 32 || trace.sumOf { it.length } > 6144) trace.removeFirst()
        ProxyDaemonMain.log("NativeMode $line")
    }

    @Synchronized private fun initialize() {
        if (initialized) return
        initialized = true
        try {
            val j = if (journalAccess != null) journalAccess.load() ?: return else if (journal.exists()) readJournal() else return
            require(j.getInt("schema") == 1)
            nonce = j.getString("nonce"); require(nonce.matches(Regex("[0-9a-f]{32}")))
            debt = j.getBoolean("debt")
            helperPid = j.getInt("helperPid"); helperStart = j.getString("helperStart"); helperToken = j.getString("helperToken")
            require(helperPid == 0 && helperStart.isEmpty() && helperToken.isEmpty() ||
                helperPid > 0 && helperStart.matches(Regex("[0-9]+")) && helperToken.matches(Regex("[0-9a-f]{32}")))
            uncertain = debt; helperUnreaped = debt && helperPid > 0
            state = if (debt) "QUARANTINE" else "CLOSED"
            detail = "Recovered native-mode receipt; no command replayed. Physical mode UNKNOWN; manual return required for outstanding debt."
        } catch (_: Exception) {
            trusted = false; debt = true; uncertain = true; state = "QUARANTINE"
            detail = "Unreadable native-mode journal; no new mode request. Do not infer restored state."
        }
    }

    @Synchronized fun blocked(): Boolean { initialize(); return debt || pending > 0 || !trusted }
    @Synchronized fun status(): JSONObject {
        initialize()
        return JSONObject().put("state", state).put("nonce", nonce).put("detail", detail).put("debt", debt)
            .put("pending", pending > 0).put("command", command).put("native", native)
            .put("uncertain", uncertain).put("normalSettled", normalSettled).put("observedByUser", observedByUser)
            .put("physicalPixels", "UNKNOWN").put("qmlAck", "UNKNOWN").put("debounce", "UNKNOWN")
            .put("queuedElapsedMs", queuedElapsedMs).put("admittedElapsedMs", admittedElapsedMs).put("transactionAttempted", transactionAttempted)
            .put("trace", trace.joinToString("\n"))
            .put("fullReceipt", fullReceipt).put("normalReceipt", normalReceipt)
            .put("receivedEpochMs", receivedEpochMs).put("quietRemainingMs", (quietUntil - elapsed()).coerceAtLeast(0))
    }

    @Synchronized fun request(full: Boolean, expectedNonce: String, parked: Boolean, firmware: Boolean,
        validity: IBinder, run: (Int, () -> Unit, (Int, String, String) -> Unit) -> ClusterThemeQueryRunner.Result = ClusterThemeQueryRunner::nativeMode): JSONObject {
        initialize()
        check(trusted && parked && firmware) { "Trusted journal and explicit parked/firmware assertions required; movement UNKNOWN" }
        check(valid(validity)) { "Idle projection/ADAS reservation expired" }
        if (full) {
            require(expectedNonce.matches(Regex("[0-9a-f]{32}")))
            check(!debt && pending == 0 && elapsed() >= quietUntil) { "Native test/recovery or quiet interval active" }
            nonce = expectedNonce
            debt = true; normalSettled = false; observedByUser = false; uncertain = false; cancelFull = false
        } else {
            if (!debt) {
                require(expectedNonce.matches(Regex("[0-9a-f]{32}")))
                nonce = expectedNonce; debt = true; uncertain = true; normalSettled = false; observedByUser = false
            }
            check(debt && expectedNonce == nonce) { "Read current native test nonce before requesting return" }
            check(!returnQueued) { "Return already requested; no replay" }
            cancelFull = true; returnQueued = true; normalSettled = false
        }
        quietUntil = maxOf(quietUntil, elapsed() + 1500)
        queuedElapsedMs = elapsed()
        pending++; state = if (full) "FULL_QUEUED" else "RETURN_QUEUED"; detail = "One manual native request queued; movement and physical pixels UNKNOWN."
        step("enqueue", "requestedCommand=${if (full) 16 else 18} parkedAssertion=$parked firmwareAssertion=$firmware quietUntil=$quietUntil")
        try { persist() } catch (error: Exception) { pending--; if (!full) returnQueued = false; uncertain = true; state = "QUARANTINE"; throw error }
        val expected = nonce
        val requestQueuedAt = elapsed()
        worker.execute {
            val code = if (full) 16 else 18
            var reaped = !helperUnreaped
            var admitted = false
            var admittedAt = 0L
            try {
                if (!full) reapRecoveredHelper()
                reaped = !helperUnreaped
                step("quiet-begin", "until=$quietUntil projectionTokenValid=${valid(validity)}")
                while (elapsed() < quietUntil) {
                    check(valid(validity)) { "Reservation expired before quiet interval ended" }; pause(25)
                }
                step("quiet-end")
                synchronized(this) {
                    check(nonce == expected && valid(validity) && (!full || !cancelFull)) { "Queued request cancelled before admission" }
                    command = code; native = null; state = "PREPARING"; transactionAttempted = "UNKNOWN"
                }
                val result = run(code, {
                    synchronized(this) {
                        check(nonce == expected && valid(validity) && (!full || !cancelFull)) { "Request cancelled before GO; no replay" }
                        admitted = true; state = "ADMITTED"; uncertain = true; admittedElapsedMs = elapsed(); admittedAt = admittedElapsedMs
                        detail = "sendInfo(1000,$code) admitted once; native debounce and QML/render completion UNKNOWN."
                        persist() // Durable debt and owned helper identity BEFORE GO.
                        step("durable-before-GO", "helperPid=$helperPid helperStart=$helperStart token=$helperToken projectionTokenValid=true")
                    }
                }, { pid, start, token -> synchronized(this) {
                    helperPid = pid; helperStart = start; helperToken = token; helperUnreaped = true; persist()
                    step("owned-helper-ready", "pid=$pid start=$start token=$token")
                } })
                reaped = result.reaped
                synchronized(this) {
                    native = if (result.receipt.has("native")) result.receipt.getInt("native") else null
                    transactionAttempted = if (result.receipt.has("attempted")) (if (result.receipt.getBoolean("attempted")) "YES" else "NO") else "UNKNOWN"
                    state = if (result.receipt.optString("state") == "RECEIVED") "RETURNED" else "UNKNOWN"
                    uncertain = uncertain || state == "UNKNOWN"
                    detail = result.receipt.optString("detail", "No supported native receipt; no replay").take(768)
                    if (!full) normalSettled = admitted && reaped
                    result.receipt.optJSONArray("trace")?.let { lines ->
                        for (i in 0 until minOf(lines.length(), 20)) step("helper", lines.optString(i).take(384))
                    }
                    step("helper-result", "state=$state attempted=$transactionAttempted native=$native reaped=$reaped detail=$detail")
                }
            } catch (error: Throwable) {
                synchronized(this) { state = if (admitted) "UNKNOWN" else "REFUSED"; uncertain = uncertain || admitted
                    detail = "${error.javaClass.simpleName}: ${error.message}".take(768) }
                step("request-failure", "admitted=$admitted reaped=$reaped detail=$detail")
            } finally {
                synchronized(this) {
                    quietUntil = elapsed() + 1500
                    val receipt = JSONObject().put("command", code).put("nonce", expected).put("state", state).put("detail", detail.take(768))
                        .put("queuedElapsedMs", requestQueuedAt).put("admittedElapsedMs", admittedAt).put("completedElapsedMs", elapsed())
                        .put("admitted", admitted).put("attempted", transactionAttempted).put("native", native).put("reaped", reaped)
                        .put("helperPid", helperPid).put("helperStart", helperStart).put("helperToken", helperToken)
                    if (full) fullReceipt = receipt else normalReceipt = receipt
                    helperUnreaped = !reaped
                    if (reaped) { helperPid = 0; helperStart = ""; helperToken = "" }
                    pending--; if (!full) returnQueued = false
                    receivedEpochMs = System.currentTimeMillis()
                    step("worker-return", "pending=$pending helperUnreaped=$helperUnreaped debt=$debt quietUntil=$quietUntil")
                    runCatching { persist() }.onFailure { trusted = false; state = "QUARANTINE"; uncertain = true }
                }
            }
        }
        return status()
    }

    @Synchronized fun observedNormal(expectedNonce: String): JSONObject {
        initialize()
        if (trusted && !debt && state == "CLOSED" && expectedNonce == nonce) return status()
        check(trusted && debt && nonce == expectedNonce && pending == 0 && !helperUnreaped && normalSettled) {
            "Normal request must have completed/reaped before a fresh, nonce-bound user observation"
        }
        observedByUser = true; debt = false; state = "CLOSED"
        detail = "User reports normal BYD display after manual return. Operational debt closed; historical IPC uncertainty retained; pixels/QML ACK UNKNOWN."
        receivedEpochMs = System.currentTimeMillis()
        step("user-normal-observation", "pending=$pending normalSettled=$normalSettled operationalDebtClosing=true pixels=UNKNOWN")
        try { persist() } catch (error: Exception) {
            trusted = false; debt = true; state = "QUARANTINE"; uncertain = true; throw error
        }
        return status()
    }

    private fun valid(binder: IBinder): Boolean {
        if (!binder.isBinderAlive) return false
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken("com.byd.dashcast.NativeModeReservation")
            binder.transact(1, d, r, 0) && run { r.readException(); r.dataAvail() == 4 && r.readInt() == 1 }
        } catch (_: Exception) { false } finally { r.recycle(); d.recycle() }
    }

    private fun reapRecoveredHelper() {
        if (!helperUnreaped) return
        check(helperPid > 0) { "Unreaped helper identity unavailable; no further mode request" }
        val path = File("/proc/$helperPid")
        if (path.exists() && ClusterNativeModeHelper.procStart(helperPid) == helperStart) {
            check(Os.stat(path.path).st_uid == Process.myUid()) { "Recorded helper owner differs" }
            val bytes = File(path, "cmdline").inputStream().use { input ->
                val buffer = ByteArray(4097); var count = 0
                while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                check(count in 1..4096); buffer.copyOf(count)
            }
            val argv = bytes.toString(Charsets.UTF_8).split('\u0000')
            check(argv.contains("com.byd.dashcast.proxy.daemon.ClusterNativeModeHelper") && argv.contains(helperToken) &&
                ClusterNativeModeHelper.procStart(helperPid) == helperStart && Os.stat(path.path).st_uid == Process.myUid()) {
                "Recorded helper argv/token/identity differs; refusing kill"
            }
            // Only our recorded PID generation; this cannot cancel an already admitted OEM request.
            Os.kill(helperPid, OsConstants.SIGKILL)
            val until = SystemClock.elapsedRealtime() + 1000
            while (path.exists() && runCatching { ClusterNativeModeHelper.procStart(helperPid) == helperStart }.getOrDefault(false) && SystemClock.elapsedRealtime() < until) Thread.sleep(25)
            check(!path.exists() || ClusterNativeModeHelper.procStart(helperPid) != helperStart) { "Recorded helper not reaped" }
        }
        helperUnreaped = false; helperPid = 0; helperStart = ""; helperToken = ""
    }

    private fun ensureDirectory() {
        if (!directory.exists()) check(directory.mkdir())
        val st = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(st.st_mode) && st.st_uid == Process.myUid())
        Os.chmod(directory.path, 448)
    }
    private fun persist() {
        val receipt = JSONObject().put("schema", 1).put("nonce", nonce).put("debt", debt)
            .put("helperPid", helperPid).put("helperStart", helperStart).put("helperToken", helperToken)
        if (journalAccess != null) { journalAccess.save(receipt); return }
        ensureDirectory()
        val text = receipt.toString().toByteArray()
        val temp = File(directory, "native-journal.tmp")
        val fd = Os.open(temp.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
        try {
            val st = Os.fstat(fd); check(OsConstants.S_ISREG(st.st_mode) && st.st_uid == Process.myUid())
            var count = 0
            while (count < text.size) { val n = Os.write(fd, text, count, text.size - count); check(n > 0); count += n }
            Os.fsync(fd)
        } finally { Os.close(fd) }
        Os.rename(temp.path, journal.path)
        val parent = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(parent).st_mode)); Os.fsync(parent) } finally { Os.close(parent) }
    }
    private fun readJournal(): JSONObject {
        ensureDirectory()
        val fd = Os.open(journal.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        try {
            val st = Os.fstat(fd); check(OsConstants.S_ISREG(st.st_mode) && st.st_uid == Process.myUid() && st.st_size in 1..2048)
            val bytes = ByteArray(st.st_size.toInt()); var count = 0
            while (count < bytes.size) { val n = Os.read(fd, bytes, count, bytes.size - count); check(n > 0); count += n }
            check(Os.read(fd, ByteArray(1), 0, 1) == 0)
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { Os.close(fd) }
    }
}
