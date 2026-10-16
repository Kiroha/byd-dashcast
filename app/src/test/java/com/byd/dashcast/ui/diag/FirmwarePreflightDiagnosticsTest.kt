package com.byd.dashcast.ui.diag

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.daemon.StorageMetadata
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FirmwarePreflightDiagnosticsTest {
    @After fun clear(){FirmwareDiagnostics.begin()}

    @Test fun `unknown storage before mkdir retains getter error and physical observations without extra transactions`() {
        val transactions=ArrayList<Int>()
        val operations=ArrayList<Int>()
        val outputPaths=ArrayList<String>()
        fun encoded(value:String)=Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val binder=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(ProxyDaemonContract.DESCRIPTOR);transactions.add(code)
            when(code) {
                ProxyDaemonContract.TXN_WHOAMI->{reply!!.writeNoException();reply.writeInt(2000);reply.writeInt(99);reply.writeString("26");reply.writeString("0123456789abcdef0123456789abcdef")}
                ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES->{reply!!.writeNoException();reply.writeInt(1);reply.writeInt(3)}
                ProxyDaemonContract.TXN_STORAGE_METADATA->{
                    val request=StorageMetadata.request(data);operations.add(request.operation)
                    if(request.operation==StorageMetadata.PRIMARY_UUID) {
                        reply!!.writeException(SecurityException("fixed StorageManager getter denied"));return true
                    }
                    val text=when(request.operation) {
                        StorageMetadata.MOUNTINFO->"1 0 0:1 / / rw - rootfs rootfs rw\n2 1 179:1 / /data rw - ext4 /dev/block/mmcblk0 rw\n3 1 0:50 / /storage/emulated rw - fuse /dev/fuse rw\n"
                        StorageMetadata.PROPERTY->"synthetic\n"
                        StorageMetadata.VOLUMES->"private mounted null\nemulated mounted null\n"
                        StorageMetadata.BOOT_DEVICE->"mmc0\n"
                        StorageMetadata.BLOCK->"BLOCK|179:1|${encoded("/sys/devices/platform/mmc_host/mmc0/block/mmcblk0")}|0|MMC||\n"
                        StorageMetadata.OUTPUT->{outputPaths.add(request.argument);"${encoded("/storage/emulated/0")}|50"}
                        else->throw AssertionError("Unexpected operation ${request.operation}")
                    }
                    reply!!.writeNoException();reply.writeByteArray(text.toByteArray(Charsets.UTF_8))
                }
                else->throw AssertionError("No source payload or execution is authorized in this fixture: $code")
            }
            return true
        }}
        val device=FirmwareDevice(RuntimeEnvironment.getApplication(),endpoint={binder},connect={true},
            observeOutput={_,_,_->throw AssertionError("SHELL refusal precedes APP observation")})
        val root=File("/storage/emulated/0/Android/data/com.byd.dashcast/files/firmware-exports")
        val before=root.list().orEmpty().toSet()
        var finished=0
        FullApkExportSession.start(RuntimeEnvironment.getApplication(),ExportKind.FIRMWARE_LARGE,
            launch={task,_->task.run()},firmwareFactory={_,_->device},root={_,_->root}) {finished++}
        assertEquals(1,finished);assertEquals("failed",FullApkExportSession.snapshot!!.outcome)
        assertNull(FullApkExportSession.snapshot!!.directory)
        assertEquals(before,root.list().orEmpty().toSet())
        val evidence=FirmwareDiagnostics.snapshot()
        assertTrue(evidence.contains("fixed StorageManager getter denied"))
        assertTrue(evidence.contains("internal emulated backing not established"))
        assertTrue(evidence.contains("/sys/devices/platform/mmc_host/mmc0/block/mmcblk0"))
        assertTrue(evidence.contains("WHOAMI uid=2000"));assertFalse(evidence.contains("0123456789abcdef"))
        assertTrue(evidence.contains("[CANONICAL]\n/storage/emulated/0"))
        assertEquals(1,outputPaths.size);assertEquals(outputPaths.single(),FirmwareDiagnostics.failurePath())
        assertTrue(FirmwareDiagnostics.failureSummary()!!.startsWith("Export non démarré"))
        assertTrue(transactions.all{it in setOf(ProxyDaemonContract.TXN_WHOAMI,ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES,ProxyDaemonContract.TXN_STORAGE_METADATA)})
        assertEquals(10,operations.size)
        val calls=transactions.size
        repeat(2){runCatching{device.close()};FirmwareDiagnostics.snapshot()}
        assertEquals(calls,transactions.size);assertEquals(evidence,FirmwareDiagnostics.snapshot())
    }
}
