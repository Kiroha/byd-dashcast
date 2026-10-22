package com.byd.dashcast.ui.diag

import android.content.Context
import android.content.pm.PackageManager
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

/** Offline checks for our single bundled RCC v1 fixture, never an OEM loader or Qt engine. */
internal object ExperimentalThemeDiagnostics {
    private const val LIMIT = 64 * 1024
    private const val VERSION = "0.1.0"
    private val files = listOf("Main.qml", "theme.qrc", "theme.rcc")

    data class Result(val passed: Boolean, val detail: String) {
        fun report(): String = """
            Experimental theme v$VERSION — local verification only
            Bundled files: ${if (passed) "PASS" else "FAIL"}
            $detail
            Qt rendering: NOT TESTED (no Qt engine in this app).
            OEM loading/access: NOT ESTABLISHED; no OEM access attempted.
            OEM recovery: NOT ESTABLISHED.
            Target firmware from REA: 13.1.33.2602030.1; installed cluster firmware: UNKNOWN.
            No theme was loaded or installed. This is not a compatibility test.
        """.trimIndent()
    }

    fun verify(open: (String) -> InputStream): Result = try {
        val manifest = Properties().apply {
            open("manifest.properties").use { load(readBounded(it).inputStream()) }
        }
        require(manifest.getProperty("schema") == "1") { "Unsupported manifest schema" }
        require(manifest.getProperty("version") == VERSION) { "Unexpected theme version" }
        require(manifest.getProperty("entry") == ":/dashcast_experimental/Main.qml") { "Unexpected entry point" }
        val contents = files.associateWith { filename ->
            open(filename).use { readBounded(it) }.also { bytes ->
                require(manifest.getProperty("$filename.size") == bytes.size.toString()) { "$filename size mismatch" }
                require(manifest.getProperty("$filename.sha256") == sha256(bytes)) { "$filename SHA-256 mismatch" }
            }
        }
        checkRcc(contents.getValue("theme.rcc"), contents.getValue("Main.qml"))
        Result(true, "SHA-256 and sizes match packaged metadata. RCC v1 tree, entry and uncompressed QML payload checked. Hashes are integrity checks, not OEM signatures.")
    } catch (e: Exception) {
        Result(false, "Local check failed: ${e.message ?: e.javaClass.simpleName}")
    }

    fun passiveAccessContext(context: Context): String = accessContext(
        requestedPermissions = {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        },
        signatureMatch = { context.packageManager.checkSignatures("android", context.packageName) },
        effectivePermission = context::checkSelfPermission
    )

    internal fun accessContext(
        requestedPermissions: () -> Array<String>?,
        signatureMatch: () -> Int,
        effectivePermission: (String) -> Int
    ): String {
        val signature = try {
            when (val code = signatureMatch()) {
                PackageManager.SIGNATURE_MATCH -> "MATCH"
                PackageManager.SIGNATURE_UNKNOWN_PACKAGE -> "UNKNOWN"
                else -> "NOT MATCHED (code $code)"
            }
        } catch (_: Exception) { "UNKNOWN" }
        val permissions = try {
            val requested = requestedPermissions().orEmpty().toSet()
            listOf("android.permission.ACCESS_SURFACE_FLINGER", "android.permission.INTERNAL_SYSTEM_WINDOW",
                "android.permission.BYDAUTO_INSTRUMENT_COMMON", "android.permission.BYDAUTO_INSTRUMENT_GET")
                .joinToString("\n") { permission ->
                    val status = if (permission !in requested) "NOT REQUESTED" else try {
                        if (effectivePermission(permission) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
                    } catch (_: Exception) { "UNKNOWN" }
                    "$permission: $status"
                }
        } catch (_: Exception) { "Requested/effective permissions: UNKNOWN" }
        return "App UID only — passive Android access context:\nSignature vs android: $signature\n$permissions" +
            "\nThese display/instrument permissions and signatures do not establish OEM RCC loading authorization."
    }

    private fun readBounded(input: InputStream): ByteArray {
        val bytes = input.readBytesBounded()
        require(bytes.size <= LIMIT) { "Asset exceeds $LIMIT bytes" }
        return bytes
    }

    private fun InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() <= LIMIT) {
            val count = read(buffer, 0, minOf(buffer.size, LIMIT + 1 - output.size()))
            if (count < 0) break
            require(count > 0) { "Asset stream made no progress" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    // shortcut: accepts only our one-file RCC v1 layout; extend only when the fixture changes.
    private fun checkRcc(bytes: ByteArray, qml: ByteArray) {
        fun u16(at: Int): Int {
            require(at >= 0 && at <= bytes.size - 2) { "Truncated RCC field" }
            return ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
        }
        fun u32(at: Int): Int {
            require(at >= 0 && at <= bytes.size - 4) { "Truncated RCC field" }
            val value = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
            require(value <= Int.MAX_VALUE) { "RCC field out of bounds" }
            return value.toInt()
        }
        require(bytes.size >= 20 && bytes.copyOfRange(0, 4).contentEquals("qres".toByteArray())) { "Invalid RCC magic" }
        require(u32(4) == 1) { "Unsupported RCC format" }
        val tree = u32(8)
        val data = u32(12)
        val names = u32(16)
        require(data == 20 && names == data + 4 + qml.size && tree >= names &&
            tree <= bytes.size && bytes.size - tree == 42) { "Unexpected RCC sections" }
        require(u32(data) == qml.size && bytes.copyOfRange(data + 4, names).contentEquals(qml)) { "RCC QML payload mismatch" }
        fun name(offset: Int, expected: String, qtHash: Int): Int {
            require(offset >= names && offset <= tree - 6) { "Invalid RCC name offset" }
            require(u32(offset + 2) == qtHash) { "Invalid RCC resource name hash" }
            val count = u16(offset)
            val end = offset + 6 + count * 2
            require(end <= tree && count == expected.length) { "Invalid RCC name length" }
            require(String(bytes, offset + 6, count * 2, Charsets.UTF_16BE) == expected) { "Unexpected RCC resource name" }
            return end
        }
        val leafName = name(names, "dashcast_experimental", 0x0403fdbc)
        require(name(leafName, "Main.qml", 0x08015e5c) == tree) { "Unexpected RCC names" }
        for (node in 0..1) {
            val at = tree + node * 14
            require(u32(at) == 0 && u16(at + 4) == 2 && u32(at + 6) == 1 && u32(at + 10) == node + 1) { "Invalid RCC directory" }
        }
        val leaf = tree + 28
        require(u32(leaf) == leafName - names && u16(leaf + 4) == 0 &&
            u16(leaf + 6) == 0 && u16(leaf + 8) == 1 && u32(leaf + 10) == 0) { "Invalid RCC leaf or compression" }
    }
}
