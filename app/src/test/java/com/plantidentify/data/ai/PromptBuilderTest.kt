package com.plantidentify.data.ai

import com.plantidentify.data.local.entity.ImageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * prompt 与解析器是**成对**的：prompt 里要求模型输出哪些键，
 * 解析器就得认识哪些键。只改一边不会报错，只会让字段静默变成空值 ——
 * 这类问题在真机上表现为「AI 没给出这个信息」，很难联想到是 prompt 的问题。
 */
class PromptBuilderTest {

    private fun analysisPrompt() = PromptBuilder.buildAnalysisPrompt(
        name = "紫薇",
        latinName = "Lagerstroemia indica",
        family = "千屈菜科",
        genus = "紫薇属",
        category = "落叶灌木",
        confidence = 0.92,
        evidence = listOf("花：圆锥花序顶生"),
    )

    @Test
    fun `分析 prompt 要求了全部八个字段`() {
        val prompt = analysisPrompt()
        val keys = listOf(
            "description",
            "morphological_features",
            "growth_habits",
            "flowering_period",
            "fruiting_period",
            "landscape_uses",
            "care_advice",
            "pest_control",
        )
        keys.forEach { key ->
            assertTrue("prompt 里没有要求字段 $key", prompt.contains("\"$key\""))
        }
    }

    @Test
    fun `分析 prompt 带上了已知的识别结论`() {
        val prompt = analysisPrompt()
        assertTrue(prompt.contains("紫薇"))
        assertTrue(prompt.contains("千屈菜科"))
    }

    @Test
    fun `不确定的内容要求留空而不是编造`() {
        val prompt = analysisPrompt()
        assertTrue("应当明确要求模型不要编造", prompt.contains("不要编造"))
    }

    @Test
    fun `标注了部位时给每张照片编号`() {
        val prompt = PromptBuilder.buildRecognitionPrompt(
            roles = listOf(
                ImageRole.WHOLE_PLANT,
                ImageRole.LEAF,
                ImageRole.FLOWER,
            ),
        )
        // 写法是「照片 N」，编号与文字之间有一个空格
        assertTrue(prompt.contains("照片 1"))
        assertTrue(prompt.contains("照片 2"))
        assertTrue(prompt.contains("照片 3"))
        // 部位名称也要带上 —— 只给编号不给部位等于没标。
        // 这里写死中文字面量而不是引用 ImageRole.label：
        // prompt 里用的是一份**文件私有**的标签映射，与界面上那份是两个声明，
        // 测试要钉的正是「prompt 里到底印了什么字」
        assertTrue(prompt.contains("叶片"))
    }

    @Test
    fun `一张都没标注时不画蛇添足`() {
        // 全 UNKNOWN 等价于 A 组（多图原样）。此时再列一遍
        // 「照片 N：未标注部位，请自行判断」纯属噪音，
        // 反而提醒模型去猜部位 —— 与不做标注的意图相反
        val prompt = PromptBuilder.buildRecognitionPrompt(
            roles = List(3) { ImageRole.UNKNOWN },
        )
        assertFalse(prompt.contains("## 每张照片的拍摄部位"))
    }

    @Test
    fun `识别 prompt 里照片数量与传入张数一致`() {
        val prompt = PromptBuilder.buildRecognitionPrompt(
            roles = List(3) { ImageRole.UNKNOWN },
        )
        assertTrue(prompt.contains("3 张照片"))
    }
}
