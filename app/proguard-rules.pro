# Parachord ProGuard Rules

# Keep JS bridge classes accessible via reflection
-keep class com.parachord.android.bridge.** { *; }

# Spotify App Remote SDK references Jackson internally for serialization.
# The SDK bundles its own mappers but R8 can't verify the Jackson classes
# at compile time. These dontwarn rules suppress the missing-class errors.
-dontwarn com.fasterxml.jackson.databind.**

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
