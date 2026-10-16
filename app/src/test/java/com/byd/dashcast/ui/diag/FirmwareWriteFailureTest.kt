package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FirmwareWriteFailureTest {
    @get:Rule val temporary=TemporaryFolder()
    private class Device(val files:Map<String,ByteArray>):FirmwareExport.Device {
        var attestation:()->Unit={}
        var attestations=0
        var closes=0
        var preparing:(String)->Unit={}
        override fun prepareDestination(path:String) {preparing(path)}
        override val outputGuard=FirmwareWriteGuard {attestations++;attestation()}
        override fun context()="{}"
        override fun discover(ledger:FirmwareLedger,check:()->Unit,update:(String)->Unit) {
            files.forEach { (path,bytes)->check();ledger.add("SHELL",FirmwareObserved(path,path,bytes.size.toLong(),"synthetic")) }
        }
        override fun open(source:FirmwareSource,identity:String):InputStream=ByteArrayInputStream(files.getValue(source.canonical))
        override fun close() {closes++;outputGuard.check()}
    }
    private fun snapshot(directory:File):Map<String,List<Byte>> = directory.walkTopDown().filter{it.isFile}.associate {it.relativeTo(directory).path to it.readBytes().toList()}
    private fun run(directory:File,device:Device,progress:(FullApkExport.Progress)->Unit={}):FullApkExport.Progress =
        FirmwareLedger(directory,writes=device.outputGuard).use { ledger ->
            FirmwareExport(device,directory,ledger,{false},progress,freeBytes={4L*1024*1024*1024}).run()
        }
    private fun sentinels(directory:File) {
        File(directory,"finished-old.zip").writeText("immutable old ZIP")
        File(directory,"manifest.json").writeText("retained old manifest")
        File(directory,"RESULT.txt").writeText("retained old result")
        File(directory,"RUNNING.txt").writeText("retained running marker")
    }
    @Test fun `real engine aborts raw FD without footer journal update result overwrite or cleanup after terminal refusal`() {
        val directory=temporary.newFolder();sentinels(directory)
        val device=Device(mapOf("/system/large" to ByteArray(FirmwarePolicy.BUFFER*3){(it*31+17).toByte()}))
        var finishing=false
        var atRefusal:Map<String,List<Byte>>?=null
        device.attestation={if(finishing){atRefusal=snapshot(directory);throw IOException("synthetic destination lost before footer")}}
        val result=run(directory,device){if(it.stage.startsWith("Fermeture du ZIP"))finishing=true}
        assertEquals("failed",result.outcome);assertEquals(0,result.completed)
        assertNotNull(atRefusal);assertEquals(atRefusal,snapshot(directory))
        val part=directory.listFiles()!!.single{it.name.endsWith(".part")}
        assertTrue(part.length()>FirmwarePolicy.BUFFER)
        try{ZipFile(part).close();fail("no central directory may be emitted after refusal")}catch(_:IOException){}
        assertTrue(device.outputGuard.refused)
        val calls=device.attestations
        device.attestation={fail("terminal refusal may not be rearmed")}
        repeat(2){try{device.outputGuard.validate();fail()}catch(_:FirmwareOutputRefused){};try{device.close();fail()}catch(_:FirmwareOutputRefused){}}
        assertEquals(calls,device.attestations);assertEquals(atRefusal,snapshot(directory))
    }
    @Test fun `real engine refuses ZIP rename after footer and preserves all files and ledger at refusal`() {
        val directory=temporary.newFolder();sentinels(directory)
        val device=Device(mapOf("/system/file" to ByteArray(50){it.toByte()}))
        var atRefusal:Map<String,List<Byte>>?=null
        device.attestation={
            val part=directory.listFiles().orEmpty().firstOrNull{it.name.endsWith(".part")}
            if(part!=null && runCatching {ZipFile(part).use{it.size()}}.isSuccess) {
                atRefusal=snapshot(directory);throw IOException("synthetic loss before ZIP rename")
            }
        }
        val result=run(directory,device)
        assertEquals("failed",result.outcome);assertEquals(0,result.completed)
        assertNotNull(atRefusal);assertEquals(atRefusal,snapshot(directory))
        assertEquals(listOf("finished-old.zip"),directory.listFiles()!!.filter{it.extension=="zip"}.map{it.name})
        assertEquals(1,directory.listFiles()!!.count{it.name.endsWith(".part")})
    }
    @Test fun `real engine refuses manifest rename and never overwrites RESULT after that refusal`() {
        val directory=temporary.newFolder();sentinels(directory)
        val device=Device(emptyMap());var atRefusal:Map<String,List<Byte>>?=null
        device.attestation={if(File(directory,"manifest.json.tmp").isFile){atRefusal=snapshot(directory);throw IOException("synthetic loss before manifest rename")}}
        val result=run(directory,device)
        assertEquals("failed",result.outcome);assertNotNull(atRefusal);assertEquals(atRefusal,snapshot(directory))
        assertEquals("retained old manifest",File(directory,"manifest.json").readText())
        assertEquals("retained old result",File(directory,"RESULT.txt").readText())
    }
    @Test fun `real session retains RUNNING and finished ZIP when attestation fails immediately before marker deletion`() {
        val root=temporary.newFolder();val calls=AtomicInteger();val device=Device(mapOf("/system/file" to byteArrayOf(1,2,3)))
        var directory:File?=null;var atRefusal:Map<String,List<Byte>>?=null
        device.attestation={
            val current=FullApkExportSession.snapshot
            if(current?.running==false && current.directory==directory && directory!=null) {
                atRefusal=snapshot(directory!!);throw IOException("synthetic loss before RUNNING deletion")
            }
        }
        device.preparing={directory=File(it)}
        FullApkExportSession.start(RuntimeEnvironment.getApplication(),ExportKind.FIRMWARE_LARGE,
            launch={task,_->task.run()},firmwareFactory={_,_->device},root={_,_->root}) {calls.incrementAndGet()}
        assertEquals(1,calls.get());assertNotNull(atRefusal)
        assertEquals("failed",FullApkExportSession.snapshot!!.outcome)
        assertTrue(File(directory!!,"RUNNING.txt").isFile)
        assertEquals(atRefusal,snapshot(directory!!))
        assertEquals(1,directory!!.listFiles()!!.count{it.extension=="zip"})
    }
}
