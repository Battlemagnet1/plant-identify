package com.plantidentify.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.ai.AiConfig
import com.plantidentify.data.ai.AiEndpointConfig
import com.plantidentify.data.ai.AiPreset
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.ConnectivityRequest
import com.plantidentify.data.ai.ConnectivityResult
import com.plantidentify.data.ai.PromptStrategy
import com.plantidentify.data.ai.RecognitionChannel
import com.plantidentify.data.ai.TextProvider
import com.plantidentify.data.ai.VisionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel。
 *
 * ## 表单即编辑态
 *
 * 表单初始值来自磁盘上的配置；用户修改后只有点「保存」才写回。
 * 这样改到一半退出不会把半成品配置落盘 —— 否则应用会处于
 * 「配置看起来完整但其实是错的」状态，下次识别报一堆莫名其妙的错。
 *
 * ## 两条通道
 *
 * 视觉识别与文字分析是**两个独立的模型**，规格书第十节要求分别可配。
 * 但多数用户只会用一家服务，所以默认勾选「使用同一个 AI 服务」，
 * 此时文字侧复用视觉配置（[AiConfig.text] 为 null），只需填一次。
 *
 * ## API Key 的处理
 *
 * 从磁盘读出的 Key 会解密后回填到表单，但**输入框默认以密码形式显示**。
 * 用户可以点「显示」临时查看。这比「留空表示不修改」更不容易出岔子 ——
 * 后者在用户只想改模型名时会不小心把 Key 清掉。
 */
