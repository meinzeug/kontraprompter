plugins { id("com.android.application") }

android {
    namespace = "de.kontrakurs.voskfixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "de.kontrakurs.voskfixture"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

// Explicit AARs: this fixture is separate from the KontraPrompter APK.
dependencies {
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
