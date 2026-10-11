package com.plantidentify.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别通道的配置可用性判据（v1.0.3 修 Pl@ntNet 不可用）。
 *
 * ## 这组用例钉的是「提示与事实相反」那一类缺陷
 *
 * 用户报告：填入 Pl@ntNet 的 Key 后依然报「还没有配置 Pl@ntNet 的 API Key」，
 * 而且保存按钮一直是灰的。根因有两条，都不是崩溃、都不会在编译期报错：
 *
 * 1. **`isUsable` 套在了 Pl@ntNet 上** —— 它要求「模型名」非空，
 *    而 Pl@ntNet 没有模型名的概念（`defaultPlantNet()` 的 `model` 就是空串）。
 *    于是 Key 填对了也判为不可用。
 * 2. **保存门控只看视觉配置** —— 选了 Pl@ntNet 却因为视觉侧没填 Key 而按钮置灰。
 *
 * 这两条如果只靠手工点界面验证，回归时极容易漏（它们不报错，
 * 只表现为「填了没用」）。所以在这里用单测把语义钉死。
 */
class RecognitionChannelConfigTest {

    private val visionReady = AiEndpointConfig(
        baseUrl = "https://example.com/v1",
        model = "some-vl-model",
        apiKey = "sk-vision",
    )

    private val visionIncomplete = AiEndpointConfig(
        baseUrl = "https://example.com/v1",
        model = "",
        apiKey = "",
    )

    /** Pl@ntNet 配置：只有 baseUrl 与 apiKey，**model 刻意留空**（它没有模型名） */
    private fun plantNet(apiKey: String, baseUrl: String = "https://my.plantnet.org") =
        AiConfig.defaultPlantNet().copy(baseUrl = baseUrl, apiKey = apiKey)

    // ---------------------------------------------------------- 判据本身

    /**
     * 核心回归：Pl@ntNet 的 Key 填了就算可用 —— **模型名为空不影响**。
     *
     * 这条一旦失败，用户就会看到「还没有配置 Pl@ntNet 的 API Key」，
     * 而他手里那个 Key 是完全正确的。
     */
    @Test
    fun `plantNet 填了 Key 即可用 即使模型名为空`() {
        val config = plantNet("pn-key-123")

        // 前提：model 确实是空的 —— 这正是 isUsable 会误判的原因
        assertEquals("", config.model)

        assertFalse("套用 OpenAI 口径的 isUsable 必然为 false（模型名空）", config.isUsable)
        assertTrue("Pl@ntNet 自己的口径只看 baseUrl + Key", config.isUsableAsPlantNet)
    }

    @Test
    fun `plantNet 缺 Key 或端点都算不可用`() {
        assertFalse(plantNet("").isUsableAsPlantNet)
        assertFalse(plantNet("pn-key", baseUrl = "").isUsableAsPlantNet)
        assertFalse(AiConfig.defaultPlantNet().isUsableAsPlantNet)
    }

    /** 缺项提示里**不该出现「模型名」** —— 那会让用户去找一个不存在的东西 */
    @Test
    fun `plantNet 缺项提示不含模型名`() {
        val empty = AiConfig.defaultPlantNet()
        assertEquals(listOf("Pl@ntNet API Key"), empty.missingPlantNetFields)

        val noUrl = plantNet("k", baseUrl = "")
        assertEquals(listOf("Base URL"), noUrl.missingPlantNetFields)
    }

    // ---------------------------------------------------------- 按通道校验

    /**
     * 用户报告的「无法保存」：视觉配置不完整，但通道选了 Pl@ntNet。
     *
     * 只要 Pl@ntNet 的 Key 填好，就**必须**允许保存 —— 不该被视觉侧的
     * 缺失拖住。
     */
    @Test
    fun `选了 Pl@ntNet 时 视觉配置不完整也不阻止保存`() {
        val config = AiConfig(
            vision = visionIncomplete,
            plantNet = plantNet("pn-key-123"),
            recognitionChannel = RecognitionChannel.PLANT_NET,
        )

        assertTrue(
            "Pl@ntNet 通道只应由 Pl@ntNet 自己的配置决定可用性",
            config.isUsableFor(RecognitionChannel.PLANT_NET),
        )
        assertTrue(config.missingFor(RecognitionChannel.PLANT_NET).isEmpty())
    }

    /** 反向：选了 Pl@ntNet 但 Key 没填 → 不可保存，且提示点明缺的是 Pl@ntNet Key */
    @Test
    fun `选了 Pl@ntNet 但没填 Key 时 提示指明缺 Pl@ntNet Key`() {
        val config = AiConfig(
            vision = visionReady,
            plantNet = null,
            recognitionChannel = RecognitionChannel.PLANT_NET,
        )

        assertFalse(config.isUsableFor(RecognitionChannel.PLANT_NET))
        assertEquals(listOf("Pl@ntNet：API Key"), config.missingFor(RecognitionChannel.PLANT_NET))
    }

    /**
     * 视觉通道不受 Pl@ntNet 影响：没填 Pl@ntNet 的 Key 也该能保存。
     *
     * 这条防的是「反向耦合」——修完一个方向后把另一个方向弄坏。
     */
    @Test
    fun `选了视觉 AI 时 Pl@ntNet 未配置不影响保存`() {
        val config = AiConfig(
            vision = visionReady,
            plantNet = null,
            recognitionChannel = RecognitionChannel.VISION_AI,
        )

        assertTrue(config.isUsableFor(RecognitionChannel.VISION_AI))
        assertTrue(config.missingFor(RecognitionChannel.VISION_AI).isEmpty())
    }

