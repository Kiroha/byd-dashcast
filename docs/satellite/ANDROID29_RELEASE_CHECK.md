# Android 29 satellite release check

JVM and Robolectric tests do not execute the minified release dex on ART. Run this
check after changing the Android build tools, R8 rules or satellite transport dependencies.
Use a dedicated, already booted **stock Android 29 emulator**. The probe refuses
physical devices and other Android API levels.

```sh
python3 .github/scripts/check_satellite_release_art.py \
  --sdk "$ANDROID_HOME" --serial emulator-5554 \
  --apk app/build/outputs/apk/release/DashCast-v1.9.15-beta-release.apk \
  --mapping app/build/outputs/mapping/release/mapping.txt
```

Supply the APK and R8 mapping from the **same release build**. The SDK must contain
build-tools 36.0.0 and platform android-36; a JDK providing `javac` is required.
The script compiles a small probe, copies it and the APK to temporary emulator files,
then removes those files. It does not install the app or call Android service lifecycle methods.

A passing check prints `ART_SERVER_CLASS_OK`, `ART_SERVER_CONSTRUCTOR_OK`,
`ART_CONTROL_LISTENER_BOUND` and `ART_CONTROL_LISTENER_STOPPED`. The listener check
verifies the tested server's own bound channel, connects over emulator loopback, and
stops its selector thread. Preferences and TLS context are local test substitutes:
this check does **not** validate the installation's AndroidKeyStore certificate,
pairing, a pinned TLS handshake, vehicle hotspot routing or vehicle outputs.
Those require separate integration and parked-vehicle checks. `--load-only` limits
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
