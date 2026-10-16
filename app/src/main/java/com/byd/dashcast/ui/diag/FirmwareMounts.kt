package com.byd.dashcast.ui.diag

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Mount metadata only. Mount IDs, propagation IDs and options are provenance, not resume identity. */
internal data class FirmwareMount(val device: String, val root: String, val point: String, val type: String, val raw: String = "", val source: String = "")

internal class FirmwareMounts(val entries: List<FirmwareMount>, private val exclusions: List<String> = emptyList()) {
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_MOUNTS = 4096
        private val storage = setOf("rootfs", "tmpfs", "ramfs", "ext2", "ext3", "ext4", "f2fs", "erofs",
            "squashfs", "xfs", "btrfs", "vfat", "exfat", "ntfs", "ntfs3", "fuse", "fuseblk", "sdcardfs",
            "esdfs", "overlay", "aufs", "ubifs", "yaffs2", "jffs2")
        private val ownUserBacking=Regex("^/(?:user|user_de)/[0-9]+/com\\.byd\\.dashcast(?:/.*)?$",RegexOption.DOT_MATCHES_ALL)
        private val androidStorage=Regex("^/(?:storage(?:/.*)?|mnt/(?:runtime|user|media_rw|pass_through|expand)(?:/.*)?|data/media(?:/.*)?)$",RegexOption.DOT_MATCHES_ALL)
        private val pseudo = setOf("proc", "sysfs", "devtmpfs", "devpts", "debugfs", "tracefs", "configfs",
            "securityfs", "selinuxfs", "cgroup", "cgroup2", "bpf", "pstore", "fusectl", "binder", "binderfs",
            "efivarfs", "mqueue", "hugetlbfs", "autofs", "binfmt_misc", "rpc_pipefs")
        fun within(path: String, root: String) = path == root || (root == "/" && path.startsWith('/')) || path.startsWith("$root/")
        private fun unescape(value: String): String = buildString {
            var i=0
            while(i<value.length) {
                if(value[i]!='\\')append(value[i++]) else {
                    if(i+3>=value.length)throw IOException("Incomplete mount escape")
                    append(when(value.substring(i+1,i+4)){"040"->' ';"011"->'\t';"012"->'\n';"134"->'\\';else->throw IOException("Unknown mount escape")})
                    i+=4
                }
            }
        }
        fun parse(input: InputStream, exclusions: List<String> = emptyList()): FirmwareMounts {
            var bytes=0
            val bounded=object:java.io.FilterInputStream(input){
                override fun read():Int {val n=super.read();if(n>=0 && ++bytes>MAX_BYTES)throw IOException("Mount metadata exceeds byte budget");return n}
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {val n=`in`.read(buffer,offset,length);if(n>0){bytes+=n;if(bytes>MAX_BYTES)throw IOException("Mount metadata exceeds byte budget")};return n}
            }
            val reader=java.io.InputStreamReader(bounded,Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)).buffered(8192)
            val result=ArrayList<FirmwareMount>();val line=StringBuilder();var chars=0
            fun consume() {
                if(line.isEmpty())throw IOException("Empty mountinfo record")
                val fields=line.toString().split(' ');val separator=fields.indexOf("-")
                if(separator<6 || fields.size<separator+4 || fields[0].toLongOrNull()==null || fields[1].toLongOrNull()==null ||
                    !fields[2].matches(Regex("[0-9]+:[0-9]+")))throw IOException("Malformed mountinfo record")
                val root=unescape(fields[3]);val point=unescape(fields[4]);val type=fields[separator+1]
                if(!FirmwarePolicy.sane(root) || !FirmwarePolicy.sane(point) || !type.matches(Regex("[A-Za-z0-9_.-]+")))throw IOException("Invalid mount path/type")
                if(result.size>=MAX_MOUNTS)throw IOException("Mount count exceeds $MAX_MOUNTS")
                result.add(FirmwareMount(fields[2],root,point,type,line.toString(),unescape(fields[separator+2])));line.setLength(0)
            }
            while(true){val c=reader.read();if(c<0)break;if(++chars>MAX_BYTES)throw IOException("Mount metadata exceeds budget")
                if(c==10)consume() else {if(line.length>=FirmwarePolicy.MAX_LINE)throw IOException("Mount metadata line exceeds budget");line.append(c.toChar())}}
            if(line.isNotEmpty())throw IOException("Incomplete mountinfo final record")
            if(result.none{it.point=="/"})throw IOException("Mountinfo has no root; namespace coverage unknown")
            return FirmwareMounts(result,exclusions)
        }
    }
    private val points=java.util.TreeMap<String,FirmwareMount>().apply {this@FirmwareMounts.entries.forEach {put(it.point,it)}}
    private val topologyCache=object:LinkedHashMap<String,String>(32,0.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>?)=size>32}
    private val knownFuseDevices=entries.filter {it.type in setOf("fuse","fuseblk") && androidStorage.matches(it.point) &&
        (it.source=="/dev/fuse" || it.source.startsWith("/dev/block/"))}.map{it.device}.toSet()
    private val ambiguous=entries.groupBy{it.point}.filterValues{it.size>1}.keys
    private val sensitiveDevices=entries.filter { it.type in pseudo || listOf("/dev","/proc","/sys").any { root->within(it.point,root) } }.map{it.device}.toSet()
    private fun backing(path: String, mount: FirmwareMount) = (mount.root.trimEnd('/') + "/" + path.removePrefix(mount.point).trimStart('/')).trimEnd('/').ifEmpty{"/"}
    private val excludedBacking=exclusions.mapNotNull { path->mount(path)?.let{it.device to backing(path,it)} }
    fun mount(path: String): FirmwareMount? {
        var candidate=path
        while(true){points[candidate]?.let{return it};if(candidate=="/")return null;candidate=candidate.substringBeforeLast('/',"").ifEmpty{"/"}}
    }
    fun reason(mount: FirmwareMount): String? = when {
        mount.point in ambiguous -> "ambiguous overlaid mountpoint"
        mount.type in pseudo -> "pseudo filesystem ${mount.type}"
        mount.type !in storage -> "unrecognised/non-local filesystem ${mount.type}"
        mount.type in setOf("fuse","fuseblk") && mount.device !in knownFuseDevices -> "unrecognised/non-local FUSE provenance"
        mount.device in sensitiveDevices -> "filesystem also mounted as device/pseudo interface"
        !FirmwarePolicy.firmware(mount.point) -> "excluded interface or collector output"
        else -> null
    }
    fun decision(path: String): String? {
        if(!FirmwarePolicy.firmware(path))return "excluded interface or collector output"
        if(exclusions.any{within(path,it)})return "collector private/cache/export tree"
        val mount=mount(path)?:return "unobserved mount"
        reason(mount)?.let{return it}
        val backed=backing(path,mount)
        if(!FirmwarePolicy.firmware(backed) || backed.startsWith("/local/tmp/dashcast") || ownUserBacking.matches(backed))return "collector/interface backing subtree"
        if(excludedBacking.any{it.first==mount.device && within(backed,it.second)})return "bind alias of collector output"
        return null
    }
    fun deviceMatches(path:String,device:Long):Boolean {
        val major=((device ushr 8) and 0xfffL) or ((device ushr 32) and 0xfffff000L)
        val minor=(device and 0xffL) or ((device ushr 12) and 0xffffff00L)
        return mount(path)?.device=="$major:$minor"
    }
    fun json():String=entries.joinToString(",","[","]") { m->
        "{\"device\":${FirmwarePolicy.json(m.device)},\"root\":${FirmwarePolicy.json(m.root)},\"point\":${FirmwarePolicy.json(m.point)},\"type\":${FirmwarePolicy.json(m.type)},\"source\":${FirmwarePolicy.json(m.source)},\"decision\":${FirmwarePolicy.json(decision(m.point)?:"admitted")},\"rawMountinfo\":${FirmwarePolicy.json(m.raw)}}"
    }
    fun accepted(path: String, canonical: String) = decision(path)==null && decision(canonical)==null
    /** A directory stays within its own mount. Every admitted submount is a separate root. */
    fun sameMount(directory: String, child: String) = mount(directory)==mount(child)
    fun topology(path: String): String {
        topologyCache[path]?.let{return it}
        val prefix=path.trimEnd('/')+"/"
        val children=points.tailMap(prefix).entries.asSequence().takeWhile{it.key.startsWith(prefix)}.filter{it.key!=path}
        val serialized=children.joinToString(",","[","]") {
            val mount=it.value
            listOf(mount.point.removePrefix(path),mount.device,mount.root,mount.type,decision(mount.point).orEmpty()).joinToString(",","[","]",transform=FirmwarePolicy::json)
        }
        val result=if(serialized=="[]")"[]" else FirmwareExport.hex(MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8)))
        topologyCache[path]=result;return result
    }
    fun fingerprint(): String = FirmwareExport.hex(MessageDigest.getInstance("SHA-256").digest(
        (entries.sortedWith(compareBy({it.point},{it.device},{it.root},{it.type})).joinToString(",","[","]") {
            listOf(it.point,it.device,it.root,it.type,decision(it.point).orEmpty()).joinToString(",","[","]",transform=FirmwarePolicy::json)
        }+exclusions.sorted().joinToString(",","[","]",transform=FirmwarePolicy::json)).toByteArray(Charsets.UTF_8)))

}
