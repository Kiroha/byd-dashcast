# App-local native WebRTC receiver

Reviewed October 17, 2026. DashCast 1.9.46-beta and DashSat 0.9.9-dev pin
`io.github.webrtc-sdk:android:150.7871.01` from Maven Central. Publisher GitHub identifies this M150
build as its latest release; the later M144 maintenance build is an older Chromium branch.
No dynamic version, custom native patch, system WebView update or firmware change is used.

- [Publisher release](https://github.com/webrtc-sdk/android/releases/tag/v150.7871.01)
- [Maven metadata](https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/maven-metadata.xml)
- AAR SHA-256: `0a1627b1a48c2bc17d9a40d62fc47bd45166f44a311e95917f147c402de379b0`.
- `verifyWebRtcArchive` verifies the actual debug and release runtime AAR before builds.
- RX keeps only `armeabi-v7a` (6,809,404 raw library bytes) and `arm64-v8a` (12,287,312 bytes).
  TX retains those and x86/x86_64 for companion compatibility. Locally assembled optimized APK: 26,213,017 bytes
  (25.00 MiB), before the final commit/VCS metadata refresh.
- RX minSdk 28 / targetSdk 29 / compileSdk 36 and its signing identity are unchanged.
- The AAR contains no consumer ProGuard rules; RX explicitly preserves `org.webrtc.**` JNI classes,
  constructors and callbacks. Test the actual optimized APK on Android 29, not only JVM fixtures.

The MIT fork license and full upstream/third-party distribution notices are bundled in both APKs
as `webrtc-sdk-license.txt` and `webrtc-third-party-notices.md`. Sources are pinned to
[publisher LICENSE](https://github.com/webrtc-sdk/android/blob/v150.7871.01/LICENSE) and
[upstream notices](https://github.com/webrtc-sdk/android/blob/v150.7871.01/Licenses/WEBRTC.md).
SHA-256 respectively: `e6b282fe6c0fb353928923470457f31b44cbab203effd60c0cde4a5bb96c8aec` and
`d1f9382c6878ac024155fd6d44a5977329108bb8b0a01cea40e4a2f1d7de252e`.

## Hardware contract and H.265 boundary

The exact AAR's `VideoCodecMimeType` contains H265; its MediaCodec encoder and decoder factories
have H265 selection branches. This is Java API/build evidence, not proof that a particular
MediaCodec, native SDP/RTP configuration or hardware pair supports a working H.265 session.
Chip-level H.265 support alone is insufficient. H.265 remains unadvertised in this release.

RX uses only `HardwareVideoDecoderFactory` with a non-null shared EGL context and no software
factory. Only packetization-mode 1 H.264 Baseline is advertised. API 29+ requires the actual selected
codec's `isHardwareAccelerated && !isSoftwareOnly`; API 28 has no equivalent flag and accepts only
known vendor name families (QCOM, Exynos, MTK, HiSilicon, NVIDIA), failing closed otherwise.
A decoder object is distinct from successful `initDecode(OK)`, borrowed decoded texture callbacks,
and completed EGL swaps. Reports expose those separate states and the bounded selected codec name.
The wrapper explicitly keeps Java callbacks active (`createNative=0`); a native pointer cannot bypass
the texture/attempt guard. No CPU image readback or software decoder fallback is installed.
TX advertises only the existing hardware-guarded H.264 Baseline 3.1 path (or consented pre-encoded
reference test), and exposes no receive decoder.

## Lifecycle, network and validation

A single serialized worker owns every peer/decoder/renderer/EGL allocation and teardown. Main hides
and invalidates a preview immediately on stop; cleanup never holds the synchronized signalling hub.
A separate main monitor can blank/invalidate timed-out media even while native cleanup is blocked.
It does not create another native worker or decoder to recover around that blocked operation.
Replacement offers preempt a bounded 32-entry signal inbox; ICE count is bounded before posting.
All callbacks, terminal UI/errors and render progress are fenced by current attempt and mutation epoch.
The SDK observer owns callback transceivers and automatically disposes them at observer teardown.
The JavaDoc's "automatically" does not mean disposal immediately after callback return. The exact
[pinned JNI implementation](https://github.com/webrtc-sdk/webrtc/blob/73cb8180f7258ee292878d6edd05177f41883962/sdk/android/src/jni/pc/peer_connection.cc#L450)
retains the wrappers. Our callback queues only a notification; the worker queries the current peer
receiver cache exactly once, then removes its sink before peer disposal. Re-querying the SDK receiver
cache would dispose its previous Java wrappers, so no other task may refresh that cache while bound.

The receiver disables Android NetworkMonitor so native getifaddrs can discover hotspot/AP/P2P
interfaces lacking an Android Network handle. It admits UNKNOWN interface types while ignoring
cellular/VPN/loopback; strict private IPv4 host-UDP candidates and current authenticated socket-peer
filtering remain authoritative. No process-wide network binding, DNS, STUN, TURN or relay is used.

Run the consented reference test first, then ABRP/Maps capture. Verify selected hardware codec,
initialized state, positive decoded and rendered counts on the actual DiLink/API/ROM. Re-test the
same-screen CarPlay switch sequence, source stop/revoke, Wi-Fi recovery and cluster destination loss.
Measure 10/30-minute car CPU/GPU, heat, latency and Overdrive loss against local app rendering.
Hardware availability, vehicle rendering and lower workload are not established by static/JVM tests,
or by an x86 emulator native-load test using the same AAR's different ABI.
