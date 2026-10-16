package com.byd.dashcast.ui.diag

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.daemon.StorageMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FirmwareDestinationFinalizationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val destination="/storage/emulated/0/Android/data/com.byd.dashcast/files/firmware-exports/firmware-export-20261016-123000-123"
    private fun encoded(value:String)=Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private val data=FirmwareMount("179:1","/","/data","ext4",source="/dev/block/mmcblk0")
    private val view=FirmwareMount("0:50","/","/storage/emulated","sdcardfs",source="/data/media")
    private fun mounts(dataMount:FirmwareMount=data,storageMount:FirmwareMount=view,version:Int=1):String =
        "$version 0 0:1 / / rw - rootfs rootfs rw\n"+
            listOf(dataMount,storageMount).mapIndexed {index,m->"${version+index+1} $version ${m.device} ${m.root} ${m.point} rw - ${m.type} ${m.source} rw\n"}.joinToString("")

    private inner class Fixture {
        val cancelled=AtomicBoolean(false)
        val operations=ArrayList<Pair<Int,String>>()
        val transactions=ArrayList<Int>()
        var freshMounts=mounts()
        var appMounts=mounts(storageMount=view.copy(device="0:61"))
        var appDevice=61L
        var observations=0
        val outputOverrides=HashMap<String,Pair<String,Long>>()
        var allowRmdir=false
        val cleanupCommands=ArrayList<String>()
        private val binder=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(ProxyDaemonContract.DESCRIPTOR);transactions.add(code)
            reply!!.writeNoException()
            when(code) {
                ProxyDaemonContract.TXN_WHOAMI->{reply.writeInt(2000);reply.writeInt(99);reply.writeString("26");reply.writeString("0123456789abcdef0123456789abcdef")}
                ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES->{reply.writeInt(1);reply.writeInt(3)}
                ProxyDaemonContract.TXN_STORAGE_METADATA->{
                    val request=StorageMetadata.request(data);operations.add(request.operation to request.argument)
                    val text=when(request.operation) {
                        StorageMetadata.MOUNTINFO->freshMounts
                        StorageMetadata.PROPERTY->"synthetic\n"
                        StorageMetadata.PRIMARY_UUID->"null\n"
                        StorageMetadata.VOLUMES->"private mounted null\nemulated mounted null\n"
                        StorageMetadata.BOOT_DEVICE->"mmc0\n"
                        StorageMetadata.BLOCK->{assertEquals("179:1",request.argument);"BLOCK|179:1|${encoded("/sys/devices/platform/mmc_host/mmc0/block/mmcblk0")}|0|MMC||\n"}
                        StorageMetadata.OUTPUT->outputOverrides[request.argument]?.let {"${encoded(it.first)}|${it.second}"}
                            ?: if(request.argument==destination)"${encoded("/storage/emulated/0")}|50"
                            else {assertTrue(request.argument=="/data/local/tmp" || request.argument.startsWith("/data/local/tmp/dashcast-firmware-"));"${encoded(request.argument)}|${179L*256+1}"}
                        else->throw AssertionError("No source inventory, chunk or command after cancellation")
                    }
                    reply.writeByteArray(text.toByteArray(Charsets.UTF_8))
                }
                ProxyDaemonContract.TXN_EXEC->{
                    assertTrue(allowRmdir);val command=data.readString()!!;cleanupCommands.add(command)
                    assertTrue(command.startsWith("rmdir '/data/local/tmp/dashcast-firmware-"))
                    assertFalse(command.contains("rm -"));reply.writeInt(1);reply.writeString("Directory not empty")
                }
                else->throw AssertionError("Unexpected payload/execution transaction $code")
            }
            return true
        }}
        val device=FirmwareDevice(RuntimeEnvironment.getApplication(),cancelled::get,endpoint={binder},
            observeOutput={path,exclusions,proof->
                observations++
                FirmwareOutput.validate(path,"/storage/emulated/0",appDevice,FirmwareMounts.parse(appMounts.byteInputStream(),exclusions,proof))
            },connect={true})
        init {
            // Seed pins through the actual connection, context, metadata and preparation path.
            device.prepareDestination(destination)
            cancelled.set(true);operations.clear();transactions.clear();observations=0
        }
        fun assertMetadataOnly() {
            assertTrue(transactions.all{it==ProxyDaemonContract.TXN_STORAGE_METADATA})
            assertTrue(operations.all{it.first in setOf(StorageMetadata.MOUNTINFO,StorageMetadata.OUTPUT)})
        }
    }

    @Test fun `cancelled real device accepts constant destination temp and APP tuples for finalization`() {
        val fixture=Fixture()
        // Volatile mount IDs differ but all covering tuples stay the same in both namespaces.
        fixture.freshMounts=mounts(version=100)
        fixture.appMounts=mounts(storageMount=view.copy(device="0:61"),version=200)
        fixture.device.validateDestination()
        assertEquals(listOf(StorageMetadata.MOUNTINFO,StorageMetadata.OUTPUT,StorageMetadata.OUTPUT),fixture.operations.map{it.first})
        assertEquals(listOf(destination,"/data/local/tmp"),fixture.operations.filter{it.first==StorageMetadata.OUTPUT}.map{it.second})
        assertEquals(1,fixture.observations);fixture.assertMetadataOnly()
    }

    @Test fun `cancelled real device rejects every changed destination or temp tuple before output access`() {
        val destinationVariants=listOf(view.copy(device="0:51"),view.copy(root="/foreign"),view.copy(point="/storage/emulated/0"),view.copy(type="fuse"),view.copy(source="/other"))
        val tempVariants=listOf(data.copy(device="179:2"),data.copy(root="/foreign"),data.copy(point="/data/local/tmp"),data.copy(type="f2fs"),data.copy(source="/dev/block/other"))
        for(fresh in destinationVariants.map{mounts(storageMount=it)}+tempVariants.map{mounts(dataMount=it)}) {
            val fixture=Fixture();fixture.freshMounts=fresh
            val directory=temporary.newFolder();val zip=File(directory,"finished.zip").apply{writeText("retained ZIP")};val before=zip.readBytes()
            var writes=0
            try{fixture.device.validateDestination();writes++;File(directory,"new-manifest.json").writeText("must not happen");fail()}catch(e:IOException){assertTrue(e.message.orEmpty().contains("Vue destination changée"))}
            assertEquals(0,writes);assertArrayEquals(before,zip.readBytes());assertEquals(listOf("finished.zip"),directory.list()!!.toList())
            assertEquals(listOf(StorageMetadata.MOUNTINFO),fixture.operations.map{it.first});assertEquals(0,fixture.observations)
            fixture.assertMetadataOnly()
        }
    }

    @Test fun `cancelled real device rejects a newly internal APP view instead of borrowing SHELL pin`() {
        val fixture=Fixture()
        // This remains independently classifiable as internal; only the old APP pin rejects it.
        fixture.appMounts=mounts(storageMount=view.copy(device="0:62"));fixture.appDevice=62
        val directory=temporary.newFolder();val zip=File(directory,"finished.zip").apply{writeText("retained ZIP")};val before=zip.readBytes()
        var writes=0
        try{fixture.device.validateDestination();writes++;File(directory,"new-manifest.json").writeText("must not happen");fail()}catch(e:IOException){assertTrue(e.message.orEmpty().contains("Vue sortie APP changée"))}
        assertEquals(0,writes);assertArrayEquals(before,zip.readBytes());assertEquals(listOf("finished.zip"),directory.list()!!.toList())
        assertEquals(listOf(StorageMetadata.MOUNTINFO,StorageMetadata.OUTPUT),fixture.operations.map{it.first})
        assertEquals(1,fixture.observations);fixture.assertMetadataOnly()
    }

    @Test fun `real device refuses externally mounted or redirected temp children and never executes rm even on repeated close`() {
        for(snapshot in listOf(false,true))for(redirected in listOf(false,true)) {
            val fixture=Fixture()
            val field=FirmwareDevice::class.java.getDeclaredField("temporary").apply{isAccessible=true}
            val temporary=field.get(fixture.device) as String
            val path=if(snapshot)"$temporary/names-1" else temporary
            FirmwareDevice::class.java.getDeclaredField("temporaryReady").apply{isAccessible=true;set(fixture.device,true)}
            if(redirected) {
                fixture.freshMounts+="4 1 179:65 / /storage/A1B2-C3D4 rw - exfat /dev/block/vold/public:179,65 rw\n"
                fixture.outputOverrides[path]="/storage/A1B2-C3D4/foreign" to (179L*256+65)
            } else fixture.freshMounts+="4 1 179:65 / $path rw - exfat /dev/block/vold/public:179,65 rw\n"
            if(snapshot) {
                val cleanup=FirmwareDevice::class.java.getDeclaredMethod("removeTemporary",String::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}
                try{cleanup.invoke(fixture.device,path,false);fail()}catch(e:java.lang.reflect.InvocationTargetException){assertTrue(e.cause is FirmwareOutputRefused)}
            } else try{fixture.device.close();fail()}catch(_:FirmwareOutputRefused){}
            assertTrue(fixture.device.outputGuard.refused)
            val calls=fixture.transactions.size
            fixture.freshMounts=mounts();fixture.outputOverrides.clear()
            repeat(2){try{fixture.device.close();fail()}catch(_:FirmwareOutputRefused){}}
            assertEquals(calls,fixture.transactions.size)
            assertTrue(fixture.transactions.all{it==ProxyDaemonContract.TXN_STORAGE_METADATA})
            fixture.assertMetadataOnly()
        }
    }

    @Test fun `real device does not execute rm when the exact temporary target is absent`() {
        val fixture=Fixture()
        val path=FirmwareDevice::class.java.getDeclaredField("temporary").apply{isAccessible=true}.get(fixture.device) as String
        FirmwareDevice::class.java.getDeclaredField("temporaryReady").apply{isAccessible=true;set(fixture.device,true)}
        fixture.outputOverrides[path]="/data/local/tmp" to (179L*256+1)
        fixture.device.close();fixture.device.close()
        assertFalse(fixture.device.outputGuard.refused);fixture.assertMetadataOnly()
    }

    @Test fun `real temp cleanup only attempts rmdir and cannot descend into a newly mounted external child`() {
        val fixture=Fixture();fixture.allowRmdir=true
        val path=FirmwareDevice::class.java.getDeclaredField("temporary").apply{isAccessible=true}.get(fixture.device) as String
        FirmwareDevice::class.java.getDeclaredField("temporaryReady").apply{isAccessible=true;set(fixture.device,true)}
        fixture.freshMounts+="4 1 179:65 / $path/foreign-child rw - exfat /dev/block/vold/public:179,65 rw\n"
        try{fixture.device.close();fail()}catch(e:IOException){assertTrue(e.message.orEmpty().contains("conservé"))}
        assertEquals(1,fixture.cleanupCommands.size);assertFalse(fixture.device.outputGuard.refused)
    }
}
