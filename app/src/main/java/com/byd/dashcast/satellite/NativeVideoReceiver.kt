package com.byd.dashcast.satellite

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import org.json.JSONObject
import org.webrtc.*
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface SatelliteVideoReceiver {
    val view: View
    fun start()
    fun signal(session: String, message: JSONObject)
    fun reset()
    fun close()
    interface Listener {
        fun ready()
        fun failed(code: String)
        fun status(reference: Boolean, rendered: Boolean)
        fun reply(session: String, raw: String)
    }
}

/** All native allocation/disposal is serialized off the UI and WSS threads, including across viewers. */
internal class NativeVideoReceiver(context: Context, listener: SatelliteVideoReceiver.Listener,
    dispatch: (() -> Unit) -> Unit = { worker.post(it) }) : SatelliteVideoReceiver {
    private val context = context
    private val listener = listener
    private val dispatch = dispatch
    private fun post(block: () -> Unit) = dispatch(block)
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val mutation = AtomicLong()
    private data class Signal(val attempt: Attempt, val message: JSONObject)
    private val inbox = NativeSignalInbox<Signal>()
    private val statusPosted = AtomicBoolean(false)
    private val statsQueued = AtomicBoolean(false)
    private val monitor = object : Runnable {
        override fun run() {
            if (closed.get()) return
            active?.takeIf(::owns)?.let(::tick)
            main.postDelayed(this, 1_000)
        }
    }
    private fun waiting() {
        if (!statusPosted.compareAndSet(false, true)) return
        main.post {
            statusPosted.set(false)
            if (!closed.get()) { val a = active; listener.status(a?.reference == true, a?.rendered?.get()?.let { it > 0 } == true) }
        }
    }
    private fun enqueue(a: Attempt, message: JSONObject, replace: Boolean = false) {
        val result = synchronized(lock) { if (!owns(a)) return; inbox.submit(Signal(a, message), replace) }
        when (result) {
            NativeSignalInbox.Result.SCHEDULE -> post(::drain)
            NativeSignalInbox.Result.OVERFLOW -> fail(a, "signalling_overflow")
            NativeSignalInbox.Result.QUEUED -> Unit
        }
    }
    private fun drain() {
        val signal = inbox.take() ?: return
        val a = signal.attempt
        if (owns(a)) guarded(a) {
            if (signal.message.optString("type") == "video.offer") offer(a, signal.message.getString("sdp"))
            else remoteIce(a, signal.message)
        }
        post(::drain)
    }
    @Volatile private var active: Attempt? = null
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var renderer: EglRenderer? = null
    private var surface: SurfaceLease? = null
    private var peer: PeerConnection? = null
    private var track: VideoTrack? = null
    private var sink: VideoSink? = null
    private var initialized = false
    private val texture = TextureView(context)
    override val view: View get() = texture

    private class SurfaceLease(val texture: SurfaceTexture) {
        val released = AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) texture.release() }
    }
    private class Attempt(val session: String, val negotiation: String, val diagnostic: Long, val reference: Boolean) {
        val valid = AtomicBoolean(true)
        val decoded = AtomicLong(0)
        val rendered = AtomicLong(0)
        val lastRender = AtomicLong(0)
        val width = AtomicLong(0)
        val height = AtomicLong(0)
        val watchdog = NativeImageWatchdog(SystemClock.elapsedRealtime())
        val ice = ArrayDeque<IceCandidate>()
        val outgoing = ArrayDeque<JSONObject>()
        var remoteApplied = false
        var answerSent = false
        var localCount = 0
        var remoteCount = 0
        @Volatile var connected = false
        @Volatile var receivedTrack = false
        var statsPending = false
        @Volatile var statsAt = 0L
        @Volatile var statsState = "not_requested"
        @Volatile var stats: Map<String, Any> = emptyMap()
        @Volatile var stageDeadline = 0L
        @Volatile var decoderState = "not_created"
        @Volatile var decoderName: String? = null
    }

    init {
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private var lease: SurfaceLease? = null
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                val next = SurfaceLease(value).also { lease = it }
                post {
                    if (closed.get()) { next.release(); return@post }
                    surface = next
                    renderer?.createEglSurface(value)
                }
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { fit() }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                val old = lease?.takeIf { it.texture === value } ?: return true
                lease = null
                post {
                    if (surface === old) {
                        surface = null
                        renderer?.releaseEglSurface { old.release() } ?: old.release()
                    } else old.release()
                }
                return false // Our lease releases after EGL has relinquished this texture.
            }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
        }
    }
    private fun owns(a: Attempt) = !closed.get() && a.valid.get() && active === a
    private fun ui(a: Attempt? = null, block: () -> Unit) { main.post { if (!closed.get() && (a == null || owns(a))) block() } }
    private fun fit() {
        val a = active ?: return
        val width = a.width.get().toFloat(); val height = a.height.get().toFloat()
        if (width <= 0 || height <= 0 || texture.width == 0 || texture.height == 0) return
        val viewRatio = texture.width.toFloat() / texture.height
        val videoRatio = width / height
        texture.setTransform(Matrix().apply {
            setScale(if (videoRatio < viewRatio) videoRatio / viewRatio else 1f,
                if (videoRatio > viewRatio) viewRatio / videoRatio else 1f, texture.width / 2f, texture.height / 2f)
        })
    }
    override fun start() { main.post(monitor); post {
        if (closed.get() || initialized) return@post
        try {
            initialize(context)
            egl = EglBase.create()
            val hardware = HardwareVideoReceiverFactory(egl!!.eglBaseContext, { !closed.get() }, { _, _ -> }, {})
            if (hardware.supportedCodecs.isEmpty()) { ui { listener.failed("hardware_decoder_unavailable") }; return@post }
            initialized = true
            ui { listener.ready() }
        } catch (_: Exception) { ui { listener.failed("native_receiver_unavailable") } }
        catch (_: LinkageError) { ui { listener.failed("native_receiver_unavailable") } }
    } }

    override fun signal(session: String, message: JSONObject) {
        if (closed.get()) return
        val type = message.optString("type")
        if (type == "video.offer") {
            if (message.has("sourceMode") && message.opt("sourceMode") != "reference") return
            val a = synchronized(lock) {
                active?.valid?.set(false)
                mutation.incrementAndGet()
                Attempt(session, message.optString("negotiation"), SatelliteVideoDiagnostics.beginAttempt(
                    if (message.opt("sourceMode") == "reference") SatelliteVideoDiagnostics.SourceMode.REFERENCE else SatelliteVideoDiagnostics.SourceMode.CAPTURE),
                    message.opt("sourceMode") == "reference").also { active = it }
            }
            waiting()
            enqueue(a, message, replace = true)
        } else {
            val a = active ?: return
            if (a.session != session || a.negotiation != message.optString("negotiation")) return
            if (type == "video.stop") { invalidate(SatelliteVideoDiagnostics.End.SENDER_STOPPED); return }
            if (type == "video.ice") {
                val overflow = synchronized(lock) { !owns(a) || ++a.remoteCount > NativeVideoPolicy.MAX_ICE }
                if (overflow) { fail(a, "signalling_overflow"); return }
                enqueue(a, message)
            }
        }
    }
    private fun remoteIce(a: Attempt, message: JSONObject) {
        val remote = authenticatedAddress(a) ?: return fail(a, "connection_lost")
        if (NativeVideoPolicy.candidate(message.getString("candidate")) != remote) return
        val index = message.get("sdpMLineIndex")
        require(index is Int && index in 0..8 && message.getString("sdpMid").length <= 64)
        val ice = IceCandidate(message.getString("sdpMid"), index, message.getString("candidate"))
        if (a.remoteApplied) applyIce(a, ice) else a.ice.add(ice)
    }

    private fun authenticatedAddress(a: Attempt): String? = SatellitePeers.snapshot(context).current?.takeIf {
        it.session == a.session && NativeVideoPolicy.localIpv4(it.address ?: "")
    }?.address

    private fun offer(a: Attempt, raw: String) {
        disposePeer()
        if (!owns(a) || !initialized) return
        val remote = authenticatedAddress(a) ?: return fail(a, "connection_lost")
        val sdp = NativeVideoPolicy.sdp(raw, setOf(remote))
        // Reject send-to-car audio/data/additional media before allocating a decoder.
        require(sdp.lineSequence().none { it == "a=recvonly" || it == "a=inactive" })
        val local = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.map { it.hostAddress ?: "" }.filter(NativeVideoPolicy::localIpv4).toSet()
        require(local.isNotEmpty())
        val decoder = HardwareVideoReceiverFactory(egl!!.eglBaseContext, { owns(a) }, { state, name ->
            post { if (owns(a)) { a.decoderState = state; a.decoderName = name } }
        }, { post { if (owns(a)) fail(a, "hardware_decoder_unavailable") } })
        val options = networkOptions()
        factory = PeerConnectionFactory.builder().setOptions(options).setVideoDecoderFactory(decoder)
            .setVideoEncoderFactory(NoVideoEncoder).createPeerConnectionFactory()
        val render = EglRenderer("DashCastSatellite").also { renderer = it }
        render.init(egl!!.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        render.setErrorCallback { post { if (owns(a)) fail(a, "playback_failed") } }
        render.addRenderListener {
            if (owns(a)) { a.rendered.incrementAndGet(); a.lastRender.set(SystemClock.elapsedRealtime()) }
        }
        surface?.takeIf { !it.released.get() }?.let { render.createEglSurface(it.texture) }
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            disableIPv6OnWifi = true; maxIPv6Networks = 0
            enableIceGatheringOnAnyAddressPorts = false; iceCandidatePoolSize = 0
        }
        val trackNotice = NativeTrackNotification({ owns(a) }, ::post) { guarded(a) { attachRemoteTrack(a, render) } }
        val connection = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { post {
                if (owns(a) && state in setOf(PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED)) fail(a, "connection_lost")
            } }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) { post {
                if (!owns(a)) return@post
                if (state == PeerConnection.PeerConnectionState.CONNECTED) { a.connected = true; SatelliteVideoDiagnostics.progress("media_connected") }
                else if (state == PeerConnection.PeerConnectionState.FAILED) fail(a, "connection_lost")
            } }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) { post { if (owns(a)) {
                if (NativeVideoPolicy.candidate(candidate.sdp) !in local) return@post
                if (++a.localCount > NativeVideoPolicy.MAX_ICE) { fail(a, "signalling_overflow"); return@post }
                val message = JSONObject().put("type", "video.ice").put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid ?: "0").put("sdpMLineIndex", candidate.sdpMLineIndex)
                if (a.answerSent) send(a, message) else a.outgoing.add(message)
            } } }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) { channel.close(); channel.dispose() }
            override fun onRenegotiationNeeded() = Unit
            override fun onTrack(transceiver: RtpTransceiver) {
                // The SDK owns this wrapper; queue a notification rather than its borrowed handle.
                trackNotice.received()
            }
        }) ?: error("peer_unavailable")
        peer = connection
        connection.setAudioPlayout(false); connection.setAudioRecording(false)
        ui(a) { listener.status(a.reference, false) }
        a.stageDeadline = SystemClock.elapsedRealtime() + 8_000
        connection.setRemoteDescription(observer(a, applied = {
            a.remoteApplied = true
            while (a.ice.isNotEmpty() && owns(a)) applyIce(a, a.ice.removeFirst())
            connection.transceivers.forEach { transceiver ->
                check(transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                val codecs = factory!!.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
                check(transceiver.setCodecPreferences(codecs.filter { it.name.equals("H264", true) }).isSuccess)
            }
            a.stageDeadline = SystemClock.elapsedRealtime() + 8_000
            connection.createAnswer(observer(a, created = { answer ->
                a.stageDeadline = SystemClock.elapsedRealtime() + 8_000
                connection.setLocalDescription(observer(a, applied = {
                    send(a, JSONObject().put("type", "video.answer").put("sdp", NativeVideoPolicy.sdp(connection.localDescription.description, local)))
                    a.answerSent = true; a.stageDeadline = 0
                    a.watchdog.answered(SystemClock.elapsedRealtime())
                    while (a.outgoing.isNotEmpty() && owns(a)) send(a, a.outgoing.removeFirst())
                }), answer)
            }), MediaConstraints())
        }), SessionDescription(SessionDescription.Type.OFFER, sdp))
    }
    private fun attachRemoteTrack(a: Attempt, render: EglRenderer) {
        if (!owns(a)) return
        check(track == null)
        // getReceivers() replaces/disposes its previous Java cache. Query exactly once per peer,
        // and use this owned cached wrapper until removeSink + PeerConnection.dispose().
        val receivers = checkNotNull(peer).receivers
        require(receivers.size == 1)
        val incoming = receivers.single().track() as? VideoTrack ?: error("video_track_unavailable")
        track = incoming
        a.receivedTrack = true; SatelliteVideoDiagnostics.progress("track_received")
        val input = VideoSink { frame ->
            if (owns(a)) {
                a.decoded.incrementAndGet()
                a.width.set(frame.rotatedWidth.toLong()); a.height.set(frame.rotatedHeight.toLong())
                if (owns(a)) render.onFrame(frame)
            }
        }.also { sink = it }
        incoming.addSink(input)
    }
    private fun applyIce(a: Attempt, ice: IceCandidate) { if (owns(a) && peer?.addIceCandidate(ice) != true) fail(a, "negotiation_failed") }
    private fun observer(a: Attempt, created: (SessionDescription) -> Unit = {}, applied: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) { post { if (owns(a)) guarded(a) { created(description) } } }
        override fun onSetSuccess() { post { if (owns(a)) guarded(a, applied) } }
        override fun onCreateFailure(error: String) { post { if (owns(a)) fail(a, "negotiation_failed") } }
        override fun onSetFailure(error: String) { post { if (owns(a)) fail(a, "negotiation_failed") } }
    }
    private fun send(a: Attempt, message: JSONObject) { if (owns(a)) ui(a) { listener.reply(a.session, message.put("negotiation", a.negotiation).toString()) } }
    private fun guarded(a: Attempt, action: () -> Unit) {
        try { action() } catch (_: Exception) { if (owns(a)) fail(a, "negotiation_failed") }
        catch (_: LinkageError) { if (owns(a)) fail(a, "native_receiver_unavailable") }
    }
    /** Runs on main independently of a blocked MediaCodec, EGL, SDP or getStats call. */
    private fun tick(a: Attempt) {
        if (!owns(a)) return
        val now = SystemClock.elapsedRealtime()
        a.lastRender.get().takeIf { it > 0 }?.let(a.watchdog::rendered)
        observe(a)
        if (a.watchdog.expired(now)) { fail(a, "video_timeout"); return }
        if (a.stageDeadline > 0 && now >= a.stageDeadline) { fail(a, "negotiation_failed"); return }
        if (a.statsAt > 0 && now - a.statsAt >= 8_000 && a.statsState == "pending") a.statsState = "timed_out"
        if (statsQueued.compareAndSet(false, true)) post {
            statsQueued.set(false)
            if (owns(a) && !a.statsPending) {
                a.statsPending = true; a.statsAt = SystemClock.elapsedRealtime(); a.statsState = "pending"
                peer?.getStats { report -> post { if (owns(a)) {
                    a.statsPending = false; a.statsState = "completed"
                    a.stats = report.statsMap.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }?.members ?: emptyMap()
                } } }
            }
        }
        if (owns(a)) { fit(); listener.status(a.reference, a.rendered.get() > 0) }
    }
    private fun observe(a: Attempt, terminal: Boolean = false) {
        val names = listOf("bytesReceived", "packetsReceived", "packetsLost", "framesReceived", "framesDecoded", "keyFramesDecoded", "framesDropped", "pliCount", "nackCount", "firCount")
        val values = names.associateWith { (a.stats[it] as? Number)?.toLong()?.takeIf { it in 0..9_000_000_000_000L } }
        SatelliteVideoDiagnostics.observe(a.diagnostic, SatelliteVideoDiagnostics.Observation(values, "H264", a.statsState,
            a.receivedTrack, a.connected, a.decoded.get() > 0, mapOf("backend" to "native", "sdk" to NativeVideoPolicy.SDK,
                "decoderState" to a.decoderState, "decoderName" to a.decoderName, "hardware" to if (a.decoderState == "initialized") true else null,
                "sinkFrames" to a.decoded.get(), "renderedFrames" to a.rendered.get(),
                "videoWidth" to a.width.get(), "videoHeight" to a.height.get())), terminal)
    }
    private fun fail(a: Attempt, code: String) {
        if (!owns(a)) return
        observe(a, true)
        val raw = JSONObject().put("type", "video.error").put("negotiation", a.negotiation).put("code", code).toString()
        val epoch = invalidate(when (code) { "video_timeout" -> SatelliteVideoDiagnostics.End.VIDEO_TIMEOUT; "connection_lost" -> SatelliteVideoDiagnostics.End.CONNECTION_LOST; else -> SatelliteVideoDiagnostics.End.OTHER_FAILURE }, a) ?: return
        main.post {
            if (!closed.get() && mutation.get() == epoch && active == null) {
                listener.reply(a.session, raw)
                if (mutation.get() == epoch) listener.failed(code)
            }
        }
    }
    private fun invalidate(end: SatelliteVideoDiagnostics.End, expected: Attempt? = null): Long? {
        val retired = synchronized(lock) {
            if (expected != null && active !== expected) return null
            val previous = active
            previous?.valid?.set(false); active = null
            inbox.clear()
            mutation.incrementAndGet() to previous
        }
        retired.second?.let { SatelliteVideoDiagnostics.endAttempt(it.diagnostic, end) }
        waiting()
        post { if (active == null) disposePeer() }
        return retired.first
    }
    override fun reset() { invalidate(SatelliteVideoDiagnostics.End.CONTROL_RESET) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.removeCallbacks(monitor)
        invalidate(SatelliteVideoDiagnostics.End.VIEWER_HIDDEN)
        post {
            disposePeer()
            surface?.release(); surface = null
            runCatching { egl?.release() }; egl = null
        }
    }
    private fun disposePeer() {
        val oldTrack = track; val oldSink = sink; track = null; sink = null
        if (oldTrack != null && oldSink != null) runCatching { oldTrack.removeSink(oldSink) }
        val connection = peer; peer = null
        if (connection != null) { runCatching { connection.close() }; runCatching { connection.dispose() } }
        runCatching { renderer?.release() }; renderer = null
        runCatching { factory?.dispose() }; factory = null
    }
    companion object {
        internal fun networkOptions() = PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = true // AP/P2P interfaces may have no Android Network handle.
            networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or PeerConnectionFactory.Options.ADAPTER_TYPE_VPN or
                PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
        }
        private val worker = Handler(HandlerThread("satellite-native-video").apply { start() }.looper)
        private var nativeInitialized = false
        private fun initialize(context: Context) {
            if (nativeInitialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE).setEnableInternalTracer(false).createInitializationOptions())
            nativeInitialized = true
        }
        private object NoVideoEncoder : VideoEncoderFactory {
            override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = null
            override fun getSupportedCodecs() = emptyArray<VideoCodecInfo>()
        }
    }
}
