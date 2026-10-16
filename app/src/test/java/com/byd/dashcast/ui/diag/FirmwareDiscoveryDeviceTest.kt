package com.byd.dashcast.ui.diag

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.daemon.StorageMetadata
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/** Actual Device -> SQLite discovery -> strict Binder reads -> independent ZIP workflow.
 * A fake daemon models searchable known descendants below unlistable Android parents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FirmwareDiscoveryDeviceTest {
    @get:Rule val temp=TemporaryFolder()
    @After fun clear(){FirmwareDiagnostics.begin()}
    private fun b64(text:String)=Base64.getEncoder().encodeToString(text.toByteArray())
    private val destination="/storage/emulated/0/Android/data/com.byd.dashcast/files/firmware-exports/firmware-export-fixture"
    private val mounts="1 0 0:1 / / rw - rootfs rootfs rw\n"+
        "2 1 8:1 / /data rw - ext4 /dev/block/sda1 rw\n"+
        "3 1 0:50 / /storage/emulated rw - sdcardfs /data/media rw\n"+
        "4 1 8:97 / /storage/A1B2-C3D4 rw - vfat /dev/block/vold/public:8,97 rw\n"
    private val apk="/data/app/opaque/base.apk"
    private val visible="/storage/emulated/0/visible.bin"

    private inner class Endpoint:Binder() {
        var temporary:String?=null
        var denyParents=true
        var cancelled=false
        var cancelAfterSnapshot=false
        val sourceCalls=ArrayList<String>()
        val chunks=ArrayList<String>()
        val scans=LinkedHashMap<String,Int>()
        val snapshots=HashMap<String,ByteArray>()
        val payloads=linkedMapOf(apk to byteArrayOf(1,2,3),visible to byteArrayOf(4,5))
        private val directories=setOf("/","/data","/data/app","/data/app/opaque","/storage/emulated","/storage/emulated/0")
        private fun dev(path:String)=if(path.startsWith("/storage/emulated"))50L else if(path.startsWith("/data"))2049L else 1L
        private fun stamp(path:String,bytes:ByteArray)="81a4:${bytes.size}:1:1:${100+path.length}:${if(path.startsWith("/data/local/tmp"))2049 else dev(path)}"
        private fun batch(path:String):String {
            sourceCalls.add(path)
            check(!path.startsWith("/storage/A1B2-C3D4")) {"external metadata reached"}
            return when {
                path in payloads->"F|${b64(path)}|${b64(path)}|${payloads.getValue(path).size}|${stamp(path,payloads.getValue(path))}\nBATCH|1\n"
                path in directories->"D|${b64(path)}\nBATCH|1\n"
                else->"U|${b64(path)}\nBATCH|1\n"
            }
        }
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
            val out=reply!!
            when(code) {
                ProxyDaemonContract.TXN_WHOAMI->{out.writeNoException();out.writeInt(2000);out.writeInt(99);out.writeString("26");out.writeString("0123456789abcdef0123456789abcdef")}
                ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES->{out.writeNoException();out.writeInt(1);out.writeInt(3)}
                ProxyDaemonContract.TXN_STORAGE_METADATA->{
                    val request=StorageMetadata.request(data)
                    val text=when(request.operation) {
                        StorageMetadata.MOUNTINFO->mounts
                        StorageMetadata.PROPERTY->"fixture\n"
                        StorageMetadata.PRIMARY_UUID->"null\n"
                        StorageMetadata.VOLUMES->"private mounted null\nemulated mounted null\npublic:8,97 mounted A1B2-C3D4\n"
                        StorageMetadata.BOOT_DEVICE->"fixture\n"
                        StorageMetadata.BLOCK->"BLOCK|${request.argument}|${b64("/sys/devices/platform/ufs/block/sda")}|0|MMC||\n"
                        StorageMetadata.OUTPUT->{
                            val path=request.argument
                            val real=if(path==destination)"/storage/emulated/0" else if(path==temporary || path in snapshots)path else "/data/local/tmp"
                            "${b64(real)}|${if(path==destination)50 else 2049}"
                        }
                        else->error("Unexpected metadata ${request.operation}")
                    }
                    out.writeNoException();out.writeByteArray(text.toByteArray())
                }
                ProxyDaemonContract.TXN_EXEC->{
                    val command=data.readString()!!
                    val text=when {
                        command.startsWith("pm list packages -f --user ")->"package:$apk=com.example.fixture\npackage:/storage/A1B2-C3D4/external.apk=com.example.card\n\nPACKAGES|0\n"
                        command.startsWith("umask 077; mkdir '")->{temporary=command.substringAfter("mkdir '").substringBefore('\'');""}
                        command.startsWith("for dc_p in '")->{
                            // Fixture pages contain one eligible child; excluded SD is rejected
                            // before a page is constructed.
                            val path=command.substringAfter("for dc_p in '").substringBefore('\'')
                            batch(path)
                        }
                        command.startsWith("dc_p='") && command.contains("'%d:%i'")->{
                            val path=command.substringAfter("dc_p='").substringBefore('\'')
                            "${b64(path)}|${dev(path)}:${100+path.length}\n"
                        }
                        command.contains("find -H")->{
                            val path=command.substringAfter("dc_d='").substringBefore('\'')
                            val snapshot=Regex("/data/local/tmp/dashcast-firmware-[a-f0-9-]+/names-[0-9]+").find(command)!!.value
                            scans[path]=(scans[path]?:0)+1
                            val denied=denyParents && path in setOf("/data","/data/app","/data/app/opaque","/storage/emulated")
                            val names=when {
                                denied->emptyList()
                                path=="/"->listOf("/data","/storage/emulated","/storage/A1B2-C3D4")
                                path=="/storage/emulated/0"->listOf(visible)
                                path=="/data/app" && !denyParents->listOf("/data/app/new.apk")
                                else->emptyList()
                            }
                            snapshots[snapshot]=names.joinToString(""){"$it\u0000"}.toByteArray()
                            if(cancelAfterSnapshot)cancelled=true
                            "SCAN|${if(denied)1 else 0}\nSCAN_ERROR|${if(denied)b64("find: $path: Permission denied") else ""}\n"
                        }
                        command.startsWith("stat -L -c %s '")->{val path=command.substringAfter("%s '").substringBefore('\'');"${snapshots.getValue(path).size}\n"}
                        command.startsWith("stat -L -c '%f:%s:%Y:%Z:%i:%d' '")->{val path=command.substringAfter("%d' '").substringBefore('\'');"${stamp(path,snapshots.getValue(path))}\n"}
                        command.startsWith("rm -f '")->{snapshots.remove(command.substringAfter("rm -f '").substringBefore('\''));""}
                        command.startsWith("rmdir '")->{assertTrue(snapshots.isEmpty());temporary=null;""}
                        command==FirmwareShell.inspect(apk)->batch(apk).substringBefore("BATCH|")
                        command==FirmwareShell.inspect(visible)->batch(visible).substringBefore("BATCH|")
                        command==FirmwareShell.inspect("/data/app/new.apk")->batch("/data/app/new.apk").substringBefore("BATCH|")
                        else->error("Unexpected command $command")
                    }
                    out.writeNoException();out.writeInt(0);out.writeString(text)
                }
                ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT->{
                    val path=data.readString()!!;val offset=data.readLong();val length=data.readInt();repeat(6){data.readLong()}
                    chunks.add(path);assertFalse(path.startsWith("/storage/A1B2-C3D4"))
                    val bytes=snapshots[path]?:payloads[path]?:error("Unexpected chunk $path")
                    out.writeNoException();out.writeByteArray(bytes.copyOfRange(offset.toInt(),minOf(bytes.size,offset.toInt()+length)))
                }
                else->error("Unexpected transaction $code")
            }
            return true
        }
    }
    private fun run(endpoint:Endpoint,directory:File):FullApkExport.Progress {
        val device=FirmwareDevice(RuntimeEnvironment.getApplication(),{endpoint.cancelled},endpoint={endpoint},connect={true},
            observeOutput={_,_,_->"stable own output observation"})
        device.prepareDestination(destination)
        return FirmwareLedger(directory).use {ledger->FirmwareExport(device,directory,ledger,{endpoint.cancelled},{},freeBytes={4L*1024*1024*1024}).run()}
    }
    @Test fun `unlistable parents do not hide known package files or current user storage and failures remain retryable`() {
        FirmwareDiagnostics.begin();val endpoint=Endpoint();val directory=temp.newFolder()
        val first=run(endpoint,directory)
        assertEquals("partial",first.outcome);assertEquals(2,first.discovered);assertEquals(2,first.completed);assertEquals(1,first.archives.size)
        assertEquals(1,endpoint.scans["/data"]);assertEquals(1,endpoint.scans["/data/app"]);assertEquals(1,endpoint.scans["/storage/emulated/0"])
        assertTrue(endpoint.sourceCalls.contains(apk));assertTrue(endpoint.sourceCalls.contains(visible))
        assertFalse(endpoint.sourceCalls.any{it.startsWith("/storage/A1B2-C3D4")});assertFalse(endpoint.chunks.any{it.startsWith("/storage/A1B2-C3D4")})
        val manifest=org.json.JSONObject(File(directory,"manifest.json").readText())
        assertFalse(manifest.getBoolean("discoveryComplete"));assertTrue(manifest.getJSONArray("incidents").toString().contains("Permission denied"))
        val report=FirmwareDiagnostics.snapshot();assertTrue(report.contains("sources=2; exported=2"));assertTrue(report.contains("retryable="));assertTrue(report.contains("Permission denied"))
        val retained=first.archives.single().readBytes()
        endpoint.denyParents=false;endpoint.payloads["/data/app/new.apk"]=byteArrayOf(7)
        endpoint.chunks.clear()
        val second=run(endpoint,directory)
        assertEquals(3,second.completed);assertArrayEquals(retained,first.archives.single().readBytes())
        assertEquals(2,endpoint.scans["/data/app"]);assertFalse(endpoint.chunks.contains(apk));assertFalse(endpoint.chunks.contains(visible));assertTrue(endpoint.chunks.contains("/data/app/new.apk"))
    }
    @Test fun `old completed inventory is rescanned after discovery revision without recopying verified ZIP files`() {
        val endpoint=Endpoint();val directory=temp.newFolder();val first=run(endpoint,directory)
        FirmwareLedger(directory).use {ledger->
            // v37 had no revision and marked failed roots done with discovery_complete=1.
            ledger.meta("discovery_revision","legacy");ledger.restartDiscovery()
            while(true){val dir=ledger.nextDirectory()?:break;ledger.advance(dir,0,true)}
            ledger.meta("discovery_complete","1")
        }
        val retained=first.archives.single().readBytes();endpoint.chunks.clear()
        val before=endpoint.scans.getValue("/data/app")
        val second=run(endpoint,directory)
        assertEquals(2,second.completed);assertEquals(before+1,endpoint.scans["/data/app"])
        assertFalse(endpoint.chunks.contains(apk));assertFalse(endpoint.chunks.contains(visible));assertArrayEquals(retained,first.archives.single().readBytes())
        FirmwareLedger(directory).use {assertEquals(FirmwareDiscoverySeeds.REVISION,it.meta("discovery_revision"));assertEquals("limited",it.meta("discovery_complete"))}
    }
    @Test fun `zero payload is explicitly empty and retains final counts without pretending discovery was complete`() {
        FirmwareDiagnostics.begin();val endpoint=Endpoint().apply{payloads.clear()};val directory=temp.newFolder()
        val result=run(endpoint,directory)
        assertEquals("partial",result.outcome);assertEquals(0,result.discovered);assertTrue(result.archives.isEmpty())
        assertTrue(result.stage.startsWith("Collecte vide"));assertTrue(FirmwareDiagnostics.snapshot().contains("sources=0; exported=0; failed=0"))
        assertFalse(org.json.JSONObject(File(directory,"manifest.json").readText()).getBoolean("discoveryComplete"))
    }
    @Test fun `cancellation after snapshot stops inventory before any source payload and permits only known cleanup`() {
        FirmwareDiagnostics.begin();val endpoint=Endpoint().apply{cancelAfterSnapshot=true};val directory=temp.newFolder()
        val result=run(endpoint,directory)
        assertEquals("cancelled",result.outcome);assertTrue(result.archives.isEmpty());assertTrue(endpoint.chunks.isEmpty())
        assertEquals(listOf("/"),endpoint.sourceCalls);assertNull(endpoint.temporary);assertTrue(endpoint.snapshots.isEmpty())
        assertTrue(FirmwareDiagnostics.snapshot().contains("outcome=cancelled; running=false"))
    }
}
