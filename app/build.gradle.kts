import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

// Release signing credentials live in keystore/keystore.properties (gitignored,
// never committed - see the repo's own .gitignore comment on why) rather than
// hardcoded here. Absent entirely on a fresh checkout/CI machine that hasn't
// been given the keystore, in which case releaseSigningConfig below stays
// null and the release build type simply falls back to being unsigned
// (buildable, just not installable) instead of failing the whole build.
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.example.basilience"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.basilience"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                // keystore.properties' own storeFile value is relative to
                // the keystore/ directory it lives in (e.g.
                // "../keystore/basilience-release.jks" from Gradle's
                // perspective) - resolved from rootProject instead of parsed
                // out of that string, which is simpler and avoids depending
                // on the property's exact path format.
                storeFile = rootProject.file("keystore/basilience-release.jks")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Gradle's own default output name is "<module>-<buildType>.apk" (app-debug.apk,
// app-release.apk) - renamed here to something recognizable instead of tracing
// it back to the module name.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("Basilience.apk")
            }
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.database)
    implementation("com.google.firebase:firebase-functions:22.1.1")
    implementation("com.google.firebase:firebase-messaging:23.4.1")

    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.airbnb.android:lottie:6.3.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
