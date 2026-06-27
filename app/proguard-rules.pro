# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.mgafk.app.**$$serializer { *; }
-keepclassmembers class com.mgafk.app.** { *** Companion; }
-keepclasseswithmembers class com.mgafk.app.** { kotlinx.serialization.KSerializer serializer(...); }
