# ============================================================================
# DashCast — R8 / ProGuard keep rules  (enabled with minifyEnabled true)
#
# WARNING: a successful build does NOT prove correctness. This app relies on
# reflection, JNI, and classes launched by FULLY-QUALIFIED NAME via app_process
# (the uid-2000 daemon). R8 cannot see those as reachable, so getting a rule
# wrong strips/renames a class and the failure only appears AT RUNTIME ON THE
# CAR (daemon dead, no cluster/CAN/HUD). Validate on the DL3 + DL5 on-car matrix
# (daemon spawn, cluster launch/resize, CAN/HUD, IAM launch)
# before shipping any minified release.
# ============================================================================

# ---- attributes needed for JNI / reflection / generics / stack traces ----
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions, SourceFile, LineNumberTable

# ============================================================================
# CRITICAL — privileged daemon subsystem.
# ProxyDaemonMain / MirrorDaemon / TaskRemover are started by name via
# app_process64 from a runtime-built shell command; the binder DESCRIPTOR
# strings (e.g. "com.byd.dashcast.proxy.daemon.IProxyDaemon") are hardcoded and
# must match the class identity on both sides. Keep the whole proxy subsystem
# un-shrunk and un-obfuscated — safety over shrink benefit for this package.
# ============================================================================
-keep class com.byd.dashcast.proxy.** { *; }
-keepnames class com.byd.dashcast.proxy.** { *; }

# ---- Binder IPC types + Parcelable (CREATOR must survive) ----
-keep class * implements android.os.IInterface { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keep class com.byd.dashcast.**.*Parcelable { *; }

# ============================================================================
# BYD Auto SDK — compileOnly stub in libs/byd-auto-api-stubs.jar; the real
# classes live on the head unit. Our own subclasses of AbsBYDAuto*Listener are
# registered with the framework and invoked via reflection callbacks, so their
# override methods must not be stripped/renamed.
# ============================================================================
-keep class android.hardware.bydauto.** { *; }
-keep class * extends android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener { *; }
-keep class * extends android.hardware.bydauto.**$AbsBYDAutoSettingListener { *; }
-keep class * extends android.hardware.bydauto.setting.AbsBYDAutoSettingListener { *; }
-keepclassmembers class com.byd.dashcast.**.CanFeedbackListener** { *; }
-dontwarn android.hardware.bydauto.**

# ---- android.car.* (AAOS variants) — reflection, not in every ROM ----
-keep class android.car.** { *; }
-dontwarn android.car.**

# ---- hidden framework APIs reached by reflection (provided by platform, not
#      bundled in the APK — suppress "can't find referenced class") ----
-dontwarn android.app.**
-dontwarn android.os.SystemProperties
-dontwarn android.view.SurfaceControl
-dontwarn android.hardware.display.**

# ============================================================================
# Native / JNI dependencies — Tink (via androidx.security-crypto).
#
# The ONNX Runtime, Vosk, Kaldi and JNA rules that used to live here went with the
# voice subsystem in 1.8.33-beta. None of those packages is in the dependency graph
# any more, so the -keep rules matched nothing and the -dontwarn lines suppressed
# warnings about absent classes — which is how a keep file quietly stops describing
# the app it protects. The generic native-methods rule below stays: it is not
# specific to those libraries.
# ============================================================================
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# security-crypto / Tink registers primitives reflectively
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---- enums referenced by name (valueOf / values) ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Kotlin runtime metadata (reflection, when-mappings) ----
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-dontwarn kotlin.**

# ---- Android components are auto-kept by AGP; add explicit keeps here if any
#      helper is ever loaded via Class.forName in the future. ----
