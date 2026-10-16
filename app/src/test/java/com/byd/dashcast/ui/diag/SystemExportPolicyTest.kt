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
        assertEquals(79, SystemExportPolicy.nativeNames.size)
        for (root in SystemExportPolicy.nativeRoots) for (name in expected) assertTrue("$root/$name", SystemExportPolicy.allowed("$root/$name"))
        for (path in listOf("/system/bin/fission_service", "/system/bin/fissiond", "/system/bin/qtandroidnative",
            "/system/lib64/libBydCluster.so", "/system/lib64/libAdas.so")) assertTrue(path, SystemExportPolicy.allowed(path))
        for (path in listOf("/vendor/bin/fissiond", "/system/lib/libBydCluster.so",
            "/system/lib64/libbydcluster.so", "/system/lib64/subdir/libbinder.so")) assertFalse(path, SystemExportPolicy.allowed(path))
    }
    @Test fun `P10 missing dependencies extend only exact native basenames in existing roots`() {
        val additions = setOf(
            "libETC1.so", "libGLESv1_CM.so", "libGLESv3.so", "libacquisition.so",
            "libandroidfw.so", "libandroidicu.so", "libappfuse.so", "libaudioclient.so",
            "libaudiopolicy.so", "libbase.so", "libbpf_android.so", "libcamera_client.so",
            "libcamera_metadata.so", "libdebuggerd_client.so", "libdiagnostic.so", "libdl_android.so",
            "libfission_utils.so", "libgraphicsenv.so", "libharfbuzz_ng.so", "libhidlbase.so",
            "libhidltransport.so", "libhwbinder.so", "libhwui.so", "libimg_utils.so",
            "libinput.so", "libjpeg.so", "libmedia.so", "libmedia_helper.so",
            "libmediametrics.so", "libmeminfo.so", "libmemtrack.so", "libmemunreachable.so",
            "libminikin.so", "libnativebridge_lazy.so", "libnativehelper.so", "libnativeloader_lazy.so",
            "libnativewindow.so", "libnetd_client.so", "libnetdbpf.so", "libnetdutils.so",
            "libpdfium.so", "libprocessgroup.so", "libsensor.so", "libsoundtrigger.so",
            "libsqlite.so", "libstatslog.so", "libusbhost.so", "libvintf.so",
            "libvulkan.so", "libz.so", "libziparchive.so", "server_configurable_flags.so")
        val previous = setOf(
            "libxdjacontainerservice_jni.so", "libfission_services.so", "libfission_event.so", "libbydauto.so",
            "libbydautoservice.so", "libEGL.so", "libGLESv2.so", "libandroid_runtime.so",
            "libbinder.so", "libc++.so", "libc.so", "libcrypto.so",
            "libcutils.so", "libdl.so", "libfission_cutils.so", "libgui.so",
            "libhardware.so", "libhardware_legacy.so", "liblog.so", "libm.so",
            "libnativeloader.so", "libnetutils.so", "libselinux.so", "libsync.so",
            "libui.so", "libutils.so", "libzmq.so")
        assertEquals(52, additions.size)
        assertEquals(previous + additions, SystemExportPolicy.nativeNames)
        assertEquals(listOf("libacquisition.so", "libdiagnostic.so", "libfission_utils.so"),
            SystemExportPolicy.nativeNames.take(3))
        assertEquals(listOf("/system", "/system_ext", "/product", "/vendor").flatMap {
            listOf("$it/lib", "$it/lib64") }, SystemExportPolicy.nativeRoots)
        for (root in SystemExportPolicy.nativeRoots) for (name in additions) {
            assertTrue("$root/$name", SystemExportPolicy.accepted("$root/$name", "$root/$name"))
            for (outside in listOf("/odm/lib64/$name", "$root/nested/$name", "/data/private/$name")) {
                assertFalse(outside, SystemExportPolicy.accepted("$root/$name", outside))
            }
            assertFalse(SystemExportPolicy.allowed("$root/${name.uppercase()}"))
        }
    }
    @Test fun `additional context properties remain selected values with empty meaning unknown`() {
        assertEquals(17, SystemExportPolicy.propertyKeys.size)
        assertEquals(17, SystemExportPolicy.propertyKeys.toSet().size)
        assertEquals(listOf("apps.setting.product.inswver", "ro.build.region", "sys.which.rse"),
            SystemExportPolicy.propertyKeys.takeLast(3))
        val captured = SystemExportPolicy.propertyKeys.associateWith { "" }
        var captures = 0
        val result = SystemExportPolicy.finalContext(captured, false) { captures++; captured }
        assertEquals(1, captures)
        assertEquals(captured, result.firmware)
        assertTrue(result.issues.single().contains("unknown"))
    }
    @Test fun `discovery retains expanded candidate failures and marks overflow explicitly`() {
        assertEquals(2048, SystemExportPolicy.MAX_ISSUES)
        val issues = SystemDiscoveryIssues()
        repeat(1500) { issues.add("Candidate $it unavailable") }
        assertEquals(1500, issues.snapshot().size)
        assertFalse(issues.snapshot().any { it.contains("omitted") })
        repeat(548) { issues.add("Later candidate $it unavailable") }
        assertEquals(2048, issues.snapshot().size)
        issues.add("Beyond budget")
        val bounded = issues.snapshot()
        assertEquals(2049, bounded.size)
        assertEquals("Additional discovery issues omitted after 2048 entries; collection partial.", bounded.last())
        assertFalse(bounded.contains("Beyond budget"))
        assertEquals(bounded, issues.snapshot())
        val truncated = SystemDiscoveryIssues(1)
        truncated.add("x".repeat(1200)); truncated.add("overflow")
        assertEquals(800, truncated.snapshot().first().length)
        assertTrue(truncated.snapshot().last().contains("after 1 entries"))
    }
    @Test fun `P6 diagnostics and RCC candidates are exact paths with no neighboring scope`() {
        for (path in listOf("/system/bin/diagmgrsrv", "/system/lib64/cluster_theme1.rcc", "/system/lib64/cluster_theme2.rcc")) {
            assertTrue(path, SystemExportPolicy.allowed(path))
            assertTrue(path, SystemExportPolicy.accepted(path, path))
            assertFalse(path, SystemExportPolicy.accepted(path, "/data/private/${path.substringAfterLast('/')}"))
        }
        for (path in listOf("/vendor/bin/diagmgrsrv", "/system/bin/DiagMgrSrv", "/system/bin/diagmgrsrv.old",
            "/system/lib/cluster_theme1.rcc", "/vendor/lib64/cluster_theme1.rcc", "/system/lib64/cluster_theme3.rcc",
            "/system/lib64/Cluster_theme1.rcc", "/system/lib64/nested/cluster_theme1.rcc",
            "/system/lib64/cluster_theme1.rcc/embedded.rcc")) assertFalse(path, SystemExportPolicy.allowed(path))
    }
    @Test fun `exact candidates reject canonical escapes before querying metadata`() {
        val bin = temporary.newFolder("exact-canonical-tools")
        val stats = File(temporary.root, "exact-canonical-stat")
        tool(bin, "stat", "printf forbidden >> ${ApkInventoryShell.quote(stats.path)}; exit 1")
        for (canonical in listOf("/data/private/diagmgrsrv", "/vendor/bin/diagmgrsrv", "/system/bin/DiagMgrSrv",
            "/system/lib64/cluster_theme3.rcc", "/system/lib64/cluster_theme1.rcc/embedded.rcc")) {
            tool(bin, "readlink", "printf '%s\\n' ${ApkInventoryShell.quote(canonical)}")
            val reply = shell("set -- /system/bin/diagmgrsrv; " + SystemInventoryCommands.inspectFiles("exact"), bin)
            assertTrue(reply, reply.contains("canonical path outside firmware policy"))
            assertFalse(canonical, stats.exists())
        }
    }
    @Test fun `three P6 exact candidates reach inventory without inspecting neighbors`() {
        val bin = temporary.newFolder("p6-exact-tools")
        val stats = File(temporary.root, "p6-exact-stat")
        tool(bin, "readlink", "printf '%s\\n' \"\$2\"")
        tool(bin, "stat", "printf '%s\\n' \"\$3\" >> ${ApkInventoryShell.quote(stats.path)}; printf '7\\n'")
        val prefix = "[() { if builtin test \"\$1\" = -f; then return 0; else builtin [ \"\$@\"; fi; }; "
        val paths = listOf("/system/bin/diagmgrsrv", "/system/lib64/cluster_theme1.rcc", "/system/lib64/cluster_theme2.rcc")
        for (path in paths) {
            val reply = shell(prefix + "set -- ${ApkInventoryShell.quote(path)}; " + SystemInventoryCommands.inspectFiles("exact"), bin, bash = true)
            assertEquals("SYS|7|$path|$path", reply.trim())
        }
        assertEquals(paths, stats.readLines())
    }
    @Test fun `auto HAL candidate names are direct children only and never establish HAL selection`() {
        assertEquals(setOf("/odm/lib/hw", "/odm/lib64/hw"), SystemExportPolicy.halRoots.filter { it.startsWith("/odm/") }.toSet())
        assertEquals(10, SystemExportPolicy.halRoots.size)
        assertFalse(SystemExportPolicy.nativeRoots.any { it.startsWith("/odm/") })
        assertFalse(SystemExportPolicy.frameworkRoots.any { it.startsWith("/odm/") })
        for (root in SystemExportPolicy.halRoots) for (name in listOf("auto.so", "auto.default.so", "auto.vendor.board.so")) {
            assertTrue("$root/$name", SystemExportPolicy.allowed("$root/$name"))
            assertTrue("$root/$name", SystemExportPolicy.isAutoHal("$root/$name"))
        }
        for (path in listOf("/system/lib64/hw/audio.default.so", "/system/lib64/auto.default.so",
            "/system/lib64/hw/nested/auto.default.so", "/system/lib64/hw/auto..so", "/system/lib64/hw/auto.default.so.bak",
            "/odm/lib64/libbydauto.so", "/odm/framework/unknown.jar", "/odm/etc/init/diag.rc", "/system/odm/lib64/hw/auto.default.so")) {
            assertFalse(path, SystemExportPolicy.allowed(path))
            assertFalse(path, SystemExportPolicy.isAutoHal(path))
        }
        assertFalse(SystemExportPolicy.isAutoHal("/system/lib64/libbydauto.so"))
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
        File(root, "auto.other.so").writeText("other variant")
        File(root, "audio.default.so").writeText("unrelated")
        val nested = File(root, "nested").apply { mkdir() }
        File(nested, "auto.hidden.so").writeText("nested")
        val private = temporary.newFile("hal-private")
        Files.createSymbolicLink(File(root, "auto.escape.so").toPath(), private.toPath())
        val bin = temporary.newFolder("hal-tools")
        val stats = File(temporary.root, "hal-stat.log")
        val args = File(temporary.root, "hal-find.log")
        tool(bin, "readlink", "case \"\$2\" in /system/lib64/hw/*) printf '%s\\n' \"\$2\"; exit 0 ;; esac; " +
            "dc_real=\$(/usr/bin/readlink -f \"\$2\"); case \"\$dc_real\" in ${ApkInventoryShell.quote(root.path)}) printf '/system/lib64/hw\\n' ;; " +
            "${ApkInventoryShell.quote(root.path)}/*) printf '/system/lib64/hw/%s\\n' \"\${dc_real##*/}\" ;; *) printf '/data/user/0/private/auto.escape.so\\n' ;; esac")
        tool(bin, "find", "printf '%s\\n' \"\$@\" > ${ApkInventoryShell.quote(args.path)}; shift; shift; exec /usr/bin/find -H ${ApkInventoryShell.quote(root.path)} \"\$@\"")
        tool(bin, "stat", "printf '%s\\n' \"\$3\" >> ${ApkInventoryShell.quote(stats.path)}; exit 1")
        val reply = shell(SystemInventoryCommands.scan(root.path, "hal"), bin)
        assertEquals(SystemExportPolicy.halProbeNames.map { "/system/lib64/hw/$it" } + "/system/lib64/hw/auto.other.so", stats.readLines())
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
        val prefix = "[() { if builtin test \"\$1\" = '!' && builtin test \"\$2\" = -x; then return 0; else builtin [ \"\$@\"; fi; }; "
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
    @Test fun `HAL fixed probes survive denied enumeration and exclude arbitrary neighbors`() {
        val prefix = "[() { if builtin test \"\$1\" = -f; then builtin test \"\${2##*/}\" = auto.default.so; " +
            "elif builtin test \"\$1\" = '!' && builtin test \"\$2\" = -r; then return 0; else builtin [ \"\$@\"; fi; }; "
        for (canonical in listOf("/system/lib64/hw", "/odm/lib/hw", "/odm/lib64/hw")) {
            val root = temporary.newFolder("hal-x-only-${canonical.replace('/', '_')}")
            val bin = temporary.newFolder("hal-x-tools-${canonical.replace('/', '_')}")
            val stats = File(root, "stat.log")
            val finds = File(root, "find.log")
            // The inspection runs in a child shell; model its regular-file result there too.
            File(bin, "sh").apply {
                writeText("#!/bin/bash\n${prefix}dc_command=\$2; shift 3; eval \"\$dc_command\"\n")
                assertTrue(setExecutable(true))
            }
            tool(bin, "readlink", "if [ \"\$2\" = ${ApkInventoryShell.quote(root.path)} ]; then printf '%s\\n' ${ApkInventoryShell.quote(canonical)}; else printf '%s\\n' \"\$2\"; fi")
            tool(bin, "stat", "printf '%s\\n' \"\$3\" >> ${ApkInventoryShell.quote(stats.path)}; " +
                "case \"\$3\" in */auto.default.so) printf '7\\n' ;; *) exit 1 ;; esac")
            tool(bin, "find", "printf forbidden > ${ApkInventoryShell.quote(finds.path)}; exit 1")
            val reply = shell(prefix + SystemInventoryCommands.scan(root.path, "hal"), bin, bash = true)
            assertEquals(listOf("auto.so", "auto.qcom.so", "auto.QCM6125.so", "auto.trinket.so", "auto.default.so")
                .map { "$canonical/$it" }, stats.readLines())
            assertTrue(reply, reply.contains("ROOT_ENUMERATION_DENIED"))
            assertTrue(reply, reply.contains("SYS|7|$canonical/auto.default.so|$canonical/auto.default.so"))
            assertFalse(reply, reply.contains("ROOT_DENIED|"))
            assertFalse(finds.exists())
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
