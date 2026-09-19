package com.plantidentify.ui.screens.plants

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.projection.PlantCardRow
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.domain.model.PlantFilters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * 植物列表与搜索的 ViewModel。
 *
 * 首页与搜索页共用同一个 ViewModel，区别只是**初始筛选条件**：
 * 首页给空条件（列出全部），搜索页给用户输入的条件。
 *
 * 这么做的原因：两个页面的数据形状完全一致（同一张卡片的同一种查询），
 * 各写一份必然出现「首页能显示封面图、搜索页忘了取」这类不一致。
 *
 * ## 科/属 候选从**库中真实存在**的值提取
 *
 * 而不是写死一份植物学分类列表 —— 后者必然与用户实际拍到的对不上，
 * 而且会让人怀疑自己选错了分类。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlantListViewModel(
    private val repository: PlantRepository,
    initialFilters: PlantFilters = PlantFilters(),
) : ViewModel() {

    private val _filters = MutableStateFlow(initialFilters)
    val filters: StateFlow<PlantFilters> = _filters.asStateFlow()

    /**
     * 全部植物（不受筛选影响）。
     *
     * 只订阅一次，科/属候选与「库里一共有多少株」都从这里派生 ——
     * 每条派生各自去订阅数据库会造成同一份数据被读多遍。
     */
    val allCards: StateFlow<List<PlantCardRow>> = repository.observePlantCards()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    /** 当前条件下命中的植物 */
    val cards: StateFlow<List<PlantCardRow>> = _filters
        .flatMapLatest { repository.searchPlantCards(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    /** 科候选（去重排序） */
    val families: StateFlow<List<String>> = allCards
        .map { cards -> cards.distinctValuesOf { it.family } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    /** 属候选（去重排序） */
    val genera: StateFlow<List<String>> = allCards
        .map { cards -> cards.distinctValuesOf { it.genus } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    /**
     * 地点候选（Phase 6）。
     *
     * 直接查观察表而不是从 [allCards] 派生 —— 地名挂在**观察**上而非档案上，
     * 一张卡片可能对应多个地点，从卡片投影里取不出来。
     */
    val places: StateFlow<List<String>> = repository.observeLocationNames()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    fun setKeyword(value: String) = _filters.update { it.copy(keyword = value) }

    fun setFamily(value: String) = _filters.update { it.copy(family = value) }

    fun setGenus(value: String) = _filters.update { it.copy(genus = value) }

    fun setPlace(value: String) = _filters.update { it.copy(place = value) }

    fun setDateRange(from: Long?, to: Long?) =
        _filters.update { it.copy(fromDate = from, toDate = to) }

    /** 清掉筛选，但保留关键词（用户多半还想看这个词的结果） */
    fun clearFilters() = _filters.update {
        it.copy(family = "", genus = "", fromDate = null, toDate = null, place = "")
    }

    /** 关键词也一起清掉 */
    fun clearAll() {
        _filters.value = PlantFilters()
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            repository: PlantRepository,
            initialFilters: PlantFilters = PlantFilters(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlantListViewModel(repository, initialFilters) }
        }
    }
}

/** 取某字段的去重非空值并排序 */
private fun List<PlantCardRow>.distinctValuesOf(
    selector: (PlantCardRow) -> String?,
): List<String> = mapNotNull(selector)
    .filter { it.isNotBlank() }
    .distinct()
    .sorted()
