# --- Basilience ProGuard Rules ---

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# Lottie
-keep class com.airbnb.lottie.** { *; }

# Firebase Model Classes
# Ensure your model classes (Cycle, Device, Personnel) are not obfuscated
# so Firebase can map data to them.
-keepclassmembers class com.example.basilience.Cycle { *; }
-keepclassmembers class com.example.basilience.Device { *; }
-keepclassmembers class com.example.basilience.Personnel { *; }

# Navigation Component
-keepclassmembers class * extends androidx.navigation.Navigator {
    public <init>(...);
}

# General Cleanup
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses