package com.plantidentify.data.plantnet

import com.plantidentify.data.local.entity.ImageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 部位标注 → Pl@ntNet `organs` 取值的映射（v1.0.2.1018）。
 *
 * ## 钉的是什么
 *
 * Pl@ntNet 的 `organs` **只接受五个值**：
 * `leaf` / `flower` / `fruit` / `bark` / `auto`
 * （见 https://my.plantnet.org/doc/api/identify）
 *
 * 而本项目原来发的是：
 * - `WHOLE_PLANT` / `HABITAT` → `"habit"` —— **非法值**
 * - 其它（含**最常出现的** `UNKNOWN`「未标注」）→ `"other"` —— **非法值**
 *
 * 用户拍完照通常不标部位，于是绝大多数请求都在发 `"other"`。
 * 分类器是拿部位去挑特征的：部位错了会「认得出大类、认错近缘种」，
 * 表现就是「识别不准」。未标注时应该发 `auto`，让模型自己判断。
 */
class OrganMappingTest {

    /** 合法值集合，照官方文档抄的 */
    private val valid = setOf("leaf", "flower", "fruit", "bark", "auto")

    @Test
    fun `有明确部位时用对应值`() {
        assertEquals("leaf", PlantNetProvider.organOf(ImageRole.LEAF))
        assertEquals("flower", PlantNetProvider.organOf(ImageRole.FLOWER))
        assertEquals("fruit", PlantNetProvider.organOf(ImageRole.FRUIT))
        assertEquals("bark", PlantNetProvider.organOf(ImageRole.BARK))
    }

    /**
     * **未标注必须发 auto** —— 这是最常走的一条路径。
     *
     * 曾经发 `"other"`，而它不是合法值。
     */
    @Test
    fun `未标注发 auto 而不是 other`() {
        assertEquals("auto", PlantNetProvider.organOf(ImageRole.UNKNOWN))
    }

    /** 整株/生境没有对应部位，也交给模型判断 */
    @Test
    fun `整株与生境发 auto`() {
        assertEquals("auto", PlantNetProvider.organOf(ImageRole.WHOLE_PLANT))
        assertEquals("auto", PlantNetProvider.organOf(ImageRole.HABITAT))
    }

    /**
     * **兜底：任何 role 都不能产出非法值。**
     *
     * 这条用枚举全量遍历，将来往 `ImageRole` 里加新值也能自动覆盖 ——
     * 加一个 role 却忘了映射时，这里会立刻失败，而不是悄悄发出 `"other"`。
     */
    @Test
    fun `所有 role 都映射到合法值`() {
        ImageRole.entries.forEach { role ->
            val organ = PlantNetProvider.organOf(role)
            assertEquals("role=$role 映射出了非法 organs 值", true, organ in valid)
            assertFalse("role=$role 不该产出 other", organ == "other")
            assertFalse("role=$role 不该产出 habit", organ == "habit")
        }
    }
}
