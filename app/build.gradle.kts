plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("org.jetbrains.kotlin.kapt")
    id("io.objectbox")
    id("com.google.gms.google-services")      // Google services plugin
    id("com.google.firebase.crashlytics")     // Crashlytics plugin
}

android {
    namespace = "com.uav.analytics"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.uav.analytics"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("..\\keystore_untitled.jks")
            keyAlias = "key0"
            storePassword = "Untitled@123"
            keyPassword = "Untitled@123"
        }
        getByName("debug") {
            storeFile = file("..\\keystore_untitled.jks")
            keyAlias = "key0"
            storePassword = "Untitled@123"
            keyPassword = "Untitled@123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            // Optional: For faster debug builds, use only one architecture
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
        }
        // Add this to customize APK names
        applicationVariants.all {
            val variant = this
            variant.outputs
                .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
                .forEach { output ->
                    val outputFileName = "AdFlux-${variant.name}-${variant.versionName}.apk"
                    output.outputFileName = outputFileName
                }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // ML Kit
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.vision.common)
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")

    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation(libs.play.services.vision)
    implementation(libs.androidx.window)
    implementation(libs.litert.gpu.api)

    // ObjectBox
    implementation("io.objectbox:objectbox-kotlin:4.0.2")
    kapt("io.objectbox:objectbox-processor:4.0.2")

    // Image Loading
    implementation("com.github.bumptech.glide:glide:4.15.1")
    kapt("com.github.bumptech.glide:compiler:4.15.1")

    // Network
    implementation("com.jakewharton.retrofit:retrofit2-kotlin-coroutines-adapter:0.9.2")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Work Manager
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Firebase BoM (manages versions automatically)
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))

    // Firebase core (Analytics, optional but common)
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-config-ktx")

    // Firebase Crashlytics
    implementation("com.google.firebase:firebase-crashlytics-ktx")

}