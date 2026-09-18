package com.plantidentify.ui.screens.observation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.ai.TolerantJsonParser
import com.plantidentify.data.draft.CaptureDraftStore
import com.plantidentify.data.local.relation.ObservationWithImages
import com.plantidentify.data.repository.PlantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 观察记录页 ViewModel。
 *
 * 一个页面同时服务两个路由参数（plantId / observationId）之一：
 * 从详情页进来看某株植物的全部观察；从别处进来只看一条。
 * 用同一个 ViewModel 是因为两者的数据形状完全一致。
 */
class ObservationViewModel(
    private val plantId: Long?,
    private val observationId: Long?,
    private val repository: PlantRepository,
    private val draftStore: CaptureDraftStore,
) : ViewModel() {

    /** 要展示的观察列表（按时间倒序） */
    val observations: StateFlow<List<ObservationWithImages>> = (
        if (plantId != null) {
            repository.observePlantDetail(plantId)
                .map { it?.observations.orEmpty() }
        } else {
            // 单条模式：从库里取那一条（Flow 也订阅着，删除后会自动变空）
            repository.observeObservation(observationId ?: 0L)
                .map { listOfNotNull(it) }
        }
        )
        .map { list -> list.sortedByDescending { it.observation.timestamp } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    /** 植物名称（单条模式下用来显示） */
    val plantName: StateFlow<String> = observations
        .map { list ->
            list.firstOrNull()?.let { first ->
                repository.plantNameOf(first.observation.plantId)
            }.orEmpty()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = "",
        )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _draftSeeded = MutableStateFlow(false)

    /** 草稿已装好，可以跳到添加页继续补图 */
    val draftSeeded: StateFlow<Boolean> = _draftSeeded.asStateFlow()

    /**
     * 准备补图：把这条观察的现有照片装进草稿，并标记草稿要写回这条观察。
     *
     * 这是规格书第十四点五节「同一观察的重新识别」的入口。
     * 之所以要把已有照片放回草稿，是因为补图后要「原有照片 + 新增照片」
     * 一起参与重新识别，而不是只识别新加的那几张。
     */
    fun startReanalysis(observation: ObservationWithImages) {
        viewModelScope.launch {
            val images = repository.imagesOfObservation(observation.observation.id)
            if (images.isEmpty()) {
                _message.value = "这条观察没有照片，无法补图"
                return@launch
            }
            draftStore.seedForObservation(observation.observation.id, images)
            _draftSeeded.value = true
        }
    }

    /** 更新某次观察的备注 */
    fun updateNote(observationId: Long, note: String) {
        viewModelScope.launch {
            repository.updateObservationNote(observationId, note)
                .onSuccess { _message.value = "备注已保存" }
                .onFailure { error -> _message.value = error.message ?: "备注保存失败" }
        }
    }

    /** 删除某次观察（连带它的图片文件） */
    fun deleteObservation(observationId: Long) {
        viewModelScope.launch {
            repository.deleteObservation(observationId)
                .onSuccess { _message.value = "已删除这次观察" }
                .onFailure { error -> _message.value = error.message ?: "删除失败" }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeDraftSeeded() {
        _draftSeeded.value = false
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            plantId: Long?,
            observationId: Long?,
            repository: PlantRepository,
            draftStore: CaptureDraftStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ObservationViewModel(plantId, observationId, repository, draftStore) }
        }
    }
}

/** 从观察的原始 AI 返回里取识别结论（用于展示当次识别到的名称与置信度） */
internal fun parseRecognition(raw: String?): RecognitionResult? {
    if (raw.isNullOrBlank()) return null
    return when (val attempt = TolerantJsonParser.parse(raw)) {
        is TolerantJsonParser.ParseAttempt.Success -> attempt.result
        is TolerantJsonParser.ParseAttempt.Degraded -> attempt.result
        is TolerantJsonParser.ParseAttempt.Failed -> null
    }
}
