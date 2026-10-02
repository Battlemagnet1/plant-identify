package com.plantidentify.ui.screens.landscape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantidentify.domain.landscape.LandscapeStatistics
import com.plantidentify.ui.components.LocalImage
import com.plantidentify.ui.components.rememberDateFormatter
import com.plantidentify.data.storage.ImageStore
import java.util.Date

/**
 * 景观文件夹管理页（v1.0.2 Phase 3）。
 *
 * 植物成员的增删不在这里 —— 那是通用文件夹详情页的事。
 * 本页只管景观特有的四块：场地信息、景观照片、配置统计、AI 分析。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandscapeScreen(
    viewModel: LandscapeViewModel,
    imageStore: ImageStore,
    onBack: () -> Unit,
    onSharePdf: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val images by viewModel.images.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val dateFormatter = rememberDateFormatter("yyyy-MM-dd HH:mm")
    var showProfileDialog by remember { mutableStateOf(false) }
    var showPhotoPicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.exportedPdf) {
        state.exportedPdf?.let {
            onSharePdf(it.absolutePath)
            viewModel.consumeExported()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("景观管理") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 场地信息 ----------
            item {
                InfoCard(
                    title = "场地信息",
                    onEdit = { showProfileDialog = true },
                ) {
                    InfoRow("位置", state.location)
                    InfoRow("项目类型", state.projectType)
                    InfoRow("描述", state.description)
                }
            }

            // ---------- 景观照片 ----------
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("景观照片（${images.size}）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "这里放的是场地整体的照片（花坛、道路绿化、植物群落），" +
                                "与某一株植物的识别照片是分开的。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (images.isEmpty()) {
                            Text("还没有景观照片", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(images, key = { it.id }) { image ->
                                    Column(Modifier.width(120.dp)) {
                                        LocalImage(
                                            file = imageStore.resolve(image.imagePath),
                                            contentDescription = image.caption,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(90.dp),
                                        )
                                        Text(
                                            text = image.caption ?: "未命名",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                        TextButton(onClick = { viewModel.removeImage(image.id) }) {
                                            Text("删除", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showPhotoPicker = true },
                            enabled = state.busy == null,
                        ) { Text("添加照片") }
                    }
                }
            }

            // ---------- 配置统计 ----------
            state.stats?.let { stats ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(
                                "植物配置统计",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(8.dp))
                            StatsSection(stats)
                        }
                    }
                }
            }

            // ---------- AI 分析 ----------
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row {
                            Text(
                                "AI 景观分析",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.needsReanalysis) {
                                Text(
                                    "数据已变化，建议重新分析",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        state.analysisResult?.let { result ->
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "第 ${state.analysisVersion ?: 1} 版 · " +
                                    "${state.analysisModel ?: "未知模型"} · " +
                                    (state.analysisUpdatedAt?.let {
                                        dateFormatter.format(Date(it))
                                    } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(
                            onClick = viewModel::runAnalysis,
                            enabled = state.busy == null,
                        ) {
                            Text(if (state.analysisResult == null) "开始分析" else "重新分析")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "AI 辅助分析，仅供参考，不代表经过专业人员审核的景观设计结论。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------- 导出 ----------
            item {
                Button(
                    onClick = viewModel::exportPdf,
                    enabled = state.busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导出景观 PDF 报告") }
            }
        }
    }

    if (showProfileDialog) {
        ProfileDialog(
            initialLocation = state.location,
            initialType = state.projectType,
            initialDescription = state.description,
            onDismiss = { showProfileDialog = false },
            onConfirm = { location, type, description ->
                viewModel.saveProfile(location, type, description)
                showProfileDialog = false
            },
        )
    }

    if (showPhotoPicker) {
        PhotoPickerLauncher(
            onPicked = { uris ->
                viewModel.addImages(uris)
                showPhotoPicker = false
            },
        )
    }
}

@Composable
private fun PhotoPickerLauncher(onPicked: (List<android.net.Uri>) -> Unit) {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onPicked(uris) }
    // 可组合函数体里直接 launch：调用点已经在「添加照片」点击之后
    androidx.compose.runtime.LaunchedEffect(Unit) {
        launcher.launch(arrayOf("image/*"))
    }
}

@Composable
private fun InfoCard(
    title: String,
    onEdit: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEdit) { Text("编辑") }
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value.ifBlank { "未填写" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 统计数字按「标签 + 数字」两列排，配色相/季相那种一目了然的清单形式 */
@Composable
private fun StatsSection(stats: LandscapeStatistics) {
    Column {
        Text("层次分布", style = MaterialTheme.typography.labelMedium)
        stats.layers.forEach {
            Text("　${it.label}：${it.count}（${(it.ratio * 100).toInt()}%）",
                style = MaterialTheme.typography.bodySmall)
        }
        if (stats.colors.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("色彩关键词（按描述统计，非精确色彩分析）", style = MaterialTheme.typography.labelMedium)
            Text(
                stats.colors.joinToString("、") { "${it.label} ${it.count}" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("季相与常绿", style = MaterialTheme.typography.labelMedium)
        Text(
            text = stats.seasons.joinToString("、") { "${it.label} ${it.count}" }
                .ifEmpty { "暂无花期数据" },
            style = MaterialTheme.typography.bodySmall,
        )
        stats.evergreen.let { e ->
            val ratioText = e.ratio?.let { "（常绿占 ${(it * 100).toInt()}%）" } ?: ""
            Text(
                "　常绿 ${e.evergreen} · 落叶 ${e.deciduous}" +
                    if (e.unknown > 0) " · 未标注 ${e.unknown}" else "" + ratioText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("多样性", style = MaterialTheme.typography.labelMedium)
        Text(
            text = "${stats.diversity.speciesCount} 个物种 / ${stats.diversity.individualCount} 株 · " +
                "Shannon %.2f".format(stats.diversity.shannon) + " · ${stats.diversity.level}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (stats.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("规则发现的问题", style = MaterialTheme.typography.labelMedium)
            stats.suggestions.forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProfileDialog(
    initialLocation: String,
    initialType: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onConfirm: (location: String, type: String, description: String) -> Unit,
) {
    var location by remember { mutableStateOf(initialLocation) }
    var type by remember { mutableStateOf(initialType) }
    var description by remember { mutableStateOf(initialDescription) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("场地信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = location, onValueChange = { location = it },
                    label = { Text("位置") }, singleLine = true,
                )
                OutlinedTextField(
                    value = type, onValueChange = { type = it },
                    label = { Text("项目类型（校园 / 公园…）") }, singleLine = true,
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("场地描述") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(location.trim(), type.trim(), description.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
