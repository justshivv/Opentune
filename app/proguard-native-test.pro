# Android test libraries compile against Kotlin facade classes that the app
# itself may not call. AGP removes the duplicate runtime from the test APK.
# Preserve it only in the instrumented release; production rules stay separate.
-keep class kotlin.** { *; }
