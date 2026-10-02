package com.plantidentify.ui.screens.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.import.ImportMergeExecutor
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.data.local.entity.FolderImportStatus
import com.plantidentify.domain.import.ImportDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 导入预览（v1.0.2 Phase 2）。
 *
 * ## 这一页要回答的唯一问题
 *
 * 「这批数据里，哪些是新的、哪些和我的库可能是同一株？」——
 * 然后把**决定权**交给用户。所以页面上的每一条要么是「直接收下」，
 * 要么是「要不要并进某一条」的二选一，不给第三种含糊的选项。
 *
 * ## 默认值为什么是「保留为新」
 *
 * 疑似重复的默认选择是**保守的那一边**（不动已有数据）。
 * 一键「全部合并」看起来很爽，但猜错一次就会把两株不同的植物并掉 ——
 * 而合并是可逆的（被并的那条进回收站），代价却要用户事后自己发现。
 * 所以宁愿让用户多点几下。
 */
class ImportPreviewViewModel(
    private val folderId: Long,
    private val database: PlantIdentifyDatabase,
    private val mergeExecutor: ImportMergeExecutor,
) : ViewModel() {

    /** 用户对一条记录的选择 */
    enum class Choice {
        /** 并入匹配到的本地记录 */
        MERGE,

        /** 保留为一条独立的新记录 */
        KEEP,
    }

    /** 页面上的一行 */
    data class ImportRow(
        val importPlantId: Long,
        val name: String,
        val latinName: String?,
        val decision: ImportDecision,
        val matchedPlantId: Long?,
        val matchedName: String?,
        val reason: String?,
        val status: String,
        val choice: Choice,
    ) {
        /** 需要用户做决定的行（确定重复与新记录都不需要） */
        val needsReview: Boolean
            get() = decision == ImportDecision.SUSPECTED || decision == ImportDecision.CONFLICT

        /** 已经处理过的行（合并过 / 用户已确认保留） */
        val settled: Boolean
            get() = status == FolderImportStatus.MERGED.name ||
                status == FolderImportStatus.KEPT.name
    }

    data class UiState(
        val loading: Boolean = true,
        val sourceName: String = "",
        val importedAt: Long = 0L,
        val totalCount: Int = 0,
        val handledCount: Int = 0,
        val rows: List<ImportRow> = emptyList(),
        /** 候选集被硬上限截断过 —— 界面必须提示「这次没扫全」 */
        val partialHint: String? = null,
        val applying: Boolean = false,
        val message: String? = null,
    ) {
        val pendingCount: Int get() = rows.count { it.needsReview && !it.settled }
        val conclusiveCount: Int get() = rows.count { it.decision == ImportDecision.CONCLUSIVE }
        val newCount: Int get() = rows.count { it.decision == ImportDecision.NEW }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val data = database.folderImportDao().getData(folderId)
            val items = database.folderImportDao().getItems(folderId)
            val plants = database.plantRecordDao()
                .getByIds(items.map { it.importPlantId })
                .associateBy { it.id }
            val localNames = database.plantRecordDao()
                .getByIds(items.mapNotNull { it.matchedPlantId }.distinct())
                .associate { it.id to it.name }

            val rows = items.map { item ->
                val plant = plants[item.importPlantId]
                val decision = decisionOf(item.matchLevel, item.matchedPlantId)
                ImportRow(
                    importPlantId = item.importPlantId,
                    name = plant?.name ?: "（档案已删除）",
                    latinName = plant?.latinName,
                    decision = decision,
                    matchedPlantId = item.matchedPlantId,
                    matchedName = item.matchedPlantId?.let { localNames[it] },
                    reason = null,
                    status = item.status,
                    // 确定重复默认「合并」是安全的 —— level ≤ 2 是拉丁名全同
                    // 或中文名+科+属全同，本地证据足够硬；其余一律保守默认
                    choice = if (decision == ImportDecision.CONCLUSIVE) Choice.MERGE else Choice.KEEP,
                )
            }

            _state.value = UiState(
                loading = false,
                sourceName = data?.sourceName.orEmpty(),
                importedAt = data?.importedAt ?: 0L,
                totalCount = data?.totalCount ?: items.size,
                handledCount = data?.handledCount ?: 0,
                rows = rows,
                partialHint = null,
            )
        }
    }

    fun setChoice(importPlantId: Long, choice: Choice) {
        _state.value = _state.value.copy(
            rows = _state.value.rows.map {
                if (it.importPlantId == importPlantId) it.copy(choice = choice) else it
            },
        )
    }

    /** 把所有「需要确认」的行一次性设为同一种选择 */
    fun setAllPending(choice: Choice) {
        _state.value = _state.value.copy(
            rows = _state.value.rows.map {
                if (it.needsReview && !it.settled) it.copy(choice = choice) else it
            },
        )
    }

    /**
     * 执行全部决定。
     *
     * 只提交**尚未处理**的行：已经 MERGED / KEPT 的重复提交会让
     * 「重算 handledCount」这类统计来回抖动，也可能让用户重复合并。
     */
    fun apply() {
        val current = _state.value
        if (current.applying) return

        val decisions = current.rows
            .filter { !it.settled }
            .mapNotNull { row ->
                when {
                    // 新记录已经好好躺在库里了，没有任何要执行的动作
                    row.decision == ImportDecision.NEW -> null
                    // 选了合并 → 并进匹配到的那条
                    row.choice == Choice.MERGE -> row.importPlantId to row.matchedPlantId
                    // 选了保留（或确定重复被用户改成了保留）→ 只标记状态
                    else -> row.importPlantId to null
                }
            }
            .toMap()

        if (decisions.isEmpty()) {
            _state.value = current.copy(message = "没有需要执行的决定")
            return
        }

        _state.value = current.copy(applying = true)
        viewModelScope.launch {
            val result = mergeExecutor.apply(folderId, decisions)
            _state.value = _state.value.copy(
                applying = false,
                message = result.fold(
                    onSuccess = { r ->
                        buildString {
                            append("已合并 ${r.merged} 条、保留 ${r.kept} 条")
                            if (r.failed.isNotEmpty()) append("；其中 ${r.failed.size} 条失败")
                        }
                    },
                    onFailure = { "执行失败：${it.message ?: "未知原因"}" },
                ),
            )
            refresh()
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun decisionOf(matchLevel: Int?, matchedPlantId: Long?): ImportDecision = when {
        matchedPlantId == null || matchLevel == null -> ImportDecision.NEW
        matchLevel <= 2 -> ImportDecision.CONCLUSIVE
        // 本页把「疑似」与「冲突」合并展示：两者的处置都是「让用户看一眼」，
        // 而具体原因写在判据里（`DuplicateMatcher` 的 reason 会说明
        // 「科或属不一致」这类细节）。区分它们需要多存一列，
        // 对用户的决定没有任何影响
        else -> ImportDecision.SUSPECTED
    }

    companion object {
        fun factory(
            folderId: Long,
            database: PlantIdentifyDatabase,
            mergeExecutor: ImportMergeExecutor,
        ) = viewModelFactory {
            initializer { ImportPreviewViewModel(folderId, database, mergeExecutor) }
        }
    }
}
