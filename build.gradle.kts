// plant Identify —— 根构建脚本
// 只声明插件，不在此处配置模块。
//
// 注意：AGP 9 已内置 Kotlin 支持（内置 KGP 2.2.10），
// 因此这里不声明 org.jetbrains.kotlin.android。

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
