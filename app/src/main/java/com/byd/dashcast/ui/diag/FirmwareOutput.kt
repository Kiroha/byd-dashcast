package com.byd.dashcast.ui.diag

import java.io.IOException

/** APP metadata is restricted to its own output path; source discovery/reads remain daemon-only.
 * APP and daemon storage views are attested independently, since FUSE device numbers can differ. */
internal object FirmwareOutput {
    fun present(path:String):Boolean=try{android.system.Os.lstat(path);true}catch(e:android.system.ErrnoException){
        if(e.errno==android.system.OsConstants.ENOENT || e.errno==android.system.OsConstants.ENOTDIR)false else throw e
    }
    fun observe(path:String,exclusions:List<String>,storage:FirmwareStorage):String {
        var ancestor=java.io.File(path);var depth=0
        while(!present(ancestor.path)){if(++depth>32)throw IOException("Ancêtre sortie APP inconnu");ancestor=ancestor.parentFile?:throw IOException("Sortie APP absente")}
        val real=ancestor.canonicalPath;val stat=android.system.Os.stat(real)
        val appTable=java.io.FileInputStream("/proc/self/mountinfo").use {FirmwareMounts.parse(it,exclusions,storage)}
        return validate(path,real,stat.st_dev,appTable)
    }
    fun validate(path:String,canonicalAncestor:String,actualDevice:Long,table:FirmwareMounts):String {
        val reason=table.storageDecision(path)?:table.storageDecision(canonicalAncestor)
        if(reason!=null || !table.deviceMatches(canonicalAncestor,actualDevice))throw IOException("Sortie APP non attestée interne : $reason")
        return listOf(path,table.mount(path),table.mount(canonicalAncestor)).joinToString("|") { value->
            if(value is FirmwareMount)listOf(value.device,value.root,value.point,value.type,value.source).joinToString(",","[","]",transform=FirmwarePolicy::json)
            else FirmwarePolicy.json(value.toString())
        }
    }
}
