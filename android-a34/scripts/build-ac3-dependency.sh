#!/usr/bin/env bash
# Reproducible, source-only LGPL AC-3 dependency. No prebuilt codec package.
set -euo pipefail
task_root=$(cd "$(dirname "$0")/.." && pwd)
task_ndk=${ANDROID_NDK_HOME:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk/27.0.12077973}
task_work=${SISTEMA51_FFMPEG_WORK:-${HOME}/.cache/sistema51-ffmpeg}
task_version=8.0.3
task_sha=6136812ea6d4e68bdba27e33c2a94382711cdf4f8602ffef056ff792bd6f9818
task_host=linux-x86_64
task_hostcc=cc
case "$(uname -s)" in
  MINGW*|MSYS*) task_host=windows-x86_64; task_hostcc=aarch64-linux-android28-clang
    task_ndk=$(cygpath -u "$task_ndk")
    export PATH="$task_ndk/prebuilt/windows-x86_64/bin:$PATH";;
  Darwin*) task_host=darwin-x86_64;;
esac
export PATH="$task_ndk/toolchains/llvm/prebuilt/$task_host/bin:$PATH"
command -v aarch64-linux-android28-clang >/dev/null || { echo 'Set ANDROID_NDK_HOME to NDK 27.0.12077973.' >&2; exit 1; }
mkdir -p "$task_work"
task_archive="$task_work/ffmpeg-$task_version.tar.xz"
if [ ! -f "$task_archive" ]; then
  curl --fail --location --output "$task_archive" "https://ffmpeg.org/releases/ffmpeg-$task_version.tar.xz"
fi
printf '%s  %s\n' "$task_sha" "$task_archive" | sha256sum --check --status
if [ ! -d "$task_work/ffmpeg-$task_version" ]; then tar -xf "$task_archive" -C "$task_work"; fi
# The pinned release archive was also checked with its upstream PGP signature.
# No GPL, version3, nonfree, encoders, containers, network or external autodetected packages.
# The AC-3 decoder automatically selects its internal AC-3 header parser.
# Hardcoded tables eliminate host generators on Windows; AC-3 float transform remains enabled.
cd "$task_work/ffmpeg-$task_version"
./configure --prefix="$task_work/arm64" --target-os=android --arch=aarch64 --enable-cross-compile \
  --cc=aarch64-linux-android28-clang --host-cc="$task_hostcc" --ar=llvm-ar --nm=llvm-nm \
  --ranlib=llvm-ranlib --strip=llvm-strip --disable-everything --enable-decoder=ac3 \
  --enable-hardcoded-tables --disable-programs --disable-doc --disable-avdevice --disable-avformat \
  --disable-avfilter --disable-swscale --disable-swresample --disable-network --disable-autodetect \
  --disable-debug --disable-asm --enable-pic --enable-small --enable-static --disable-shared \
  --disable-gpl --disable-version3 --extra-cflags=-O2 --extra-ldflags=-Wl,-z,max-page-size=16384
make -j"${SISTEMA51_BUILD_JOBS:-8}"
make install
mkdir -p "$task_root/third-party/ffmpeg-arm64"
cp -R "$task_work/arm64/include" "$task_work/arm64/lib" "$task_root/third-party/ffmpeg-arm64/"
echo "FFmpeg AC-3 $task_version ARM64 built from pinned LGPL source."
