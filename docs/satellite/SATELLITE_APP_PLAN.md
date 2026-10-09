# Dedicated Android satellite application

Proposed repository: `Kiroha/dashcast-satellite` (not created by this change).
Keep the companion independent of BYD firmware, CAN, ADB, platform signing and vehicle diagnostics.
DashCast owns OEM outputs; the companion owns source observation, screen capture and transmission.

## Why a separate repository

Different runtime permissions, target SDK, devices, release cadence and signing identity justify a
separate application/repository. Use a regular Android application signing key, never DashCast's
vehicle platform key. Keep a single companion app with optional guidance/video modes; two separate
companion apps would duplicate pairing, network recovery, foreground-service policy and support.
Do not create a third shared-code repository yet. Share a versioned wire contract and compatibility
fixtures first; extract a library only after actual duplication makes maintenance harder.

## First milestone: guidance on the real Carlinkit

1. Record exact Carlinkit model/Android version, notification-access availability, WebView version,
   hotspot reachability and whether its SIM remains usable while connected locally.
2. Build a Kotlin application with pairing-profile import, certificate pinning and protected token
   storage. Connect one persistent WSS socket to DashCast, bound to the LAN network. Add bounded
   latest-value delivery, increasing sequence numbers, real source age and reconnect backoff.
3. Implement an explicitly granted NotificationListenerService plus separate Maps/ABRP adapters.
   Reuse or extract the proven pure parsing logic where possible, with attribution/history;
   neither application assumes all maneuvers are present in notification text. Unsupported image
   recognition must yield an explicit unsupported state rather than a guessed turn.
4. Send portable guidance snapshots and an explicit stop. Distinguish control connectivity from
   valid source guidance. Re-snapshot genuinely active navigation for liveness; a timer alone must
   not make cached source data fresh. ABRP support is conditional on observable usable guidance.
5. Test overnight/ignition restarts, user-grant revocation, Wi-Fi reconnect and source stop. The
   receiver already survives the car UI closing through its existing foreground keeper.

## Second milestone: receive a map in the cluster

Capture on the companion via MediaProjection with the required user consent and foreground
service. Select one application where Android supports it; older devices generally mirror the
display. Screen capture alone does not provide a second independent invisible application display.
Do not promise unattended video restart where Android requires a new capture consent.

Add a maintained, pinned native WebRTC sender dependency in the **companion**, single video track,
local ICE only, WSS signalling as specified in `PROTOCOL_V1.md`. Begin with modest resolution/frame
rate, negotiate a supported codec, and measure screen-to-cluster latency, CPU/GPU, thermal behavior
and Overdrive frame loss on the vehicle. The DashCast receiver deliberately uses installed WebView
WebRTC instead of shipping a large native codec AAR to all users. If OEM WebView proves insufficient,
evaluate a native receiver from that evidence, including APK size and ABI impact.

Keep interaction on the box/main display initially. Remote input would need its own explicit
permission model and coordinate mapping; capture permission does not authorize input injection.

## Repository baseline

* Modules: one `app`; packages for `pairing`, `transport`, `navigation` and `capture`.
* CI: unit tests, protocol fixture tests, lint and debug assembly; signed release pipeline with
  independent secrets. Keep support logs free of tokens, SDP, notifications and route text.
* Pin a copy of receiver protocol v1 and fixture hashes to a reviewed DashCast commit. Version
  application releases independently from protocol versions.
* Test standalone guidance first, then video alongside Overdrive, then automatic recovery.

Acceptance is the user's actual vehicle displaying valid guidance/video after reconnection,
with source loss clearing old information and normal DashCast behavior preserved when disabled.
