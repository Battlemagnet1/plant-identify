package com.plantidentify.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.entity.FolderEntity
import com.plantidentify.data.repository.FolderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「加入文件夹」选择页（v1.0.2 Phase 1）。
 *
 * 与文件夹详情页里的多选不同：这里是**整体替换**语义 ——
 * 保存时按「目标集合与初始集合的差集」写入（`setPlantFolders`），
 * 所以取消勾选就等于把那株植物从对应文件夹移出。
 *
 * 进入时先把「当前所属」读出来预勾选：用户看到的是现状，
 * 改哪里一目了然，而不是面对一个全空的列表从头选一遍。
 */
class FolderPickerViewModel(
    private val repository: FolderRepository,
    private val plantId: Long,
) : ViewModel() {

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** 初始归属是否读完。没读完就允许保存会覆盖掉尚未读到的归属 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    val folders: StateFlow<List<FolderEntity>> = repository.observeAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _selectedIds.value = repository.observeFolderIdsForPlant(plantId).first().toSet()
            _loaded.value = true
        }
    }

    fun toggle(folderId: Long) {
        _selectedIds.value = _selectedIds.value.let { current ->
            if (folderId in current) current - folderId else current + folderId
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun save() {
        if (!_loaded.value) return

        viewModelScope.launch {
            repository.setPlantFolders(plantId, _selectedIds.value.toList())
                .onSuccess { _finished.value = true }
                .onFailure { _message.value = it.message ?: "保存失败" }
        }
    }

    companion object {
        fun factory(
            repository: FolderRepository,
            plantId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { FolderPickerViewModel(repository, plantId) }
        }
    }
}
