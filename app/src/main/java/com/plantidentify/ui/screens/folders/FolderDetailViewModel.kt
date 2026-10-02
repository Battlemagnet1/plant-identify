package com.plantidentify.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.entity.FolderEntity
import com.plantidentify.data.local.projection.PlantCardRow
import com.plantidentify.data.repository.FolderRepository
import com.plantidentify.domain.model.FolderPlantSort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 文件夹详情（v1.0.2 Phase 1）。
 *
 * 除了「看信息 + 看成员」，这里还负责**批量整理的状态机** ——
 * 项目里之前没有任何多选交互，所以选择态的三个问题都在这里定下来：
 *
 * 1. **选择态用一个显式布尔**，而不是「已选集合非空」——
 *    否则用户进入选择态后一旦取消勾选就自动退出，没法「先看看有哪些」。
 * 2. **选中集合按 plantId 而不是列表下标** —— 列表会因为排序 / 搜索而重排，
 *    按下标记会在重排后选中完全不同的植物。
 * 3. **退出选择态一定清空选中**，避免下次进入时带着上次的残留。
 */
class FolderDetailViewModel(
    private val repository: FolderRepository,
    private val folderId: Long,
) : ViewModel() {

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _sort = MutableStateFlow(FolderPlantSort.DEFAULT)
    val sort: StateFlow<FolderPlantSort> = _sort.asStateFlow()

    private val _selectionActive = MutableStateFlow(false)
    val selectionActive: StateFlow<Boolean> = _selectionActive.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 文件夹被删除（或不存在）后置真 —— 界面据此返回上一页 */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    val folder: StateFlow<FolderEntity?> = repository.observeFolder(folderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 供「加入其他文件夹」「移动到其他文件夹」的选择列表用 */
    val allFolders: StateFlow<List<FolderEntity>> = repository.observeAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val plants: StateFlow<List<PlantCardRow>> = combine(_keyword, _sort) { keyword, sort ->
        keyword to sort
    }
        .flatMapLatest { (keyword, sort) ->
            repository.observeFolderPlants(folderId, keyword, sort)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ------------------------------------------------------------------
    // 筛选
    // ------------------------------------------------------------------

    fun onKeywordChange(value: String) {
        _keyword.value = value
    }

    fun onSortSelected(sort: FolderPlantSort) {
        _sort.value = sort
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------------
    // 选择态
    // ------------------------------------------------------------------

    /** 长按某一项：进入选择态并选中它（长按的那一项通常就是用户想要的第一个） */
    fun beginSelection(plantId: Long) {
        _selectionActive.value = true
        _selectedIds.value = setOf(plantId)
    }

    /** 顶栏「选择」：进入选择态但不预选 */
    fun startSelection() {
        _selectionActive.value = true
    }

    fun toggleSelection(plantId: Long) {
        _selectedIds.value = _selectedIds.value.let { current ->
            if (plantId in current) current - plantId else current + plantId
        }
    }

    /**
     * 全选。
     *
     * 传的是**当前界面上看得见的那些**（调用方从已加载的列表取）——
     * 全选一个搜索过滤后的列表却把没显示的也选上，用户按「移除」时
     * 会删掉自己没看到的关联。
     */
    fun selectAll(visibleIds: List<Long>) {
        _selectedIds.value = visibleIds.toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun exitSelection() {
        _selectionActive.value = false
        _selectedIds.value = emptySet()
    }

    // ------------------------------------------------------------------
    // 批量操作
    // ------------------------------------------------------------------

    /** 加入其他文件夹（**保留**当前文件夹里的归属） */
    fun addSelectionToFolders(folderIds: List<Long>) {
        val plantIds = _selectedIds.value.toList()
        if (plantIds.isEmpty() || folderIds.isEmpty()) return

        viewModelScope.launch {
            repository.addPlantsToFolders(folderIds, plantIds)
                .onSuccess { added ->
                    exitSelection()
                    _message.value = if (added > 0) {
                        "已加入 $added 处"
                    } else {
                        "这些植物本来就在所选文件夹里"
                    }
                }
                .onFailure { _message.value = it.message ?: "加入失败" }
        }
    }

    /** 移动到另一个文件夹（**离开**当前文件夹） */
    fun moveSelectionToFolder(targetFolderId: Long) {
        val plantIds = _selectedIds.value.toList()
        if (plantIds.isEmpty()) return

        viewModelScope.launch {
            repository.movePlants(folderId, targetFolderId, plantIds)
                .onSuccess { moved ->
                    exitSelection()
                    _message.value = "已移动 $moved 株"
                }
                .onFailure { _message.value = it.message ?: "移动失败" }
        }
    }

    /**
     * 从本文件夹移除。
     *
     * **只解除关联，不删除植物** —— 需求文档明确要求这一点，
     * 界面上也要用二次确认把这句话说给用户听（见 FolderDetailScreen）。
     */
    fun removeSelection() {
        val plantIds = _selectedIds.value.toList()
        if (plantIds.isEmpty()) return

        viewModelScope.launch {
            repository.removePlantsFromFolder(folderId, plantIds)
                .onSuccess { removed ->
                    exitSelection()
                    _message.value = "已从文件夹移除 $removed 株（植物本身还在）"
                }
                .onFailure { _message.value = it.message ?: "移除失败" }
        }
    }

    // ------------------------------------------------------------------
    // 删除文件夹
    // ------------------------------------------------------------------

    /**
     * 删除本文件夹。**不会删除任何植物** —— Repository 只删关联行与文件夹行。
     */
    fun deleteFolder() {
        viewModelScope.launch {
            repository.deleteFolder(folderId)
                .onSuccess { _finished.value = true }
                .onFailure { _message.value = it.message ?: "删除失败" }
        }
    }

    companion object {
        fun factory(
            repository: FolderRepository,
            folderId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { FolderDetailViewModel(repository, folderId) }
        }
    }
}
