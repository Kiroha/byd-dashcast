package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Properties

class ExperimentalThemeDiagnosticsTest {
    private val assets = File("src/main/assets/experimental_theme")
    private fun fixture() = assets.listFiles()!!.associate { it.name to it.readBytes() }.toMutableMap()
    private fun verify(files: Map<String, ByteArray>) = ExperimentalThemeDiagnostics.verify {
        files[it]?.inputStream() ?: error("Missing $it")
    }
    private fun updateRccMetadata(files: MutableMap<String, ByteArray>) {
        val props = Properties().apply { load(files.getValue("manifest.properties").inputStream()) }
        val bytes = files.getValue("theme.rcc")
        props.setProperty("theme.rcc.size", bytes.size.toString())
        props.setProperty("theme.rcc.sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) })
        val out = java.io.ByteArrayOutputStream()
        props.store(out, null)
        files["manifest.properties"] = out.toByteArray()
    }

    @Test fun `actual compiled fixture passes only offline checks`() {
        val result = verify(fixture())
        assertTrue(result.detail, result.passed)
        assertTrue(result.report().contains("Qt rendering: NOT TESTED"))
        assertTrue(result.report().contains("OEM loading/access: NOT ESTABLISHED"))
        assertTrue(result.report().contains("OEM recovery: NOT ESTABLISHED"))
    }

    @Test fun `tampered missing oversized and mismatched version assets fail`() {
        val changes: List<(MutableMap<String, ByteArray>) -> Unit> = listOf(
            { it.getValue("Main.qml")[0] = 0 },
            { it.remove("theme.rcc") },
            { it["theme.rcc"] = ByteArray(65537) },
            { it["manifest.properties"] = it.getValue("manifest.properties").toString(Charsets.UTF_8)
                .replace("version=0.1.0", "version=9.9.9").toByteArray() },
            { it["manifest.properties"] = "schema=9".toByteArray() }
        )
        changes.forEach { change -> assertFalse(verify(fixture().also(change)).passed) }
    }

    @Test fun `malformed RCC fails even when packaged metadata matches`() {
        for (offset in listOf(0, 7, 8, 12, 16, 20)) {
            val files = fixture()
            files.getValue("theme.rcc")[offset] = 127
            updateRccMetadata(files)
            assertFalse("offset $offset", verify(files).passed)
        }
        for (length in listOf(0, 19, 25, 100)) {
            val files = fixture()
            files["theme.rcc"] = files.getValue("theme.rcc").copyOf(length)
            updateRccMetadata(files)
            assertFalse("length $length", verify(files).passed)
        }
    }

    @Test fun `invalid tree flags or entry fail with matching metadata`() {
        val original = fixture().getValue("theme.rcc")
        val tree = java.nio.ByteBuffer.wrap(original).getInt(8)
        for (offset in listOf(tree + 5, tree + 13, tree + 28, tree + 33, tree + 41)) {
            val files = fixture()
            files.getValue("theme.rcc")[offset] = 127
            updateRccMetadata(files)
            assertFalse("tree offset $offset", verify(files).passed)
        }
    }
    @Test fun `passive access context distinguishes grants denials and unknown without inferring OEM admission`() {
        val calls = mutableListOf<String>()
        val report = ExperimentalThemeDiagnostics.accessContext(
            requestedPermissions = { arrayOf("android.permission.ACCESS_SURFACE_FLINGER", "android.permission.INTERNAL_SYSTEM_WINDOW") },
            signatureMatch = { 0 },
            effectivePermission = { calls += it; if (it.endsWith("ACCESS_SURFACE_FLINGER")) 0 else -1 }
        )
        assertTrue(report.contains("Signature vs android: MATCH"))
        assertTrue(report.contains("ACCESS_SURFACE_FLINGER: GRANTED"))
        assertTrue(report.contains("INTERNAL_SYSTEM_WINDOW: DENIED"))
        assertTrue(report.contains("BYDAUTO_INSTRUMENT_GET: NOT REQUESTED"))
        assertTrue(report.contains("do not establish OEM RCC loading authorization"))
        assertEquals(2, calls.size)
        val unknown = ExperimentalThemeDiagnostics.accessContext(
            requestedPermissions = { error("Unavailable") }, signatureMatch = { error("Unavailable") },
            effectivePermission = { error("Must not be called") })
        assertTrue(unknown.contains("Signature vs android: UNKNOWN"))
        assertTrue(unknown.contains("Requested/effective permissions: UNKNOWN"))
        val failedGrant = ExperimentalThemeDiagnostics.accessContext(
            requestedPermissions = { arrayOf("android.permission.ACCESS_SURFACE_FLINGER") },
            signatureMatch = { -4 }, effectivePermission = { error("Unavailable") })
        assertTrue(failedGrant.contains("Signature vs android: UNKNOWN"))
        assertTrue(failedGrant.contains("ACCESS_SURFACE_FLINGER: UNKNOWN"))
    }

    @Test fun `resource name hashes participate in entry validation`() {
        val original = fixture().getValue("theme.rcc")
        val names = java.nio.ByteBuffer.wrap(original).getInt(16)
        val leafName = names + 6 + "dashcast_experimental".length * 2
        for (offset in listOf(names + 2, leafName + 2)) {
            val files = fixture()
            files.getValue("theme.rcc")[offset] = 127
            updateRccMetadata(files)
            assertFalse("name hash offset $offset", verify(files).passed)
        }
    }

}
