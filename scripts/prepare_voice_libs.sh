#!/usr/bin/env bash
# Prepares voice_native_libs_arm64.zip for upload to the GitHub release
# that VoiceLibsManager downloads from.
#
# Usage:
#   cd <repo root>
#   ./scripts/prepare_voice_libs.sh
#
# After running, upload the produced file to the GitHub release:
#   gh release upload voice-libs-v1 /tmp/voice_libs_staging/voice_native_libs_arm64.zip
#
# Or create the release if it doesn't exist yet:
#   gh release create voice-libs-v1 \
#       --title "Voice Native Libs v1" \
#       --notes "ONNX Runtime 1.17.1 + Vosk 0.3.47 — arm64-v8a" \
#       --prerelease \
#       /tmp/voice_libs_staging/voice_native_libs_arm64.zip

set -e

STAGING=/tmp/voice_libs_staging
GRADLE_CACHE="$HOME/.gradle/caches/transforms-3"

echo "Creating staging directory: $STAGING"
rm -rf "$STAGING"
mkdir -p "$STAGING"

echo "Locating .so files in Gradle cache..."

LIBONNX=$(find "$GRADLE_CACHE" -path "*/jetified-onnxruntime-android-1.17.1/jni/arm64-v8a/libonnxruntime.so" | head -1)
LIBONNXJNI=$(find "$GRADLE_CACHE" -path "*/jetified-onnxruntime-android-1.17.1/jni/arm64-v8a/libonnxruntime4j_jni.so" | head -1)
LIBVOSK=$(find "$GRADLE_CACHE" -path "*/jetified-vosk-android-0.3.47/jni/arm64-v8a/libvosk.so" | head -1)
LIBJNI=$(find "$GRADLE_CACHE" -path "*/jetified-jna-*/jni/arm64-v8a/libjnidispatch.so" | head -1)

for LIB in "$LIBONNX" "$LIBONNXJNI" "$LIBVOSK" "$LIBJNI"; do
    if [ -z "$LIB" ]; then
        echo "ERROR: Could not find a required .so file in Gradle cache."
        echo "Make sure you have built the project at least once (./gradlew assembleDebug)."
        exit 1
    fi
    NAME=$(basename "$LIB")
    echo "  Found: $LIB"
    cp "$LIB" "$STAGING/$NAME"
done

echo ""
echo "Staging contents:"
ls -lh "$STAGING"

echo ""
echo "Creating zip..."
cd "$STAGING"
python3 -c "
import zipfile
files = ['libonnxruntime.so', 'libonnxruntime4j_jni.so', 'libjnidispatch.so', 'libvosk.so']
with zipfile.ZipFile('voice_native_libs_arm64.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for f in files:
        z.write(f, f)
        print('  Added ' + f)
"
echo ""
ls -lh "$STAGING/voice_native_libs_arm64.zip"

echo ""
echo "Done! Now run:"
echo "  gh release create voice-libs-v1 \\"
echo "      --title \"Voice Native Libs v1\" \\"
echo "      --notes \"ONNX Runtime 1.17.1 + Vosk 0.3.47 — arm64-v8a\" \\"
echo "      --prerelease \\"
echo "      $STAGING/voice_native_libs_arm64.zip"
