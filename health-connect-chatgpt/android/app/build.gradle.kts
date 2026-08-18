plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStoreFilePath = System.getenv("HEALTH_BRIDGE_SIGNING_STORE_FILE")?.trim().orEmpty()
val signingStorePassword = System.getenv("HEALTH_BRIDGE_SIGNING_STORE_PASSWORD")?.trim().orEmpty()
val signingKeyAlias = System.getenv("HEALTH_BRIDGE_SIGNING_KEY_ALIAS")?.trim().orEmpty()
val signingKeyPassword = System.getenv("HEALTH_BRIDGE_SIGNING_KEY_PASSWORD")?.trim().orEmpty()
val hasStableSigning = listOf(
    signingStoreFilePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { it.isNotBlank() }

android {
    namespace = "com.zhuchenyu.healthchatbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhuchenyu.healthchatbridge"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasStableSigning) {
            create("healthBridgeStable") {
                storeFile = file(signingStoreFilePath)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("healthBridgeStable")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("healthBridgeStable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.work:work-runtime:2.10.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
