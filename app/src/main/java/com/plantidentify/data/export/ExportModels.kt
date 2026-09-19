package com.plantidentify.data.export

import java.util.Locale

/**
 * HTML 导出的三种模式（分析报告 Part 1.3 缺口 3 的三级策略）。
 *
 * ## 为什么不能默认嵌原图
 *
 * 报告里算过一笔账：83 种植物的原图 base64 之后约 660 MB，
 * 移动端浏览器基本打不开。所以默认只嵌缩略图（1024px / JPEG 75），
 * 并给出体积预估，让用户在导出前就知道会得到多大的文件。
 */
enum class ExportMode {
    /** 默认：单文件 HTML，内嵌 1024px 缩略图 */
    THUMBNAIL,

    /** 用户显式选择：单文件 HTML，内嵌原图。体积可能极大 */
    ORIGINAL,

    /** 降级：HTML + 图片文件夹，打包成 zip。照片多到单文件不现实时用 */
    FOLDER_ZIP,
}

/**
 * 体积阈值与降级规则。
 *
 * 这三个数字不是拍脑袋来的，来自分析报告 Part 1.3 缺口 3 的测算与
 * 风险登记表 R2 的触发条件（> 200 张照片或 > 150 MB 即降级）。
 */
object ExportThresholds {

    /** 默认模式的目标上限：超过就该提醒用户换模式 */
    const val TARGET_MAX_BYTES: Long = 50L * 1024 * 1024

    /** 单文件模式的绝对上限：超过就用 zip 模式，否则文件根本打不开 */
    const val DOWNGRADE_BYTES: Long = 150L * 1024 * 1024

    /** 照片数上限：超过就用 zip 模式 */
    const val DOWNGRADE_PHOTOS: Int = 200

    /** 缩略图相对原图的体积系数（1024px JPEG75 vs 1536+ 原图，经验值） */
    const val THUMBNAIL_RATIO: Double = 0.22
}

/** 导出前的体积预估，用于让用户知情后再选模式 */
data class ExportEstimate(
    val plantCount: Int,
    val photoCount: Int,
    /** 全部原图的字节数 */
    val originalBytes: Long,
    /** 按缩略图估算的字节数 */
    val estimatedThumbnailBytes: Long,
) {
    /** 单文件模式是否已不现实（照片太多或原图太大） */
    val requiresZip: Boolean
        get() = photoCount > ExportThresholds.DOWNGRADE_PHOTOS ||
            originalBytes > ExportThresholds.DOWNGRADE_BYTES

    /** 缩略图模式是否超出 50 MB 目标 */
    val thumbnailExceedsTarget: Boolean
        get() = estimatedThumbnailBytes > ExportThresholds.TARGET_MAX_BYTES

    fun estimateFor(mode: ExportMode): Long = when (mode) {
        // base64 膨胀约 4/3，再加上 HTML 本身
        ExportMode.THUMBNAIL -> estimatedThumbnailBytes * 4 / 3
        ExportMode.ORIGINAL -> originalBytes * 4 / 3
        // zip 里的图片不膨胀（zip 还会压一点），HTML 只引用相对路径
        ExportMode.FOLDER_ZIP -> originalBytes
    }
}

/** 一次导出的结果 */
data class ExportResult(
    val filePath: String,
    val fileName: String,
    val mode: ExportMode,
    val bytes: Long,
    val plantCount: Int,
    val photoCount: Int,
    /** 因体积或照片数自动降级过 */
    val downgraded: Boolean = false,
) {
    /** 供 UI 展示的一句话结论 */
    val summary: String
        get() = buildString {
            append("$plantCount 株植物 · $photoCount 张照片 · ")
            append(formatBytes(bytes))
            if (downgraded) append("（已自动降级为 zip 模式）")
        }
}

/**
 * 把字节数格式化成易读文本。
 *
 * 显式指定 [Locale.US] 而不依赖默认区域：德语、法语等区域会用逗号做小数点，
 * 「1,5 MB」在这个场景里只会让人困惑；而且同一份数据在不同手机上显示不一致时
 * 根本无从排查。
 *
 * 全项目只此一份。拍摄页曾经另有一份略有差异的实现（`0 字节` 一个显示
 * 「0 KB」、一个显示「0 B」），同一批数据在两个页面上是两种样子 ——
 * 显示口径必须只有一个来源。
 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
