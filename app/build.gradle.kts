import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val useExperimentalFixedCameraFps = providers.gradleProperty("experimentalFixedCameraFps").map { it == "true" }.getOrElse(false)

android {
    namespace = "de.kontrakurs.kontraprompter"
    compileSdk = 36
    defaultConfig {
        applicationId = "de.kontrakurs.kontraprompter"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        getByName("debug") {
            // Test build only; release cannot turn on this unvalidated camera path.
            buildConfigField("boolean", "EXPERIMENTAL_RAW_RECORDING", providers.gradleProperty("experimentalRawRecording").map { (it == "true").toString() }.getOrElse("false"))
            buildConfigField("boolean", "EXPERIMENTAL_FIXED_CAMERA_FPS", useExperimentalFixedCameraFps.toString())
            buildConfigField("boolean", "FRAME_RATE_DIAGNOSTICS", providers.gradleProperty("frameDiagnostics").map { (it == "true").toString() }.getOrElse("false"))
        }
        getByName("release") {
            buildConfigField("boolean", "EXPERIMENTAL_RAW_RECORDING", "false")
            buildConfigField("boolean", "EXPERIMENTAL_FIXED_CAMERA_FPS", "false")
            buildConfigField("boolean", "FRAME_RATE_DIAGNOSTICS", "false")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

// Package-private org.webrtc interfaces are version-specific. An experimental build
// must never silently compile the copied hook against a different binary.
if (useExperimentalFixedCameraFps) {
    val verifyExperimentalWebrtc by tasks.registering {
        doLast {
            val artifacts = configurations.getByName("debugCompileClasspath").resolvedConfiguration.resolvedArtifacts.filter {
                it.moduleVersion.id.group == "io.github.webrtc-sdk" && it.name == "android"
            }
            check(artifacts.size == 1 && artifacts.single().moduleVersion.id.version == "150.7871.04") { "Experimental Camera2 hook requires WebRTC 150.7871.04" }
            val digest = MessageDigest.getInstance("SHA-256")
            artifacts.single().file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(hash == "1872dda4e3bf7d6ea6f7c6e11861332081c57081a1e62fc82fab72415c69c84c") { "Experimental Camera2 hook requires the verified WebRTC AAR" }
        }
    }
    tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(verifyExperimentalWebrtc) }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
