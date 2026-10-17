# DashCast 1.9.46-beta

- Replace the satellite viewer's old system-WebView video backend with app-local native WebRTC
  150.7871.01, aligned with DashSat. No system WebView or firmware update is required.
- Preserve M150 JNI Zero bootstrap entry points through release optimization, with an actual-APK
  declaration gate and separate Android runtime verification.
- Require hardware H.264 decoding with texture rendering; show an explicit failure if unavailable.
  Reports distinguish decoder creation, initialization, decoded textures and completed renders.
- Discover vehicle hotspot interfaces without changing process routing; retain authenticated local
  IPv4-only ICE, receive-only video and separate guidance/control state.
- Bound signalling and timeouts, clear stopped/stale images, and release the main preview decoder
  when switching to CarPlay. Existing cluster destination checks remain unchanged.

Install DashSat 0.9.9-dev alongside this release. H.265 is not yet enabled. Native video on the actual
vehicle and lower sustained CPU/GPU load still require testing; decoding/composition remain on the
car. The native SDK increases APK size, while retaining ARMv7 and ARM64 support.
