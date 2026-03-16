# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.scamkill.app.**$$serializer { *; }
-keepclassmembers class com.scamkill.app.** { *** Companion; }
-keepclasseswithmembers class com.scamkill.app.** { kotlinx.serialization.KSerializer serializer(...); }
