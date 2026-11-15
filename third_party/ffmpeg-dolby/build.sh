#!/bin/bash
# Rebuilds core/src/main/jniLibs/<abi>/libffmpegJNI.so: FFmpeg with the Dolby Digital (AC-3)
# and Dolby Digital Plus (E-AC-3) decoders only, and Media3's JNI glue (ffmpeg_jni.cc).
#
#   third_party/ffmpeg-dolby/build.sh [path to the Android NDK]
#
# Needs curl, make, and the SDK's CMake 3.22.1 (with Ninja). The NDK defaults to the version
# core/build.gradle.kts asks for.
set -euo pipefail

FFMPEG_VERSION=7.1.1
FFMPEG_SHA256=733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
NDK="${1:-$SDK/ndk/27.2.12479018}"
CMAKE_BIN="$SDK/cmake/3.22.1/bin"
HOST=linux-x86_64
[[ "$(uname)" == Darwin ]] && HOST=darwin-x86_64
STRIP="$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-strip"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/jni"
cd "$WORK"
curl -sSfL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
echo "$FFMPEG_SHA256  ffmpeg.tar.xz" | sha256sum -c -
tar xf ffmpeg.tar.xz
mv "ffmpeg-$FFMPEG_VERSION" jni/ffmpeg

# FFmpeg's static libraries for every ABI (minSdk 26), then the JNI library on top of them.
bash "$HERE/build_ffmpeg.sh" "$WORK" "$NDK" "$HOST" 26 ac3 eac3
cp "$HERE/CMakeLists.txt" "$HERE/ffmpeg_jni.cc" "$WORK/jni/"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    "$CMAKE_BIN/cmake" -S "$WORK/jni" -B "$WORK/out/$abi" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
    "$CMAKE_BIN/ninja" -C "$WORK/out/$abi"
    mkdir -p "$REPO/core/src/main/jniLibs/$abi"
    "$STRIP" --strip-unneeded -o "$REPO/core/src/main/jniLibs/$abi/libffmpegJNI.so" "$WORK/out/$abi/libffmpegJNI.so"
done
echo "Built:"
ls -l "$REPO"/core/src/main/jniLibs/*/libffmpegJNI.so
