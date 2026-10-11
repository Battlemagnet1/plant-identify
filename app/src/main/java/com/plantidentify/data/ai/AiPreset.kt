package com.plantidentify.data.ai

/**
 * AI 服务预设（规格书第十节，2026-09-18 决策：首版预置 Qwen / 豆包 / OpenAI 三家）。
 *
 * ## 设计约束
 *
 * 预设**只作为表单初值**，不得硬编码进请求逻辑（见分析报告 Part 9.1 衍生要求 2）。
 * 用户改了 Base URL 或模型名之后，必须以用户填的为准；
 * 只有当用户主动切换预设时，才用预设值覆盖表单。
 *
 * ## 为什么同一家的视觉与文字模型分开写
 *
 * 视觉识别与文字分析是两个独立模型，规格书第十节要求分别可配。
 * 此处把两套默认值放在一起，但配置项彼此独立。
 */
enum class AiPreset(
    val displayName: String,
    /** 视觉识别（多图联合识别）默认 Base URL */
    val visionBaseUrl: String,
    /** 视觉模型默认名；空串表示必须由用户填写（各家命名差异大，写死容易过期） */
    val visionModel: String,
    /** 文字分析默认 Base URL */
    val textBaseUrl: String,
    /** 文字模型默认名；空串表示必须由用户填写 */
    val textModel: String,
    /** 模型名输入框里的提示语 */
    val modelHint: String,
) {
    /**
     * 阿里云百炼（DashScope）OpenAI 兼容模式。
     *
     * 注意：百炼已推出带 WorkspaceId 的新域名
     * （`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`），
     * 但官方声明旧域名 `dashscope.aliyuncs.com` **仍然完全可用**。
     * 这里默认用旧域名 —— 它不含用户专属的 WorkspaceId，通用性更好；
     * 需要新域名的用户可自行替换。
     *
     * 视觉模型：`qwen-vl-max` 是文档中稳定举例的通用名。更新一代的
     * `qwen3-vl-plus` 识别能力更强，但可用性因地域而异，如遇「模型不存在」
     * 可自行改为 `qwen3-vl-plus`。
     */
    QWEN(
        displayName = "Qwen（阿里云百炼）",
        visionBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        visionModel = "qwen-vl-max",
        textBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        textModel = "qwen-plus",
        modelHint = "如 qwen-vl-max、qwen-vl-plus、qwen3-vl-plus",
    ),

    /**
     * 字节跳动火山方舟（豆包）。
     *
     * Base URL 是固定值。**模型名必须由用户填写**：
     * 方舟支持两种写法 —— 控制台的预置模型 ID（如 `doubao-seed-2-0-lite-260428`）
     * 或自建的推理接入点 ID（`ep-` 开头）。这两种命名都带版本日期，
     * 预置一个写死的名字很快就会失效，让用户从控制台复制更可靠。
     */
    DOUBAO(
        displayName = "豆包（火山方舟）",
        visionBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        visionModel = "",
        textBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        textModel = "",
        modelHint = "填控制台的模型 ID（如 doubao-seed-2-0-lite-268428）或接入点 ID（ep- 开头）",
    ),

    /**
     * OpenAI 官方。
     *
     * `gpt-4o` 长期存在且稳定，作为默认值最不容易让用户一上手就失败；
     * 想用更新模型可在模型名栏自行替换。
     */
    OPENAI(
        displayName = "OpenAI",
        visionBaseUrl = "https://api.openai.com/v1",
        visionModel = "gpt-4o",
        textBaseUrl = "https://api.openai.com/v1",
        textModel = "gpt-4o-mini",
        modelHint = "如 gpt-4o、gpt-4.1",
    ),

    /**
     * 自定义 OpenAI Compatible 端点。
     *
     * 保留这个入口是硬要求（规格书第十节「必须支持用户自定义」）：
     * 国内可用的兼容服务很多，也可能用户自建（如本地推理服务），
     * 预置三家只是降低门槛，不能成为唯一通路。
     */
    CUSTOM(
        displayName = "自定义（OpenAI 兼容）",
        visionBaseUrl = "",
        visionModel = "",
        textBaseUrl = "",
        textModel = "",
        modelHint = "填入服务商提供的模型名",
    ),
    ;

    val needsManualModel: Boolean get() = visionModel.isEmpty()

    companion object {
        val DEFAULT: AiPreset = QWEN

        /** 按名称解析，未知名称回退到自定义（数据来自持久化，可能被手工改过） */
        fun fromName(name: String?): AiPreset =
            entries.firstOrNull { it.name == name } ?: CUSTOM
    }
}

