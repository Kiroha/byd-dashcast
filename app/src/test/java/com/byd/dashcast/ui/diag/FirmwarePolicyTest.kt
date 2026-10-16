package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64

class FirmwarePolicyTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `global scope accepts ordinary paths independent of root and rejects interfaces and our own output`() {
        for(path in listOf("/","/system/lib/a.so","/data/vendor/file","/storage/emulated/0/photo.jpg","/data/apex/decompressed/x","/apex/module@bad/lib/x","/random/file"))assertTrue(path,FirmwarePolicy.firmware(path))
        for(path in listOf("/dev/x","/proc/a","/sys/x","/system/../data/key","/system//a","/data/local/tmp/dashcast-firmware-123/names-1","/data/media/0/Android/data/com.byd.dashcast/files/a.zip","/data/user/10/com.byd.dashcast/shared_prefs/a","/data/data/com.byd.dashcast/cache/x"))assertFalse(path,FirmwarePolicy.firmware(path))
        assertFalse(FirmwarePolicy.accepted("/data/file","/proc/uptime"))
        assertTrue(FirmwarePolicy.accepted("/data/vendor/file","/data/vendor/file"))
    }
    @Test fun `bounded line decoder rejects truncation and invalid UTF8 before interpreting paths`() {
        try{FirmwarePolicy.boundedLines("x".repeat(FirmwarePolicy.MAX_LINE+1));fail()}catch(_:IOException){}
        try{FirmwarePolicy.decoded(Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte())));fail()}catch(_:java.nio.charset.CharacterCodingException){}
        val name="/system/a 'b|c\n\t$";assertEquals(name,FirmwarePolicy.decoded(Base64.getEncoder().encodeToString(name.toByteArray())))
    }
    private fun shell(command:String):Pair<Int,String> {
        val process=ProcessBuilder("sh","-c",command).redirectErrorStream(true).start()
        val output=process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }
    @Test fun `shell metadata encodes spaces separators newline and literal shell syntax without execution`() {
        val folder=temp.newFolder();val file=File(folder,"a ' b|c\n\t\$(not_a_command)`literal`.bin");file.writeBytes(byteArrayOf())
        val result=shell(FirmwareShell.inspect("/system/"+file.name).replace("/system",folder.absolutePath))
        assertEquals(result.second,0,result.first)
        val f=result.second.trim().split('|');assertEquals(5,f.size);assertEquals("F",f[0]);assertEquals(file.absolutePath,FirmwarePolicy.decoded(f[1]));assertEquals(file.canonicalPath,FirmwarePolicy.decoded(f[2]));assertEquals("0",f[3])
    }
    @Test fun `shell page never follows a child directory symlink and counts empty regular files`() {
        val folder=temp.newFolder();val outside=temp.newFolder();File(outside,"secret").writeText("fixture")
        Files.createSymbolicLink(File(folder,"linked-directory").toPath(),outside.toPath())
        File(folder,"zero").writeBytes(byteArrayOf());File(folder,"ordinary-dir").mkdir()
        // Only the test's root is translated to a temporary host fixture; no vehicle path is read.
        val command=FirmwareShell.batch(listOf("/system/linked-directory","/system/zero","/system/ordinary-dir")).replace("/system",folder.absolutePath)
        val result=shell(command);assertEquals(result.second,0,result.first)
        val rows=FirmwarePolicy.boundedLines(result.second)
        assertEquals(1,rows.count{it.startsWith("D|")});assertEquals(1,rows.count{it.startsWith("S|")});assertEquals(1,rows.count{it.startsWith("F|")})
        assertTrue(rows.contains("BATCH|3"));assertFalse(result.second.contains("secret"))
        val alias=FirmwareShell.directory("/system",false).replace("/system",File(folder,"linked-directory").absolutePath)
        assertEquals(2,shell(alias).first)
    }
    @Test fun `shell metadata batches cap records and names snapshot scans directory only once`() {
        val folder=temp.newFolder();repeat(40){File(folder,"f$it").writeText("fixture")}
        for(offset in listOf(0,16,32)) {
            val paths=(offset until minOf(offset+16,40)).map{"/system/f$it"}
            val result=shell(FirmwareShell.batch(paths).replace("/system",folder.absolutePath))
            assertEquals(0,result.first);val rows=FirmwarePolicy.boundedLines(result.second)
            assertEquals(paths.size,rows.count{it.startsWith("F|")});assertTrue(rows.contains("BATCH|${paths.size}"))
        }
        val snapshot=File(temp.newFolder(),"names-1")
        val command=FirmwareShell.snapshot("/system","/data/local/tmp/dashcast-firmware-123/names-1",true)
            .replace("/system",folder.absolutePath).replace("/data/local/tmp/dashcast-firmware-123/names-1",snapshot.absolutePath).replace("/data/local/tmp/dashcast-firmware-123",snapshot.parentFile!!.absolutePath)
        assertEquals(0,shell(command).first)
        val paths=ArrayList<String>();FirmwareNames.read(snapshot.inputStream(),{fail(it)}){if(it!=null)paths.add(it)}
        assertEquals(40,paths.size);assertEquals(40,paths.distinct().size)
    }
    @Test fun `shell metadata never opens unreadable files and still inventories its sibling`() {
        val folder=temp.newFolder();val denied=File(folder,"denied");denied.writeText("fixture")
        Files.setPosixFilePermissions(denied.toPath(),emptySet())
        File(folder,"good").writeBytes(byteArrayOf())
        try {
            val result=shell(FirmwareShell.batch(listOf("/system/denied","/system/good")).replace("/system",folder.absolutePath))
            assertEquals(result.second,0,result.first)
            val rows=FirmwarePolicy.boundedLines(result.second)
            // Metadata does not read payload; the strict FD reader later records any denied open.
            assertEquals(0,rows.count{it.startsWith("E|")});assertEquals(2,rows.count{it.startsWith("F|")});assertTrue(rows.contains("BATCH|2"))
        } finally { Files.setPosixFilePermissions(denied.toPath(),setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) }
    }
    @Test fun `file symlink escape is rejected before shell read-open probe`() {
        val folder=temp.newFolder();val outside=File("/proc/version")
        val link=File(folder,"alias");Files.createSymbolicLink(link.toPath(),outside.toPath())
        val result=shell(FirmwareShell.inspect("/system/alias").replace("/system",folder.absolutePath))
        assertEquals(3,result.first);assertTrue(result.second.contains("outside-scope"));assertFalse(result.second.startsWith("F|"))
    }

    @Test fun `canonical resolution preserves one or many terminal newlines and distinct neighbors`() {
        val folder=temp.newFolder()
        for((i,name) in listOf("name","name\n","name\n\n").withIndex()) {
            val file=File(folder,name);file.writeBytes(ByteArray(i+1){7})
            val result=shell(FirmwareShell.inspect("/system/"+name).replace("/system",folder.absolutePath))
            assertEquals(result.second,0,result.first)
            val f=result.second.trimEnd('\n').split('|')
            assertEquals(file.absolutePath,FirmwarePolicy.decoded(f[1]));assertEquals(file.canonicalPath,FirmwarePolicy.decoded(f[2]));assertEquals((i+1).toString(),f[3])
        }
    }
    @Test fun `NUL parser keeps separators and terminal newlines and omits oversized or malformed names`() {
        val accepted=ArrayList<String?>();val issues=ArrayList<String>()
        val names="/system/a| b\n\u0000/system/c\n\n\u0000".toByteArray()+ByteArray(4097){120}+byteArrayOf(0)+byteArrayOf(0xc3.toByte(),0)+"unfinished".toByteArray()
        FirmwareNames.read(names.inputStream(),issues::add,accepted::add)
        assertEquals(listOf("/system/a| b\n","/system/c\n\n",null,null),accepted)
        assertEquals(3,issues.size)
    }

    @Test fun `all-excluded snapshot still yields bounded pages and cancellation before another input chunk`() {
        val bytes=(0 until 10000).joinToString("") {"/proc/excluded$it\u0000"}.toByteArray()
        var reads=0;var stopped=false;var pages=0
        val input=object:java.io.ByteArrayInputStream(bytes){override fun read(b:ByteArray,off:Int,len:Int):Int {reads++;return super.read(b,off,len)}}
        try {
            FirmwareNames.pages(input,{if(stopped)throw java.util.concurrent.CancellationException()}) {paths,issues->
                assertEquals(FirmwarePolicy.PAGE,paths.size);assertTrue(issues.isEmpty());assertTrue(paths.all{!FirmwarePolicy.firmware(it!!)})
                pages++;stopped=true
            };fail()
        }catch(_:java.util.concurrent.CancellationException){}
        assertEquals(1,pages);assertEquals(1,reads)
    }
    @Test fun `malformed names and exclusions share bounded pages without per-name destination mutation`() {
        val bytes=buildString {repeat(100){append("/proc/x$it\u0000")}}.toByteArray()+byteArrayOf(0xc3.toByte(),0)
        var entries=0;var pages=0;var issues=0
        FirmwareNames.pages(bytes.inputStream(),{}) {paths,errors->assertTrue(paths.size<=FirmwarePolicy.PAGE);entries+=paths.size;pages++;issues+=errors.size}
        assertEquals(101,entries);assertEquals(7,pages);assertEquals(1,issues)
    }
    @Test fun `temporary filesystem reserve fails closed before a snapshot can be written`() {
        val guard=FirmwareShell.space("/data/local/tmp/dashcast-firmware-123")
        val low=shell("stat() { case \"\$*\" in *%a*) printf '1' ;; *%S*) printf '4096' ;; esac; }; $guard printf unexpected")
        assertEquals(123,low.first);assertFalse(low.second.contains("unexpected"))
        val unknown=shell("stat() { return 1; }; $guard printf unexpected")
        assertEquals(123,unknown.first)
        val malformed=shell("stat() { printf 'unknown'; }; $guard printf unexpected")
        assertEquals(123,malformed.first);assertFalse(malformed.second.contains("unexpected"))
        val enough=shell("stat() { case \"\$*\" in *%a*) printf '1000000' ;; *%S*) printf '4096' ;; esac; }; $guard printf ready")
        assertEquals(0,enough.first);assertEquals("ready",enough.second)
    }

    @Test fun `snapshot stderr is bounded at producer and failed find keeps NUL names and exact exit separately`() {
        val folder=temp.newFolder();val snapshot=File(temp.newFolder(),"names-1")
        val script=FirmwareShell.snapshot("/system","/data/local/tmp/dashcast-firmware-123/names-1",false)
            .replace("/system",folder.absolutePath).replace("/data/local/tmp/dashcast-firmware-123/names-1",snapshot.absolutePath)
            .replace("/data/local/tmp/dashcast-firmware-123",snapshot.parentFile!!.absolutePath)
        // Function replacement reproduces an Android find permission error without touching
        // any real vehicle path. More stderr than the Binder budget must still stay bounded.
        val result=shell("find() { printf '%s\\000' ${ApkInventoryShell.quote(folder.path+"/a b|c\n")}; i=0; while [ \"\$i\" -lt 10000 ]; do printf 'éééé permission denied\\n' >&2; i=\$((i+1)); done; return 1; }; $script")
        assertEquals(result.second,0,result.first);assertTrue(result.second.toByteArray().size<3000)
        val parsed=FirmwareShell.snapshotResult(result.second)
        assertEquals(1,parsed.first);assertTrue(parsed.second.contains("permission denied"));assertTrue(parsed.second.toByteArray().size<=2050)
        val names=ArrayList<String?>();FirmwareNames.read(snapshot.inputStream(),{fail(it)},names::add)
        assertEquals(listOf(folder.path+"/a b|c\n"),names)
        assertFalse(snapshot.readBytes().toString(Charsets.UTF_8).contains("permission denied"))
    }
    @Test fun `empty successful snapshot differs from denied snapshot and incomplete markers stay unknown`() {
        assertEquals(0 to "",FirmwareShell.snapshotResult("SCAN|0\nSCAN_ERROR|\n"))
        assertEquals(null to "",FirmwareShell.snapshotResult("SCAN_ERROR|\n"))
        for(text in listOf("SCAN|0\n","SCAN|0\nSCAN_ERROR|@@@\n","junk\nSCAN|0\nSCAN_ERROR|\n")) {
            try{FirmwareShell.snapshotResult(text);fail(text)}catch(_:Exception){}
        }
        val encoded=Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte()))
        assertNotNull(FirmwareShell.snapshotResult("SCAN|1\nSCAN_ERROR|$encoded\n").second)
    }
    @Test fun `shell cannot equate inaccessible or absent path with a proven special file`() {
        val path=temp.root.path+"/absent"
        val result=shell(FirmwareShell.batch(listOf("/system/absent")).replace("/system",temp.root.path))
        assertTrue(result.second.contains("U|"));assertFalse(result.second.contains("S|"))
        assertTrue(result.second.contains(Base64.getEncoder().encodeToString(path.toByteArray())))
    }
    @Test fun `package references validate framing and package grammar before any path can become a seed`() {
        val text="package:/data/app/opaque/base.apk=com.example.app\npackage:/data/app/opaque/base.apk=com.example.duplicate\n\nPACKAGES|0\n"
        assertEquals(listOf("/data/app/opaque/base.apk"),FirmwareDiscoverySeeds.packagePaths(0,text))
        for(bad in listOf(text.replace("PACKAGES|0","PACKAGES|1"),text.substringBefore("PACKAGES"),text.replace("com.example.app","a;id"),text.replace("/data/app/opaque","/data/../opaque"),"PACKAGES|0\n")) {
            try{FirmwareDiscoverySeeds.packagePaths(0,bad);fail(bad)}catch(_:Exception){}
        }
        assertTrue(FirmwareDiscoverySeeds.known(10).contains("/storage/emulated/10"))
    }

}
