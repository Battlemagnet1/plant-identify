// 必须显式 import：Gradle 脚本里 `java` 会被解析成 java 扩展（JavaPluginExtension），
// 写成 java.util.Properties 会报 Unresolved reference 'util'
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android。
    // 但仍需 Compose 编译器插件 —— 它的版本必须与 AGP 内置的 KGP 版本一致（2.2.10）。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------- 发布签名 ----------
// 密钥与口令存在仓库之外：仓库根目录的 keystore.properties（已被 .gitignore 排除），
// 密钥本体放在用户目录下。这样「仓库将来转公开」时不会连带泄露签名身份。
//
// 文件不存在时**静默跳过**而不是报错 —— 别人 clone 下来无需任何密钥即可
// assembleDebug / assembleRelease（后者产出未签名包）。让构建因为缺少
// 一个只有维护者才有的文件而失败，是最容易劝退贡献者的做法。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = !keystoreProperties.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "com.plantidentify"

    // compileSdk 必须为 37：本版 androidx 依赖（navigation-compose 2.10.1、
    // lifecycle 2.11.0）的 AAR 元数据要求调用方编译目标不低于 API 37。
    // 注意 compileSdk 与 targetSdk 相互独立：
    //   - compileSdk 只决定「用哪套 API 编译」，取高值不影响运行行为
    //   - targetSdk 才是「对系统声明按哪个版本的行为运行」，仍保持 36
    //     （Google Play 自 2026-08-31 起要求新应用 targetSdk ≥ 36）
    compileSdk = 37

    defaultConfig {
        applicationId = "com.plantidentify"
        // 模拟器为 Android 14 / API 34，minSdk 必须 ≤ 34 才能安装
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ---------- 两个版本 ----------
    //
    // base = 基础版（Phase 1–5 的功能集），applicationId 沿用 com.plantidentify，
    //        已装过 v1.0.0 的用户可以直接覆盖升级。
    // full = 完整版（含统计/位置/HTML 导出/备份恢复/别名与病虫害/大图查看/照片增删），
    //        独立 applicationId，与基础版**可同时安装**、互不覆盖。
    //
    // 两者共用同一份底层代码与同一个数据库结构（version 2）与同一套 AI 请求，
    // 差异只在界面入口 —— 靠 FULL_EDITION 这个编译期常量控制。
    // 于是两个包的档案数据可以通过备份包互相迁移。
    //
    // 版本号两边完全相同、都保持 1.0.0：区分靠包名而不是版本号，
    // 因为两个包的「版本」指的是同一份代码的两个功能集，不是两次迭代。
    flavorDimensions += "edition"
    productFlavors {
        create("base") {
            dimension = "edition"
            applicationId = "com.plantidentify"
            // 显示名沿用 main 里的 app_name，不做覆盖
            buildConfigField("boolean", "FULL_EDITION", "false")
        }
        create("full") {
            dimension = "edition"
            applicationId = "com.plantidentify.full"
            // 显示名由 src/full/res/values/strings.xml 覆盖（比 resValue 稳：
            // resValue 会在生成的 res 里再造一份 app_name，与 main 的那份
            // 同名资源在合并期谁赢取决于源集优先级，不如直接声明一个覆盖源集清楚）
            buildConfigField("boolean", "FULL_EDITION", "true")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 混淆在 Phase 7 再开启，Phase 1 先保证能出包
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 首页要显示版本号，用 BuildConfig.VERSION_NAME 而不是硬编码字符串
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // 单测跑在桌面 JVM 上，android.jar 里的方法默认是「抛异常」而非返回默认值。
            // 把开关打开，被测代码里那些无关紧要的 android.* 调用（Log、Base64 等）
            // 才不至于让一条纯逻辑断言失败 —— 失败原因会变成「not mocked」，
            // 而不是真的断言不成立，那种误导比测试没写更糟。
            isReturnDefaultValues = true
        }
    }
}

// Room 导出数据库 schema，提交到仓库用于版本追溯
// （schema JSON 只含表结构，不含任何用户数据）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- Phase 2：图像采集 ---
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.datastore.preferences)

    // --- Phase 3：AI 视觉识别 ---
    // 刻意不加 okhttp 的 logging-interceptor：
    // 它会打印请求头（含 Authorization: Bearer <apiKey>）到 Logcat，
    // 与验收标准「API Key 不出现在任何日志中」直接冲突。
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    // 容错 JSON 解析要用 JSONObject/JSONArray，JVM 上没有实现，必须自带
    testImplementation(libs.org.json)
}
