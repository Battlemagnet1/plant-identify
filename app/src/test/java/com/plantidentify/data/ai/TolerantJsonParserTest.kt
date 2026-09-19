package com.plantidentify.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容错解析是整条识别链路里最值得单测的一环。
 *
 * 模型返回什么形状都有可能：带 markdown 围栏、围栏外还写一段解释、
 * 用中文键名、字段缺一半。这些分支在真机上极难复现（要凑出模型的
 * 各种坏脾气），但每一次出问题都会直接表现成「识别失败」。
 */
class TolerantJsonParserTest {

    // ---------------------------------------------------------- 围栏剥离

    @Test
    fun `没有围栏时原样返回`() {
        val raw = """{"name":"紫薇"}"""
        assertEquals(raw, TolerantJsonParser.stripCodeFence(raw))
    }

    @Test
    fun `剥掉 json 围栏`() {
        val raw = "```json\n{\"name\":\"紫薇\"}\n```"
        assertEquals("""{"name":"紫薇"}""", TolerantJsonParser.stripCodeFence(raw))
    }

    @Test
    fun `剥掉无语言标记的围栏`() {
        val raw = "```\n{\"name\":\"紫薇\"}\n```"
        assertEquals("""{"name":"紫薇"}""", TolerantJsonParser.stripCodeFence(raw))
    }

    // ---------------------------------------------------------- 括号配对扫描

    @Test
    fun `从混杂文本里挖出 JSON`() {
        val raw = "好的，我分析了一下：{\"name\":\"紫薇\"} 以上是我的判断。"
        assertEquals("""{"name":"紫薇"}""", TolerantJsonParser.extractFirstJsonObject(raw))
    }

    @Test
    fun `嵌套对象能配对到最外层`() {
        val raw = """前缀{"a":{"b":{"c":1}},"d":2}后缀"""
        assertEquals("""{"a":{"b":{"c":1}},"d":2}""", TolerantJsonParser.extractFirstJsonObject(raw))
    }

    @Test
    fun `字符串里的花括号不算层级`() {
        // 这是最容易写错的一种：直接数括号会把字符串内的 } 当成结构结束
        val raw = """{"note":"用 { 包裹 } 即可","name":"紫薇"}"""
        assertEquals(raw, TolerantJsonParser.extractFirstJsonObject(raw))
    }

    @Test
    fun `转义的引号不结束字符串`() {
        val raw = """{"note":"他说\"你好\"","name":"紫薇"}"""
        assertEquals(raw, TolerantJsonParser.extractFirstJsonObject(raw))
    }

    @Test
    fun `没有 JSON 时返回 null`() {
        assertNull(TolerantJsonParser.extractFirstJsonObject("这里没有任何结构化内容"))
    }

    // ---------------------------------------------------------- 识别结果

    @Test
    fun `核心字段齐全时才算完全成功`() {
        val raw = """{"name":"紫薇","latinName":"Lagerstroemia indica",""" +
            """"family":"千屈菜科","genus":"紫薇属","confidence":0.92}"""
        val attempt = TolerantJsonParser.parse(raw)
        assertTrue(attempt is TolerantJsonParser.ParseAttempt.Success)
        val result = (attempt as TolerantJsonParser.ParseAttempt.Success).result
        assertEquals("紫薇", result.name)
        assertEquals("千屈菜科", result.family)
        assertEquals(0.92, result.confidence, 1e-9)
    }

    @Test
    fun `缺科属学名时降级并列出缺了什么`() {
        // 名称认出来了、结果可用，但缺了核心字段 —— 必须如实说这是
        // 「尽力提取」而不是完整结果，否则用户会把半份数据当全份
        val raw = """{"name":"紫薇","confidence":0.92}"""
        val attempt = TolerantJsonParser.parse(raw)
        assertTrue(attempt is TolerantJsonParser.ParseAttempt.Degraded)
        val degraded = attempt as TolerantJsonParser.ParseAttempt.Degraded
        assertEquals("紫薇", degraded.result.name)
        listOf("拉丁学名", "科", "属").forEach { missing ->
            assertTrue("降级说明里应提到缺了 $missing", degraded.note.contains(missing))
        }
    }

    @Test
    fun `置信度为 0 也算降级`() {
        // 置信度 0 意味着模型没说它有多确定，这本身就是要提示用户的信息
        val raw = """{"name":"紫薇","latinName":"a","family":"b","genus":"c","confidence":0}"""
        assertTrue(TolerantJsonParser.parse(raw) is TolerantJsonParser.ParseAttempt.Degraded)
    }

