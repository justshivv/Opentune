# The FFmpeg Dolby decoder (androidx.media3.decoder.ffmpeg, third_party/ffmpeg-dolby):
# its native methods keep their names, and the native code calls growOutputBuffer.
-keepclasseswithmembernames class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}
-keep, includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
  private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
