package com.plantidentify.data.export

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 一个工作表：名字 + 行数据。单元格为 `null` 表示留空 */
data class XlsxSheet(
    val name: String,
    val rows: List<List<String?>>,
)

/**
 * 极简 XLSX 写出器（v1.0.2 Phase 2）。
 *
 * ## 为什么手写而不是引入 Apache POI
 *
 * POI 的 `poi-ooxml` 及其传递依赖打进 APK 要 **+10 MB 以上**，而我们要写的东西
 * 只是「几行几列的文本」。`.xlsx` 的本质是一个装着几个 XML 的 ZIP 包，
 * 生成它不需要一整个 Office 文档对象模型。
 *
 * 代价是**样式能力有限** —— 本实现只写数据，不做单元格格式（字体/颜色/合并单元格）。
 * 导出报表要的是「内容正确、能打开、能继续编辑」，加粗表头这类需求以后真的提了再说。
 *
 * ## 为什么用 `inlineStr` 而不是 `sharedStrings.xml`
 *
 * OOXML 有两种放字符串的方式：共用的字符串表（要额外维护一份 `sharedStrings.xml`
 * 并记住每个字符串的索引）和直接内联（`t="inlineStr"`）。
 * 我们的数据是一行行的档案字段，重复值很少，**内联省掉了整个部件和它的簿记**。
 *
 * ## 空表也要能写
 *
 * `rows` 为空时写一个只有表头（或完全没有行）的 sheet —— Excel 打开时是空的，
 * 而不是打不开。这是「查询结果恰好为空」时必须走到的分支。
 */
object XlsxWriter {

    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL_DOC =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_CONTENT_TYPES =
        "http://schemas.openxmlformats.org/package/2006/content-types"

    /** 工作表名的上限是 Excel 定的：31 个字符，且不允许这几个符号 */
    private const val MAX_SHEET_NAME = 31

    fun write(file: File, sheets: List<XlsxSheet>) {
        require(sheets.isNotEmpty()) { "至少要有一个工作表" }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            zip.putText("[Content_Types].xml", contentTypes(sheets.size))
            zip.putText("_rels/.rels", rootRels())
            zip.putText("xl/workbook.xml", workbook(sheets))
            zip.putText("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            sheets.forEachIndexed { index, sheet ->
                zip.putText("xl/worksheets/sheet${index + 1}.xml", worksheet(sheet))
            }
        }
    }

    // ---------------------------------------------------------------- 部件

    private fun ZipOutputStream.putText(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="$NS_CONTENT_TYPES">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append(
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""",
        )
        for (i in 1..sheetCount) {
            append(
                """<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""",
            )
        }
        append("</Types>")
    }

    private fun rootRels(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="$NS_PKG_REL">""")
        append(
            """<Relationship Id="rId1" Type="$NS_REL_DOC/officeDocument" Target="xl/workbook.xml"/>""",
        )
        append("</Relationships>")
    }

    private fun workbook(sheets: List<XlsxSheet>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="$NS_MAIN" xmlns:r="$NS_REL_DOC">""")
        append("<sheets>")
        sheets.forEachIndexed { index, sheet ->
            // sheetId 从 1 开始、rId 与 workbookRels 里的编号一一对应
            append(
                """<sheet name="${escape(sanitizeSheetName(sheet.name, index))}" """ +
                    """sheetId="${index + 1}" r:id="rId${index + 1}"/>""",
            )
        }
        append("</sheets>")
        append("</workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="$NS_PKG_REL">""")
        for (i in 1..sheetCount) {
            append(
                """<Relationship Id="rId$i" Type="$NS_REL_DOC/worksheet" Target="worksheets/sheet$i.xml"/>""",
            )
        }
        append("</Relationships>")
    }

    private fun worksheet(sheet: XlsxSheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="$NS_MAIN">""")
        append("<sheetData>")
        sheet.rows.forEachIndexed { rowIndex, cells ->
            val rowNumber = rowIndex + 1
            append("""<row r="$rowNumber">""")
            cells.forEachIndexed { colIndex, value ->
                if (value == null) return@forEachIndexed
                append(
                    """<c r="${columnName(colIndex)}$rowNumber" t="inlineStr"><is>""" +
                        """<t xml:space="preserve">${escape(value)}</t></is></c>""",
                )
            }
            append("</row>")
        }
        append("</sheetData>")
        append("</worksheet>")
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 列序号 → 列名（0 → A，25 → Z，26 → AA）。
     *
     * 这是**双射的 26 进制**，但没有 0 这个「数字」——
     * 所以取模后要 +1 再借用，不能直接 `'A' + n % 26`。
     */
    internal fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.append(('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.reverse().toString()
    }

    /**
     * Excel 对工作表名有三条硬限制，违反任意一条文件就打不开：
     * 不超过 31 个字符、不能含 `[ ] : * ? / \`、不能为空。
     *
     * 另外名字也不能重复 —— 这里用序号兜底，保证同一份文件内一定不重名。
     */
    internal fun sanitizeSheetName(raw: String, index: Int): String {
        val cleaned = raw
            .replace(Regex("""[\[\]:*?/\\]"""), "_")
            .take(MAX_SHEET_NAME)
        // 「清洗后只剩替换符」说明原名字里的字符**全部**非法 —— 此时 `___` 虽然
        // 能打开但毫无信息量（用户看到的是三个下划线，认不出这是哪张表），
        // 不如用序号兜底来得清楚
        return if (cleaned.isBlank() || cleaned.all { it == '_' }) {
            "Sheet${index + 1}"
        } else {
            cleaned
        }
    }

    /** XML 文本转义。`&` 必须第一个替换，否则会把后面生成的实体再转一遍 */
    internal fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
