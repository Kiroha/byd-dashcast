package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FirmwareMountsTest {
    private fun row(id:Int,dev:String,root:String,point:String,type:String="ext4",extra:String="",source:String="source")=
        "$id 1 $dev $root $point rw $extra- $type $source rw\n"
    private fun table(extra:String="",excluded:List<String> = emptyList())=FirmwareMounts.parse(
        (row(1,"0:1","/","/","rootfs")+row(2,"8:2","/","/data")+extra).byteInputStream(),excluded)

    @Test fun `mount parser preserves escaped paths optional metadata and raw provenance`() {
        val value=table(row(3,"8:3","/a\\040b\\011c\\012d\\134e","/storage/a\\040b","vfat","shared:17 future:thing "))
        val mount=value.entries.last()
        assertEquals("/a b\tc\nd\\e",mount.root);assertEquals("/storage/a b",mount.point)
        assertTrue(mount.raw.contains("future:thing"));assertTrue(value.json().contains("rawMountinfo"))
        assertNull(value.decision("/storage/a b/photo.jpeg"))
    }
    @Test fun `pseudo aliases unknown types and nested overrides are excluded by longest mount`() {
        val value=table(row(3,"0:5","/","/proc","proc")+row(4,"0:5","/sys","/data/alias","proc")+
            row(5,"9:1","/","/data/unknown","newfs")+row(6,"0:6","/","/dev","tmpfs")+
            row(7,"0:6","/ordinary","/data/devbind","tmpfs"))
        for(path in listOf("/proc/x","/data/alias/a","/data/unknown/x","/data/devbind/file"))assertNotNull(path,value.decision(path))
        assertNull(value.decision("/data/proc-name/file"));assertFalse(value.accepted("/data/link","/proc/uptime"))
        assertFalse(value.sameMount("/data","/data/unknown"));assertTrue(value.sameMount("/data","/data/ordinary"))
    }
    @Test fun `only recognised Android storage FUSE provenance admits its bind aliases`() {
        val value=table(row(3,"0:50","/","/storage/emulated","fuse",source="/dev/fuse")+
            row(4,"0:50","/0","/bind-storage","fuse",source="/dev/fuse")+
            row(5,"0:51","/","/custom-service","fuse",source="custom"))
        assertNull(value.decision("/storage/emulated/0/file"));assertNull(value.decision("/bind-storage/file"))
        assertNotNull(value.decision("/custom-service/file"))
    }
    @Test fun `root tmpfs ordinary memory files remain eligible while device namespace is excluded`() {
        val value=FirmwareMounts.parse((row(1,"0:1","/","/","tmpfs")+row(2,"0:2","/","/dev","tmpfs")).byteInputStream())
        assertNull(value.decision("/init.rc"));assertNull(value.decision("/"));assertNotNull(value.decision("/dev/zero"))
    }
    @Test fun `self outputs excluded across FUSE data media and bind subtree aliases before metadata`() {
        val value=table(row(3,"0:50","/","/storage/emulated","fuse",source="/dev/fuse")+
            row(4,"8:2","/media/0/Android/data/com.byd.dashcast/files","/arbitrary-bind")+
            row(5,"8:2","/user/0/com.byd.dashcast/cache","/cache-bind"),listOf("/data/user/0/com.byd.dashcast"))
        for(path in listOf("/storage/emulated/0/Android/data/com.byd.dashcast/files/a.zip",
            "/data/media/0/Android/data/com.byd.dashcast/files/a.zip","/arbitrary-bind/a.zip","/cache-bind/journal"))assertNotNull(path,value.decision(path))
        assertNull(value.decision("/storage/emulated/0/Pictures/file.jpg"))
        assertNull(value.decision("/data/media/0/Pictures/file.jpg"))
    }
    @Test fun `daemon snapshots and bootstrap aliases are excluded through bind roots`() {
        val value=table(row(3,"8:2","/local/tmp","/scratch")+
            row(4,"8:2","/local/tmp/dashcast-firmware-123","/hidden-snapshot"))
        for(path in listOf("/scratch/dashcast-firmware-123/names-1","/scratch/dashcast_proxy.pid","/hidden-snapshot/names-1"))assertNotNull(path,value.decision(path))
        assertNull(value.decision("/scratch/other-file"))
        assertTrue(value.json().contains("collector/interface backing subtree"))
    }
    @Test fun `bind mounted ordinary file remains its own admitted root`() {
        val value=table(row(3,"8:2","/hosts","/system/etc/hosts"))
        assertNull(value.decision("/system/etc/hosts"));assertFalse(value.sameMount("/system/etc","/system/etc/hosts"))
        assertTrue(value.entries.any{it.point=="/system/etc/hosts"})
    }
    @Test fun `structural fingerprints omit volatile IDs but retain namespace topology and exclusions`() {
        val a=table(row(3,"8:3","/","/storage/user","vfat","shared:10 "))
        val b=table(row(999,"8:3","/","/storage/user","vfat","shared:42 "))
        assertEquals(a.fingerprint(),b.fingerprint())
        assertNotEquals(a.fingerprint(),table(row(3,"8:4","/","/storage/user","vfat")).fingerprint())
        assertNotEquals(a.fingerprint(),table(row(3,"8:3","/","/storage/user","vfat"),listOf("/data/own")).fingerprint())
        assertNotEquals(a.topology("/data"),table(row(3,"8:3","/","/data/nested","vfat")).topology("/data"))
    }
    @Test fun `directory topology keys preserve embedded separators and terminal newlines`() {
        val a=table(row(3,"8:3","/a|b\\012c","/data/a|b\\012c"))
        val b=table(row(3,"8:3","/a|b","/data/a|b"))
        assertNotEquals(a.topology("/data"),b.topology("/data"))
    }
    @Test fun `malformed incomplete oversized and ambiguous mount evidence fails closed`() {
        for(text in listOf("","1 1 0:1 / / rw - rootfs source rw",row(1,"0:1","/","/bad\\999"),
            row(1,"0:1","/","/../bad"),"x".repeat(FirmwarePolicy.MAX_LINE+1))) {
            try {FirmwareMounts.parse(text.byteInputStream());fail(text.take(80))}catch(_:IOException){}
        }
        val duplicate=table(row(3,"8:3","/","/data"))
        assertNotNull(duplicate.decision("/data/file"))
        val many=buildString {repeat(FirmwareMounts.MAX_MOUNTS+1){append(row(it+1,"0:1","/",if(it==0)"/" else "/m$it"))}}
        try{FirmwareMounts.parse(many.byteInputStream());fail()}catch(_:IOException){}
    }
    @Test fun `4096 mounts use prefix lookup and bounded directory topology cache`() {
        val text=buildString {append(row(1,"0:1","/","/","rootfs"));repeat(FirmwareMounts.MAX_MOUNTS-1){append(row(it+2,"8:${it+1}","/","/vol${it.toString().padStart(4,'0')}"))}}
        val value=FirmwareMounts.parse(text.byteInputStream())
        assertEquals(FirmwareMounts.MAX_MOUNTS,value.entries.size)
        for(i in 0 until 1000){val path="/vol${i.toString().padStart(4,'0')}/deep/file";assertEquals("8:${i+1}",value.mount(path)!!.device);assertEquals("[]",value.topology(path))}
        val cache=FirmwareMounts::class.java.getDeclaredField("topologyCache").apply{isAccessible=true}.get(value) as Map<*,*>
        assertEquals(32,cache.size)
    }
    @Test fun `observed device encoding is checked against actual inode namespace`() {
        val value=table()
        assertTrue(value.deviceMatches("/data/file",(8L shl 8) or 2L));assertFalse(value.deviceMatches("/data/file",1))
    }
}
