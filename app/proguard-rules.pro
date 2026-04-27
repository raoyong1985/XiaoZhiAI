# Add project specific ProGuard rules here.

# Keep MQTT classes
-keep class org.eclipse.paho.** { *; }

# Keep WebSocket
-keep class org.java-websocket.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes
-keep class com.xiaozhi.aiservice.domain.model.** { *; }
-keep class com.xiaozhi.aiservice.data.remote.** { *; }
