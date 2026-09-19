package com.plantidentify.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把导出物交给系统的分享面板。
 *
 * ## 为什么要走 FileProvider
 *
 * `file://` URI 从 Android 7 起就会抛 `FileUriExposedException`：直接给外部应用
 * 一个本机文件路径，等于让它绕过沙盒。FileProvider 把文件包装成
 * `content://` 并**按次授权**，对方只能读我们点名的那一个文件。
 *
 * 可分享的目录白名单在 `res/xml/file_paths.xml` 里 —— 只有 exports/ 与 backups/，
 * 用户的原图与配置不在其中。
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

    /** 按扩展名给 MIME，分享面板才能正确筛选接收方 */
    fun mimeTypeOf(fileName: String): String = when {
        fileName.endsWith(".html", ignoreCase = true) -> "text/html"
        fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
        else -> "application/octet-stream"
    }
}
