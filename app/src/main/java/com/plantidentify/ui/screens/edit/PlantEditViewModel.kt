package com.plantidentify.ui.screens.edit

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.relation.PlantWithObservationsAndImages
import com.plantidentify.data.location.placeText
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.ui.components.label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 人工可编辑的字段 —— **档案里所有给人看的内容**。
 *
 * ## 为什么从「只改 6 项」放开到全部
 *
 * 早期版本刻意只开放名称/学名/科/属/类型/备注，理由是「置信度与百科是 AI 结论，
 * 允许改写会让档案失去可追溯性」。用下来这条原则站不住：
 * **AI 的确会认错**，而用户手上就拿着那株植物。
 * 让他为了保全 AI 的原始输出而留着一个错的名字，是把工程洁癖凌驾于现实之上。
 *
 * 可追溯性另有保障：**每次识别的模型原始返回都原封不动存在
 * `PlantObservation.aiResultJson` 里**。档案上的字段是「当前认为正确的结论」，
 * 观察里的 JSON 是「模型当初说了什么」—— 两者的职责本来就不同，
 * 不必让前者为后者让路。
 *
 * ## 仍然不在这里改的
 *
 * - `analysisStatus`：分析流程的状态机，改它没有意义
 * - 观察的**时间与地点**：那是「某次观察在哪儿」的事实记录，不是 AI 的判断
 * - 照片的**拍摄部位标注**：它只参与识别 prompt，改了对已有档案无影响
 */
data class EditablePlantInfo(
    val name: String = "",
    val commonNames: String = "",
    val latinName: String = "",
    val family: String = "",
    val genus: String = "",
    val category: String = "",

    /**
     * 置信度按**百分数**编辑（0–100）。
     *
     * 界面上让用户填 91 比填 0.91 自然得多；换算在 ViewModel 里一次做完，
     * 免得每个输入框各自记得除 100。
     */
    val confidencePercent: String = "",

    val description: String = "",
    val morphologicalFeatures: String = "",
    val growthHabits: String = "",
    val floweringPeriod: String = "",
    val fruitingPeriod: String = "",
    val landscapeUses: String = "",
    val careAdvice: String = "",
    val pestControl: String = "",
    val note: String = "",
) {
    /** 中文名是唯一必填项：没有名字的档案在列表里无法辨认 */
    val canSave: Boolean get() = name.isNotBlank() && confidenceValid

    /**
     * 置信度要么留空（视为 0），要么是 0–100 的整数。
     *
     * 拦在输入框这里而不是写库时兜底 —— 用户填了「95%」这种带符号的内容，
     * 应该当场看到保存按钮不可用，而不是某天发现置信度变成了 0。
     */
    val confidenceValid: Boolean
        get() {
            val raw = confidencePercent.trim()
            if (raw.isEmpty()) return true
            val value = raw.toIntOrNull() ?: return false
            return value in 0..100
        }

    /** 换算回库里存的 0.0–1.0 */
    val confidenceFraction: Double
        get() = (confidencePercent.trim().toIntOrNull() ?: 0).coerceIn(0, 100) / 100.0
}

/** 编辑页里的一张照片 */
data class EditableImage(
    val id: Long,
    val imagePath: String,
    /** 拍摄部位的中文名（未标注 / 叶片 / 花…） */
    val roleLabel: String,
)

/** 编辑页里的一组照片（按观察分组） */
data class EditableObservation(
    val observationId: Long,
    val timeText: String,
    val place: String?,
    val images: List<EditableImage>,
)

/**
 * 植物档案编辑页 ViewModel。
 *
 * ## 表单为什么只读一次
 *
 * 文本字段进入时从库中读一次作为初始值，之后由表单自己维护 ——
 * 不订阅 Flow 是因为：用户正在输入时若后台恰好更新了记录，
 * 订阅会把输入框里的字冲掉。这类「编辑中被打断」的体验问题很难解释。
 *
 * ## 照片为什么反过来要订阅
 *
 * 照片是**即时生效**的（增删当场落库），不存在「还在编辑中」的中间态。
 * 订阅才能让删掉的那张立刻从列表里消失。
 */
