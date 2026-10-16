package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Test

class FirmwareBootStorageTest {
    private val boot="1234000.ufshc"
    private val volumes="public:8,97 mounted A1B2-C3D4\nprivate mounted null\nemulated mounted null"
    private val proof=FirmwareStorage.BootUserdata("8:21","/dev/block/platform/soc/$boot",0x41ed,"/dev/block/sda21",0x6180,(8L shl 8) or 21L)
    private fun row(id:Int,device:String,root:String,point:String,type:String="f2fs",source:String="/dev/block/bootdevice/by-name/userdata")=
        "$id 1 $device $root $point rw - $type $source rw\n"
    private val data=row(2,"8:21","/cells/guest-rw/data","/data")
    private val emulated=row(4,"0:50","/","/storage/emulated","sdcardfs","/data/media")
    private fun table(extra:String="",bootProof:FirmwareStorage.BootUserdata?=proof,primary:String?="null",rows:String?=volumes,
        bootName:String=boot,dataRows:String=data,nodes:List<FirmwareStorage.Block> = emptyList()):FirmwareMounts {
        val storage=FirmwareStorage(nodes.associateBy{it.device},primary,rows,bootName,bootUserdata=bootProof)
        return FirmwareMounts.parse((row(1,"0:1","/","/","rootfs","rootfs")+dataRows+emulated+extra).byteInputStream(),storage=storage)
    }
    @Test fun `equivalent cell mounts preserve raw origins and allow only attested internal data and emulated outputs`() {
        val value=table(row(200,"8:21","/cells/guest-rw/data","/data"))
        for(path in listOf("/data/local/tmp","/data/media/0/Pictures/a","/storage/emulated/0/Pictures/a"))assertNull(path,value.storageDecision(path))
        assertEquals(2,value.entries.count{it.point=="/data"})
        assertTrue(value.json().contains("200 1 8:21"));assertTrue(value.json().contains("2 1 8:21"))
        assertEquals(table().fingerprint(),value.fingerprint())
    }
    @Test fun `conflicting mount roots devices types or sources stay unknown including backing consumers`() {
        for(conflict in listOf(row(200,"8:21","/other-data-root","/data"),row(200,"8:22","/cells/guest-rw/data","/data"),
            row(200,"8:21","/cells/guest-rw/data","/data","ext4"),row(200,"8:21","/cells/guest-rw/data","/data",source="/dev/block/other"))) {
            val value=table(conflict)
            assertNotNull(value.storageDecision("/data/local/tmp"));assertNotNull(value.storageDecision("/storage/emulated/0/file"))
        }
        val mnt=table(row(30,"8:21","/cells/guest-rw/mnt","/mnt")+row(31,"0:60","/","/mnt","tmpfs","tmpfs"))
        assertNotNull(mnt.decision("/mnt/file"));assertNull(mnt.storageDecision("/data/local/tmp"))
    }
    @Test fun `logical external subtree and its aliases do not poison other userdata roots`() {
        val value=table(row(10,"8:21","/cells_sd/guest/sdcard","/mnt/media_rw/sdcardv")+
            row(11,"8:21","/cells_sd/guest/sdcard","/virtual-card-alias")+row(12,"8:21","/","/full-userdata")+
            row(13,"8:97","/","/storage/A1B2-C3D4","vfat","/dev/block/vold/public:8,97")+
            row(14,"8:97","/surveillance","/system/card-alias","vfat","/dev/block/sdg1"))
        for(path in listOf("/data/local/tmp","/storage/emulated/0/file","/full-userdata/cells/guest-rw/data/file"))assertNull(path,value.storageDecision(path))
        for(path in listOf("/mnt/media_rw/sdcardv/file","/virtual-card-alias/file","/full-userdata/cells_sd/guest/sdcard/file","/storage/A1B2-C3D4/file","/system/card-alias/file")) {
            assertTrue(path,value.storageDecision(path).orEmpty().startsWith("external storage"))
            assertFalse(value.accepted("/data/link",path))
        }
    }
    @Test fun `public and adopted device IDs override boot proof and never contaminate sibling partitions`() {
        for(prefix in listOf("public","private")) {
            val value=table(rows="private mounted null\nemulated mounted null\n$prefix:8,21 mounted A1B2-C3D4")
            assertTrue(value.storageDecision("/data/local/tmp").orEmpty().startsWith("external storage"))
            assertNotNull(value.storageDecision("/storage/emulated/0/file"))
        }
        assertNull(table().storageDecision("/data/local/tmp"))
    }
    @Test fun `external regions propagate through bind then sdcardfs and loop and override data media backing`() {
        val loop=FirmwareStorage.Block("7:1","/sys/devices/virtual/block/loop1","0","",backing="/virtual-card-alias/module.img")
        val external=row(10,"8:21","/cells_sd/guest/sdcard","/mnt/media_rw/sdcardv")+
            row(11,"8:21","/cells_sd/guest/sdcard","/virtual-card-alias")+
            row(12,"0:62","/","/derived-emulated","sdcardfs","/virtual-card-alias")+
            row(13,"7:1","/","/apex/module","ext4","/dev/block/loop1")
        val value=table(external,nodes=listOf(loop))
        assertNull(value.storageDecision("/data/local/tmp"))
        for(path in listOf("/derived-emulated/file","/apex/module/file"))assertTrue(value.storageDecision(path).orEmpty().startsWith("external storage"))
        val redirected=table(external+row(14,"8:21","/cells_sd/guest/sdcard","/data/media"))
        assertTrue(redirected.storageDecision("/storage/emulated/0/file").orEmpty().startsWith("external storage"))
    }
    @Test fun `every missing or contradictory StorageManager and boot identity condition refuses fallback`() {
        for(primary in listOf(null,"","primary_physical","A1B2-C3D4"))assertNotNull(table(primary=primary).storageDecision("/data/local/tmp"))
        for(rows in listOf(null,"","private mounted null","emulated mounted null","private unmounted null\nemulated mounted null","private mounted null\nemulated unmounted null","private mounted null\nemulated mounted null\ninvalid"))
            assertNotNull(table(rows=rows).storageDecision("/data/local/tmp"))
        for(candidate in listOf(proof.copy(device="8:22"),proof.copy(controller="/dev/block/platform/soc/x$boot"),
            proof.copy(controller="/dev/block/platform/usb1/$boot"),proof.copy(controller="/other/$boot"),
            proof.copy(controllerMode=0x81ed),proof.copy(target="/dev/block/dm-6"),proof.copy(mode=0x8180),proof.copy(rdev=(8L shl 8) or 97L)))
            assertNotNull(candidate.toString(),table(bootProof=candidate).storageDecision("/data/local/tmp"))
        assertNotNull(table(bootProof=null).storageDecision("/data/local/tmp"))
        assertNotNull(table(bootName="other.ufshc").storageDecision("/data/local/tmp"))
        assertNotNull(table(dataRows=data.replace("/dev/block/bootdevice/by-name/userdata","/dev/block/sda21")).storageDecision("/data/local/tmp"))
    }
    @Test fun `known USB SD and removable block evidence overrides otherwise successful boot metadata`() {
        for(node in listOf(FirmwareStorage.Block("8:21","/sys/devices/platform/usb1/1-1/block/sda/sda21","0",""),
            FirmwareStorage.Block("8:21","/sys/devices/platform/block/sda/sda21","1",""),
            FirmwareStorage.Block("8:21","/sys/devices/platform/block/sda/sda21","0","SD")))
            assertNotNull(table(nodes=listOf(node)).storageDecision("/data/local/tmp"))
    }
    @Test fun `userdata proof never attests unknown system vendor device mapper or loop devices`() {
        val value=table(row(20,"253:6","/system","/system","ext4","/dev/block/dm-6")+
            row(21,"253:7","/","/vendor","ext4","/dev/block/dm-7")+row(22,"7:1","/","/apex/module","ext4","/dev/block/loop1"))
        assertNull(value.storageDecision("/data/local/tmp"))
        for(path in listOf("/system/framework/a.jar","/vendor/lib/a.so","/apex/module/a"))assertTrue(value.storageDecision(path).orEmpty().startsWith("unknown storage"))
    }
}
