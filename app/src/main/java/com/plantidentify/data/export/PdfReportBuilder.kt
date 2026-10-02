package com.plantidentify.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** PDF 报告的内容模型 —— 与渲染完全分离，上层按用途组装 */
data class PdfReport(
    val title: String,
    val subtitle: String? = null,
    /** 顶部概览的若干「标签 : 值」 */
    val stats: List<Pair<String, String>> = emptyList(),

    /**
     * 报告的**前置正文**（排在统计概览之后、植物明细之前）。
     *
     * 景观报告用：统计结论 + AI 分析原文。普通档案报告为空。
     * 以 `## ` 开头的行按小节标题渲染，其余按正文段落渲染 ——
     * 只认这一种标记，因为来源只有统计摘要与 AI 输出，约定一个就够。
     */
    val preface: List<String> = emptyList(),

    val plants: List<PdfPlant> = emptyList(),
    val footerNote: String? = null,
)

data class PdfPlant(
    val name: String,
    val latinName: String? = null,
    /** 档案字段（科 / 属 / 类型 / 可信度…），按顺序渲染成两列表格 */
    val fields: List<Pair<String, String>> = emptyList(),
    val observations: List<PdfObservation> = emptyList(),
    val imagePaths: List<String> = emptyList(),
)

data class PdfObservation(
    val timeText: String,
    val place: String? = null,
    val note: String? = null,
)

/**
 * PDF 报告生成（v1.0.2 Phase 2 §十四）。
 *
 * ## 为什么用系统的 `PdfDocument`
 *
 * 它就是 Android 自带的「把 Canvas 画进 PDF」的 API，零依赖、零体积代价
 * （对比：iText 是 AGPL，商用要授权；PdfBox-Android 要多打几 MB）。
 * 代价是**排版要自己写** —— 分页、换行、表格对齐都得手算，
 * 所以这个文件比 `XlsxWriter` 长得多。
 *
 * ## 「真正的报告格式」具体指什么
 *
 * 需求特意写了一句「不是简单把数据库内容转成文本」。落到实处是：
 * 有页眉页脚与页码、字段排成左标签右值的表、照片按网格排、内容自动分页
 * 且**不会把一个字段切两半**。这些东西看起来琐碎，但它们决定了一份
 * 导出的 PDF 是「能直接交上去的档案」还是「一坨文字」。
 *
 * ## 分页的单位是「块」而不是「行」
 *
 * 每次画东西之前先问 [PageWriter.ensureSpace]：放不下就换页。
 * 一个字段、一张图、一段观察各算一块 —— 所以**不会出现半个字段留在页底**
 * 这种最难看的情况。
 */
class PdfReportBuilder(
    private val context: Context,
    private val imageStore: ImageStore,
) {

    fun exportDir(): File = File(context.filesDir, DIR_EXPORTS)

    suspend fun build(report: PdfReport, file: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val document = PdfDocument()
            try {
                val writer = PageWriter(document)
                writer.begin()
                render(writer, report)
                writer.finish()
                FileOutputStream(file).use { document.writeTo(it) }
            } finally {
                document.close()
            }
            file
        }
    }

    private fun render(writer: PageWriter, report: PdfReport) {
        // ---- 封面区（不单独占一页：报告通常没长到需要封面）----
        writer.drawTitle(report.title)
        report.subtitle?.let { writer.drawSubtitle(it) }
        if (report.stats.isNotEmpty()) {
            writer.ensureSpace(20f + report.stats.size * 16f)
            writer.drawDivider()
            report.stats.forEach { (label, value) ->
                writer.drawKeyValue(label, value)
            }
            writer.drawDivider()
        }

        // ---- 前置正文（景观报告的统计结论与 AI 分析）----
        report.preface.forEach { line ->
            writer.ensureSpace(24f)
            if (line.startsWith("## ")) {
                writer.drawSpacer(4f)
                writer.drawSectionLabel(line.removePrefix("## ").trim())
            } else if (line.startsWith("> ")) {
                // 引用行（AI 报告的免责声明）用脚注样式，视觉上明显弱于正文
                writer.drawFootnote(line.removePrefix("> ").trim())
                writer.drawSpacer(2f)
            } else if (line.isNotBlank()) {
                writer.drawParagraph(line, writer.paintBody)
                writer.drawSpacer(2f)
            }
        }

        if (report.plants.isEmpty()) {
            writer.ensureSpace(30f)
            writer.drawParagraph("（没有可导出的档案）", writer.paintBody)
        }

        report.plants.forEachIndexed { index, plant ->
            writer.ensureSpace(90f)
            if (index > 0) writer.drawSpacer(12f)
            writer.drawPlantHeader(index + 1, plant)
            plant.fields.forEach { (label, value) ->
                if (value.isNotBlank()) {
                    writer.ensureSpace(30f)
                    writer.drawKeyValue(label, value)
                }
            }

            if (plant.imagePaths.isNotEmpty()) {
                writer.ensureSpace(30f)
                writer.drawSectionLabel("照片（${plant.imagePaths.size}）")
                writer.drawImageGrid(plant.imagePaths) { imageStore.resolve(it) }
            }

            if (plant.observations.isNotEmpty()) {
                writer.ensureSpace(30f)
                writer.drawSectionLabel("观察记录（${plant.observations.size}）")
                plant.observations.forEach { observation ->
                    writer.ensureSpace(40f)
                    writer.drawObservation(observation)
                }
            }
        }

        report.footerNote?.let {
            writer.ensureSpace(40f)
            writer.drawSpacer(10f)
            writer.drawDivider()
            writer.drawFootnote(it)
        }
    }

    private companion object {
        const val DIR_EXPORTS = "exports"
    }
}

