plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.lovekey.open"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "dev.lovekey.open"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
