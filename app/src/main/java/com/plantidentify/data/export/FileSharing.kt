package com.plantidentify.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把文件交给系统的分享面板。
 *
 * ## 为什么要走 FileProvider
 *
 * `file://` URI 从 Android 7 起就会抛 `FileUriExposedException`：直接给外部应用
 * 一个本机文件路径，等于让它绕过沙盒。FileProvider 把文件包装成
 * `content://` 并**按次授权**，对方只能读我们点名的那一个文件。
 *
 * 可分享的目录白名单在 `res/xml/file_paths.xml` 里 ——
 * Phase 6 只有导出物（exports/、backups/），
 * Phase 6+ 增加了用户原图（images/，供「分享照片」用）。
 * 三个目录之外的一律不可分享。
 */
object FileSharing {

    /** FileProvider 的 authority，与 AndroidManifest 里保持一致 */
    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    /**
     * 弹出分享面板。
     *
     * @return 是否成功发起 —— 设备上没有可接收的应用时返回 false，
     *         调用方据此提示「文件已保存在…」而不是让用户对着无反应的按钮点
     */
    fun share(context: Context, file: File, mimeType: String, subject: String): Boolean =
        runCatching {
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 一定要包一层 chooser：直接 startActivity 在有多家可实现时会
            // 静默取第一个，用户没得选
            context.startActivity(
                Intent.createChooser(intent, subject).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)

    /**
     * 按扩展名给 MIME，分享面板才能正确筛选接收方。
     *
     * 图片这几种是 Phase 6+ 补的：以前一律落到 `application/octet-stream`，
     * 结果是分享照片时系统**只列出「保存到文件」之类**，
     * 微信、图库、修图应用都不会出现在候选里 —— 用户会以为「分享坏了」。
     */
    fun mimeTypeOf(fileName: String): String = when {
        fileName.endsWith(".html", ignoreCase = true) ||
            fileName.endsWith(".htm", ignoreCase = true) -> "text/html"
        fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
        fileName.endsWith(".png", ignoreCase = true) -> "image/png"
        fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
        fileName.endsWith(".heic", ignoreCase = true) ||
            fileName.endsWith(".heif", ignoreCase = true) -> "image/heic"
        fileName.endsWith(".jpg", ignoreCase = true) ||
            fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        else -> "application/octet-stream"
    }
}