    /**
     * 自动模式以视觉为主源：视觉不可用就不能保存；
     * 而 **Pl@ntNet 副源没配不影响** —— 运行时会在没有副源时安静降级。
     */
    @Test
    fun `自动模式要求视觉可用 但 Pl@ntNet 副源可选`() {
        val noSubSource = AiConfig(
            vision = visionReady,
            plantNet = null,
            recognitionChannel = RecognitionChannel.AUTO,
        )
        assertTrue("副源可选，不该因为没配 Pl@ntNet 就拦住自动模式", noSubSource.isUsableFor(RecognitionChannel.AUTO))

        val noMainSource = AiConfig(
            vision = visionIncomplete,
            plantNet = plantNet("pn-key"),
            recognitionChannel = RecognitionChannel.AUTO,
        )
        assertFalse("视觉是主源，它不可用时自动模式不该放行", noMainSource.isUsableFor(RecognitionChannel.AUTO))
    }

    // ---------------------------------------------------------- 状态文案

    @Test
    fun `状态文案按通道区分 不把 Pl@ntNet 说成视觉配置`() {
        val config = AiConfig(
            vision = visionIncomplete,
            plantNet = plantNet("pn-key-123"),
            recognitionChannel = RecognitionChannel.PLANT_NET,
        )

        // Pl@ntNet 已就绪时不该提示「不完整」
        assertEquals("已就绪", config.readinessOf(RecognitionChannel.PLANT_NET))

        // 而同一份配置在视觉通道下确实是不完整的 —— 两者口径独立
        assertTrue(config.readinessOf(RecognitionChannel.VISION_AI).startsWith("不完整"))
    }

    @Test
    fun `未配置 Pl@ntNet 时的文案说明缺 Key 而不是笼统的不完整`() {
        val config = AiConfig(vision = visionReady, plantNet = null)
        assertEquals("未配置（缺 Pl@ntNet API Key）", config.readinessOf(RecognitionChannel.PLANT_NET))
    }

    /**
     * 自动模式的文案要说明「副源可选」，否则用户会以为还差一步配不齐。
     */
    @Test
    fun `自动模式文案说明副源可选`() {
        val onlyMain = AiConfig(vision = visionReady, plantNet = null)
        assertTrue(
            onlyMain.readinessOf(RecognitionChannel.AUTO).contains("仅视觉 AI"),
        )

        val bothSources = AiConfig(vision = visionReady, plantNet = plantNet("pn-key"))
        assertTrue(bothSources.readinessOf(RecognitionChannel.AUTO).contains("双源"))
    }

    // ---------------------------------------------------------- 配置往返

    /**
     * 通道枚举持久化用的是 `name()`（见 `AiSettingsStore`），
     * 解析必须稳定 —— 认不出时回退视觉 AI 而不是抛异常。
     */
    @Test
    fun `通道名解析稳定且回退到视觉 AI`() {
        RecognitionChannel.entries.forEach { channel ->
            assertEquals(channel, RecognitionChannel.fromName(channel.name))
        }
        assertEquals(RecognitionChannel.VISION_AI, RecognitionChannel.fromName(null))
        assertEquals(RecognitionChannel.VISION_AI, RecognitionChannel.fromName(""))
        assertEquals(RecognitionChannel.VISION_AI, RecognitionChannel.fromName("NOT_A_CHANNEL"))
    }

    // ---------------------------------------------------------- 端点

    /**
     * 默认端点必须是 `my-api.plantnet.org/v2`。
     *
     * ## 这条为什么必须钉死
     *
     * 旧代码写的是 `https://my.plantnet.org`，且 Provider 还额外拼了
     * `/v1/identify` —— **域名与版本两个都错**。后果是识别恒返回 404，
     * 而且**与 API Key 对不对毫无关系**：用户会一直以为是自己的 Key
     * 有问题，反复去申请、重填，永远修不好。
     *
     * 实测对照（同一个 Key）：
     * - `my.plantnet.org/v1/identify/all` → 404 `"Server Error"`
     * - `my-api.plantnet.org/v2/identify/all`（不带图）→ 400 `"images" is required`
     *
     * 后者才说明「路由是对的」。这条断言把域名与版本一起锁住，
     * 防止有人「顺手」改回看起来更眼熟的 my.plantnet.org。
     */
    @Test
    fun `默认 Pl@ntNet 端点是 my-api 且 v2`() {
        val baseUrl = AiConfig.defaultPlantNet().baseUrl

        assertTrue("域名必须是 my-api.plantnet.org，实际=$baseUrl", baseUrl.contains("my-api.plantnet.org"))
        assertTrue("版本必须是 /v2，实际=$baseUrl", baseUrl.endsWith("/v2"))
        // 拼出来的识别 URL 必须是 /v2/identify/all
        assertEquals(
            "https://my-api.plantnet.org/v2/identify/all",
            "${baseUrl.trimEnd('/')}/identify/all",
        )
    }

    /** 旧的坏端点必须能被识别出来（`AiSettingsStore` 据此自动迁移） */
    @Test
    fun `旧端点可被识别为需要迁移`() {
        // 这里只钉「默认值已经是对的」，迁移逻辑本身在 AiSettingsStore 内部，
        // 由 verify_plantnet_fix.py 在真机上验证落盘结果。
        val legacy = "https://my.plantnet.org"
        assertFalse(
            "旧端点绝不能再出现在默认值里",
            AiConfig.defaultPlantNet().baseUrl == legacy,
        )
    }
}
