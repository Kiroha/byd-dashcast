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
    @Test fun `P4 native candidates remain exact by ABI partition and case`() {
        val expected = setOf("libEGL.so", "libGLESv2.so", "libandroid_runtime.so", "libbinder.so", "libc++.so", "libc.so",
            "libcrypto.so", "libcutils.so", "libdl.so", "libfission_cutils.so", "libgui.so", "libhardware.so",
            "libhardware_legacy.so", "liblog.so", "libm.so", "libnativeloader.so", "libnetutils.so", "libselinux.so",
            "libsync.so", "libui.so", "libutils.so", "libzmq.so")
        assertEquals(27, SystemExportPolicy.nativeNames.size)
        for (root in SystemExportPolicy.nativeRoots) for (name in expected) assertTrue("$root/$name", SystemExportPolicy.allowed("$root/$name"))
        for (path in listOf("/system/bin/fission_service", "/system/bin/fissiond", "/system/bin/qtandroidnative",
            "/system/lib64/libBydCluster.so", "/system/lib64/libAdas.so")) assertTrue(path, SystemExportPolicy.allowed(path))
        for (path in listOf("/vendor/bin/fissiond", "/system/bin/diagmgrsrv", "/system/lib/libBydCluster.so",
            "/system/lib64/libbydcluster.so", "/system/lib64/subdir/libbinder.so")) assertFalse(path, SystemExportPolicy.allowed(path))
    }
    @Test fun `auto HAL candidate names are direct children only and never establish HAL selection`() {
        for (root in SystemExportPolicy.halRoots) for (name in listOf("auto.so", "auto.default.so", "auto.vendor.board.so")) {
            assertTrue("$root/$name", SystemExportPolicy.allowed("$root/$name"))
        }
        for (path in listOf("/system/lib64/hw/audio.default.so", "/system/lib64/auto.default.so",
            "/system/lib64/hw/nested/auto.default.so", "/system/lib64/hw/auto..so", "/system/lib64/hw/auto.default.so.bak")) {
            assertFalse(path, SystemExportPolicy.allowed(path))
        }
    }
    @Test fun `media classpath and Bionic aliases accept only explicitly selected module layout`() {
        assertTrue(SystemExportPolicy.isApexClasspath("/apex/com.android.media@123/javalib/updatable-media.jar"))
        assertFalse(SystemExportPolicy.isApexClasspath("/apex/com.android.media.swcodec/javalib/a.jar"))
        for (abi in listOf("lib", "lib64")) for (name in listOf("libc.so", "libm.so", "libdl.so")) {
            assertTrue(SystemExportPolicy.accepted("/system/$abi/$name", "/apex/com.android.runtime@123/$abi/bionic/$name"))
        }
        for (path in listOf("/apex/com.android.runtime@abc/lib64/bionic/libc.so", "/apex/com.android.runtime@1@2/lib64/bionic/libc.so",
            "/apex/com.android.runtime/lib64/libc.so", "/apex/com.android.runtime/lib64/bionic/liblog.so",
            "/apex/com.android.art/lib64/bionic/libc.so", "/apex/com.android.runtime@123/extra/lib64/bionic/libc.so")) {
            assertFalse(path, SystemExportPolicy.allowed(path))
        }
    }
    @Test fun `native inventory probes selected paths together without enumerating neighbors`() {
        val root = temporary.newFolder("native-root")
        File(root, "private-neighbor.so").writeText("private")
        val bin = temporary.newFolder("native-tools")
        val stats = File(temporary.root, "native-stat.log")
        val finds = File(temporary.root, "native-find.log")
        tool(bin, "readlink", "if [ \"\$2\" = ${ApkInventoryShell.quote(root.path)} ]; then printf '/system/lib64\\n'; else printf '%s\\n' \"\$2\"; fi")
        tool(bin, "stat", "printf '%s\\n' \"\$3\" >> ${ApkInventoryShell.quote(stats.path)}; exit 1")
        tool(bin, "find", "printf forbidden > ${ApkInventoryShell.quote(finds.path)}; exit 1")
        val reply = shell(SystemInventoryCommands.scan(root.path, "native"), bin)
        assertFalse(finds.exists())
        assertEquals(SystemExportPolicy.nativeNames.map { "/system/lib64/$it" }.toSet(), stats.readLines().toSet())
        assertTrue(reply, reply.contains("canonical/stat unavailable"))
        assertFalse(stats.readText().contains("private-neighbor"))
        assertFalse(reply.contains("private-neighbor"))
    }
    @Test fun `HAL scan filters before stat avoids recursion and rejects escaping selected aliases`() {
        val root = temporary.newFolder("hal-root")
        File(root, "auto.default.so").writeText("hal")
        File(root, "audio.default.so").writeText("unrelated")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "auto.hidden.so").writeText("nested")
        val private = temporary.newFile("hal-private")
        Files.createSymbolicLink(File(root, "auto.escape.so").toPath(), private.toPath())
        val bin = temporary.newFolder("hal-tools")
        val stats = File(temporary.root, "hal-stat.log")
        val args = File(temporary.root, "hal-find.log")
        tool(bin, "readlink", "dc_real=\$(/usr/bin/readlink -f \"\$2\"); case \"\$dc_real\" in ${ApkInventoryShell.quote(root.path)}) printf '/system/lib64/hw\\n' ;; " +
            "${ApkInventoryShell.quote(root.path)}/*) printf '/system/lib64/hw/%s\\n' \"\${dc_real##*/}\" ;; *) printf '/data/user/0/private/auto.escape.so\\n' ;; esac")
        tool(bin, "find", "printf '%s\\n' \"\$@\" > ${ApkInventoryShell.quote(args.path)}; shift; shift; exec /usr/bin/find -H ${ApkInventoryShell.quote(root.path)} \"\$@\"")
        tool(bin, "stat", "printf '%s\\n' \"\$3\" >> ${ApkInventoryShell.quote(stats.path)}; exit 1")
        val reply = shell(SystemInventoryCommands.scan(root.path, "hal"), bin)
        assertEquals(listOf("/system/lib64/hw/auto.default.so"), stats.readLines())
        assertTrue(reply, reply.contains("canonical path outside firmware policy"))
        val tokens = args.readLines()
        assertEquals("1", tokens[tokens.indexOf("-maxdepth") + 1])
        assertTrue(tokens.indexOf("-name") < tokens.indexOf("-type"))
        assertFalse(reply.contains("auto.hidden"))
        assertFalse(reply.contains("audio.default"))
    }
    @Test fun `unavailable or denied roots report gaps before enumeration`() {
        val absent = File(temporary.root, "absent")
        assertTrue(shell(SystemInventoryCommands.scan(absent.path, "hal")).contains("ROOT_UNAVAILABLE"))
        val root = temporary.newFolder("denied-root")
        // Shell fixture models denied search rights even when host tests run as a privileged user.
        val prefix = "[() { if builtin test \"\$1\" = '!' && builtin test \"\$2\" = -r; then return 0; else builtin [ \"\$@\"; fi; }; "
        val reply = shell(prefix + SystemInventoryCommands.scan(root.path, "hal"), bash = true)
        assertTrue(reply, reply.contains("ROOT_DENIED"))
        assertFalse(reply.contains("SYS|"))
    }
    @Test fun `init inspection reads at most 64 KiB and reports unmatched truncated content`() {
        val input = temporary.newFile("large-init")
        input.writeText("#".repeat(65536) + "\nservice diagnosticsrv /system/bin/unknown\n")
        val bin = temporary.newFolder("init-bounded-tools")
        val calls = File(temporary.root, "init-head.log")
        tool(bin, "readlink", "printf '/system/etc/init/test.rc\\n'")
        tool(bin, "stat", "printf '%s\\n' ${input.length()}")
        tool(bin, "head", "printf '%s\\n' \"\$@\" > ${ApkInventoryShell.quote(calls.path)}; exec /usr/bin/head \"\$1\" \"\$2\" ${ApkInventoryShell.quote(input.path)}")
        val prefix = "[() { if builtin test \"\$1\" = -f; then return 0; else builtin [ \"\$@\"; fi; }; "
        val script = prefix + "set -- /system/etc/init/test.rc; " + SystemInventoryCommands.inspectFiles("init")
        val reply = shell(script, bin, bash = true)
        assertEquals(listOf("-c", "65536", "/system/etc/init/test.rc"), calls.readLines())
        assertTrue(reply, reply.contains("init inspection truncated"))
        assertFalse(reply.contains("SYS|"))
        input.writeText("service diagmgrsrv /system/bin/unknown\n")
        tool(bin, "stat", "printf '%s\\n' ${input.length()}")
        assertTrue(shell(script, bin, bash = true).contains("SYS|"))
    }
    @Test fun `Bionic root aliases reject nested layouts and malformed versions before probes`() {
        val root = temporary.newFolder("bionic-root")
        val bin = temporary.newFolder("bionic-tools")
        val stats = File(temporary.root, "bionic-stat.log")
        tool(bin, "stat", "printf forbidden > ${ApkInventoryShell.quote(stats.path)}; exit 1")
        for (canonical in listOf("/apex/com.android.runtime@123/extra/lib64/bionic", "/apex/com.android.runtime@bad/lib64/bionic",
            "/apex/com.android.runtime@123@456/lib64/bionic", "/apex/com.android.art/lib64/bionic")) {
            tool(bin, "readlink", "printf '%s\\n' ${ApkInventoryShell.quote(canonical)}")
            val reply = shell(SystemInventoryCommands.scan(root.path, "bionic"), bin)
            assertTrue(reply, reply.contains("ROOT_POLICY_DENIED"))
            assertFalse(stats.exists())
        }
    }
    @Test fun `HAL canonical nested aliases are denied before stat even when glob would match`() {
        val bin = temporary.newFolder("hal-canonical-tools")
        val stat = File(temporary.root, "hal-canonical-stat")
        tool(bin, "stat", "printf forbidden > ${ApkInventoryShell.quote(stat.path)}; exit 1")
        for (canonical in listOf("/system/lib64/hw/auto.private/secret.so", "/vendor/lib/hw/auto..so",
            "/system/lib64/hw/auto..invalid.so")) {
            tool(bin, "readlink", "printf '%s\\n' ${ApkInventoryShell.quote(canonical)}")
            val reply = shell("set -- /system/lib64/hw/auto.default.so; " + SystemInventoryCommands.inspectFiles("hal"), bin)
            assertTrue(reply, reply.contains("ISSUE|"))
            assertFalse("Canonical target must be denied before stat: $canonical", stat.exists())
        }
    }
    @Test fun `exact native probes work with directory traversal but no listing permission`() {
        val prefix = "[() { if builtin test \"\$1\" = '!' && builtin test \"\$2\" = -r; then return 0; else builtin [ \"\$@\"; fi; }; "
        for (mode in listOf("native", "bionic")) {
            val root = temporary.newFolder("x-only-$mode")
            val canonical = if (mode == "native") "/system/lib64" else "/apex/com.android.runtime@123/lib64/bionic"
            val names = if (mode == "native") SystemExportPolicy.nativeNames else SystemExportPolicy.bionicNames
            val bin = temporary.newFolder("x-only-tools-$mode")
            val stats = File(temporary.root, "x-only-stat-$mode")
            tool(bin, "readlink", "if [ \"\$2\" = ${ApkInventoryShell.quote(root.path)} ]; then printf '%s\\n' ${ApkInventoryShell.quote(canonical)}; else printf '%s\\n' \"\$2\"; fi")
            tool(bin, "stat", "printf '%s\\n' \"\$3\" >> ${ApkInventoryShell.quote(stats.path)}; exit 1")
            val reply = shell(prefix + SystemInventoryCommands.scan(root.path, mode), bin, bash = true)
            assertFalse(reply, reply.contains("ROOT_DENIED"))
            assertEquals(names.map { "$canonical/$it" }.toSet(), stats.readLines().toSet())
        }
    }
    private fun tool(directory: File, name: String, body: String) = File(directory, name).apply {
        writeText("#!/bin/sh\n$body\n")
        assertTrue(setExecutable(true))
    }
    private fun shell(script: String, bin: File? = null, bash: Boolean = false): String {
        val builder = ProcessBuilder(if (bash) "bash" else "sh", "-c", script).redirectErrorStream(true)
        if (bin != null) builder.environment()["PATH"] = bin.path + ":" + builder.environment()["PATH"]
        val running = builder.start()
        val reply = running.inputStream.bufferedReader().readText()
        assertEquals(reply, 0, running.waitFor())
        return reply
    }

}
