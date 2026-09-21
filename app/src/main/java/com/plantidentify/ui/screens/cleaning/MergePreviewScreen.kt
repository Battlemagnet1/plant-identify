package com.plantidentify.ui.screens.cleaning

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantidentify.domain.cleaning.FieldSource
import com.plantidentify.domain.cleaning.MergeField
import com.plantidentify.domain.cleaning.RecordSnapshot
import com.plantidentify.domain.cleaning.valueOf

/**
 * 合并预览（Phase 3）。
 *
 * ## 这一页是「合并」这个不可逆操作的**唯一**确认点
 *
 * 所以它必须把三件事说清楚：
 * 1. **谁活下来**（保留 / 并入），并且可以换边
 * 2. **每个字段最后取哪边的值**，并且可以逐个改
 * 3. **系统替你做了哪些有争议的决定**（warnings）
 *
 * 合并本身是软删（被合并的那株进回收站），所以最坏情况是可恢复的 ——
 * 这一点要写在确认框里，用户才敢下手。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergePreviewScreen(
    viewModel: MergePreviewViewModel,
    onBack: () -> Unit,
    onMerged: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmOpen by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<MergeField?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.done) {
        if (state.done) onMerged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("合并预览") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            state.unavailable -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("无法生成合并方案", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "涉及的档案可能已经被合并或删除。返回清洗中心刷新即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                val plan = state.plan ?: return@Scaffold
                val keep = state.keep ?: return@Scaffold
                val drop = state.drop ?: return@Scaffold

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "sides") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                SideLine("保留", keep, plan.observationCount)
                                Spacer(Modifier.height(10.dp))
                                SideLine("并入", drop, null)
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = viewModel::swapSides) {
                                    Text("换边（让「${drop.name}」活下来）")
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "被并入的那一株进入回收站，不会被物理删除；" +
                                        "它的观察与照片会转到保留的那一株名下。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (plan.warnings.isNotEmpty()) {
                        item(key = "warnings") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "需要你确认的取舍",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    plan.warnings.forEach { warning ->
                                        Text(
                                            text = "· $warning",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                    }

                    item(key = "fields-header") {
                        Text(
                            text = "合并后的内容（点任意一行可改选）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    // 逐字段：默认择优结果 + 来源标记。两边不同的字段点开可改选
                    item(key = "fields") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column {
                                MergeField.entries.forEachIndexed { index, field ->
                                    val choice = plan.choice(field)
                                    FieldRow(
                                        field = field,
                                        value = choice?.value,
                                        source = choice?.source,
                                        keepValue = keep.valueOf(field),
                                        dropValue = drop.valueOf(field),
                                        onEdit = { editingField = field },
                                    )
                                    if (index != MergeField.entries.lastIndex) {
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }

                    item(key = "confirm") {
                        OutlinedButton(
                            onClick = { confirmOpen = true },
                            enabled = !state.merging,
                        ) {
                            if (state.merging) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("确认合并")
                        }
                    }
                }

                editingField?.let { field ->
                    FieldChoiceDialog(
                        field = field,
                        keepName = keep.name,
                        dropName = drop.name,
                        keepValue = keep.valueOf(field),
                        dropValue = drop.valueOf(field),
                        onChoose = { value ->
                            viewModel.chooseValue(field, value)
                            editingField = null
                        },
                        onReset = {
                            viewModel.resetField(field)
                            editingField = null
                        },
                        onDismiss = { editingField = null },
                    )
                }

                if (confirmOpen) {
                    AlertDialog(
                        onDismissRequest = { confirmOpen = false },
                        title = { Text("确认合并？") },
                        text = {
                            Text(
                                "「${drop.name}」的 ${plan.observationCount - keep.observationCount} 次观察" +
                                    "会转到「${keep.name}」名下，它自己会进入回收站。" +
                                    "合并后如果发现不对，可以在回收站里恢复它。",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmOpen = false
                                viewModel.confirm()
                            }) { Text("合并") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmOpen = false }) { Text("取消") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SideLine(label: String, record: RecordSnapshot, mergedCount: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(record.name.ifBlank { "（无名称）" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = buildString {
                    append(record.latinName?.takeIf { it.isNotBlank() } ?: "无学名")
                    append(" · ")
                    append("${record.observationCount} 次观察 · ${record.imageCount} 张照片")
                    if (mergedCount != null) {
                        append("  →  合并后 $mergedCount 次观察")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一行字段。
 *
 * 只有**两边不同**的行才可点 —— 完全相同却可点会让用户以为
 * 「点了有用」，而两个值一模一样时点了看不出任何变化。
 */
@Composable
private fun FieldRow(
    field: MergeField,
    value: String?,
    source: FieldSource?,
    keepValue: String?,
    dropValue: String?,
    onEdit: () -> Unit,
) {
    val differs = normalize(keepValue) != normalize(dropValue)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (differs) Modifier.clickable(onClick = onEdit) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(84.dp)) {
            Text(field.label, style = MaterialTheme.typography.bodySmall)
            if (differs && source != null) {
                Text(
                    text = source.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = value?.takeIf { it.isNotBlank() } ?: "（两边都没有）",
                style = MaterialTheme.typography.bodySmall,
                // 长文本截断显示：完整内容在改选对话框里看得到
                maxLines = 3,
            )
            if (differs) {
                Text(
                    text = "两边不同 · 点此改选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FieldChoiceDialog(
    field: MergeField,
    keepName: String,
    dropName: String,
    keepValue: String?,
    dropValue: String?,
    onChoose: (String?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.label) },
        text = {
            Column {
                ChoiceOption(keepName, keepValue) { onChoose(keepValue) }
                Spacer(Modifier.height(12.dp))
                ChoiceOption(dropName, dropValue) { onChoose(dropValue) }
            }
        },
        confirmButton = { TextButton(onClick = onReset) { Text("恢复自动选择") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ChoiceOption(name: String, value: String?, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value?.takeIf { it.isNotBlank() } ?: "（没有内容）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 比较用：把「空」与「空字符串」当成同一种情况，避免多出无意义的可点行 */
private fun normalize(value: String?): String = value?.trim().orEmpty()
