package com.byd.dashcast.proxy

import com.byd.dashcast.proxy.daemon.StrictFileRead
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StrictFileReadTest {
    private val expected=StrictFileRead.Expected(2049,10,0x81a4,3,100,101)
    private class Access(var before:StrictFileRead.Expected,var after:StrictFileRead.Expected=before):StrictFileRead.Access<Int> {
        val calls=ArrayList<String>();var stats=0;var failOpen=false;var count=3
        override fun open(path:String):Int {calls.add("open");if(failOpen)throw IOException("denied");return 1}
        override fun stat(fd:Int):StrictFileRead.Expected {calls.add("stat");return if(stats++==0)before else after}
        override fun read(fd:Int,buffer:ByteArray,offset:Long):Int {calls.add("read:$offset");buffer.fill(7);return minOf(count,buffer.size)}
        override fun close(fd:Int){calls.add("close")}
    }
    private fun fails(block:()->Unit){try{block();fail()}catch(_:IOException){}}
    @Test fun `FD is checked before bytes and after bytes then always closed`() {
        val access=Access(expected)
        assertArrayEquals(byteArrayOf(7,7,7),StrictFileRead.read("/data/file",0,3,expected,access))
        assertEquals(listOf("open","stat","read:0","stat","close"),access.calls)
    }
    @Test fun `replaced FIFO device socket or inode never reaches read`() {
        for(actual in listOf(expected.copy(mode=0x11a4),expected.copy(mode=0x21a4),expected.copy(mode=0xc1a4),expected.copy(inode=11),expected.copy(device=4097))) {
            val access=Access(actual);fails{StrictFileRead.read("/data/file",0,3,expected,access)}
            assertEquals(listOf("open","stat","close"),access.calls)
        }
    }
    @Test fun `post-read changes or short reads never return a successful chunk`() {
        val changed=Access(expected,expected.copy(changed=102));fails{StrictFileRead.read("/data/file",0,3,expected,changed)}
        assertEquals("close",changed.calls.last())
        val short=Access(expected).apply {count=2};fails{StrictFileRead.read("/data/file",0,3,expected,short)}
        assertEquals("close",short.calls.last())
    }
    @Test fun `empty file EOF and unreadable empty file are distinguished by actual open`() {
        for(value in listOf(expected,expected.copy(size=0))) {
            val access=Access(value);assertTrue(StrictFileRead.read("/data/file",value.size,1,value,access).isEmpty())
            assertEquals(listOf("open","stat","stat","close"),access.calls)
            val denied=Access(value).apply {failOpen=true};fails{StrictFileRead.read("/data/file",value.size,1,value,denied)}
            assertEquals(listOf("open"),denied.calls)
        }
    }
    @Test fun `invalid overflow offsets chunk lengths and path traversal are refused before open`() {
        val access=Access(expected)
        for(offset in listOf(-1L,4L,Long.MAX_VALUE))fails{StrictFileRead.read("/data/file",offset,1,expected,access)}
        for(length in listOf(0,-1,StrictFileRead.MAX_CHUNK+1,Int.MAX_VALUE))fails{StrictFileRead.read("/data/file",0,length,expected,access)}
        for(path in listOf("relative","/data/../proc/a","/data//x","/data/x\u0000"))fails{StrictFileRead.read(path,0,1,expected,access)}
        assertTrue(access.calls.isEmpty())
    }
    @Test fun `large offsets are bounded by subtraction without overflow`() {
        val large=expected.copy(size=Long.MAX_VALUE);val access=Access(large)
        assertEquals(1,StrictFileRead.read("/data/file",Long.MAX_VALUE-1,StrictFileRead.MAX_CHUNK,large,access).size)
        assertEquals("read:${Long.MAX_VALUE-1}",access.calls[2])
    }
    @Test fun `shell metadata normalises into the same identity attested by the app`() {
        assertEquals(expected,StrictFileRead.Expected.shell("81a4:3:100:101:10:2049"))
        assertEquals("2049:10:33188:3:100:101",expected.node())
    }
}
