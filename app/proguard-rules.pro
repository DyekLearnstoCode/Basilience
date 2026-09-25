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
# Apache POI / Aalto (XLSX export) reference the StAX API, which is not on
# Android. Only the six classes R8 reported missing are silenced.
-dontwarn javax.xml.stream.XMLEventFactory
-dontwarn javax.xml.stream.XMLInputFactory
-dontwarn javax.xml.stream.XMLOutputFactory
-dontwarn javax.xml.stream.XMLReporter
-dontwarn javax.xml.stream.XMLResolver
-dontwarn javax.xml.stream.util.XMLEventAllocator

# Log4j2 (pulled in by Apache POI) creates its default flow message factory
# with Class.newInstance(); without this R8 strips the constructor and the
# first XLSX export dies with InstantiationException in LogManager.
-keepclassmembers class org.apache.logging.log4j.message.DefaultFlowMessageFactory {
    <init>();
}

# XMLBeans derives its schema resource path from the package of this holder
# class (SchemaTypeSystemImpl: className.substring(0, className.lastIndexOf('.'))),
# so its name must not be obfuscated.
-keepnames class org.apache.poi.schemas.ooxml.system.ooxml.TypeSystemHolder

# XMLBeans finds the generated OOXML schema classes by name from its .xsb
# schema files. If the Impl classes are stripped or renamed it falls back to a
# generic object and XSSFWorkbook fails with ClassCastException.
-keepnames class org.openxmlformats.schemas.**
-keep class org.openxmlformats.schemas.**.impl.* {
    <init>(org.apache.xmlbeans.SchemaType);
}

# XMLBeans reads each schema enum's static lookup table reflectively; without it
# it returns a generic StringEnumValue (ClassCastException in XSSFCellFill).
-keepclassmembers class org.openxmlformats.schemas.**$Enum {
    public static ** table;
    public static ** forString(java.lang.String);
    public static ** forInt(int);
}

# commons-compress registers its ZipExtraField implementations through their
# no-arg constructors (ExtraFieldUtils static init).
-keepclassmembers class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    <init>();
}
