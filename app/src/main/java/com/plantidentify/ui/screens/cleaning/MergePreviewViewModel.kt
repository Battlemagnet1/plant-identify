package com.plantidentify.ui.screens.cleaning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.cleaning.CleaningDataLoader
import com.plantidentify.data.cleaning.CleaningOrchestrator
import com.plantidentify.data.local.dao.CleaningIssueDao
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.domain.cleaning.CleaningIssueStatus
import com.plantidentify.domain.cleaning.MergeField
import com.plantidentify.domain.cleaning.MergePlan
import com.plantidentify.domain.cleaning.MergePlanner
import com.plantidentify.domain.cleaning.RecordSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 合并预览页状态。
 *
 * [keep] / [drop] 两份快照都留着，是因为界面要让用户**逐字段二选一** ——
 * 只有 [plan] 的话，方案里只存了「选中哪一边」，用户看不到另一边的内容，
 * 「改成另一个」这个动作就无从下手。
 */
data class MergePreviewUiState(
    val loading: Boolean = true,
    val plan: MergePlan? = null,
    val keep: RecordSnapshot? = null,
    val drop: RecordSnapshot? = null,
    val covers: Map<Long, String> = emptyMap(),
    val merging: Boolean = false,
    val message: String? = null,

    /** 合并完成 —— 界面据此返回上一页 */
    val done: Boolean = false,

    /** 加载不出方案（问题不是两株档案的疑似重复，或档案已不在） */
    val unavailable: Boolean = false,
)

/**
 * 合并预览 ViewModel。
 *
 * ## 这一页的职责是「让用户看清系统替他做了什么决定」
 *
 * [MergePlanner] 的默认择优规则能覆盖九成情况，但：
 * - 「科」两边冲突时，只有用户知道哪个对
 * - 「保留哪一株」影响很大（保留下来的那份观察历史是主档），必须能改
 *
 * 所以这里提供两个能力：**换边**（谁活下来）与**逐字段改选**。
 */
class MergePreviewViewModel(
    private val issueId: Long,
    private val orchestrator: CleaningOrchestrator,
    private val repository: PlantRepository,
    private val issueDao: CleaningIssueDao,
    private val loader: CleaningDataLoader,
) : ViewModel() {

    private val _state = MutableStateFlow(MergePreviewUiState())
    val state: StateFlow<MergePreviewUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val plan = orchestrator.loadMergePlan(issueId)
            if (plan == null) {
                _state.update { it.copy(loading = false, unavailable = true) }
                return@launch
            }
            val dataset = loader.load()
            val keep = dataset.recordById(plan.keepId)
            val drop = dataset.recordById(plan.dropId)
            if (keep == null || drop == null) {
                _state.update { it.copy(loading = false, unavailable = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    plan = plan,
                    keep = keep,
                    drop = drop,
                    covers = loader.coverPaths(listOf(keep.id, drop.id)),
                )
            }
        }
    }

    /**
     * 换边：让另一株活下来。
     *
     * **必须重新生成方案而不是简单对调 keep/drop**：字段择优规则不是对称的
     * （名称取保留侧、备注拼接顺序、俗称并集顺序都跟方向有关），
     * 对调两个 id 会让「哪些字段变了」与界面上显示的来源对不上。
     */
    fun swapSides() {
        val state = _state.value
        val keep = state.keep ?: return
        val drop = state.drop ?: return
        _state.update {
            it.copy(
                keep = drop,
                drop = keep,
                plan = MergePlanner.plan(keep = drop, drop = keep),
            )
        }
    }

    /**
     * 某个字段改选另一边的值。
     *
     * 传 [MergeField.NAME] 时也允许 —— 虽然默认不按长度选，
     * 但用户可能确实想让被合并那株的名字留下来。
     */
    fun chooseValue(field: MergeField, value: String?) {
        val plan = _state.value.plan ?: return
        _state.update { it.copy(plan = plan.withChoice(field, value)) }
    }

    /** 恢复某个字段的自动择优结果 */
    fun resetField(field: MergeField) {
        val state = _state.value
        val keep = state.keep ?: return
        val drop = state.drop ?: return
        val auto = MergePlanner.plan(keep, drop)
        val plan = state.plan ?: return
        _state.update { it.copy(plan = plan.copy(fields = plan.fields + (field to auto.fields.getValue(field)))) }
    }

    /**
     * 执行合并。
     *
     * 顺序不能反：先落库、成功了再结案问题。反过来会在合并失败时
     * 留下「问题说已解决、档案其实没合」的状态，而用户没有任何线索。
     */
    fun confirm() {
        val plan = _state.value.plan ?: return
        if (_state.value.merging) return

        viewModelScope.launch {
            _state.update { it.copy(merging = true) }
            repository.mergeInto(plan)
                .onSuccess {
                    issueDao.setStatus(issueId, CleaningIssueStatus.RESOLVED, System.currentTimeMillis())
                    _state.update { it.copy(merging = false, done = true) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(merging = false, message = error.message ?: "合并失败，请重试")
                    }
                }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory(
            issueId: Long,
            orchestrator: CleaningOrchestrator,
            repository: PlantRepository,
            issueDao: CleaningIssueDao,
            loader: CleaningDataLoader,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MergePreviewViewModel(issueId, orchestrator, repository, issueDao, loader)
            }
        }
    }
}
