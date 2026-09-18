package com.plantidentify.data.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * 图片文件存储（规格书第十三节）。
 *
 * 三条硬约束（详见分析报告 Part 1.3 缺口 4）：
 *
 * 1. **存 `filesDir`，绝不使用 `cacheDir`**
 *    系统在存储紧张时会静默清理 cacheDir，用它会直接导致用户档案图片全部丢失。
 *
 * 2. **对外只暴露相对路径**（如 `2026/09/xxxx.jpg`）
 *    应用私有目录的绝对路径包含随机化的沙盒段（形如
 *    `/data/user/0/<pkg>/files/...` 之外还有运行期随机化），换机恢复备份后
 *    绝对路径会全部失效。数据库里存的、页面上传的都是相对路径。
 *
 * 3. **原图不可破坏**
 *    AI 上传所需的小图是另行生成到缓存目录的派生数据（见 [com.plantidentify.data.image.ImageCompressor]），
 *    不会覆盖或替换原图。
 */
class ImageStore(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 图片根目录：<filesDir>/images */
    private val root: File = File(appContext.filesDir, DIR_IMAGES)

    /** 把相对路径还原为可读写的文件对象 */
    fun resolve(relativePath: String): File = File(root, relativePath)

    fun exists(relativePath: String): Boolean = resolve(relativePath).isFile

    /**
     * 从相册 / 相机返回的 Uri 导入原图。
     *
     * @return 成功时返回相对路径
     */
    suspend fun importFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val target = newFile(extension = guessExtension(uri))
            val stream = appContext.contentResolver.openInputStream(uri)
                ?: error("无法读取所选图片（openInputStream 返回 null）")
            stream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() == 0L) {
                target.delete()
                error("所选图片内容为空")
            }
            relativePathOf(target)
        }
    }

    /**
     * 导入相机拍摄的临时文件，导入后删除临时文件。
     * CameraX 写入的是 cacheDir 下的临时文件，不能直接当作档案原图使用。
     */
    suspend fun importCapturedTemp(tempFile: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val target = newFile(extension = EXT_JPG)
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
            relativePathOf(target)
        }
    }

    /** 删除单个文件。删除植物档案时用于清理磁盘，避免残留孤儿图片 */
    suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { resolve(relativePath).delete() }.getOrDefault(false)
    }

    /** 批量删除 */
    suspend fun deleteAll(relativePaths: Collection<String>) = withContext(Dispatchers.IO) {
        relativePaths.forEach { path ->
            runCatching { resolve(path).delete() }
                .onFailure { Log.w(TAG, "删除图片失败：$path", it) }
        }
    }

    /** 已占用空间（字节），供设置页展示 */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext 0L
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ---------------- 内部 ----------------

    /**
     * 生成一个新的目标文件。
     * 目录按 `2026/09/` 分月组织，避免单目录文件过多影响列举性能。
     */
    private fun newFile(extension: String): File {
        val today = LocalDate.now()
        val dir = File(root, "%04d/%02d".format(today.year, today.monthValue))
        if (!dir.exists() && !dir.mkdirs()) {
            error("无法创建图片目录：${dir.absolutePath}")
        }
        return File(dir, "${UUID.randomUUID()}.$extension")
    }

    private fun relativePathOf(file: File): String =
        file.relativeTo(root).invariantSeparatorsPath

    private fun guessExtension(uri: Uri): String {
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        return when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            "image/gif" -> "gif"
            else -> EXT_JPG
        }
    }

    private companion object {
        const val TAG = "ImageStore"
        const val DIR_IMAGES = "images"
        const val EXT_JPG = "jpg"
    }
}
