plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStoreFilePath = System.getenv("WATCH_PROBE_SIGNING_STORE_FILE")?.trim().orEmpty()
val signingStorePassword = System.getenv("WATCH_PROBE_SIGNING_STORE_PASSWORD")?.trim().orEmpty()
val signingKeyAlias = System.getenv("WATCH_PROBE_SIGNING_KEY_ALIAS")?.trim().orEmpty()
val signingKeyPassword = System.getenv("WATCH_PROBE_SIGNING_KEY_PASSWORD")?.trim().orEmpty()
val hasStableSigning = listOf(
    signingStoreFilePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { it.isNotBlank() }

android {
    namespace = "com.zhuchenyu.oppowatchprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhuchenyu.oppowatchprobe"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasStableSigning) {
            create("watchProbeStable") {
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
                signingConfig = signingConfigs.getByName("watchProbeStable")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("watchProbeStable")
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
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    implementation("com.google.guava:guava:33.4.8-android")
}
