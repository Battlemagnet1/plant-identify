package com.plantidentify.ui.screens.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.ai.AiFailure
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.TextAnalysisRequest
import com.plantidentify.data.ai.TextAnalysisResult
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.ai.TextProvider
import com.plantidentify.data.ai.VisionCallResult
import com.plantidentify.data.ai.VisionImage
import com.plantidentify.data.ai.VisionProvider
import com.plantidentify.data.ai.VisionRequest
import com.plantidentify.data.ai.VisionResponse
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.image.ImageCompressor
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.model.MergeSuggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 识别页 ViewModel。
 *
 * ## 完整流程（规格书第三十节）
 *
 * ```
 * 读草稿 → 校验配置 → 压缩 → 调视觉模型 → 容错解析 → 展示结果
 *        → 用户点「保存到档案」→ 落库（三表）
 *        → 调文字模型生成百科 → 回填档案
 * ```
 *
 * ## 为什么保存要等用户确认
 *
 * 规格书的流程图里保存发生在「用户确认」之后。这既符合预期，也有实际好处：
 * 用户看到明显离谱的结果可以直接返回，不会在库里留下垃圾档案。
 *
 * ## 关键顺序：先落库，再分析
 *
 * 文字分析**必须**排在落库之后。规格书第三十节明确要求
 * 「文字分析失败不能导致植物识别结果丢失」——
 * 如果反过来先分析再保存，一旦分析超时或报错，
 * 已经花掉的识别成本就白费了。
 *
 * 因此 [saveCurrentResult] 的顺序是死的：建档案 → 标记 PENDING →
 * 调文字模型 → 回填或标记 FAILED。任何文字侧的失败都不影响档案本身。
 */
