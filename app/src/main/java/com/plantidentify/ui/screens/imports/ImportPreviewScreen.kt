package com.plantidentify.ui.screens.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantidentify.domain.import.ImportDecision
import com.plantidentify.ui.components.rememberDateFormatter
import java.util.Date

/**
 * 导入检查（v1.0.2 Phase 2）。
 *
 * 页面按「用户要做的决定」分组，而不是按数据库里的顺序罗列：
 * 确定重复、疑似重复、新记录 —— 前两类要用户表态，最后一类只是通知。
 *
 * 判据（`reason`）直接显示在每一行上：用户没道理相信一个只说
 * 「疑似重复」却不说凭什么的功能，而 `DuplicateMatcher` 本来就产出了
 * 一句中文说明（「拉丁学名完全相同（Lagerstroemia indica）」），
 * 不用白不用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPreviewScreen(
    viewModel: ImportPreviewViewModel,
    onBack: () -> Unit,
    onOpenPlant: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val dateFormatter = rememberDateFormatter("yyyy-MM-dd HH:mm")

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入检查") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SummaryCard(
                    sourceName = state.sourceName,
                    importedAtText = dateFormatter.format(Date(state.importedAt)),
                    total = state.totalCount,
                    handled = state.handledCount,
                    conclusive = state.conclusiveCount,
                    pending = state.pendingCount,
                    newCount = state.newCount,
                )
            }

            item { BulkActionRow(onSetAll = viewModel::setAllPending) }

            val conclusive = state.rows.filter { it.decision == ImportDecision.CONCLUSIVE }
            val review = state.rows.filter { it.needsReview }
            val fresh = state.rows.filter { it.decision == ImportDecision.NEW }

            if (conclusive.isNotEmpty()) {
                item { GroupHeader("确定重复", "${conclusive.size} 条", "本地证据足够硬（拉丁学名全同，或中文名+科+属全同）") }
                items(conclusive, key = { "c${it.importPlantId}" }) { row ->
                    ImportRowCard(row, state.applying, viewModel::setChoice, onOpenPlant)
                }
            }

            if (review.isNotEmpty()) {
                item {
                    GroupHeader(
                        "需要确认",
                        "${review.size} 条",
                        "像，但没有硬证据 —— 默认保留为新记录，不会动你的已有数据",
                    )
                }
                items(review, key = { "r${it.importPlantId}" }) { row ->
                    ImportRowCard(row, state.applying, viewModel::setChoice, onOpenPlant)
                }
            }

            if (fresh.isNotEmpty()) {
                item { GroupHeader("新记录", "${fresh.size} 条", "与本地任何档案都不沾边，已直接收下") }
                items(fresh, key = { "n${it.importPlantId}" }) { row ->
                    ImportRowCard(row, state.applying, viewModel::setChoice, onOpenPlant)
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = viewModel::apply,
                    enabled = !state.applying,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.applying) "正在执行…" else "执行以上决定")
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    sourceName: String,
    importedAtText: String,
    total: Int,
    handled: Int,
    conclusive: Int,
    pending: Int,
    newCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(sourceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "导入于 $importedAtText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "共 $total 条 · 已处理 $handled 条 · 确定重复 $conclusive · 待确认 $pending · 新记录 $newCount",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "这些记录都在「协作文件夹」里，可以随时查看；只有你确认的才会并进已有档案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BulkActionRow(onSetAll: (ImportPreviewViewModel.Choice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = false,
            onClick = { onSetAll(ImportPreviewViewModel.Choice.KEEP) },
            label = { Text("待确认项全部保留为新") },
        )
        FilterChip(
            selected = false,
            onClick = { onSetAll(ImportPreviewViewModel.Choice.MERGE) },
            label = { Text("全部合并") },
        )
    }
}

@Composable
private fun GroupHeader(title: String, count: String, hint: String) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                text = count,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportRowCard(
    row: ImportPreviewViewModel.ImportRow,
    applying: Boolean,
    onChoice: (Long, ImportPreviewViewModel.Choice) -> Unit,
    onOpenPlant: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    row.latinName?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { onOpenPlant(row.importPlantId) }) { Text("查看") }
            }

            row.matchedName?.let { matched ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "与本地「$matched」相似",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    row.settled -> "已处理"
                    row.decision == ImportDecision.NEW -> "与本地无重复"
                    row.decision == ImportDecision.CONCLUSIVE -> "确定是同一株 —— 默认合并"
                    else -> "疑似重复 —— 请选择处理方式"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!row.settled && row.decision != ImportDecision.NEW) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = row.choice == ImportPreviewViewModel.Choice.MERGE,
                        onClick = { onChoice(row.importPlantId, ImportPreviewViewModel.Choice.MERGE) },
                        enabled = !applying,
                        label = { Text("合并") },
                    )
                    FilterChip(
                        selected = row.choice == ImportPreviewViewModel.Choice.KEEP,
                        onClick = { onChoice(row.importPlantId, ImportPreviewViewModel.Choice.KEEP) },
                        enabled = !applying,
                        label = { Text("保留为新") },
                    )
                }
            }
        }
    }
}
