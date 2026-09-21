package com.plantidentify.ui.screens.cleaning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.cleaning.AiCleaningAdvisor
import com.plantidentify.data.cleaning.CleaningDataLoader
import com.plantidentify.data.cleaning.CleaningOrchestrator
import com.plantidentify.data.local.dao.CleaningIssueDao
import com.plantidentify.domain.cleaning.CleaningHealth
import com.plantidentify.domain.cleaning.CleaningHealthCalculator
import com.plantidentify.domain.cleaning.CleaningIssue
import com.plantidentify.domain.cleaning.CleaningIssueStatus
import com.plantidentify.domain.cleaning.RecordSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 清洗中心的状态。
 *
 * [snapshots] 把「问题里的 id」翻译成「用户认得的名字」——
 * 只显示 `#12、#38` 的清单是没法用的，用户不知道该不该合并。
 */
data class CleaningUiState(
    val loading: Boolean = true,

    /** 正在扫描（本地，秒级） */
    val scanning: Boolean = false,

    /** 正在跑 AI 复核（可能要十几秒，且花钱） */
    val advising: Boolean = false,

    /** 待处理问题，已按「严重程度 → 相似度」排好序 */
    val issues: List<CleaningIssue> = emptyList(),

    val snapshots: Map<Long, RecordSnapshot> = emptyMap(),

    /**
     * 封面图（plantId → 相对路径）。
     *
     * 放在状态里而不是 ViewModel 的普通字段：普通字段变了不会触发重组，
     * 表现是「列表出来了但缩略图一直是空的」，而且不报错。
     */
    val covers: Map<Long, String> = emptyMap(),

    val health: CleaningHealth = CleaningHealth.PERFECT,

    /** 等待 AI 复核的候选数 —— 「AI 复核」按钮上要显示它 */
    val pendingAi: Int = 0,

    /** 上次扫描的一句总结（如「检查 17 株，新发现 3 项」） */
    val lastScanNote: String? = null,

    val message: String? = null,
)

/**
 * 清洗中心 ViewModel。
 *
 * ## 三个动作的代价差别极大，界面上必须让用户看得出来
 *
 * | 动作 | 代价 | 界面表现 |
 * |---|---|---|
 * | 开始检查 | 本地计算，秒级 | 一个转圈 |
 * | 全库深度检查 | 本地计算，但**忽略增量游标** | 明确标注「忽略上次结果，全部重查」 |
 * | AI 复核 | **花钱**，且要十几秒 | 显示待复核数量，跑完报告调用了几次 |
 *
 * ## 问题列表是**响应式**的，不是一次性读取
 *
 * 这一点在真机上暴露过：合并完一株植物、AI 改了一批结论之后返回本页，
 * 列表还停在上次读到的样子（7 项待处理，其中一条点进去是「问题已不存在」）。
 * 用户唯一的办法是手动再检查一次 —— 而他不一定想得到。
 *
 * 改成订阅 `observeOpen()` 之后，**任何地方**改了问题状态（合并、
 * 忽略、AI 判定、扫描自动结案）列表都会自己更新。
 * 「快照 / 封面图」这类重数据仍是一次性加载的，由 [refresh] 重取。
 */
