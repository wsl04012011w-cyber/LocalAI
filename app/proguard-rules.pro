# Preserve names looked up from JNI.
-keep class com.example.engine.NativeLlamaBridge { *; }
-keep interface com.example.engine.NativeLlamaBridge$TokenCallback { *; }
-keep class com.example.engine.LlamaCppEngineAdapter { *; }

-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep class **$$serializer { *; }
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
