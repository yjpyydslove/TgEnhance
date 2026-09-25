plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号统一在 gradle.properties 维护（VERSION_NAME / VERSION_CODE），
// CI 与本文件读同一处，避免两边不一致。
val appVersionName: String = (project.findProperty("VERSION_NAME") as String?) ?: "1.0.0"
val appVersionCode: Int = ((project.findProperty("VERSION_CODE") as String?) ?: "1").toInt()

// 签名密钥（v N2.2 起不再随仓库公开）
//
// 原来密钥连同密码一起提交在仓库里，任何人拿到都能签一个能覆盖升级的假包。
// 现在：CI 上由 GitHub Secret 注入密钥文件、密码走环境变量；
// 密钥文件已在 .gitignore 里，本地自己构建时放一份即可。
val keystoreFile = rootProject.file("keystore/tgenhance.p12")

// 密码从环境变量读。
//
// **CI 上缺了就直接报错**（v N2.4）—— 之前这里静默回退到占位值，
// 结果 Secret 配好了、密钥也恢复了，打包时却报「密码不对」，
// 查了好几轮才发现是 workflow 忘了用 env 传进来。
// 静默回退把「配置漏了一环」伪装成了「密码错误」，代价太大。
val keystorePassword: String = System.getenv("KEYSTORE_PASSWORD")
    ?: if (System.getenv("CI") != null) {
        throw GradleException(
            "CI 环境下缺少 KEYSTORE_PASSWORD 环境变量。" +
                "请在 workflow 的构建步骤里加上 " +
                "`KEYSTORE_PASSWORD: \${{ secrets.KEYSTORE_PASSWORD }}`。"
        )
    } else {
        // 本地构建：没设环境变量时用占位值，方便本机出包
        "tgenhance"
    }

android {
    namespace = "com.yjp.tgenhance"

    // v N1.16：编译目标升到 Android 16（API 36）。
    //
    // 升 compileSdk 只影响「编译期能用哪些 API」，不改变运行期行为 ——
    // 真正决定行为的是 targetSdk（见下方，**仍然保持 35**）。
    // 升上来是为了能用 API 36 的符号，也让「支持到 Android 16」有依据。
    compileSdk = 36

    signingConfigs {
        if (keystoreFile.exists()) {
            create("fixed") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "tgenhance"
                keyPassword = keystorePassword
                storeType = "PKCS12"
            }
        }
    }

    defaultConfig {
        applicationId = "com.yjp.tgenhance"
        minSdk = 26

        // targetSdk **刻意停在 35**（v N1.16）。
        //
        // 它决定运行期按哪一版的规则对待本应用。升到 36 会一次性引入两条
        // 行为变更：edge-to-edge 不能再退出、预测性返回强制启用
        // （`onBackPressed` 不再被调用）。本模块目前不依赖旧返回机制、
        // 也已自行处理 insets，**技术上能升**；但没有收益 —— 这两条变更
        // 对「一个设置界面 + 若干个 Hook」来说只是多两个要照顾的分支。
        //
        // 什么时候该升：Google 要求新应用 targetSdk 不低于某版本、
        // 或需要用到只在 36+ 生效的 API 时。那天记得回来重新评估上面两条。
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
