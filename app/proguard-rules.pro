# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.taskflow.data.backup.**$$serializer { *; }
-keepclassmembers class com.taskflow.data.backup.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.taskflow.data.ai.**$$serializer { *; }
-keepclassmembers class com.taskflow.data.ai.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
