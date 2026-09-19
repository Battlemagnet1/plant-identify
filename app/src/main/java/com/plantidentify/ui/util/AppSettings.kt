package com.plantidentify.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 跳到本应用的系统设置页。
 *
 * ## 为什么必须有这条路径
 *
 * 权限被**永久拒绝**之后，系统不再弹授权框 —— 此时所有「再试一次」
 * 都是点了没反应。用户卡在这一页，除了卸载重装没有别的出路。
 * 所以每个权限被拒的面板都要留一个明确的去处。
 *
 * ## 为什么用 ACTION_APPLICATION_DETAILS_SETTINGS
 *
 * 它是唯一从 Android 6 到 16 都稳定可用的、指向「本应用」详情页的 action。
 * 更早的 `ACTION_APPLICATION_SETTINGS` 会落到应用列表，
 * 用户还得自己找一遍我们在哪。
 *
 * 取 `context.packageName` 而不是写死 `com.plantidentify`：
 * 完整版的 applicationId 带 `.full` 后缀，写死就会跳到另一个包去。
 */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // 个别定制 ROM 没有这个页面。失败就不做任何事 ——
    // 这里本来就是最后的兜底出口，再抛异常只会把「没权限」升级成「崩溃」
    runCatching { context.startActivity(intent) }
}
