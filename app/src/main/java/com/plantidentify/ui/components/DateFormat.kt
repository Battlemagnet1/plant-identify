package com.plantidentify.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLocale
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 在 composable 里按当前 locale 取一个日期格式化器。
 *
 * ## 为什么不能直接写 `Locale.getDefault()`
 *
 * 它读的是**非可观察**状态：用户切换系统语言后界面不会重组，会继续用旧 locale
 * 的格式显示。lint 的 `NonObservableLocale` 把「在 composable 里读它」判为
 * **error** —— 会直接中断 `lintFullRelease`，所以本项目里凡是在 composable 内
 * 格式化日期，都必须走这个函数。
 *
 * `LocalLocale` 是 Compose 提供的可观察 locale，配置变化时会触发重组。
 *
 * ## 为什么要 remember
 *
 * `SimpleDateFormat` 的构造不便宜（要解析模式串），而重组很频繁。
 * 但注意它**不是线程安全的** —— 只在这里返回给调用方在组合期使用，别往外传。
 */
@Composable
fun rememberDateFormatter(pattern: String): SimpleDateFormat {
    val locale: Locale = LocalLocale.current.platformLocale
    return remember(pattern, locale) { SimpleDateFormat(pattern, locale) }
}
