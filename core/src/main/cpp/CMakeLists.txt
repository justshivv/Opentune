cmake_minimum_required(VERSION 3.22.1)
project(opentune_audio CXX)

# Bit-perfect outputs that Android's own audio path can't give: an exclusive
# AAudio stream to the phone's DAC, and a USB Audio Class driver of the app's own.
add_library(opentune_audio SHARED aaudio_out.cpp usb_iso.cpp)
target_compile_features(opentune_audio PRIVATE cxx_std_17)
target_compile_options(opentune_audio PRIVATE -Wall -Wextra -O2 -fvisibility=hidden)
target_link_libraries(opentune_audio aaudio log dl)
# 16 KB pages (Android 15 devices): the library must load on them too.
target_link_options(opentune_audio PRIVATE "-Wl,-z,max-page-size=16384")
