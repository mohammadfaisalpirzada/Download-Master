# Master Downloader Proguard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Keep Models
-keep class com.themastersahib.downloader.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Media3
-keep class androidx.media3.** { *; }

# NewPipeExtractor
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.javascript.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
