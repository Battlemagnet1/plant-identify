package com.plantidentify.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.domain.model.PlantStatistics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 首页 ViewModel。
 *
 * Phase 1 唯一的职责是把数据库统计读出来 —— 它的真正作用是**验证 Room 全链路打通**：
 * Room → DAO(Flow) → Repository(combine) → ViewModel(stateIn) → Compose。
 * 统计三项全部显示 0 且不崩溃，即证明整条链路成立。
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

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(repository: PlantRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}
