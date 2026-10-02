package com.plantidentify.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.entity.FolderType
import com.plantidentify.data.repository.FolderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 新建 / 编辑文件夹的表单状态（v1.0.2 Phase 1）。
 *
 * 同一个 ViewModel 服务两条路由：`folderId == null` 是新建，否则是编辑。
 * 这样「字段校验、保存中状态、失败提示」只有一份实现。
 */
class FolderEditViewModel(
    private val repository: FolderRepository,
    private val folderId: Long?,
) : ViewModel() {

    data class UiState(
        /** 编辑模式（决定标题是「新建文件夹」还是「编辑文件夹」） */
        val isEditing: Boolean = false,
        /** 编辑模式下正在读原数据 */
        val loading: Boolean = false,
        val name: String = "",
        val type: FolderType = FolderType.CUSTOM,
        val description: String = "",
        val saving: Boolean = false,
    ) {
        /** 名称必填；保存中不能重复提交 */
        val canSave: Boolean get() = name.isNotBlank() && !saving && !loading
    }

    private val _uiState = MutableStateFlow(UiState(isEditing = folderId != null))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 保存成功或文件夹已不存在时置真 —— 界面据此返回上一页 */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        if (folderId != null) {
            _uiState.update { it.copy(loading = true) }
            viewModelScope.launch {
                val folder = repository.getFolder(folderId)
                if (folder == null) {
                    // 详情页删掉文件夹后又返回到编辑页（或深链接进来）：
                    // 直接说明情况并退出，不要把空表单留给用户去反复保存
                    _message.value = "文件夹不存在，可能已被删除"
                    _finished.value = true
                } else {
                    _uiState.update {
                        it.copy(
                            loading = false,
                            name = folder.name,
                            type = folder.type,
                            description = folder.description.orEmpty(),
                        )
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value) }
    }

    fun onTypeChange(type: FolderType) {
        _uiState.update { it.copy(type = type) }
    }

    fun onDescriptionChange(value: String) {
        _uiState.update { it.copy(description = value) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 保存。成功后置 [finished]，由界面返回上一页 */
    fun save() {
        val state = _uiState.value
        if (!state.canSave) return

        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = if (folderId == null) {
                repository.createFolder(state.name, state.type, state.description)
                    .map { Unit }
            } else {
                repository.updateFolder(folderId, state.name, state.type, state.description)
            }

            result
                .onSuccess {
                    _message.value = if (folderId == null) "已创建文件夹" else "已保存"
                    _finished.value = true
                }
                .onFailure { error ->
                    // 校验失败（名称为空等）或数据库异常：留在页面让用户改，
                    // 不要悄悄返回 —— 那样用户会以为保存成功了
                    _uiState.update { it.copy(saving = false) }
                    _message.value = error.message ?: "保存失败"
                }
        }
    }

    companion object {
        fun factory(
            repository: FolderRepository,
            folderId: Long? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { FolderEditViewModel(repository, folderId) }
        }
    }
}
