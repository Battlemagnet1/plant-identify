package com.plantidentify.data.export

import com.plantidentify.data.local.entity.ImageRole
import com.plantidentify.ui.components.label

/** 报告头部要用的三个统计数 */
data class ReportStats(
    val distinctPlants: Int,
    val observationCount: Int,
    val imageCount: Int,
)

/** 报告里的单次观察 */
data class ReportObservation(
    val timeText: String,
    val place: String?,
    val note: String?,
)

/**
 * 报告里的一张照片。
 *
 * [source] 直接就是 `<img src>` 的值，两种形态：
 * - 单文件模式：`data:image/jpeg;base64,...`
 * - zip 模式：`images/2026/09/xxx.jpg`
 *
 * 为空表示这张图读不出来（文件缺失），此时渲染占位框而不是破图。
 */
data class ReportImage(
    val role: ImageRole,
    val source: String?,
)

/** 报告里的一株植物 */
data class ReportPlant(
    val name: String,
    val latinName: String?,
    val family: String?,
    val genus: String?,
    val category: String?,
    val confidence: Double,
    val description: String?,
    val morphologicalFeatures: String?,
    val growthHabits: String?,
    val floweringPeriod: String?,
    val fruitingPeriod: String?,
    val landscapeUses: String?,
    val careAdvice: String?,
    val note: String?,
    val observations: List<ReportObservation>,
    val images: List<ReportImage>,
)

/**
 * 生成 HTML 报告（规格书第二十一节的结构）。
 *
 * ## 为什么是「逐株拼接」而不是一次性生成整个文档
 *
 * 报告最大的风险是体积（缺口 3）。如果把整份 HTML 变成一个 String，
 * 一个 50 MB 的文件在 Kotlin 里会占约 100 MB 的 UTF-16 内存 —— 直接 OOM。
 * 所以这里每个方法只返回**一株植物**的片段，由调用方逐段写进输出流，
 * 任意时刻内存里只有一株植物的内容。
 *
 * ## 为什么要转义
 *
 * 植物的简介、备注、地名都来自用户与模型，里面完全可能出现 `<`、`&`。
 * 不转义的话轻则排版错乱，重则把一段文本变成标签、破坏整个文档结构。
 */
object HtmlReportBuilder {

    /** charset 必须显式声明为 UTF-8，否则在电脑浏览器上中文会乱码 */
    fun documentStart(title: String, generatedAt: String, stats: ReportStats): String = """
        |<!DOCTYPE html>
        |<html lang="zh-CN">
        |<head>
        |<meta charset="UTF-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<title>${escape(title)} — 植物调查报告</title>
        |<style>
        |  :root { color-scheme: light; }
        |  body {
        |    margin: 0; padding: 32px 20px 64px;
        |    font-family: -apple-system, "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif;
        |    line-height: 1.7; color: #1f2933; background: #f7f8fa;
        |  }
        |  .page { max-width: 820px; margin: 0 auto; }
        |  h1 { font-size: 28px; margin: 0 0 4px; letter-spacing: .5px; }
        |  .subtitle { color: #6b7280; font-size: 14px; margin-bottom: 28px; }
        |  .stats { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
        |  .stat {
        |    flex: 1 1 140px; background: #fff; border: 1px solid #e5e7eb;
        |    border-radius: 10px; padding: 14px 16px;
        |  }
        |  .stat b { display: block; font-size: 24px; }
        |  .stat span { color: #6b7280; font-size: 13px; }
        |  .hint { color: #6b7280; font-size: 13px; margin: 10px 0 0; }
        |  hr.sep { border: 0; border-top: 1px solid #e5e7eb; margin: 28px 0; }
        |  .plant { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px;
        |           padding: 20px 22px; margin-bottom: 20px; }
        |  .plant h2 { font-size: 20px; margin: 0 0 14px; }
        |  .plant h2 .idx { color: #9ca3af; font-weight: 400; margin-right: 8px; }
        |  .photos { display: flex; flex-wrap: wrap; gap: 10px; margin: 0 0 16px; }
        |  .photos figure { margin: 0; width: 200px; }
        |  .photos img { width: 100%; border-radius: 8px; display: block; }
        |  .photos figcaption { font-size: 12px; color: #6b7280; margin-top: 4px; }
        |  .missing { width: 200px; height: 140px; border: 1px dashed #d1d5db; border-radius: 8px;
        |             display: flex; align-items: center; justify-content: center;
        |             color: #9ca3af; font-size: 12px; }
        |  dl { margin: 0; }
        |  dt { font-weight: 600; color: #374151; font-size: 14px; margin-top: 12px; }
        |  dd { margin: 2px 0 0; white-space: pre-wrap; }
        |  table.obs { width: 100%; border-collapse: collapse; margin-top: 6px; font-size: 14px; }
        |  table.obs th, table.obs td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #eef0f3; }
        |  table.obs th { color: #6b7280; font-weight: 500; }
        |  footer { color: #9ca3af; font-size: 12px; margin-top: 32px; text-align: center; }
        |</style>
        |</head>
        |<body>
        |<div class="page">
        |<h1>${escape(title)}</h1>
        |<div class="subtitle">植物调查报告 · 生成时间 ${escape(generatedAt)}</div>
        |<div class="stats">
        |  <div class="stat"><b>${stats.distinctPlants}</b><span>不同植物</span></div>
        |  <div class="stat"><b>${stats.observationCount}</b><span>观察次数</span></div>
        |  <div class="stat"><b>${stats.imageCount}</b><span>照片数</span></div>
        |</div>
        |<p class="hint">三项分母不同：不同植物为去重后的物种数，观察次数为累计记录数，照片数为图片文件数。</p>
        |<hr class="sep">
        |""".trimMargin()

