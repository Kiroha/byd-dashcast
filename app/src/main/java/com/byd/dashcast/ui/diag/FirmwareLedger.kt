package com.byd.dashcast.ui.diag

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.Writer

/** Disk-backed inventory and durable volume intent journal; used exclusively by one worker. */
internal class FirmwareLedger(private val directory: File, private val sourceLimit: Int = FirmwarePolicy.MAX_SOURCES,
    private val inventoryLimit: Long = FirmwarePolicy.MAX_INVENTORY_BYTES, private val incidentLimit: Int = FirmwarePolicy.MAX_INCIDENTS,
    private var writes: FirmwareWriteGuard? = null) : Closeable {
    private val file = File(directory, "firmware-ledger.sqlite")
    private val db: SQLiteDatabase
    var sources = 0; private set
    var incidents = 0L; private set
    private var mutations = 0
    private var directoryCount = 0L
    private var storedIncidents = 0L
    init {
        require(sourceLimit in 1..FirmwarePolicy.MAX_SOURCES && inventoryLimit in 1..FirmwarePolicy.MAX_INVENTORY_BYTES && incidentLimit in 1..FirmwarePolicy.MAX_INCIDENTS)
        writes?.validate()
        check(directory.mkdirs() || directory.isDirectory)
        writes?.check()
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        // No WAL/checkpoint deferred to close after a terminal output refusal. Attestations
        // happen outside transactions, which have finished before the latch can be lost.
        db.rawQuery("PRAGMA journal_mode=DELETE",null).use {check(it.moveToFirst() && it.getString(0).equals("delete",true))}
        db.execSQL("PRAGMA synchronous=FULL")
        val pageBytes=db.rawQuery("PRAGMA page_size",null).use {it.moveToFirst();it.getLong(0)}
        db.rawQuery("PRAGMA max_page_count=${maxOf(16,(inventoryLimit*7/8)/pageBytes)}",null).use {it.moveToFirst()}
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sources(id INTEGER PRIMARY KEY, canonical TEXT NOT NULL, app_path TEXT, app_size INTEGER, app_stamp TEXT, shell_path TEXT, shell_size INTEGER, shell_stamp TEXT, package_origin TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE IF NOT EXISTS dirs(id INTEGER PRIMARY KEY, identity TEXT, path TEXT, offset INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0, UNIQUE(identity,path))")
        db.execSQL("CREATE INDEX IF NOT EXISTS dirs_done ON dirs(done,id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS incidents(id INTEGER PRIMARY KEY, message TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS coverage(id INTEGER PRIMARY KEY, identity TEXT, path TEXT, state TEXT, detail TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS volumes(id INTEGER PRIMARY KEY, name TEXT UNIQUE, state TEXT NOT NULL, bytes INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS copies(id INTEGER PRIMARY KEY, source INTEGER, volume INTEGER, entry TEXT, bytes INTEGER, sha256 TEXT, success INTEGER, error TEXT, identity TEXT, observed_ms INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS origins(source INTEGER,identity TEXT,path TEXT,package_origin TEXT, UNIQUE(source,identity,path,package_origin))")
        db.execSQL("CREATE INDEX IF NOT EXISTS origin_source ON origins(source)")
        db.execSQL("CREATE TABLE IF NOT EXISTS observations(source INTEGER,identity TEXT,path TEXT,canonical TEXT,bytes INTEGER,stamp TEXT,node TEXT,UNIQUE(source,identity,path,canonical,stamp))")
        db.execSQL("CREATE INDEX IF NOT EXISTS observation_source ON observations(source)")
        db.execSQL("CREATE TABLE IF NOT EXISTS nodes(node TEXT PRIMARY KEY,source INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS preferred_paths(source INTEGER,identity TEXT,canonical TEXT NOT NULL,PRIMARY KEY(source,identity))")
        db.execSQL("CREATE TABLE IF NOT EXISTS directory_nodes(identity TEXT,node TEXT,topology TEXT,path TEXT,PRIMARY KEY(identity,node,topology))")
        db.execSQL("CREATE INDEX IF NOT EXISTS copy_source ON copies(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS copy_volume ON copies(volume)")
        sources = count("sources").toInt(); storedIncidents = count("incidents"); directoryCount = count("dirs"); incidents = meta("incident_count")?.toLongOrNull() ?: storedIncidents
    }
    fun protect(guard: FirmwareWriteGuard) { check(writes == null || writes === guard); writes = guard; guard.check() }
    fun meta(key: String): String? = db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun meta(key: String, value: String) { write { db.insertWithOnConflict("meta", null, ContentValues().apply { put("k",key); put("v",value) }, SQLiteDatabase.CONFLICT_REPLACE) } }
    fun <T> batch(block: () -> T): T {
        writes?.check()
        if(db.inTransaction())return block()
        val savedSources=sources;val savedIncidents=incidents;val savedStored=storedIncidents;val savedDirs=directoryCount
        writes?.enterTransaction()
        var begun=false;var committed=false
        try {db.beginTransaction();begun=true;budget();val result=block();budget();writes?.check();db.setTransactionSuccessful();committed=true;return result}
        catch(e:android.database.sqlite.SQLiteFullException){throw FirmwareBudget("Inventory SQLite page budget reached; remaining coverage unknown")}
        finally {try{if(begun)db.endTransaction()}finally{writes?.leaveTransaction()};if(!committed){sources=savedSources;incidents=savedIncidents;storedIncidents=savedStored;directoryCount=savedDirs}}
    }
    private fun <T> write(block:()->T):T {
        writes?.check()
        return if(db.inTransaction()) {val result=block();if(++mutations%16==0)budget();result} else batch(block)
    }
    fun initialize(context: String) = batch {
        meta("schema",FirmwarePolicy.SCHEMA.toString());meta("profile",FirmwarePolicy.PROFILE);meta("context",context);meta("started_at",System.currentTimeMillis().toString())
    }
    private fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
    fun issue(message: String) { write {
        incidents++; meta("incident_count", incidents.toString())
        if (storedIncidents < incidentLimit) { db.insertOrThrow("incidents",null,ContentValues().apply { put("message",message.take(3000)) }); storedIncidents++ }
    } }
    fun coverage(identity: String, path: String, state: String, detail: String = "") { write {
        db.insertOrThrow("coverage",null,ContentValues().apply { put("identity",identity); put("path",path); put("state",state); put("detail",detail.take(2000)) })
    } }
    fun budget() {
        val allocated=db.rawQuery("PRAGMA page_count",null).use {it.moveToFirst();it.getLong(0)} * db.rawQuery("PRAGMA page_size",null).use {it.moveToFirst();it.getLong(0)}
        val used = maxOf(file.length(),allocated) + File(file.path + "-journal").length() + File(file.path + "-wal").length() + File(file.path + "-shm").length()
        if (used >= (inventoryLimit - minOf(1024L*1024, inventoryLimit/8))) throw FirmwareBudget("Inventory disk limit reached; remaining coverage unknown")
    }
    fun add(identity: String, value: FirmwareObserved, packageOrigin: String = "") { write {
        require(identity == "APP" || identity == "SHELL")
        require(value.size >= 0 && FirmwarePolicy.accepted(value.path,value.canonical,packageOrigin.isNotEmpty()))
        val old = if(value.node.isNotEmpty())byNode(value.node) else byCanonical(value.canonical)
        if (old == null && sources >= sourceLimit) throw FirmwareBudget("Source cap ${FirmwarePolicy.MAX_SOURCES} reached")
        val key = if (identity == "APP") "app" else "shell"
        val values = ContentValues().apply {
            if(old==null)put("canonical",value.canonical);
            if(old == null || (identity == "APP" && old.appPath == null) || (identity == "SHELL" && old.shellPath == null))put("${key}_path",value.path)
            put("${key}_size",value.size); put("${key}_stamp",value.stamp)
            if (packageOrigin.isNotEmpty()) put("package_origin",packageOrigin)
        }
        val inserted = if (old == null) { sources++;db.insertOrThrow("sources",null,values) } else -1L
        if (old != null) {
            val priorSize = if(identity == "APP") old.appSize else old.shellSize
            val priorStamp = if(identity == "APP") old.appStamp else old.shellStamp
            if (priorSize != null && (priorSize != value.size || priorStamp != value.stamp)) issue("$identity live metadata changed during inventory: ${value.path}")
            db.update("sources",values,"id=?",arrayOf(old.id.toString()))
        }
        val id=old?.id ?: inserted
        if(value.node.isNotEmpty())db.insertWithOnConflict("nodes",null,ContentValues().apply {put("node",value.node);put("source",id)},SQLiteDatabase.CONFLICT_IGNORE)
        db.insertWithOnConflict("preferred_paths",null,ContentValues().apply {put("source",id);put("identity",identity);put("canonical",value.canonical)},SQLiteDatabase.CONFLICT_IGNORE)
        db.insertWithOnConflict("origins",null,ContentValues().apply {put("source",id);put("identity",identity);put("path",value.path);put("package_origin",packageOrigin)},SQLiteDatabase.CONFLICT_IGNORE)
        db.insertWithOnConflict("observations",null,ContentValues().apply {put("source",id);put("identity",identity);put("path",value.path);put("canonical",value.canonical);put("bytes",value.size);put("stamp",value.stamp);put("node",value.node)},SQLiteDatabase.CONFLICT_IGNORE)
    } }
    fun enqueue(identity: String, path: String) { write {
        require(FirmwarePolicy.firmware(path))
        if (directoryCount >= sourceLimit) throw FirmwareBudget("Directory inventory cap reached")
        if (db.insertWithOnConflict("dirs",null,ContentValues().apply {put("identity",identity);put("path",path)},SQLiteDatabase.CONFLICT_IGNORE) != -1L) directoryCount++
    } }
    fun nextDirectory(): FirmwareDirectory? = db.rawQuery("SELECT id,identity,path,offset FROM dirs WHERE done=0 ORDER BY id LIMIT 1",null).use {
        if (!it.moveToFirst()) null else FirmwareDirectory(it.getLong(0),it.getString(1),it.getString(2),it.getInt(3)) }
    fun advance(dir: FirmwareDirectory, offset: Int, done: Boolean) { write { db.execSQL("UPDATE dirs SET offset=?,done=? WHERE id=?",arrayOf<Any>(offset,if(done)1 else 0,dir.id)) } }
    fun byCanonical(path: String): FirmwareSource? = db.rawQuery("SELECT * FROM sources WHERE canonical=? OR id IN (SELECT source FROM preferred_paths WHERE canonical=?) LIMIT 1",arrayOf(path,path)).use { if(it.moveToFirst()) source(it) else null }
    private fun byNode(node:String):FirmwareSource?=db.rawQuery("SELECT s.* FROM sources s JOIN nodes n ON n.source=s.id WHERE n.node=? LIMIT 1",arrayOf(node)).use {if(it.moveToFirst())source(it) else null}
    private fun preferred(id:Long,identity:String):String?=db.rawQuery("SELECT canonical FROM preferred_paths WHERE source=? AND identity=?",arrayOf(id.toString(),identity)).use {if(it.moveToFirst())it.getString(0) else null}
    fun directoryOwner(identity:String,node:String,topology:String,path:String):String =
        db.rawQuery("SELECT path FROM directory_nodes WHERE identity=? AND node=? AND topology=?",arrayOf(identity,node,topology)).use {if(it.moveToFirst())it.getString(0) else path}
    fun completeDirectory(identity:String,node:String,topology:String,path:String) {write {
        db.insertWithOnConflict("directory_nodes",null,ContentValues().apply {put("identity",identity);put("node",node);put("topology",topology);put("path",path)},SQLiteDatabase.CONFLICT_IGNORE)
    }}
    fun forEachSource(action: (FirmwareSource) -> Unit) { db.rawQuery("SELECT * FROM sources ORDER BY id",null).use { while(it.moveToNext()) action(source(it)) } }
    private fun source(c: Cursor) = FirmwareSource(c.getLong(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getLong(3),c.getString(4),c.getString(5),if(c.isNull(6))null else c.getLong(6),c.getString(7),c.getString(8),c.getString(9),preferred(c.getLong(0),"APP"),preferred(c.getLong(0),"SHELL"))
    fun sourceError(id: Long,error: String) { write { db.execSQL("UPDATE sources SET error=? WHERE id=?",arrayOf<Any>(error.take(3000),id)) } }
    data class Volume(val id: Long,val name: String,val state: String,val bytes: Long)
    data class Copy(val source: Long,val volume: Long,val entry: String,val bytes: Long,val sha: String,val success: Boolean,val error: String,val identity: String,val observedMs: Long)
    fun volumes(): List<Volume> = db.rawQuery("SELECT id,name,state,bytes FROM volumes ORDER BY id",null).use { c -> buildList { while(c.moveToNext()) add(Volume(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3))) } }
    fun newVolume(): Volume {
        val id = db.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM volumes",null).use { it.moveToFirst();it.getLong(0) }
        if(id>4096)throw FirmwareBudget("Volume cap 4096 reached; existing ZIPs retained")
        val name = "firmware-${id.toString().padStart(4,'0')}.zip"
        check(!File(directory,name).exists() && !File(directory,"$name.part").exists()) { "Volume name collision; no existing volume overwritten" }
        write {db.insertOrThrow("volumes",null,ContentValues().apply { put("id",id);put("name",name);put("state","part") })}
        return Volume(id,name,"part",0)
    }
    fun volumeState(id: Long,state: String,bytes: Long = 0) { write {db.execSQL("UPDATE volumes SET state=?,bytes=? WHERE id=?",arrayOf<Any>(state,bytes,id))} }
    fun copy(value: Copy) { write { db.insertOrThrow("copies",null,ContentValues().apply {
        put("source",value.source);put("volume",value.volume);put("entry",value.entry);put("bytes",value.bytes);put("sha256",value.sha)
        put("success",if(value.success)1 else 0);put("error",value.error.take(3000));put("identity",value.identity);put("observed_ms",value.observedMs)
    }) } }
    fun copies(volume: Long): List<Copy> = db.rawQuery("SELECT source,volume,entry,bytes,sha256,success,error,identity,observed_ms FROM copies WHERE volume=? ORDER BY id",arrayOf(volume.toString())).use { c -> buildList { while(c.moveToNext()) add(copy(c)) } }
    private fun copy(c: Cursor) = Copy(c.getLong(0),c.getLong(1),c.getString(2),c.getLong(3),c.getString(4),c.getInt(5)==1,c.getString(6),c.getString(7),c.getLong(8))
    fun copied(source: Long) = db.rawQuery("SELECT 1 FROM copies c JOIN volumes v ON v.id=c.volume WHERE c.source=? AND c.success=1 AND v.state='verified' LIMIT 1",arrayOf(source.toString())).use { it.moveToFirst() }
    fun successful(): Int = db.rawQuery("SELECT COUNT(DISTINCT c.source) FROM copies c JOIN volumes v ON v.id=c.volume WHERE c.success=1 AND v.state='verified'",null).use {it.moveToFirst();it.getInt(0)}
    fun totalBytes(): Long = db.rawQuery("SELECT COALESCE(SUM(c.bytes),0) FROM copies c JOIN volumes v ON v.id=c.volume WHERE c.success=1 AND v.state='verified'",null).use {it.moveToFirst();it.getLong(0)}
    fun manifest(writer: Writer,outcome: String,message: String) {
        fun q(s:String)=FirmwarePolicy.json(s)
        writer.append("{\"schema\":${FirmwarePolicy.SCHEMA},\"kind\":\"FIRMWARE_LARGE\",\"profile\":${q(FirmwarePolicy.PROFILE)},\"outcome\":${q(outcome)},\"message\":${q(message)},\"wholeVehicleCoverage\":\"unverified\",\"scope\":\"All ordinary accessible files on attested INTERNAL storage in the SHELL daemon namespace only (UID2000). SD/USB/adopted/unknown storage and aliases excluded before inventory/read; internal firmware, data and emulated storage only; no APP source scan/fallback. Explicit pseudo-filesystem, device, collector output/cache/bootstrap exclusions. No partition image or target execution; live non-atomic, potentially personal data.\",\"startedAt\":${q(meta("started_at").orEmpty())},\"contextBefore\":${meta("context").orEmpty().ifEmpty { "{}" }},\"contextAfter\":${meta("context_after").orEmpty().ifEmpty { "{}" }},\"discoveryComplete\":${meta("discovery_complete")=="1"},\"sourceCount\":$sources,\"incidentCount\":$incidents,\"incidentsOmitted\":${(incidents-storedIncidents).coerceAtLeast(0)},\"incidents\":[")
        var comma=false
        db.rawQuery("SELECT message FROM incidents ORDER BY id",null).use { while(it.moveToNext()){if(comma)writer.append(',');comma=true;writer.append(q(it.getString(0))) } }
        writer.append("],\"coverage\":[");comma=false
        db.rawQuery("SELECT identity,path,state,detail FROM coverage ORDER BY id",null).use {while(it.moveToNext()){if(comma)writer.append(',');comma=true;writer.append("{\"identity\":${q(it.getString(0))},\"path\":${q(it.getString(1))},\"state\":${q(it.getString(2))},\"detail\":${q(it.getString(3))}}")}}
        writer.append("],\"mountsApp\":${meta("mounts_APP")?:"[]"},\"mountsShell\":${meta("mounts_SHELL")?:"[]"},\"storageProvenance\":${meta("storage_provenance")?:"{}"},\"exclusions\":${meta("exclusions")?:"[]"},\"volumes\":[");comma=false
        for(v in volumes()){if(comma)writer.append(',');comma=true;writer.append("{\"archive\":${q(v.name)},\"state\":${q(v.state)},\"bytes\":${v.bytes}}")}
        writer.append("],\"files\":[");comma=false
        forEachSource { s ->
            if(comma)writer.append(',');comma=true
            writer.append("{\"id\":${s.id},\"path\":${q(s.path)},\"canonicalPath\":${q(s.canonical)},\"appCanonicalPath\":${s.appCanonical?.let(::q)?:"null"},\"shellCanonicalPath\":${s.shellCanonical?.let(::q)?:"null"},\"size\":${s.size},\"packageOrigin\":${q(s.packageOrigin)},\"appPath\":${s.appPath?.let(::q)?:"null"},\"appSize\":${s.appSize?:"null"},\"appStamp\":${s.appStamp?.let(::q)?:"null"},\"shellPath\":${s.shellPath?.let(::q)?:"null"},\"shellSize\":${s.shellSize?:"null"},\"shellStamp\":${s.shellStamp?.let(::q)?:"null"},\"error\":${q(s.error)},\"origins\":[")
            // The preferred APP/SHELL path above is only the latest observation. Every alias
            // and positive package origin is retained separately and streamed here.
            var originComma=false
            db.rawQuery("SELECT identity,path,package_origin FROM origins WHERE source=? ORDER BY identity,path,package_origin",arrayOf(s.id.toString())).use { c -> while(c.moveToNext()) {
                if(originComma)writer.append(',');originComma=true
                writer.append("{\"identity\":${q(c.getString(0))},\"path\":${q(c.getString(1))},\"package\":${q(c.getString(2))}}")
            } }
            writer.append("],\"observations\":[")
            var observationComma=false
            db.rawQuery("SELECT identity,path,canonical,bytes,stamp,node FROM observations WHERE source=? ORDER BY identity,path,canonical,stamp",arrayOf(s.id.toString())).use { c->while(c.moveToNext()) {
                if(observationComma)writer.append(',');observationComma=true
                writer.append("{\"identity\":${q(c.getString(0))},\"path\":${q(c.getString(1))},\"canonical\":${q(c.getString(2))},\"bytes\":${c.getLong(3)},\"stamp\":${q(c.getString(4))},\"attestedNode\":${q(c.getString(5))}}")
            }}
            writer.append("],\"copies\":[")
            var cp=false
            db.rawQuery("SELECT c.source,c.volume,c.entry,c.bytes,c.sha256,c.success,c.error,c.identity,c.observed_ms,v.name,v.state FROM copies c JOIN volumes v ON c.volume=v.id WHERE c.source=? ORDER BY c.id",arrayOf(s.id.toString())).use { c -> while(c.moveToNext()) {
                if(cp)writer.append(',');cp=true
                val row=copy(c)
                writer.append("{\"archive\":${q(c.getString(9))},\"archiveState\":${q(c.getString(10))},\"entry\":${q(row.entry)},\"bytes\":${row.bytes},\"sha256\":${q(row.sha)},\"status\":${q(if(row.success)"exported" else "failed")},\"identity\":${q(row.identity)},\"observedAtMs\":${row.observedMs},\"error\":${q(row.error)}}")
            } }
            writer.append("]}")
        }
        writer.append("]}\n")
    }
    companion object {
        fun requireCompatibleResume(directory:File) {
            val file=File(directory,"firmware-ledger.sqlite")
            if(!file.isFile)throw IOException("Reprise refusée : journal inconnu ; démarrer une nouvelle collecte du stockage interne")
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db->
                fun read(key:String)=db.rawQuery("SELECT v FROM meta WHERE k=?",arrayOf(key)).use {if(it.moveToFirst())it.getString(0) else null}
                if(read("schema")!=FirmwarePolicy.SCHEMA.toString() || read("profile")!=FirmwarePolicy.PROFILE)throw IOException("Reprise refusée : périmètre de collecte différent. Archives conservées. Démarrer une nouvelle collecte du stockage interne.")
            }
        }
    }
    override fun close() = db.close()
}
internal class FirmwareBudget(message: String): IOException(message)
