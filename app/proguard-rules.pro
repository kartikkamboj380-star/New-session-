# MultiSpace package-specific ProGuard rules (debug builds do not apply ProGuard;
# these rules prepare future release builds).
-keep class com.multispace.manager.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
