plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号统一在 gradle.properties 维护（VERSION_NAME / VERSION_CODE），
// CI 与本文件读同一处，避免两边不一致。
val appVersionName: String = (project.findProperty("VERSION_NAME") as String?) ?: "1.0.0"
val appVersionCode: Int = ((project.findProperty("VERSION_CODE") as String?) ?: "1").toInt()

// 固定自签名密钥（随仓库公开，仅用于自编译安装包，避免每次 CI 签名不一致导致无法覆盖升级）
val keystoreFile = rootProject.file("keystore/tgenhance.p12")

android {
    namespace = "com.yjp.tgenhance"
    compileSdk = 35

    signingConfigs {
        if (keystoreFile.exists()) {
            create("fixed") {
                storeFile = keystoreFile
                storePassword = "tgenhance"
                keyAlias = "tgenhance"
                keyPassword = "tgenhance"
                storeType = "PKCS12"
            }
        }
    }

    defaultConfig {
        applicationId = "com.yjp.tgenhance"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            // LSPosed 模块内的 hook 类名必须可被反射/类加载器找到，
            // 混淆会让 xposed_init、hook 入口和内部 hook 类名错位，故关闭。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Xposed API 仅编译期需要，不进 APK。jar 已随仓库提交，CI 不依赖外部仓库可用性。
    compileOnly(files("libs/api-82.jar"))
}
