package com.byd.dashcast.ui.diag

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.daemon.StrictFileRead
import com.byd.dashcast.proxy.daemon.StorageMetadata
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/** No executor, timed Future, reconnect or prefetch: the physical synchronous Binder call owns
 * the worker and shared DIAG gate until it actually returns, even after the UI says it is late. */
internal class FirmwareSerialGate {
    @Volatile var pendingSince = 0L; private set
    private val active=java.util.concurrent.atomic.AtomicBoolean(false)
    fun <T> call(block: () -> T): T {
        check(active.compareAndSet(false,true)) {"A physical firmware transaction is already outstanding"}
        pendingSince=FullApkExport.monotonicMs()
        try{return block()}finally{pendingSince=0L;active.set(false)}
    }
}

internal class FirmwareDevice(private val context: Context, private val cancelled: () -> Boolean = {false},
    private val endpoint: () -> IBinder? = {ProxyClient.getProxyDaemonBinder()},
    private val observeOutput:(String,List<String>,FirmwareStorage)->String = FirmwareOutput::observe,
    private val connect: (Context) -> Boolean = {ProxyClient.connect(it)}) : FirmwareExport.Device {
    override val outputGuard=FirmwareWriteGuard { validateDestinationState() }
    private val gate=FirmwareSerialGate()
    private var initialized=false
    private var binder: IBinder?=null
    private var shellProblem=""
    private var shellMounts:FirmwareMounts?=null
    private var protocol="unavailable"
    private val exclusions=listOf(context.applicationInfo.dataDir,
        "/storage/emulated/${android.os.Process.myUid()/100000}/Android/data/${context.packageName}",
        "/data/media/${android.os.Process.myUid()/100000}/Android/data/${context.packageName}")
    private var preparedContext:String?=null
    private var destination:String?=null
    private var appOutputView:String?=null
    private var pinnedOutputMounts:FirmwareMounts?=null
    private var temporaryParent:String?=null
    private val temporary="/data/local/tmp/dashcast-firmware-${java.util.UUID.randomUUID()}"
    private var temporaryReady=false
    private var shellSnapshotsBlocked=false
    private var updateDiscovery:((String)->Unit)?=null
    private fun init() {
        if(initialized)return
        initialized=true
        val ready=try{gate.call{connect(context)}}catch(e:Exception){shellProblem="Connexion existante indisponible : ${e.message}";false}
        if(cancelled())throw CancellationException("Annulation après connexion ; aucun appel suivant")
        if(ready) {
            binder=endpoint()
            try {negotiate()}catch(e:CancellationException){throw e}catch(e:Exception){shellProblem="SHELL stricte indisponible : ${e.message}";binder=null}
        }
        if(cancelled())throw CancellationException("Annulation après négociation ; aucun appel suivant")
        if(binder==null && shellProblem.isEmpty())shellProblem="ProxyDaemon indisponible ; couverture SHELL inconnue"
        if(binder==null)throw IOException(shellProblem)
    }

    override fun context(): String {
        preparedContext?.let {preparedContext=null;return it}
        init()
        val values=sortedMapOf("fingerprint" to Build.FINGERPRINT,"device" to Build.DEVICE,"product" to Build.PRODUCT,
            "display" to Build.DISPLAY,"api" to Build.VERSION.SDK_INT.toString(),"appUid" to android.os.Process.myUid().toString(),
            "shellUid" to if(binder==null)"unavailable" else "2000","profile" to FirmwarePolicy.PROFILE)
        if(binder!=null) {
            for(key in listOf("ro.build.fingerprint","ro.build.version.incremental","ro.build.region","sys.byd.countrycode")) {
                values["shell:$key"]=metadata(StorageMetadata.PROPERTY,key).trim()
            }
        }
        shellMounts=readMounts()
        values["shellMounts"]=shellMounts!!.fingerprint()
        values["shellProtocol"]=protocol
        return values.entries.joinToString(",","{","}"){FirmwarePolicy.json(it.key)+":"+FirmwarePolicy.json(it.value)}
    }
    override fun discover(ledger:FirmwareLedger,check:()->Unit,update:(String)->Unit) {
        init();check();updateDiscovery=update
        ledger.coverage("SCOPE","mountinfo","declared","Ordinary accessible files on attested INTERNAL storage only in the SHELL daemon namespace (UID2000), independent of extension. SD, USB, adopted and unknown storage excluded before inventory and reads, including aliases. Internal firmware/data/emulated storage retained; no APP source scan/fallback, live non-atomic, no partition image.")
        ledger.meta("exclusions",exclusions.joinToString(",","[","]",transform=FirmwarePolicy::json))
        if(shellProblem.isNotEmpty())ledger.issue(shellProblem)
        for(identity in listOf("SHELL")) {
            check()
            val table=shellMounts
            if(table==null){ledger.coverage(identity,"/","unavailable",shellProblem);continue}
            ledger.meta("mounts_$identity",table.json())
            ledger.meta("storage_provenance",table.storageProof.json())
            for(issue in table.storageProof.issues)ledger.issue(issue)
            for(mount in table.entries) {
                check();val reason=table.decision(mount.point)
                ledger.coverage(identity,mount.point,if(reason==null)"mount_admitted" else "mount_excluded",reason?:"${mount.type}; ${mount.device}; root=${mount.root}")
                if(reason?.startsWith("unrecognised/non-local")==true || reason?.startsWith("ambiguous")==true || reason?.startsWith("unknown storage")==true)ledger.issue("Montage non inventorié : ${mount.point} : $reason")
                if(reason==null && table.decision(mount.point)==null)ledger.enqueue(identity,mount.point)
            }
        }
        while(true) {
                check();ledger.budget()
                val dir=ledger.nextDirectory()?:break
                update("${dir.identity} ${dir.path}")
                try {scanShell(dir,ledger,check)}
                catch(e:CancellationException){throw e}
                catch(e:FirmwareBudget){throw e}
                catch(e:Exception){ledger.issue("${dir.identity} inventaire ${dir.path} : ${e.message}");ledger.coverage(dir.identity,dir.path,"partial",e.message.orEmpty());ledger.advance(dir,dir.offset,true)}
            }
    }
    override fun close() {
        updateDiscovery=null;preparedContext=null
        outputGuard.check()
        if(temporaryReady) {
            if(!removeTemporary(temporary,true))throw IOException("Snapshot temporaire conservé : $temporary")
            temporaryReady=false
        }
    }
    private fun table(identity:String):FirmwareMounts {if(identity!="SHELL")throw IOException("Sources APP interdites pour ce profil");return shellMounts?:throw IOException("Namespace SHELL non établi")}
    private fun admitted(path:String,canonical:String,identity:String) {
        val table=table(identity)
        if(!table.accepted(path,canonical))throw IOException("Montage/exclusion $identity : ${table.decision(path)?:table.decision(canonical)}")
    }
    private fun temporary() {
        if(temporaryReady)return
        outputGuard.validate()
        attestTemporary(temporary)
        val ready=exec("umask 077; mkdir ${ApkInventoryShell.quote(temporary)}")
        if(ready.first!=0)throw IOException("Répertoire de snapshots SHELL indisponible")
        temporaryReady=true
    }
    private fun readMounts():FirmwareMounts {
        // Bootstrap is direct metadata only: do not create a snapshot on an unproven medium.
        FirmwareDiagnostics.phase("Montages SHELL")
        val bytes=metadata(StorageMetadata.MOUNTINFO).toByteArray(Charsets.UTF_8)
        if(bytes.size>196608)throw IOException("Mountinfo absent/excessif ; aucun temporaire créé")
        val raw=FirmwareMounts.parse(bytes.inputStream(),exclusions)
        return raw.withStorage(readStorage(raw)).also {
            FirmwareDiagnostics.record(FirmwareDiagnostics.Field.PROVENANCE,it.json())
        }
    }
    private fun readStorage(mounts:FirmwareMounts):FirmwareStorage {
        val issues=ArrayList<String>()
        fun field(operation:Int,label:String,maxBytes:Int):String? {
            FirmwareDiagnostics.phase("Métadonnées SHELL : $label")
            return try {val value=metadata(operation);if(value.toByteArray(Charsets.UTF_8).size>maxBytes)throw IOException("excessif");value.trimEnd('\n','\r')}
            catch(e:CancellationException){throw e}catch(e:Exception){
                issues.add("Métadonnées $label indisponibles/excessives : ${FirmwareDiagnostics.bounded("${e.javaClass.simpleName}: ${e.message}",1024)} ; provenance inconnue")
                FirmwareDiagnostics.record(FirmwareDiagnostics.Field.METADATA_ISSUES,issues.joinToString("\n"));null
            }
        }
        val rawPrimary=field(StorageMetadata.PRIMARY_UUID,"primary storage UUID",4096)
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.PRIMARY_UUID,if(rawPrimary==null)"getter unavailable; see metadata issues" else "getter succeeded; value=$rawPrimary")
        val primary=rawPrimary?.takeIf {it=="null" || it.matches(Regex("[A-Za-z0-9_-]+"))}
        val volumes=field(StorageMetadata.VOLUMES,"storage volumes",64*1024)
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.VOLUMES,if(volumes==null)"getter unavailable; see metadata issues" else "getter succeeded:\n$volumes")
        val rawBoot=field(StorageMetadata.BOOT_DEVICE,"boot controller",4096)
        val boot=rawBoot.orEmpty()
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.BOOT_DEVICE,if(rawBoot==null)"getter unavailable; see metadata issues" else "getter succeeded; value=$rawBoot")
        val nodes=LinkedHashMap<String,FirmwareStorage.Block>()
        val pending=java.util.ArrayDeque<Pair<String,Int>>()
        mounts.entries.filter {it.device.substringBefore(':').toLongOrNull()?.let {major->major>0}==true &&
            !FirmwareStorage.externalPath(it.point) && !FirmwareStorage.externalSource(it.source)}.map{it.device}.distinct().forEach{pending.add(it to 0)}
        while(pending.isNotEmpty()) {
            if(cancelled())throw CancellationException("Annulation pendant provenance stockage")
            val (device,depth)=pending.removeFirst()
            if(device in nodes)continue
            if(nodes.size>=FirmwareStorage.MAX_BLOCKS){issues.add("Limite ${FirmwareStorage.MAX_BLOCKS} métadonnées blocs ; supports restants inconnus");break}
            if(depth>FirmwareStorage.MAX_DEPTH){issues.add("Profondeur blocs limitée : $device");continue}
            try {
                FirmwareDiagnostics.phase("Provenance bloc SHELL $device")
                val f=FirmwarePolicy.boundedLines(metadata(StorageMetadata.BLOCK,device)).single().split('|')
                if(f.size!=7 || f[0]!="BLOCK" || f[1]!=device)throw IOException("Champs blocs invalides")
                val path=FirmwarePolicy.decoded(f[2]);val rawBacking=FirmwarePolicy.decoded(f[6])
                val backing=if(rawBacking.isEmpty())"" else if(rawBacking.endsWith('\n'))rawBacking.removeSuffix("\n") else throw IOException("Attribut loop tronqué")
                val dependencies=if(f[5].isEmpty())emptyList() else f[5].split(',')
                if(!path.startsWith("/sys/devices/") || path.length>4096 || dependencies.size>16 || dependencies.any{!it.matches(Regex("[0-9]+:[0-9]+"))} || f[3] !in setOf("","0","1") || f[4].length>32 || backing.length>4096)throw IOException("Provenance blocs invalide")
                nodes[device]=FirmwareStorage.Block(device,path,f[3],f[4],dependencies,backing)
                dependencies.forEach{pending.add(it to depth+1)}
            }catch(e:CancellationException){throw e}catch(e:Exception){nodes[device]=FirmwareStorage.Block(device,"","","");issues.add("Provenance bloc inconnue $device : ${FirmwareDiagnostics.bounded("${e.javaClass.simpleName}: ${e.message}",1024)}")}
        }
        val storage=FirmwareStorage(nodes,primary,volumes,boot,issues)
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.BLOCKS,nodes.values.joinToString("\n"))
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.METADATA_ISSUES,issues.joinToString("\n"))
        return storage
    }
    /** Own output path metadata only. Resolve the nearest existing ancestor without creating it. */
    private fun attestInternalPath(path:String):String {
        FirmwareDiagnostics.phase("Vérification sortie/temporaire SHELL",path)
        if(!FirmwarePolicy.sane(path))throw IOException("Chemin destination invalide")
        val f=metadata(StorageMetadata.OUTPUT,path,cleanup=true).trimEnd('\n').split('|')
        if(f.size!=2)throw IOException("Métadonnées destination invalides")
        val real=FirmwarePolicy.decoded(f[0]);val device=f[1].toLongOrNull()?:throw IOException("Périphérique destination inconnu")
        val table=table("SHELL")
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.CANONICAL,real)
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.ACTUAL_DEVICE,device.toString())
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.SHELL_OUTPUT_MOUNTS,FirmwareDiagnostics.mounts(table,listOf(path,real,"/data/local/tmp","/data/media","/data")))
        val reason=table.storageDecision(path)?:table.storageDecision(real)
        if(reason!=null || !table.deviceMatches(real,device))throw IOException("Stockage interne non établi : ${reason?:"périphérique observé différent du montage"}")
        return real
    }
    override fun prepareDestination(path:String) = outputGuard.attest {
        FirmwareDiagnostics.phase("Préparation de la destination",path)
        try {
            val context=context()
            attestInternalPath(path);attestAppOutput(path)
            // All later writes, including the NUL inventory snapshots, require an internal temp FS.
            temporaryParent=attestInternalPath("/data/local/tmp")
            preparedContext=context;destination=path;pinnedOutputMounts=table("SHELL")
            FirmwareDiagnostics.ready()
        } catch(e:Exception) {if(e !is CancellationException)FirmwareDiagnostics.failure(e);throw e}
    }
    private fun attestAppOutput(path:String) {
        FirmwareDiagnostics.phase("Vérification de la sortie APP",path)
        val view=observeOutput(path,exclusions,table("SHELL").storageProof)
        if(appOutputView!=null && view!=appOutputView)throw IOException("Vue sortie APP changée pendant finalisation ; aucune nouvelle provenance admise")
        appOutputView=view
    }
    override fun validateDestination() = outputGuard.validate()
    private fun validateDestinationState(extra:String?=null) {
      try {
        val path=destination?:throw IOException("Destination non préparée")
        FirmwareDiagnostics.phase("Revalidation des montages de sortie",extra?:path)
        // Retain the initial physical proofs in both namespaces. Volatile mount IDs may
        // change; a covering device/root/point/type/source tuple may never be replaced.
        val previous=pinnedOutputMounts?:throw IOException("Preuve sortie absente")
        val bytes=metadata(StorageMetadata.MOUNTINFO,cleanup=true).toByteArray(Charsets.UTF_8)
        val fresh=FirmwareMounts.parse(bytes.inputStream(),exclusions,previous.storageProof)
        FirmwareDiagnostics.record(FirmwareDiagnostics.Field.SHELL_OUTPUT_MOUNTS,FirmwareDiagnostics.mounts(fresh,listOfNotNull(path,"/data/local/tmp",extra)))
        for(target in listOfNotNull(path,"/data/local/tmp",extra)) {
            val before=previous.mount(target);val after=fresh.mount(target)
            if(before==null || after==null || listOf(before.device,before.root,before.point,before.type,before.source)!=listOf(after.device,after.root,after.point,after.type,after.source))throw IOException("Vue destination changée pendant finalisation ; provenance nouvelle refusée")
        }
        shellMounts=fresh
        attestInternalPath(path);attestAppOutput(path);attestInternalPath("/data/local/tmp")
      } catch(e:Exception) {if(e !is CancellationException)FirmwareDiagnostics.failure(e);throw e}
    }
    /** Exact generated temp path, fresh covering view, canonical path and actual device.
     * A missing target is not removed. Cancellation permits only these bounded checks and
     * the ensuing closed cleanup command; a terminal refusal permits no Binder call. */
    private fun attestTemporary(path:String):Boolean {
        var exists=false
        outputGuard.attest {
            validateDestinationState(path)
            val real=attestInternalPath(path)
            val parent=temporaryParent?:throw IOException("Preuve temporaire absente")
            val expected=parent+temporary.removePrefix("/data/local/tmp")+path.removePrefix(temporary)
            if(real==expected)exists=true
            else if(!expected.startsWith(real.trimEnd('/')+"/"))throw IOException("Chemin temporaire canonique changé ; suppression refusée")
        }
        return exists
    }
    private fun removeTemporary(path:String,directory:Boolean):Boolean {
        if(!attestTemporary(path))return true
        // Never recursively descend into a newly mounted child or an unknown leftover.
        // Known snapshots are removed individually; a nonempty temp directory is retained.
        val command=if(directory)"rmdir" else "rm -f"
        return exec("$command ${ApkInventoryShell.quote(path)} && [ ! -e ${ApkInventoryShell.quote(path)} ]",true).first==0
    }
    private fun metadata(operation:Int,argument:String="",cleanup:Boolean=false):String {
        outputGuard.check()
        if(!cleanup && cancelled())throw CancellationException("Annulation avant métadonnées")
        return gate.call {
            val b=binder?:throw IOException("Daemon metadata absent")
            val data=Parcel.obtain();val reply=Parcel.obtain()
            try {
                data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);data.writeInt(operation);data.writeByteArray(argument.toByteArray(Charsets.UTF_8))
                if(!b.transact(ProxyDaemonContract.TXN_STORAGE_METADATA,data,reply,0))throw IOException("Capacité metadata absente")
                reply.readException()
                if(reply.dataAvail()<4)throw IOException("Réponse metadata tronquée")
                val position=reply.dataPosition();val length=reply.readInt()
                if(length !in 0..StorageMetadata.MAX_BYTES || reply.dataAvail()!=((length+3)/4)*4)throw IOException("Réponse metadata mal cadrée/excessive")
                reply.setDataPosition(position)
                val response=reply.createByteArray()?:throw IOException("Réponse metadata nulle")
                val value=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(response)).toString()
                if(reply.dataAvail()!=0 || value.toByteArray(Charsets.UTF_8).size>StorageMetadata.MAX_BYTES)throw IOException("Réponse metadata excessive")
                value
            }finally{data.recycle();reply.recycle()}
        }
    }
    private fun rawStream(path:String,expected:StrictFileRead.Expected):InputStream=object:InputStream(){
        private var offset=0L;private var ended=false
        override fun read():Int {val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt()and 255}
        override fun read(b:ByteArray,off:Int,len:Int):Int {
            if(len==0)return 0
            if(ended)return -1
            val bytes=strictChunk(path,offset,minOf(len,FirmwarePolicy.BUFFER),expected)
            if(bytes.isEmpty()){if(offset!=expected.size)throw IOException("Snapshot court");ended=true;return -1}
            if(bytes.size>len || bytes.size.toLong()>expected.size-offset)throw IOException("Snapshot incohérent")
            bytes.copyInto(b,off);offset+=bytes.size;return bytes.size
        }
    }
    private fun negotiate() {
        gate.call {
            val b=binder?:throw IOException("Binder indisponible")
            val data=Parcel.obtain();val reply=Parcel.obtain()
            try {
                data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
                if(!b.transact(ProxyDaemonContract.TXN_WHOAMI,data,reply,0))throw IOException("WHOAMI non traité")
                reply.readException();val uid=reply.readInt();val pid=reply.readInt();protocol=reply.readString().orEmpty()
                val instance=reply.readString().orEmpty()
                FirmwareDiagnostics.record(FirmwareDiagnostics.Field.DAEMON,"WHOAMI uid=$uid; pid=$pid; protocol=$protocol; capabilities not yet observed")
                if(uid!=2000 || pid<=0 || !instance.matches(Regex("[0-9a-f]{32}")) || reply.dataAvail()!=0 || (protocol.toIntOrNull()?:0)<26)throw IOException("Identité/protocole daemon incompatible : $uid/$protocol")
            }finally{reply.recycle();data.recycle()}
        }
        if(cancelled())throw CancellationException("Annulation après négociation")
        gate.call {
            val data=Parcel.obtain();val reply=Parcel.obtain()
            try {
                data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
                if(!binder!!.transact(ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES,data,reply,0))throw IOException("Lecture stricte non annoncée")
                reply.readException();if(reply.dataAvail()!=8)throw IOException("Réponse capacités mal cadrée");val schema=reply.readInt();val flags=reply.readInt()
                FirmwareDiagnostics.record(FirmwareDiagnostics.Field.DAEMON,"WHOAMI uid=2000; protocol=$protocol; capabilitySchema=$schema; capabilityFlags=$flags; instance nonce omitted")
                if(schema!=ProxyDaemonContract.STRICT_FILE_SCHEMA || flags and (ProxyDaemonContract.CAP_STRICT_FILE_READ or ProxyDaemonContract.CAP_STORAGE_METADATA)!=(ProxyDaemonContract.CAP_STRICT_FILE_READ or ProxyDaemonContract.CAP_STORAGE_METADATA))throw IOException("Schéma/capacité stricte incompatible")
            }finally{reply.recycle();data.recycle()}
        }
    }
    private fun scanShell(dir:FirmwareDirectory,ledger:FirmwareLedger,check:()->Unit) {
        if(!temporaryReady)temporary()
        if(shellSnapshotsBlocked)throw IOException("Snapshot SHELL indisponible ou nettoyage précédent non confirmé ; aucun nouveau snapshot")
        if(table("SHELL").entries.any{it.point==dir.path}) {
            val node=exec(FirmwareShell.batch(listOf(dir.path)))
            if(node.first!=0)throw IOException("Type du montage SHELL inconnu")
            val rows=FirmwarePolicy.boundedLines(node.second)
            val file=rows.singleOrNull{it.startsWith("F|")}
            if(file!=null){ledger.add("SHELL",observedShell(file.split('|')));ledger.advance(dir,1,true);ledger.coverage("SHELL",dir.path,"file_mount_collected");return}
            if(rows.any{it.startsWith("S|")}){ledger.coverage("SHELL",dir.path,"non_regular_mount_skipped");ledger.advance(dir,0,true);return}
        }
        val aliasAllowed=false
        val resolved=exec(FirmwareShell.directory(dir.path,aliasAllowed))
        if(resolved.first!=0)throw IOException("Répertoire absent/refusé ou alias enfant")
        val resolvedFields=resolved.second.trimEnd('\n').split('|')
        if(resolvedFields.size!=2)throw IOException("Métadonnées répertoire absentes")
        val canonical=FirmwarePolicy.decoded(resolvedFields[0])
        admitted(dir.path,canonical,"SHELL")
        val dirStat=resolvedFields[1].split(':')
        if(dirStat.size!=2 || !table("SHELL").deviceMatches(canonical,dirStat[0].toLong()))throw IOException("Montage SHELL changé depuis mountinfo")
        val node=if(dirStat[1].toLongOrNull()?.let{it>0}==true)resolvedFields[1] else "path:${table("SHELL").mount(canonical)?.device}:$canonical"
        val owner=ledger.directoryOwner("SHELL",node,table("SHELL").topology(dir.path),dir.path)
        if(owner!=dir.path){ledger.coverage("SHELL",dir.path,"directory_alias_deduplicated",owner);ledger.advance(dir,0,true);return}
        val incidentsBefore=ledger.incidents
        val snapshot="$temporary/names-${dir.id}"
        try {
        attestTemporary(snapshot)
        val result=exec(FirmwareShell.snapshot(dir.path,snapshot,aliasAllowed))
        check()
        val completion=FirmwarePolicy.boundedLines(result.second).firstOrNull{it.startsWith("SCAN|") }?.substringAfter('|')?.toIntOrNull()
        val complete=result.first==0 && completion==0
        if(!complete)ledger.issue("SHELL snapshot partiel ${dir.path}, exit=${result.first}/$completion ; limite 30s et 16MiB")
        val sizeReply=exec("stat -L -c %s ${ApkInventoryShell.quote(snapshot)}")
        val size=sizeReply.second.trim().toLongOrNull()?.takeIf{it in 0..16L*1024*1024}?:throw IOException("Taille snapshot inconnue/excessive")
        val snapshotStat=exec("stat -L -c '%f:%s:%Y:%Z:%i:%d' ${ApkInventoryShell.quote(snapshot)}")
        if(snapshotStat.first!=0)throw IOException("Métadonnées snapshot refusées")
        val snapshotExpected=StrictFileRead.Expected.shell(snapshotStat.second.trim())
        if(snapshotExpected.size!=size)throw IOException("Snapshot changé")
        val names=ArrayList<String>(FirmwarePolicy.PAGE)
        var seen=0
        fun flush() {
            if(names.isEmpty())return
            check()
            val metadata=exec(FirmwareShell.batch(names))
            check()
            if(metadata.first!=0)throw IOException("Page de métadonnées incomplète ${dir.path}, exit ${metadata.first}")
            ledger.batch {
                var rows=0
                for(line in FirmwarePolicy.boundedLines(metadata.second)) {
                    when {
                        line.startsWith("D|")->{val path=FirmwarePolicy.decoded(line.substringAfter('|'));if(FirmwarePolicy.firmware(path))ledger.enqueue("SHELL",path)else ledger.issue("SHELL répertoire hors périmètre : $path");rows++}
                        line.startsWith("F|")->{val f=line.split('|');if(f.size!=5)throw IOException("Métadonnées ambiguës")
                            try {ledger.add("SHELL",observedShell(f))}catch(e:FirmwareBudget){throw e}catch(e:Exception){ledger.issue("SHELL fichier refusé : ${e.message}")};rows++}
                        line.startsWith("S|")->{ledger.coverage("SHELL",FirmwarePolicy.decoded(line.substringAfter('|')),"non_regular_or_directory_alias_skipped");rows++}
                        line.startsWith("E|")->{val f=line.split('|');ledger.issue("SHELL métadonnées refusées : "+if(f.size==3)FirmwarePolicy.decoded(f[1])+": "+f[2]else line.take(300));rows++}
                        line=="BATCH|${names.size}"->Unit
                        line.isNotEmpty()->throw IOException("Sortie de métadonnées inconnue : ${line.take(240)}")
                    }
                }
                if(rows!=names.size || !metadata.second.lineSequence().any{it=="BATCH|${names.size}"})throw IOException("Page de métadonnées tronquée")
                ledger.advance(dir,seen,false)
            }
            names.clear()
        }
            var offset=0L
            val input=object:InputStream(){
                override fun read():Int{val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt()and 255}
                override fun read(b:ByteArray,off:Int,len:Int):Int {
                    if(len==0)return 0
                    check();if(offset==size)return -1
                    val bytes=strictChunk(snapshot,offset,minOf(len,FirmwarePolicy.BUFFER,(size-offset).toInt()),snapshotExpected)
                    if(bytes.isEmpty()||offset+bytes.size>size||bytes.size>len)throw IOException("Snapshot court/changé")
                    bytes.copyInto(b,off);offset+=bytes.size;return bytes.size
                }
            }
            FirmwareNames.pages(input,check) { pending,pendingIssues ->
                ledger.batch {
                    for(issue in pendingIssues)ledger.issue("SHELL ${dir.path}: $issue")
                    for(path in pending) {
                        check();seen++
                        if(path!=null) {
                            val reason=table("SHELL").decision(path)
                            if(reason!=null)ledger.coverage("SHELL",path,"excluded",reason)
                            else if(!table("SHELL").sameMount(dir.path,path))ledger.coverage("SHELL",path,"submount_separate_root")
                            else names.add(path)
                        }
                    }
                    ledger.advance(dir,seen,false)
                }
                flush()
                // Includes all-excluded/malformed pages, so cancellation and progress are bounded.
                updateDiscovery?.invoke("SHELL ${dir.path} · $seen entrées examinées")
            }
            val after=exec("stat -L -c %s ${ApkInventoryShell.quote(snapshot)}")
            if(after.first!=0 || after.second.trim().toLongOrNull()!=size)throw IOException("Snapshot modifié pendant lecture")
            ledger.batch {
                ledger.advance(dir,seen,true)
                ledger.coverage("SHELL",dir.path,if(complete)"enumerated" else "partial","$seen direct entries, one bounded NUL snapshot; live non-atomic. Interrupted directory rescanned, not skipped by unstable ordinal.")
                if(complete && ledger.incidents==incidentsBefore)ledger.completeDirectory("SHELL",node,table("SHELL").topology(dir.path),dir.path)
            }
        }finally{
            val removed=runCatching {removeTemporary(snapshot,false)}.getOrDefault(false)
            if(!removed){shellSnapshotsBlocked=true;ledger.issue("Nettoyage snapshot SHELL non confirmé : $snapshot ; aucun nouveau snapshot dans cette session")}
        }
    }
    private fun inspect(path:String,identity:String,positive:Boolean):FirmwareObserved {
        if(identity!="SHELL")throw IOException("Lecture source APP interdite pour ce profil")
        table(identity).decision(path)?.let {throw IOException("Exclusion avant métadonnées : $it")}
        val result=exec(FirmwareShell.inspect(path,positive))
        if(result.first!=0)throw IOException("Métadonnées SHELL refusées, exit ${result.first}")
        val line=FirmwarePolicy.boundedLines(result.second).singleOrNull{it.startsWith("F|")}?:throw IOException("Métadonnées SHELL absentes")
        val f=line.split('|');if(f.size!=5)throw IOException("Métadonnées SHELL invalides")
        return observedShell(f)
    }
    private fun observedShell(f:List<String>):FirmwareObserved {
        val path=FirmwarePolicy.decoded(f[1]);val canonical=FirmwarePolicy.decoded(f[2]);val size=f[3].toLongOrNull()?:throw IOException("Taille inconnue")
        admitted(path,canonical,"SHELL")
        val expected=StrictFileRead.Expected.shell(f[4])
        if(size<0 || size!=expected.size || !expected.matches(expected) || !table("SHELL").deviceMatches(canonical,expected.device))throw IOException("Métadonnées/montage SHELL incohérents")
        return FirmwareObserved(path,canonical,size,f[4],if(expected.inode>0)expected.node() else "path:${table("SHELL").mount(canonical)?.device}:$canonical:${expected.node()}")
    }
    override fun open(source:FirmwareSource,identity:String):InputStream {
        if(identity!="SHELL")throw IOException("Lecture source APP interdite pour ce profil")
        val path=source.shellPath?:throw IOException("Source non inventoriée par le daemon")
        val size=source.shellSize?:throw IOException("Taille inconnue")
        val stamp=source.shellStamp?:throw IOException("Métadonnées inconnues")
        val canonical=source.shellCanonical?:source.canonical
        val now=inspect(path,identity,false)
        if(now.canonical!=canonical || now.size!=size || now.stamp!=stamp)throw IOException("Source changée depuis l'inventaire")
        return rawStream(canonical,StrictFileRead.Expected.shell(stamp))
    }
    private fun exec(command:String,cleanup:Boolean=false):Pair<Int,String> {
        outputGuard.check()
        if(!cleanup && cancelled())throw CancellationException("Annulation après le retour de la commande précédente")
        return gate.call {
        val b=binder?:throw IOException("SHELL indisponible")
        if(!b.isBinderAlive)throw IOException("Binder fixé mort ; aucun reconnect/retry")
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);data.writeString(command)
            if(!b.transact(ProxyDaemonContract.TXN_EXEC,data,reply,0))throw IOException("TXN_EXEC non traité")
            reply.readException();val exit=reply.readInt();val text=reply.readString()?:throw IOException("Sortie absente")
            if(text.contains("[output truncated]"))throw IOException("Sortie tronquée")
            exit to text
        }finally{reply.recycle();data.recycle()}
        }
    }
    private fun strictChunk(path:String,offset:Long,size:Int,expected:StrictFileRead.Expected):ByteArray {
        outputGuard.check()
        if(cancelled())throw CancellationException("Annulation après le retour de la lecture précédente")
        return gate.call {
        val b=binder?:throw IOException("SHELL indisponible")
        if(!b.isBinderAlive)throw IOException("Binder fixé mort ; aucun reconnect/retry")
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);data.writeString(path);data.writeLong(offset);data.writeInt(size)
            data.writeLong(expected.device);data.writeLong(expected.inode);data.writeLong(expected.mode);data.writeLong(expected.size);data.writeLong(expected.modified);data.writeLong(expected.changed)
            if(!b.transact(ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT,data,reply,0))throw IOException("TXN_READ_FILE_CHUNK_STRICT non traité")
            reply.readException()
            if(offset<0 || offset>expected.size || size !in 1..FirmwarePolicy.BUFFER)throw IOException("Bornes de lecture locales invalides")
            val expectedBytes=minOf(size.toLong(),expected.size-offset).toInt()
            if(reply.dataAvail()<4)throw IOException("En-tête chunk absent")
            val position=reply.dataPosition();val declared=reply.readInt()
            if(declared!=expectedBytes || reply.dataAvail()!=((expectedBytes+3)/4)*4)throw IOException("Réponse chunk tronquée/excessive")
            reply.setDataPosition(position)
            val bytes=reply.createByteArray()?:throw IOException("Chunk absent")
            if(bytes.size!=expectedBytes || reply.dataAvail()!=0)throw IOException("Réponse chunk mal cadrée")
            bytes
        }finally{reply.recycle();data.recycle()}
        }
    }
}

