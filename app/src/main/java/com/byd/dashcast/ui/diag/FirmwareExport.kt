package com.byd.dashcast.ui.diag

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A single serial worker owns discovery, the disk ledger, source reads and destination writes. */
internal class FirmwareExport(
    private val device: Device, private val directory: File, private val ledger: FirmwareLedger,
    private val cancelled: () -> Boolean, private val progress: (FullApkExport.Progress) -> Unit,
    private val volumeBytes: Long = 1024L*1024*1024, private val volumeEntries: Int = 2048,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val afterRename: () -> Unit = {},
    private val writes: FirmwareWriteGuard = device.outputGuard ?: FirmwareWriteGuard(device::validateDestination),
) {
    interface Device {
        val outputGuard: FirmwareWriteGuard? get() = null
        fun prepareDestination(path: String) {}
        fun context(): String
        fun discover(ledger: FirmwareLedger,check: () -> Unit,update: (String) -> Unit)
        fun open(source: FirmwareSource,identity: String): InputStream
        fun close() {}
        fun validateDestination() {}
    }
    private var state = FullApkExport.Progress("Préparation collecte large",kind=ExportKind.FIRMWARE_LARGE,directory=directory)
    private var zip: ZipOutputStream? = null
    private var raw: FileOutputStream? = null
    private var volume: FirmwareLedger.Volume? = null
    private var payload=0L
    private var entries=0
    private var completed=0
    private var failed=0
    private var copiedBytes=0L
    private var lastPublish=0L
    private var broken=false
    private var compatible=false
    private var lastSpaceCheck=0L
    private var storageFailure:FirmwareStorage?=null
    private val archives=ArrayList<File>()
    private fun update(stage: String=state.stage,current: String=state.current,force: Boolean=true) {
        state=state.copy(stage=stage,current=current,discovered=ledger.sources,completed=completed,failed=failed,
            bytes=copiedBytes,lastProgressMs=FullApkExport.monotonicMs())
        val now=FullApkExport.monotonicMs()
        if(force || now-lastPublish>=250){ lastPublish=now;progress(state) }
    }
    private fun check() {
        writes.check()
        if(cancelled())throw CancellationException("Annulation demandée ; volumes finalisés conservés")
        writes.periodic()
        storageFailure?.let {throw it}
        val now=FullApkExport.monotonicMs()
        if(now-lastSpaceCheck>=1000){lastSpaceCheck=now;try{space()}catch(e:FirmwareStorage){storageFailure=e;throw e}}
    }
    private fun space() {
        val free=freeBytes(directory)
        if(free<512L*1024*1024+FirmwarePolicy.BUFFER) throw FirmwareStorage("Réserve de stockage 512 MiB atteinte ou espace inconnu")
    }
    fun run(): FullApkExport.Progress {
        var outcome="failed";var message=""
        try {
            ledger.protect(writes)
            require(volumeEntries in 1..4095)
            space();check()
            val schema=ledger.meta("schema")
            if(schema!=null)check(schema==FirmwarePolicy.SCHEMA.toString() && ledger.meta("profile")==FirmwarePolicy.PROFILE) {
                "Reprise refusée : périmètre de collecte différent. Archives conservées. Démarrer une nouvelle collecte du stockage interne."
            }
            val context=device.context()
            if(schema!=null) {
                check(schema==FirmwarePolicy.SCHEMA.toString() && ledger.meta("profile")==FirmwarePolicy.PROFILE) { "Reprise refusée : périmètre de collecte différent. Archives conservées. Démarrer une nouvelle collecte du stockage interne." }
                check(ledger.meta("context")==context) { "Reprise refusée : contexte ROM/identité différent ; créer une nouvelle session" }
            } else {
                ledger.initialize(context)
            }
            compatible=true
            verifyVolumes()
            completed=ledger.successful();copiedBytes=ledger.totalBytes()
            if(ledger.meta("discovery_revision")!=FirmwareDiscoverySeeds.REVISION) {
                ledger.restartDiscovery();ledger.meta("discovery_revision",FirmwareDiscoverySeeds.REVISION)
            }
            if(ledger.meta("discovery_complete")!="1") {
                update("Inventaire sur disque — daemon SHELL")
                try {
                    device.discover(ledger,::check) { update("Inventaire sur disque — daemon SHELL",it,false) }
                    ledger.meta("discovery_complete",if(ledger.discoveryComplete())"1" else "limited")
                } catch(e: FirmwareBudget) { ledger.issue(e.message.orEmpty());ledger.meta("discovery_complete","limited") }
            }
            update("Copie des fichiers accessibles — ZIP indépendants")
            writeManifest("running","")
            ledger.forEachSource { source ->
                check()
                if(!ledger.copied(source.id)) copy(source)
            }
            finishVolume()
            check()
            val after=device.context();ledger.meta("context_after",after)
            if(after!=context) ledger.issue("Contexte final différent du contexte initial ; aucune atomicité de la collecte garantie")
            outcome=if(ledger.incidents>0 || failed>0 || completed<ledger.sources || ledger.sources==0 || ledger.meta("discovery_complete")!="1")"partial" else "finished"
            message=if(ledger.sources==0)"Collecte vide : aucun fichier accessible inventorié, aucun ZIP de données. Refus et limites dans manifest.json et le rapport de bug."
                else "Collecte terminée : $completed/${ledger.sources} fichiers, $failed échecs, ${archives.size} ZIP ; résultat $outcome. Sources live non atomiques ; couverture limitée, consulter manifest.json."
        } catch(e: CancellationException) {outcome="cancelled";message=e.message.orEmpty()}
        catch(e:FirmwareBudget){outcome="partial";message=e.message.orEmpty();runCatching {ledger.issue(message)}}
        catch(e: Exception) { message="${e.javaClass.simpleName}: ${e.message}";runCatching {ledger.issue(message)} }
        finally {
            if(zip!=null && !broken && !writes.refused) {
                // Cancellation/read errors can still finalize earlier complete entries. A destination
                // failure leaves only .part and no source in that volume can be skipped on resume.
                try { finishVolume() } catch(e:Exception) {
                    abortVolume()
                    volume?.let { runCatching {ledger.volumeState(it.id,"incomplete")} }
                    message+="; volume incomplet : ${e.message}"
                }
            }
            if(broken || writes.refused){abortVolume();volume?.let {runCatching {ledger.volumeState(it.id,"incomplete")}}}
            runCatching {device.close()}.onFailure {message+="; nettoyage temporaire : ${it.message}";outcome="partial"}
            completed=ledger.successful()
            if(compatible || ledger.meta("schema")==null)runCatching {writeManifest(outcome,message)}.onFailure {message+="; manifeste indisponible : ${it.message}";outcome="failed"}
            if(compatible || ledger.meta("schema")==null)runCatching {
                writes.validate()
                File(directory,"RESULT.txt").writeText("FIRMWARE_LARGE $outcome\n$message\nReprendre la même session vérifie les ZIP avant de sauter les fichiers. Les .part sont incomplets.\n")
            }.onFailure { message+="; résultat conservé dans DIAG : ${it.message}" }
            if(writes.refused)outcome="failed"
            state=state.copy(stage=message,current="",running=false,outcome=outcome,discovered=ledger.sources,completed=completed,failed=failed,
                bytes=copiedBytes,archives=archives.toList(),lastProgressMs=FullApkExport.monotonicMs())
            runCatching {FirmwareDiagnostics.terminal(state,ledger.diagnosticSummary(),ledger.diagnosticIssues())}
            progress(state)
        }
        return state
    }
    private fun abortVolume() {
        // Closing the ZIP or its buffer would emit a footer/flush queued payload.
        // Release only the already-open raw descriptor after a known output refusal.
        runCatching {raw?.close()};raw=null
        (zip as? AbortableZip)?.abort();zip=null
    }
    private class AbortableZip(output:OutputStream):ZipOutputStream(output) {
        fun abort() { def.end() }
    }
    private fun verifyVolumes() {
        for(v in ledger.volumes()) {
            check();update("Vérification en flux des volumes existants",v.name);space()
            val file=File(directory,v.name)
            if(!file.isFile) {
                if(v.state=="verified" || v.state=="final")ledger.issue("Volume finalisé manquant : ${v.name}")
                ledger.volumeState(v.id,"incomplete");continue
            }
            try {
                val records=ledger.copies(v.id)
                check(records.isNotEmpty() && records.size<=volumeEntries) {"Nombre d'entrées journalisées invalide"}
                val expected=records.associateBy {it.entry}
                check(expected.size==records.size) {"Entrée dupliquée dans le journal"}
                val seen=HashSet<String>()
                ZipFile(file).use { z ->
                    val enumeration=z.entries()
                    while(enumeration.hasMoreElements()) {
                        check();val entry=enumeration.nextElement()
                        check(seen.add(entry.name)) {"Entrée ZIP dupliquée"}
                        if(entry.name=="manifest-volume.json") {
                            val manifest=volumeManifestFingerprint(records)
                            check(!entry.isDirectory && entry.size==manifest.first && entry.size in 1..(2L*1024*1024)) {"Taille des métadonnées volume différente du journal"}
                            val digest=MessageDigest.getInstance("SHA-256");var count=0L
                            z.getInputStream(entry).use { input ->
                                val buffer=ByteArray(FirmwarePolicy.BUFFER)
                                while(true){check();val n=input.read(buffer);if(n<0)break;if(n==0)throw IOException("Lecture sans progrès")
                                    count+=n;check(count<=manifest.first){"Métadonnées excessives"};digest.update(buffer,0,n)
                                }
                            }
                            check(count==manifest.first && hex(digest.digest())==manifest.second){"SHA-256 des métadonnées volume différent du journal"}
                            continue
                        }
                        val row=expected[entry.name]?:throw IOException("Entrée ZIP non journalisée")
                        check(!entry.isDirectory && entry.size==row.bytes){"Taille ZIP différente du journal"}
                        val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
                        z.getInputStream(entry).use { input ->
                            val buffer=ByteArray(FirmwarePolicy.BUFFER)
                            while(true){check();val n=input.read(buffer);if(n<0)break;if(n==0)throw IOException("Lecture sans progrès")
                                bytes+=n;if(bytes>row.bytes)throw IOException("Entrée ZIP trop longue");digest.update(buffer,0,n)
                                update("Vérification en flux des volumes existants","${v.name} · ${bytes/1048576} MiB vérifiés",false)}
                        }
                        check(bytes==row.bytes && hex(digest.digest())==row.sha){"SHA-256 ZIP différent du journal"}
                    }
                }
                check(seen==expected.keys+"manifest-volume.json") {"Entrée ZIP supprimée ou métadonnées absentes"}
                ledger.volumeState(v.id,"verified",file.length());archives.add(file);state=state.copy(archives=archives.toList())
            } catch(e:CancellationException){throw e}
            catch(e:FirmwareOutputRefused){throw e}
            catch(e:FirmwareStorage){throw e}
            catch(e:FirmwareBudget){throw e}
            catch(e:android.database.sqlite.SQLiteException){throw e}
            catch(e:Exception){ledger.volumeState(v.id,"corrupt",file.length());ledger.issue("Volume conservé mais invalide ${v.name}: ${e.message}; les sources seront recopiées dans un nouveau volume")}
        }
    }
    private fun copy(source: FirmwareSource) {
        update("Copie des fichiers accessibles — ZIP indépendants",source.path)
        var input: InputStream?=null;var identity="";val errors=ArrayList<String>()
        for(candidate in listOf("SHELL")) {
            if((candidate=="APP" && source.appPath==null)||(candidate=="SHELL" && source.shellPath==null))continue
            check()
            try {input=device.open(source,candidate);identity=candidate;break}
            catch(e:CancellationException){throw e}
            catch(e:FirmwareOutputRefused){throw e}
            catch(e:Exception){errors.add("$candidate: ${e.message}");ledger.issue("Ouverture refusée/changée ${source.path}: ${errors.last()}")}
        }
        if(input==null){failed++;ledger.sourceError(source.id,errors.joinToString("; "));update();return}
        val expected=if(identity=="APP")source.appSize!! else source.shellSize!!
        try { input.use { stream ->
            check();space()
            if(zip!=null && (entries>=volumeEntries || (entries>0 && payload+expected>volumeBytes)))finishVolume()
            if(zip==null)startVolume()
            val entry="firmware/${source.id.toString().padStart(6,'0')}/${FirmwarePolicy.safeName(source.path)}"
            zip!!.putNextEntry(ZipEntry(entry));entries++
            val digest=MessageDigest.getInstance("SHA-256");var bytes=0L;var error="";var success=false
            var cancellation: CancellationException?=null
            try {
                val buffer=ByteArray(FirmwarePolicy.BUFFER);var nextSpace=0L
                while(true) {
                    check();val n=stream.read(buffer)
                    if(n<0)break
                    if(n==0)throw IOException("Lecture source sans progrès")
                    if(bytes+n>expected)throw IOException("Source agrandie pendant la copie")
                    if(bytes>=nextSpace){try{space()}catch(e:Exception){broken=true;throw FirmwareDestinationFailure("Réserve de stockage atteinte",e)};nextSpace=bytes+4L*1024*1024}
                    try{zip!!.write(buffer,0,n)}catch(e:Exception){broken=true;throw FirmwareDestinationFailure("Écriture ZIP échouée",e)}
                    digest.update(buffer,0,n);bytes+=n;payload+=n;copiedBytes+=n
                    update(current=source.path,force=false)
                }
                if(bytes!=expected)throw IOException("Taille source changée : $bytes/$expected")
                success=true
            } catch(e:CancellationException){error=e.message.orEmpty();cancellation=e}
            catch(e:FirmwareOutputRefused){throw e}
            catch(e:FirmwareDestinationFailure){throw e}
            catch(e:Exception){error="${e.javaClass.simpleName}: ${e.message}"}
            // A failed entry is explicitly journaled and remains unusable as a successful file.
            writes.check();zip!!.closeEntry()
            ledger.batch {
                ledger.copy(FirmwareLedger.Copy(source.id,volume!!.id,entry,bytes,hex(digest.digest()),success,error,identity,System.currentTimeMillis()))
                if(success){ledger.sourceError(source.id,"")}else{ledger.sourceError(source.id,error);ledger.issue("Copie incomplète ${source.path}: $error")}
            }
            if(success)completed++ else failed++
            update()
            if(cancellation!=null)throw cancellation
            if(payload>=volumeBytes || entries>=volumeEntries)finishVolume()
        } } catch(e:CancellationException){throw e}
    }
    private fun startVolume() {
        writes.validate();space();val v=ledger.newVolume();volume=v
        val target=File(directory,v.name+".part")
        writes.validate()
        raw=FileOutputStream(target);zip=AbortableZip(writes.stream(raw!!.buffered(FirmwarePolicy.BUFFER))).apply{setLevel(0)}
        entries=0;payload=0
    }
    private fun finishVolume() {
        val output=zip?:return;val v=volume!!
        update("Fermeture du ZIP — ne pas interrompre",v.name);writes.validate();space()
        val records=ledger.copies(v.id)
        check(records.size==entries){"Journal de volume incomplet ; .part conservé"}
        output.putNextEntry(ZipEntry("manifest-volume.json"))
        writeVolumeManifest(output,records)
        output.closeEntry();output.finish();output.flush();raw!!.fd.sync();output.close();zip=null;raw=null
        space()
        val part=File(directory,v.name+".part");val final=File(directory,v.name)
        writes.validate()
        check(!final.exists() && part.renameTo(final)){"Impossible de finaliser le ZIP sans écrasement"}
        afterRename() // Durable intent allows a future run to reconcile rename-before-checkpoint.
        writes.validate()
        ledger.volumeState(v.id,"verified",final.length());archives.add(final);state=state.copy(archives=archives.toList());volume=null
        entries=0;payload=0
        update("Copie des fichiers accessibles — ZIP indépendants")
    }
    /** The same deterministic UTF-8 stream is used when writing a volume and when predicting
     * its metadata on resume. No whole JSON string/byte array or new journal schema is needed. */
    private fun writeVolumeManifest(output:OutputStream,records:List<FirmwareLedger.Copy>) {
        val writer=output.writer(Charsets.UTF_8)
        writer.append("{\"schema\":${FirmwarePolicy.SCHEMA},\"profile\":${FirmwarePolicy.json(FirmwarePolicy.PROFILE)},\"sessionManifest\":\"manifest.json outside ZIP contains final coverage\",\"files\":[")
        records.forEachIndexed { i,c ->
            if(i>0)writer.append(',')
            writer.append("{\"sourceId\":${c.source},\"entry\":${FirmwarePolicy.json(c.entry)},\"bytes\":${c.bytes},\"sha256\":${FirmwarePolicy.json(c.sha)},\"status\":${FirmwarePolicy.json(if(c.success)"exported" else "failed")}}")
        }
        writer.append("]}\n");writer.flush()
    }
    private fun volumeManifestFingerprint(records:List<FirmwareLedger.Copy>):Pair<Long,String> {
        val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
        val sink=object:OutputStream(){
            override fun write(value:Int){digest.update(value.toByte());bytes++}
            override fun write(buffer:ByteArray,offset:Int,length:Int){digest.update(buffer,offset,length);bytes+=length}
        }
        writeVolumeManifest(sink,records)
        return bytes to hex(digest.digest())
    }
    private fun writeManifest(outcome:String,message:String) {
        writes.validate()
        val temporary=File(directory,"manifest.json.tmp")
        FileOutputStream(temporary).use { stream -> val output=writes.stream(stream).bufferedWriter()
            val writer=object:java.io.FilterWriter(output){
                private var chars=0
                override fun write(buffer:CharArray,offset:Int,length:Int){super.write(buffer,offset,length);chars+=length;if(chars>=256*1024){space();chars=0}}
                override fun write(text:String,offset:Int,length:Int){super.write(text,offset,length);chars+=length;if(chars>=256*1024){space();chars=0}}
            }
            ledger.manifest(writer,outcome,message);writer.flush();stream.fd.sync() }
        writes.validate()
        if(!temporary.renameTo(File(directory,"manifest.json")))throw IOException("Impossible de finaliser manifest.json")
    }
    private class FirmwareStorage(message:String):IOException(message)
    private class FirmwareDestinationFailure(message:String,cause:Throwable): IOException(message,cause)
    companion object { fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)} }
}
