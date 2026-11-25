plugins { id("com.android.application") }

android {
    namespace = "cn.zhuchenyu.mediascribe"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.zhuchenyu.mediascribe"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9")
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
