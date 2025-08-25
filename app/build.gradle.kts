plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("org.jetbrains.kotlin.kapt")
    id("io.objectbox")
    id("com.google.gms.google-services")      // Google services plugin
    id("com.google.firebase.crashlytics")     // Crashlytics plugin
}

android {
    namespace = "com.example.demoapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.demoapplication"
        minSdk = 24
        targetSdk = 34
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

    implementation(libs.tensorflow.lite)
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // ML Kit Face Detection
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.vision.common)

    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:object-detection-custom:17.0.2")
    // ML Kit Pose Detection
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")

    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation(libs.play.services.vision)
    implementation(libs.androidx.window)
    implementation(libs.litert.gpu.api)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.activity:activity-ktx:1.7.1")

    implementation("io.objectbox:objectbox-kotlin:4.0.2")
    kapt("io.objectbox:objectbox-processor:4.0.2")

    implementation ("com.github.bumptech.glide:glide:4.15.1")

    implementation("com.jakewharton.retrofit:retrofit2-kotlin-coroutines-adapter:0.9.2")
    implementation("com.squareup.retrofit2:retrofit:2.8.1")
    implementation("com.squareup.retrofit2:converter-gson:2.8.1")
    implementation("com.google.code.gson:gson:2.8.8")
    implementation("androidx.work:work-runtime-ktx:2.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Firebase BoM (manages versions automatically)
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))

    // Firebase core (Analytics, optional but common)
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Firebase Crashlytics
    implementation("com.google.firebase:firebase-crashlytics-ktx")

}