class RecognitionViewModel(
    private val draftStore: CaptureDraftStore,
    private val imageStore: ImageStore,
    private val imageCompressor: ImageCompressor,
    private val aiSettingsStore: AiSettingsStore,
    private val visionProvider: VisionProvider,
    private val textProvider: TextProvider,
    private val repository: PlantRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RecognitionUiState>(RecognitionUiState.Idle)
    val state: StateFlow<RecognitionUiState> = _state.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.NotSaved)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    init {
        // 进入页面即自动开始识别：用户从「添加植物」点「开始识别」过来时，
        // 意图已经明确，再让他按一次按钮是多余的
        recognize()
    }

    /** 重新识别（用户改完配置或补充照片后回来） */
    fun recognize() {
        _saveState.value = SaveState.NotSaved

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

    /**
     * 保存当前识别结果。
     *
     * 可以重复调用而不会产生重复档案：已经在保存中或已保存时直接返回，
     * 由 [SaveState] 拦住。
     *
     * ## 三条分支
     *
     * | 草稿状态 | 行为 |
     * |---|---|
     * | 指向已有观察（补图重识别） | **更新那条观察**，不新建，也不做归并提示 |
     * | 普通新建 + 找到候选 | 先弹归并提示，由用户决定 |
     * | 普通新建 + 无候选 | 直接建新档案 |
     *
     * 补图那条之所以跳过归并提示：用户在点「补图并重新识别」时已经明确
     * 表示「这是同一株、同一次观察」，再问一遍「要不要加到已有植物」
     * 是重复确认，只会让人怀疑自己点错了。
     */
    fun saveCurrentResult() {
        val current = _state.value as? RecognitionUiState.Success ?: return
        val result = current.response.result ?: return

        when (_saveState.value) {
            SaveState.Saving,
            is SaveState.SavedWithAnalysis,
            is SaveState.SavedWithoutAnalysis,
            is SaveState.AwaitingMergeDecision,
            -> return

            else -> Unit
        }

        viewModelScope.launch {
            val draft = draftStore.current()

            // ---- 分支一：给已有观察补图 → 写回原观察
            if (draft.isReanalysis) {
                _saveState.value = SaveState.Saving
                repository.reanalyzeObservation(
                    observationId = draft.targetObservationId!!,
                    result = result,
                    rawAiJson = current.response.rawText,
                ).onSuccess { plantId ->
                    _saveState.value = SaveState.SavedToExistingObservation(plantId)
                    // 观察内容变了，但百科是基于植物整体生成的，不必重跑
                }.onFailure { error ->
                    _saveState.value = SaveState.Failed(
                        error.message ?: "更新观察失败，请重试",
                    )
                }
                return@launch
            }

            // ---- 分支二 / 三：先问归并，再决定
            _saveState.value = SaveState.Saving
            val suggestion = repository.findMergeSuggestion(result)
            if (suggestion.hasCandidate) {
                // 只提示、不写入。是否归并完全由用户确认 —— 规格书第十四点五节
                _saveState.value = SaveState.AwaitingMergeDecision(suggestion)
                return@launch
            }

            saveAsNewPlant(current, result)
        }
    }

    /** 用户确认「添加到已有植物」 */
    fun appendToExistingPlant() {
        val current = _state.value as? RecognitionUiState.Success ?: return
        val result = current.response.result ?: return
        val decision = _saveState.value as? SaveState.AwaitingMergeDecision ?: return
        val target = decision.suggestion.plant ?: return

        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            repository.appendObservation(
                plantId = target.id,
                result = result,
                rawAiJson = current.response.rawText,
            ).onSuccess { plantId ->
                // 追加观察后不自动重跑百科：已有植物的百科是按整株生成的，
                // 本次新增的照片未必带来新信息，主动覆盖反而可能让内容变差。
                // 需要更新时由用户在详情页点「重新生成」。
                _saveState.value = SaveState.SavedWithAnalysis(plantId)
            }.onFailure { error ->
                _saveState.value = SaveState.Failed(error.message ?: "添加观察失败，请重试")
            }
        }
    }

    /** 用户确认「创建新的植物」 */
    fun createNewPlant() {
        val current = _state.value as? RecognitionUiState.Success ?: return
        val result = current.response.result ?: return
        if (_saveState.value !is SaveState.AwaitingMergeDecision) return

        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            saveAsNewPlant(current, result)
        }
    }

    /** 基础结果落库 + 文字分析（两条分支共用） */
    private suspend fun saveAsNewPlant(
        current: RecognitionUiState.Success,
        result: RecognitionResult,
    ) {
        // ---- 第一步：基础识别结果落库。这一步失败就没有档案，如实报错
        val plantId = repository
            .saveAsNewPlant(result = result, rawAiJson = current.response.rawText)
            .getOrElse { error ->
                _saveState.value = SaveState.Failed(error.message ?: "保存失败，请重试")
                return
            }

        // ---- 第二步：文字分析。失败只影响描述内容，不影响档案
        runAnalysis(plantId, result.name, current)
    }

    /**
     * 重新生成文字分析。
     *
     * 用于两种场景：首次分析失败后的重试，以及用户换了文字模型后想重新生成。
     */
    fun regenerateAnalysis() {
        val current = _state.value as? RecognitionUiState.Success ?: return
        val plantId = when (val save = _saveState.value) {
            is SaveState.SavedWithAnalysis -> save.plantId
            is SaveState.SavedWithoutAnalysis -> save.plantId
            else -> return
        }

        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            runAnalysis(plantId, current.response.result?.name.orEmpty(), current)
        }
    }

    /**
     * 执行文字分析并回填档案。
     *
     * 方法与 [saveCurrentResult] 分开，是为了让「已保存但分析失败 → 重新生成」
     * 这条路径能复用同一段逻辑，而不是复制一遍。
     */
    private suspend fun runAnalysis(
        plantId: Long,
        plantName: String,
        recognition: RecognitionUiState.Success,
    ) {
        val config = aiSettingsStore.current().effectiveText

        // 没配文字模型不算错误 —— 用户可能只想用视觉识别。
        // 但要区分「完全没配」与「配了一半」，否则用户看到「尚未配置」
        // 会以为自己没动过设置。
        if (!config.isUsable) {
            repository.updateAnalysis(plantId, AnalysisStatus.NOT_REQUESTED)

            val nothingConfigured = config.baseUrl.isBlank() && config.model.isBlank() && config.apiKey.isBlank()
            _saveState.value = SaveState.SavedWithoutAnalysis(
                plantId = plantId,
                reason = if (nothingConfigured) {
                    "尚未配置文字分析模型，植物百科未能生成"
                } else {
                    "文字分析配置不完整（缺 ${config.missingFields.joinToString("、")}）" +
                        "，植物百科未能生成"
                },
            )
            return
        }

        repository.markAnalysisPending(plantId)

        val result = recognition.response.result
        val analysis = textProvider.generateAnalysis(
            TextAnalysisRequest(
                name = plantName,
                latinName = result?.latinName,
                family = result?.family,
                genus = result?.genus,
                category = result?.category,
                confidence = result?.confidence ?: 0.0,
                evidence = result?.evidence.orEmpty(),
                config = config,
            ),
        )

        when (analysis) {
            is TextAnalysisResult.Success -> {
                repository.updateAnalysis(
                    plantId = plantId,
                    status = AnalysisStatus.SUCCEEDED,
                    analysis = analysis.analysis,
                )
                _saveState.value = SaveState.SavedWithAnalysis(
                    plantId = plantId,
                    partialNote = analysis.parseNote,
                )
            }

            is TextAnalysisResult.Failure -> {
                repository.updateAnalysis(plantId, AnalysisStatus.FAILED)
                _saveState.value = SaveState.SavedWithoutAnalysis(
                    plantId = plantId,
                    reason = analysis.failure.userMessage,
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
            textProvider: TextProvider,
            repository: PlantRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RecognitionViewModel(
                    draftStore = draftStore,
                    imageStore = imageStore,
                    imageCompressor = imageCompressor,
                    aiSettingsStore = aiSettingsStore,
                    visionProvider = visionProvider,
                    textProvider = textProvider,
                    repository = repository,
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

/**
 * 保存流程的状态。
 *
 * 「保存失败」与「保存成功但分析失败」被刻意分成两类：
 * 前者档案不存在，用户必须重试；后者档案已经在库里了，
 * 只是描述内容暂缺 —— 混在一起会让用户以为白识别了一场。
 */
sealed interface SaveState {

    /** 尚未保存，等待用户确认 */
    data object NotSaved : SaveState

    /**
     * 找到了可能对应的已有植物，等用户决定是否归并。
     *
     * 这个状态的存在本身就是规格书的要求：**系统不得自动合并**。
     * 它不是一个「中间态」，而是流程的正常分支。
     */
    data class AwaitingMergeDecision(val suggestion: MergeSuggestion) : SaveState

    /** 正在保存（可能包含文字分析，耗时较长） */
    data object Saving : SaveState

    /** 已保存，且文字分析成功 */
    data class SavedWithAnalysis(
        val plantId: Long,
        /** 非空表示模型只返回了部分字段 */
        val partialNote: String? = null,
    ) : SaveState

    /** **已保存**，但文字分析失败 —— 基础识别结果仍然完整可查看 */
    data class SavedWithoutAnalysis(
        val plantId: Long,
        val reason: String,
    ) : SaveState

    /**
     * 识别结果已写回**已有观察**（补图重识别）。
     *
     * 与 [SavedWithAnalysis] 分开，是因为界面要给不同的下一步：
     * 新建档案后用户多半想看详情页；而补图之后他会想确认「观察次数没变多」。
     */
    data class SavedToExistingObservation(val plantId: Long) : SaveState

    /** 保存本身失败，档案未建立 */
    data class Failed(val message: String) : SaveState
}
