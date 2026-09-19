package com.plantidentify.ui.screens.stats

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
 * 统计页（规格书第二十节）。
 *
 * 五项指标全部来自三张表的行数，不做任何本地累计 ——
 * 「累计值存在别处」是这类页面最常见的失真来源：一旦某次写入失败或
 * 用户手工删过数据，计数器与真实数据就永久对不上了。
 * 直接数行数，代价是一次 COUNT 查询，换来的是永远与档案一致。
 */
class StatsViewModel(
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
            initializer { StatsViewModel(repository) }
        }
    }
}
