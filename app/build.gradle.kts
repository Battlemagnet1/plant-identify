plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android。
    // 但仍需 Compose 编译器插件 —— 它的版本必须与 AGP 内置的 KGP 版本一致（2.2.10）。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

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

    buildTypes {
        release {
            // 混淆在 Phase 7 再开启，Phase 1 先保证能出包
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
}
