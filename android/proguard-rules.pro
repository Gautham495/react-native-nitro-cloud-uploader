# Nitro Modules - Keep annotations and hybrid classes
-keep @com.facebook.proguard.annotations.DoNotStrip class *
-keep @androidx.annotation.Keep class *
-keepclassmembers class * {
    @com.facebook.proguard.annotations.DoNotStrip *;
    @androidx.annotation.Keep *;
}

# Keep NitroCloudUploader classes
-keep class com.margelo.nitro.nitroclouduploader.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep service class
-keep class com.margelo.nitro.nitroclouduploader.UploadForegroundService { *; }

