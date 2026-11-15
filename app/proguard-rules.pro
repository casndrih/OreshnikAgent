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
