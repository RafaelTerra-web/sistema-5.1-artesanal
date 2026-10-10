FFmpeg 8.0.3 supplies the floating point AC-3 decoder in `sistema51_ac3`.

The app builds `libavcodec` and `libavutil` from the unmodified official release,
with only the AC-3 decoder enabled. GPL, version 3, nonfree code, network,
containers, encoders, external package autodetection and assembler are disabled.
The decoder selects its own internal AC-3 header parser. Android system libraries
(Bionic libc, libm, libatomic, libandroid and libmediandk) provide platform functions;
the only audio codec enabled is software AC-3. No external codec package is used.
The resulting configuration reports LGPL version 2.1 or later.

Source: https://ffmpeg.org/releases/ffmpeg-8.0.3.tar.xz

SHA-256: `6136812ea6d4e68bdba27e33c2a94382711cdf4f8602ffef056ff792bd6f9818`

The source archive's upstream detached signature was verified against the
FFmpeg release signing key fingerprint
`FCF986EA15E6E293A5644F10B4322F04D67658D8`.
The signature and public key accompany this directory. Build scripts verify
the pinned SHA-256 before extracting downloaded sources. Downloaded source and
compiled libraries are local build artifacts excluded from Git.

Run `scripts/build-ac3-dependency.ps1` on Windows or
`bash scripts/build-ac3-dependency.sh` on Linux with NDK 27.0.12077973.
`ANDROID_NDK_HOME` can select the NDK path. The same scripts rebuild the LGPL
libraries and the public JNI source can be relinked against modified libraries;
no private codec binaries or keys are required for a debug APK.

The app bundles LGPL-2.1, FFmpeg's license text and this attribution as assets.
FFmpeg: https://ffmpeg.org/ . No affiliation with FFmpeg is implied.
