package com.byd.dashcast.ui.diag

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
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
    private val connect: (Context) -> Boolean = {ProxyClient.connect(it)}) : FirmwareExport.Device {
    private val gate=FirmwareSerialGate()
    private var initialized=false
    private var binder: IBinder?=null
    private var shellProblem=""
    private var appApex=emptyList<String>()
    private var shellApex=emptyList<String>()
    private val packagePaths=LinkedHashMap<String,String>()
    private val temporary="/data/local/tmp/dashcast-firmware-${java.util.UUID.randomUUID()}"
    private var temporaryReady=false
    private var shellSnapshotsBlocked=false
    private fun init() {
        if(initialized)return
        initialized=true
        val ready=try{gate.call{connect(context)}}catch(e:Exception){shellProblem="Connexion existante indisponible : ${e.message}";false}
        if(cancelled())throw CancellationException("Annulation après connexion ; aucun appel suivant")
        if(ready)binder=ProxyClient.getProxyDaemonBinder()
        if(binder==null && shellProblem.isEmpty())shellProblem="ProxyDaemon indisponible ; couverture SHELL inconnue"
        @Suppress("DEPRECATION")
        try {
            for(app in context.packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
                app.sourceDir?.let { if(FirmwarePolicy.packagePath(it))packagePaths[it]="PackageManager:${app.packageName}:base" }
                app.splitSourceDirs?.forEach {if(FirmwarePolicy.packagePath(it))packagePaths[it]="PackageManager:${app.packageName}:split"}
            }
        }catch(e:Exception){shellProblem+="; inventaire APK positif indisponible : ${e.message}"}
    }
    override fun context(): String {
        init()
        val values=sortedMapOf("fingerprint" to Build.FINGERPRINT,"device" to Build.DEVICE,"product" to Build.PRODUCT,
            "display" to Build.DISPLAY,"api" to Build.VERSION.SDK_INT.toString(),"appUid" to android.os.Process.myUid().toString(),
            "shellUid" to if(binder==null)"unavailable" else ProxyClient.getCallerUid().toString(),"profile" to FirmwarePolicy.PROFILE)
        if(binder!=null) {
            for(key in listOf("ro.build.fingerprint","ro.build.version.incremental","ro.build.region","sys.byd.countrycode")) {
                val result=exec("getprop ${ApkInventoryShell.quote(key)}")
                if(result.first!=0)throw IOException("Contexte ROM SHELL indisponible : $key exit=${result.first}")
                values["shell:$key"]=result.second.trim()
            }
        }
        return values.entries.joinToString(",","{","}"){FirmwarePolicy.json(it.key)+":"+FirmwarePolicy.json(it.value)}
    }
    override fun discover(ledger:FirmwareLedger,check:()->Unit,update:(String)->Unit) {
        init();check()
        ledger.coverage("SCOPE","firmware","declared","Six fixed roots plus strict mounted APEX module directories; no /data/apex, /dev, /proc, /sys or block devices. No directory symlink recursion. Positive PackageManager APK paths only outside firmware.")
        if(shellProblem.isNotEmpty())ledger.issue(shellProblem)
        appApex=try {
            Files.newDirectoryStream(File("/apex").toPath()).use { stream ->
                val found=ArrayList<String>()
                val parent=Os.stat("/apex").st_dev
                for(path in stream){if(found.size>=256){ledger.issue("APP APEX root cap 256 atteint");break}; val value=path.toString()
                    if(FirmwarePolicy.apexRoot(value) && Files.isDirectory(path)){
                        try{if(Os.stat(value).st_dev!=parent)found.add(value)else ledger.issue("APP APEX sans preuve de montage distinct : $value")}catch(e:Exception){ledger.issue("APP APEX $value : ${e.message}")}
                    }
                };found
            }
        }catch(e:Exception){ledger.issue("APP /apex indisponible : ${e.message}");emptyList()}
        shellApex=if(binder==null)emptyList() else try {
            val result=exec("dc_parent=\$(stat -L -c %d /apex) || exit 1; dc_n=0; for dc_p in /apex/*; do [ -d \"\$dc_p\" ] || continue; dc_n=\$((dc_n+1)); [ \"\$dc_n\" -le 256 ] || exit 4; dc_dev=\$(stat -L -c %d \"\$dc_p\") || exit 2; [ \"\$dc_dev\" != \"\$dc_parent\" ] || continue; printf 'APEX|'; printf '%s' \"\$dc_p\" | base64 | tr -d '\\n'; printf '\\n'; done")
            if(result.first!=0)ledger.issue("SHELL inventaire APEX incomplet : exit ${result.first}")
            FirmwarePolicy.boundedLines(result.second).filter {it.startsWith("APEX|")}.map {FirmwarePolicy.decoded(it.substringAfter('|'))}.filter(FirmwarePolicy::apexRoot)
        }catch(e:Exception){ledger.issue("SHELL /apex indisponible : ${e.message}");emptyList()}
        val allRoots=(FirmwarePolicy.roots+appApex+shellApex).distinct()
        for(root in allRoots) {
            for(identity in listOf("APP","SHELL")) {
                check();ledger.coverage(identity,root,"probe_requested")
                if(identity=="SHELL" && binder==null)ledger.coverage(identity,root,"unavailable",shellProblem)
                else ledger.enqueue(identity,root)
            }
        }
        // Enumeration is positive, current-user PackageManager evidence, not private-data recursion.
        ledger.coverage("APP","PackageManager","positive_paths","${packagePaths.size} base/split paths; current-user visibility only, not a proof of every user/package")
        for((path,origin) in packagePaths)for(identity in listOf("APP","SHELL")) {
            check();update("$identity APK $path")
            if(identity=="SHELL" && binder==null)continue
            try {ledger.add(identity,inspect(path,identity,true),origin)}
            catch(e:FirmwareBudget){throw e}
            catch(e:Exception){ledger.issue("$identity APK $path : ${e.message}")}
        }
        try {
            if(binder!=null){val ready=exec("umask 077; mkdir ${ApkInventoryShell.quote(temporary)}");temporaryReady=ready.first==0
                if(!temporaryReady)ledger.issue("SHELL répertoire de snapshots indisponible ; inventaire SHELL partiel")}
            while(true) {
                check();ledger.budget()
                val dir=ledger.nextDirectory()?:break
                update("${dir.identity} ${dir.path}")
                try {if(dir.identity=="APP")scanApp(dir,ledger,check,update) else scanShell(dir,ledger,check)}
                catch(e:CancellationException){throw e}
                catch(e:FirmwareBudget){throw e}
                catch(e:Exception){ledger.issue("${dir.identity} inventaire ${dir.path} : ${e.message}");ledger.coverage(dir.identity,dir.path,"partial",e.message.orEmpty());ledger.advance(dir,dir.offset,true)}
            }
        } finally {
            if(temporaryReady)runCatching {val result=exec("rm -rf ${ApkInventoryShell.quote(temporary)}",true);if(result.first!=0)ledger.issue("Nettoyage snapshot local temporaire refusé")}
                .onFailure {runCatching {ledger.issue("Snapshot local temporaire conservé : ${it.message}")}}
        }
    }
    private fun scanApp(dir:FirmwareDirectory,ledger:FirmwareLedger,check:()->Unit,update:(String)->Unit) {
        // A declared root can be an Android mount alias; child directory symlinks never recurse.
        val root=File(dir.path);val real=root.canonicalPath
        if(Files.isSymbolicLink(root.toPath()) && dir.path !in (FirmwarePolicy.roots+appApex+shellApex))throw IOException("Alias de répertoire enfant non suivi")
        if(!FirmwarePolicy.accepted(dir.path,real))throw IOException("Alias de répertoire hors périmètre")
        if(!Files.isDirectory(root.toPath()))throw IOException("Racine/répertoire absent ou inaccessible")
        var visited=0
        Files.newDirectoryStream(root.toPath()).use { stream ->
            val iterator=stream.iterator()
            while(iterator.hasNext())ledger.batch {
              var batchCount=0
              while(iterator.hasNext() && batchCount++<32) {
                val path=iterator.next();check();val text=path.toString()
                if(++visited%32==0)update("APP $text")
                try {
                    val attrs=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
                    when {
                        attrs.isDirectory -> if(FirmwarePolicy.firmware(text))ledger.enqueue("APP",text) else ledger.issue("APP chemin hors périmètre : $text")
                        attrs.isRegularFile || attrs.isSymbolicLink -> {
                            if(attrs.isSymbolicLink && Files.isDirectory(path)) {ledger.coverage("APP",text,"directory_alias_skipped");continue}
                            ledger.add("APP",inspect(text,"APP",false))
                        }
                        else -> ledger.coverage("APP",text,"non_regular_skipped")
                    }
                }catch(e:FirmwareBudget){throw e}
                catch(e:Exception){ledger.issue("APP métadonnées $text : ${e.message}")}
              }
            }
        }
        ledger.advance(dir,visited,true);ledger.coverage("APP",dir.path,"enumerated","$visited direct entries; live non-atomic enumeration")
    }
    private fun scanShell(dir:FirmwareDirectory,ledger:FirmwareLedger,check:()->Unit) {
        if(!temporaryReady || shellSnapshotsBlocked)throw IOException("Snapshot SHELL indisponible ou nettoyage précédent non confirmé ; aucun nouveau snapshot")
        val aliasAllowed=dir.path in (FirmwarePolicy.roots+appApex+shellApex)
        val resolved=exec(FirmwareShell.directory(dir.path,aliasAllowed))
        if(resolved.first!=0)throw IOException("Répertoire absent/refusé ou alias enfant")
        val canonical=FirmwarePolicy.decoded(resolved.second.trim())
        if(!FirmwarePolicy.accepted(dir.path,canonical))throw IOException("Canonique de répertoire hors périmètre")
        val snapshot="$temporary/names-${dir.id}"
        try {
        val result=exec(FirmwareShell.snapshot(dir.path,snapshot,aliasAllowed))
        check()
        val completion=FirmwarePolicy.boundedLines(result.second).firstOrNull{it.startsWith("SCAN|") }?.substringAfter('|')?.toIntOrNull()
        val complete=result.first==0 && completion==0
        if(!complete)ledger.issue("SHELL snapshot partiel ${dir.path}, exit=${result.first}/$completion ; limite 30s et 16MiB")
        val sizeReply=exec("stat -L -c %s ${ApkInventoryShell.quote(snapshot)}")
        val size=sizeReply.second.trim().toLongOrNull()?.takeIf{it in 0..16L*1024*1024}?:throw IOException("Taille snapshot inconnue/excessive")
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
                            val value=FirmwareObserved(FirmwarePolicy.decoded(f[1]),FirmwarePolicy.decoded(f[2]),f[3].toLongOrNull()?:throw IOException("Taille inconnue"),f[4])
                            if(value.size>=0 && FirmwarePolicy.accepted(value.path,value.canonical))ledger.add("SHELL",value)else ledger.issue("SHELL fichier/alias refusé : ${value.path}");rows++}
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
                    val bytes=chunk(snapshot,offset,minOf(len,FirmwarePolicy.BUFFER,(size-offset).toInt()))
                    if(bytes.isEmpty()||offset+bytes.size>size||bytes.size>len)throw IOException("Snapshot court/changé")
                    bytes.copyInto(b,off);offset+=bytes.size;return bytes.size
                }
            }
            FirmwareNames.read(input,{ledger.issue("SHELL ${dir.path}: $it")}) {path->
                seen++;if(path!=null)names.add(path);if(names.size==FirmwarePolicy.PAGE)flush()
            }
            flush()
            val after=exec("stat -L -c %s ${ApkInventoryShell.quote(snapshot)}")
            if(after.first!=0 || after.second.trim().toLongOrNull()!=size)throw IOException("Snapshot modifié pendant lecture")
            ledger.batch {ledger.advance(dir,seen,true);ledger.coverage("SHELL",dir.path,if(complete)"enumerated" else "partial","$seen direct entries, one bounded NUL snapshot; live non-atomic. Interrupted directory rescanned, not skipped by unstable ordinal.")}
        }finally{
            val removed=runCatching {exec("rm -f ${ApkInventoryShell.quote(snapshot)} && [ ! -e ${ApkInventoryShell.quote(snapshot)} ]",true).first==0}.getOrDefault(false)
            if(!removed){shellSnapshotsBlocked=true;ledger.issue("Nettoyage snapshot SHELL non confirmé : $snapshot ; aucun nouveau snapshot dans cette session")}
        }
    }
    private fun inspect(path:String,identity:String,positive:Boolean):FirmwareObserved {
        if(identity=="APP") {
            val file=File(path);val real=file.canonicalPath
            if(!FirmwarePolicy.accepted(path,real,positive))throw IOException("Canonique hors périmètre")
            val stat=Os.stat(real)
            if(!OsConstants.S_ISREG(stat.st_mode) || stat.st_size<0)throw IOException("Métadonnées régulières fiables indisponibles")
            return FirmwareObserved(path,real,stat.st_size,stamp(stat))
        }
        val result=exec(FirmwareShell.inspect(path,positive))
        if(result.first!=0)throw IOException("Métadonnées SHELL refusées, exit ${result.first}")
        val line=FirmwarePolicy.boundedLines(result.second).singleOrNull{it.startsWith("F|")}?:throw IOException("Métadonnées SHELL absentes")
        val f=line.split('|');if(f.size!=5)throw IOException("Métadonnées SHELL invalides")
        val value=FirmwareObserved(FirmwarePolicy.decoded(f[1]),FirmwarePolicy.decoded(f[2]),f[3].toLongOrNull()?:throw IOException("Taille inconnue"),f[4])
        if(value.size<0 || !FirmwarePolicy.accepted(value.path,value.canonical,positive))throw IOException("Canonique SHELL hors périmètre")
        return value
    }
    override fun open(source:FirmwareSource,identity:String):InputStream {
        val path=(if(identity=="APP")source.appPath else source.shellPath)?:throw IOException("Identité non inventoriée")
        val size=(if(identity=="APP")source.appSize else source.shellSize)?:throw IOException("Taille inconnue")
        val expectedStamp=(if(identity=="APP")source.appStamp else source.shellStamp)?:throw IOException("Métadonnées inconnues")
        if(source.positivePackage && !FirmwarePolicy.firmware(path) && path !in packagePaths)throw IOException("APK hors inventaire PackageManager actuel")
        val now=inspect(path,identity,source.positivePackage)
        if(now.canonical!=source.canonical || now.size!=size || now.stamp!=expectedStamp)throw IOException("Source changée depuis l'inventaire")
        if(identity=="APP") {
            val fd=Os.open(source.canonical,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0)
            try {
                val stat=Os.fstat(fd)
                if(!OsConstants.S_ISREG(stat.st_mode)||stat.st_size!=size||stamp(stat)!=expectedStamp)throw IOException("Source non régulière/changée à l'ouverture")
                val input=FileInputStream(fd)
                return object:InputStream(){
                    private var read=0L
                    override fun read():Int{val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt()and 255}
                    override fun read(b:ByteArray,off:Int,len:Int):Int {
                        if(len==0)return 0
                        val n=input.read(b,off,minOf(len,FirmwarePolicy.BUFFER))
                        if(n<0){val end=Os.fstat(fd);if(read!=size || stamp(end)!=expectedStamp)throw IOException("Source APP changée pendant lecture")}
                        else read+=n
                        return n
                    }
                    override fun close(){try{input.close()}finally{runCatching {Os.close(fd)}}}
                }
            }catch(e:Exception){Os.close(fd);throw e}
        }
        return object:InputStream(){
            private var offset=0L
            private var ended=false
            override fun read():Int{val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt()and 255}
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                if(len==0)return 0
                if(ended)return -1
                // Existing file-chunk protocol cannot pin an FD or expose errno. Metadata is
                // rechecked before every chunk and after EOF; the remaining path race is recorded.
                val value=inspect(path,"SHELL",source.positivePackage)
                if(value.canonical!=source.canonical||value.size!=size||value.stamp!=expectedStamp)throw IOException("Source SHELL changée avant lecture")
                if(offset==size){ended=true;return -1}
                val bytes=chunk(source.canonical,offset,minOf(len,FirmwarePolicy.BUFFER,(size-offset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                if(bytes.isEmpty() || bytes.size>len || offset+bytes.size>size)throw IOException("Lecture SHELL refusée/courte/incohérente")
                bytes.copyInto(b,off);offset+=bytes.size;return bytes.size
            }
        }
    }
    private fun stamp(s:StructStat)="${s.st_dev}:${s.st_ino}:${s.st_mode}:${s.st_size}:${s.st_mtime}:${s.st_ctime}"
    private fun exec(command:String,cleanup:Boolean=false):Pair<Int,String> {
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
    private fun chunk(path:String,offset:Long,size:Int):ByteArray {
        if(cancelled())throw CancellationException("Annulation après le retour de la lecture précédente")
        return gate.call {
        val b=binder?:throw IOException("SHELL indisponible")
        if(!b.isBinderAlive)throw IOException("Binder fixé mort ; aucun reconnect/retry")
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);data.writeString(path);data.writeLong(offset);data.writeInt(size)
            if(!b.transact(ProxyDaemonContract.TXN_READ_FILE_CHUNK,data,reply,0))throw IOException("TXN_READ_FILE_CHUNK non traité")
            reply.readException();reply.createByteArray()?:throw IOException("Chunk absent")
        }finally{reply.recycle();data.recycle()}
        }
    }
}

