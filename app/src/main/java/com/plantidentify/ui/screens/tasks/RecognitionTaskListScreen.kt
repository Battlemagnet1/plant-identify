package com.plantidentify.ui.screens.tasks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantidentify.data.local.entity.RecognitionTaskStatus
import com.plantidentify.data.local.projection.TaskCardRow
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.ui.components.LocalImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别任务列表（Phase 8，完整版专属入口）。
 *
 * 本页是任务队列的**唯一操作面**：建任务（系统相册多选）、看状态、
 * 失败重试、取消、以及处理「后台自动挂靠」的归并裁决。
 * 任务编辑（拍照 / 调序 / 部位标注）随后续迭代补齐 —— 先让
 * 「建 → 跑 → 看」这条主链路端到端可用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionTaskListScreen(
    viewModel: RecognitionTaskListViewModel,
    imageStore: ImageStore,
    onBack: () -> Unit,
    onOpenPlantDetail: (Long) -> Unit,
) {
    val cards by viewModel.cards.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var pendingCancel by remember { mutableStateOf<TaskCardRow?>(null) }

    // 系统相册多选（与「添加植物」同一契约：≤5 张，视觉识别的边际收益在 5 张后归零）
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.createTask(uris)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("识别任务") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "批量识别 · 后台逐个执行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }) {
                    Text("新建任务")
                }
            }

            // 状态过滤片
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TaskFilter.entries) { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = {
                            viewModel.setFilter(if (f == TaskFilter.ALL) null else f)
                        },
                        label = { Text(f.label) },
                    )
                }
            }

            val visible = cards.filter { task -> filter?.matches(task.status) ?: true }
            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有识别任务")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点「新建任务」从相册选 1–5 张照片，应用会在后台逐个识别并写入档案。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = { it.id }, contentType = { "task-card" }) { task ->
                        TaskCard(
                            task = task,
                            imageStore = imageStore,
                            onRetry = { viewModel.retry(task.id) },
                            onCancel = { pendingCancel = task },
                            onKeepExisting = { viewModel.resolveMerge(task.id, keepAsNew = false) },
                            onSplitNew = { viewModel.resolveMerge(task.id, keepAsNew = true) },
                            onOpenPlant = {
                                (task.resultPlantId ?: task.pendingMergePlantId)?.let(
                                    onOpenPlantDetail,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    pendingCancel?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text("取消这条任务？") },
            text = { Text("已识别的部分不会写入档案。取消后不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancel(task.id)
                    pendingCancel = null
                }) { Text("取消任务") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancel = null }) { Text("再想想") }
            },
        )
    }
}

/** [TaskFilter] 与状态行的映射。集中一处，加新状态时编译器会逼着改这里 */
private fun TaskFilter.matches(status: RecognitionTaskStatus): Boolean = when (this) {
    TaskFilter.ALL -> true
    TaskFilter.WAITING -> status == RecognitionTaskStatus.PENDING ||
        status == RecognitionTaskStatus.QUEUED
    TaskFilter.RUNNING -> status == RecognitionTaskStatus.PROCESSING ||
        status == RecognitionTaskStatus.ANALYZING
    TaskFilter.DONE -> status == RecognitionTaskStatus.COMPLETED
    TaskFilter.FAILED -> status == RecognitionTaskStatus.FAILED ||
        status == RecognitionTaskStatus.CANCELLED
}

@Composable
private fun TaskCard(
    task: TaskCardRow,
    imageStore: ImageStore,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onKeepExisting: () -> Unit,
    onSplitNew: () -> Unit,
    onOpenPlant: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                task.coverPath?.let { path ->
                    LocalImage(
                        file = imageStore.resolve(path),
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = taskLabel(task),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${task.imageCount} 张照片 · " +
                            SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                                .format(Date(task.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = statusLabel(task.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (task.status) {
                        RecognitionTaskStatus.COMPLETED ->
                            MaterialTheme.colorScheme.primary
                        RecognitionTaskStatus.FAILED ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // 失败原因。直接展示执行体给出的那句话 —— 它已经把
            // 「能不能靠重试解决」翻译成了用户语言
            task.errorMessage?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 后台自动挂靠的裁决提示（§6.2）。用户不在场时的挂靠必须可撤销，
            // 这两条按钮就是「撤销」与「追认」的入口
            if (task.pendingMergePlantId != null) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "结果可能是已有档案（${task.pendingMergeReason.orEmpty()}）。" +
                                "已暂挂到该档案下，请确认：",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onKeepExisting) { Text("确实是同一种") }
                            OutlinedButton(onClick = onSplitNew) { Text("拆分为新植物") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.status == RecognitionTaskStatus.FAILED) {
                    Button(onClick = onRetry) { Text("重试") }
                }
                if (task.status.isBusy || task.status == RecognitionTaskStatus.PENDING) {
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }
                val canOpen = task.status == RecognitionTaskStatus.COMPLETED &&
                    (task.resultPlantId != null || task.pendingMergePlantId != null)
                if (canOpen) {
                    OutlinedButton(onClick = onOpenPlant) { Text("查看档案") }
                }
            }
        }
    }
}

/** 任务标题：现场调查的编号（P-001）优先，普通任务用 #行号 */
private fun taskLabel(task: TaskCardRow): String = when (task.origin) {
    RecognitionTaskEntityOrigin.SURVEY -> task.code ?: "现场调查 #${task.id}"
    else -> "任务 #${task.id}"
}

/** 与 [RecognitionTaskEntityOrigin] 解耦的常量来源，避免 UI 依赖实体的 companion */
private object RecognitionTaskEntityOrigin {
    const val SURVEY = "SURVEY"
}

private fun statusLabel(status: RecognitionTaskStatus): String = when (status) {
    RecognitionTaskStatus.PENDING -> "等待处理"
    RecognitionTaskStatus.QUEUED -> "排队中"
    RecognitionTaskStatus.PROCESSING -> "识别中"
    RecognitionTaskStatus.ANALYZING -> "生成百科中"
    RecognitionTaskStatus.COMPLETED -> "已完成"
    RecognitionTaskStatus.FAILED -> "失败"
    RecognitionTaskStatus.CANCELLED -> "已取消"
}
