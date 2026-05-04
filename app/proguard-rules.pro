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

# Keep Gson-serialized group chat model classes
-keep class com.xiaozhi.aiservice.domain.model.GroupMessage { *; }
-keep class com.xiaozhi.aiservice.domain.model.GroupChat { *; }
-keep class com.xiaozhi.aiservice.domain.model.BotContact { *; }
-keep class com.xiaozhi.aiservice.domain.model.ChatMessage { *; }

# Keep all Kotlin data classes (Gson serialization)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

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
