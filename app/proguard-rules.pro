# Keep runtime-discovered tools and JSON model metadata while allowing R8 to optimize the rest.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * implements com.example.oreshik.agent.core.AgentTool { *; }
-keepclassmembers class ** { @com.google.gson.annotations.SerializedName <fields>; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
