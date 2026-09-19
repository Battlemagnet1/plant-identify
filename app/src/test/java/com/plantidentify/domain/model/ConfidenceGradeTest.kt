package com.plantidentify.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 置信度分级同时被识别结果页与植物档案页使用。
 * 两处若各自定阈值，用户会在两个页面上看到同一个 91% 却是不同的星级。
 */
class ConfidenceGradeTest {

    @Test
    fun `星级按区间映射`() {
        assertEquals(5, ConfidenceGrade.stars(0.90))
        assertEquals(4, ConfidenceGrade.stars(0.80))
        assertEquals(3, ConfidenceGrade.stars(0.70))
        assertEquals(2, ConfidenceGrade.stars(0.50))
        assertEquals(1, ConfidenceGrade.stars(0.49))
    }

    @Test
    fun `边界值归入高一档`() {
        // 阈值按「大于等于」判定，写反了就会出现 90% 只有四星这种怪事
        assertEquals(5, ConfidenceGrade.stars(1.0))
        assertEquals(4, ConfidenceGrade.stars(0.8999))
    }

    @Test
    fun `文字描述与星级一一对应`() {
        assertEquals("优秀", ConfidenceGrade.label(0.95))
        assertEquals("良好", ConfidenceGrade.label(0.85))
        assertEquals("一般", ConfidenceGrade.label(0.75))
        assertEquals("较低", ConfidenceGrade.label(0.55))
        assertEquals("很低", ConfidenceGrade.label(0.10))
    }

    @Test
    fun `渲染出来永远是五个字符`() {
        // 长度不对会让卡片宽度随置信度变化，一行卡片参差不齐
        listOf(0.0, 0.5, 0.75, 0.85, 1.0).forEach { c ->
            val rendered = ConfidenceGrade.render(c)
            assertEquals(5, rendered.length)
            assertEquals(ConfidenceGrade.stars(c), rendered.count { it == '★' })
        }
    }
}
