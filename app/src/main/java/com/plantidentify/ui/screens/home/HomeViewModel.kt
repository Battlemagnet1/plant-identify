package com.plantidentify.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.projection.PlantCardRow
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.domain.model.PlantStatistics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 首页 ViewModel。
 *
 * 两件事：统计三项数字，以及**植物档案列表**。
 *
 * 列表查询放在这里而不是让首页再挂一个列表 ViewModel：
 * 首页的列表就是「全部植物」，没有任何筛选状态，
 * 与搜索页（有筛选状态、有输入框）的复杂度不在一个量级。
 * 搜索页另有 [com.plantidentify.ui.screens.plants.PlantListViewModel]。
 */
class HomeViewModel(
    repository: PlantRepository,
) : ViewModel() {

    val statistics: StateFlow<PlantStatistics> = repository.observeStatistics()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = PlantStatistics.EMPTY,
        )

    /** 全部植物，按最近更新排序 */
    val cards: StateFlow<List<PlantCardRow>> = repository.observePlantCards()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(repository: PlantRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}
