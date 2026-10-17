package com.byd.dashcast.satellite

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*
import java.lang.reflect.Proxy

class HardwareVideoReceiverFactoryTest {
    private val h264 = VideoCodecInfo("H264", mapOf("profile-level-id" to "42e01f", "packetization-mode" to "1"), emptyList())
    private class Decoder : VideoDecoder {
        var callback: VideoDecoder.Callback? = null
        var status = VideoCodecStatus.OK
        var releases = 0; var decodes = 0
        var throwInit = false; var throwDecode = false
        override fun createNative(webrtcEnv: Long) = 123L
        override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus { this.callback = callback; if (throwInit) throw IllegalStateException("fixture"); return status }
        override fun decode(image: EncodedImage, info: VideoDecoder.DecodeInfo): VideoCodecStatus { decodes++; if (throwDecode) throw IllegalStateException("fixture"); return status }
        override fun release(): VideoCodecStatus { releases++; return VideoCodecStatus.OK }
        override fun getImplementationName() = "OMX.vendor.avc.decoder"
    }
    @Test fun `factory never advertises alternate codecs or high profile and checks the selected hardware identity`() {
        val decoder = Decoder()
        var creates = 0; var failures = 0
        val factory = HardwareVideoReceiverFactory(null, { true }, { _, _ -> }, { failures++ }, object : VideoDecoderFactory {
            override fun createDecoder(info: VideoCodecInfo): VideoDecoder { creates++; return decoder }
            override fun getSupportedCodecs() = arrayOf(h264, VideoCodecInfo("VP8", emptyMap(), emptyList()),
                VideoCodecInfo("H265", emptyMap(), emptyList()), VideoCodecInfo("H264", mapOf("profile-level-id" to "640c1f", "packetization-mode" to "1"), emptyList()))
        }, { false })
        assertArrayEquals(arrayOf(h264), factory.supportedCodecs)
        assertNull(factory.createDecoder(VideoCodecInfo("VP8", emptyMap(), emptyList())))
        assertEquals(0, creates)
        assertNull(factory.createDecoder(h264))
        assertEquals(1, creates); assertEquals(1, decoder.releases); assertEquals(1, failures)
    }
    @Test fun `hardware initialization has distinct evidence and software fallback is terminal`() {
        val delegate = Decoder(); val evidence = mutableListOf<String>(); var failures = 0
        val decoder = ObservedHardwareDecoder(delegate, { true }, { value, _ -> evidence += value }, { failures++ })
        assertEquals(VideoCodecStatus.OK, decoder.initDecode(VideoDecoder.Settings(1, 960, 540)) { _, _, _ -> })
        assertEquals(listOf("initialized"), evidence)
        delegate.status = VideoCodecStatus.FALLBACK_SOFTWARE
        assertEquals(VideoCodecStatus.ERROR, decoder.initDecode(VideoDecoder.Settings(1, 960, 540)) { _, _, _ -> })
        assertEquals(1, failures)
    }
    @Test fun `decoder callback forwards only borrowed textures in the current live generation without buffer reads or retains`() {
        var alive = true; var delivered = 0; var failures = 0
        val delegate = Decoder()
        val decoder = ObservedHardwareDecoder(delegate, { alive }, { _, _ -> }, { failures++ })
        val buffer = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(VideoFrame.TextureBuffer::class.java)) { _, method, _ ->
            when (method.name) { "getWidth", "getHeight" -> 32; else -> error("Unexpected buffer operation ${method.name}") }
        } as VideoFrame.TextureBuffer
        val frame = VideoFrame(buffer, 0, 123)
        decoder.initDecode(VideoDecoder.Settings(1, 32, 32)) { value, ms, qp -> assertSame(frame, value); assertEquals(3, ms); assertEquals(5, qp); delivered++ }
        delegate.callback!!.onDecodedFrame(frame, 3, 5)
        assertEquals(1, delivered); assertEquals(0, failures)
        alive = false
        delegate.callback!!.onDecodedFrame(frame, 3, 5)
        alive = true
        decoder.release()
        delegate.callback!!.onDecodedFrame(frame, 3, 5)
        assertEquals(1, delivered)
    }
    @Test fun `codec implementation names are bounded and cannot include diagnostic payload text`() {
        assertEquals("OMX.qcom.video.decoder.avc", HardwareVideoReceiverFactory.safeName("OMX.qcom.video.decoder.avc"))
        assertNull(HardwareVideoReceiverFactory.safeName("name\nprivate text"))
        assertNull(HardwareVideoReceiverFactory.safeName("a".repeat(97)))
    }
    @Test fun `a delegate native pointer cannot bypass Java guards and release inside the final predicate fences the callback`() {
        val delegate = Decoder()
        lateinit var decoder: ObservedHardwareDecoder
        var calls = 0; var delivered = 0
        var releaseAt = Int.MAX_VALUE
        decoder = ObservedHardwareDecoder(delegate, { if (++calls == releaseAt) decoder.release(); true }, { _, _ -> }, {})
        assertEquals(0, decoder.createNative(123))
        decoder.initDecode(VideoDecoder.Settings(1, 32, 32)) { _, _, _ -> delivered++ }
        val buffer = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(VideoFrame.TextureBuffer::class.java)) { _, method, _ ->
            when (method.name) { "getWidth", "getHeight" -> 32; else -> error("Unexpected buffer read") }
        } as VideoFrame.TextureBuffer
        releaseAt = calls + 2
        delegate.callback!!.onDecodedFrame(VideoFrame(buffer, 0, 1), null, null)
        assertEquals(0, delivered)
        decoder.release(); assertEquals(1, delegate.releases)
    }
    @Test fun `failed initialization and a forbidden second initialization invalidate all prior callbacks`() {
        for (firstStatus in listOf(VideoCodecStatus.ERROR, VideoCodecStatus.FALLBACK_SOFTWARE, VideoCodecStatus.OK)) {
            val delegate = Decoder().apply { status = firstStatus }
            var delivered = 0
            val wrapper = ObservedHardwareDecoder(delegate, { true }, { _, _ -> }, {})
            wrapper.initDecode(VideoDecoder.Settings(1, 32, 32)) { _, _, _ -> delivered++ }
            val first = delegate.callback!!
            if (firstStatus == VideoCodecStatus.OK) wrapper.initDecode(VideoDecoder.Settings(1, 32, 32)) { _, _, _ -> delivered++ }
            val buffer = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(VideoFrame.TextureBuffer::class.java)) { _, method, _ ->
                when (method.name) { "getWidth", "getHeight" -> 32; else -> error("Unexpected buffer read") }
            } as VideoFrame.TextureBuffer
            first.onDecodedFrame(VideoFrame(buffer, 0, 1), null, null)
            assertEquals(firstStatus.toString(), 0, delivered)
            wrapper.release(); assertEquals(1, delegate.releases)
        }
    }
    @Test fun `expected codec exceptions become local errors and cannot leak frames across the JNI boundary`() {
        for (duringInit in listOf(true, false)) {
            val delegate = Decoder().apply { throwInit = duringInit; throwDecode = !duringInit }
            var delivered = 0; var failures = 0
            val wrapper = ObservedHardwareDecoder(delegate, { true }, { _, _ -> }, { failures++ })
            val status = wrapper.initDecode(VideoDecoder.Settings(1, 32, 32)) { _, _, _ -> delivered++ }
            if (duringInit) assertEquals(VideoCodecStatus.ERROR, status)
            else {
                val image = EncodedImage.builder().setBuffer(java.nio.ByteBuffer.allocateDirect(1), {}).setEncodedWidth(32).setEncodedHeight(32)
                    .setCaptureTimeNs(1).setFrameType(EncodedImage.FrameType.VideoFrameKey).createEncodedImage()
                try { assertEquals(VideoCodecStatus.ERROR, wrapper.decode(image, VideoDecoder.DecodeInfo(false, 0))) } finally { image.release() }
            }
            val buffer = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(VideoFrame.TextureBuffer::class.java)) { _, method, _ ->
                when (method.name) { "getWidth", "getHeight" -> 32; else -> error("Unexpected buffer read") }
            } as VideoFrame.TextureBuffer
            delegate.callback!!.onDecodedFrame(VideoFrame(buffer, 0, 1), null, null)
            assertEquals(0, delivered); assertEquals(1, failures)
            wrapper.release()
        }
    }
}