internal object FirmwareShell {
    private fun q(s:String)=ApkInventoryShell.quote(s)
    private const val ENCODE_PATH="printf '%s' \"\$dc_p\" | base64 | tr -d '\\n'"
    fun snapshot(path:String,temporary:String,allowAlias:Boolean):String {
        require(FirmwarePolicy.firmware(path) && temporary.matches(Regex("/data/local/tmp/dashcast-firmware-[a-f0-9-]+/names-[0-9]+")))
        return "dc_d=${q(path)}; " + (if(allowAlias)"" else "[ ! -L \"\$dc_d\" ] || exit 2; ") +
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
            "[ -d \"\$dc_p\" ] || exit 1; " + resolve("exit 1") + "printf '%s' \"\$dc_real\" | base64 | tr -d '\\n'"
    }
    fun inspect(path:String,positivePackage:Boolean=false):String {
        require(FirmwarePolicy.firmware(path) || (positivePackage && FirmwarePolicy.packagePath(path)))
        return "dc_p=${q(path)}; "+inspectBody(positivePackage=positivePackage)
    }
    private fun canonicalGuard(failure:String,positivePackage:Boolean):String {
        val denied=if(failure.trimEnd().endsWith(';'))failure else "$failure; "
        return "case \"\$dc_real\" in /system|/system/*|/system_ext|/system_ext/*|/product|/product/*|/vendor|/vendor/*|/odm|/odm/*|/oem|/oem/*) ;; " +
            "/apex/*) dc_tail=\${dc_real#/apex/}; dc_module=\${dc_tail%%/*}; dc_base=\${dc_module%%@*}; " +
            "case \"\$dc_base\" in ''|apex-info-list.xml|[!A-Za-z0-9_]*|*[!A-Za-z0-9_.-]*) $denied ;; esac; " +
            "case \"\$dc_module\" in *@*) dc_version=\${dc_module#*@}; case \"\$dc_version\" in ''|*[!0-9]*) $denied ;; esac ;; esac ;; " +
            (if(positivePackage)"/data/app/*.apk|/data/app-private/*.apk|/mnt/expand/*/app/*.apk) ;; " else "") +
            "*) $denied ;; esac; "
    }
    private fun inspectBody(onFailure:String="exit 3",positivePackage:Boolean=false):String {
        fun failure(reason:String)="{ printf 'E|'; $ENCODE_PATH; printf '|$reason\\n'; $onFailure; }; "
        return resolve(failure("canonical")) + canonicalGuard(failure("outside-scope"),positivePackage) +
            "[ -f \"\$dc_real\" ] && dc_stat=\$(stat -L -c '%f:%s:%Y:%Z:%i:%d' \"\$dc_real\" 2>/dev/null) || " + failure("metadata") +
            "[ \"\${#dc_real}\" -le 4096 ] || " + failure("canonical-name-too-long") +
            "dc_mode=\${dc_stat%%:*}; dc_rest=\${dc_stat#*:}; dc_size=\${dc_rest%%:*}; " +
            "case \"\$dc_mode\" in 8[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]) ;; *) " + failure("not-regular") + ";; esac; " +
            "case \"\$dc_size\" in ''|*[!0-9]*) " + failure("unknown-size") + ";; esac; " +
            "( : < \"\$dc_real\" ) 2>/dev/null || " + failure("read-open-denied") +
            "printf 'F|'; $ENCODE_PATH; printf '|'; printf '%s' \"\$dc_real\" | base64 | tr -d '\\n'; " +
            "printf '|%s|%s\\n' \"\$dc_size\" \"\$dc_stat\"; "
    }
}
