# Android 29 satellite release check

JVM and Robolectric tests do not execute the minified release dex on ART. Run this
check after changing the Android build tools, R8 rules, satellite transport dependencies
or AndroidKeyStore identity policy. Use `--tls` to test a complete encrypted connection.
Use a dedicated, already booted **stock Android 29 emulator**. The probe refuses
physical devices and other Android API levels.

```sh
python3 .github/scripts/check_satellite_release_art.py \
  --sdk "$ANDROID_HOME" --serial emulator-5554 \
  --apk app/build/outputs/apk/release/DashCast-v1.9.17-beta-release.apk \
  --mapping app/build/outputs/mapping/release/mapping.txt --tls
```

Supply the APK and R8 mapping from the **same release build**. The SDK must contain
build-tools 36.0.0 and platform android-36; a JDK providing `javac` is required.
The script compiles a small probe, copies it and the APK to temporary emulator files,
then removes those files. It does not install the app or call DashCast application/service
lifecycle methods. `--tls` bootstraps only the emulator's system context and real platform
AndroidKeyStore provider, which app_process does not initialize automatically.

A passing check prints `ART_SERVER_CLASS_OK`, `ART_SERVER_CONSTRUCTOR_OK`,
`ART_CONTROL_LISTENER_BOUND` and `ART_CONTROL_LISTENER_STOPPED`. The listener check
verifies the tested server's own bound channel, connects over emulator loopback, and
stops its selector thread. Without `--tls`, preferences and TLS context are test substitutes
and only listener startup is covered. With `--tls`, preferences remain test substitutes,
but the certificate and TLS context come from **the actual optimized APK and the emulator's
AndroidKeyStore**, not a PKCS12/JDK fixture. The probe creates the original v1 key policy
under the emulator's shell UID, verifies that raw TLS signing is rejected, then calls
the mapped production TLS context/profile methods. It verifies immutable v2 migration
without deleting v1, repeated preparation with a stable fingerprint, explicit server
identity selection while both aliases exist, wrong and legacy certificate pin rejection,
wrong-token authentication rejection, a pinned WSS `hello`/`welcome` and reconnection.
Only short fixed test credentials are used; no payloads, tokens or certificate contents
are printed. Pre-existing probe aliases cause refusal, and only newly created probe
aliases are deleted in cleanup.

This does **not** validate the car's hardware-backed KeyStore implementation, hotspot
routing, pairing transfer or vehicle outputs. Those require separate integration and
parked-vehicle checks. `--load-only` limits
the probe to ART class verification when investigating generated-dex failures.

## Regression reproduced on 2026-10-11

The original minified 1.9.15-beta APK failed on stock Android 29 ART before constructing
the server. R8 inlined `AbstractWebSocket.startConnectionLostTimer()` into the server's
`run()` method and emitted a field read at dex offset `0x77` without a catch-all covering
the held monitor. ART reported `VerifyError` on the first load and
`NoClassDefFoundError` on subsequent loads. This matches the supplied DiLink 3/API 29
startup log, though the vehicle's own full verifier detail was not present in that log.

The narrow keep rule preserves the optimization boundary of that one timer method;
obfuscation and the remaining app/library optimization stay enabled. The corrected
minified artifact passed class loading, construction, owned listener binding and
selector shutdown on stock Android 29. Real DiLink firmware remains to be retested.

## AndroidKeyStore handshake regression

After listener startup was fixed, the supplied DiLink 3/API 29 logs showed Conscrypt
failing in `CryptoUpcalls.rsaSignDigestWithPrivateKey` with `Incompatible digest`.
The old RSA key permitted only SHA-256/SHA-512 and PKCS1 signatures. API 29 uses
an already encoded digest through private-key `RSA/ECB/NoPadding`; the actual
KeyStore operation needs `DIGEST_NONE` and NoPadding authorization. This is still
`PURPOSE_SIGN`: no encryption/decryption purposes or trust relaxation are added.

The v2 alias permits this provider operation, which is checked before either an
SSL context or pairing fingerprint is published. A single-alias key manager presents
exactly the advertised certificate instead of letting a general KeyManagerFactory
choose a legacy or unrelated installation key. Existing v1 material is retained,
and an unusable existing v2 identity fails closed without regeneration or fallback.

Upgrading an existing v1 installation changes its certificate and displayed receiver
ID. **Pair DashSat again using DashCast's Pair a device button. Do not uninstall
either application.** The shared token and any open pairing countdown are preserved;
migration must not call token rotation, which would close that countdown. A page
notice remains until a new authenticated connection succeeds. Dated last-connection
history remains historical information and is not proof of a current connection.

The stock API 29 check exercises actual AndroidKeyStore keys and the minified receiver
through pinned TLS, the WebSocket upgrade and authenticated control frames. It does
not establish compatibility with the BYD ROM's hardware provider or prove guidance
delivery on the vehicle; retest those on the parked vehicle with DashSat 0.6.0-dev.

## Native video receiver from 1.9.46-beta

The control/TLS ART check above remains required. The default video viewer now uses app-local
native WebRTC, independently of the installed system WebView. Its JNI entry points, EGL lifecycle,
receive-only peer callbacks and hardware absence must also be checked using the optimized APK.
An x86 emulator may load the same AAR's matching x86 library beside the production ARM-only dex;
record that ABI substitution explicitly. It does not prove ARM hardware decoding or vehicle output.
Actual acceptance requires the target car's selected hardware decoder to initialize and produce
both decoded textures and completed EGL swaps. See [NATIVE_WEBRTC.md](NATIVE_WEBRTC.md).

The retained `check_satellite_webview.py`, old bundled HTML and Node fixtures are historical
WebView compatibility probes. They neither gate native startup nor prove native media decoding.
Older WebView report fields may remain unavailable; current native reports use their separate
backend, SDK, selected decoder, initialization, sink-frame and rendered-frame evidence.