internal object FirmwareShell {
    private fun q(s:String)=ApkInventoryShell.quote(s)
    private const val ENCODE_PATH="printf '%s' \"\$dc_p\" | base64 | tr -d '\\n'"
    fun space(temporary:String):String {
        require(temporary.matches(Regex("/data/local/tmp/dashcast-firmware-[a-f0-9-]+")))
        return "dc_a=\$(stat -f -c %a ${q(temporary)}) && dc_b=\$(stat -f -c %S ${q(temporary)}) || exit 123; " +
            "case \"\$dc_a:\$dc_b\" in *[!0-9:]*|:*|*:) exit 123 ;; esac; " +
            "[ \"\$dc_b\" -gt 0 ] && [ \"\$dc_b\" -le 1048576 ] || exit 123; " +
            "dc_need=\$(( (553648128 + dc_b - 1) / dc_b )); [ \"\$dc_a\" -ge \"\$dc_need\" ] || exit 123; "
    }
    fun snapshot(path:String,temporary:String,allowAlias:Boolean):String {
        require(FirmwarePolicy.firmware(path) && temporary.matches(Regex("/data/local/tmp/dashcast-firmware-[a-f0-9-]+/names-[0-9]+")))
        return space(temporary.substringBeforeLast('/'))+"dc_d=${q(path)}; " + (if(allowAlias)"" else "[ ! -L \"\$dc_d\" ] || exit 2; ") +
            "dc_p=\$dc_d; " + resolve("exit 2") + canonicalGuard("exit 2",false) +
            "[ -d \"\$dc_real\" ] || exit 1; umask 077; ( ulimit -f 16384 || exit 125; " +
            "find -H \"\$dc_d\" -mindepth 1 -maxdepth 1 -print0 ) > ${q(temporary)} 2>/dev/null; dc_rc=\$?; printf 'SCAN|%s\\n' \"\$dc_rc\""
    }
    fun batch(paths:List<String>):String {
        require(paths.size in 1..FirmwarePolicy.PAGE && paths.all(FirmwarePolicy::firmware))
        return "for dc_p in ${paths.joinToString(" ",transform=::q)}; do " +
            "if [ -L \"\$dc_p\" ] && [ -d \"\$dc_p\" ]; then printf 'S|'; $ENCODE_PATH; printf '\\n'; " +
            "elif [ -d \"\$dc_p\" ]; then printf 'D|'; $ENCODE_PATH; printf '\\n'; " +
            "elif [ -f \"\$dc_p\" ]; then " + inspectBody("continue") +
            "else printf 'S|'; $ENCODE_PATH; printf '\\n'; fi; done; printf 'BATCH|${paths.size}\\n'"
    }
    private fun resolve(failure:String):String =
        "dc_real=\$(readlink -f \"\$dc_p\" 2>/dev/null; dc_rc=\$?; printf '.DC.%s' \"\$dc_rc\"); " +
        "case \"\$dc_real\" in *.DC.0) dc_real=\${dc_real%.DC.0}; dc_real=\${dc_real%?} ;; *) $failure ;; esac; "
    fun directory(path:String,allowAlias:Boolean):String {
        require(FirmwarePolicy.firmware(path))
        return "dc_p=${q(path)}; " + (if(allowAlias)"" else "[ ! -L \"\$dc_p\" ] || exit 2; ") +
            "[ -d \"\$dc_p\" ] || exit 1; " + resolve("exit 1") + "printf '%s' \"\$dc_real\" | base64 | tr -d '\\n'; printf '|'; stat -L -c '%d:%i' \"\$dc_real\""
    }
    fun inspect(path:String,positivePackage:Boolean=false):String {
        require(FirmwarePolicy.firmware(path) || (positivePackage && FirmwarePolicy.packagePath(path)))
        return "dc_p=${q(path)}; "+inspectBody(positivePackage=positivePackage)
    }
    private fun canonicalGuard(failure:String,@Suppress("UNUSED_PARAMETER") positivePackage:Boolean):String {
        val denied=if(failure.trimEnd().endsWith(';'))failure else "$failure; "
        return "case \"\$dc_real\" in /dev|/dev/*|/proc|/proc/*|/sys|/sys/*|/data/local/tmp/dashcast*) $denied ;; /*) ;; *) $denied ;; esac; "
    }
    private fun inspectBody(onFailure:String="exit 3",positivePackage:Boolean=false):String {
        fun failure(reason:String)="{ printf 'E|'; $ENCODE_PATH; printf '|$reason\\n'; $onFailure; }; "
        return resolve(failure("canonical")) + canonicalGuard(failure("outside-scope"),positivePackage) +
            "[ -f \"\$dc_real\" ] && dc_stat=\$(stat -L -c '%f:%s:%Y:%Z:%i:%d' \"\$dc_real\" 2>/dev/null) || " + failure("metadata") +
            "[ \"\${#dc_real}\" -le 4096 ] || " + failure("canonical-name-too-long") +
            "dc_mode=\${dc_stat%%:*}; dc_rest=\${dc_stat#*:}; dc_size=\${dc_rest%%:*}; " +
            "case \"\$dc_mode\" in 8[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]) ;; *) " + failure("not-regular") + ";; esac; " +
            "case \"\$dc_size\" in ''|*[!0-9]*) " + failure("unknown-size") + ";; esac; " +
            "printf 'F|'; $ENCODE_PATH; printf '|'; printf '%s' \"\$dc_real\" | base64 | tr -d '\\n'; " +
            "printf '|%s|%s\\n' \"\$dc_size\" \"\$dc_stat\"; "
    }
}
