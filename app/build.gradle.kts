import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9부터 Kotlin 지원이 내장되어 있어 별도 kotlin-android 플러그인이 필요 없습니다.
    id("com.android.application")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "com.shortformpet.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shortformpet.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 외부 라이브러리 없이 안드로이드 기본 프레임워크만 사용합니다.
dependencies {
}
