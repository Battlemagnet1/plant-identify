package com.plantidentify.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.projection.TaskCardRow
import com.plantidentify.data.recognition.RecognitionQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 任务列表页 ViewModel。
 *
 * 列表数据只来自任务表（[RecognitionQueue.observeTaskCards]）——
 * **永远不读 WorkManager 的状态**。WorkManager 负责「什么时候跑」，
 * 任务行负责「跑到哪了」，界面只认后者（方案 §5 的职责边界）。
 */
class RecognitionTaskListViewModel(
    private val queue: RecognitionQueue,
) : ViewModel() {

    val cards: StateFlow<List<TaskCardRow>> = queue.observeTaskCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 操作反馈（Snackbar 用）。一次性事件，消费后置空 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** 当前选中的状态过滤（null = 全部）。过滤在内存里做 —— 列表量级撑得起 */
    private val _filter = MutableStateFlow<TaskFilter?>(null)
    val filter: StateFlow<TaskFilter?> = _filter.asStateFlow()

    fun setFilter(filter: TaskFilter?) {
        _filter.value = filter
    }

    fun createTask(uris: List<android.net.Uri>) {
        viewModelScope.launch {
            queue.createTask(uris)
                .onSuccess { _message.value = "已加入识别队列" }
                .onFailure { _message.value = it.message ?: "创建任务失败" }
        }
    }

    fun retry(taskId: Long) {
        viewModelScope.launch {
            queue.retryNow(taskId)
        }
    }

    fun cancel(taskId: Long) {
        viewModelScope.launch {
            queue.cancel(taskId)
        }
    }

    fun resolveMerge(taskId: Long, keepAsNew: Boolean) {
        viewModelScope.launch {
            queue.resolveMerge(taskId, keepAsNew)
                .onSuccess { _message.value = if (keepAsNew) "已拆分为新档案" else "已确认为同一种" }
                .onFailure { _message.value = it.message ?: "操作失败" }
        }
    }

    companion object {
        fun factory(queue: RecognitionQueue): ViewModelProvider.Factory = viewModelFactory {
            initializer { RecognitionTaskListViewModel(queue) }
        }
    }
}

/** 列表的状态过滤片。`ALL` 显示全部 */
enum class TaskFilter(val label: String) {
    ALL("全部"),
    WAITING("待处理"),
    RUNNING("识别中"),
    DONE("已完成"),
    FAILED("失败"),
    ;
}
