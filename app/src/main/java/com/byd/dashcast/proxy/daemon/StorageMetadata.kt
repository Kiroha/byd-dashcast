package com.byd.dashcast.proxy.daemon

import android.os.Parcel
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Closed read-only bootstrap operations. No ProxyShell wrapper, PID file or filesystem writes. */
internal object StorageMetadata {
    const val MOUNTINFO=1
    const val PRIMARY_UUID=2
    const val VOLUMES=3
    const val BOOT_DEVICE=4
    const val BLOCK=5
    const val OUTPUT=6
    const val PROPERTY=7
    const val BOOT_USERDATA=8
    const val MAX_BYTES=196608
    data class Request(val operation:Int,val argument:String)
    fun request(data:Parcel):Request {
        if(data.dataAvail()<8)throw IOException("Truncated metadata request")
        val op=data.readInt();val position=data.dataPosition();val length=data.readInt()
        if(length !in 0..4096 || data.dataAvail()!=((length+3)/4)*4)throw IOException("Invalid metadata argument framing")
        data.setDataPosition(position);val bytes=data.createByteArray()?:throw IOException("Null metadata argument")
        val arg=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        if(data.dataAvail()!=0 || '\u0000' in arg || arg.length>4096)throw IOException("Invalid metadata framing")
        if(op !in MOUNTINFO..BOOT_USERDATA)throw IOException("Unknown metadata operation")
        when(op) {
            BLOCK->if(!arg.matches(Regex("[0-9]{1,10}:[0-9]{1,10}")))throw IOException("Invalid numeric block device")
            BOOT_USERDATA->if(!numericDevice(arg))throw IOException("Invalid observed boot block device")
            PROPERTY->if(arg !in setOf("ro.build.fingerprint","ro.build.version.incremental","ro.build.region","sys.byd.countrycode"))throw IOException("Unexpected metadata property")
            OUTPUT->if(!ownOutput(arg))throw IOException("Metadata path outside collector outputs")
            else->if(arg.isNotEmpty())throw IOException("Unexpected metadata argument")
        }
        return Request(op,arg)
    }
    private fun ownOutput(path:String):Boolean = path=="/data/local/tmp" ||
        path.matches(Regex("/data/local/tmp/dashcast-firmware-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:/names-[1-9][0-9]{0,5})?")) ||
        path.matches(Regex("/storage/emulated/[0-9]+/Android/data/com\\.byd\\.dashcast/files(?:/firmware-exports(?:/firmware-export-[A-Za-z0-9-]+)?)?"))
    private fun read(file:File,limit:Int):String {
        val output=ByteArrayOutputStream(minOf(limit,4096))
        file.inputStream().use {input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;if(n==0)throw IOException("Metadata read made no progress");if(output.size()+n>limit)throw IOException("Metadata exceeds budget");output.write(b,0,n)}}
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    }
    fun execute(request:Request):String = when(request.operation) {
        MOUNTINFO->read(File("/proc/self/mountinfo"),MAX_BYTES)
        PRIMARY_UUID->MetadataProcess.run(listOf("/system/bin/sm","get-primary-storage-uuid"),4096)
        VOLUMES->MetadataProcess.run(listOf("/system/bin/sm","list-volumes","all"),64*1024)
        BOOT_DEVICE->MetadataProcess.run(listOf("/system/bin/getprop","ro.boot.bootdevice"),4096)
        PROPERTY->MetadataProcess.run(listOf("/system/bin/getprop",request.argument),4096)
        BLOCK->block(request.argument)
        BOOT_USERDATA->bootUserdata(request.argument)
        OUTPUT->output(request.argument)
        else->throw IOException("Unknown metadata operation")
    }
    internal fun optionalPresent(file:File,probe:(String)->Unit = {Os.lstat(it);Unit}):Boolean = try {probe(file.path);true}catch(e:ErrnoException){
        if(e.errno==OsConstants.ENOENT || e.errno==OsConstants.ENOTDIR)false else throw e
    }
    internal fun numericDevice(value:String):Boolean {
        val fields=value.split(':')
        if(fields.size!=2 || fields.any{!it.matches(Regex("[0-9]{1,10}"))})return false
        val major=fields[0].toLongOrNull()?:return false;val minor=fields[1].toLongOrNull()?:return false
        return major in 1..0xffffffffL && minor in 0..0xffffffffL && "$major:$minor"==value
    }
    internal data class NodeStat(val mode:Int,val rdev:Long)
    internal interface MetadataFiles {
        fun canonical(path:String):String
        fun stat(path:String):NodeStat
    }
    private val nativeFiles=object:MetadataFiles {
        override fun canonical(path:String)=File(path).canonicalPath
        override fun stat(path:String)=Os.stat(path).let{NodeStat(it.st_mode,it.st_rdev)}
    }
    private fun block(device:String):String {
      var stage="canonical /sys/dev/block/$device"
      try {
        val directory=File("/sys/dev/block/$device").canonicalFile
        stage="directory ${directory.path}"
        if(!directory.path.startsWith("/sys/devices/") || !directory.isDirectory)throw IOException("Unresolved block metadata")
        fun present(file:File):Boolean {stage="lstat ${file.path}";return optionalPresent(file)}
        fun readAt(file:File,limit:Int):String {stage="read ${file.path}";return read(file,limit)}
        fun optional(path:String,limit:Int)=File(directory,path).let {if(present(it))readAt(it,limit).trimEnd('\n') else ""}
        val dependencies=ArrayList<String>()
        if(present(File(directory,"slaves"))) {stage="list ${directory.path}/slaves";java.nio.file.Files.newDirectoryStream(File(directory,"slaves").toPath()).use {stream->for(slave in stream){
            if(dependencies.size>=16)throw IOException("Too many block dependencies")
            dependencies.add(readAt(slave.resolve("dev").toFile(),128).trimEnd('\n'))
        }}}
        if(present(File(directory,"partition")))dependencies.add(readAt(File(directory.parentFile,"dev"),128).trimEnd('\n'))
        if(dependencies.any{!it.matches(Regex("[0-9]{1,10}:[0-9]{1,10}"))})throw IOException("Invalid block dependency metadata")
        val backing=File(directory,"loop/backing_file").let {if(present(it))readAt(it,4097) else ""}
        fun b64(value:String)=Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        return "BLOCK|$device|${b64(directory.path)}|${optional("removable",16)}|${optional("device/type",32)}|${dependencies.joinToString(",")}|${b64(backing)}\n"
      }catch(e:Exception){throw IOException("Block metadata $device $stage: ${e.javaClass.simpleName}: ${e.message}",e)}
    }
    /** Fixed metadata paths only. stat/readlink do not open or read a block-device payload. */
    internal fun bootUserdata(device:String,files:MetadataFiles=nativeFiles):String {
        if(!numericDevice(device))throw IOException("Invalid observed boot block device")
        val root="/dev/block/bootdevice"
        var stage="canonical $root"
        try {
            val controller=files.canonical(root)
            stage="stat controller $controller";val controllerStat=files.stat(controller)
            if(!OsConstants.S_ISDIR(controllerStat.mode))throw IOException("Boot controller is not a directory")
            val path="$root/by-name/userdata"
            stage="canonical $path";val target=files.canonical(path)
            stage="stat fixed userdata $path";val observed=files.stat(path)
            stage="stat canonical userdata $target";val canonical=files.stat(target)
            if(!OsConstants.S_ISBLK(observed.mode) || !OsConstants.S_ISBLK(canonical.mode) || observed.rdev!=canonical.rdev || deviceId(observed.rdev)!=device)throw IOException("Boot userdata block type/device mismatch")
            fun b64(value:String)=Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
            return "BOOT_USERDATA|1|$device|${b64(controller)}|${controllerStat.mode}|${b64(target)}|${observed.mode}|${observed.rdev}\n"
        }catch(e:Exception){throw IOException("Boot userdata metadata $device $stage: ${e.javaClass.simpleName}: ${e.message}",e)}
    }
    private fun deviceId(device:Long):String {
        val major=((device ushr 8) and 0xfffL) or ((device ushr 32) and 0xfffff000L)
        val minor=(device and 0xffL) or ((device ushr 12) and 0xffffff00L)
        return "$major:$minor"
    }
    private fun output(path:String):String {
        var ancestor=File(path);var depth=0
        while(!optionalPresent(ancestor)){if(++depth>32)throw IOException("Output ancestor depth limit");ancestor=ancestor.parentFile?:throw IOException("Output ancestor absent")}
        val real=ancestor.canonicalPath;val stat=Os.stat(real)
        return Base64.getEncoder().encodeToString(real.toByteArray(Charsets.UTF_8))+"|${stat.st_dev}"
    }
}

