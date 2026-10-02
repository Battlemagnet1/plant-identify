package com.plantidentify.ui.screens.folders

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.model.FolderPlantSort
import com.plantidentify.ui.components.rememberDateFormatter
import com.plantidentify.ui.screens.plants.PlantCard
import java.util.Date

/**
 * 文件夹详情（v1.0.2 Phase 1，完整版专属）。
 *
 * ## 这一页最重要的语义
 *
 * 页面上会出现两种「移除」，必须让用户分得清：
 *   - **从文件夹移除** —— 只解除归类，植物还在档案里（本页的批量操作）
 *   - **删除文件夹** —— 只删容器，里面的植物一株不少（右上角「删除」）
 *
 * 两者都不会删除植物，所以两处都写了明确的二次确认文案，把这句话
 * 直接说给用户听 —— 而不是让他去猜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    viewModel: FolderDetailViewModel,
    imageStore: ImageStore,
    onBack: () -> Unit,
    onEditFolder: (Long) -> Unit,
    onOpenPlant: (Long) -> Unit,
) {
    val folder by viewModel.folder.collectAsState()
    val plants by viewModel.plants.collectAsState()
    val keyword by viewModel.keyword.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val selectionActive by viewModel.selectionActive.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val allFolders by viewModel.allFolders.collectAsState()
    val message by viewModel.message.collectAsState()
    val finished by viewModel.finished.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(finished) {
        if (finished) onBack()
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            if (selectionActive) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    allSelected = plants.isNotEmpty() && selectedIds.size == plants.size,
                    onExit = viewModel::exitSelection,
                    onToggleAll = {
                        if (plants.isNotEmpty() && selectedIds.size == plants.size) {
                            viewModel.clearSelection()
                        } else {
                            viewModel.selectAll(plants.map { it.plantId })
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(folder?.name ?: "文件夹") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                    actions = {
                        if (plants.isNotEmpty()) {
                            TextButton(onClick = viewModel::startSelection) { Text("选择") }
                        }
                        TextButton(onClick = { folder?.let { onEditFolder(it.id) } }) { Text("编辑") }
                        TextButton(onClick = { showDeleteConfirm = true }) { Text("删除") }
                    },
                )
            }
        },
        bottomBar = {
            if (selectionActive && selectedIds.isNotEmpty()) {
                SelectionActionBar(
                    onAddToFolders = { showAddDialog = true },
                    onMove = { showMoveDialog = true },
                    onRemove = { showRemoveConfirm = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            folder?.let { entity ->
                item(key = "info") {
                    FolderInfoCard(
                        name = entity.name,
                        typeLabel = entity.type.label(),
                        description = entity.description,
                        plantCount = plants.size,
                        updatedAt = entity.updatedAt,
                    )
                }
            }

            item(key = "search") {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = viewModel::onKeywordChange,
                    label = { Text("在文件夹内搜索") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "sort") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FolderPlantSort.entries.toList(), key = { it.name }) { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { viewModel.onSortSelected(option) },
                            label = { Text(option.displayLabel()) },
                        )
                    }
                }
            }

            if (plants.isEmpty()) {
                item(key = "empty") {
                    EmptyMembersCard(
                        hasKeyword = keyword.isNotBlank(),
                        folderName = folder?.name.orEmpty(),
                    )
                }
            } else {
                items(plants, key = { it.plantId }, contentType = { "plant-card" }) { plant ->
                    PlantCard(
                        card = plant,
                        imageStore = imageStore,
                        onClick = {
                            if (selectionActive) {
                                viewModel.toggleSelection(plant.plantId)
                            } else {
                                onOpenPlant(plant.plantId)
                            }
                        },
                        onLongClick = {
                            if (!selectionActive) viewModel.beginSelection(plant.plantId)
                        },
                        leading = if (selectionActive) {
                            {
                                Checkbox(
                                    checked = plant.plantId in selectedIds,
                                    onCheckedChange = { viewModel.toggleSelection(plant.plantId) },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    // ---------------- 加入其他文件夹（多选） ----------------
    if (showAddDialog) {
        var picked by remember { mutableStateOf(emptySet<Long>()) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("加入其他文件夹") },
            text = {
                Column {
                    Text(
                        text = "选中的 ${selectedIds.size} 株会**同时**加入这些文件夹，" +
                            "它们仍留在「${folder?.name.orEmpty()}」里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FolderSelectionList(
                        folders = allFolders,
                        selectedIds = picked,
                        onToggle = { id ->
                            picked = if (id in picked) picked - id else picked + id
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addSelectionToFolders(picked.toList())
                        showAddDialog = false
                    },
                    enabled = picked.isNotEmpty(),
                ) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------------- 移动到其他文件夹（单选） ----------------
    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text("移动到其他文件夹") },
            text = {
                Column {
                    Text(
                        text = "选中的 ${selectedIds.size} 株会从「${folder?.name.orEmpty()}」" +
                            "移到目标文件夹。植物本身不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FolderSelectionList(
                        folders = allFolders,
                        selectedIds = emptySet(),
                        // 单选：点一下就移动，不再需要「确认」——
                        // 多一步确认只会让这个高频操作变慢
                        onToggle = { targetId ->
                            viewModel.moveSelectionToFolder(targetId)
                            showMoveDialog = false
                        },
                        // 排除当前文件夹：移到自己所在的地方是无意义操作
                        excludeIds = setOf(folder?.id ?: -1L),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------------- 移除确认（强调不删植物） ----------------
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("从文件夹移除 ${selectedIds.size} 株？") },
            text = {
                Text(
                    "只会解除它们与这个文件夹的归类关系，" +
                        "**植物档案、观察记录和照片都会完整保留**。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeSelection()
                    showRemoveConfirm = false
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("取消") }
            },
        )
    }

    // ---------------- 删除文件夹确认 ----------------
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除文件夹「${folder?.name.orEmpty()}」？") },
            text = {
                Text(
                    "只删除这个文件夹本身，**里面的 ${plants.size} 株植物不会被删除**，" +
                        "它们仍然在植物档案里，只是不再属于这个文件夹。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder()
                    showDeleteConfirm = false
                }) { Text("删除文件夹") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
) {
    TopAppBar(
        title = { Text("已选 $selectedCount 项") },
        navigationIcon = { TextButton(onClick = onExit) { Text("×") } },
        actions = {
            TextButton(onClick = onToggleAll) {
                Text(if (allSelected) "取消全选" else "全选")
            }
        },
    )
}

/**
 * 选择态的底部操作条。
 *
 * 三个动作的语义差别很大（加入是加法、移动是搬家、移除是减法），
 * 所以不用图标而是写全称 —— 图标在「加入/移动」之间极容易混淆。
 */
