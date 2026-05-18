# Nexara Native ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class kotlinx.serialization.** { *; }

# R8 bytecode optimization to strip logging in production
-assumenosideeffects class com.promenar.nexara.utils.NexaraLogger {
    public static void log(java.lang.String);
}
