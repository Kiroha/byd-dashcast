package com.byd.dashcast.proxy.daemon

import android.os.Parcel
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.FileDescriptor
import java.io.IOException

/** Reopens each chunk without following the final symlink and attests its FD before any read.
 * Metadata is second precision on this Android API; this is not a filesystem snapshot. */
internal object StrictFileRead {
    const val MAX_CHUNK = 256 * 1024
    data class Expected(val device:Long,val inode:Long,val mode:Long,val size:Long,val modified:Long,val changed:Long) {
        fun matches(actual:Expected)=this==actual && mode and 0xf000L==0x8000L && size>=0
        companion object {
            fun from(stat:StructStat)=Expected(stat.st_dev,stat.st_ino,stat.st_mode.toLong(),stat.st_size,stat.st_mtime,stat.st_ctime)
            fun shell(stamp:String):Expected {
                val f=stamp.split(':');if(f.size!=6)throw IOException("Malformed strict file metadata")
                return Expected(f[5].toLong(),f[4].toLong(),f[0].toLong(16),f[1].toLong(),f[2].toLong(),f[3].toLong())
            }
        }
        fun node()="$device:$inode:$mode:$size:$modified:$changed"
    }
    data class Request(val path:String?,val offset:Long,val length:Int,val expected:Expected)
    fun request(data:Parcel):Request {
        val path=data.readString()
        // Parcel scalar reads may produce zero for missing bytes. Check framing before unpacking.
        if(data.dataAvail()!=60)throw IOException("Truncated/trailing strict file fields")
        val offset=data.readLong();val length=data.readInt()
        val expected=Expected(data.readLong(),data.readLong(),data.readLong(),data.readLong(),data.readLong(),data.readLong())
        return Request(path,offset,length,expected)
    }
    interface Access<F> {
        fun open(path:String):F
        fun stat(fd:F):Expected
        fun read(fd:F,buffer:ByteArray,offset:Long):Int
        fun close(fd:F)
    }
    private val system=object:Access<FileDescriptor> {
        override fun open(path:String):FileDescriptor=Os.open(path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0)
        override fun stat(fd:FileDescriptor)=Expected.from(Os.fstat(fd))
        override fun read(fd:FileDescriptor,buffer:ByteArray,offset:Long)=Os.pread(fd,buffer,0,buffer.size,offset)
        override fun close(fd:FileDescriptor)=Os.close(fd)
    }
    fun read(path:String?,offset:Long,length:Int,expected:Expected):ByteArray=read(path,offset,length,expected,system)
    internal fun <F> read(path:String?,offset:Long,length:Int,expected:Expected,access:Access<F>):ByteArray {
        if(path==null || !path.startsWith('/') || path.length>4096 || '\u0000' in path ||
            path.split('/').drop(1).any{it.isEmpty() || it=="." || it==".."})throw IOException("Invalid strict file path")
        if(length !in 1..MAX_CHUNK || offset<0 || expected.size<0 || offset>expected.size ||
            expected.mode and 0xf000L!=0x8000L)throw IOException("Invalid strict read bounds/type")
        val fd=access.open(path)
        try {
            if(!expected.matches(access.stat(fd)))throw IOException("Strict FD identity/type changed before read")
            val amount=minOf(length.toLong(),expected.size-offset).toInt()
            val buffer=ByteArray(amount)
            // Zero-length and EOF requests still open and fstat, rather than implying readability.
            val count=if(amount==0)0 else access.read(fd,buffer,offset)
            if(count!=amount)throw IOException("Strict read short: $count/$amount")
            if(!expected.matches(access.stat(fd)))throw IOException("Strict FD metadata changed during read")
            return buffer
        } finally {access.close(fd)}
    }
}
