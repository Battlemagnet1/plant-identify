package com.plantidentify.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.ai.TextAnalysisRequest
import com.plantidentify.data.ai.TextAnalysisResult
import com.plantidentify.data.ai.TextProvider
import com.plantidentify.data.ai.TolerantJsonParser
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.location.LocationSettingsStore
import com.plantidentify.data.location.aiPlaceHint
import com.plantidentify.data.local.relation.PlantWithObservationsAndImages
import com.plantidentify.data.repository.PlantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 植物详情页 ViewModel。
 *
 * 只订阅数据、不持有数据 —— 页面每次重组都从 Room 的 Flow 拿最新的，
 * 因此「文字分析生成完成」这类后台更新会自动反映到界面上，
 * 不需要手动刷新或回传结果。
 *
 * ## 为什么详情页也能触发重新生成
 *
 * 文字分析可能在两处失败：保存时（网络抖动）与之后（用户换了模型想重写）。
 * 如果只能在识别结果页重试，用户回到详情页后就再也没有入口了 ——
 * 档案就此永远缺一块内容。
 */
class PlantDetailViewModel(
    private val plantId: Long,
    private val repository: PlantRepository,
    private val textProvider: TextProvider,
    private val aiSettingsStore: AiSettingsStore,
    private val locationSettingsStore: LocationSettingsStore,
) : ViewModel() {

    val detail: StateFlow<PlantWithObservationsAndImages?> =
        repository.observePlantDetail(plantId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

    private val _analyzing = MutableStateFlow(false)
    val analyzing: StateFlow<Boolean> = _analyzing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _deleted = MutableStateFlow(false)

    /** 删除已完成 —— 页面据此返回上一级 */
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /**
     * 相似植物（来自最近一次识别结果里模型的候选）。
     *
     * 不做成数据库字段：它是**识别过程的产物**，只对最近一次识别有意义，
     * 而原始 JSON 已经完整存在 observation.aiResultJson 里。
     * 再加一列只会多一份可能与原始记录不一致的副本。
     */
    val alternatives: StateFlow<List<RecognitionResult.Alternative>> =
        repository.observePlantDetail(plantId)
            .map { detail ->
                // observations 的元素是 ObservationWithImages（观察 + 它的图片），
                // 实体本身要再往里取一层
                val observations = detail?.observations.orEmpty()
                val raw = (observations.firstOrNull { it.observation.isPrimary }
                    ?: observations.firstOrNull())
                    ?.observation
                    ?.aiResultJson
                if (raw.isNullOrBlank()) {
                    emptyList()
                } else {
                    when (val attempt = TolerantJsonParser.parse(raw)) {
                        is TolerantJsonParser.ParseAttempt.Success ->
                            attempt.result.alternatives
                        is TolerantJsonParser.ParseAttempt.Degraded ->
                            attempt.result.alternatives
                        is TolerantJsonParser.ParseAttempt.Failed -> emptyList()
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

    /**
     * 删除整份档案。
     *
     * 连带删除全部观察、图片行，以及**图片文件** —— 数据库级联不等于文件级联，
     * 文件不删就会永远占着存储（规格书把这条列为本 Phase 的主要风险之一）。
     */
    fun deletePlant() {
        viewModelScope.launch {
            repository.deletePlant(plantId)
                .onSuccess { _deleted.value = true }
                .onFailure { error ->
                    _message.value = error.message ?: "删除失败，请重试"
                }
        }
    }

    /** 重新生成植物百科（用于分析失败后重试，或换了文字模型后重写） */
    fun regenerateAnalysis() {
        if (_analyzing.value) return

        viewModelScope.launch {
            val plant = detail.value?.plant ?: return@launch

            val config = aiSettingsStore.current().effectiveText
            if (!config.isUsable) {
                _message.value = "尚未配置文字分析模型，请先到设置页填写"
                return@launch
            }

            _analyzing.value = true
            try {
                repository.markAnalysisPending(plantId)

                // 地点弱先验取「最近一次观察」的那份 ——
                // 重新生成百科时用户关心的是这株最近的状况，
                // 而不是它第一次被记录时的位置。同样受「允许上传」开关约束。
                val latest = detail.value?.observations
                    ?.maxByOrNull { it.observation.timestamp }
                    ?.observation
                val place = locationSettingsStore.current().let { choice ->
                    aiPlaceHint(
                        shareWithAi = choice.shareWithAi,
                        locationName = latest?.locationName,
                        latitude = latest?.latitude,
                        longitude = latest?.longitude,
                    )
                }

                // 置信度取档案上记录的最近一次识别结果，用于决定
                // prompt 是否要退化为属/科的通用特征
                val result = textProvider.generateAnalysis(
                    TextAnalysisRequest(
                        name = plant.name,
                        latinName = plant.latinName,
                        family = plant.family,
                        genus = plant.genus,
                        category = plant.category,
                        confidence = plant.confidence,
                        config = config,
                        place = place,
                    ),
                )

                when (result) {
                    is TextAnalysisResult.Success -> {
                        repository.updateAnalysis(
                            plantId = plantId,
                            status = AnalysisStatus.SUCCEEDED,
                            analysis = result.analysis,
                        )
                        _message.value = "植物百科已生成"
                    }

                    is TextAnalysisResult.Failure -> {
                        repository.updateAnalysis(plantId, AnalysisStatus.FAILED)
                        _message.value = "生成失败：${result.failure.userMessage}"
                    }
                }
            } finally {
                _analyzing.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            plantId: Long,
            repository: PlantRepository,
            textProvider: TextProvider,
            aiSettingsStore: AiSettingsStore,
            locationSettingsStore: LocationSettingsStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlantDetailViewModel(
                    plantId, repository, textProvider, aiSettingsStore, locationSettingsStore,
                )
            }
        }
    }
}
