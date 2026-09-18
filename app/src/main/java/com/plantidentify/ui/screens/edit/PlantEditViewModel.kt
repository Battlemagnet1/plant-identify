package com.plantidentify.ui.screens.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.repository.PlantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 人工可编辑的字段。
 *
 * 刻意**只包含这几项**：中文名 / 拉丁学名 / 科 / 属 / 植物类型 / 备注。
 * 置信度、百科内容、观察记录都不在列 —— 前三者是 AI 结论与流程产物，
 * 允许随手改写会让档案失去可追溯性（「这个科到底是 AI 给的还是我改的？」）。
 * 用户想纠正 AI 的判断，改分类字段就够了。
 */
data class EditablePlantInfo(
    val name: String = "",
    val latinName: String = "",
    val family: String = "",
    val genus: String = "",
    val category: String = "",
    val note: String = "",
) {
    /** 中文名是唯一必填项：没有名字的档案在列表里无法辨认 */
    val canSave: Boolean get() = name.isNotBlank()
}

/**
 * 植物档案编辑页 ViewModel。
 *
 * 进入时从库中读一次作为初始值，之后由表单自己维护状态 ——
 * 不订阅 Flow 的原因是：用户正在输入时若后台恰好更新了记录，
 * 订阅会把输入框内容冲掉。这类「编辑中被打断」的体验问题很难解释。
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

    /** 原值，用于判断是否真的有改动 */
    private var original: EditablePlantInfo = EditablePlantInfo()

    val hasChanges: StateFlow<Boolean> = _form
        .map { it != original }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = false,
        )

    init {
        viewModelScope.launch {
            repository.observePlantDetail(plantId).first()?.let { detail ->
                val plant = detail.plant
                val info = EditablePlantInfo(
                    name = plant.name,
                    latinName = plant.latinName.orEmpty(),
                    family = plant.family.orEmpty(),
                    genus = plant.genus.orEmpty(),
                    category = plant.category.orEmpty(),
                    note = plant.note.orEmpty(),
                )
                original = info
                _form.value = info
            }
            _loaded.value = true
        }
    }

    fun setName(value: String) = _form.value.let { _form.value = it.copy(name = value) }

    fun setLatinName(value: String) = _form.value.let { _form.value = it.copy(latinName = value) }

    fun setFamily(value: String) = _form.value.let { _form.value = it.copy(family = value) }

    fun setGenus(value: String) = _form.value.let { _form.value = it.copy(genus = value) }

    fun setCategory(value: String) = _form.value.let { _form.value = it.copy(category = value) }

    fun setNote(value: String) = _form.value.let { _form.value = it.copy(note = value) }

    fun save() {
        val current = _form.value
        if (!current.canSave) {
            _message.value = "中文名称不能为空"
            return
        }

        viewModelScope.launch {
            repository.updatePlantInfo(
                plantId = plantId,
                name = current.name,
                latinName = current.latinName,
                family = current.family,
                genus = current.genus,
                category = current.category,
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

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun factory(plantId: Long, repository: PlantRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PlantEditViewModel(plantId, repository) }
            }
    }
}
