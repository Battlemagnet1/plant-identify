package com.plantidentify.ui.screens.data

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.backup.BackupEntry
import com.plantidentify.data.backup.BackupManager
import com.plantidentify.data.backup.BackupManifest
import com.plantidentify.data.export.DataExporter
import com.plantidentify.data.export.ExportEstimate
import com.plantidentify.data.export.ExportMode
import com.plantidentify.data.export.formatBytes
import com.plantidentify.data.location.LocationSettingsStore
import com.plantidentify.data.repository.PlantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** 数据管理页上正在进行的任务 */
sealed interface DataTask {
    data object Idle : DataTask

    /** [progress] 为 null 表示无法给出进度（备份/恢复就是一步） */
    data class Running(val label: String, val progress: Float?) : DataTask

    /** 产出了文件（可分享）或只有一句结论 */
    data class Done(
        val message: String,
        val filePath: String? = null,
        val fileName: String? = null,
    ) : DataTask

    data class Failed(val message: String) : DataTask
}

/** 等待用户确认的恢复操作 */
data class PendingRestore(
    val file: File,
    val manifest: BackupManifest,
)

/**
 * 数据管理（规格书第二十一、二十二节）。
 *
 * ## 为什么导出前一定要先算体积
 *
 * 这是报告里最有价值的一条结论：83 种植物、每株 2 张原图，
 * base64 之后约 660 MB —— 这种 HTML 在手机上根本打不开。
 * 所以页面上先显示预估体积，让用户在选择模式之前就知情。
 *
 * ## 为什么恢复要「先看一眼再确认」
 *
 * 恢复是**替换**语义，会清掉现有全部档案。备份包可能是别的设备、
 * 别的时期的，所以先把包里的条数与时间显示出来，让用户确认这正是他想恢复的那一份。
 */
