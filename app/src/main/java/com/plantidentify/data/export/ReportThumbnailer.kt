package com.plantidentify.data.export

import android.content.Context
import android.graphics.Bitmap
import com.plantidentify.data.image.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 报告用的缩略图（1024px / JPEG 75）。
 *
 * ## 为什么不复用 [ImageCompressor] 的缓存
 *
 * 它的产物是 1536px / JPEG 80、给 AI 上传用的，而且由「添加植物」流程的
 * 草稿生命周期管理（草稿放弃时会被清掉）。报告要的是另一种参数、
 * 另一种生命周期，混在同一个目录里会互相误清。
 *
 * 但**EXIF 方向矫正必须复用** —— 那是十几行容易写错的矩阵逻辑，
 * 抄一份迟早两边不一致。所以这里只调它的 `decodeOriented`。
 *
 * ## 为什么可以放 cacheDir
 *
 * 这是**派生数据**：原图在 filesDir 里，缩略图随时可以重建。
 * 与「档案图片严禁放 cacheDir」不冲突 —— 那条规则针对的是不可再生的原图。
 */
class ReportThumbnailer(context: Context) {

    private val cacheRoot: File = File(context.cacheDir, DIR_REPORT)

    /**
     * 生成（或复用）缩略图。
     *
     * @return 失败返回 null —— 单张图坏了不该让整份报告导不出来
     */
    suspend fun thumbnailFor(original: File): File? = withContext(Dispatchers.IO) {
        runCatching {
            if (!original.isFile) return@runCatching null

            if (!cacheRoot.exists()) cacheRoot.mkdirs()
            val target = File(
                cacheRoot,
                "${original.nameWithoutExtension}_${MAX_EDGE}_q$JPEG_QUALITY.jpg",
            )
            if (target.isFile && target.length() > 0 &&
                target.lastModified() >= original.lastModified()
            ) {
                return@runCatching target
            }

            val bitmap = ImageCompressor.decodeOriented(original, MAX_EDGE)
                ?: return@runCatching null

            target.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    return@runCatching null
                }
            }
            if (target.length() == 0L) {
                target.delete()
                return@runCatching null
            }
            target
        }.getOrNull()
    }

    companion object {
        private const val DIR_REPORT = "report"

        /**
         * 报告缩略图的最长边。
         *
         * 1024px 是分析报告 Part 1.3 缺口 3 给的默认值：
         * 在电脑上看已经足够清晰（报告正文宽度 820px，图按 200px 网格排），
         * 而相比 1536px 又省掉约一半体积。
         */
        const val MAX_EDGE = 1024

        /** 75 比 80 再省一截，报告图用不着那么高的质量 */
        const val JPEG_QUALITY = 75
    }
}
