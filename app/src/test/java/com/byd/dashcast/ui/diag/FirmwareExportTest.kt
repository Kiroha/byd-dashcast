package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FirmwareExportTest {
    @get:Rule val temp=TemporaryFolder()
    private class Device(val files:LinkedHashMap<String,ByteArray>):FirmwareExport.Device {
        var contextValue="{\"fingerprint\":\"synthetic\"}"
        var opens=0
        var discovered=0
        var reader:((FirmwareSource,String)->InputStream)?=null
        var discovery:((FirmwareLedger)->Unit)?=null
        override fun context()=contextValue
        override fun discover(ledger:FirmwareLedger,check:()->Unit,update:(String)->Unit) {
            discovered++
            if(discovery!=null){discovery!!(ledger);return}
            files.forEach { (path,bytes) -> check();ledger.add("APP",FirmwareObserved(path,path,bytes.size.toLong(),"app-stamp"));ledger.add("SHELL",FirmwareObserved(path,path,bytes.size.toLong(),"shell-stamp")) }
        }
        override fun open(source:FirmwareSource,identity:String):InputStream {opens++;return reader?.invoke(source,identity)?:ByteArrayInputStream(files.getValue(source.canonical))}
    }
    private fun files(count:Int,size:Int=12)=linkedMapOf<String,ByteArray>().apply {repeat(count){put("/system/file${it.toString().padStart(4,'0')}",ByteArray(size){v->(v+it).toByte()})}}
    private fun run(dir:File,device:Device,cancel:()->Boolean={false},volume:Long=1024,entries:Int=3,rename:()->Unit={}):FullApkExport.Progress =
        FirmwareLedger(dir).use {FirmwareExport(device,dir,it,cancel,{},volume,entries,{4L*1024*1024*1024},rename).run()}
    @Test fun `zero files are copied and identity metadata stays separate across aliases`() {
        val d=temp.newFolder();val device=Device(linkedMapOf("/system/zero" to byteArrayOf(),"/system/a b|c\n.apk" to byteArrayOf(1,2)))
        val result=run(d,device)
        assertEquals("finished",result.outcome);assertEquals(2,result.completed);assertEquals(2,result.discovered)
        FirmwareLedger(d).use {ledger ->
            val zero=ledger.byCanonical("/system/zero")!!
            assertEquals(0L,zero.appSize);assertEquals(0L,zero.shellSize);assertEquals("app-stamp",zero.appStamp);assertEquals("shell-stamp",zero.shellStamp)
            ledger.add("APP",FirmwareObserved("/vendor/alias","/system/zero",0,"app-stamp"))
            assertEquals(2,ledger.sources);assertEquals("/system/zero",ledger.byCanonical("/system/zero")!!.appPath)
        }
        ZipFile(result.archives.single()).use { z -> assertEquals(0,z.getEntry("firmware/000001/zero").size) }
        assertTrue(File(d,"manifest.json").readText().contains("a b|c\\n.apk"))
    }
    @Test fun `many small files use entry ceiling and oversized single file is never truncated`() {
        val d=temp.newFolder();val data=files(1000,0);data["/system/huge"]=ByteArray(2500){(it%251).toByte()}
        val result=run(d,Device(data),volume=1000,entries=31)
        assertEquals("finished",result.outcome);assertEquals(1001,result.completed)
        var count=0;var bytes=0L
        for(file in result.archives)ZipFile(file).use {z->val list=z.entries().asSequence().filter {it.name!="manifest-volume.json"}.toList();assertTrue(list.size<=31);count+=list.size;bytes+=list.sumOf{it.size}}
        assertEquals(1001,count);assertEquals(2500L,bytes)
    }
    @Test fun `cancellation preserves finalized earlier entries and reopens same journal for true resume`() {
        val d=temp.newFolder();val data=files(3,20);val stop=AtomicBoolean(false);val device=Device(data)
        device.reader={source,_->object:ByteArrayInputStream(data.getValue(source.canonical)){override fun read(b:ByteArray,off:Int,len:Int):Int{val n=super.read(b,off,minOf(len,5));stop.set(true);return n}}}
        val first=run(d,device,stop::get)
        assertEquals("cancelled",first.outcome);assertEquals(0,first.completed);assertEquals(1,first.archives.size)
        val original=first.archives.single().readBytes()
        val resumed=Device(data);val second=run(d,resumed)
        assertEquals(3,second.completed);assertEquals(0,resumed.discovered);assertEquals(3,resumed.opens)
        assertArrayEquals(original,first.archives.single().readBytes());assertEquals(2,second.archives.size)
    }
    @Test fun `process death after rename before checkpoint reconciles ZIP and skips verified source`() {
        class Death:Error()
        val d=temp.newFolder();val device=Device(files(1))
        try {run(d,device,rename={throw Death()});fail()}catch(_:Death){}
        val file=d.listFiles()!!.single{it.extension=="zip"};val before=file.readBytes()
        val second=Device(device.files);val result=run(d,second)
        assertEquals(1,result.completed);assertEquals(0,second.opens);assertArrayEquals(before,file.readBytes())
        FirmwareLedger(d).use {assertEquals("verified",it.volumes().single().state)}
    }
    private fun rewrite(file:File,rows:List<Pair<String,ByteArray>>) {
        ZipOutputStream(file.outputStream()).use {z->rows.forEach{(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}}
    }
    @Test fun `corrupt entry is retained but recopied into a new immutable volume`() {
        val d=temp.newFolder();val data=files(1);val first=run(d,Device(data));val file=first.archives.single()
        val rows=ZipFile(file).use{z->z.entries().asSequence().map{it.name to z.getInputStream(it).readBytes()}.toList()}
        rewrite(file,rows.map{if(it.first=="manifest-volume.json")it else it.first to ByteArray(it.second.size){99}})
        val corrupt=file.readBytes();val device=Device(data);val result=run(d,device)
        assertEquals(1,device.opens);assertEquals(1,result.completed);assertEquals(1,result.archives.size);assertNotEquals(file,result.archives.single());assertArrayEquals(corrupt,file.readBytes())
        assertTrue(File(d,"manifest.json").readText().contains("corrupt"))
    }
    @Test fun `missing finalized ZIP is not trusted and sources are copied again`() {
        val d=temp.newFolder();val data=files(2);val first=run(d,Device(data));assertTrue(first.archives.single().delete())
        val device=Device(data);val result=run(d,device)
        assertEquals(2,device.opens);assertEquals(2,result.completed);assertTrue(File(d,"manifest.json").readText().contains("manquant"))
    }
    @Test fun `missing ZIP entry and duplicate central directory names invalidate whole volume`() {
        for(duplicate in listOf(false,true)) {
            val d=temp.newFolder();val data=files(2);val first=run(d,Device(data));val file=first.archives.single()
            val rows=ZipFile(file).use{z->z.entries().asSequence().map{it.name to z.getInputStream(it).readBytes()}.toList()}
            if(!duplicate)rewrite(file,rows.filterIndexed{i,_->i!=0})
            else {
                val bytes=file.readBytes();val from=rows[1].first.toByteArray();val to=rows[0].first.toByteArray();assertEquals(from.size,to.size)
                var replacements=0
                for(i in 0..bytes.size-from.size)if(from.indices.all{j->bytes[i+j]==from[j]}){to.copyInto(bytes,i);replacements++}
                assertTrue(replacements>=2);file.writeBytes(bytes)
            }
            val device=Device(data);val result=run(d,device);assertEquals(2,device.opens);assertEquals(2,result.completed)
        }
    }
    @Test fun `changed ROM profile or schema refuses resume before source reads and preserves manifest`() {
        for(key in listOf("context","profile","schema")) {
            val d=temp.newFolder();val data=files(1);run(d,Device(data));val manifest=File(d,"manifest.json").readBytes()
            FirmwareLedger(d).use{it.meta(key,"different")}
            val device=Device(data);val result=run(d,device)
            assertEquals("failed",result.outcome);assertEquals(0,device.opens);assertEquals(0,device.discovered);assertArrayEquals(manifest,File(d,"manifest.json").readBytes())
        }
    }
    @Test fun `source denial remains visible when alternate identity succeeds`() {
        val d=temp.newFolder();val device=Device(files(2));device.reader={s,identity->if(identity=="APP")throw IOException("denied") else ByteArrayInputStream(device.files.getValue(s.canonical))}
        val result=run(d,device);assertEquals("partial",result.outcome);assertEquals(2,result.completed);assertEquals(4,device.opens)
        assertTrue(File(d,"manifest.json").readText().contains("APP: denied"))
    }
    @Test fun `source cap keeps partial inventory with omitted incident counter`() {
        val d=temp.newFolder();val device=Device(files(5))
        FirmwareLedger(d,sourceLimit=2,incidentLimit=2).use {ledger ->
            repeat(5){ledger.issue("synthetic incident $it")}
            val result=FirmwareExport(device,d,ledger,{false},{},1024,3,{4L*1024*1024*1024}).run()
            assertEquals("partial",result.outcome);assertEquals(2,result.discovered);assertEquals(2,result.completed)
            val json=File(d,"manifest.json").readText();assertTrue(json.contains("\"incidentsOmitted\":4"));assertTrue(json.contains("\"discoveryComplete\":false"))
        }
    }
    @Test fun `storage reserve failure leaves no successful archive`() {
        val d=temp.newFolder();val device=Device(files(1))
        FirmwareLedger(d).use {ledger->val r=FirmwareExport(device,d,ledger,{false},{},freeBytes={512L*1024*1024}).run();assertEquals("failed",r.outcome);assertEquals(0,device.opens);assertTrue(r.archives.isEmpty())}
    }
    @Test fun `part file is never accepted as a completed ZIP after reopen`() {
        val d=temp.newFolder();val data=files(1);run(d,Device(data))
        FirmwareLedger(d).use{l->val v=l.newVolume();File(d,v.name+".part").writeText("incomplete")}
        val device=Device(data);val result=run(d,device)
        assertEquals(0,device.opens);assertEquals(1,result.completed);assertEquals(1,result.archives.size)
        assertEquals(1,d.listFiles()!!.count{it.name.endsWith(".part")})
    }
    @Test fun `late physical transaction keeps gate until actual return without retry threads`() {
        val gate=FirmwareSerialGate();val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=AtomicBoolean(false)
        val thread=Thread {gate.call{entered.countDown();release.await();finished.set(true)}};thread.start()
        assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(gate.pendingSince>0)
        try{gate.call{fail("second physical call must not start")};fail()}catch(_:IllegalStateException){}
        assertFalse(finished.get());assertTrue(gate.pendingSince>0)
        release.countDown();thread.join(2000);assertTrue(finished.get());assertEquals(0L,gate.pendingSince)
        assertEquals(7,gate.call{7})
    }
    @Test fun `storage reserve loss during resume does not classify intact ZIP as corrupt`() {
        val d=temp.newFolder();val data=files(1);val first=run(d,Device(data));val zip=first.archives.single();val before=zip.readBytes()
        val low=AtomicBoolean(false)
        FirmwareLedger(d).use {ledger ->
            val result=FirmwareExport(Device(data),d,ledger,{false},{if(it.stage.startsWith("Vérification"))low.set(true)},freeBytes={if(low.get())0 else 4L*1024*1024*1024}).run()
            assertEquals("failed",result.outcome);assertEquals("verified",ledger.volumes().single().state)
        }
        assertArrayEquals(before,zip.readBytes());assertFalse(File(d,"manifest.json").readText().contains("corrupt"))
    }
    @Test fun `inventory batch rollback restores disk rows and incremental counters`() {
        val d=temp.newFolder()
        FirmwareLedger(d).use {ledger ->
            try {ledger.batch {ledger.add("APP",FirmwareObserved("/system/a","/system/a",0,"stamp"));ledger.enqueue("APP","/system");ledger.issue("rolled back");throw IOException("crash")};fail()}catch(_:IOException){}
            assertEquals(0,ledger.sources);assertEquals(0L,ledger.incidents);assertNull(ledger.byCanonical("/system/a"));assertNull(ledger.nextDirectory())
            ledger.batch {ledger.add("APP",FirmwareObserved("/system/a.apk","/system/a.apk",0,"stamp"));ledger.add("APP",FirmwareObserved("/vendor/alias.apk","/system/a.apk",0,"stamp"),"PackageManager:synthetic:base")}
            val out=java.io.StringWriter();ledger.manifest(out,"partial","")
            val json=org.json.JSONObject(out.toString());val origins=json.getJSONArray("files").getJSONObject(0).getJSONArray("origins")
            assertEquals(2,origins.length())
        }
    }

    @Test fun `cancel during daemon connection prevents PackageManager or any following call`() {
        val stop=AtomicBoolean(false);var packageCalls=0;var connectCalls=0
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val context=object:android.content.ContextWrapper(app){override fun getPackageManager():android.content.pm.PackageManager{packageCalls++;return super.getPackageManager()}}
        val device=FirmwareDevice(context,stop::get){connectCalls++;stop.set(true);false}
        try{device.context();fail()}catch(_:java.util.concurrent.CancellationException){}
        assertEquals(1,connectCalls);assertEquals(0,packageCalls)
    }
    @Test fun `directory and coverage mutations share inventory budget and rollback boundary`() {
        val d=temp.newFolder();var limited=false
        FirmwareLedger(d,inventoryLimit=256L*1024).use {ledger ->
            try{repeat(1000){i->ledger.batch{ledger.enqueue("APP","/system/dir$i");ledger.coverage("APP","/system/dir$i","fixture","x".repeat(2000))}}}catch(_:FirmwareBudget){limited=true}
            assertTrue(limited);assertEquals(0,ledger.sources)
        }
        val disk=d.listFiles()!!.filter{it.name.startsWith("firmware-ledger.sqlite")}.sumOf{it.length()}
        assertTrue("inventory $disk",disk<=256L*1024)
    }
    @Test fun `diagnostic screen has a single export action and no targeted menu choices`() {
        val controller=org.robolectric.Robolectric.buildActivity(DiagActivity::class.java).create()
        try {
            val activity=controller.get();val labels=ArrayList<android.widget.Button>()
            fun visit(view:android.view.View){if(view is android.widget.Button)labels.add(view);if(view is android.view.ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))}
            visit(activity.window.decorView)
            val action=labels.single{it.text.toString()=="Export complet accessible — firmware + APK"}
            assertFalse(labels.any{it.text.toString().contains("BYD APK Extraction")||it.text.toString().contains("P15")||it.text.toString().contains("Export système")||it.text.toString().contains("Relevé DiLink")})
            action.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val items=(0 until dialog.listView.adapter.count).map{dialog.listView.adapter.getItem(it).toString()}
            assertEquals("Nouvelle collecte complète accessible",items.first());assertFalse(items.any{it.contains("P15")||it.contains("SYSTEM")||it.contains("CONTEXTE")})
            dialog.dismiss()
        } finally {controller.destroy()}
    }

    @Test fun `metadata only corruption with unchanged size and valid ZIP CRC invalidates volume`() {
        val d=temp.newFolder();val data=files(1);val first=run(d,Device(data));val file=first.archives.single()
        val rows=ZipFile(file).use{z->z.entries().asSequence().map{it.name to z.getInputStream(it).readBytes()}.toList()}
        val mutated=rows.map{(name,bytes)->if(name=="manifest-volume.json"){
            val changed=String(bytes,Charsets.UTF_8).replaceFirst("\"schema\":1","\"schema\":9").toByteArray(Charsets.UTF_8)
            assertEquals(bytes.size,changed.size);assertFalse(bytes.contentEquals(changed));name to changed
        }else name to bytes}
        rewrite(file,mutated)
        val corrupt=file.readBytes();val device=Device(data);val result=run(d,device)
        assertEquals(1,device.opens);assertEquals(1,result.completed);assertEquals(1,result.archives.size);assertNotEquals(file,result.archives.single())
        assertArrayEquals(corrupt,file.readBytes());assertTrue(File(d,"manifest.json").readText().contains("SHA-256 des métadonnées"))
    }

}
