package com.plantidentify.data.storage

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把应用私有目录里的照片导出到系统相册。
 *
 * ## 为什么要分两条路径
 *
 * `MediaStore` 的免权限写入是 **Android 10（API 29）** 才有的
 * （Scoped Storage）。项目 `minSdk = 26`，所以：
 *
 * | 版本 | 做法 | 权限 |
 * |---|---|---|
 * | API ≥ 29 | `MediaStore.Images` + `RELATIVE_PATH` | 不需要 |
 * | API 26–28 | 直接写公共 `Pictures/` 目录 + 通知媒体扫描 | 需要 `WRITE_EXTERNAL_STORAGE` |
 *
 * 老版本那条路必须在**运行时申请权限**，因此开放了
 * [needsLegacyPermission] 给界面判断，而不是在这里静默失败。
 *
 * ## 为什么不写「保存成功」到相册就完事
 *
 * API 29+ 写入时先置 `IS_PENDING = 1`，写完再改回 0 ——
 * 否则相册应用可能在文件还没写完时就去读它，出现半张图或 0 字节。
 * 中途异常则删除这个占位行，不留一条指向空文件的媒体记录。
 */
class MediaSaver(private val context: Context) {

    /**
     * 当前是否需要先申请存储权限。
     *
     * 只在 API 26–28 为 true。界面据此决定是否弹权限框 ——
     * 用它而不是在 [saveToGallery] 里吞掉权限异常，是因为
     * 「没权限」和「保存失败」对用户是两件完全不同的事：
     * 前者可以点「允许」解决，后者只能重试。
     */
    fun needsLegacyPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED

    /**
     * 保存到相册。
     *
     * @param source 应用私有目录里的原图
     * @param baseName 文件名主体（不含扩展名）。相册里看到的就是它，
     *        所以调用方应该传「紫薇_20260919_2030」这种能认出来的名字，
     *        而不是文件系统里那串 UUID。
     */
    suspend fun saveToGallery(source: File, baseName: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!source.isFile) error("照片文件不存在")

                val extension = source.extension.ifBlank { "jpg" }
                val displayName = "$baseName.$extension"
                val mime = mimeTypeOf(extension)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(source, displayName, mime)
                } else {
                    if (needsLegacyPermission()) error("没有存储权限，无法保存到相册")
                    saveViaLegacyPath(source, displayName, mime)
                }
            }
        }

    // ---------------- API ≥ 29 ----------------

    private fun saveViaMediaStore(source: File, displayName: String, mime: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM",
            )
            // 先标记为「写入中」，别让相册应用读到半截文件
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, pending)
            ?: error("系统相册拒绝了这次写入")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法打开相册写入通道")

            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            return uri
        } catch (error: Throwable) {
            // 失败就把刚才插进去的占位行删掉，不然相册里会留一张打不开的空图
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    // ---------------- API 26–28 ----------------

    private fun saveViaLegacyPath(source: File, displayName: String, mime: String): Uri {
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES,
        )
        val dir = File(pictures, ALBUM)
        if (!dir.exists() && !dir.mkdirs()) error("无法创建相册目录")

        val target = uniqueFile(dir, displayName)
        source.copyTo(target, overwrite = false)

        // 不通知媒体扫描的话，文件在相册应用里要等下次开机才出现
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(target)
    }

    /** 同名文件加序号后缀，避免覆盖用户之前存过的同名照片 */
    private fun uniqueFile(dir: File, displayName: String): File {
        val dot = displayName.lastIndexOf('.')
        val stem = if (dot > 0) displayName.substring(0, dot) else displayName
        val extension = if (dot > 0) displayName.substring(dot) else ""

        var candidate = File(dir, displayName)
        var index = 2
        while (candidate.exists() && index < 1000) {
            candidate = File(dir, "${stem}_$index$extension")
            index++
        }
        return candidate
    }

    private fun mimeTypeOf(extension: String): String = when (extension.lowercase(Locale.US)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    companion object {
        /** 相册里归到这个子相册下，便于用户在几千张图里找到 */
        const val ALBUM = "PlantIdentify"

        /**
         * 生成一个「看得懂」的文件名主体。
         *
         * 私有目录里的文件名是 UUID —— 直接拿它当相册里的名字，
         * 用户存了十张就得到十个认不出来的乱码。
         */
        fun baseName(plantName: String, at: Long = System.currentTimeMillis()): String {
            val safe = plantName
                .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                .trim('_')
                .take(40)
                .ifBlank { "plant" }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(at))
            return "${safe}_$stamp"
        }
    }
}
