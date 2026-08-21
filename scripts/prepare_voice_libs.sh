#!/usr/bin/env bash
# Prepares voice_native_libs_<abi>.zip for upload to the GitHub release that
# VoiceLibsManager downloads from at runtime.
#
# Usage:
#   ./scripts/prepare_voice_libs.sh            # arm64-v8a (what the release ships)
#   ./scripts/prepare_voice_libs.sh armeabi-v7a
#
# Run it from the repository root, after at least one build, so that the
# dependencies are resolved and their .so files are unpacked in the Gradle cache.
#
# After running, upload the produced file to the GitHub release:
#   gh release upload voice-libs-v1 /tmp/voice_libs_staging/voice_native_libs_arm64.zip
#
# Or create the release if it doesn't exist yet:
#   gh release create voice-libs-v1 \
#       --title "Voice Native Libs v1" \
#       --notes "ONNX Runtime + Vosk — arm64-v8a" \
#       --prerelease \
#       /tmp/voice_libs_staging/voice_native_libs_arm64.zip
#
# Note on ABIs: the APK is built for arm64-v8a AND armeabi-v7a
# (app/build.gradle, defaultConfig.ndk.abiFilters), but only the arm64 archive
# is published. Every DiLink 3/4/5 head unit seen so far is arm64, so a 32-bit
# unit would install fine and then fail to load the voice libraries. Passing an
# ABI argument is the lever if that ever needs fixing.

set -euo pipefail

ABI="${1:-arm64-v8a}"
STAGING=/tmp/voice_libs_staging
# Do not hard-code the cache layout. It is not a contract, and it has already
# changed once underneath this script: Gradle 8 moved transforms from
# caches/transforms-3/ to caches/<version>/transforms/ and dropped the
# "jetified-" directory prefix along with the jetifier. Searching the whole
# cache by file name and ABI survives the next reshuffle too.
GRADLE_CACHE="$HOME/.gradle/caches"
LIBS=(libonnxruntime.so libonnxruntime4j_jni.so libvosk.so libjnidispatch.so)

if [ ! -d "$GRADLE_CACHE" ]; then
    echo "ERROR: no Gradle cache at $GRADLE_CACHE."
    echo "Build the project once first: ./gradlew assembleDebug"
    exit 1
fi

echo "Creating staging directory: $STAGING"
rm -rf "$STAGING"
mkdir -p "$STAGING"

echo "Locating .so files for $ABI under $GRADLE_CACHE ..."

for NAME in "${LIBS[@]}"; do
    # Newest match wins, and every candidate is printed: a stale cache can hold
    # several versions of the same library, and picking one silently is how you
    # ship a mismatched pair.
    mapfile -t FOUND < <(find "$GRADLE_CACHE" -type f -name "$NAME" -path "*/$ABI/*" \
                         -printf '%T@ %p\n' 2>/dev/null | sort -rn | cut -d' ' -f2-)
    if [ "${#FOUND[@]}" -eq 0 ]; then
        echo "ERROR: $NAME not found for $ABI."
        echo "Build the project once first: ./gradlew assembleDebug"
        exit 1
    fi
    if [ "${#FOUND[@]}" -gt 1 ]; then
        echo "  NOTE: ${#FOUND[@]} copies of $NAME in the cache, taking the most recent:"
        printf '        %s\n' "${FOUND[@]}"
    fi
    echo "  Found: ${FOUND[0]}"
    cp "${FOUND[0]}" "$STAGING/$NAME"
done

echo ""
echo "Staging contents:"
ls -lh "$STAGING"

ZIP="voice_native_libs_${ABI//-/_}.zip"
# The published asset is named voice_native_libs_arm64.zip; keep that exact
# name for the default ABI, because VoiceLibsManager downloads it by URL.
[ "$ABI" = "arm64-v8a" ] && ZIP="voice_native_libs_arm64.zip"

echo ""
echo "Creating $ZIP ..."
cd "$STAGING"
python3 - "$ZIP" "${LIBS[@]}" <<'PY'
import sys, zipfile
zip_name, files = sys.argv[1], sys.argv[2:]
with zipfile.ZipFile(zip_name, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in files:
        z.write(f, f)
        print('  Added ' + f)
# Read the archive back rather than trusting that writing it worked.
with zipfile.ZipFile(zip_name) as z:
    bad = z.testzip()
    if bad:
        sys.exit('ERROR: corrupt entry in archive: ' + bad)
    missing = set(files) - set(z.namelist())
    if missing:
        sys.exit('ERROR: missing from archive: ' + ', '.join(sorted(missing)))
print('  Verified %d entries' % len(files))
PY

echo ""
ls -lh "$STAGING/$ZIP"

echo ""
echo "Done! Now run:"
echo "  gh release upload voice-libs-v1 $STAGING/$ZIP --clobber"
echo ""
echo "Or, if the release does not exist yet:"
echo "  gh release create voice-libs-v1 \\"
echo "      --title \"Voice Native Libs v1\" \\"
echo "      --notes \"ONNX Runtime + Vosk — $ABI\" \\"
echo "      --prerelease \\"
echo "      $STAGING/$ZIP"
