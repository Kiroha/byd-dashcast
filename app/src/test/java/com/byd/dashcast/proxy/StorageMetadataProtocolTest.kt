package com.byd.dashcast.proxy

import android.os.Parcel
import com.byd.dashcast.proxy.daemon.StorageMetadata
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class StorageMetadataProtocolTest {
    private fun request(op:Int,arg:String,extra:Boolean=false):StorageMetadata.Request {
        val data=Parcel.obtain()
        try {data.writeInt(op);data.writeByteArray(arg.toByteArray(Charsets.UTF_8));if(extra)data.writeInt(0);data.setDataPosition(0);return StorageMetadata.request(data)}finally{data.recycle()}
    }
    @Test fun `closed operations and validated own-output or numeric arguments preserve old transaction ABI`() {
        assertEquals(40,ProxyDaemonContract.TXN_STORAGE_METADATA);assertEquals(39,ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT)
        assertEquals(2,ProxyDaemonContract.CAP_STORAGE_METADATA)
        assertEquals("179:1",request(StorageMetadata.BLOCK,"179:1").argument)
        assertEquals("",request(StorageMetadata.MOUNTINFO,"").argument)
        val path="/storage/emulated/10/Android/data/com.byd.dashcast/files/firmware-exports/firmware-export-20261016-123000-123"
        assertEquals(path,request(StorageMetadata.OUTPUT,path).argument)
        val temporary="/data/local/tmp/dashcast-firmware-01234567-89ab-cdef-0123-456789abcdef"
        for(value in listOf(temporary,"$temporary/names-1","$temporary/names-200000"))assertEquals(value,request(StorageMetadata.OUTPUT,value).argument)
        for(value in listOf(temporary.replace("01234567","invalid"),"$temporary/","$temporary/names-0","$temporary/names--1","$temporary/names-9999999","$temporary/names-9223372036854775808","$temporary/names-1/child","$temporary/../other","$temporary//names-1","$temporary/names-1\u0000")) {
            try{request(StorageMetadata.OUTPUT,value);fail(value)}catch(_:IOException){}
        }
    }
    @Test fun `unknown operations extra fields NUL nonnumeric devices and arbitrary output paths fail before access`() {
        for((op,arg) in listOf(99 to "",StorageMetadata.BLOCK to "../179:1",StorageMetadata.BLOCK to "1:2:3",StorageMetadata.BLOCK to "179:1\u0000",StorageMetadata.OUTPUT to "/storage/A1B2-C3D4/secret",StorageMetadata.OUTPUT to "/data/system",StorageMetadata.PROPERTY to "sys.powerctl",StorageMetadata.MOUNTINFO to "/proc/other")) {
            try{request(op,arg);fail("$op $arg")}catch(_:IOException){}
        }
        try{request(StorageMetadata.MOUNTINFO,"",true);fail()}catch(_:IOException){}
        for(bytes in listOf(0,4,12,100)) {
            val data=Parcel.obtain()
            try{repeat(bytes/4){data.writeInt(0)};data.setDataPosition(0);try{StorageMetadata.request(data);fail()}catch(_:IOException){}}finally{data.recycle()}
        }
    }
    @Test fun `missing optional kernel attributes differ from inaccessible ones`() {
        val path=java.io.File("/sys/fixed-metadata-fixture")
        assertFalse(StorageMetadata.optionalPresent(path){throw android.system.ErrnoException("lstat",android.system.OsConstants.ENOENT)})
        assertFalse(StorageMetadata.optionalPresent(path){throw android.system.ErrnoException("lstat",android.system.OsConstants.ENOTDIR)})
        try{StorageMetadata.optionalPresent(path){throw android.system.ErrnoException("lstat",android.system.OsConstants.EACCES)};fail()}catch(e:android.system.ErrnoException){assertEquals(android.system.OsConstants.EACCES,e.errno)}
        assertTrue(StorageMetadata.optionalPresent(path){})
    }

    @Test fun `metadata replies reject overflow trailing bytes and malformed UTF8 before returning any classification`() {
        for(mode in listOf("valid","oversized","trailing","utf8","missing")) {
            val binder=object:android.os.Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                assertEquals(ProxyDaemonContract.TXN_STORAGE_METADATA,code);assertEquals(StorageMetadata.MOUNTINFO,StorageMetadata.request(data).operation)
                reply!!.writeNoException()
                when(mode){"oversized"->reply.writeInt(Int.MAX_VALUE);"missing"->Unit;"utf8"->reply.writeByteArray(byteArrayOf(0xc3.toByte()));else->{reply.writeByteArray("metadata\n".toByteArray());if(mode=="trailing")reply.writeInt(0)}}
                return true
            }}
            val device=com.byd.dashcast.ui.diag.FirmwareDevice(org.robolectric.RuntimeEnvironment.getApplication(),endpoint={binder},connect={true})
            device.javaClass.getDeclaredField("binder").apply{isAccessible=true;set(device,binder)}
            val method=device.javaClass.getDeclaredMethod("metadata",Int::class.javaPrimitiveType,String::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}
            try{val value=method.invoke(device,StorageMetadata.MOUNTINFO,"",false);assertEquals("valid",mode);assertEquals("metadata\n",value)}catch(e:java.lang.reflect.InvocationTargetException){assertNotEquals("valid",mode);assertTrue(e.cause is IOException)}
        }
    }
    @Test fun `native mountinfo bootstrap preserves its final newline without legacy shell execution`() {
        val result=StorageMetadata.execute(StorageMetadata.Request(StorageMetadata.MOUNTINFO,""))
        assertTrue(result.endsWith("\n"));assertTrue(result.toByteArray().size<=StorageMetadata.MAX_BYTES)
        // This host has unrelated container mounts; parser coverage uses Android fixtures.
        assertTrue(result.lineSequence().any{it.contains(" - ")})
    }

}
