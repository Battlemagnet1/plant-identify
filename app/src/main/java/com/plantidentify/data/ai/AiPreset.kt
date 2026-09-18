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
) {
    val sharesOneEndpoint: Boolean get() = text == null

    /** 文字分析实际会用的配置（复用视觉配置时也走这里） */
    val effectiveText: AiEndpointConfig get() = text ?: vision

    val textPreset: AiPreset get() = effectiveText.preset

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

        val EMPTY = AiConfig()
    }
}