    /**
     * 一株植物的卡片。
     *
     * 字段顺序照规格书第二十一节，只渲染有值的字段 —— 一堆空的
     * 「形态特征：—」只会让报告显得像没做完。
     */
    fun plantCard(index: Int, plant: ReportPlant): String = buildString {
        append("<section class=\"plant\">\n")
        append("<h2><span class=\"idx\">")
        append(index.toString().padStart(2, '0'))
        append("</span>")
        append(escape(plant.name))
        append("</h2>\n")

        if (plant.images.isNotEmpty()) {
            append("<div class=\"photos\">\n")
            plant.images.forEach { image ->
                append("<figure>")
                if (image.source != null) {
                    append("<img src=\"")
                    append(image.source)
                    append("\" alt=\"")
                    append(escape(plant.name))
                    append("\">")
                } else {
                    append("<div class=\"missing\">图片缺失</div>")
                }
                append("<figcaption>")
                append(escape(image.role.label))
                append("</figcaption></figure>\n")
            }
            append("</div>\n")
        }

        append("<dl>\n")
        field("中文名称", plant.name)
        field("拉丁学名", plant.latinName)
        field("科", plant.family)
        field("属", plant.genus)
        field("植物类型", plant.category)
        field("AI识别置信度", "${(plant.confidence * 100).toInt()}%")
        field("植物简介", plant.description)
        field("形态特征", plant.morphologicalFeatures)
        field("生长习性", plant.growthHabits)
        field("花期", plant.floweringPeriod)
        field("果期", plant.fruitingPeriod)
        field("园林用途", plant.landscapeUses)
        field("养护建议", plant.careAdvice)
        field("备注", plant.note)
        append("</dl>\n")

        if (plant.observations.isNotEmpty()) {
            append("<dl><dt>观察记录</dt></dl>\n")
            append("<table class=\"obs\">\n")
            append("<tr><th>时间</th><th>地点</th><th>备注</th></tr>\n")
            plant.observations.forEach { observation ->
                append("<tr><td>")
                append(escape(observation.timeText))
                append("</td><td>")
                append(escape(observation.place ?: "—"))
                append("</td><td>")
                append(escape(observation.note ?: "—"))
                append("</td></tr>\n")
            }
            append("</table>\n")
        }

        append("</section>\n")
    }

    fun documentEnd(disclaimer: String): String = """
        |<footer>${escape(disclaimer)}</footer>
        |</div>
        |</body>
        |</html>
        |""".trimMargin()

    /** 图片以 Data URI 内嵌 —— 单文件 HTML 的关键 */
    fun dataUri(jpegBytes: ByteArray): String =
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(
            jpegBytes,
            android.util.Base64.NO_WRAP,
        )

    private fun StringBuilder.field(label: String, value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return
        append("<dt>").append(escape(label)).append("</dt>")
        append("<dd>").append(escape(text)).append("</dd>\n")
    }

    /**
     * HTML 转义。
     *
     * `&` 必须最先替换 —— 否则后面替换出来的 `&lt;` 会被二次转义成 `&amp;lt;`。
     */
    fun escape(raw: String): String = buildString(raw.length + 16) {
        raw.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
}
