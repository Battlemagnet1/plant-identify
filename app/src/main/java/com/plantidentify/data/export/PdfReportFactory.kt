package com.plantidentify.data.export

import com.plantidentify.data.local.entity.FolderEntity
import com.plantidentify.data.local.relation.PlantWithObservationsAndImages
import com.plantidentify.data.location.placeText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把领域模型组装成 [PdfReport]（v1.0.2 Phase 2 §十四）。
 *
 * 单独一层而不是让 `PdfReportBuilder` 直接吃 Room 实体：渲染器只认
 * 「标题 + 字段 + 图片」这种与业务无关的结构，于是
 * **同一个渲染器既能出植物报告、也能出文件夹报告、Phase 3 还能出景观报告**，
 * 不必为每种报告各写一套排版。
 *
 * ## 时间与地点都用项目里已有的口径
 *
 * 地点走 `placeText()` —— 规格书要求「地点显示口径只有一处」，
 * 导出报告当然也算显示。时间用固定格式而不是系统区域设置：
 * 报告是留档用的，`2026-10-02 14:26` 比任何本地化写法都更不容易被误读。
 */
object PdfReportFactory {

    fun plantReport(item: PlantWithObservationsAndImages): PdfReport {
        val plant = item.plant
        return PdfReport(
            title = plant.name,
            subtitle = plant.latinName?.takeIf { it.isNotBlank() },
            stats = buildList {
                add("观察次数" to "${item.observations.size}")
                add("照片" to "${item.observations.sumOf { it.images.size }} 张")
                add("建档时间" to formatTime(plant.createdAt))
            },
            plants = listOf(item.toPdfPlant()),
            footerNote = "由 Plant Identify Library 导出 · " +
                "AI 识别结果为参考意见，不作为专业鉴定依据。",
        )
    }

    /** 「全部植物」报告：没有对应的文件夹实体，单独一个入口 */
    fun allPlantsReport(items: List<PlantWithObservationsAndImages>): PdfReport =
        collectionReport(
            title = "植物档案报告",
            subtitle = "全部植物",
            items = items,
        )

    /**
     * 某个文件夹的报告。
     *
     * `typeLabel` 由调用方传入、而不是在这里算：文件夹类型的中文标签
     * 定义在 UI 层（`FolderTypeUi.label()`），**导出层不该反向依赖 UI** ——
     * 那会让「数据层能不能单独编译」这件事变得说不清。
     */
    fun folderReport(
        folder: FolderEntity,
        items: List<PlantWithObservationsAndImages>,
        typeLabel: String,
    ): PdfReport = collectionReport(
        title = folder.name,
        subtitle = buildString {
            append(typeLabel)
            folder.description?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        },
        items = items,
        extraStats = listOf("最后更新" to formatTime(folder.updatedAt)),
    )

    /**
     * 「一批植物」报告的公共实现。
     *
     * 全部植物与某个文件夹的报告，除了标题与多一行统计之外完全一样 ——
     * 所以合成一个函数，而不是复制两份出来慢慢分叉。
     */
    private fun collectionReport(
        title: String,
        subtitle: String?,
        items: List<PlantWithObservationsAndImages>,
        extraStats: List<Pair<String, String>> = emptyList(),
    ): PdfReport {
        val observationCount = items.sumOf { it.observations.size }
        val imageCount = items.sumOf { it.observations.sumOf { o -> o.images.size } }
        return PdfReport(
            title = title,
            subtitle = subtitle,
            stats = buildList {
                add("植物" to "${items.size} 种")
                add("观察记录" to "$observationCount 次")
                add("照片" to "$imageCount 张")
                addAll(extraStats)
            },
            plants = items.map { it.toPdfPlant() },
            footerNote = "由 Plant Identify Library 导出 · " +
                "AI 识别结果为参考意见，不作为专业鉴定依据。",
        )
    }

    // ---------------------------------------------------------------- 内部

    private fun PlantWithObservationsAndImages.toPdfPlant(): PdfPlant {
        val plant = this.plant
        return PdfPlant(
            name = plant.name,
            latinName = plant.latinName,
            fields = buildList {
                addAll(
                    listOfNotNull(
                        plant.commonNames?.takeIf { it.isNotBlank() }?.let { "其他俗称" to it },
                        plant.family?.takeIf { it.isNotBlank() }?.let { "科" to it },
                        plant.genus?.takeIf { it.isNotBlank() }?.let { "属" to it },
                        plant.category?.takeIf { it.isNotBlank() }?.let { "植物类型" to it },
                        (plant.confidence.takeIf { it > 0 }?.let {
                            "识别可信度" to "${(it * 100).toInt()}%"
                        }),
                        plant.floweringPeriod?.takeIf { it.isNotBlank() }?.let { "花期" to it },
                        plant.fruitingPeriod?.takeIf { it.isNotBlank() }?.let { "果期" to it },
                        plant.landscapeUses?.takeIf { it.isNotBlank() }?.let { "园林用途" to it },
                        plant.morphologicalFeatures?.takeIf { it.isNotBlank() }
                            ?.let { "形态特征" to it },
                        plant.growthHabits?.takeIf { it.isNotBlank() }?.let { "生长习性" to it },
                        plant.careAdvice?.takeIf { it.isNotBlank() }?.let { "养护建议" to it },
                        plant.pestControl?.takeIf { it.isNotBlank() }?.let { "病虫害防治" to it },
                        plant.description?.takeIf { it.isNotBlank() }?.let { "植物简介" to it },
                        plant.note?.takeIf { it.isNotBlank() }?.let { "备注" to it },
                    ),
                )
            },
            observations = observations
                .sortedByDescending { it.observation.timestamp }
                .map { row ->
                    PdfObservation(
                        timeText = formatTime(row.observation.timestamp),
                        place = placeText(
                            locationName = row.observation.locationName,
                            latitude = row.observation.latitude,
                            longitude = row.observation.longitude,
                        )?.takeIf { it.isNotBlank() },
                        note = row.observation.note,
                    )
                },
            // 每株最多放 6 张：一份报告几十株时，全部照片会把 PDF 撑到几十页，
            // 而报告的价值在字段与观察，照片只是佐证
            imagePaths = observations
                .sortedByDescending { it.observation.isPrimary }
                .flatMap { row -> row.images.sortedBy { it.sortOrder }.map { it.imagePath } }
                .distinct()
                .take(MAX_IMAGES_PER_PLANT),
        )
    }

    private const val MAX_IMAGES_PER_PLANT = 6

    /**
     * 报告里的时间格式。
     *
     * 用 `Locale.US` 而不是系统区域设置：报告是**留档**，跨设备传阅时
     * 阿拉伯文数字或佛历都会让人看不懂。ISO 风格的写法到哪都一样。
     */
    private fun formatTime(millis: Long): String =
        if (millis <= 0L) "—"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
}
