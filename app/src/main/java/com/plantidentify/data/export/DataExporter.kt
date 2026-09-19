package com.plantidentify.data.export

import android.content.Context
import com.plantidentify.data.local.relation.PlantWithObservationsAndImages
import com.plantidentify.data.location.placeText
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * HTML 导出（规格书第二十一节 + 分析报告 Part 1.3 缺口 3 的三级策略）。
 *
 * ## 体积是这个功能的唯一难点
 *
 * 报告测算过：83 种植物的原图 base64 后约 660 MB，移动端浏览器根本打不开。
 * 因此：
 *
 * | 模式 | 触发 | 内容 |
 * |---|---|---|
 * | 缩略图单文件 | 默认 | 1024px / JPEG 75 内嵌，目标 < 50 MB |
 * | 原图单文件 | 用户显式选择 | 全部原图内嵌 |
 * | HTML + 图片文件夹 | 照片 > 200 张 或 原图 > 150 MB | 打包 zip |
 *
 * ## 为什么全程流式写
 *
 * 一份 50 MB 的 HTML 在内存里是 100 MB 的 UTF-16 字符串，直接 OOM。
 * 这里逐株拼接、逐段写进 `BufferedWriter`，任意时刻内存里只有一株植物的内容。
 * 单张图的 base64 是独立的临时字符串，用完即弃。
 */
