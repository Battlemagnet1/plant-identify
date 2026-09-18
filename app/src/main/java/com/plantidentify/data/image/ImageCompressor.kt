package com.plantidentify.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片压缩与解码（规格书第十三节）。
 *
 * ## 为什么参数是 1536px / JPEG 80
 *
 * 分析报告 Part 3.3 的测算：5 张图上传时的请求体大小直接决定用户等待时间。
 *
 * | 方案 | 5 图请求体（base64 后） | 4G 上传耗时 |
 * |---|---|---|
 * | 2048px / JPEG 85 | 2.7–5.3 MB | 5–20 s |
 * | **1536px / JPEG 80** | **1.7–2.7 MB** | **3–10 s** |
 *
 * 1536px 对植物识别已足够（叶形、叶序、花部结构都清晰可辨），
 * 相比 2048px 上传耗时减半、费用降低约四成。
 *
 * ## 为什么必须处理 EXIF 方向
 *
 * 手机拍摄的照片常把方向信息写在 EXIF 里而非实际旋转像素。若直接压缩上传，
 * 模型看到的可能是上下颠倒或旋转 90° 的图 —— **这会严重影响识别准确率**。
 * 因此这里在缩放前先把像素真正旋转到位，输出图不再依赖 EXIF 方向标记。
 *
 * ## 缓存策略
 *
 * 压缩结果写入 `cacheDir/upload/`（派生数据，系统可回收），
 * 文件名带上压缩参数，原图变更时自动失效。这样「补图重新识别」时
 * 不必重复压缩未改动的图片。
 */
class ImageCompressor(context: Context) {

    private val cacheRoot: File = File(context.cacheDir, DIR_UPLOAD)

    /**
     * 压缩副本在缓存中的目标文件（只推导路径，不做任何 IO）。
     * 文件名带上压缩参数，参数变化时旧副本自然失效，不会被误复用。
     */
    fun cacheEntryFor(original: File): File = File(
        cacheRoot,
        "${original.nameWithoutExtension}_${MAX_EDGE}_q$JPEG_QUALITY.jpg",
    )

    /**
     * 生成（或复用）用于上传的压缩副本。
     *
     * @param original 原图文件
     * @return 成功时返回缓存中的压缩图文件
     */
    suspend fun compressedFor(original: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(original.isFile) { "原图不存在：${original.absolutePath}" }

            if (!cacheRoot.exists()) cacheRoot.mkdirs()
            val target = cacheEntryFor(original)

            // 缓存命中：原图未更新过，直接复用
            if (target.isFile && target.lastModified() >= original.lastModified()) {
                return@runCatching target
            }

            val bitmap = decodeOriented(original, MAX_EDGE)
                ?: error("图片解码失败，可能是不支持的格式或文件已损坏")

            target.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    error("图片压缩失败")
                }
            }
            if (target.length() == 0L) {
                target.delete()
                error("压缩结果为空")
            }
            target
        }
    }

    /**
     * 删除某张原图对应的压缩副本。
     *
     * 原图被删除时必须一并调用：否则缓存里会留下**永远无法被复用**的死文件
     * （文件名派生自已删除原图的 UUID），一直占用空间直到系统清理缓存。
     */
    suspend fun evictCacheFor(original: File): Unit = withContext(Dispatchers.IO) {
        runCatching { cacheEntryFor(original).delete() }
        Unit
    }

    /** 清空压缩缓存 */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        runCatching { cacheRoot.deleteRecursively() }
        Unit
    }

    companion object {
        private const val DIR_UPLOAD = "upload"

        /** AI 上传图的最长边（像素） */
        const val MAX_EDGE = 1536

        /** AI 上传图的 JPEG 质量 */
        const val JPEG_QUALITY = 80

        /**
         * 按最长边限制解码并矫正好方向。
         *
         * 先用 `inJustDecodeBounds` 读出原始尺寸以计算降采样倍率，
         * 避免把上亿像素的原图整张读进内存导致 OOM。
         *
         * @param maxEdge 输出图的最长边；传 0 表示不缩放（仅纠正方向）
         */
        fun decodeOriented(file: File, maxEdge: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxEdge = if (maxEdge > 0) maxEdge else maxOf(bounds.outWidth, bounds.outHeight),
            )

            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: return null

            val oriented = applyExifOrientation(decoded, file)

            if (maxEdge <= 0) return oriented
            val longest = maxOf(oriented.width, oriented.height)
            if (longest <= maxEdge) return oriented

            val scale = maxEdge.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt().coerceAtLeast(1),
                (oriented.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            return scaled
        }

        /**
         * 计算二次幂降采样倍率：在保证解码结果不小于 maxEdge 的前提下取最大倍率。
         * 之后再由 `createScaledBitmap` 精确缩放到目标尺寸。
         */
        internal fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
            if (maxEdge <= 0) return 1
            var sampleSize = 1
            var longest = maxOf(width, height)
            while (longest / 2 >= maxEdge) {
                longest /= 2
                sampleSize *= 2
            }
            return sampleSize
        }

        /** 按 EXIF 方向标记真正旋转像素，返回方向已正确的位图 */
        private fun applyExifOrientation(bitmap: Bitmap, file: File): Bitmap {
            val orientation = runCatching {
                ExifInterface(file).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return bitmap
            }

            return runCatching {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }.getOrDefault(bitmap)
        }
    }
}