/** 一套 OpenAI Compatible 端点配置（视觉或文字） */
data class AiEndpointConfig(
    /** 用户选择的预设。切换预设时用它判断是否需要套用新默认值 */
    val preset: AiPreset = AiPreset.DEFAULT,

    /** 实际使用的 Base URL —— 用户可覆盖预设值 */
    val baseUrl: String = "",

    /** 实际使用的模型名 —— 用户可覆盖预设值 */
    val model: String = "",

    /**
     * API Key 明文。
     *
     * **只在内存中短暂存在**：持久化时会被剥掉，密文另行存放
     * （见 [SecretCipher] 与 [AiSettingsStore]）。
     * 读取配置时由 Store 解密回填。
     */
    val apiKey: String = "",
) {
    /** 是否足以发起一次请求 */
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** 缺哪一项 —— 用于在 UI 上直接指出该补什么，而不是笼统地说「配置不完整」 */
    val missingFields: List<String>
        get() = buildList {
            if (baseUrl.isBlank()) add("Base URL")
            if (model.isBlank()) add("模型名")
            if (apiKey.isBlank()) add("API Key")
        }

    /**
     * Pl@ntNet 专用的可用性判据（v1.0.3）。
     *
     * ## 为什么不能复用 [isUsable]
     *
     * [isUsable] 要求 `baseUrl` + `model` + `apiKey` **三项**都非空，那是
     * OpenAI 兼容服务的形状。而 **Pl@ntNet 没有「模型名」这个概念** ——
     * [AiConfig.defaultPlantNet] 把 `model` 显式留成空串（`/v1/identify/all`
     * 里的 `all` 是**项目**，不是模型）。
     *
     * 于是套用 [isUsable] 时出现一颗必然触发的雷：用户把 Key 填对了，
     * `model` 依然是空的 → `isUsable == false` → 识别永远报
     * 「还没有配置 Pl@ntNet 的 API Key」，而 Key 其实就在库里。
     * 提示与事实相反，用户只能反复重填一个正确的 Key。
     *
     * Pl@ntNet 真正需要的只有两项：端点与 Key。
     */
    val isUsableAsPlantNet: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    /** Pl@ntNet 侧缺哪一项（口径同 [isUsableAsPlantNet]，不含模型名） */
    val missingPlantNetFields: List<String>
        get() = buildList {
            if (baseUrl.isBlank()) add("Base URL")
            if (apiKey.isBlank()) add("Pl@ntNet API Key")
        }

    /** 切换预设：套用新预设的默认值 */
    fun withPreset(preset: AiPreset, vision: Boolean): AiEndpointConfig = copy(
        preset = preset,
        baseUrl = if (vision) preset.visionBaseUrl else preset.textBaseUrl,
        model = if (vision) preset.visionModel else preset.textModel,
    )
}

/**
 * 完整的 AI 服务配置。
 *
 * [text] 为 null 表示「文字分析与视觉识别共用同一套配置」——
 * 对应设置页上的「使用同一个 AI 服务」开关。
 *
 * [promptStrategy] 单独作为一项配置，是为了支持分析报告 Part 3.4
 * 设计的 A/B 对照实验：用户可以拿自己的照片在两种 prompt 策略间切换实测，
 * 不必改代码重新打包。
 */
