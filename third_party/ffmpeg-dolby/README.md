# FFmpeg Dolby decoder

Plays Dolby Digital (AC-3) and Dolby Digital Plus (E-AC-3) on phones that have no Dolby decoder
of their own. For Dolby Atmos in E-AC-3 (JOC), it plays the 5.1 or 7.1 bed. The height objects
need Dolby's own decoder, which the phone or a receiver has.

The player tries these in order:

1. The phone's own decoder, or passing the stream to a receiver or TV over HDMI or eARC.
2. This decoder.

AC-4 is not here. FFmpeg has no AC-4 decoder, so AC-4 plays only where the phone has Dolby's
decoder, or through a receiver.

## What is in the repository

- `core/src/main/jniLibs/<abi>/libffmpegJNI.so`: built by `build.sh` from the files below. Each
  one links only Android's system libraries, and is aligned for 16 KB pages.
- `core/src/main/java/androidx/media3/decoder/ffmpeg/`: the audio classes of Media3's FFmpeg
  decoder module (Apache 2.0), taken from the Jellyfin build of Media3 1.9.0 (sources jar of
  `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1`) and compiled against the app's Media3.
  The one change: `FfmpegLibrary.supportsFormat` answers only for the decoders built here.
- `ffmpeg_jni.cc`, `CMakeLists.txt`, `build_ffmpeg.sh`: Media3's JNI glue and build files, from
  the same sources jar.
- `build.sh`: downloads FFmpeg 7.1.1, checks its SHA-256, and rebuilds the libraries.

## Rebuilding

```
third_party/ffmpeg-dolby/build.sh            # NDK 27.2.12479018 from the Android SDK
third_party/ffmpeg-dolby/build.sh /path/ndk  # another NDK
```

## Licences

FFmpeg is LGPL 2.1 or later. It is built without `--enable-gpl` or `--enable-nonfree`, and
ships as its own shared library. Media3's files are Apache 2.0.
