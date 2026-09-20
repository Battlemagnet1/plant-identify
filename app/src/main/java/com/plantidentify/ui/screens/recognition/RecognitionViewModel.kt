package com.plantidentify.ui.screens.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import android.util.Log
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.ai.AiFailure
import com.plantidentify.data.ai.VisionResponse
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.recognition.AnalysisRunner
import com.plantidentify.data.recognition.RecognitionExecutor
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.domain.model.MergeSuggestion
import com.plantidentify.domain.model.TimingTrace
import kotlinx.coroutines.CoroutineScope
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
    /**
     * 识别流程的共享执行体。
     *
     * 压缩 / 视觉请求 / 落库都搬去了它那里，本类只负责「把结果映射成界面状态」。
     * 这样 Phase 2 的 Worker 能用同一份实现跑后台任务，
     * 不会出现「前台一套 prompt、后台一套 prompt」的分叉。
     */
    private val executor: RecognitionExecutor,
    private val repository: PlantRepository,
    private val analysisRunner: AnalysisRunner,
    /**
     * 应用级作用域，**不是** `viewModelScope`。
     *
     * 保存成功后会 `navigate(plantDetail) { popUpTo(RECOGNITION) { inclusive = true } }` ——
     * 本 ViewModel 随即被销毁，`viewModelScope` 里的协程会被取消。
     * 而文字分析正是在保存之后启动的：用 `viewModelScope` 会让分析
     * 在导航那一瞬间静默中止，用户永远等不到百科。
     */
    private val externalScope: CoroutineScope,
) : ViewModel() {

    /**
     * 本次识别的**地点弱先验**，在 [recognize] 里算一次、保存阶段复用。
     *
     * 为什么不每次现读草稿：保存成功后草稿会被清掉，
     * 而文字分析发生在保存之后 —— 那时再读草稿只会得到空值，
     * 于是视觉请求带了地点、百科请求却没带，同一株植物的两次请求口径不一致。
     */
    private var placeHint: String? = null

    private val _state = MutableStateFlow<RecognitionUiState>(RecognitionUiState.Idle)
    val state: StateFlow<RecognitionUiState> = _state.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.NotSaved)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    /**
     * 本次识别各阶段的耗时，给界面上的「本次耗时」卡用。
     *
     * 存在的意义是让「慢在哪」可见 —— 在它出现之前，
     * 「识别要两三分钟」这个说法无法拆开，只能靠猜。
     */
    private val _timing = MutableStateFlow<List<TimingTrace.Segment>>(emptyList())
    val timing: StateFlow<List<TimingTrace.Segment>> = _timing.asStateFlow()

    init {
        // 进入页面即自动开始识别：用户从「添加植物」点「开始识别」过来时，
        // 意图已经明确，再让他按一次按钮是多余的
        recognize()
    }

    /** 重新识别（用户改完配置或补充照片后回来） */
    fun recognize() {
        _saveState.value = SaveState.NotSaved
        _timing.value = emptyList()

        viewModelScope.launch {
            _state.value = RecognitionUiState.Preparing

            val draft = draftStore.current()
            if (draft.isEmpty) {
                _state.value = RecognitionUiState.Failed(
                    AiFailure.LocalProblem("还没有照片，请先添加至少一张植物照片"),
                    canOpenSettings = false,
                )
                return@launch
            }

            // 压缩与请求都在执行体里。这里只负责把它的结果翻译成界面状态。
            val outcome = executor.recognize(draft) { imageCount, usedFallback ->
                // 压缩完成、即将发请求 —— 切到「正在请求模型」。
                // 放在回调里而不是调用之前：「有几张图真的上传了」要等压缩跑完才知道
                _state.value = RecognitionUiState.Recognizing(
                    imageCount = imageCount,
                    usedFallbackForSomeImages = usedFallback,
                )
            }

            when (outcome) {
                is RecognitionExecutor.RecognizeOutcome.Recognized -> {
                    _timing.value = outcome.timing
                    // 地点先验要**记住**：保存阶段的文字分析复用同一个值。
                    // 不记的话，保存成功后草稿已被清空，百科请求就丢了地点，
                    // 同一株植物的两次请求口径不一致
                    placeHint = outcome.placeHint
                    Log.i(TimingTrace.LOG_TAG, "本次识别 ${outcome.timing.renderForLog()}")
                    _state.value = RecognitionUiState.Success(outcome.response)
                }

                is RecognitionExecutor.RecognizeOutcome.Failed -> {
                    _state.value = RecognitionUiState.Failed(
                        outcome.failure,
                        canOpenSettings = outcome.canOpenSettings,
                    )
                }
            }
        }
    }

    /**
     * 把耗时分段拼成一行日志。
     *
     * 结果页的耗时卡在识别成功后才看得到，失败的那一轮不会有卡片 ——
     * 而「失败时卡在哪一步」恰恰是更值得知道的信息，只能靠这行日志。
     */
    private fun List<TimingTrace.Segment>.renderForLog(): String =
        joinToString(" / ") { "${it.label} ${it.millis}ms" }

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
            // 已落库但百科还在后台跑 —— 这也是「已经保存过了」，
            // 漏掉它就会让一次连点产生第二个档案
            is SaveState.SavedAnalysisPending,
            is SaveState.SavedWithAnalysis,
            is SaveState.SavedWithoutAnalysis,
            is SaveState.AwaitingMergeDecision,
            -> return

            else -> Unit
        }

        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            applyPersistOutcome(
                executor.persistNow(
                    result = result,
                    rawAiJson = current.response.rawText,
                    draft = draftStore.current(),
                ),
            )
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
            executor
                .appendToExisting(
                    plantId = target.id,
                    result = result,
                    rawAiJson = current.response.rawText,
                )
                .onSuccess { plantId ->
                    // 追加观察后不自动重跑百科：已有植物的百科是按整株生成的，
                    // 本次新增的照片未必带来新信息，主动覆盖反而可能让内容变差。
                    // 需要更新时由用户在详情页点「重新生成」。
                    _saveState.value = SaveState.SavedWithAnalysis(plantId)
                }
                .onFailure { error ->
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
            applyPersistOutcome(
                executor.persistNow(
                    result = result,
                    rawAiJson = current.response.rawText,
                    draft = draftStore.current(),
                    // 用户已经在那次提示里选了「创建新的植物」——
                    // 不跳过的话执行体会再问一次，界面表现为点了没反应
                    skipMergeCheck = true,
                ),
            )
        }
    }

    /**
     * 把落库结果翻译成界面状态。
     *
     * ## 「已落库」与「已启动分析」是两步
     *
     * 落库一完成就置 [SaveState.SavedAnalysisPending] 并让界面可以立刻导航离开，
     * 文字分析交给应用级作用域。原先这里要等分析跑完
     * （界面停在「正在保存（可能包含文字分析，耗时较长）」）——
     * 用户感知到的「保存好慢」就是那一次串行的网络请求。
     *
     * 而它其实不必等：档案在落库那一瞬间就已经完整 —— 可查、可搜、可导出。
     * 百科描述是锦上添花，晚几秒到、甚至失败，都不影响档案的可用性
     * （规格书第三十节也是这么要求的）。
     */
    private fun applyPersistOutcome(persisted: Result<RecognitionExecutor.PersistOutcome>) {
        persisted
            .onSuccess { outcome ->
                when (outcome) {
                    is RecognitionExecutor.PersistOutcome.UpdatedObservation -> {
                        // 观察内容变了，但百科是基于植物整体生成的，不必重跑
                        _saveState.value = SaveState.SavedToExistingObservation(outcome.plantId)
                    }

                    is RecognitionExecutor.PersistOutcome.AwaitingDecision -> {
                        // 只提示、不写入。是否归并完全由用户确认 —— 规格书第十四点五节
                        _saveState.value = SaveState.AwaitingMergeDecision(outcome.suggestion)
                    }

                    is RecognitionExecutor.PersistOutcome.Created -> {
                        _saveState.value = SaveState.SavedAnalysisPending(outcome.plantId)
                        startBackgroundAnalysis(outcome.plantId)
                    }

                    is RecognitionExecutor.PersistOutcome.AttachedToExisting -> {
                        _saveState.value = SaveState.SavedWithAnalysis(outcome.plantId)
                    }
                }
            }
            .onFailure { error ->
                _saveState.value = SaveState.Failed(error.message ?: "保存失败，请重试")
            }
    }

    /**
     * 把文字分析丢到应用级作用域。
     *
     * 用 [externalScope] 而不是 `viewModelScope` 是**必须的**：
     * 界面收到 `SavedAnalysisPending` 后会 `navigate(plantDetail)` 并把本页
     * 从回退栈移除，本 ViewModel 随即销毁 —— `viewModelScope` 里的协程
     * 会在那一刻被取消，百科永远生成不出来，而且不会有任何报错。
     */
    private fun startBackgroundAnalysis(plantId: Long) {
        val result = (_state.value as? RecognitionUiState.Success)?.response?.result
        externalScope.launch {
            val outcome = analysisRunner.run(
                plantId = plantId,
                result = result,
                plantName = result?.name,
                place = placeHint,
            )
            // 此时页面多半已经不在，这个赋值没人看 —— 保留它只是为了
            // 「用户一直停在识别页」这种情形下状态仍然是对的
            applyAnalysisOutcome(plantId, outcome)
        }
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
            val outcome = analysisRunner.run(
                plantId = plantId,
                result = current.response.result,
                place = placeHint,
            )
            applyAnalysisOutcome(plantId, outcome)
        }
    }

    /** 把分析结果映射成界面状态。三条分支的文案差异在 [AnalysisRunner.Outcome] 里已备好 */
    private fun applyAnalysisOutcome(plantId: Long, outcome: AnalysisRunner.Outcome) {
        _saveState.value = when (outcome) {
            is AnalysisRunner.Outcome.Succeeded ->
                SaveState.SavedWithAnalysis(plantId = plantId, partialNote = outcome.partialNote)

            is AnalysisRunner.Outcome.NotConfigured ->
                SaveState.SavedWithoutAnalysis(plantId = plantId, reason = outcome.reason)

            is AnalysisRunner.Outcome.Failed ->
                SaveState.SavedWithoutAnalysis(plantId = plantId, reason = outcome.reason)
        }
    }

    companion object {
        fun factory(
            draftStore: CaptureDraftStore,
            executor: RecognitionExecutor,
            repository: PlantRepository,
            analysisRunner: AnalysisRunner,
            externalScope: CoroutineScope,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RecognitionViewModel(
                    draftStore = draftStore,
                    executor = executor,
                    repository = repository,
                    analysisRunner = analysisRunner,
                    externalScope = externalScope,
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

    /** 正在保存（落库） */
    data object Saving : SaveState

    /**
     * **已落库**，植物百科正在后台生成。
     *
     * 与 [SavedWithAnalysis] 的区别只有一件事：分析还没跑完。
     * 但它值得单独一个状态，因为它代表**流程已经结束** ——
     * 用户可以立刻离开这一页，档案已经在了。
     *
     * 原先保存要等文字分析跑完才返回（界面上写着「正在保存（可能包含文字分析，
     * 耗时较长）」），用户感知到的「保存好慢」就是这么来的。
     * 而分析其实完全可以晚一步做：它的产物是百科描述，
     * 缺了它档案依然完整可查、可搜索、可导出。
     */
    data class SavedAnalysisPending(val plantId: Long) : SaveState

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
