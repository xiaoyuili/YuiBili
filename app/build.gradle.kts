plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuilittle.bili"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yuilittle.bili"
        minSdk = 23
        targetSdk = 34
        versionCode = 10106
        versionName = "1.0.1beta6"
        manifestPlaceholders["appLabel"] = "YuiBili"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
        }
        create("release") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "YuiBili Debug"
            // 开发期跳过 PNG 压缩，减少资源处理耗时（不影响安装与运行）
            isCrunchPngs = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }

    buildToolsVersion = "34.0.0"

    lint {
        // 开发期关闭 test/androidTest 源集的 Lint 分析与冗余报告输出，大幅缩减常驻构建耗时
        checkTestSources = false
        ignoreTestSources = true
        xmlReport = false
        htmlReport = false
        textReport = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    testImplementation("junit:junit:4.13.2")
}