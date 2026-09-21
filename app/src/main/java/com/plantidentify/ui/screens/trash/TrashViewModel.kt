package com.plantidentify.ui.screens.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.repository.PlantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 回收站 ViewModel。
 *
 * 只做三件事：列出来、恢复、彻底删除。刻意**不做批量选择** ——
 * 回收站的价值在于「手滑了能撤回」，批量删除反而放大了不可逆操作的风险面。
 */
class TrashViewModel(
    private val repository: PlantRepository,
) : ViewModel() {

    val plants: StateFlow<List<PlantRecordEntity>> = repository.observeDeletedPlants()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun restore(plantId: Long) {
        viewModelScope.launch {
            repository.restorePlant(plantId)
                .onSuccess { _message.value = "已恢复，回到植物档案" }
                .onFailure { _message.value = it.message ?: "恢复失败" }
        }
    }

    /**
     * 彻底删除单株。
     *
     * 这个操作**不可撤销**，且会连同照片一起从磁盘消失 ——
     * 界面上必须走二次确认（见 TrashScreen）。
     */
    fun purge(plantId: Long) {
        viewModelScope.launch {
            repository.purgePlant(plantId)
                .onSuccess { _message.value = "已彻底删除" }
                .onFailure { _message.value = it.message ?: "删除失败" }
        }
    }

    fun purgeAll() {
        viewModelScope.launch {
            repository.purgeAllDeleted()
                .onSuccess { count -> _message.value = "已清空回收站（$count 株）" }
                .onFailure { _message.value = it.message ?: "清空失败" }
        }
    }

    companion object {
        fun factory(repository: PlantRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { TrashViewModel(repository) }
        }
    }
}
