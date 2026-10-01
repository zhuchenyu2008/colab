plugins { id("com.android.application") }

android {
    namespace = "cn.zhuchenyu.netprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "cn.zhuchenyu.netprobe"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
