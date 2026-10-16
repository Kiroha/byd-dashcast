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
    @Test fun `scope requires both requested and canonical paths and strict APEX versions`() {
        assertTrue(FirmwarePolicy.accepted("/system/vendor/lib/a.so","/vendor/lib/a.so"))
        assertTrue(FirmwarePolicy.firmware("/apex/com.android.runtime@123/lib64/a.so"))
        assertTrue(FirmwarePolicy.firmware("/system/etc/a b|c\n'd"))
        for(path in listOf("/dev/x","/proc/a","/sys/x","/data/apex/x","/systematic/x","/system/../data/key","/system//a","/apex/foo@bad/lib/x","/apex/foo@12x/lib/x","/apex/foo@1@2/lib/x","/apex/apex-info-list.xml"))assertFalse(path,FirmwarePolicy.firmware(path))
        assertFalse(FirmwarePolicy.accepted("/system/bin/x","/data/private/x"))
        assertFalse(FirmwarePolicy.accepted("/data/private/x","/system/bin/x"))
        assertFalse(FirmwarePolicy.accepted("/data/app/pkg/base.apk","/data/app/pkg/base.apk"))
        assertTrue(FirmwarePolicy.accepted("/data/app/pkg/base.apk","/data/app/pkg/base.apk",true))
        assertFalse(FirmwarePolicy.accepted("/data/data/pkg/private.apk","/data/data/pkg/private.apk",true))
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
            .replace("/system",folder.absolutePath).replace("/data/local/tmp/dashcast-firmware-123/names-1",snapshot.absolutePath)
        assertEquals(0,shell(command).first)
        val paths=ArrayList<String>();FirmwareNames.read(snapshot.inputStream(),{fail(it)}){if(it!=null)paths.add(it)}
        assertEquals(40,paths.size);assertEquals(40,paths.distinct().size)
    }
    @Test fun `shell page records denied entry and still inventories its readable sibling`() {
        val folder=temp.newFolder();val denied=File(folder,"denied");denied.writeText("fixture")
        Files.setPosixFilePermissions(denied.toPath(),emptySet())
        File(folder,"good").writeBytes(byteArrayOf())
        try {
            val result=shell(FirmwareShell.batch(listOf("/system/denied","/system/good")).replace("/system",folder.absolutePath))
            assertEquals(result.second,0,result.first)
            val rows=FirmwarePolicy.boundedLines(result.second)
            // This desktop fixture runs as an unprivileged user; no vehicle permission is changed.
            assertEquals(1,rows.count{it.startsWith("E|")});assertEquals(1,rows.count{it.startsWith("F|")});assertTrue(rows.contains("BATCH|2"))
        } finally { Files.setPosixFilePermissions(denied.toPath(),setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) }
    }
    @Test fun `file symlink escape is rejected before shell read-open probe`() {
        val folder=temp.newFolder();val outside=temp.newFile();outside.writeText("fixture")
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

}
