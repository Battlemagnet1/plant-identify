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

    // ---------------- 正式名 / 俗称 分离 ----------------

    @Test
    fun `识别 prompt 把 name 定义成正式中文名称`() {
        val prompt = PromptBuilder.buildRecognitionPrompt(roles = List(1) { ImageRole.LEAF })
        assertTrue(
            "schema 里应写「正式中文名称」而不是含糊的「中文名称」",
            prompt.contains("\"name\": \"正式中文名称\""),
        )
    }

    @Test
    fun `识别 prompt 明确禁止用俗称与商品名`() {
        // 模型的默认倾向是「用最常见的叫法」，而最常见的往往是俗称。
        // 只写「正式中文名」四个字不够，得给反例它才知道边界在哪
        val prompt = PromptBuilder.buildRecognitionPrompt(roles = List(1) { ImageRole.LEAF })

        assertTrue("应有一段专门讲 name 怎么写", prompt.contains("关于 name 的写法"))
        assertTrue("应点名俗称不属于正式名", prompt.contains("俗称"))
        assertTrue("应给出具体反例，否则模型不知道边界", prompt.contains("法国梧桐"))
        assertTrue("应给出对应的正式名", prompt.contains("悬铃木"))
    }

    @Test
    fun `分析 prompt 不让模型把正式名再写进俗称栏`() {
        // 「紫薇、紫薇」这种重复会让用户以为程序出了问题
        val prompt = analysisPrompt()
        assertTrue(
            "应禁止把正式中文名本身写进 common_names",
            prompt.contains("也不要把这株植物的正式中文名本身再写一遍"),
        )
    }

    // ---------------- 地点弱先验 ----------------

    @Test
    fun `不给地点时 prompt 里不出现地点节`() {
        // 写「地点：未知」反而会让模型自己脑补一个环境，
        // 所以约定是**整节不出现**
        val prompt = PromptBuilder.buildRecognitionPrompt(roles = List(1) { ImageRole.LEAF })
        assertFalse(prompt.contains("## 拍摄地点"))
    }

    @Test
    fun `给了地点才出现地点节并带上地名`() {
        val prompt = PromptBuilder.buildRecognitionPrompt(
            roles = List(1) { ImageRole.LEAF },
            placeHint = "杭州市西湖区·北山街",
        )
        assertTrue(prompt.contains("## 拍摄地点"))
        assertTrue(prompt.contains("杭州市西湖区·北山街"))
    }

    @Test
    fun `地点被限定为弱先验而不是鉴定依据`() {
        // 这段措辞是防「地点绑死识别」的唯一保障：
        // 一旦模型把「拍摄地在杭州」当成「只可能是杭州的树种」，
        // 引种栽培或室内养护的植株就永远识别不对
        val prompt = PromptBuilder.buildRecognitionPrompt(
            roles = List(1) { ImageRole.LEAF },
            placeHint = "杭州市西湖区·北山街",
        )
        assertTrue(prompt.contains("不是鉴定依据"))
        assertTrue("必须明说冲突时以形态特征为准", prompt.contains("以形态特征为准"))
        assertTrue("必须禁止把地点写进 evidence（依据栏混进地点就没法复核了）",
            prompt.contains("把地点写进 evidence"))
    }

    @Test
    fun `分析 prompt 同样接受地点且同样按弱先验处理`() {
        val prompt = PromptBuilder.buildAnalysisPrompt(
            name = "紫薇",
            latinName = "Lagerstroemia indica",
            family = "千屈菜科",
            genus = "紫薇属",
            category = "落叶灌木",
            confidence = 0.92,
            evidence = emptyList(),
            placeHint = "杭州市西湖区·北山街",
        )
        assertTrue(prompt.contains("## 拍摄地点"))
        assertTrue(prompt.contains("不是鉴定依据"))
    }

    @Test
    fun `空白地点等同于没有地点`() {
        val prompt = PromptBuilder.buildRecognitionPrompt(
            roles = List(1) { ImageRole.LEAF },
            placeHint = "   ",
        )
        assertFalse("只有空白的字符串不该生成地点节", prompt.contains("## 拍摄地点"))
    }
}
