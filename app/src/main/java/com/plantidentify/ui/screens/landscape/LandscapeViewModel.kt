package com.plantidentify.ui.screens.landscape

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.plantidentify.data.export.PdfReportBuilder
import com.plantidentify.data.export.PdfReportFactory
import com.plantidentify.data.landscape.LandscapeAiAdvisor
import com.plantidentify.data.repository.LandscapeRepository
import com.plantidentify.domain.landscape.LandscapeStatistics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 景观文件夹管理页的状态（v1.0.2 Phase 3）。
 *
 * 这一页承担需求 §七「景观文件夹详情」的职责：场地信息、景观照片、
 * 配置统计、AI 分析与报告导出。植物成员的增删仍走通用的文件夹详情页 ——
 * 「管理植物」与「管理景观」是两件事，混在一个页面会两边都做不好。
 */
class LandscapeViewModel(
    private val folderId: Long,
    private val folderName: String,
    private val repository: LandscapeRepository,
    private val advisor: LandscapeAiAdvisor,
    private val pdfBuilder: PdfReportBuilder,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val folderName: String = "",
        val location: String = "",
        val projectType: String = "",
        val description: String = "",
        val analysisResult: String? = null,
        val analysisModel: String? = null,
        val analysisUpdatedAt: Long? = null,
        val analysisVersion: Int? = null,
        val needsReanalysis: Boolean = false,
        val stats: LandscapeStatistics? = null,
        val busy: String? = null,
        val message: String? = null,
        /** 导出完成的 PDF 文件，界面据此弹「分享」 */
        val exportedPdf: File? = null,
    )

    private val _state = MutableStateFlow(UiState(folderName = folderName))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val images: StateFlow<List<com.plantidentify.data.local.entity.FolderImageEntity>> =
        repository.observeImages(folderId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val data = repository.getData(folderId)
            val stats = repository.statisticsOf(folderId)
            _state.value = _state.value.copy(
                loading = false,
                location = data?.location.orEmpty(),
                projectType = data?.projectType.orEmpty(),
                description = data?.landscapeDescription.orEmpty(),
                analysisResult = data?.analysisResult,
                analysisModel = data?.analysisModel,
                analysisUpdatedAt = data?.analysisUpdatedAt,
                analysisVersion = data?.analysisVersion,
                needsReanalysis = data?.needsReanalysis ?: false,
                stats = stats,
            )
        }
    }

    /** 保存场地信息。保存成员相关变化时顺带标记「需要重新分析」由合并路径负责 */
    fun saveProfile(location: String, projectType: String, description: String) {
        viewModelScope.launch {
            repository.saveProfile(folderId, location, projectType, description)
                .onSuccess { refresh() }
                .onFailure { _state.value = _state.value.copy(message = it.message) }
        }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在添加照片…")
            var added = 0
            uris.forEach { uri ->
                repository.addImage(folderId, uri).onSuccess { added++ }
            }
            _state.value = _state.value.copy(
                busy = null,
                message = if (added == uris.size) "已添加 $added 张照片"
                else "添加了 $added / ${uris.size} 张（部分失败）",
            )
        }
    }

    fun removeImage(imageId: Long) {
        viewModelScope.launch {
            val target = images.value.firstOrNull { it.id == imageId } ?: return@launch
            repository.removeImage(target)
                .onFailure { _state.value = _state.value.copy(message = it.message) }
        }
    }

    /** 保存照片说明。修改照片**不**影响统计（统计只看植物），不必标记重分析 */
    fun updateCaption(imageId: Long, caption: String) {
        viewModelScope.launch {
            repository.updateImage(imageId, caption, kindOf(imageId), folderId)
        }
    }

    private fun kindOf(imageId: Long) =
        images.value.firstOrNull { it.id == imageId }?.let {
            com.plantidentify.data.local.entity.FolderImageKind.entries
                .firstOrNull { k -> k.name == it.kind }
        } ?: com.plantidentify.data.local.entity.FolderImageKind.OTHER

    /**
     * 跑 AI 景观分析。
     *
     * 花钱且耗时（可能 30 秒以上），所以按钮按下后整页进入 busy 态，
     * 防止用户连点两下产生两次付费请求。
     */
    fun runAnalysis() {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "AI 正在分析…（可能需要半分钟）")
            advisor.analyze(folderId, folderName)
                .onSuccess {
                    _state.value = _state.value.copy(busy = null)
                    refresh()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = null,
                        message = "分析失败：${it.message ?: "未知原因"}",
                    )
                }
        }
    }

    fun exportPdf() {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            val current = _state.value
            val stats = current.stats ?: return@launch
            _state.value = current.copy(busy = "正在生成 PDF…")
            // 植物明细也要进报告（需求 §十四：景观 PDF 含植物列表与观察数据）
            val plants = repository.plantsWithDetails(folderId)
            val report = PdfReportFactory.landscapeReport(
                folder = com.plantidentify.data.local.entity.FolderEntity(
                    id = folderId,
                    name = folderName,
                    type = com.plantidentify.data.local.entity.FolderType.LANDSCAPE,
                    createdAt = 0L,
                    updatedAt = System.currentTimeMillis(),
                ),
                data = repository.getData(folderId),
                stats = stats,
                aiAnalysis = current.analysisResult,
                items = plants,
            )
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val file = File(pdfBuilder.exportDir(), "景观报告_${folderName}_$stamp.pdf")
            pdfBuilder.build(report, file)
                .onSuccess {
                    _state.value = _state.value.copy(busy = null, exportedPdf = it)
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = null,
                        message = "生成失败：${it.message ?: "未知原因"}",
                    )
                }
        }
    }

    fun consumeExported() {
        _state.value = _state.value.copy(exportedPdf = null)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    companion object {
        fun factory(
            folderId: Long,
            folderName: String,
            repository: LandscapeRepository,
            advisor: LandscapeAiAdvisor,
            pdfBuilder: PdfReportBuilder,
        ) = viewModelFactory {
            initializer {
                LandscapeViewModel(folderId, folderName, repository, advisor, pdfBuilder)
            }
        }
    }
}
