# Add project specific ProGuard rules here.

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Gson TypeToken - R8 会吃掉匿名内部类的泛型签名
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# Keep generic signatures for Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep all Kotlin data classes (Gson serialization)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all enums
-keepclassmembers enum * {
    <fields>;
    **[] values();
}

# Keep VoiceMode enum
-keep class com.xiaozhi.aiservice.domain.model.VoiceMode { *; }
-keep class com.xiaozhi.aiservice.domain.model.InputMode { *; }
-keep class com.xiaozhi.aiservice.domain.model.MessageType { *; }
-keep class com.xiaozhi.aiservice.domain.model.MessageDirection { *; }
-keep class com.xiaozhi.aiservice.domain.model.MessageStatus { *; }

# Keep JNI native methods
-keep class com.xiaozhi.aiservice.data.audio.NativeOpusDecoder { *; }

# Keep MCP tools registry and tool class (release 混淆会丢失 tools/list 响应)
-keep class com.xiaozhi.aiservice.data.remote.MCPToolRegistry { *; }
-keep class com.xiaozhi.aiservice.data.remote.MCPTool { *; }

# Keep DataStore Preferences keys (generated class)
-keep class com.xiaozhi.aiservice.data.repository.MessageRepositoryImpl$** { *; }

# Keep WebSocket callback
-keep class com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService$** { *; }

# Keep all kotlinx.coroutines for Flow/StateFlow
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
