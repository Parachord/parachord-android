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

# Keep all @Serializable data classes and their generated serializers.
# R8 can obfuscate field names, breaking JSON (de)serialization for API
# responses, queue persistence, and disk caches.
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep custom serializers (used by Last.fm API, Critical Darlings, etc.)
-keep class **$$serializer { *; }

# Retrofit service interfaces — R8 may strip method signatures needed
# for dynamic proxy generation
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Enum classes used in serialization
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
