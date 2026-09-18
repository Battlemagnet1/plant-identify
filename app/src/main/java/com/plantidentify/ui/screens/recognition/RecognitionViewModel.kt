package com.plantidentify.ui.screens.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.ai.AiFailure
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.VisionCallResult
import com.plantidentify.data.ai.VisionImage
import com.plantidentify.data.ai.VisionProvider
import com.plantidentify.data.ai.VisionRequest
import com.plantidentify.data.ai.VisionResponse
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.image.ImageCompressor
import com.plantidentify.data.storage.ImageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 识别页 ViewModel。
 *
 * ## 流程
 *
 * ```
 * 读草稿 → 校验配置 → 压缩（1536px/JPEG80）→ 调视觉模型 → 容错解析 → 展示
 * ```
 *
 * ## 为什么压缩放在这里而不是 Phase 2 的导入环节
 *
 * 压缩副本是**派生数据**，只在上传前需要。放在导入时做会让「用户加了一张图
 * 又删掉」这种常见操作白白压一遍；放在识别前做，配合 [ImageCompressor]
 * 的缓存与失效判断，未改动的图不会重复压缩。
 *
 * ## 失败不吞掉结果
 *
 * 三类结果都会被完整传递到 UI：
 *  - 结构化成功
 *  - 降级成功（有名称但字段不全，或只有原始文本）
 *  - 彻底失败（带分类好的 [AiFailure]）
 *
 * 其中「降级成功」展示的是模型原话而非空结果 —— 这是验收标准 ⑤ 的直接要求。
 */
class RecognitionViewModel(
    private val draftStore: CaptureDraftStore,
    private val imageStore: ImageStore,
    private val imageCompressor: ImageCompressor,
    private val aiSettingsStore: AiSettingsStore,
    private val visionProvider: VisionProvider,
) : ViewModel() {

    private val _state = MutableStateFlow<RecognitionUiState>(RecognitionUiState.Idle)
    val state: StateFlow<RecognitionUiState> = _state.asStateFlow()

    init {
        // 进入页面即自动开始识别：用户从「添加植物」点「开始识别」过来时，
        // 意图已经明确，再让他按一次按钮是多余的
        recognize()
    }

    /** 重新识别（用户改完配置或补充照片后回来） */
    fun recognize() {
        viewModelScope.launch {
            _state.value = RecognitionUiState.Preparing

            val draft = draftStore.current()
            if (draft.isEmpty) {
                _state.value = RecognitionUiState.Failed(
                    AiFailure.Unknown("还没有照片，请先添加至少一张植物照片"),
                    canOpenSettings = false,
                )
                return@launch
            }

            val config = aiSettingsStore.current()
            if (!config.vision.isUsable) {
                _state.value = RecognitionUiState.Failed(
                    AiFailure.NotConfigured(config.vision.missingFields),
                    canOpenSettings = true,
                )
                return@launch
            }

            // 逐张生成（或复用）压缩副本
            val images = draft.images.mapNotNull { draftImage ->
                imageCompressor
                    .compressedFor(imageStore.resolve(draftImage.relativePath))
                    .getOrNull()
                    ?.let { compressed ->
                        VisionImage(file = compressed, role = draftImage.role)
                    }
            }

            if (images.isEmpty()) {
                _state.value = RecognitionUiState.Failed(
                    AiFailure.Unknown("照片读取失败，请回到上一步重新添加照片"),
                    canOpenSettings = false,
                )
                return@launch
            }

            _state.value = RecognitionUiState.Recognizing(
                imageCount = images.size,
                usedFallbackForSomeImages = images.size < draft.count,
            )

            _state.value = when (
                val result = visionProvider.recognize(
                    VisionRequest(
                        images = images,
                        config = config.vision,
                        strategy = config.promptStrategy,
                    ),
                )
            ) {
                is VisionCallResult.Success -> RecognitionUiState.Success(result.response)
                is VisionCallResult.Failure -> RecognitionUiState.Failed(
                    result.failure,
                    // 配置类错误直接引导去设置页；其他错误留在本页重试更顺手
                    canOpenSettings = result.failure is AiFailure.NotConfigured ||
                        result.failure is AiFailure.Unauthorized ||
                        result.failure is AiFailure.ModelNotFound ||
                        result.failure is AiFailure.ModelNotVisionCapable ||
                        result.failure is AiFailure.EndpointNotFound ||
                        result.failure is AiFailure.CleartextBlocked,
                )
            }
        }
    }

    companion object {
        fun factory(
            draftStore: CaptureDraftStore,
            imageStore: ImageStore,
            imageCompressor: ImageCompressor,
            aiSettingsStore: AiSettingsStore,
            visionProvider: VisionProvider,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RecognitionViewModel(
                    draftStore = draftStore,
                    imageStore = imageStore,
                    imageCompressor = imageCompressor,
                    aiSettingsStore = aiSettingsStore,
                    visionProvider = visionProvider,
                )
            }
        }
    }
}

/** 识别页的状态机 */
sealed interface RecognitionUiState {

    data object Idle : RecognitionUiState

    /** 正在读取草稿、压缩图片 */
    data object Preparing : RecognitionUiState

    /**
     * 正在请求模型。
     *
     * @param usedFallbackForSomeImages 有图片压缩失败被跳过（提示用户结果可能受影响）
     */
    data class Recognizing(
        val imageCount: Int,
        val usedFallbackForSomeImages: Boolean = false,
    ) : RecognitionUiState

    data class Success(val response: VisionResponse) : RecognitionUiState

    data class Failed(
        val failure: AiFailure,
        /** 是否应引导用户去设置页（配置类错误才需要） */
        val canOpenSettings: Boolean,
    ) : RecognitionUiState
}