class DataExporter(
    private val context: Context,
    private val imageStore: ImageStore,
    private val thumbnailer: ReportThumbnailer,
) {

    /** 导出文件落在 filesDir 下 —— 不进 cacheDir，用户分享前它得先活着 */
    fun exportDir(): File = File(context.filesDir, DIR_EXPORTS)

    /**
     * 导出体积预估。
     *
     * 必须先给用户看这个数字：报告里最有价值的一条结论就是
     * 「500 MB 的 HTML 打不开」，让用户在按下导出前就知道会得到多大。
     */
    suspend fun estimate(data: List<PlantWithObservationsAndImages>): ExportEstimate =
        withContext(Dispatchers.IO) {
            val paths = data.flatMap { plant ->
                plant.observations.flatMap { observation -> observation.images }
            }.map { it.imagePath }

            val originalBytes = paths.sumOf { path ->
                runCatching { imageStore.resolve(path).length() }.getOrDefault(0L)
            }

            ExportEstimate(
                plantCount = data.size,
                photoCount = paths.size,
                originalBytes = originalBytes,
                estimatedThumbnailBytes =
                    (originalBytes * ExportThresholds.THUMBNAIL_RATIO).toLong(),
            )
        }

    /**
     * 执行导出。
     *
     * @param requestedMode 用户选择的模式；体积或照片数超过降级阈值时会被
     *        自动改为 [ExportMode.FOLDER_ZIP]（结果里的 `downgraded` 会标出来）
     */
    suspend fun export(
        data: List<PlantWithObservationsAndImages>,
        appName: String,
        requestedMode: ExportMode,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val estimate = estimate(data)
            val mode = resolveMode(requestedMode, estimate)

            val dir = exportDir()
            if (!dir.exists()) dir.mkdirs()

            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val extension = if (mode == ExportMode.FOLDER_ZIP) "zip" else "html"
            // 文件名照规格书第二十一节的示例。同一天重复导出会覆盖上一份 ——
            // 它随时可以从数据重新生成，不值得为它设计一套版本管理
            val file = File(dir, "plantIdentify_Report_$stamp.$extension")

            val stats = statsOf(data)
            val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date())

            val photoCount = when (mode) {
                ExportMode.FOLDER_ZIP -> writeZip(file, data, appName, generatedAt, stats, onProgress)
                else -> writeSingleFile(file, data, appName, generatedAt, stats, mode, onProgress)
            }

            ExportResult(
                filePath = file.absolutePath,
                fileName = file.name,
                mode = mode,
                bytes = file.length(),
                plantCount = data.size,
                photoCount = photoCount,
                downgraded = mode != requestedMode,
            )
        }
    }

    /**
     * 单文件模式：图片 base64 内嵌，一个 HTML 走天下。
     */
    private suspend fun writeSingleFile(
        file: File,
        data: List<PlantWithObservationsAndImages>,
        appName: String,
        generatedAt: String,
        stats: ReportStats,
        mode: ExportMode,
        onProgress: (Int, Int) -> Unit,
    ): Int {
        var written = 0
        BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).use { writer ->
            writer.write(HtmlReportBuilder.documentStart(appName, generatedAt, stats))

            data.forEachIndexed { index, item ->
                val images = item.observations.flatMap { it.images }.map { entity ->
                    val source = readImageSource(entity.imagePath, mode == ExportMode.ORIGINAL)
                    if (source != null) written++
                    ReportImage(role = entity.role, source = source)
                }
                writer.write(
                    HtmlReportBuilder.plantCard(index + 1, toReportPlant(item, images)),
                )
                onProgress(index + 1, data.size)
            }

            writer.write(HtmlReportBuilder.documentEnd(DISCLAIMER))
        }
        return written
    }

    /**
     * zip 模式：HTML 引用相对路径图片，整体打包。
     *
     * 图片直接以**原文件**流式拷进 zip，不经过内存 —— 这正是这个模式存在的意义：
     * 单文件模式必须把每张图读进内存做 base64，照片一多就撑不住。
     */
    private suspend fun writeZip(
        file: File,
        data: List<PlantWithObservationsAndImages>,
        appName: String,
        generatedAt: String,
        stats: ReportStats,
        onProgress: (Int, Int) -> Unit,
    ): Int {
        var copied = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            zip.putNextEntry(ZipEntry("report.html"))
            val html = buildString {
                append(HtmlReportBuilder.documentStart(appName, generatedAt, stats))
                data.forEachIndexed { index, item ->
                    val images = item.observations.flatMap { it.images }.map { entity ->
                        ReportImage(role = entity.role, source = "$DIR_IMAGES/${entity.imagePath}")
                    }
                    append(HtmlReportBuilder.plantCard(index + 1, toReportPlant(item, images)))
                    onProgress(index + 1, data.size)
                }
                append(HtmlReportBuilder.documentEnd(DISCLAIMER))
            }
            zip.write(html.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            data.forEach { item ->
                item.observations.forEach { observation ->
                    observation.images.forEach { entity ->
                        val source = imageStore.resolve(entity.imagePath)
                        if (!source.isFile) return@forEach
                        runCatching {
                            zip.putNextEntry(ZipEntry("$DIR_IMAGES/${entity.imagePath}"))
                            source.inputStream().use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                            copied++
                        }
                    }
                }
            }
        }
        return copied
    }

    /** 读一张图的图片源：单文件模式给 data URI，读不出来返回 null（报告里渲染占位） */
    private suspend fun readImageSource(relativePath: String, useOriginal: Boolean): String? {
        val original = imageStore.resolve(relativePath)
        val source = if (useOriginal) original else thumbnailer.thumbnailFor(original)
        if (source == null || !source.isFile) return null
        return runCatching {
            HtmlReportBuilder.dataUri(source.readBytes())
        }.getOrNull()
    }

    private fun resolveMode(requested: ExportMode, estimate: ExportEstimate): ExportMode =
        // 降级规则来自报告 Part 1.3：照片过多或原图过大时，单文件已不现实
        if (estimate.requiresZip) ExportMode.FOLDER_ZIP else requested

    private fun statsOf(data: List<PlantWithObservationsAndImages>): ReportStats = ReportStats(
        distinctPlants = data.size,
        observationCount = data.sumOf { it.observations.size },
        imageCount = data.sumOf { plant -> plant.observations.sumOf { it.images.size } },
    )

    private fun toReportPlant(
        item: PlantWithObservationsAndImages,
        images: List<ReportImage>,
    ): ReportPlant {
        val plant = item.plant
        return ReportPlant(
            name = plant.name,
            latinName = plant.latinName,
            commonNames = plant.commonNames,
            family = plant.family,
            genus = plant.genus,
            category = plant.category,
            confidence = plant.confidence,
            description = plant.description,
            morphologicalFeatures = plant.morphologicalFeatures,
            growthHabits = plant.growthHabits,
            floweringPeriod = plant.floweringPeriod,
            fruitingPeriod = plant.fruitingPeriod,
            landscapeUses = plant.landscapeUses,
            careAdvice = plant.careAdvice,
            pestControl = plant.pestControl,
            note = plant.note,
            observations = item.observations.map { observation ->
                ReportObservation(
                    timeText = formatTime(observation.observation.timestamp),
                    // 优先地名，取不到就退化成经纬度 —— 规格书要求显示地名而非坐标，
                    // 但「有个坐标」总比「什么都没有」有用。
                    // 口径与 App 内的观察记录页共用 placeText，两处不会跑偏
                    place = placeText(
                        locationName = observation.observation.locationName,
                        latitude = observation.observation.latitude,
                        longitude = observation.observation.longitude,
                    ),
                    note = observation.observation.note,
                )
            },
            images = images,
        )
    }

    private companion object {
        const val DIR_EXPORTS = "exports"
        const val DIR_IMAGES = "images"

        const val DISCLAIMER =
            "AI 识别结果仅供参考，置信度是模型对当前视觉证据的置信程度估计，" +
                "不是经过科学验证的物种鉴定概率。专业鉴定与科研场景请由专业人员复核。"

        /**
         * 观察时间的显示格式。
         *
         * 按**生成报告那一刻**的系统区域现建一个格式化器。
         * 缓存成静态字段的话，用户在应用运行期间切换语言后仍会用旧区域 ——
         * 一份报告里出现两种日期写法，正是 lint 的 ConstantLocale 警告所指。
         */
        fun formatTime(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
