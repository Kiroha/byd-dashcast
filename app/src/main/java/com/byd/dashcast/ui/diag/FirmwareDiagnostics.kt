package com.byd.dashcast.ui.diag

/** Recent collector metadata only, retained in RAM even when preflight creates no directory.
 * Fixed fields bound retained UTF-8 data below 96 KiB. No collector, filesystem or network call
 * occurs here; the existing consented bug-report path can include [snapshot]. */
internal object FirmwareDiagnostics {
    const val MAX_BYTES=96*1024
    enum class Field(val limit:Int) {
        SESSION(512), STATE(512), PHASE(512), REQUESTED(4096), CANONICAL(4096), ACTUAL_DEVICE(128), DAEMON(1024),
        FAILURE(4096), PRIMARY_UUID(4096), VOLUMES(8192), BOOT_DEVICE(4096), BOOT_USERDATA(4096),
        BLOCKS(20480), PROVENANCE(8192), SHELL_OUTPUT_MOUNTS(8192), APP_OUTPUT_MOUNTS(8192),
        METADATA_ISSUES(8192)
    }
    private val fields=java.util.EnumMap<Field,String>(Field::class.java)
    private var failed=false
    private var prepared=false

    @Synchronized fun begin() {
        fields.clear();failed=false;prepared=false
        record(Field.SESSION,"${java.util.UUID.randomUUID()}; startedEpochMs=${System.currentTimeMillis()}; process-local historical evidence")
        record(Field.STATE,"preflight; metadata only; live non-atomic observations")
    }
    @Synchronized fun record(field:Field,value:String) {
        if(!failed)fields[field]=bounded(value,field.limit)
    }
    @Synchronized fun phase(value:String,path:String?=null) {
        record(Field.PHASE,value)
        if(path!=null)record(Field.REQUESTED,path)
    }
    @Synchronized fun ready() {
        if(!failed){prepared=true;record(Field.STATE,"destination initially attested; live checks remain necessary")}
    }
    /** The first cause and its associated observations survive close/finalization failures. */
    @Synchronized fun failure(error:Throwable) {
        if(failed)return
        var cause=error
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        repeat(16){if(seen.add(cause)){val next=cause.cause;if(next!=null && next!==cause)cause=next}}
        val detail="${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
            .replace(Regex("https?://\\S+"),"<url>")
            .replace(Regex("[0-9]{6,12}:[A-Za-z0-9_-]{25,}"),"<token>")
        record(Field.FAILURE,detail)
        record(Field.STATE,if(prepared)"failed after initial attestation" else "not started; initial attestation not established")
        failed=true
    }
    @Synchronized fun failureSummary():String? {
        if(!failed)return null
        val first=if(prepared)"Export arrêté." else "Export non démarré."
        return "$first\n${fields[Field.PHASE].orEmpty()} : ${bounded(fields[Field.FAILURE].orEmpty(),220)}\nDétails dans le rapport de bug."
    }
    @Synchronized fun failurePath():String?=if(failed)fields[Field.REQUESTED] else null
    @Synchronized fun snapshot():String {
        if(fields.isEmpty())return "No internal-storage export diagnostic in this process.\n"
        val text=buildString {
            append("Recent internal-storage export; metadata only, no source-file payload.\n")
            for(field in Field.entries)fields[field]?.let{append('[').append(field.name).append("]\n").append(it).append('\n')}
        }
        check(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
        return text
    }
    internal fun bounded(value:String,limit:Int):String {
        val bytes=value.toByteArray(Charsets.UTF_8)
        if(bytes.size<=limit)return value
        val suffix="\n[truncated]"
        var end=limit-suffix.length
        while(end>0 && (bytes[end].toInt() and 0xc0)==0x80)end--
        return String(bytes,0,end,Charsets.UTF_8)+suffix
    }
    fun mounts(table:FirmwareMounts,paths:List<String>):String=paths.distinct().joinToString("\n") {path->
        val mount=table.mount(path)
        "$path => $mount ; storage=${table.storageDecision(path)?:"internal"}"
    }
}
