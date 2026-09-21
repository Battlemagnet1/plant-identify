package com.plantidentify.ui.screens.cleaning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.cleaning.CleaningDataLoader
import com.plantidentify.data.local.dao.CleaningIssueDao
import com.plantidentify.domain.cleaning.CleaningIssue
import com.plantidentify.domain.cleaning.CleaningIssueStatus
import com.plantidentify.domain.cleaning.RecordSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 问题详情页状态。
 *
 * [gone] 是一个**独立于 loading 的标志**：问题可能在用户看着列表的时候
 * 被另一次扫描或 AI 复核结案了（页面还停在栈上）。这时不该显示空白页，
 * 而要明确说「这条问题已经处理掉了」—— 空白页会让用户以为是应用坏了。
 */
data class IssueDetailUiState(
    val loading: Boolean = true,
    val issue: CleaningIssue? = null,

    /** 涉及的档案快照（疑似重复是 2 条，字段类问题通常是 1 条） */
    val records: List<RecordSnapshot> = emptyList(),

    val covers: Map<Long, String> = emptyMap(),

    /** 涉及的档案已经不在（被合并或删除） */
    val gone: Boolean = false,

    val message: String? = null,
)

class CleaningIssueDetailViewModel(
    private val issueId: Long,
    private val issueDao: CleaningIssueDao,
    private val loader: CleaningDataLoader,
) : ViewModel() {

    private val _state = MutableStateFlow(IssueDetailUiState())
    val state: StateFlow<IssueDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val entity = issueDao.getById(issueId)
            if (entity == null) {
                _state.update { it.copy(loading = false, gone = true) }
                return@launch
            }

            val issue = entity.toDomain()
            val dataset = loader.load()
            val records = issue.recordIds.mapNotNull { dataset.recordById(it) }

            _state.update {
                it.copy(
                    loading = false,
                    issue = issue,
                    records = records,
                    // 一条都取不到 = 涉及的档案都没了（多半是刚被合并掉）
                    gone = records.isEmpty(),
                    covers = loader.coverPaths(records.map { r -> r.id }),
                )
            }
        }
    }

    fun ignore() {
        viewModelScope.launch {
            issueDao.setStatus(issueId, CleaningIssueStatus.IGNORED, System.currentTimeMillis())
            _state.update { it.copy(message = "已忽略，不再提示这一条") }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory(
            issueId: Long,
            issueDao: CleaningIssueDao,
            loader: CleaningDataLoader,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CleaningIssueDetailViewModel(issueId, issueDao, loader) }
        }
    }
}
