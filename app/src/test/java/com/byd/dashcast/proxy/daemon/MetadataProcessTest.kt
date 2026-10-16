package com.byd.dashcast.proxy.daemon

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class MetadataProcessTest {
    private val host=listOf("/usr/bin/setsid","/bin/sh")
    @Test fun `complete native metadata preserves LF without creating shell PID files`() {
        fun pidFiles()=File(System.getProperty("java.io.tmpdir")?:".").listFiles().orEmpty().filter{it.name.startsWith("dashcast_proxy_shell_")}.map{it.path}.toSet()
        val before=pidFiles()
        val result=MetadataProcess.run(listOf("/bin/sh","-c","printf 'one\\ntwo\\n'"),1024,2000,host)
        assertEquals("one\ntwo\n",result);assertEquals(before,pidFiles())
    }
    @Test fun `output overflow is failure and never returned as truncated valid metadata`() {
        try{MetadataProcess.run(listOf("/bin/sh","-c","yes x | head -c 4096"),128,2000,host);fail()}catch(_:IOException){}
    }
    @Test fun `deadline terminates descendants that hold the capture pipe open`() {
        val pidFile=Files.createTempFile("metadata-test-child-",".pid").toFile()
        var child:Long?=null
        val started=System.nanoTime()
        try {
            val command="sleep 10 & child=\$!; printf '%s' \"\$child\" > '${pidFile.path}'; wait \$child"
            try{MetadataProcess.run(listOf("/bin/sh","-c",command),1024,200,host);fail()}catch(_:IOException){}
            child=pidFile.readText().toLong()
            val status=File("/proc/$child/stat").let {if(it.exists())it.readText().substringAfterLast(") ").first() else null}
            assertTrue("child survived as $status",status==null || status=='Z')
            assertTrue((System.nanoTime()-started)/1_000_000<2000)
        }finally{child?.let {ProcessBuilder("/bin/sh","-c","kill -9 $it 2>/dev/null || true").start().waitFor()};pidFile.delete()}
    }
    @Test fun `nonzero getters and missing absolute launcher never produce positive metadata`() {
        try{MetadataProcess.run(listOf("/bin/sh","-c","printf null; exit 1"),128,2000,host);fail()}catch(_:IOException){}
        try{MetadataProcess.run(listOf("/bin/true"),128,2000,listOf("/missing/setsid","/bin/sh"));fail()}catch(_:IOException){}
    }
    @Test fun `nonzero getter preserves captured stdout stderr reason and bounded Unicode without accepting metadata`() {
        try {
            MetadataProcess.run(listOf("/bin/sh","-c","printf 'getter unavailable'; printf ' permission denied' >&2; exit 7"),4096,2000,host)
            fail()
        }catch(e:IOException) {
            assertTrue(e.message.orEmpty().contains("exit 7"))
            assertTrue(e.message.orEmpty().contains("getter unavailable permission denied"))
        }
        val detail=MetadataProcess.failureDetail("é🚘".repeat(1000).toByteArray(Charsets.UTF_8))
        assertTrue(detail.toByteArray(Charsets.UTF_8).size<=1024)
        assertTrue(detail.endsWith("[truncated]"));assertFalse(detail.contains('\ufffd'))
        try {
            MetadataProcess.run(listOf("/bin/sh","-c","printf '"+"é🚘".repeat(1000)+"'; exit 9"),8192,2000,host)
            fail()
        }catch(e:IOException) {
            assertTrue(e.message.orEmpty().toByteArray(Charsets.UTF_8).size<=1024)
            assertTrue(e.message.orEmpty().contains("exit 9"));assertTrue(e.message.orEmpty().endsWith("[truncated]"))
            assertFalse(e.message.orEmpty().contains('\ufffd'))
        }
    }
}
