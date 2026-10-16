package com.byd.dashcast.ui.diag

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** One serial worker shares this terminal latch across output, SQLite and daemon cleanup.
 * Metadata checks are bounded and live, not an atomic mount lock. Once a check fails, even
 * a later apparently internal mount cannot authorize another write in this session. */
internal class FirmwareWriteGuard(private val attest: () -> Unit) {
    private var refusal: FirmwareOutputRefused? = null
    private var lastValidation = 0L
    private var transactions = 0
    val refused: Boolean get() = refusal != null

    fun check() { refusal?.let { throw it } }
    fun validate() = attest { attest() }
    fun attest(block: () -> Unit) {
        check()
        check(transactions == 0) { "Output attestation must occur outside journal transactions" }
        try { block(); check(); lastValidation = FullApkExport.monotonicMs() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = refusal ?: FirmwareOutputRefused("Écriture arrêtée : attestation du stockage interne perdue ; archives déjà finalisées conservées : ${e.message}", e)
            refusal = failure
            throw failure
        }
    }
    fun periodic() {
        check()
        // No attestation inside a SQLite transaction: finish its durable commit/rollback
        // before discovering a changed mount. Per-row/per-chunk checks are only latch reads.
        if (transactions == 0 && FullApkExport.monotonicMs() - lastValidation >= 1000) validate()
    }
    fun enterTransaction() { check(); transactions++ }
    fun leaveTransaction() { check(transactions > 0); transactions-- }
    fun stream(output: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) { check(); output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { check(); output.write(bytes, offset, length) }
        override fun flush() { check(); output.flush() }
        override fun close() { check(); output.close() }
    }
}

internal class FirmwareOutputRefused(message: String, cause: Throwable) : IOException(message, cause)
