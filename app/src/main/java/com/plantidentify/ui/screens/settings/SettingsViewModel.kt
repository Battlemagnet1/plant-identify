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
import com.plantidentify.data.ai.VisionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * ## API Key 的处理
 *
 * 从磁盘读出的 Key 会解密后回填到表单，但**输入框默认以密码形式显示**（验收标准 ④）。
 * 用户可以点小眼睛临时查看。这比「留空表示不修改」更不容易出岔子 ——
 * 后者在用户只想改模型名时会不小心把 Key 清掉。
 */
class SettingsViewModel(
    private val store: AiSettingsStore,
    private val visionProvider: VisionProvider,
) : ViewModel() {

    /** 表单中正在编辑的视觉配置 */
    private val _form = MutableStateFlow(AiConfig.defaultVision())
    val form: StateFlow<AiEndpointConfig> = _form.asStateFlow()

    /** 是否在表单里显示 API Key 明文（默认隐藏） */
    private val _apiKeyVisible = MutableStateFlow(false)
    val apiKeyVisible: StateFlow<Boolean> = _apiKeyVisible.asStateFlow()

    /**
     * 识别 prompt 策略。
     *
     * 开放给用户切换，是为了让分析报告 Part 3.4 的 A/B 对照实验
     * 可以直接用真实照片在 App 里跑，而不必另写一套实验脚本。
     */
    private val _strategy = MutableStateFlow(PromptStrategy.DEFAULT)
    val strategy: StateFlow<PromptStrategy> = _strategy.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    /** 测试连接的结论，展示在按钮下方 */
    private val _testOutcome = MutableStateFlow<TestOutcome?>(null)
    val testOutcome: StateFlow<TestOutcome?> = _testOutcome.asStateFlow()

    /** 一次性提示（保存成功等） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 是否存在已保存的 API Key（用于显示状态文案，不回显明文） */
    private val _hasSavedKey = MutableStateFlow(false)
    val hasSavedKey: StateFlow<Boolean> = _hasSavedKey.asStateFlow()

    init {
        viewModelScope.launch {
            val config = store.config.first()
            _form.value = config.vision
            _strategy.value = config.promptStrategy
            _hasSavedKey.value = config.vision.apiKey.isNotEmpty()
        }
    }

    // ---------------- 表单编辑 ----------------

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

    fun selectStrategy(value: PromptStrategy) {
        _strategy.value = value
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---------------- 动作 ----------------

    /**
     * 测试连接。
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
                _testOutcome.value = when (val result = visionProvider.testConnection(
                    ConnectivityRequest(config = candidate),
                )) {
                    is ConnectivityResult.Success -> TestOutcome.Success(
                        model = result.model,
                        latencyMs = result.latencyMs,
                    )

                    is ConnectivityResult.TextOnlyModel -> TestOutcome.TextOnly(result.model)

                    is ConnectivityResult.Failure -> TestOutcome.Failed(
                        message = result.failure.userMessage,
                        hint = result.failure.fixHint,
                        detail = result.failure.serverDetail,
                    )
                }
            } finally {
                _testing.value = false
            }
        }
    }

    /** 保存配置 */
    fun save() {
        val candidate = _form.value
        if (!candidate.isUsable) {
            _message.value = "还缺少：${candidate.missingFields.joinToString("、")}"
            return
        }

        viewModelScope.launch {
            _saving.value = true
            try {
                // Phase 3 只配置视觉通道；文字通道留到 Phase 4 接入 TextProvider 时再开放，
                // 现在先保持「复用视觉配置」的状态（text = null）
                val config = AiConfig(
                    vision = candidate,
                    text = null,
                    promptStrategy = _strategy.value,
                )

                store.save(config)
                    .onSuccess {
                        _hasSavedKey.value = candidate.apiKey.isNotEmpty()
                        _message.value = "已保存。API Key 已加密存放在本机，不会上传到任何服务器。"
                    }
                    .onFailure { error ->
                        _message.value = error.message ?: "保存失败"
                    }
            } finally {
                _saving.value = false
            }
        }
    }

    /** 清除全部配置（含 Keystore 中的密钥） */
    fun clearAll() {
        viewModelScope.launch {
            store.clear()
            _form.value = AiConfig.defaultVision()
            _strategy.value = PromptStrategy.DEFAULT
            _hasSavedKey.value = false
            _testOutcome.value = null
            _apiKeyVisible.value = false
            _message.value = "已清除全部 AI 配置"
        }
    }

    companion object {
        fun factory(
            store: AiSettingsStore,
            visionProvider: VisionProvider,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(store, visionProvider) }
        }
    }
}

/** 测试连接的结果，直接对应 UI 上的三态展示 */
sealed interface TestOutcome {

    /** 连接正常且模型接受图片 */
    data class Success(val model: String, val latencyMs: Long) : TestOutcome

    /** 连接正常，但该模型不接受图片输入 —— 配成了纯文本模型 */
    data class TextOnly(val model: String) : TestOutcome

    data class Failed(
        val message: String,
        val hint: String?,
        val detail: String?,
    ) : TestOutcome
}

/** 供设置页展示的配置完整度提示 */
val AiEndpointConfig.statusText: String
    get() = when {
        apiKey.isBlank() -> "尚未填写 API Key"
        baseUrl.isBlank() -> "尚未填写 Base URL"
        model.isBlank() -> "尚未填写模型名"
        else -> "配置完整"
    }