@Composable
private fun SelectionActionBar(
    onAddToFolders: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onAddToFolders, modifier = Modifier.weight(1f)) {
                    Text("加入其他")
                }
                OutlinedButton(onClick = onMove, modifier = Modifier.weight(1f)) {
                    Text("移动")
                }
                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                    Text("移除")
                }
            }
        }
    }
}

@Composable
private fun FolderInfoCard(
    name: String,
    typeLabel: String,
    description: String?,
    plantCount: Int,
    updatedAt: Long,
) {
    // 与 FolderCard 同一原因：composable 内不能用 Locale.getDefault()
    val dateFormat = rememberDateFormatter("yyyy-MM-dd")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$typeLabel · $plantCount 株植物 · 最后更新 " +
                    dateFormat.format(Date(updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EmptyMembersCard(hasKeyword: Boolean, folderName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = if (hasKeyword) "没有匹配的植物" else "这个文件夹还是空的",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (hasKeyword) {
                    "换个关键词试试。"
                } else {
                    "去植物详情页点「加入文件夹」把植物放进来，" +
                        "或者先在「$folderName」里点右上角「选择」整理已有成员。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun FolderPlantSort.displayLabel(): String = when (this) {
    FolderPlantSort.JOINED -> "最近加入"
    FolderPlantSort.UPDATED -> "最近更新"
    FolderPlantSort.NAME -> "名称"
}