/**
 * 分页写手：把「画什么」和「画在哪一页」彻底分开。
 *
 * 上层只管画，放不下它会自己开新页 —— 于是渲染代码里看不到一处
 * `if (y > …)` 这类分页判断。
 */
private class PageWriter(private val document: PdfDocument) {

    private var page: PdfDocument.Page? = null
    private var canvas: Canvas = Canvas()
    private var y = 0f
    private var pageNumber = 0

    private val pageWidth = 595   // A4 @72dpi
    private val pageHeight = 842
    private val margin = 48f
    private val contentWidth get() = pageWidth - margin * 2

    val paintBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 38, 38)
        textSize = 10.5f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 110, 110)
        textSize = 10.5f
    }
    private val paintFootnote = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 120, 120)
        textSize = 9f
    }
    private val paintRule = Paint().apply {
        color = Color.rgb(210, 210, 210)
        strokeWidth = 0.7f
    }

    fun begin() {
        newPage()
    }

    fun finish() {
        closePage()
    }

    // ---------------------------------------------------------------- 分页

    fun ensureSpace(height: Float) {
        if (y + height > pageHeight - margin - FOOTER_RESERVE) newPage()
    }

    private fun newPage() {
        closePage()
        pageNumber++
        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        val started = document.startPage(info)
        page = started
        canvas = started.canvas
        y = margin
    }

    private fun closePage() {
        val current = page ?: return
        // 页脚：页码 + 生成说明。每一页都有，翻页时才不会丢失上下文
        val footer = "第 $pageNumber 页"
        canvas.drawText(footer, pageWidth - margin - paintFootnote.measureText(footer),
            pageHeight - margin * 0.6f, paintFootnote)
        document.finishPage(current)
        page = null
    }

    // ---------------------------------------------------------------- 绘制

    fun drawTitle(text: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 20)
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        ensureSpace(30f)
        canvas.drawText(text, margin, y + paint.textSize, paint)
        y += paint.textSize + 6f
    }

    fun drawSubtitle(text: String) {
        ensureSpace(18f)
        canvas.drawText(text, margin, y + paintBody.textSize, paintFootnote)
        y += paintBody.textSize + 6f
    }

    fun drawPlantHeader(index: Int, plant: PdfPlant) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 15, 15)
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("$index. ${plant.name}", margin, y + paint.textSize, paint)
        y += paint.textSize + 4f
        plant.latinName?.takeIf { it.isNotBlank() }?.let {
            val italic = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(100, 100, 100)
                textSize = 10.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            }
            ensureSpace(16f)
            canvas.drawText(it, margin, y + italic.textSize, italic)
            y += italic.textSize + 4f
        }
        drawDivider()
    }

    /** 左标签（固定 68pt）+ 右值（自动换行）的两列布局 */
    fun drawKeyValue(label: String, value: String) {
        val labelWidth = 68f
        canvas.drawText(label, margin, y + paintBody.textSize, paintLabel)
        val lines = wrap(value, paintBody, contentWidth - labelWidth)
        lines.forEach { line ->
            ensureSpace(paintBody.textSize + 4f)
            canvas.drawText(line, margin + labelWidth, y + paintBody.textSize, paintBody)
            y += paintBody.textSize + 3.5f
        }
        y += 1.5f
    }

    fun drawSectionLabel(text: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 70, 70)
            textSize = 10f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        ensureSpace(16f)
        canvas.drawText(text, margin, y + paint.textSize, paint)
        y += paint.textSize + 4f
    }

    /** 脚注段落（灰字小号）。渲染层不该直接碰 `Paint`，所以在这里包一层 */
    fun drawFootnote(text: String) {
        drawParagraph(text, paintFootnote)
    }

    fun drawParagraph(text: String, paint: Paint) {
        wrap(text, paint, contentWidth).forEach { line ->
            ensureSpace(paint.textSize + 4f)
            canvas.drawText(line, margin, y + paint.textSize, paint)
            y += paint.textSize + 3.5f
        }
    }

    fun drawObservation(observation: PdfObservation) {
        ensureSpace(16f)
        canvas.drawText(observation.timeText, margin + 6f, y + paintBody.textSize, paintBody)
        y += paintBody.textSize + 3f
        val detail = listOfNotNull(
            observation.place?.takeIf { it.isNotBlank() },
            observation.note?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (detail.isNotBlank()) {
            val lines = wrap(detail, paintFootnote, contentWidth - 6f)
            lines.forEach { line ->
                ensureSpace(paintFootnote.textSize + 3f)
                canvas.drawText(line, margin + 6f, y + paintFootnote.textSize, paintFootnote)
                y += paintFootnote.textSize + 2.5f
            }
        }
        y += 2f
    }

    /**
     * 照片网格。
     *
     * 每张图按 3 列排，**整行一起判断空间**（不是一张一张判断）——
     * 否则一行可能被切到两页上，看起来像排版坏了。
     */
    fun drawImageGrid(paths: List<String>, resolve: (String) -> File) {
        val columns = 3
        val gap = 8f
        val cellWidth = (contentWidth - gap * (columns - 1)) / columns
        val cellHeight = cellWidth * 0.75f

        paths.chunked(columns).forEach { row ->
            ensureSpace(cellHeight + gap)
            row.forEachIndexed { index, path ->
                // 解不出图的直接跳过：一张坏图不该让整份报告生成失败
                val bitmap = decodeScaled(resolve(path), cellWidth) ?: return@forEachIndexed
                val left = margin + index * (cellWidth + gap)
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(
                        left.toInt(), y.toInt(),
                        (left + cellWidth).toInt(), (y + cellHeight).toInt(),
                    ),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                bitmap.recycle()
            }
            y += cellHeight + gap
        }
        y += 2f
    }

    fun drawDivider() {
        ensureSpace(8f)
        canvas.drawLine(margin, y, pageWidth - margin, y, paintRule)
        y += 7f
    }

    fun drawSpacer(height: Float) {
        y += height
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 按字符切行。
     *
     * 用 `breakText` 而不是自己按字符宽度累加 —— 它内部处理了字距、
     * 连字与代理对（emoji 是两个 char），自己数容易把字切开。
     *
     * 显式处理 `\n`：目录、备注里的换行是用户写的，必须保留。
     */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val result = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) {
                result += ""
                return@forEach
            }
            var rest = paragraph
            while (rest.isNotEmpty()) {
                val count = paint.breakText(rest, true, width, null)
                if (count <= 0) break
                result += rest.substring(0, count)
                rest = rest.substring(count)
            }
        }
        return result
    }

    /**
     * 按目标宽度解码，避免把 4000px 的原图整张读进内存。
     *
     * PDF 的 A4 一页只有 595pt 宽，一格里放 160pt 就够 ——
     * 用原图既浪费内存又不会更清晰（除非以后要做打印级的 300dpi 输出）。
     */
    private fun decodeScaled(file: File, targetWidth: Float): Bitmap? = runCatching {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        // 目标像素宽 ≈ points × 2（在 144dpi 下打印也够看）
        val targetPx = (targetWidth * 2).toInt().coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrNull()

    private companion object {
        /** 页脚占用的高度，在判断空间时要预留出来 */
        const val FOOTER_RESERVE = 34f
    }
}
