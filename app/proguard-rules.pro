# Add project specific ProGuard rules here.

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Gson TypeToken - R8 会吃掉匿名内部类的泛型签名
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# Keep generic signatures for Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep all @SerializedName annotated fields (Gson deserialization)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all domain model classes (used by Gson + Compose UI)
-keep class com.xiaozhi.aiservice.domain.model.** { *; }

# Keep all data model / MCP classes
-keep class com.xiaozhi.aiservice.data.remote.mcp.** { *; }

# Keep all enums (values() used by Gson)
-keepclassmembers enum * {
    <fields>;
    **[] values();
}

# Keep JNI native methods
-keep class com.xiaozhi.aiservice.data.audio.NativeOpusDecoder { *; }
-keep class com.xiaozhi.aiservice.data.audio.NativeOpusEncoder { *; }

# Keep ONNX Runtime (FsmnVAD, SileroVAD)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep MCP tools registry and tool classes
-keep class com.xiaozhi.aiservice.data.remote.MCPToolRegistry { *; }
-keep class com.xiaozhi.aiservice.data.remote.MCPTool { *; }
-keep class com.xiaozhi.aiservice.data.remote.McpResultFormat { *; }

# Keep WebSocket callback
-keep class com.xiaozhi.aiservice.data.remote.XiaoZhiBackendService$** { *; }

# Keep repository interfaces and states (used by Compose UI)
-keep class com.xiaozhi.aiservice.domain.repository.** { *; }

# Keep all kotlinx.coroutines for Flow/StateFlow
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep AudioService / recording classes
-keep class com.xiaozhi.aiservice.data.audio.** { *; }

# Keep broadcast receivers
-keep class com.xiaozhi.aiservice.data.remote.mcp.AlarmReceiver { *; }
-keep class com.xiaozhi.aiservice.data.remote.mcp.ReminderReceiver { *; }
-keep class com.xiaozhi.aiservice.data.remote.mcp.BootReceiver { *; }

# Keep Hilt DI
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
