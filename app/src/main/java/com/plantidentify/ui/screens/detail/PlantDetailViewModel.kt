package com.plantidentify.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.TextAnalysisRequest
import com.plantidentify.data.ai.TextAnalysisResult
import com.plantidentify.data.ai.TextProvider
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.local.relation.PlantWithObservationsAndImages
import com.plantidentify.data.repository.PlantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlantDetailViewModel(plantId, repository, textProvider, aiSettingsStore)
            }
        }
    }
}
