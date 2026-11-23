plugins { id("com.android.application") }

android {
    namespace = "cn.zhuchenyu.videofacesearch"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.zhuchenyu.videofacesearch"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
