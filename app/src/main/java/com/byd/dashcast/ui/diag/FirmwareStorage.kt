package com.byd.dashcast.ui.diag

/** Only fixed, bounded SHELL metadata supplies physical provenance. No filesystem type or
 * familiar mountpoint, by itself, establishes that a block filesystem is internal. */
internal class FirmwareStorage(
    val blocks: Map<String, Block> = emptyMap(),
    val primary: String? = null,
    val volumes: String? = null,
    val bootDevice: String = "",
    val issues: List<String> = emptyList(),
    val bootUserdata: BootUserdata? = null,
) {
    data class Block(val device:String,val path:String,val removable:String,val type:String,
        val dependencies:List<String> = emptyList(),val backing:String = "")
    data class BootUserdata(val device:String,val controller:String,val controllerMode:Int,
        val target:String,val mode:Int,val rdev:Long) {
        companion object {
            fun parse(text:String,expected:String):BootUserdata {
                if(!text.endsWith('\n'))throw java.io.IOException("Truncated boot userdata metadata")
                val fields=FirmwarePolicy.boundedLines(text).single().split('|')
                if(fields.size!=8 || fields[0]!="BOOT_USERDATA" || fields[1]!="1" || fields[2]!=expected)throw java.io.IOException("Invalid boot userdata metadata framing/schema/device")
                val controller=FirmwarePolicy.decoded(fields[3]);val target=FirmwarePolicy.decoded(fields[5])
                if(!FirmwarePolicy.sane(controller) || !controller.startsWith("/dev/block/platform/") || !target.matches(Regex("/dev/block/sd[a-z]+[0-9]+")))throw java.io.IOException("Invalid boot userdata canonical path")
                val controllerMode=fields[4].toIntOrNull()?:throw java.io.IOException("Invalid boot controller mode")
                val mode=fields[6].toIntOrNull()?:throw java.io.IOException("Invalid boot userdata mode")
                val rdev=fields[7].toLongOrNull()?.takeIf{it>=0}?:throw java.io.IOException("Invalid boot userdata device")
                if(controllerMode !in 0..0xffff || mode !in 0..0xffff || (controllerMode and 0xf000)!=0x4000 || (mode and 0xf000)!=0x6000 || FirmwareMounts.deviceId(rdev)!=expected)throw java.io.IOException("Boot userdata type/device mismatch")
                return BootUserdata(expected,controller,controllerMode,target,mode,rdev)
            }
        }
    }
    data class Result(val kind:String,val proof:String,val deviceWide:Boolean=false) {
        val internal get()=kind=="internal"
        fun reason()="$kind storage: $proof"
    }
    companion object {
        const val MAX_BLOCKS=64
        const val MAX_DEPTH=8
        private val internalEmulated=Regex("^/(?:storage/emulated|mnt/(?:runtime/(?:default|read|write|full)|user/[0-9]+|pass_through/[0-9]+)/emulated)(?:/.*)?$",RegexOption.DOT_MATCHES_ALL)
        private val usb=Regex("/usb[0-9]+/",RegexOption.IGNORE_CASE)
        private val ufs=Regex("(?:^|[/.-])ufs(?:hc|hcd)?[^/]*(?:/|$)",RegexOption.IGNORE_CASE)
        fun externalPath(path:String):Boolean {
            if(listOf("/mnt/media_rw","/mnt/expand","/mnt/asec","/mnt/secure/asec").any{FirmwareMounts.within(path,it)})return true
            if(path.startsWith("/storage/"))return path.removePrefix("/storage/").substringBefore('/') !in setOf("emulated","self")
            if(Regex("^/mnt/runtime/(?:default|read|write|full)/[^/]+(?:/.*)?$",RegexOption.DOT_MATCHES_ALL).matches(path))return !internalEmulated.matches(path)
            if(Regex("^/mnt/(?:user|pass_through)/[0-9]+/[^/]+(?:/.*)?$",RegexOption.DOT_MATCHES_ALL).matches(path))return !internalEmulated.matches(path)
            return false
        }
        fun externalSource(source:String)=source.startsWith("/dev/block/vold/public:") ||
            source.startsWith("/dev/block/vold/private:") || source.startsWith("/dev/block/vold/expand_") || externalPath(source)
        fun internalEmulatedPath(path:String)=internalEmulated.matches(path)
        private val primaryAnchor=Regex("^/(?:storage/emulated|mnt/(?:runtime/(?:default|read|write|full)|user/[0-9]+|pass_through/[0-9]+)/emulated)(?:/([0-9]+))?$")
        fun primaryAnchor(mount:FirmwareMount):Boolean {
            val match=primaryAnchor.matchEntire(mount.point)?:return false
            val user=match.groupValues[1]
            return if(user.isEmpty())mount.root=="/" else mount.root=="/$user"
        }
    }
    // Missing/failed/ambiguous command output never means the AOSP null internal UUID.
    private val volumeRows=volumes?.lineSequence()?.filter{it.isNotEmpty()}?.map{it.split(' ')}?.toList()
    private val validVolumes=volumeRows!=null && volumeRows.isNotEmpty() && volumeRows.all {
        it.size==3 && it[0].matches(Regex("[A-Za-z0-9:;,._-]+")) && it[1] in setOf("unmounted","checking","mounted","mounted_ro","formatting","ejecting","unmountable","removed","bad_removal") &&
            (it[2]=="null" || it[2].matches(Regex("[A-Za-z0-9-]+")))
    }
    val internalPrimary=primary=="null" && validVolumes && volumeRows!!.any {
        it[0]=="private" && it[1] in setOf("mounted","mounted_ro") && it[2]=="null"
    } && volumeRows.any {it[0].matches(Regex("emulated(?:;[0-9]+)?")) && it[1] in setOf("mounted","mounted_ro") && it[2]=="null"}
    val externalVolumeDevices:Set<String> = volumeRows.orEmpty().mapNotNull {row->
        if(row.size!=3)return@mapNotNull null
        val match=Regex("(?:public|private):([0-9]{1,10}),([0-9]{1,10})").matchEntire(row[0])?:return@mapNotNull null
        val major=match.groupValues[1].toLongOrNull()?:return@mapNotNull null
        val minor=match.groupValues[2].toLongOrNull()?:return@mapNotNull null
        if(major !in 1..0xffffffffL || minor !in 0..0xffffffffL)return@mapNotNull null
        "$major:$minor"
    }.toSet()
    fun externalMount(mount:FirmwareMount)=externalPath(mount.point) || externalSource(mount.source) ||
        mount.device in externalVolumeDevices ||
        ((mount.type=="fuse" || mount.type=="fuseblk") && internalEmulatedPath(mount.point) && primary!=null && primary!="null")
    fun classify(mount:FirmwareMount,mountOf:(String)->FirmwareMount?,interfaces:Set<String> = emptySet(),denyBacking:(String,FirmwareMount)->Result? = {_,_->null}):Result {
        if(externalMount(mount))return externalResult(mount)
        return classifyMount(mount,mountOf,emptySet(),0,interfaces,denyBacking)
    }
    private fun classifyMount(mount:FirmwareMount,mountOf:(String)->FirmwareMount?,visited:Set<String>,depth:Int,interfaces:Set<String>,denyBacking:(String,FirmwareMount)->Result?):Result {
        if(depth>MAX_DEPTH)return Result("unknown","backing dependency depth limit")
        if(externalMount(mount))return externalResult(mount)
        if(mount.type in setOf("rootfs","tmpfs","ramfs"))return Result("internal","ordinary boot/memory filesystem; volatile")
        if(mount.type in setOf("sdcardfs","esdfs") && mount.source.startsWith('/'))return backing(mount.source,mountOf,visited,depth+1,interfaces,denyBacking)
        if(mount.type=="fuse" && primaryAnchor(mount) && mount.source=="/dev/fuse") {
            if(!internalPrimary)return Result("unknown","internal emulated backing not established by StorageManager metadata")
            val data=backing("/data/media",mountOf,visited,depth+1,interfaces,denyBacking)
            return if(data.internal)Result("internal","successful AOSP primary UUID null, internal private/emulated volumes and attested userdata backing") else data
        }
        if(mount.device.substringBefore(':').toLongOrNull()?.let{it>0}==true)return device(mount.device,mountOf,visited,depth+1,interfaces,denyBacking)
        return Result("unknown","no attested internal block or emulated backing")
    }
    private fun externalResult(mount:FirmwareMount)=Result("external","removable/adopted volume point, source or primary UUID",
        mount.device in externalVolumeDevices || mount.source.startsWith("/dev/block/vold/"))
    private fun backing(path:String,mountOf:(String)->FirmwareMount?,visited:Set<String>,depth:Int,interfaces:Set<String>,denyBacking:(String,FirmwareMount)->Result?):Result {
        if(!FirmwarePolicy.sane(path))return Result("unknown","invalid backing path")
        if(listOf("/dev","/proc","/sys").any{FirmwareMounts.within(path,it)})return Result("unknown","pseudo/device interface cannot establish storage backing")
        if(externalPath(path))return Result("external","external backing path")
        val mount=mountOf(path)?:return Result("unknown","backing mount not observed")
        if(mount.device in interfaces || FirmwareMounts.pseudoInterface(mount))return Result("unknown","pseudo/device backing mount or alias")
        denyBacking(path,mount)?.let{return it}
        return classifyMount(mount,mountOf,visited,depth,interfaces,denyBacking)
    }
    private fun device(id:String,mountOf:(String)->FirmwareMount?,visited:Set<String>,depth:Int,interfaces:Set<String>,denyBacking:(String,FirmwareMount)->Result?):Result {
        if(depth>MAX_DEPTH || id in visited)return Result("unknown","block dependency cycle/depth limit")
        if(id in externalVolumeDevices)return Result("external","StorageManager public/adopted volume device",true)
        val node=blocks[id]
        if(node!=null && (usb.containsMatchIn(node.path+"/") || node.removable=="1" || node.type=="SD"))return Result("external","USB/SD/removable physical block dependency",true)
        if(node==null || !node.path.startsWith("/sys/devices/"))return bootProof(id,mountOf)
            ?: Result("unknown",if(node==null)"block provenance unavailable for $id" else "unresolved kernel block path")
        val next=visited+id
        if(node.backing.isNotEmpty())return backing(node.backing,mountOf,next,depth+1,interfaces,denyBacking)
        if(node.dependencies.isNotEmpty()) {
            val dependencies=node.dependencies.map{device(it,mountOf,next,depth+1,interfaces,denyBacking)}
            return dependencies.firstOrNull{it.kind=="external"} ?: dependencies.firstOrNull{!it.internal}
                ?: Result("internal","all partition/dm block dependencies attested internal")
        }
        if(node.removable!="0")return Result("unknown","physical removable metadata unavailable")
        if(node.type=="MMC" || ufs.containsMatchIn(node.path))return Result("internal","non-removable MMC/UFS kernel device without USB ancestry")
        val boot=bootDevice.trim('/').takeIf {it.length in 4..1024 && !it.contains('\n') && !it.contains('\u0000')}
        if(boot!=null && ("/${boot}/") in (node.path+"/"))return Result("internal","non-removable kernel device on declared boot controller")
        return Result("unknown","removable=0 alone does not establish internal transport")
    }
    /** Independent fixed boot-device metadata can attest only this userdata block. It never
     * supplies missing dm/slave evidence for other mounted filesystems. */
    private fun bootProof(id:String,mountOf:(String)->FirmwareMount?):Result? {
        val proof=bootUserdata?:return null
        val data=mountOf("/data")?:return null
        if(!internalPrimary || proof.device!=id || data.device!=id || data.source!="/dev/block/bootdevice/by-name/userdata")return null
        if(!FirmwarePolicy.sane(proof.controller) || !proof.controller.startsWith("/dev/block/platform/") ||
            proof.controller.substringAfterLast('/')!=bootDevice || !bootDevice.matches(Regex("(?:[A-Za-z0-9_-]+\\.)?ufs(?:hc|hcd)",RegexOption.IGNORE_CASE)) ||
            proof.controller.split('/').any{it.startsWith("usb",true)})return null
        if(!proof.target.matches(Regex("/dev/block/sd[a-z]+[0-9]+")) || (proof.controllerMode and 0xf000)!=0x4000 ||
            (proof.mode and 0xf000)!=0x6000 || FirmwareMounts.deviceId(proof.rdev)!=id)return null
        return Result("internal","successful internal primary/private/emulated StorageManager metadata plus fixed boot-controller userdata block identity")
    }
    fun json():String {
        fun q(v:String)=FirmwarePolicy.json(v)
        return "{\"primaryUuid\":${primary?.let(::q)?:"null"},\"volumes\":${volumes?.let(::q)?:"null"},\"bootDevice\":${q(bootDevice)},\"bootUserdata\":"+
            (bootUserdata?.let{"{\"device\":${q(it.device)},\"controller\":${q(it.controller)},\"controllerMode\":${it.controllerMode},\"target\":${q(it.target)},\"mode\":${it.mode},\"rdev\":${it.rdev}}"}?:"null")+",\"issues\":"+
            issues.joinToString(",","[","]",transform=::q)+",\"blocks\":"+blocks.values.sortedBy{it.device}.joinToString(",","[","]") {
                "{\"device\":${q(it.device)},\"kernelPath\":${q(it.path)},\"removable\":${q(it.removable)},\"type\":${q(it.type)},\"dependencies\":"+
                    it.dependencies.joinToString(",","[","]",transform=::q)+",\"backing\":${q(it.backing)}}"
            }+"}"
    }
}

