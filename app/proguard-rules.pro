# Parachord ProGuard Rules

# Keep JS bridge classes accessible via reflection
-keep class com.parachord.android.bridge.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
