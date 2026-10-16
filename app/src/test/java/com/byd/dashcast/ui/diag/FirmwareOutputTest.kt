package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FirmwareOutputTest {
    private val destination="/storage/emulated/0/Android/data/com.byd.dashcast/files/firmware-exports/firmware-export-20261016-123000-123"
    private val proof=FirmwareStorage(mapOf("179:1" to FirmwareStorage.Block("179:1","/sys/devices/platform/mmc_host/mmc0/block/mmcblk0","0","MMC")),"null","private mounted null\nemulated mounted null")
    private fun table(view:String,extra:String="")=FirmwareMounts.parse((
        "1 0 0:1 / / rw - rootfs rootfs rw\n"+
        "2 1 179:1 / /data rw - ext4 /dev/block/mmcblk0 rw\n"+view+extra).byteInputStream(),storage=proof)
    @Test fun `APP and SHELL namespaces can attest internal outputs with different sdcardfs devices`() {
        val shell=table("3 1 0:50 / /storage/emulated rw - sdcardfs /data/media rw\n")
        val app=table("3 1 0:61 / /storage/emulated rw - sdcardfs /data/media rw\n")
        assertNotEquals(shell.mount(destination)!!.device,app.mount(destination)!!.device)
        assertNotNull(FirmwareOutput.validate(destination,"/storage/emulated/0",50,shell))
        assertNotNull(FirmwareOutput.validate(destination,"/storage/emulated/0",61,app))
        // Creating the authorized directory changes its nearest existing ancestor,
        // while its independently pinned storage view stays the same.
        assertEquals(FirmwareOutput.validate(destination,"/storage/emulated/0",61,app),FirmwareOutput.validate(destination,destination,61,app))
    }
    @Test fun `external child mount under an internal output root is refused without any write`() {
        val base="3 1 0:50 / /storage/emulated rw - sdcardfs /data/media rw\n"
        val external=table(base,"4 3 179:65 / $destination rw - ext4 /dev/block/vold/public:179,65 rw\n")
        try{FirmwareOutput.validate(destination,destination,179L*256+65,external);fail()}catch(_:IOException){}
    }
    @Test fun `external canonical alias opaque APP view or wrong observed device cannot borrow SHELL provenance`() {
        val valid=table("3 1 0:50 / /storage/emulated rw - sdcardfs /data/media rw\n")
        try{FirmwareOutput.validate(destination,"/storage/A1B2-C3D4/output",50,valid);fail()}catch(_:IOException){}
        try{FirmwareOutput.validate(destination,"/storage/emulated/0",51,valid);fail()}catch(_:IOException){}
        val opaque=table("3 1 0:61 /foreign /storage/emulated rw - fuse /dev/fuse rw\n")
        try{FirmwareOutput.validate(destination,"/storage/emulated/0",61,opaque);fail()}catch(_:IOException){}
    }
    @Test fun `temp directory bind from an external volume is refused before snapshot creation`() {
        val external=table("3 2 179:65 / /data/local/tmp rw - ext4 /dev/block/vold/public:179,65 rw\n")
        assertTrue(external.storageDecision("/data/local/tmp").orEmpty().startsWith("external storage"))
    }
}