class DataManagementViewModel(
    private val repository: PlantRepository,
    private val exporter: DataExporter,
    private val backupManager: BackupManager,
    private val locationSettingsStore: LocationSettingsStore,
) : ViewModel() {

    private val _task = MutableStateFlow<DataTask>(DataTask.Idle)
    val task: StateFlow<DataTask> = _task.asStateFlow()

    private val _estimate = MutableStateFlow<ExportEstimate?>(null)
    val estimate: StateFlow<ExportEstimate?> = _estimate.asStateFlow()

    private val _mode = MutableStateFlow(ExportMode.THUMBNAIL)
    val mode: StateFlow<ExportMode> = _mode.asStateFlow()

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    /**
     * 本机已有的备份包。
     *
     * 必须有这个列表：备份落在应用私有目录，系统文件选择器无权浏览那里，
     * 只给「从文件恢复」的话，用户在本机做的备份自己反而恢复不了。
     */
    private val _backups = MutableStateFlow<List<BackupEntry>>(emptyList())
    val backups: StateFlow<List<BackupEntry>> = _backups.asStateFlow()

    /** 位置开关（规格书第十八节） */
    val locationEnabled: StateFlow<Boolean> = locationSettingsStore.choice
        .map { it.enabled }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = false,
        )

    init {
        refreshEstimate()
        refreshBackups()
    }

    /** 重新算体积预估。数据变了（导完一次又加了档案）要重算 */
    fun refreshEstimate() {
        viewModelScope.launch {
            _estimate.value = runCatching { exporter.estimate(repository.loadArchive()) }
                .getOrNull()
        }
    }

    fun refreshBackups() {
        viewModelScope.launch {
            _backups.value = runCatching { backupManager.listBackups() }.getOrDefault(emptyList())
        }
    }

    /** 从本机已有的备份包恢复：直接进入确认流程，不必再走文件选择器 */
    fun prepareRestoreFromLocal(entry: BackupEntry) {
        val manifest = entry.manifest
        if (manifest == null) {
            _task.value = DataTask.Failed("这个备份包无法读取，可能已损坏")
            return
        }
        _pendingRestore.value = PendingRestore(file = entry.file, manifest = manifest)
    }

    fun deleteBackup(entry: BackupEntry) {
        viewModelScope.launch {
            backupManager.deleteBackup(entry.file)
            refreshBackups()
        }
    }

    fun setMode(value: ExportMode) {
        _mode.value = value
    }

    fun setLocationEnabled(enabled: Boolean) {
        viewModelScope.launch { locationSettingsStore.setEnabled(enabled) }
    }

    // ---------------------------------------------------------------- 导出

    fun exportHtml() {
        if (_task.value is DataTask.Running) return
        viewModelScope.launch {
            _task.value = DataTask.Running("正在导出 HTML…", 0f)
            val archive = repository.loadArchive()
            if (archive.isEmpty()) {
                _task.value = DataTask.Failed("还没有植物档案，没有可导出的内容")
                return@launch
            }

            val requested = _mode.value
            exporter.export(
                data = archive,
                appName = APP_NAME,
                requestedMode = requested,
                onProgress = { done, total ->
                    _task.value = DataTask.Running(
                        label = "正在导出 HTML…",
                        progress = if (total > 0) done.toFloat() / total else null,
                    )
                },
            ).onSuccess { result ->
                _task.value = DataTask.Done(
                    message = "导出完成：${result.summary}",
                    filePath = result.filePath,
                    fileName = result.fileName,
                )
                refreshEstimate()
            }.onFailure { error ->
                _task.value = DataTask.Failed(error.message ?: "导出失败，请重试")
            }
        }
    }

    // ---------------------------------------------------------------- 备份

    fun backup() {
        if (_task.value is DataTask.Running) return
        viewModelScope.launch {
            _task.value = DataTask.Running("正在打包备份…", null)
            backupManager.backup()
                .onSuccess { result ->
                    _task.value = DataTask.Done(
                        message = "备份完成：${result.plantCount} 株植物 · " +
                            "${result.observationCount} 次观察 · " +
                            "${result.imageCount} 张照片 · ${formatBytes(result.bytes)}",
                        filePath = result.filePath,
                        fileName = result.fileName,
                    )
                    refreshBackups()
                }
                .onFailure { error ->
                    _task.value = DataTask.Failed(error.message ?: "备份失败，请重试")
                }
        }
    }

    // ---------------------------------------------------------------- 恢复

    /** 用户从文件选择器挑了一个备份包：先复制到本地、读出清单，等用户确认 */
    fun prepareRestore(uri: Uri) {
        if (_task.value is DataTask.Running) return
        viewModelScope.launch {
            _task.value = DataTask.Running("正在读取备份包…", null)
            backupManager.importFromUri(uri)
                .mapCatching { file -> PendingRestore(file, backupManager.inspect(file).getOrThrow()) }
                .onSuccess { pending ->
                    _task.value = DataTask.Idle
                    _pendingRestore.value = pending
                }
                .onFailure { error ->
                    _task.value = DataTask.Failed(error.message ?: "这个文件不是有效的备份包")
                }
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    /**
     * 确认恢复。
     *
     * 替换语义：现有档案会被整体覆盖。恢复完成后刷新体积预估 ——
     * 数据已经换了一批，旧的预估数字没有意义了。
     */
    fun confirmRestore() {
        val pending = _pendingRestore.value ?: return
        _pendingRestore.value = null
        if (_task.value is DataTask.Running) return

        viewModelScope.launch {
            _task.value = DataTask.Running("正在恢复数据…", null)
            backupManager.restore(pending.file)
                .onSuccess { result ->
                    val missingNote = if (result.missingFiles > 0) {
                        "；有 ${result.missingFiles} 张照片在备份包里缺失"
                    } else {
                        ""
                    }
                    _task.value = DataTask.Done(
                        message = "恢复完成：${result.plantCount} 株植物 · " +
                            "${result.observationCount} 次观察 · " +
                            "${result.imageCount} 张照片$missingNote",
                    )
                    refreshEstimate()
                    refreshBackups()
                }
                .onFailure { error ->
                    _task.value = DataTask.Failed(error.message ?: "恢复失败，请重试")
                }
        }
    }

    fun consumeTask() {
        _task.value = DataTask.Idle
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** 报告标题用应用显示名。与 strings.xml 保持一致 */
        private const val APP_NAME = "Plant Identify Library"

        fun factory(
            repository: PlantRepository,
            exporter: DataExporter,
            backupManager: BackupManager,
            locationSettingsStore: LocationSettingsStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DataManagementViewModel(repository, exporter, backupManager, locationSettingsStore)
            }
        }
    }
}