class PlantEditViewModel(
    private val plantId: Long,
    private val repository: PlantRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(EditablePlantInfo())
    val form: StateFlow<EditablePlantInfo> = _form.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 正在导入照片（复制文件可能耗时），用来禁用按钮防连点 */
    private val _photoBusy = MutableStateFlow(false)
    val photoBusy: StateFlow<Boolean> = _photoBusy.asStateFlow()

    /** 原值，用于判断是否真的有改动 */
    private var original: EditablePlantInfo = EditablePlantInfo()

    val hasChanges: StateFlow<Boolean> = _form
        .map { it != original }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = false,
        )

    /** 照片按观察分组（新的在上） */
    val photos: StateFlow<List<EditableObservation>> = repository.observePlantDetail(plantId)
        .map { detail -> detail.toEditableObservations() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    init {
        viewModelScope.launch {
            repository.observePlantDetail(plantId).first()?.let { detail ->
                val plant = detail.plant
                val info = EditablePlantInfo(
                    name = plant.name,
                    commonNames = plant.commonNames.orEmpty(),
                    latinName = plant.latinName.orEmpty(),
                    family = plant.family.orEmpty(),
                    genus = plant.genus.orEmpty(),
                    category = plant.category.orEmpty(),
                    confidencePercent = (plant.confidence * 100).toInt().toString(),
                    description = plant.description.orEmpty(),
                    morphologicalFeatures = plant.morphologicalFeatures.orEmpty(),
                    growthHabits = plant.growthHabits.orEmpty(),
                    floweringPeriod = plant.floweringPeriod.orEmpty(),
                    fruitingPeriod = plant.fruitingPeriod.orEmpty(),
                    landscapeUses = plant.landscapeUses.orEmpty(),
                    careAdvice = plant.careAdvice.orEmpty(),
                    pestControl = plant.pestControl.orEmpty(),
                    note = plant.note.orEmpty(),
                )
                original = info
                _form.value = info
            }
            _loaded.value = true
        }
    }

    // ---------------- 表单 ----------------

    fun update(transform: EditablePlantInfo.() -> EditablePlantInfo) {
        _form.value = _form.value.transform()
    }

    fun save() {
        val current = _form.value
        if (!current.canSave) {
            _message.value = if (current.name.isBlank()) {
                "中文名称不能为空"
            } else {
                "置信度请填 0–100 之间的整数"
            }
            return
        }

        viewModelScope.launch {
            repository.updatePlantInfo(
                plantId = plantId,
                name = current.name,
                latinName = current.latinName,
                commonNames = current.commonNames,
                family = current.family,
                genus = current.genus,
                category = current.category,
                confidence = current.confidenceFraction,
                description = current.description,
                morphologicalFeatures = current.morphologicalFeatures,
                growthHabits = current.growthHabits,
                floweringPeriod = current.floweringPeriod,
                fruitingPeriod = current.fruitingPeriod,
                landscapeUses = current.landscapeUses,
                careAdvice = current.careAdvice,
                pestControl = current.pestControl,
                note = current.note,
            ).onSuccess {
                // 保存后更新基线，避免 hasChanges 一直为 true
                original = current
                _saved.value = true
            }.onFailure { error ->
                _message.value = error.message ?: "保存失败，请重试"
            }
        }
    }

    // ---------------- 照片 ----------------

    /**
     * 往某次观察里追加照片。
     *
     * 走的是**纯本地**路径（导入文件 + 插一行），不需要重跑 AI ——
     * 用户想做的可能只是「当时漏传了一张叶子」，为此付一次 API 调用、
     * 外加结论被改写的风险，代价不对等。
     */
    fun addPhotos(observationId: Long, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _photoBusy.value = true
            repository.addImagesToObservation(observationId, uris)
                .onSuccess { count -> _message.value = "已添加 $count 张照片" }
                .onFailure { error -> _message.value = error.message ?: "添加照片失败" }
            _photoBusy.value = false
        }
    }

    /** 删除一张照片。删最后一张会被仓库拒绝，原因照原话显示给用户 */
    fun deletePhoto(imageId: Long) {
        viewModelScope.launch {
            repository.deleteObservationImage(imageId)
                .onSuccess { _message.value = "已删除这张照片" }
                .onFailure { error -> _message.value = error.message ?: "删除失败" }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(plantId: Long, repository: PlantRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PlantEditViewModel(plantId, repository) }
            }
    }
}

/** 详情模型 → 照片分组。时间取 `yyyy-MM-dd`：编辑页不需要精确到分 */
private fun PlantWithObservationsAndImages?.toEditableObservations(): List<EditableObservation> {
    val observations = this?.observations.orEmpty()
    return observations
        .sortedByDescending { it.observation.timestamp }
        .map { item ->
            EditableObservation(
                observationId = item.observation.id,
                timeText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(item.observation.timestamp)),
                place = placeText(
                    locationName = item.observation.locationName,
                    latitude = item.observation.latitude,
                    longitude = item.observation.longitude,
                ),
                images = item.images.map { image ->
                    EditableImage(
                        id = image.id,
                        imagePath = image.imagePath,
                        roleLabel = image.role.label,
                    )
                },
            )
        }
}
