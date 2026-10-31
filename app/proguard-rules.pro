# Media3 / ExoPlayer rules
-keep class androidx.media3.** { *; }

# OkHttp and Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.diyp.next.data.model.** { *; }

# Lottie and Glide
-keep public class com.airbnb.lottie.** { *; }
-keep public class * implements com.bumptech.glide.module.GlideModule
