plugins { id("com.android.application") }

android {
    namespace = "cn.zhuchenyu.videofacesearch"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.zhuchenyu.videofacesearch"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    ndkVersion = "30.0.16248370"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
