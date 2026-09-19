package com.plantidentify.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告是**要给别人看的文件**，一次转义漏掉就可能让整份文档排版崩掉，
 * 而且用户拿到的是已经生成好的 HTML，没有第二次机会。
 */
class HtmlReportBuilderTest {

    @Test
    fun `转义全部五个危险字符`() {
        assertEquals("&amp;", HtmlReportBuilder.escape("&"))
        assertEquals("&lt;", HtmlReportBuilder.escape("<"))
        assertEquals("&gt;", HtmlReportBuilder.escape(">"))
        assertEquals("&quot;", HtmlReportBuilder.escape("\""))
        assertEquals("&#39;", HtmlReportBuilder.escape("'"))
    }

    @Test
    fun `先转义与号再转义尖括号`() {
        // 顺序反了会把 & 变成 &amp;lt; —— 页面上直接显示出 "&lt;"
        assertEquals("&amp;lt;", HtmlReportBuilder.escape("&lt;"))
        assertFalse(HtmlReportBuilder.escape("&lt;").contains("<"))
    }

    @Test
    fun `普通中文原样保留`() {
        assertEquals("紫薇（千屈菜科）", HtmlReportBuilder.escape("紫薇（千屈菜科）"))
    }

    @Test
    fun `卡片只渲染有值的字段`() {
        val html = HtmlReportBuilder.plantCard(1, plant(fruitingPeriod = null))
        assertTrue(html.contains("植物简介"))
        // 空字段若也渲染，报告里会出现一排「果期：—」，
        // 整份文档看起来像没做完
        assertFalse(html.contains("果期"))
    }

    @Test
    fun `别名与病虫害出现在卡片里`() {
        val html = HtmlReportBuilder.plantCard(1, plant())
        assertTrue(html.contains("常用名称 / 俗称"))
        assertTrue(html.contains("病虫害防治"))
    }

    @Test
    fun `科属不会重复带出「科科」`() {
        // family 字段本身已经带「科」字，再拼一个就成「千屈菜科科」
        val html = HtmlReportBuilder.plantCard(1, plant())
        assertFalse(html.contains("千屈菜科科"))
        assertTrue(html.contains("千屈菜科"))
    }

    @Test
    fun `观察记录渲染成表格`() {
        val html = HtmlReportBuilder.plantCard(
            1,
            plant().copy(
                observations = listOf(
                    ReportObservation(timeText = "2026-09-19 20:00", place = "上海", note = "开花"),
                ),
            ),
        )
        assertTrue(html.contains("2026-09-19 20:00"))
        assertTrue(html.contains("上海"))
        assertTrue(html.contains("开花"))
    }

    @Test
    fun `图片缺失时给出占位而不是坏图`() {
        val html = HtmlReportBuilder.plantCard(
            1,
            plant().copy(images = listOf(ReportImage(role = com.plantidentify.data.local.entity.ImageRole.LEAF, source = null))),
        )
        assertTrue(html.contains("图片缺失"))
    }

    private fun plant(
        fruitingPeriod: String? = "果期内容",
    ) = ReportPlant(
        name = "紫薇",
        commonNames = "紫薇花、痒痒树",
        latinName = "Lagerstroemia indica",
        family = "千屈菜科",
        genus = "紫薇属",
        category = "落叶灌木",
        confidence = 0.92,
        description = "简介内容",
        morphologicalFeatures = null,
        growthHabits = null,
        floweringPeriod = null,
        fruitingPeriod = fruitingPeriod,
        landscapeUses = null,
        careAdvice = null,
        pestControl = "蚜虫用吡虫啉",
        note = null,
        observations = emptyList(),
        images = emptyList(),
    )
}