data class AiConfig(
    val vision: AiEndpointConfig = defaultVision(),
    val text: AiEndpointConfig? = null,
    val promptStrategy: PromptStrategy = PromptStrategy.DEFAULT,

    /**
     * Pl@ntNet 通道配置（v1.0.2 Phase 3 §十六）。
     *
     * 复用 [AiEndpointConfig]：`baseUrl`（默认 `https://my-api.plantnet.org/v2`）
     * 与 `apiKey` 有意义，`model`/`preset` 不用。null = 未配置。
     */
    val plantNet: AiEndpointConfig? = null,

    /** 识别走哪条通道（AI 视觉 / Pl@ntNet / 自动多源比对） */
    val recognitionChannel: RecognitionChannel = RecognitionChannel.VISION_AI,
) {
    val sharesOneEndpoint: Boolean get() = text == null

    /** 文字分析实际会用的配置（复用视觉配置时也走这里） */
    val effectiveText: AiEndpointConfig get() = text ?: vision

    val textPreset: AiPreset get() = effectiveText.preset

    /**
     * 当前通道**自身**是否配置完整（v1.0.3）。
     *
     * ## 为什么保存前要按通道分别校验
     *
     * 保存按钮曾经只看 `vision.isUsable`，于是选了 Pl@ntNet 而没配视觉 AI 时，
     * 按钮直接置灰 —— 用户填好了 Pl@ntNet 的 Key 却**根本点不动保存**，
     * 而且界面上的理由是「视觉识别配置不完整」，与他想做的事毫无关系。
     *
     * 校验必须跟着用户选的那条通道走：
     * - [RecognitionChannel.VISION_AI] → 视觉配置（Pl@ntNet 的 Key 可有可无）
     * - [RecognitionChannel.PLANT_NET] → Pl@ntNet 配置才算数
     * - [RecognitionChannel.AUTO] → 视觉是主源，**必须可用**；Pl@ntNet 是可选副源
     *   （未配时自动模式会安静降级为主源，见 `RecognitionExecutor`）
     */
    fun isUsableFor(channel: RecognitionChannel): Boolean = when (channel) {
        RecognitionChannel.VISION_AI -> vision.isUsable
        RecognitionChannel.PLANT_NET -> plantNet?.isUsableAsPlantNet == true
        RecognitionChannel.AUTO -> vision.isUsable
    }

    /**
     * 当前通道还缺什么。空列表表示可以保存。
     *
     * 文案里点明是**哪条通道**缺东西：笼统的「配置不完整」会让用户
     * 跑去补一条他压根没打算用的通道。
     */
    fun missingFor(channel: RecognitionChannel): List<String> = when (channel) {
        RecognitionChannel.VISION_AI ->
            vision.missingFields.map { "AI 视觉识别：$it" }

        RecognitionChannel.PLANT_NET -> when {
            plantNet == null -> listOf("Pl@ntNet：API Key")
            else -> plantNet.missingPlantNetFields.map { "Pl@ntNet：$it" }
        }

        RecognitionChannel.AUTO -> vision.missingFields.map { "AI 视觉识别（自动模式的主源）：$it" }
    }

    /** 当前通道是否已就绪（供设置页状态卡使用） */
    fun readinessOf(channel: RecognitionChannel): String = when (channel) {
        RecognitionChannel.VISION_AI ->
            if (vision.isUsable) "已就绪" else "不完整（缺 ${vision.missingFields.joinToString("、")}）"

        RecognitionChannel.PLANT_NET -> when {
            plantNet == null -> "未配置（缺 Pl@ntNet API Key）"
            plantNet.isUsableAsPlantNet -> "已就绪"
            else -> "不完整（缺 ${plantNet.missingPlantNetFields.joinToString("、")}）"
        }

        RecognitionChannel.AUTO -> if (vision.isUsable) {
            if (plantNet?.isUsableAsPlantNet == true) "已就绪（双源）" else "已就绪（仅视觉 AI，未配 Pl@ntNet 副源）"
        } else {
            "不完整（缺 ${vision.missingFields.joinToString("、")}）"
        }
    }

    companion object {
        fun defaultVision(): AiEndpointConfig = AiEndpointConfig(
            preset = AiPreset.DEFAULT,
            baseUrl = AiPreset.DEFAULT.visionBaseUrl,
            model = AiPreset.DEFAULT.visionModel,
        )

        fun defaultText(): AiEndpointConfig = AiEndpointConfig(
            preset = AiPreset.DEFAULT,
            baseUrl = AiPreset.DEFAULT.textBaseUrl,
            model = AiPreset.DEFAULT.textModel,
        )

        /**
         * Pl@ntNet 的默认端点（它不是 OpenAI 兼容服务，preset 无意义）。
         *
         * ## ⚠️ 域名是 `my-api.plantnet.org` 且版本是 `/v2`（v1.0.3 修正）
         *
         * 这里曾经写的是 `https://my.plantnet.org`，而 `PlantNetProvider`
         * 拼的是 `/v1/identify/{project}` —— **两个都错**：
         *
         * | | 错的（旧） | 对的（官方文档） |
         * |---|---|---|
         * | 域名 | `my.plantnet.org` | `my-api.plantnet.org` |
         * | 版本 | `/v1/` | `/v2/` |
         *
         * `my.plantnet.org` 是**网站**不是 API 主机，`/v1` 也早已下线 ——
         * 于是每次识别都返回 404「Not found」，与 API Key 对不对**毫无关系**。
         * 实测：`my.plantnet.org/v1/…` 恒返回
         * `{"statusCode":404,"message":"Server Error"}`，而
         * `my-api.plantnet.org/v2/…` 在不带图时返回
         * `{"statusCode":400,"message":"\"images\" is required"}`（说明路由正确）。
         *
         * 参考：https://my.plantnet.org/doc/api/identify
         */
        fun defaultPlantNet(): AiEndpointConfig = AiEndpointConfig(
            preset = AiPreset.DEFAULT,
            baseUrl = "https://my-api.plantnet.org/v2",
            model = "",
        )

        val EMPTY = AiConfig()
    }
}

/**
 * 识别通道选择（需求 §十六）。
 *
 * **AUTO 不是「谁的置信度高用谁」**：两个源的 confidence 语义不同
 * （一个是语言模型的自估、一个是分类器的后验），相加或平均都是无意义数字 ——
 * 需求特意点了这条。AUTO 的比较只产出**定性结论**（一致 / 不一致），
 * 不一致时把双方摆出来要求用户确认。
 */
enum class RecognitionChannel(val label: String) {
    VISION_AI("AI 视觉识别"),
    PLANT_NET("Pl@ntNet"),
    AUTO("自动（双源比对）"),
    ;

    companion object {
        fun fromName(name: String?): RecognitionChannel =
            entries.firstOrNull { it.name == name } ?: VISION_AI
    }
}
