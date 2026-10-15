package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SystemExportPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `firmware artifacts both ABIs and partition aliases are allowed`() {
        for (path in listOf("/system/framework/framework.jar", "/system/framework/oat/arm64/services.odex",
            "/system/framework/arm/boot.art", "/vendor/framework/boot.vdex", "/product/framework/ext.oat",
            "/system/system_ext/framework/oem.jar", "/system/lib/libbydauto.so", "/system/lib64/libbydauto.so",
            "/system/etc/container_comm_cfg.json", "/system/etc/permissions/oem.xml", "/product/etc/sysconfig/oem.xml",
            "/vendor/etc/init/fission.rc")) assertTrue(path, SystemExportPolicy.allowed(path))
    }
    @Test fun `userdata keys APKs devices and arbitrary binaries are excluded`() {
        for (path in listOf("/data/user/0/com.byd.secret/key.xml", "/sdcard/private.jar", "/proc/version",
            "/sys/devices/can", "/system/framework/oem.apk", "/system/lib64/libunknown.so", "/system/etc/security/key.xml",
            "/system/framework/../etc/permissions/foo.xml", "/system//framework/foo.jar", "/system/framework/a.jar\nrm -rf x")) {
            assertFalse(path, SystemExportPolicy.allowed(path))
        }
    }
    @Test fun `canonical aliases stay in firmware and symlink escape is denied`() {
        assertTrue(SystemExportPolicy.accepted("/product/framework/oem.jar", "/system/product/framework/oem.jar"))
        assertFalse(SystemExportPolicy.accepted("/system/framework/safe.jar", "/data/user/0/a/safe.jar"))
        assertFalse(SystemExportPolicy.accepted("/system/etc/init/safe.rc", "/data/local/tmp/private.rc"))
    }
    @Test fun `runtime classpaths are data and only known APEX javalib modules qualify`() {
        val (accepted, rejected) = SystemExportPolicy.classpath("/system/framework/core.jar:/apex/com.android.runtime/javalib/core-oj.jar:/data/secret.jar:/apex/vendor.module/javalib/oem.jar:/system/framework/x.jar;touch /tmp/a")
        assertEquals(listOf("/system/framework/core.jar", "/apex/com.android.runtime/javalib/core-oj.jar"), accepted)
        assertEquals(3, rejected.size)
        assertFalse(SystemExportPolicy.isApexClasspath("/apex/com.android.runtime/bin/cmd.jar"))
        assertTrue(SystemExportPolicy.isApexClasspath("/apex/com.android.runtime@123/javalib/core-oj.jar"))
        assertFalse(SystemExportPolicy.isApexClasspath("/apex/com.android.runtime@123evil/javalib/core-oj.jar"))
        assertFalse(SystemExportPolicy.isApexClasspath("/apex/com.android.runtime@/javalib/core-oj.jar"))
    }
    @Test fun `unknown or changed build context never establishes same firmware`() {
        assertFalse(SystemExportPolicy.contextIssues(emptyMap(), emptyMap()).isEmpty())
        assertFalse(SystemExportPolicy.contextIssues(mapOf("ro.build.fingerprint" to "A"), emptyMap()).isEmpty())
        assertTrue(SystemExportPolicy.contextIssues(mapOf("ro.build.fingerprint" to "A"), mapOf("ro.build.fingerprint" to "B")).single().contains("changed"))
        assertTrue(SystemExportPolicy.contextIssues(mapOf("ro.build.fingerprint" to "A"), mapOf("ro.build.fingerprint" to "A")).isEmpty())
    }
    @Test fun `unknown oversized or aggregate rejected inventory stops all later snapshots`() {
        for (rejected in listOf<Long?>(null, -1, 11)) {
            val budget = SystemInventoryBudget(perRoot = 10, total = 20)
            assertTrue(budget.accept(8))
            assertFalse(budget.accept(rejected))
            assertTrue(budget.stopped)
            assertFalse(budget.accept(1))
            assertEquals(8, budget.bytes)
        }
        val budget = SystemInventoryBudget(perRoot = 10, total = 20)
        assertTrue(budget.accept(8))
        assertTrue(budget.accept(8))
        assertFalse(budget.accept(8))
        assertTrue(budget.stopped)
        assertFalse(budget.accept(1))
    }
    @Test fun `cancelled collection never starts another context command`() {
        var commands = 0
        val result = SystemExportPolicy.finalContext(mapOf("ro.build.fingerprint" to "before"), true) {
            commands++; mapOf("ro.build.fingerprint" to "after")
        }
        assertEquals(0, commands)
        assertTrue(result.firmware.isEmpty())
        assertTrue(result.issues.single().contains("cancelled"))
    }
    @Test fun `command truncation timeout and exit errors remain partial`() {
        for (reply in listOf("ERR timeout 30s", "[output truncated]", "__DC_SYSTEM_SCAN__:1", "", "file.jar\n__DC_SYSTEM_SCAN__:0")) {
            assertNotNull(SystemExportPolicy.completionIssue(reply, "__DC_SYSTEM_SCAN__"))
        }
        assertNull(SystemExportPolicy.completionIssue("__DC_SYSTEM_SCAN__:0\n", "__DC_SYSTEM_SCAN__"))
    }
    @Test fun `recovery chooses latest timestamp across explicit profiles`() {
        val apk = temporary.newFolder("apk-exports")
        val system = temporary.newFolder("system-exports")
        File(system, "system-export-20261015-100000-001").mkdir()
        File(apk, "apk-export-20261015-110000-001").mkdir()
        val kinds = listOf(ExportKind.APK to apk, ExportKind.SYSTEM to system)
        assertEquals(ExportKind.APK, ExportKind.latest(kinds)!!.first)
        File(system, "system-export-20261015-120000-001").mkdir()
        assertEquals(ExportKind.SYSTEM, ExportKind.latest(kinds)!!.first)
        File(system, "unrelated-future-directory").mkdir()
        assertTrue(ExportKind.latest(kinds)!!.second.name.startsWith("system-export-"))
    }
    @Test fun `escaping init symlink is rejected before grep can read its target`() {
        val root = temporary.newFolder("init")
        val secret = temporary.newFile("private-data")
        secret.writeText("autoservice PRIVATE_MARKER")
        Files.createSymbolicLink(File(root, "service.rc").toPath(), secret.toPath())
        val bin = temporary.newFolder("bin")
        val log = File(temporary.root, "grep-was-called")
        val fakeGrep = File(bin, "grep")
        fakeGrep.writeText("#!/bin/sh\nprintf forbidden > ${ApkInventoryShell.quote(log.absolutePath)}\nexit 0\n")
        assertTrue(fakeGrep.setExecutable(true))
        val process = ProcessBuilder("sh", "-c", SystemInventoryCommands.inspectFiles("init"), "_", File(root, "service.rc").absolutePath)
            .redirectErrorStream(true)
        process.environment()["PATH"] = bin.absolutePath + ":" + process.environment()["PATH"]
        val running = process.start()
        val reply = running.inputStream.bufferedReader().readText()
        assertEquals(0, running.waitFor())
        assertTrue(reply, reply.contains("canonical path outside firmware policy"))
        assertFalse("No content-reading grep may run on escaping aliases", log.exists())
        assertFalse(reply.contains("PRIVATE_MARKER"))
    }
    @Test fun `shell APEX guard rejects repeated or nonnumeric version suffix before stat`() {
        val bin = temporary.newFolder("apex-tools")
        val forbidden = File(temporary.root, "apex-stat-was-called")
        val readlink = File(bin, "readlink")
        val stat = File(bin, "stat")
        stat.writeText("#!/bin/sh\nprintf forbidden > ${ApkInventoryShell.quote(forbidden.absolutePath)}\nexit 1\n")
        assertTrue(stat.setExecutable(true))
        for (module in listOf("com.android.runtime@bad@42", "com.android.runtime@42bad", "com.android.runtime@")) {
            readlink.writeText("#!/bin/sh\nprintf '%s\\n' ${ApkInventoryShell.quote("/apex/$module/javalib/core.jar")}\n")
            assertTrue(readlink.setExecutable(true))
            val builder = ProcessBuilder("sh", "-c", SystemInventoryCommands.inspectFiles("apex"), "_", "/apex/com.android.runtime/javalib/core.jar").redirectErrorStream(true)
            builder.environment()["PATH"] = bin.absolutePath + ":" + builder.environment()["PATH"]
            val process = builder.start()
            val reply = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            assertTrue(reply, reply.contains("unsupported APEX version"))
            assertFalse(forbidden.exists())
        }
    }
    @Test fun `escaping root is denied before recursive enumeration`() {
        val root = temporary.newFolder("root-target")
        File(root, "private.rc").writeText("autoservice PRIVATE_ROOT_MARKER")
        val alias = File(temporary.root, "init-root-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        val process = ProcessBuilder("sh", "-c", SystemInventoryCommands.scan(alias.absolutePath, "init")).redirectErrorStream(true).start()
        val reply = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        assertTrue(reply, reply.contains("ROOT_POLICY_DENIED"))
        assertFalse(reply.contains("private.rc"))
    }
    @Test fun `bounded metadata reader never consumes a large file before truncating`() {
        var consumed = 0
        val reader = object : java.io.StringReader("a".repeat(1000000)) {
            override fun read(chars: CharArray, offset: Int, length: Int): Int {
                return super.read(chars, offset, length).also { if (it > 0) consumed += it }
            }
        }
        val (text, truncated) = ExportText.read(reader, 1600)
        assertEquals(1600, text.length)
        assertTrue(truncated)
        assertEquals(1601, consumed)
        assertFalse(ExportText.read(java.io.StringReader("short"), 1600).second)
    }
    @Test fun `large inventory is captured once without shell reply truncation`() {
        val snapshot = File(temporary.root, "inventory.txt")
        val script = SystemInventoryCommands.snapshot("i=0; while [ \"\$i\" -lt 12000 ]; do printf 'SYS|1|/system/framework/%s.jar|/system/framework/%s.jar\\n' \"\$i\" \"\$i\"; i=\$((i+1)); done", snapshot.absolutePath)
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val reply = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        assertNull(SystemExportPolicy.completionIssue(reply, "__DC_SYSTEM_SCAN__"))
        assertTrue(snapshot.length() > 256 * 1024)
        assertEquals(12000, snapshot.useLines { it.count() })
    }
    @Test fun `inventory disk limit reports nonzero completion instead of success`() {
        val snapshot = File(temporary.root, "inventory-limit.txt")
        val script = SystemInventoryCommands.snapshot("head -c 10000000 /dev/zero", snapshot.absolutePath)
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val reply = process.inputStream.bufferedReader().readText()
        process.waitFor()
        assertNotNull(reply, SystemExportPolicy.completionIssue(reply, "__DC_SYSTEM_SCAN__"))
        assertTrue(snapshot.length() <= SystemExportPolicy.MAX_SNAPSHOT_BYTES)
    }
}
