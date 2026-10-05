# R8 for release builds: shrinking only.
#
# Nothing is renamed or optimized. Much of stream resolution is reached by
# reflection or by name (Rhino running YouTube's player JavaScript, NewPipe,
# InnerTubeX's Ktor client, protobuf, QuickJS's JNI callbacks, the PoToken
# WebView's JavaScript interface), and readable class names keep the stack
# traces in "Copy log" useful. What shrinking removes is library code
# nothing calls, the bulk of it unused Material icons, which is where the
# size goes.
-dontobfuscate
-dontoptimize
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,Exceptions

# The app itself is small next to its libraries; keeping all of it means no
# setting, serializer, provider or JavaScript interface can go missing.
-keep class com.opentune.** { *; }

# Stream resolution.
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.metrolist.innertubex.** { *; }
-keep class io.ktor.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.dokar.quickjs.** { *; }
-keep class org.jsoup.** { *; }

# The PoToken WebView calls back into Kotlin by method name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Optional pieces of these libraries that Android doesn't have.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn javax.lang.model.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.re2j.**
-dontwarn jdk.dynalink.**
-dontwarn java.lang.management.**
