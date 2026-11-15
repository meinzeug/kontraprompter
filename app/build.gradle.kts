import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.kontrakurs.kontraprompter"
    compileSdk = 37
    defaultConfig {
        applicationId = "de.kontrakurs.kontraprompter"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

// The JVM tests load the real test JSON implementation in an isolated classloader;
// Android's framework org.json stub otherwise takes precedence over dependencies.
val jsonUnitTestRuntime by configurations.creating

tasks.withType<Test>().configureEach {
    doFirst { systemProperty("kontraprompter.test.jsonJar", jsonUnitTestRuntime.singleFile.absolutePath) }
}

dependencies {
    jsonUnitTestRuntime("org.json:json:20240303")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    // Explicit release candidate: public Material Expressive APIs are not in stable 1.4.0.
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
