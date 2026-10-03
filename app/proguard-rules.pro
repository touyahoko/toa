# MHXX RNG Tool ProGuard rules

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# CameraX
-keep class androidx.camera.** { *; }

# Keep all Kotlin data classes (RNG types)
-keep class org.mhxxtools.mhxxrngtool.rng.** { *; }

# Keep ViewModel subclasses
-keep class * extends androidx.lifecycle.ViewModel { *; }