/** Fixed argv only at production call sites. setsid identity travels over a control pipe, never
 * a PID file; stdout and control stderr drain concurrently under one launch/read deadline. */
internal object MetadataProcess {
    fun run(arguments:List<String>,limit:Int,timeoutMs:Long=30000,systemPaths:List<String> = listOf("/system/bin/setsid","/system/bin/sh")):String {
        require(arguments.isNotEmpty() && limit in 1..StorageMetadata.MAX_BYTES && timeoutMs>0)
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val wrapper="printf '%s\\n' \"\$\$\" >&2; exec \"\$@\" 2>&1"
        require(systemPaths.size==2 && systemPaths.all{it.startsWith('/')})
        val process=ProcessBuilder(listOf(systemPaths[0],systemPaths[1],"-c",wrapper,"dashcast-storage-metadata")+arguments).start()
        val identity=AtomicReference<Long?>();val failure=AtomicReference<Throwable?>();val output=ByteArrayOutputStream(minOf(limit,4096))
        val reader=Thread({try{process.inputStream.use {input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;if(output.size()+n>limit){failure.compareAndSet(null,IOException("Metadata output exceeds budget"))}else if(failure.get()==null)output.write(b,0,n)}}}catch(e:Throwable){failure.compareAndSet(null,e)}},"storage-metadata-output")
        val control=Thread({try{process.errorStream.use {input->val line=ByteArrayOutputStream();var complete=false;while(true){val c=input.read();if(c<0)break;if(complete || line.size()>=64)throw IOException("Invalid metadata process identity");if(c==10){val pid=line.toString("US-ASCII").toLongOrNull();if(pid==null || pid<=1)throw IOException("Metadata process identity absent");identity.set(pid);complete=true}else line.write(c)};if(!complete)throw IOException("Metadata process identity absent")}}catch(e:Throwable){failure.compareAndSet(null,e)}},"storage-metadata-control")
        reader.isDaemon=true;control.isDaemon=true
        fun remaining()=maxOf(1L,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))
        fun killGroup(){val pid=identity.get()?:return;runCatching {val killer=ProcessBuilder(systemPaths[1],"-c","kill -9 -- -\$1 2>/dev/null || kill -9 -\$1 2>/dev/null || true","metadata-kill",pid.toString()).start();try{killer.waitFor(500,TimeUnit.MILLISECONDS)}finally{killer.destroyForcibly()}}}
        try {
            process.outputStream.close();reader.start();control.start()
            if(!process.waitFor(remaining(),TimeUnit.MILLISECONDS))throw IOException("Metadata process deadline")
            reader.join(remaining());control.join(remaining())
            if(reader.isAlive || control.isAlive || System.nanoTime()>deadline)throw IOException("Metadata drain deadline")
            failure.get()?.let{throw IOException("Metadata capture failed",it)}
            if(process.exitValue()!=0) {
                val prefix="Metadata command exit ${process.exitValue()}: "
                throw IOException(prefix+failureDetail(output.toByteArray(),1024-prefix.toByteArray(Charsets.UTF_8).size))
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
        }finally{
            // The direct child can leave descendants holding stdout; kill its dedicated group.
            if(process.isAlive || reader.isAlive || control.isAlive) {
                if(identity.get()!=null)killGroup()
                process.destroyForcibly()
                if(identity.get()==null){runCatching {control.join(100)};if(identity.get()!=null)killGroup()}
            }
            runCatching {process.inputStream.close()};runCatching {process.errorStream.close()};runCatching {process.outputStream.close()}
            runCatching {reader.join(500)};runCatching {control.join(500)}
        }
    }
    /** Already-captured stdout/stderr only. Diagnostic truncation never becomes a success reply. */
    internal fun failureDetail(bytes:ByteArray,limit:Int=1024):String {
        val suffix="\n[truncated]"
        require(limit>suffix.length)
        if(bytes.size<=limit)return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        var end=limit-suffix.length
        while(end>0 && (bytes[end].toInt() and 0xc0)==0x80)end--
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes,0,end)).toString()+suffix
    }
}
