package com.byd.dashcast.proxy

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.daemon.StrictFileRead
import com.byd.dashcast.ui.diag.FirmwareDevice
import com.byd.dashcast.ui.diag.FirmwareExport
import com.byd.dashcast.ui.diag.FirmwareLedger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class StrictFileProtocolTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `truncated scalar requests and trailing bytes are refused before unpacking`() {
        for(bytes in 0..64 step 4) {
            val parcel=Parcel.obtain()
            try {
                parcel.writeString("/data/file");repeat(bytes/4){parcel.writeInt(0)};parcel.setDataPosition(0)
                if(bytes==60)assertEquals(0,StrictFileRead.request(parcel).length)
                else try{StrictFileRead.request(parcel);fail("framing $bytes")}catch(_:IOException){}
            }finally{parcel.recycle()}
        }
    }
    @Test fun `typed request preserves large offsets metadata and exact Parcel shape`() {
        val parcel=Parcel.obtain()
        try {
            parcel.writeString("/data/a|b\n");parcel.writeLong(4L*1024*1024*1024);parcel.writeInt(256*1024)
            listOf(2049L,19L,33188L,9L*1024*1024*1024,111L,112L).forEach(parcel::writeLong)
            parcel.setDataPosition(0);val request=StrictFileRead.request(parcel)
            assertEquals("/data/a|b\n",request.path);assertEquals(4L*1024*1024*1024,request.offset)
            assertEquals(9L*1024*1024*1024,request.expected.size);assertEquals(0,parcel.dataAvail())
            assertEquals(38,ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES)
            assertEquals(39,ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT)
            assertEquals(28,ProxyDaemonContract.TXN_READ_FILE_CHUNK)
        }finally{parcel.recycle()}
    }
    @Test fun `old wrong UID absent or malformed capabilities fail explicitly without APP fallback`() {
        for(mode in listOf("old","root","no-capability","wrong-schema","missing-flag","truncated-capability","trailing-capability","bad-pid","bad-instance")) {
            val calls=ArrayList<Int>();var connects=0
            val binder=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR);calls.add(code)
                when(code) {
                    ProxyDaemonContract.TXN_WHOAMI -> {reply!!.writeNoException();reply.writeInt(if(mode=="root")0 else 2000);reply.writeInt(if(mode=="bad-pid")0 else 99);reply.writeString(if(mode=="old")"25" else "26");reply.writeString(if(mode=="bad-instance")"bad" else "0123456789abcdef0123456789abcdef")}
                    ProxyDaemonContract.TXN_FILE_READ_CAPABILITIES -> {
                        if(mode=="no-capability")return false
                        reply!!.writeNoException();reply.writeInt(if(mode=="wrong-schema")2 else 1);if(mode!="truncated-capability")reply.writeInt(if(mode=="missing-flag")0 else 1);if(mode=="trailing-capability")reply.writeInt(0)
                    }
                    else -> fail("No source scan/read or shell command may follow failed negotiation")
                }
                return true
            }}
            val device=FirmwareDevice(RuntimeEnvironment.getApplication(),endpoint={binder},connect={connects++;true})
            val directory=temp.newFolder()
            FirmwareLedger(directory).use { ledger->
                val result=FirmwareExport(device,directory,ledger,{false},{},freeBytes={4L*1024*1024*1024}).run()
                assertEquals(mode,"failed",result.outcome);assertEquals(0,result.discovered);assertTrue(result.archives.isEmpty())
                assertTrue(File(directory,"manifest.json").isFile)
                assertEquals(1,connects);assertFalse(calls.contains(ProxyDaemonContract.TXN_READ_FILE_CHUNK))
                assertFalse(calls.contains(ProxyDaemonContract.TXN_EXEC))
            }
        }
    }
    @Test fun `cancellation after WHOAMI prevents capabilities and any subsequent call`() {
        val stop=java.util.concurrent.atomic.AtomicBoolean(false);val calls=ArrayList<Int>()
        val binder=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            calls.add(code);data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
            reply!!.writeNoException();reply.writeInt(2000);reply.writeInt(99);reply.writeString("26");reply.writeString("0123456789abcdef0123456789abcdef");stop.set(true);return true
        }}
        val device=FirmwareDevice(RuntimeEnvironment.getApplication(),stop::get,endpoint={binder},connect={true})
        try{device.context();fail()}catch(_:java.util.concurrent.CancellationException){}
        assertEquals(listOf(ProxyDaemonContract.TXN_WHOAMI),calls)
    }
    @Test fun `chunk replies are framed and bounded before bytearray allocation with no legacy retry`() {
        val expected=StrictFileRead.Expected(2049,10,0x81a4,3,100,101)
        for(mode in listOf("valid","oversized","short","trailing","missing","unsupported")) {
            val calls=ArrayList<Int>()
            val binder=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                calls.add(code);data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val request=StrictFileRead.request(data);assertEquals(expected,request.expected)
                if(mode=="unsupported")return false
                reply!!.writeNoException()
                when(mode){
                    "oversized"->reply.writeInt(Int.MAX_VALUE)
                    "short"->reply.writeByteArray(byteArrayOf(1,2))
                    "missing"->Unit
                    else->{reply.writeByteArray(byteArrayOf(1,2,3));if(mode=="trailing")reply.writeInt(9)}
                }
                return true
            }}
            val device=FirmwareDevice(RuntimeEnvironment.getApplication(),endpoint={binder},connect={true})
            FirmwareDevice::class.java.getDeclaredField("binder").apply{isAccessible=true;set(device,binder)}
            val method=FirmwareDevice::class.java.getDeclaredMethod("strictChunk",String::class.java,Long::class.javaPrimitiveType,Int::class.javaPrimitiveType,StrictFileRead.Expected::class.java).apply{isAccessible=true}
            try {
                val bytes=method.invoke(device,"/data/file",0L,3,expected) as ByteArray
                assertEquals("valid",mode);assertArrayEquals(byteArrayOf(1,2,3),bytes)
            }catch(e:java.lang.reflect.InvocationTargetException){assertNotEquals("valid",mode);assertTrue(e.cause is IOException)}
            assertEquals(listOf(ProxyDaemonContract.TXN_READ_FILE_CHUNK_STRICT),calls)
        }
    }

}
