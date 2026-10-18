package com.byd.dashcast.satellite

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import org.webrtc.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Hardware H.264 only. No default factory, software decoder or native fallback is installed. */
internal class HardwareVideoReceiverFactory(
    context: EglBase.Context?,
    private val alive: () -> Boolean,
    private val evidence: (String, String?) -> Unit,
    private val failure: () -> Unit,
    private val delegate: VideoDecoderFactory = HardwareVideoDecoderFactory(context, ::hardware),
    private val hardwareName: (String) -> Boolean = { name ->
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { it.name == name && hardware(it) }
    },
) : VideoDecoderFactory {
    override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.supportedCodecs.filter {
        it.name.equals("H264", true) && it.params["profile-level-id"]?.startsWith("42") == true &&
            it.params["packetization-mode"] == "1"
    }.toTypedArray()

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? = try {
        createHardwareDecoder(info)
    } catch (_: Exception) { failure(); null }

    private fun createHardwareDecoder(info: VideoCodecInfo): VideoDecoder? {
        if (!alive() || supportedCodecs.none { it.name == info.name && it.params == info.params }) return null
        val decoder = delegate.createDecoder(info) ?: run { failure(); return null }
        val name = decoder.implementationName
        if (!hardwareName(name)) { decoder.release(); failure(); return null }
        evidence("created", safeName(name))
        return ObservedHardwareDecoder(decoder, alive, evidence, failure)
    }
    companion object {
        internal fun safeName(name: String): String? = name.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,96}")) }
        private fun hardware(codec: MediaCodecInfo): Boolean {
            if (codec.isEncoder) return false
            if (Build.VERSION.SDK_INT >= 29) return codec.isHardwareAccelerated && !codec.isSoftwareOnly
            // API 28 lacks hardware flags. Fail closed outside these known vendor families.
            val name = codec.name.lowercase()
            return listOf("omx.qcom.", "omx.exynos.", "omx.mtk.", "omx.hisi.", "omx.nvidia.").any(name::startsWith)
        }
    }
}

/** Exact SDK methods forwarded explicitly; decoded frames remain borrowed, never read back/copied. */
internal class ObservedHardwareDecoder(private val delegate: VideoDecoder, private val alive: () -> Boolean,
    private val evidence: (String, String?) -> Unit, private val failure: () -> Unit) : VideoDecoder {
    private val released = AtomicBoolean(false)
    private val initStarted = AtomicBoolean(false)
    private val binding = AtomicLong()
    @Volatile private var initialized = false
    override fun createNative(webrtcEnv: Long): Long = 0L // Java observation cannot be bypassed.
    override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus {
        val epoch = binding.incrementAndGet()
        initialized = false
        if (!initStarted.compareAndSet(false, true) || !alive() || released.get()) {
            failure(); return VideoCodecStatus.ERROR
        }
        val result = try {
            delegate.initDecode(settings) { frame, decodeMs, qp ->
                if (initialized && binding.get() == epoch && alive() && !released.get()) {
                    if (frame.buffer !is VideoFrame.TextureBuffer) { failure(); return@initDecode }
                    // External predicates may synchronously retire/release/reinitialize this binding.
                    if (alive() && !released.get() && binding.get() == epoch && initialized)
                        callback.onDecodedFrame(frame, decodeMs, qp)
                }
            }
        } catch (_: Exception) { VideoCodecStatus.ERROR }
        catch (_: LinkageError) { VideoCodecStatus.ERROR }
        if (result == VideoCodecStatus.OK && alive() && !released.get() && binding.get() == epoch) {
            initialized = true
            evidence("initialized", HardwareVideoReceiverFactory.safeName(delegate.implementationName))
        } else { binding.incrementAndGet(); failure() }
        return if (result == VideoCodecStatus.FALLBACK_SOFTWARE || (result == VideoCodecStatus.OK && !initialized)) VideoCodecStatus.ERROR else result
    }
    // The pinned native VideoDecoderWrapper deliberately passes null for the optional metadata.
    override fun decode(image: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        if (!initialized || !alive() || released.get()) return VideoCodecStatus.ERROR
        val status = try { delegate.decode(image, info) } catch (_: Exception) { VideoCodecStatus.ERROR }
        when (status) {
            VideoCodecStatus.ERROR, VideoCodecStatus.FALLBACK_SOFTWARE, VideoCodecStatus.TIMEOUT,
            VideoCodecStatus.MEMORY, VideoCodecStatus.ERR_PARAMETER, VideoCodecStatus.UNINITIALIZED -> {
                initialized = false; binding.incrementAndGet(); failure()
            }
            else -> Unit
        }
        return if (status == VideoCodecStatus.FALLBACK_SOFTWARE) VideoCodecStatus.ERROR else status
    }
    override fun release(): VideoCodecStatus {
        if (!released.compareAndSet(false, true)) return VideoCodecStatus.OK
        initialized = false; binding.incrementAndGet()
        return try { delegate.release() } catch (_: Exception) { failure(); VideoCodecStatus.ERROR }
    }

    override fun getImplementationName(): String = delegate.implementationName
}
