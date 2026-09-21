# --- Basilience ProGuard Rules ---

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# Lottie
-keep class com.airbnb.lottie.** { *; }

# Firebase Model Classes
# Ensure your model classes are not obfuscated so Firebase's reflection-based
# POJO mapper (DataSnapshot.getValue(X.class) / DocumentSnapshot.toObject(X.class))
# can map data to them. Missing a class here doesn't fail the build - it fails
# silently at runtime in release builds only ("No properties to serialize
# found on class ..."), since R8 is free to strip/rename that class's fields
# with nothing telling it they're read via reflection. SensorData/
# OperationRequest/FoggingEvent/Harvest were missing from this list (only
# Cycle/Device/Personnel were covered) - confirmed live via SensorRepository's
# "Failed to parse sensor data" error on the release APK, matching this exact
# ProGuard failure mode. Audit every getValue(X.class)/toObject(X.class) call
# on a custom class (not String/Long/Boolean/Double/etc, which Firebase
# special-cases and never needs a keep rule) against this list when adding one.
-keepclassmembers class com.example.basilience.Cycle { *; }
-keepclassmembers class com.example.basilience.Device { *; }
-keepclassmembers class com.example.basilience.Personnel { *; }
-keepclassmembers class com.example.basilience.OperationRequest { *; }
-keepclassmembers class com.example.basilience.Harvest { *; }
-keepclassmembers class com.example.basilience.models.SensorData { *; }
-keepclassmembers class com.example.basilience.models.SensorData$SensorState { *; }
-keepclassmembers class com.example.basilience.models.FoggingEvent { *; }

# Navigation Component
-keepclassmembers class * extends androidx.navigation.Navigator {
    public <init>(...);
}

# General Cleanup
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses