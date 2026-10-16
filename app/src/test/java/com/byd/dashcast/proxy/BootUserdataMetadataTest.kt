package com.byd.dashcast.proxy

import android.os.Parcel
import android.system.ErrnoException
import android.system.OsConstants
import com.byd.dashcast.proxy.daemon.StorageMetadata
import com.byd.dashcast.ui.diag.FirmwareStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class BootUserdataMetadataTest {
    private val device="8:21"
    private val controller="/dev/block/platform/soc/1234000.ufshc"
    private val target="/dev/block/sda21"
    private val encodedDevice=(8L shl 8) or 21L
    private inner class Files:StorageMetadata.MetadataFiles {
        val calls=ArrayList<String>()
        var controllerMode=0x41ed
        var mode=0x6180
        var canonicalRdev=encodedDevice
        var failure:Exception?=null
        override fun canonical(path:String):String {
            calls.add("canonical $path");failure?.let{throw it}
            return when(path){"/dev/block/bootdevice"->controller;"/dev/block/bootdevice/by-name/userdata"->target;else->throw AssertionError("Unexpected filesystem probe $path")}
        }
        override fun stat(path:String):StorageMetadata.NodeStat {
            calls.add("stat $path");failure?.let{throw it}
            return when(path){controller->StorageMetadata.NodeStat(controllerMode,0);"/dev/block/bootdevice/by-name/userdata"->StorageMetadata.NodeStat(mode,encodedDevice);target->StorageMetadata.NodeStat(mode,canonicalRdev);else->throw AssertionError("Unexpected filesystem probe $path")}
        }
    }
    @Test fun `closed boot operation reads only fixed canonical and stat metadata with no block payload access`() {
        val files=Files()
        val result=FirmwareStorage.BootUserdata.parse(StorageMetadata.bootUserdata(device,files),device)
        assertEquals(controller,result.controller);assertEquals(target,result.target);assertEquals(encodedDevice,result.rdev)
        assertEquals(listOf("canonical /dev/block/bootdevice","stat $controller","canonical /dev/block/bootdevice/by-name/userdata","stat /dev/block/bootdevice/by-name/userdata","stat $target"),files.calls)
    }
    @Test fun `invalid numeric request is rejected before any filesystem probe and cannot carry paths or overflow`() {
        for(value in listOf("","0:1","8:-1","-8:1","08:21","8:021","8:4294967296","4294967296:1","999999999999:1","8:21/../x","/dev/block/sda","8:21\u0000","8:21 extra")) {
            val files=Files()
            try{StorageMetadata.bootUserdata(value,files);fail(value)}catch(_:IOException){}
            assertTrue(files.calls.isEmpty())
            val parcel=Parcel.obtain()
            try{parcel.writeInt(StorageMetadata.BOOT_USERDATA);parcel.writeByteArray(value.toByteArray(Charsets.UTF_8));parcel.setDataPosition(0);try{StorageMetadata.request(parcel);fail(value)}catch(_:IOException){}}finally{parcel.recycle()}
        }
    }
    @Test fun `denied missing non-block and changed canonical target identity never return partial positive proof`() {
        for(errno in listOf(OsConstants.EACCES,OsConstants.ENOENT)) {
            val files=Files().apply{failure=ErrnoException("readlink",errno)}
            try{StorageMetadata.bootUserdata(device,files);fail()}catch(e:IOException){assertTrue(e.message.orEmpty().contains("canonical /dev/block/bootdevice"));assertTrue(e.cause is ErrnoException)}
        }
        for(files in listOf(Files().apply{controllerMode=0x81ed},Files().apply{mode=0x8180},Files().apply{canonicalRdev=(8L shl 8) or 97L})) {
            try{StorageMetadata.bootUserdata(device,files);fail()}catch(_:IOException){}
        }
    }
    @Test fun `boot metadata strict schema count paths types and observed rdev reject malformed responses`() {
        val valid=StorageMetadata.bootUserdata(device,Files())
        for(text in listOf(valid.trimEnd('\n'),valid+"extra\n",valid.replace("|1|","|2|"),valid.trimEnd('\n')+"|extra\n",valid.replace("|16877|","|33133|"),valid.replace("|24960|","|33152|"),valid.replace("|2069\n","|2145\n"),valid.replace("|8:21|","|8:22|"))) {
            try{FirmwareStorage.BootUserdata.parse(text,device);fail(text)}catch(_:Exception){}
        }
    }
}