class SettingsViewModel(
    private val store: AiSettingsStore,
    private val visionProvider: VisionProvider,
    private val textProvider: TextProvider,
) : ViewModel() {

    // ---------------- 视觉通道 ----------------

    /** 表单中正在编辑的视觉配置 */
    private val _form = MutableStateFlow(AiConfig.defaultVision())
    val form: StateFlow<AiEndpointConfig> = _form.asStateFlow()

    /** 是否在表单里显示 API Key 明文（默认隐藏） */
    private val _apiKeyVisible = MutableStateFlow(false)
    val apiKeyVisible: StateFlow<Boolean> = _apiKeyVisible.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    /** 视觉侧测试连接的结论 */
    private val _testOutcome = MutableStateFlow<TestOutcome?>(null)
    val testOutcome: StateFlow<TestOutcome?> = _testOutcome.asStateFlow()

    // ---------------- 文字通道 ----------------

    /** 文字分析是否复用视觉配置（对应设置页的「使用同一个 AI 服务」） */
    private val _sharesOneEndpoint = MutableStateFlow(true)
    val sharesOneEndpoint: StateFlow<Boolean> = _sharesOneEndpoint.asStateFlow()

    /** 表单中正在编辑的文字配置 */
    private val _textForm = MutableStateFlow(AiConfig.defaultText())
    val textForm: StateFlow<AiEndpointConfig> = _textForm.asStateFlow()

    private val _textApiKeyVisible = MutableStateFlow(false)
    val textApiKeyVisible: StateFlow<Boolean> = _textApiKeyVisible.asStateFlow()

    private val _testingText = MutableStateFlow(false)
    val testingText: StateFlow<Boolean> = _testingText.asStateFlow()

    private val _textTestOutcome = MutableStateFlow<TestOutcome?>(null)
    val textTestOutcome: StateFlow<TestOutcome?> = _textTestOutcome.asStateFlow()

    // ---------------- 共用 ----------------

    /**
     * 识别 prompt 策略。
     *
     * 开放给用户切换，是为了让分析报告 Part 3.4 的 A/B 对照实验
     * 可以直接用真实照片在 App 里跑，而不必另写一套实验脚本。
     */
    private val _strategy = MutableStateFlow(PromptStrategy.DEFAULT)
    val strategy: StateFlow<PromptStrategy> = _strategy.asStateFlow()

    // 识别通道与 Pl@ntNet（v1.0.2 Phase 3 §十六）
    private val _channel = MutableStateFlow(RecognitionChannel.VISION_AI)
    val channel: StateFlow<RecognitionChannel> = _channel.asStateFlow()

    private val _plantNetApiKey = MutableStateFlow("")
    val plantNetApiKey: StateFlow<String> = _plantNetApiKey.asStateFlow()

    /**
     * Pl@ntNet 的 Base URL（可编辑，v1.0.3）。
     *
     * 默认是官方 v2 端点。允许改是必要的：旧版本的默认值
     * （`my.plantnet.org` + `/v1`）是错的，升级用户的本地配置里可能
     * 还存着坏值；走镜像 / 自建代理的用户也需要指到别处。
     */
    private val _plantNetBaseUrl = MutableStateFlow(AiConfig.defaultPlantNet().baseUrl)
    val plantNetBaseUrl: StateFlow<String> = _plantNetBaseUrl.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** 一次性提示（保存成功等） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 是否存在已保存的 API Key（用于显示状态文案，不回显明文） */
    private val _hasSavedKey = MutableStateFlow(false)
    val hasSavedKey: StateFlow<Boolean> = _hasSavedKey.asStateFlow()

    /**
     * 当前通道能否保存（驱动保存按钮的 `enabled`）。
     *
     * 做成 StateFlow 而不是普通函数：Compose 只会因**状态变化**重组，
     * 普通函数读的是 `_form.value` 这类非状态快照，用户改完表单后
     * 按钮的亮灭不会跟着变 —— 会出现「填好了但按钮还是灰的，得切个页面回来」。
     *
     * 输入只订阅真正影响结论的四项：视觉表单、通道、Pl@ntNet Key、
     * 「使用同一个 AI 服务」开关（它决定 text 是否为 null）。文字表单的
     * 字段不参与判定（文字配置不完整不阻止保存），所以不订阅 ——
     * 订阅了只会让按钮无谓地闪。
     */
    val canSave: StateFlow<Boolean> = combine(
        _form, _channel, _plantNetApiKey, _sharesOneEndpoint, _plantNetBaseUrl,
    ) { vision, channel, pnKey, share, pnBaseUrl ->
        AiConfig(
            vision = vision,
            text = if (share) null else _textForm.value,
            plantNet = if (pnKey.trim().isEmpty()) {
                null
            } else {
                AiConfig.defaultPlantNet().copy(
                    baseUrl = pnBaseUrl.trim().ifEmpty { AiConfig.defaultPlantNet().baseUrl },
                    apiKey = pnKey.trim(),
                )
            },
            recognitionChannel = channel,
        ).isUsableFor(channel)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 当前通道还缺什么（空 = 可保存）。状态卡与失败提示共用同一口径。 */
    val missingForChannel: StateFlow<List<String>> = combine(
        _form, _channel, _plantNetApiKey, _plantNetBaseUrl,
    ) { vision, channel, pnKey, pnBaseUrl ->
        AiConfig(
            vision = vision,
            plantNet = if (pnKey.trim().isEmpty()) {
                null
            } else {
                AiConfig.defaultPlantNet().copy(
                    baseUrl = pnBaseUrl.trim().ifEmpty { AiConfig.defaultPlantNet().baseUrl },
                    apiKey = pnKey.trim(),
                )
            },
            recognitionChannel = channel,
        ).missingFor(channel)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val config = store.config.first()
            _form.value = config.vision
            _textForm.value = config.text ?: AiConfig.defaultText()
            _sharesOneEndpoint.value = config.sharesOneEndpoint
            _strategy.value = config.promptStrategy
            _channel.value = config.recognitionChannel
            _plantNetApiKey.value = config.plantNet?.apiKey.orEmpty()
            // 已保存的端点回填；没存过就用官方默认 v2
            _plantNetBaseUrl.value = config.plantNet?.baseUrl
                ?.takeIf { it.isNotBlank() }
                ?: AiConfig.defaultPlantNet().baseUrl
            _hasSavedKey.value = config.vision.apiKey.isNotEmpty()
        }
    }

    // ---------------- 视觉表单编辑 ----------------

    fun selectPreset(preset: AiPreset) {
        // 切换预设时套用该预设的默认 Base URL 与模型名；
        // 用户填的 API Key 保留 —— 同一家的 Key 通常不需要重填
        _form.value = _form.value.withPreset(preset, vision = true)
        _testOutcome.value = null
    }

    fun updateBaseUrl(value: String) {
        _form.value = _form.value.copy(baseUrl = value.trim())
        _testOutcome.value = null
    }

    fun updateModel(value: String) {
        _form.value = _form.value.copy(model = value.trim())
        _testOutcome.value = null
    }

    fun updateApiKey(value: String) {
        // 只去掉首尾空白：Key 内部可能有连字符，不能做任何清洗
        _form.value = _form.value.copy(apiKey = value.trim())
        _testOutcome.value = null
    }

    fun toggleApiKeyVisible() {
        _apiKeyVisible.value = !_apiKeyVisible.value
    }

    // ---------------- 文字表单编辑 ----------------

    fun selectChannel(channel: RecognitionChannel) {
        _channel.value = channel
    }

    fun updatePlantNetApiKey(value: String) {
        _plantNetApiKey.value = value
    }

    fun updatePlantNetBaseUrl(value: String) {
        _plantNetBaseUrl.value = value.trim()
    }

    fun setSharesOneEndpoint(share: Boolean) {
        _sharesOneEndpoint.value = share
        _textTestOutcome.value = null
    }

    fun selectTextPreset(preset: AiPreset) {
        _textForm.value = _textForm.value.withPreset(preset, vision = false)
        _textTestOutcome.value = null
    }

    fun updateTextBaseUrl(value: String) {
        _textForm.value = _textForm.value.copy(baseUrl = value.trim())
        _textTestOutcome.value = null
    }

    fun updateTextModel(value: String) {
        _textForm.value = _textForm.value.copy(model = value.trim())
        _textTestOutcome.value = null
    }

    fun updateTextApiKey(value: String) {
        _textForm.value = _textForm.value.copy(apiKey = value.trim())
        _textTestOutcome.value = null
    }

    fun toggleTextApiKeyVisible() {
        _textApiKeyVisible.value = !_textApiKeyVisible.value
    }

    fun selectStrategy(value: PromptStrategy) {
        _strategy.value = value
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---------------- 测试连接 ----------------

    /**
     * 测试视觉通道。
     *
     * 用**表单里的当前值**而不是已保存的配置 —— 用户改完地址后第一件事
     * 就是想试试对不对，如果必须先保存才能测，体验会很别扭。
     */
    fun testConnection() {
        val candidate = _form.value
        if (!candidate.isUsable) {
            _testOutcome.value = TestOutcome.Failed(
                message = "还缺少：${candidate.missingFields.joinToString("、")}",
                hint = null,
                detail = null,
            )
            return
        }

        viewModelScope.launch {
            _testing.value = true
            _testOutcome.value = null
            try {
                _testOutcome.value = visionProvider.testConnection(
                    ConnectivityRequest(config = candidate),
                ).toTestOutcome()
            } finally {
                _testing.value = false
            }
        }
    }

    /**
     * 测试文字通道。
     *
     * 不复用视觉侧的测试：视觉侧会发一张探针图，拿它去测纯文本模型
     * 必然得到「该模型不接受图片」的结论 —— 而那对文字分析根本不是问题。
     */
    fun testTextConnection() {
        val candidate = if (_sharesOneEndpoint.value) _form.value else _textForm.value
        if (!candidate.isUsable) {
            _textTestOutcome.value = TestOutcome.Failed(
                message = "还缺少：${candidate.missingFields.joinToString("、")}",
                hint = null,
                detail = null,
            )
            return
        }

        viewModelScope.launch {
            _testingText.value = true
            _textTestOutcome.value = null
            try {
                _textTestOutcome.value = textProvider.testConnection(
                    ConnectivityRequest(config = candidate),
                ).toTestOutcome()
            } finally {
                _testingText.value = false
            }
        }
    }

    // ---------------- 保存 / 清除 ----------------

    /** 保存配置 */
    fun save() {
        val share = _sharesOneEndpoint.value
        val textCandidate = _textForm.value
        val channel = _channel.value
        val pnKey = _plantNetApiKey.value.trim()

        // ---------------- 按**当前通道**校验（v1.0.3）----------------
        //
        // 这里曾经无条件校验 `_form.value.isUsable`（视觉配置），于是选了
        // Pl@ntNet 的用户即使把 Key 填对了也会被拦下 —— 而且提示写的是
        // 「视觉识别配置还缺少：…」，与他要做的事毫无关系。更糟的是
        // 同一判据还挂在保存按钮的 `enabled` 上，按钮直接置灰、连点都点不了。
        //
        // 现在拼出「即将落盘的那份配置」再问它当前通道是否可用，
        // 校验口径与实际保存内容**必然一致**（不会再出现「按 A 校验、存 B」）。
        val candidate = buildConfig(channel, share, textCandidate, pnKey)
        if (!candidate.isUsableFor(channel)) {
            _message.value = "还缺少：" + candidate.missingFor(channel).joinToString("、")
            return
        }

        // 文字配置不完整时**不阻止保存** —— 那表示用户暂时不打算生成植物百科，
        // 是合理选择而非错误。运行时若该配置不可用，会按「文字分析不可用」处理：
        // 识别结果照常落库，只是没有百科内容（规格书第三十节）。
        // 状态卡里会显示文字配置的完整度，用户自己看得到。
        viewModelScope.launch {
            _saving.value = true
            try {
                store.save(candidate)
                    .onSuccess {
                        _hasSavedKey.value = candidate.vision.apiKey.isNotEmpty()
                        _message.value = if (channel == RecognitionChannel.PLANT_NET) {
                            "已保存。Pl@ntNet API Key 已加密存放在本机，不会上传到任何服务器。"
                        } else {
                            "已保存。API Key 已加密存放在本机，不会上传到任何服务器。"
                        }
                    }
                    .onFailure { error ->
                        _message.value = error.message ?: "保存失败"
                    }
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * 按当前表单值拼出完整配置（v1.0.3）。
     *
     * 抽出来是为了让**校验与保存用同一份对象**：分成两处构造的话，
     * 「按视觉配置校验、却存了 Pl@ntNet 配置」这类错配迟早会发生，
     * 而且不会报错，只表现为某个通道神秘地不可用。
     *
     * 注意 [canSave] / [missingForChannel] 也走同一套构造与判据 ——
     * 按钮亮不亮、提示说什么、按下去存什么，三者同源。
     */
    private fun buildConfig(
        channel: RecognitionChannel,
        share: Boolean,
        textCandidate: AiEndpointConfig,
        pnKey: String,
    ): AiConfig = AiConfig(
        vision = _form.value,
        // null 表示「复用视觉配置」
        text = if (share) null else textCandidate,
        promptStrategy = _strategy.value,
        // 端点用表单里的值（默认已是官方 v2）。Key 为空则整个 plantNet
        // 存 null，形成「未配置」这个明确状态。
        plantNet = if (pnKey.isEmpty()) {
            null
        } else {
            AiConfig.defaultPlantNet().copy(
                baseUrl = _plantNetBaseUrl.value.trim().ifEmpty {
                    AiConfig.defaultPlantNet().baseUrl
                },
                apiKey = pnKey,
            )
        },
        recognitionChannel = channel,
    )

    /** 清除全部配置（含 Keystore 中的密钥） */
    fun clearAll() {
        viewModelScope.launch {
            store.clear()
            _form.value = AiConfig.defaultVision()
            _textForm.value = AiConfig.defaultText()
            _sharesOneEndpoint.value = true
            _strategy.value = PromptStrategy.DEFAULT
            _channel.value = RecognitionChannel.VISION_AI
            _plantNetApiKey.value = ""
            _plantNetBaseUrl.value = AiConfig.defaultPlantNet().baseUrl
            _hasSavedKey.value = false
            _testOutcome.value = null
            _textTestOutcome.value = null
            _apiKeyVisible.value = false
            _textApiKeyVisible.value = false
            _message.value = "已清除全部 AI 配置"
        }
    }

    companion object {
        fun factory(
            store: AiSettingsStore,
            visionProvider: VisionProvider,
            textProvider: TextProvider,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(store, visionProvider, textProvider) }
        }
    }
}

/** 把通道侧的连接结果转成设置页要展示的四态 */private fun ConnectivityResult.toTestOutcome(): TestOutcome = when (this) {
    is ConnectivityResult.Success -> TestOutcome.Success(
        model = model,
        latencyMs = latencyMs,
    )

    is ConnectivityResult.ImageUnverified -> TestOutcome.ImageUnverified(
        model = model,
        latencyMs = latencyMs,
        reason = reason,
    )

    is ConnectivityResult.TextOnlyModel -> TestOutcome.TextOnly(model)

    is ConnectivityResult.Failure -> TestOutcome.Failed(
        message = failure.userMessage,
        hint = failure.fixHint,
        detail = failure.serverDetail,
    )
}

/** 测试连接的结果，直接对应 UI 上的四态展示 */
sealed interface TestOutcome {

    /** 连接正常且模型接受图片 */
    data class Success(val model: String, val latencyMs: Long) : TestOutcome

    /**
     * 连接正常，但图片没验上。
     *
     * 与「连接失败」必须区分开：地址、Key、模型名都是对的，
     * 只是探针图被服务端的参数校验挡了（各家最小尺寸要求不同）。
     * 报成失败会让人去反复核对一个其实正确的配置。
     */
    data class ImageUnverified(
        val model: String,
        val latencyMs: Long,
        val reason: String?,
    ) : TestOutcome

    /** 连接正常，但该模型不接受图片输入 —— 配成了纯文本模型 */
    data class TextOnly(val model: String) : TestOutcome

    data class Failed(
        val message: String,
        val hint: String?,
        val detail: String?,
    ) : TestOutcome
}

/**
 * 配置是否可用的简短描述，用于设置页的状态卡。
 *
 * 与「不阻止保存」配套：用户能看到当前处于哪种状态，
 * 而不是保存时被拦住却不知道哪里不对。
 */
val AiEndpointConfig.readinessText: String
    get() = when {
        isUsable -> "已就绪"
        baseUrl.isBlank() && model.isBlank() && apiKey.isBlank() -> "未配置"
        else -> "不完整（缺 ${missingFields.joinToString("、")}）"
    }
