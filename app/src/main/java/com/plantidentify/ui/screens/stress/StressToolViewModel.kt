package com.plantidentify.ui.screens.stress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.local.dao.CleaningIssueDao
import com.plantidentify.data.local.dao.ObservationImageDao
import com.plantidentify.data.local.dao.PlantObservationDao
import com.plantidentify.data.local.dao.PlantRecordDao
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.data.stress.StressDataSeeder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 压测造数据页的 ViewModel（**仅 debug 构建可达**，见 `SettingsScreen`）。
 *
 * 它只做两件事：造数据和清数据 —— 加上把结果记成一段可回看的日志。
 * 日志不放 Snackbar 是因为造 10000 株要几十秒，用户会切出去看别的，
 * 回来时 Snackbar 早没了，而「上一次到底造了多少、花了多久」正是压测要记的东西。
 */
class StressToolViewModel(
    private val seeder: StressDataSeeder,
    private val imageStore: ImageStore,
    private val plantRecordDao: PlantRecordDao,
    private val plantObservationDao: PlantObservationDao,
    private val observationImageDao: ObservationImageDao,
    private val cleaningIssueDao: CleaningIssueDao,
) : ViewModel() {

    /** 库里的现状 —— 每档压测前后各看一次，才能算出这一档真正增加了多少 */
    data class Counts(
        val plants: Int = 0,
        val observations: Int = 0,
        val images: Int = 0,
        val openIssues: Int = 0,
        val imageBytes: Long = 0L,
    )

    data class State(
        val busy: Boolean = false,

        /** 造数据时的「已完成 / 总数」，空闲时为空串 */
        val progress: String = "",

        /** 操作日志（最新的在最后）。只保留最近若干条，避免长时间压测后无限增长 */
        val lines: List<String> = emptyList(),

        val counts: Counts = Counts(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refreshCounts()
    }

    fun seed(count: Int) = perform("造 $count 株") {
        val report = seeder.seed(count) { done, total ->
            _state.update { it.copy(progress = "$done / $total") }
        }
        "✓ 造完 ${report.plants} 株 / ${report.observations} 次观察 / " +
            "${report.images} 张照片 · 耗时 ${"%.1f".format(report.elapsedMs / 1000.0)} 秒 · " +
            "图片 ${formatBytes(report.imageBytes)}"
    }

    fun clear() = perform("清空全部数据") {
        val report = seeder.clearAll()
        "✓ 已清空 8 张表 · 释放 ${formatBytes(report.freedBytes)}"
    }

    fun refreshCounts() {
        viewModelScope.launch {
            val counts = Counts(
                plants = plantRecordDao.observeDistinctPlantCount().first(),
                observations = plantObservationDao.observeObservationCount().first(),
                images = observationImageDao.observeImageCount().first(),
                openIssues = cleaningIssueDao.countOpen(),
                imageBytes = imageStore.usedBytes(),
            )
            _state.update { it.copy(counts = counts) }
        }
    }

    /**
     * 跑一件耗时的事。
     *
     * 三处刻意的设计：
     * - **busy 时直接返回**：并发生成两份数据会让计数彻底失去意义，
     *   而且两次 `clearAll()` 交错会把刚插的行删掉一半
     * - 失败也要**记进日志**（`runCatching`），不能只在界面上弹一下 ——
     *   压测里最有价值的往往就是「哪一档失败了、失败在多少条」
     * - 结束后**刷新计数**：不管成功还是失败，库的状态都可能已经变了
     */
    private fun perform(title: String, block: suspend () -> String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, progress = "", lines = (it.lines + "▶ $title…").takeLast(MAX_LINES))
            }
            val outcome = runCatching { block() }
            val line = outcome.getOrElse { error ->
                "✗ $title 失败：${error::class.simpleName} ${error.message.orEmpty()}"
            }
            _state.update {
                it.copy(
                    busy = false,
                    progress = "",
                    lines = (it.lines + line).takeLast(MAX_LINES),
                )
            }
            refreshCounts()
        }
    }

    companion object {
        private const val MAX_LINES = 40

        fun factory(
            seeder: StressDataSeeder,
            imageStore: ImageStore,
            plantRecordDao: PlantRecordDao,
            plantObservationDao: PlantObservationDao,
            observationImageDao: ObservationImageDao,
            cleaningIssueDao: CleaningIssueDao,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                StressToolViewModel(
                    seeder = seeder,
                    imageStore = imageStore,
                    plantRecordDao = plantRecordDao,
                    plantObservationDao = plantObservationDao,
                    observationImageDao = observationImageDao,
                    cleaningIssueDao = cleaningIssueDao,
                )
            }
        }
    }
}

/** 人类可读的字节数。压测报告里要写图片目录占了多少，所以别只给字节 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
