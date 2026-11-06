plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.autotapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.autotapper"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            // 폰용 ABI만 포함해서 APK 크기 줄이기
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 항상 같은 키로 서명 → 새 버전을 지우지 않고 덮어 설치 가능
    signingConfigs {
        create("fixed") {
            storeFile = file("autotap.keystore")
            storePassword = "autotap123"
            keyAlias = "autotap"
            keyPassword = "autotap123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
    implementation("org.opencv:opencv:4.10.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
}
