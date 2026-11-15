# Keep runtime-discovered tools and JSON model metadata while allowing R8 to optimize the rest.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * implements com.example.oreshik.agent.core.AgentTool { *; }
-keepclassmembers class ** { @com.google.gson.annotations.SerializedName <fields>; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**

# JGit's optional repository GC PID lock uses the Java 9 ProcessHandle API,
# which is not part of Android's runtime API surface. The app never invokes JGit GC.
-dontwarn java.lang.ProcessHandle**

# JGit optional JVM integrations are unavailable on Android. This app uses HTTPS
# transport and does not enable JMX, GSS/Kerberos authentication, or SLF4J binding.
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Keep chat rendering types readable in release crash traces. These classes are
# involved in RecyclerView binding and persisted message deserialization.
-keep class com.example.oreshik.ChatAdapter { *; }
-keep class com.example.oreshik.ChatAdapter$* { *; }
-keep class com.example.oreshik.ChatMessage { *; }
-keep class com.example.oreshik.ChatActivityItem { *; }
-keep class com.example.oreshik.ChatFileChange { *; }
-keep class com.example.oreshik.ChatDiffLine { *; }
-keep class com.example.oreshik.MediaPreview$* { *; }
-keep class com.example.oreshik.ShimmerTextView { *; }
-keep class com.example.oreshik.MainActivity { *; }
-keep class com.example.oreshik.agent.events.AgentEvent$* { *; }
