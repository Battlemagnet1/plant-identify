package com.plantidentify.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.entity.FolderType
import com.plantidentify.data.local.projection.FolderCardRow
import com.plantidentify.data.repository.FolderRepository
import com.plantidentify.domain.model.FolderFilter
import com.plantidentify.domain.model.FolderSort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 文件夹主页 ViewModel（v1.0.2 Phase 1）。
 *
 * 三个筛选维度（关键词 / 类型 / 排序）各自一个 StateFlow，
 * 用 `combine` + `flatMapLatest` 合成一次查询 —— 这样改排序不会把关键词清掉，
 * 也不必在每次变更时手动触发刷新（Room 的 Flow 会跟着重建）。
 */
class FolderHomeViewModel(
    private val repository: FolderRepository,
) : ViewModel() {

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _selectedType = MutableStateFlow<FolderType?>(null)
    val selectedType: StateFlow<FolderType?> = _selectedType.asStateFlow()

    private val _sort = MutableStateFlow(FolderSort.DEFAULT)
    val sort: StateFlow<FolderSort> = _sort.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val folders: StateFlow<List<FolderCardRow>> = combine(
        _keyword,
        _selectedType,
        _sort,
    ) { keyword, type, sort ->
        FolderFilter(type = type, keyword = keyword) to sort
    }
        .flatMapLatest { (filter, sort) -> repository.observeFolderCards(filter, sort) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 筛选行上的计数徽标。「全部」与各类型分别订阅 */
    val totalCount: StateFlow<Int> = repository.observeTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val countsByType: StateFlow<Map<FolderType, Int>> = combine(
        repository.observeCountByType(FolderType.LANDSCAPE),
        repository.observeCountByType(FolderType.COLLABORATION),
        repository.observeCountByType(FolderType.CUSTOM),
    ) { landscape, collaboration, custom ->
        mapOf(
            FolderType.LANDSCAPE to landscape,
            FolderType.COLLABORATION to collaboration,
            FolderType.CUSTOM to custom,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun onKeywordChange(value: String) {
        _keyword.value = value
    }

    fun onTypeSelected(type: FolderType?) {
        _selectedType.value = type
    }

    fun onSortSelected(sort: FolderSort) {
        _sort.value = sort
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun factory(repository: FolderRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { FolderHomeViewModel(repository) }
        }
    }
}
