
import org.gradle.process.ExecOperations
import org.gradle.kotlin.dsl.support.serviceOf

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

// OPTIONAL device reset helpers (now SAFE by default):
// - Alexa uninstall is DISABLED
// - Bluetooth unpair remains available if you want it
val RESET_BEATS_MAC = "20:FA:85:8F:DE:9C"
val RESET_ONN_MAC   = "A8:91:6D:56:91:C9"

tasks.register("resetDeviceState") {
    group = "dev"
    description = "Optionally unpairs Beats + ONN before build/run. Alexa is preserved."

    doLast {
        val execOps = project.serviceOf<ExecOperations>()

        fun runCmd(vararg cmd: String) {
            try {
                execOps.exec {
                    commandLine(*cmd)
                    isIgnoreExitValue = true
                }
            } catch (_: Throwable) {
                // adb not found or no device attached; ignore
            }
        }

        // IMPORTANT:
        // Alexa uninstall intentionally REMOVED to avoid wiping setup on every run.
        // If you ever need to reset Alexa manually:
        //   adb uninstall com.amazon.dee.app

        // Optional: unpair Bluetooth devices (keep or comment out as desired)
        runCmd("adb", "shell", "cmd", "bluetooth_manager", "unpair", RESET_BEATS_MAC)
        runCmd("adb", "shell", "cmd", "bluetooth_manager", "unpair", RESET_ONN_MAC)
    }
}

// IMPORTANT:
// This task no longer nukes Alexa.
// You may also comment this out entirely if you don't want *any* reset behavior.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("resetDeviceState")
}

android {
    namespace = "com.example.devicelinkassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.devicelinkassistant"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Firebase Cloud Messaging (FCM)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Coach mode OCR (MediaProjection frames -> ML Kit Text Recognition)
    // Note: ML Kit dependencies are not covered by firebase-bom; pin explicitly.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
