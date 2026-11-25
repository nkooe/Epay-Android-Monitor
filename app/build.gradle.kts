import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yjk.monitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yjk.monitor"
        minSdk = 23
        // 故意停在 32：Android 13+ 的「受限设置」只对 targetSdk >= 33 生效，
        // 侧载安装时 targetSdk 32 的应用可以直接开启「通知使用权 / 悬浮窗」，
        // 也不会被 Android 15 强制 edge-to-edge（底层导航栏遮挡问题一并消失）。
        targetSdk = 32
        versionCode = 102
        versionName = "1.13.5"
        resourceConfigurations += listOf("zh", "en")
    }

    // 正式签名从 local.properties 读，keystore 不进仓库
    val ksProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val releaseKs = rootProject.file("keystore/yjk.jks")
    signingConfigs {
        if (releaseKs.exists()) {
            create("release") {
                storeFile = releaseKs
                storePassword = ksProps.getProperty("yjk.storePassword") ?: ""
                keyAlias = ksProps.getProperty("yjk.keyAlias") ?: "yjk"
                keyPassword = ksProps.getProperty("yjk.keyPassword") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 有 keystore 用正式签名，没有则退回 debug（方便他人直接构建）
            signingConfig = if (releaseKs.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 刻意不引入任何第三方 / AndroidX 依赖：纯框架 API 实现，
    // 好处是构建快、包体小、离线可编译、不会因为升级依赖而崩。
    implementation(kotlin("stdlib"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
