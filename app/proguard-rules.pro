# ShareThis ProGuard rules — keep wire models & reflection-free hot paths intact.

-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# Wire models are serialized manually; keep names stable for debugging.
-keep class com.sharethis.app.data.models.** { *; }
-keep class com.sharethis.app.data.enums.** { *; }
-keep class com.sharethis.app.core.engine.FrameProtocol** { *; }
-keep class com.sharethis.app.core.engine.JsonCodec** { *; }
-keep class com.sharethis.app.core.pairing.QrCodePayloadHandler** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ZXing core (only used classes are kept automatically; silence warnings)
-dontwarn com.google.zxing.**

# CameraX
-dontwarn androidx.camera.**
-keep class androidx.camera.** { *; }

# Custom views referenced from XML layouts
-keep class com.sharethis.app.ui.components.** { *; }
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
