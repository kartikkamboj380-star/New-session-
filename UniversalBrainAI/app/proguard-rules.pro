# Universal Brain AI ProGuard rules (debug builds do not apply ProGuard).
-keep class com.universalbrain.ai.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
