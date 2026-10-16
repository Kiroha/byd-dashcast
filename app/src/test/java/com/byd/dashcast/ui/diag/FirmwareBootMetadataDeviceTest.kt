package com.byd.dashcast.ui.diag

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.daemon.StorageMetadata
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FirmwareBootMetadataDeviceTest {
    @get:Rule val temporary=TemporaryFolder()
    private val destination="/storage/emulated/0/Android/data/com.byd.dashcast/files/firmware-exports/firmware-export-20261016-123000-123"
    private val boot="1234000.ufshc"
    private fun encoded(value:String)=Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private val response="BOOT_USERDATA|1|8:21|${encoded("/dev/block/platform/soc/$boot")}|16877|${encoded("/dev/block/sda21")}|24960|2069\n"
    private val baseMounts="1 0 0:1 / / rw - rootfs rootfs rw\n"+
        "2 1 8:21 /cells/guest-rw/data /data rw - f2fs /dev/block/bootdevice/by-name/userdata rw\n"+
        "200 2 8:21 /cells/guest-rw/data /data rw - f2fs /dev/block/bootdevice/by-name/userdata rw\n"+
        "4 1 0:50 / /storage/emulated rw - sdcardfs /data/media rw\n"+
        "5 1 8:21 /cells_sd/guest/sdcard /mnt/media_rw/sdcardv rw - f2fs /dev/block/bootdevice/by-name/userdata rw\n"+
        "6 1 8:97 / /storage/A1B2-C3D4 rw - vfat /dev/block/vold/public:8,97 rw\n"
    @After fun clear(){FirmwareDiagnostics.begin()}

    private inner class Endpoint(var capabilities:Int=7,var bootReply:String=response,var malformed:String=""):Binder() {
        val transactions=ArrayList<Int>()
        val operations=ArrayList<Int>()
        var mountText=baseMounts
        var inventoryAllowed=false
        var createdTemporary:String?=null
        val inventoryPaths=ArrayList<String>()
        val chunkPaths=ArrayList<String>()
        val positivePath="/data/positive.bin"
        val positiveStamp="8180:1:1:1:123:2069"
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(ProxyDaemonContract.DESCRIPTOR);transactions.add(code)
            when(code) {
                ProxyDaemonContract.TXN_WHOAMI->{reply!!.writeNoException();reply.writeInt(2000);reply.writeInt(99);reply.writeString("26");reply.writeString("0123456789abcdef0123456789abcdef")}
                ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES->{reply!!.writeNoException();reply.writeInt(1);reply.writeInt(capabilities)}
                ProxyDaemonContract.TXN_STORAGE_METADATA->{
                    val request=StorageMetadata.request(data);operations.add(request.operation)
                    if(request.operation==StorageMetadata.BLOCK) {
                        if(request.argument=="7:1") {reply!!.writeNoException();reply.writeByteArray("BLOCK|7:1|${encoded("/sys/devices/virtual/block/loop1")}|0|||${encoded("/virtual-card-alias/module.img\n")}\n".toByteArray());return true}
                        reply!!.writeException(IllegalStateException("Block metadata ${request.argument} lstat /sys/devices/fixed/slaves: EACCES"));return true
                    }
                    val text=when(request.operation) {
                        StorageMetadata.MOUNTINFO->mountText
                        StorageMetadata.PROPERTY->"synthetic\n"
                        StorageMetadata.PRIMARY_UUID->"null\n"
                        StorageMetadata.VOLUMES->"public:8,97 mounted A1B2-C3D4\nprivate mounted null\nemulated mounted null\n"
                        StorageMetadata.BOOT_DEVICE->"$boot\n"
                        StorageMetadata.BOOT_USERDATA->{assertEquals("8:21",request.argument);bootReply}
                        StorageMetadata.OUTPUT->if(request.argument==destination)"${encoded("/storage/emulated/0")}|50" else {
                            assertTrue(request.argument=="/data/local/tmp" || request.argument.matches(Regex("/data/local/tmp/dashcast-firmware-[a-f0-9-]+")))
                            "${encoded(if(request.argument==createdTemporary)request.argument else "/data/local/tmp")}|2069"
                        }
                        else->throw AssertionError("Unexpected operation ${request.operation}")
                    }
                    reply!!.writeNoException()
                    if(request.operation==StorageMetadata.BOOT_USERDATA) {
                        when(malformed) {
                            "oversize"->{reply.writeInt(Int.MAX_VALUE);return true}
                            "utf8"->{reply.writeByteArray(byteArrayOf(0xc3.toByte()));return true}
                            "trailing"->{reply.writeByteArray(text.toByteArray());reply.writeInt(0);return true}
                        }
                    }
                    reply.writeByteArray(text.toByteArray(Charsets.UTF_8))
                }
                ProxyDaemonContract.TXN_EXEC->{
                    assertTrue(inventoryAllowed);val command=data.readString()!!
                    val text=when {
                        command.startsWith("umask 077; mkdir '")->{
                            createdTemporary=command.substringAfter("umask 077; mkdir '").substringBeforeLast('\'')
                            assertTrue(createdTemporary!!.matches(Regex("/data/local/tmp/dashcast-firmware-[a-f0-9-]+")));""
                        }
                        command.startsWith("for dc_p in '")->{
                            val path=command.substringAfter("for dc_p in '").substringBefore("'; do")
                            assertTrue("excluded root reached source metadata: $path",path in setOf("/","/data","/storage/emulated","/full-userdata"))
                            inventoryPaths.add(path);"S|${encoded(path)}\nBATCH|1\n"
                        }
                        command==FirmwareShell.inspect(positivePath)->"F|${encoded(positivePath)}|${encoded(positivePath)}|1|$positiveStamp\n"
                        command.startsWith("rmdir '")->{assertTrue(command.contains(createdTemporary!!));createdTemporary=null;""}
                        else->throw AssertionError("Unexpected source or write command $command")
                    }
                    reply!!.writeNoException();reply.writeInt(0);reply.writeString(text)
                }
                ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT->{
                    assertTrue(inventoryAllowed);val path=data.readString()!!;assertEquals(positivePath,path);chunkPaths.add(path)
                    val offset=data.readLong();data.readInt();repeat(6){data.readLong()};assertEquals(0,data.dataAvail())
                    reply!!.writeNoException();reply.writeByteArray(if(offset==0L)byteArrayOf(42) else byteArrayOf())
                }
                else->throw AssertionError("No source payload, enumeration or execution in preflight: $code")
            }
            return true
        }
    }
    private fun device(endpoint:IBinder)=FirmwareDevice(RuntimeEnvironment.getApplication(),endpoint={endpoint},connect={true},
        observeOutput={path,exclusions,storage->FirmwareOutput.validate(path,"/storage/emulated/0",61,
            FirmwareMounts.parse(baseMounts.replace("0:50","0:61").byteInputStream(),exclusions,storage))})
    @Test fun `real preflight accepts denied sysfs with corroborated userdata while real card and virtual subtree stay denied`() {
        FirmwareDiagnostics.begin()
        val endpoint=Endpoint();val device=device(endpoint)
        device.prepareDestination(destination)
        assertEquals(1,endpoint.operations.count{it==StorageMetadata.BOOT_USERDATA})
        assertNull(FirmwareDiagnostics.failureSummary())
        val field=FirmwareDevice::class.java.getDeclaredField("shellMounts").apply{isAccessible=true}
        val table=field.get(device) as FirmwareMounts
        assertNull(table.storageDecision("/data/local/tmp"));assertNull(table.storageDecision(destination))
        for(path in listOf("/storage/A1B2-C3D4/file","/mnt/media_rw/sdcardv/file"))assertNotNull(table.storageDecision(path))
        val admitted=table.entries.filter{table.decision(it.point)==null}.map{it.point}
        assertFalse(admitted.contains("/storage/A1B2-C3D4"));assertFalse(admitted.contains("/mnt/media_rw/sdcardv"))
        assertTrue(FirmwareDiagnostics.snapshot().contains("BootUserdata(device=8:21"))
        assertTrue(endpoint.transactions.none{it==ProxyDaemonContract.TXN_EXEC || it==ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT})
    }
    @Test fun `old metadata capability can never invoke boot operation and keeps denied storage unknown`() {
        FirmwareDiagnostics.begin();val endpoint=Endpoint(capabilities=3)
        try{device(endpoint).prepareDestination(destination);fail()}catch(_:IOException){}
        assertEquals(0,endpoint.operations.count{it==StorageMetadata.BOOT_USERDATA})
        assertTrue(FirmwareDiagnostics.snapshot().contains("capability not negotiated"))
    }
    @Test fun `changed Binder cannot inherit capability and explicit renegotiation seven to three clears it`() {
        val first=Endpoint();val device=device(first);device.prepareDestination(destination)
        val second=Endpoint(capabilities=3)
        FirmwareDevice::class.java.getDeclaredField("binder").apply{isAccessible=true;set(device,second)}
        val metadata=FirmwareDevice::class.java.getDeclaredMethod("metadata",Int::class.javaPrimitiveType,String::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}
        fun refused(){try{metadata.invoke(device,StorageMetadata.BOOT_USERDATA,"8:21",false);fail()}catch(e:java.lang.reflect.InvocationTargetException){assertTrue(e.cause is IOException)}}
        refused();assertTrue(second.transactions.isEmpty())
        FirmwareDevice::class.java.getDeclaredMethod("negotiate").apply{isAccessible=true}.invoke(device)
        refused();assertEquals(0,second.operations.count{it==StorageMetadata.BOOT_USERDATA})
        assertEquals(listOf(ProxyDaemonContract.TXN_WHOAMI,ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES),second.transactions)
    }
    @Test fun `malformed framed responses and invalid boot anchors stay unknown before any source listing or read`() {
        val badResponses=listOf(response.trimEnd('\n')+"|extra\n",response.replace("|1|","|2|"),
            response.replace(encoded("/dev/block/platform/soc/$boot"),encoded("/dev/block/platform/soc/wrong.ufshc")),
            response.replace(encoded("/dev/block/platform/soc/$boot"),encoded("/dev/block/platform/usb1/$boot")),
            response.replace(encoded("/dev/block/sda21"),encoded("/dev/block/../sda21")),
            response.replace(encoded("/dev/block/sda21"),encoded("/dev/block/sda21\u0000")),
            response.replace("|24960|2069","|33152|2069"),response.replace("|24960|2069","|24960|2145"))
        for(endpoint in badResponses.map{Endpoint(bootReply=it)}+listOf("oversize","utf8","trailing").map{Endpoint(malformed=it)}) {
            FirmwareDiagnostics.begin();try{device(endpoint).prepareDestination(destination);fail()}catch(_:IOException){}
            assertEquals(1,endpoint.operations.count{it==StorageMetadata.BOOT_USERDATA})
            assertTrue(endpoint.transactions.none{it==ProxyDaemonContract.TXN_EXEC || it==ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT})
            assertEquals(1,endpoint.operations.count{it==StorageMetadata.OUTPUT})
        }
    }
    @Test fun `real discovery metadata and source opening never reach external regions or derived SD backings`() {
        val endpoint=Endpoint().apply {
            inventoryAllowed=true
            mountText+="7 1 8:21 /cells_sd/guest/sdcard /virtual-card-alias rw - f2fs /dev/block/bootdevice/by-name/userdata rw\n"+
                "8 1 0:62 / /derived-emulated rw - sdcardfs /virtual-card-alias rw\n"+
                "9 1 7:1 / /apex/module rw - ext4 /dev/block/loop1 rw\n"+
                "10 1 8:21 / /full-userdata rw - f2fs /dev/block/bootdevice/by-name/userdata rw\n"
        }
        val device=device(endpoint);device.prepareDestination(destination)
        FirmwareLedger(temporary.newFolder()).use {ledger->
            device.discover(ledger,{}, {})
            assertNull(ledger.nextDirectory());assertEquals(0,ledger.sources)
        }
        assertEquals(setOf("/","/data","/storage/emulated","/full-userdata"),endpoint.inventoryPaths.toSet())
        assertTrue(endpoint.chunkPaths.isEmpty())
        val denied=listOf("/storage/A1B2-C3D4/file","/mnt/media_rw/sdcardv/file","/virtual-card-alias/file","/derived-emulated/file","/apex/module/file","/full-userdata/cells_sd/guest/sdcard/file")
        for(path in denied) {
            val calls=endpoint.transactions.size
            try{device.open(FirmwareSource(canonical=path,shellPath=path,shellSize=1,shellStamp=endpoint.positiveStamp),"SHELL");fail(path)}catch(e:IOException){assertTrue(e.message.orEmpty().contains("Exclusion avant métadonnées"))}
            assertEquals(calls,endpoint.transactions.size)
            val observe=FirmwareDevice::class.java.getDeclaredMethod("observedShell",List::class.java).apply{isAccessible=true}
            for((requested,canonical) in listOf(endpoint.positivePath to path,path to endpoint.positivePath)) {
                try{observe.invoke(device,listOf("F",encoded(requested),encoded(canonical),"1",endpoint.positiveStamp));fail(path)}catch(e:java.lang.reflect.InvocationTargetException){assertTrue(e.cause is IOException)}
                assertEquals(calls,endpoint.transactions.size)
            }
        }
        val source=FirmwareSource(canonical=endpoint.positivePath,shellPath=endpoint.positivePath,shellSize=1,shellStamp=endpoint.positiveStamp)
        device.open(source,"SHELL").use {assertEquals(42,it.read());assertEquals(-1,it.read())}
        assertEquals(listOf(endpoint.positivePath,endpoint.positivePath),endpoint.chunkPaths)
        device.close();assertNull(endpoint.createdTemporary)
    }
    @Test fun `excluded backing region mounted over data media refuses destination before any directory write`() {
        val endpoint=Endpoint().apply {
            mountText+="7 1 8:21 /cells_sd/guest/sdcard /data/media rw - f2fs /dev/block/bootdevice/by-name/userdata rw\n"
        }
        val device=device(endpoint)
        try{device.prepareDestination(destination);fail()}catch(_:IOException){}
        assertTrue(endpoint.transactions.none{it==ProxyDaemonContract.TXN_EXEC || it==ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT})
        assertNull(endpoint.createdTemporary)
        val field=FirmwareDevice::class.java.getDeclaredField("shellMounts").apply{isAccessible=true}
        val table=field.get(device) as FirmwareMounts
        assertNull(table.storageDecision("/data/local/tmp"))
        assertTrue(table.storageDecision(destination).orEmpty().startsWith("external storage"))
    }
}
