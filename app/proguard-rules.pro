# Add project specific ProGuard rules here.

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes
-keep class com.xiaozhi.aiservice.domain.model.** { *; }

# Keep JNI native methods
-keep class com.xiaozhi.aiservice.data.audio.NativeOpusDecoder { *; }

# Keep DataStore Preferences keys (generated class)
-keep class com.xiaozhi.aiservice.data.repository.MessageRepositoryImpl$** { *; }

# Keep WebSocket callback
-keep class com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService$** { *; }

# Keep all kotlinx.coroutines for Flow/StateFlow
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
