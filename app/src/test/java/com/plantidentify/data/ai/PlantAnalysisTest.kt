package com.plantidentify.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantAnalysisTest {

    @Test
    fun `全空视为失败而不是成功的空结果`() {
        assertTrue(PlantAnalysis().isEmpty)
        // 只有空白字符同样算空 —— 模型返回 " " 很常见
        assertTrue(PlantAnalysis(description = "   ").isEmpty)
    }

    @Test
    fun `有一个字段就不算空`() {
        assertFalse(PlantAnalysis(description = "简介").isEmpty)
    }

    @Test
    fun `presentFields 只收录非空字段`() {
        val analysis = PlantAnalysis(description = "简介", careAdvice = "养护")
        assertEquals(2, analysis.presentFields.size)
        assertEquals("植物简介" to "简介", analysis.presentFields[0])
        assertEquals("养护建议" to "养护", analysis.presentFields[1])
    }

    @Test
    fun `presentFields 的字段数与解析器认定的字段数一致`() {
        // 这两个数字不符时，界面会显示「模型只返回了 n/8 个字段」，
        // 而实际上字段是齐的 —— 用户会以为 AI 没做完
        val all = PlantAnalysis(
            description = "a",
            morphologicalFeatures = "b",
            growthHabits = "c",
            floweringPeriod = "d",
            fruitingPeriod = "e",
            landscapeUses = "f",
            careAdvice = "g",
            pestControl = "h",
        )
        assertEquals(8, all.presentFields.size)
    }

    @Test
    fun `别名与病虫害有独立标签`() {
        val analysis = PlantAnalysis(commonNames = "紫薇花", pestControl = "蚜虫")
        val labels = analysis.presentFields.map { it.first }
        assertTrue(labels.contains("病虫害防治"))
        // 别名不参与 presentFields（界面上它显示在标题区），但值必须留着
        assertEquals("紫薇花", analysis.commonNames)
    }
}