class CleaningViewModel(
    private val orchestrator: CleaningOrchestrator,
    private val advisor: AiCleaningAdvisor,
    private val issueDao: CleaningIssueDao,
    private val loader: CleaningDataLoader,
) : ViewModel() {

    /** 重数据（快照与封面），只在 [refresh] 时重取 */
    private data class Extras(
        val loading: Boolean = true,
        val snapshots: Map<Long, RecordSnapshot> = emptyMap(),
        val covers: Map<Long, String> = emptyMap(),
    )

    /** 一次性的界面标志与提示 */
    private data class Flags(
        val scanning: Boolean = false,
        val advising: Boolean = false,
        val lastScanNote: String? = null,
        val message: String? = null,
    )

    private val extras = MutableStateFlow(Extras())
    private val flags = MutableStateFlow(Flags())

    val state: StateFlow<CleaningUiState> = combine(
        issueDao.observeOpen(),
        loader.observeRecordCount(),
        extras,
        flags,
    ) { entities, totalRecords, extra, flag ->
        val open = entities.map { it.toDomain() }
        CleaningUiState(
            loading = extra.loading,
            scanning = flag.scanning,
            advising = flag.advising,
            issues = sortForDisplay(open),
            snapshots = extra.snapshots,
            covers = extra.covers,
            health = CleaningHealthCalculator.compute(
                totalRecords = totalRecords,
                issues = open,
                // 只数还在的档案：问题里可能引用着刚被合并掉的株，
                // 那要等下次扫描才结案，期间「涉及 N 株」会大于「共 M 株」
                aliveIds = extra.snapshots.keys,
            ),
            // needsAi 而不是「所有疑似重复」：级 1、2 本地能直接判，
            // 把它们算进「AI 复核（N 组）」会让按钮上的数字虚高，
            // 用户点下去却发现 AI 只是在重复本地已经确定的事
            pendingAi = open.count { it.needsAi && !it.aiUsed },
            lastScanNote = flag.lastScanNote,
            message = flag.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleaningUiState())

    init {
        refresh()
    }

    /**
     * 重取快照与封面图。
     *
     * **不自动扫描**：扫描是用户按下的动作。进页面就自动跑的话，
     * 用户每次只是想看看有没有新问题，却要等一次全库计算（还要算照片哈希）。
     */
    fun refresh() {
        viewModelScope.launch {
            val dataset = loader.load()
            extras.update {
                it.copy(
                    loading = false,
                    snapshots = dataset.byId,
                    covers = loader.coverPaths(dataset.records.map { record -> record.id }),
                )
            }
        }
    }

    fun scan(deep: Boolean = false) {
        if (flags.value.scanning) return
        viewModelScope.launch {
            flags.update { it.copy(scanning = true) }
            val summary = runCatching { orchestrator.run(deep = deep) }
            flags.update { it.copy(scanning = false) }

            flags.update {
                it.copy(
                    lastScanNote = summary.fold(
                        onSuccess = { s ->
                            describe(
                                deep = s.deepScan,
                                checked = s.checkedRecords,
                                added = s.newIssues,
                                resolved = s.resolvedIssues,
                                partial = s.partialScan,
                            )
                        },
                        onFailure = { e -> "检查失败：${e.message ?: "未知错误"}" },
                    ),
                )
            }
            // 扫描可能带来了新的档案（快照要重取）；问题列表由 Flow 自己更新
            refresh()
        }
    }

    /**
     * 让 AI 复核灰区候选。
     *
     * 结论由 Flow 自动反映到列表上，这里只负责把「跑了几次、判了几组」
     * 如实报告给用户 —— 那是一次花钱的操作，用户有权知道结果。
     */
    fun advise() {
        if (flags.value.advising) return
        viewModelScope.launch {
            flags.update { it.copy(advising = true) }
            val outcome = runCatching { advisor.advise() }
            flags.update { it.copy(advising = false) }

            val note = outcome.fold(
                onSuccess = { o ->
                    when (o) {
                        is AiCleaningAdvisor.Outcome.NotConfigured -> o.reason
                        is AiCleaningAdvisor.Outcome.Ran -> buildString {
                            if (o.calls == 0 && o.judged == 0 && o.stale == 0) {
                                append("没有需要 AI 复核的候选")
                            } else {
                                append("AI 复核完成：判定 ${o.judged} 组")
                                if (o.confirmed > 0) append("，其中 ${o.confirmed} 组确认是同一株")
                                if (o.dismissed > 0) append("，${o.dismissed} 组无需处理")
                                if (o.stale > 0) append("，${o.stale} 组涉及的档案已不存在")
                                if (o.skipped > 0) append("；还有 ${o.skipped} 组超出本次上限，可再次复核")
                                if (o.failure != null) append("；有一次调用失败：${o.failure}")
                            }
                        }
                    }
                },
                onFailure = { e -> "AI 复核失败：${e.message ?: "未知错误"}" },
            )
            flags.update { it.copy(message = note) }
        }
    }

    /**
     * 忽略一条问题。
     *
     * 状态是 **IGNORED 而不是 RESOLVED** —— 两者的区别在重新扫描时体现：
     * 已解决意味着问题真的没了（下次扫描不会再出现），
     * 忽略只是「别给我看了」（问题还在，只是不再打扰）。
     * 两者都会从待处理列表里消失，但语义不同，
     * 将来做「已忽略的问题」列表时才能区分开。
     */
    fun ignore(issueId: Long) {
        viewModelScope.launch {
            issueDao.setStatus(issueId, CleaningIssueStatus.IGNORED, System.currentTimeMillis())
            flags.update { it.copy(message = "已忽略") }
        }
    }

    fun consumeMessage() {
        flags.update { it.copy(message = null, lastScanNote = null) }
    }

    private fun describe(
        deep: Boolean,
        checked: Int,
        added: Int,
        resolved: Int,
        partial: Boolean,
    ): String = buildString {
        append(if (deep) "全库检查" else "检查")
        append(" $checked 株：新增 $added 项")
        if (resolved > 0) append("，自动结案 $resolved 项")
        if (partial) append("（候选过多，本次为部分扫描）")
    }

    /**
     * 排序：严重的在前，同级按相似度高的在前。
     *
     * 「严重」而不是「新」在前，是因为列表的用途是**决定先处理哪个**；
     * 按时间排会让一条「观察无主」被十几条「缺科」压在下面。
     */
    private fun sortForDisplay(issues: List<CleaningIssue>): List<CleaningIssue> =
        issues.sortedWith(
            compareByDescending<CleaningIssue> { it.severity.weight }
                .thenByDescending { it.similarity ?: 0.0 }
                .thenBy { it.type.ordinal },
        )

    companion object {
        fun factory(
            orchestrator: CleaningOrchestrator,
            advisor: AiCleaningAdvisor,
            issueDao: CleaningIssueDao,
            loader: CleaningDataLoader,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CleaningViewModel(orchestrator, advisor, issueDao, loader) }
        }
    }
}