    @Test
    fun `围栏包裹的完整 JSON 依然是完全成功`() {
        val raw = "```json\n" +
            """{"name":"紫薇","latinName":"Lagerstroemia indica",""" +
            """"family":"千屈菜科","genus":"紫薇属","confidence":0.9}\n```"""
        assertTrue(TolerantJsonParser.parse(raw) is TolerantJsonParser.ParseAttempt.Success)
    }

    @Test
    fun `JSON 之外还有文字时降级并给出说明`() {
        val raw = "我先说明一下思路。{\"name\":\"紫薇\",\"confidence\":0.9} 以上就是结果。"
        val attempt = TolerantJsonParser.parse(raw)
        assertTrue(attempt is TolerantJsonParser.ParseAttempt.Degraded)
        val degraded = attempt as TolerantJsonParser.ParseAttempt.Degraded
        assertEquals("紫薇", degraded.result.name)
        // 降级必须带说明 —— 界面上要如实告诉用户「这是尽力提取的」
        assertTrue(degraded.note.isNotBlank())
    }

    @Test
    fun `空内容报失败而不是抛出异常`() {
        val attempt = TolerantJsonParser.parse("   ")
        assertTrue(attempt is TolerantJsonParser.ParseAttempt.Failed)
    }

    @Test
    fun `完全不是 JSON 时报失败`() {
        val attempt = TolerantJsonParser.parse("这株植物我觉得是紫薇。")
        assertTrue(attempt is TolerantJsonParser.ParseAttempt.Failed)
    }

    // ---------------------------------------------------------- 百科分析

    /** 与 PromptBuilder 里那份 schema 逐字对应 —— 两处任何一边改了，这条会红 */
    private val schemaKeys = listOf(
        "description",
        "morphological_features",
        "growth_habits",
        "flowering_period",
        "fruiting_period",
        "landscape_uses",
        "care_advice",
        "pest_control",
    )

    @Test
    fun `字段齐全时不报字段缺失`() {
        val json = schemaKeys.joinToString(prefix = "{", postfix = "}") { """"$it":"内容"""" }
        val parsed = TolerantJsonParser.parseAnalysis(json)
        assertTrue(parsed is TolerantJsonParser.AnalysisParseResult.Ok)
        // note 为空 = 字段数与 ANALYSIS_FIELD_COUNT 一致。
        // 这条断言的价值在于：往 schema 里加了字段却忘了改计数，这里会立刻红
        assertNull((parsed as TolerantJsonParser.AnalysisParseResult.Ok).note)
    }

    @Test
    fun `字段缺一半时给出 n 分之 m`() {
        val json = """{"description":"简介","flowering_period":"花期"}"""
        val parsed = TolerantJsonParser.parseAnalysis(json)
        assertTrue(parsed is TolerantJsonParser.AnalysisParseResult.Ok)
        val note = (parsed as TolerantJsonParser.AnalysisParseResult.Ok).note
        assertNotNull(note)
        assertTrue(note!!.contains("2/8"))
    }

    @Test
    fun `中文键名也能解析`() {
        val json = """{"简介":"这是简介","形态特征":"这是形态"}"""
        val parsed = TolerantJsonParser.parseAnalysis(json)
        assertTrue(parsed is TolerantJsonParser.AnalysisParseResult.Ok)
        val analysis = (parsed as TolerantJsonParser.AnalysisParseResult.Ok).analysis
        assertEquals("这是简介", analysis.description)
        assertEquals("这是形态", analysis.morphologicalFeatures)
    }

    @Test
    fun `全部字段为空视为失败`() {
        // 拿到一堆空字符串不算「分析成功」—— 界面上会显示一片空白卡片
        val json = schemaKeys.joinToString(prefix = "{", postfix = "}") { """"$it":"""" }
        assertTrue(
            TolerantJsonParser.parseAnalysis(json) is
                TolerantJsonParser.AnalysisParseResult.Failed,
        )
    }

    @Test
    fun `别名与病虫害能解析进来`() {
        val json = """{"common_names":"紫薇花、痒痒树","pest_control":"蚜虫用吡虫啉"}"""
        val parsed = TolerantJsonParser.parseAnalysis(json)
        val analysis = (parsed as TolerantJsonParser.AnalysisParseResult.Ok).analysis
        assertEquals("紫薇花、痒痒树", analysis.commonNames)
        assertEquals("蚜虫用吡虫啉", analysis.pestControl)
    }
}
