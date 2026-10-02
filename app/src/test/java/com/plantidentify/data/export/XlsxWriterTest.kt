package com.plantidentify.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/**
 * XLSX 写出器的单测（v1.0.2 Phase 2）。
 *
 * 这个类刻意断言的是**「文件结构正确」而不是「Excel 打得开」** ——
 * 后者没法在 JVM 单测里验证（要起一个 Office 解析器）。所以策略是：
 * 把 OOXML 规范里那几条「违反就打不开」的硬性要求逐条钉住 ——
 * 部件齐全、关系 id 对得上、非法字符被清掉、XML 该转义的都转了。
 *
 * 真机上一次「导出的 Excel 能用别的应用打开」的验证仍然要做（见验收脚本）。
 */
class XlsxWriterTest {

    // ------------------------------------------------------------ columnName

    @Test
    fun `列名按 26 进制展开，且没有 0 这个数字`() {
        assertEquals("A", XlsxWriter.columnName(0))
        assertEquals("B", XlsxWriter.columnName(1))
        assertEquals("Z", XlsxWriter.columnName(25))
        // 关键边界：Z 之后是 AA 而不是 BA（因为这一列没有「0」）
        assertEquals("AA", XlsxWriter.columnName(26))
        assertEquals("AB", XlsxWriter.columnName(27))
        assertEquals("AZ", XlsxWriter.columnName(51))
        assertEquals("BA", XlsxWriter.columnName(52))
        assertEquals("ZZ", XlsxWriter.columnName(701))
        assertEquals("AAA", XlsxWriter.columnName(702))
    }

    // ---------------------------------------------------------------- escape

    @Test
    fun `XML 特殊字符全部转义，且 & 不会二次转义`() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", XlsxWriter.escape("&<>\"'"))
        // 如果 & 不是第一个被替换的，这里会变成 &amp;lt;
        assertEquals("&amp;lt;", XlsxWriter.escape("&lt;"))
    }

    // ------------------------------------------------------- sanitizeSheetName

    @Test
    fun `工作表名会清掉 Excel 不接受的字符`() {
        assertEquals("a_b_c_d_e_f_g", XlsxWriter.sanitizeSheetName("a[b]c:d*e?f/g", 0))
        // 反斜杠也在禁止列表里
        assertEquals("a_b", XlsxWriter.sanitizeSheetName("""a\b""", 0))
    }

    @Test
    fun `工作表名超过 31 字符会被截断`() {
        val long = "一二三四五六七八九十".repeat(5)
        val result = XlsxWriter.sanitizeSheetName(long, 0)
        assertEquals(31, result.length)
    }

    @Test
    fun `工作表名为空时用序号兜底`() {
        assertEquals("Sheet1", XlsxWriter.sanitizeSheetName("", 0))
        // 全部是非法字符时也会被清空，同样走兜底
        assertEquals("Sheet2", XlsxWriter.sanitizeSheetName("[]:", 1))
    }

    // ----------------------------------------------------------------- write

    @Test
    fun `生成的文件是一个含全部预期部件的 zip`() {
        val file = File.createTempFile("xlsx-test", ".xlsx")
        try {
            XlsxWriter.write(
                file,
                listOf(
                    XlsxSheet("植物档案", listOf(listOf("中文名", "拉丁名"), listOf("紫薇", "Lagerstroemia indica"))),
                    XlsxSheet("观察记录", listOf(listOf("ID", "时间"))),
                ),
            )

            val entries = mutableMapOf<String, String>()
            ZipInputStream(file.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            assertTrue("缺 [Content_Types].xml", entries.containsKey("[Content_Types].xml"))
            assertTrue("缺 _rels/.rels", entries.containsKey("_rels/.rels"))
            assertTrue("缺 workbook", entries.containsKey("xl/workbook.xml"))
            assertTrue("缺 workbook 关系表", entries.containsKey("xl/_rels/workbook.xml.rels"))
            assertTrue("缺第一个工作表", entries.containsKey("xl/worksheets/sheet1.xml"))
            assertTrue("缺第二个工作表", entries.containsKey("xl/worksheets/sheet2.xml"))

            // Content_Types 必须为每个 sheet 声明 Override，否则 Excel 报文件损坏
            val types = entries.getValue("[Content_Types].xml")
            assertTrue(types.contains("/xl/worksheets/sheet1.xml"))
            assertTrue(types.contains("/xl/worksheets/sheet2.xml"))

            // workbook 里的 r:id 必须与关系表里的 Id 对得上
            val workbook = entries.getValue("xl/workbook.xml")
            assertTrue(workbook.contains("""name="植物档案""""))
            assertTrue(workbook.contains("""r:id="rId1""""))
            val rels = entries.getValue("xl/_rels/workbook.xml.rels")
            assertTrue(rels.contains("""Id="rId1""""))
            assertTrue(rels.contains("""Id="rId2""""))

            // 单元格内容与坐标
            val sheet1 = entries.getValue("xl/worksheets/sheet1.xml")
            assertTrue(sheet1.contains("""r="A1""""))
            assertTrue(sheet1.contains(">中文名<"))
            assertTrue(sheet1.contains("""r="B2""""))
            assertTrue(sheet1.contains(">Lagerstroemia indica<"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `单元格里的换行与特殊字符被安全写入`() {
        val file = File.createTempFile("xlsx-escape", ".xlsx")
        try {
            XlsxWriter.write(
                file,
                listOf(XlsxSheet("S", listOf(listOf("第一行\n第二行 & <标签>")))),
            )
            val content = readEntry(file, "xl/worksheets/sheet1.xml")
            assertTrue(content.contains("xml:space=\"preserve\""))
            assertTrue(content.contains("&amp; &lt;标签&gt;"))
            // 原始字符绝不允许出现在 XML 里
            assertTrue(!content.contains("<标签>"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `空行集合也能写出一个合法的工作表`() {
        val file = File.createTempFile("xlsx-empty", ".xlsx")
        try {
            XlsxWriter.write(file, listOf(XlsxSheet("空", emptyList())))
            val content = readEntry(file, "xl/worksheets/sheet1.xml")
            assertTrue(content.contains("<sheetData></sheetData>"))
        } finally {
            file.delete()
        }
    }

    private fun readEntry(file: File, path: String): String {
        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == path) return zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        error("找不到部件 $path")
    }
}
