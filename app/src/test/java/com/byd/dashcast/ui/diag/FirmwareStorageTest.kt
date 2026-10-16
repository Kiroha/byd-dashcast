package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Test

class FirmwareStorageTest {
    private val physical=FirmwareStorage.Block("179:0","/sys/devices/platform/soc/mmc_host/mmc0/mmc0:0001/block/mmcblk0","0","MMC")
    private val userdata=FirmwareStorage.Block("179:1","/sys/devices/platform/soc/mmc_host/mmc0/mmc0:0001/block/mmcblk0/mmcblk0p1","","",listOf("179:0"))
    private val volumes="private mounted null\nemulated mounted null\npublic:179,65 mounted A1B2-C3D4"
    private fun row(device:String,point:String,type:String="ext4",source:String="/dev/block/mmcblk0p1",root:String="/")=
        "2 1 $device $root $point rw - $type $source rw\n"
    private fun mounts(extra:String="",nodes:List<FirmwareStorage.Block> = emptyList(),primary:String?="null",volumeText:String?=volumes):FirmwareMounts {
        val storage=FirmwareStorage((listOf(physical,userdata)+nodes).associateBy{it.device},primary,volumeText)
        return FirmwareMounts.parse((row("0:1","/","rootfs","rootfs")+row("179:1","/data")+extra).byteInputStream(),storage=storage)
    }
    @Test fun `partition parents attest internal data and every bind alias without restricting firmware roots`() {
        val value=mounts(row("179:1","/unusual/internal-mount",root="/vendor-config")+row("179:1","/system/data-alias",root="/oem"))
        for(path in listOf("/data/file","/unusual/internal-mount/file","/system/data-alias/file","/init.rc"))assertNull(path,value.decision(path))
    }
    @Test fun `SD USB and adopted ext4 plus aliases under system or data are excluded before inventory`() {
        for(type in listOf("vfat","exfat","ext4")) {
            val sd=FirmwareStorage.Block("179:65","/sys/devices/platform/soc/mmc_host/mmc1/mmc1:0001/block/mmcblk1/mmcblk1p1","","",listOf("179:64"))
            val leaf=FirmwareStorage.Block("179:64","/sys/devices/platform/soc/mmc_host/mmc1/mmc1:0001/block/mmcblk1","1","SD")
            val value=mounts(row("179:65","/storage/A1B2-C3D4",type,"/dev/block/vold/public:179,65")+
                row("179:65","/data/not-internal",type,"/dev/block/mmcblk1p1")+row("179:65","/system/alias",type,"/dev/block/mmcblk1p1",root="/surveillance"),listOf(sd,leaf))
            for(path in listOf("/storage/A1B2-C3D4/file","/data/not-internal/file","/system/alias/file"))assertTrue(value.decision(path).orEmpty().startsWith("external storage"))
        }
        val usb=FirmwareStorage.Block("8:1","/sys/devices/platform/usb1/1-1/host0/target0:0:0/block/sda","0","")
        val adopted=FirmwareStorage.Block("253:2","/sys/devices/virtual/block/dm-2","0","",listOf("8:1"))
        val value=mounts(row("253:2","/data/surprise",source="/dev/block/dm-2"),listOf(usb,adopted))
        assertTrue(value.decision("/data/surprise/file").orEmpty().startsWith("external storage"))
    }
    @Test fun `adopted source and expand backing reject aliases even without removable metadata`() {
        val value=mounts(row("253:3","/mnt/expand/1234-5678",source="/dev/block/vold/private:179,66")+
            row("253:3","/arbitrary-adopted-bind",source="/dev/block/dm-3")+
            row("0:70","/data/view","sdcardfs","/mnt/expand/1234-5678/media"))
        for(path in listOf("/mnt/expand/1234-5678/file","/arbitrary-adopted-bind/file","/data/view/file"))assertTrue(value.decision(path).orEmpty().startsWith("external storage"))
    }
    @Test fun `internal emulated FUSE requires successful exact metadata and attested internal userdata`() {
        val fuse=row("0:50","/storage/emulated","fuse","/dev/fuse")+row("0:50","/internal-fuse-bind","fuse","/dev/fuse",root="/0")
        val value=mounts(fuse)
        assertNull(value.decision("/storage/emulated/0/file"));assertNull(value.decision("/internal-fuse-bind/file"))
        for(primary in listOf(null,"","error","primary_physical","1234-5678"))assertNotNull(mounts(fuse,primary=primary).decision("/storage/emulated/0/file"))
        for(rows in listOf(null,"","private mounted null","emulated mounted null","private mounted null\nemulated mounted null extra","error"))
            assertNotNull(mounts(fuse,volumeText=rows).decision("/storage/emulated/0/file"))
        val unavailable=FirmwareMounts.parse((row("0:1","/","rootfs","rootfs")+row("179:1","/data")+fuse).byteInputStream(),storage=FirmwareStorage(primary="null",volumes=volumes))
        assertNotNull(unavailable.decision("/storage/emulated/0/file"))
    }
    @Test fun `sdcardfs data media requires internal backing and external media is propagated`() {
        val value=mounts(row("0:60","/storage/emulated","sdcardfs","/data/media"),primary=null,volumeText=null)
        assertNull(value.decision("/storage/emulated/0/file"))
        val usb=FirmwareStorage.Block("8:1","/sys/devices/platform/usb2/2-1/block/sda","0","")
        val external=mounts(row("8:1","/data/media",source="/dev/block/sda")+row("0:60","/storage/emulated","sdcardfs","/data/media"),listOf(usb))
        assertNotNull(external.decision("/storage/emulated/0/file"))
    }
    @Test fun `sdcardfs esdfs and loop cannot establish backing through pseudo or device interfaces`() {
        for(path in listOf("/dev/fuse","/proc/self/fd/3","/sys/devices/storage")) {
            val pseudo=row("0:2","/"+path.split('/')[1],"tmpfs","tmpfs")
            for(type in listOf("sdcardfs","esdfs")) {
                val value=mounts(row("0:60","/storage/emulated",type,path)+pseudo)
                assertTrue(value.decision("/storage/emulated/0/file").orEmpty().startsWith("unknown storage"))
            }
            val loop=FirmwareStorage.Block("7:0","/sys/devices/virtual/block/loop0","0","",backing=path)
            assertNotNull(mounts(row("7:0","/apex/module",source="/dev/block/loop0"),listOf(loop)).decision("/apex/module/file"))
        }
    }
    @Test fun `pseudo mount device aliases cannot attest emulated or loop backing under an ordinary path`() {
        for(type in listOf("tmpfs","proc","sysfs")) {
            val interfaceMount=row("0:70","/dev/special",type,"interface")
            val alias=row("0:70","/alias",type,"interface")
            val emulated=row("0:60","/storage/emulated","sdcardfs","/alias/media")
            assertTrue(mounts(interfaceMount+alias+emulated).decision("/storage/emulated/0/file").orEmpty().startsWith("unknown storage"))
            val loop=FirmwareStorage.Block("7:0","/sys/devices/virtual/block/loop0","0","",backing="/alias/image")
            assertNotNull(mounts(interfaceMount+alias+row("7:0","/apex/module",source="/dev/block/loop0"),listOf(loop)).decision("/apex/module/file"))
        }
    }
    @Test fun `loop and dm dependency graphs retain internal firmware and reject external backing cycles or missing leaves`() {
        val loop=FirmwareStorage.Block("7:0","/sys/devices/virtual/block/loop0","0","",backing="/data/apex/module.apex")
        val dm=FirmwareStorage.Block("253:0","/sys/devices/virtual/block/dm-0","0","",listOf("179:1"))
        val internal=mounts(row("7:0","/apex/module",source="/dev/block/loop0")+row("253:0","/vendor",source="/dev/block/dm-0"),listOf(loop,dm))
        assertNull(internal.decision("/apex/module/lib/file"));assertNull(internal.decision("/vendor/lib/file"))
        val outside=mounts(row("7:0","/apex/module",source="/dev/block/loop0"),listOf(loop.copy(backing="/storage/A1B2-C3D4/file")))
        assertNotNull(outside.decision("/apex/module/file"))
        val cyclic=mounts(row("253:0","/vendor",source="/dev/block/dm-0"),listOf(dm.copy(dependencies=listOf("253:0"))))
        assertTrue(cyclic.decision("/vendor/file").orEmpty().startsWith("unknown storage"))
    }
    @Test fun `non-removable generic block and familiar mountpoints do not imply internal provenance`() {
        val generic=FirmwareStorage.Block("8:1","/sys/devices/platform/unknown/block/sda","0","")
        val value=mounts(row("8:1","/system",source="/dev/block/sda")+row("8:1","/data/another",source="/dev/block/sda"),listOf(generic))
        assertNotNull(value.decision("/system/bin/file"));assertNotNull(value.decision("/data/another/file"))
    }
    @Test fun `UFS and declared boot controller attest only non-USB non-removable devices`() {
        val ufs=FirmwareStorage.Block("8:1","/sys/devices/platform/soc/1d84000.ufshc/host0/target0/block/sda","0","")
        assertNull(mounts(row("8:1","/firmware",source="/dev/block/sda"),listOf(ufs)).decision("/firmware/file"))
        val unknown=FirmwareStorage.Block("8:2","/sys/devices/platform/soc/boot-controller/host0/block/sdb","0","")
        val storage=FirmwareStorage(mapOf("8:2" to unknown),bootDevice="soc/boot-controller")
        val table=FirmwareMounts.parse((row("0:1","/","rootfs","rootfs")+row("8:2","/firmware",source="/dev/block/sdb")).byteInputStream(),storage=storage)
        assertNull(table.decision("/firmware/file"))
    }
}
