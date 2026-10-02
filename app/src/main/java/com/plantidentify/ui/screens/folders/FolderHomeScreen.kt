package com.plantidentify.ui.screens.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantidentify.data.local.entity.FolderType
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.model.FolderSort

/**
 * 文件夹主页（v1.0.2 Phase 1，完整版专属）。
 *
 * 一页承担「看全部 + 找特定的那个 + 新建」三件事。刻意**不引入底部导航栏** ——
 * 本应用的导航是「一页一件事 + 返回栈」，为文件夹单独加一套底部栏会让
 * 返回行为变得难以预期（返回键到底是回首页还是回文件夹根？）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderHomeScreen(
    viewModel: FolderHomeViewModel,
    imageStore: ImageStore,
    onBack: () -> Unit,
    onOpenFolder: (Long) -> Unit,
    onCreateFolder: () -> Unit,
) {
    val folders by viewModel.folders.collectAsState()
    val keyword by viewModel.keyword.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()
    val countsByType by viewModel.countsByType.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件夹") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    TextButton(onClick = onCreateFolder) { Text("新建") }
                },
            )
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
            item(key = "intro") {
                Text(
                    text = "用文件夹把植物归到不同的项目、调查或场地里。" +
                        "一株植物可以同时属于多个文件夹，它本身只会保存一份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "search") {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = viewModel::onKeywordChange,
                    label = { Text("搜索文件夹") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "sort") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FolderSort.entries.toList(), key = { it.name }) { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { viewModel.onSortSelected(option) },
                            label = { Text(option.displayLabel()) },
                        )
                    }
                }
            }

            item(key = "types") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "all") {
                        FilterChip(
                            selected = selectedType == null,
                            onClick = { viewModel.onTypeSelected(null) },
                            label = { Text("全部 $totalCount") },
                        )
                    }
                    items(FolderType.entries.toList(), key = { it.name }) { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { viewModel.onTypeSelected(type) },
                            label = { Text("${type.label()} ${countsByType[type] ?: 0}") },
                        )
                    }
                }
            }

            if (folders.isEmpty()) {
                item(key = "empty") { EmptyFoldersCard(hasAnyFilter = keyword.isNotBlank() || selectedType != null) }
            } else {
                items(folders, key = { it.folderId }, contentType = { "folder-card" }) { card ->
                    FolderCard(
                        card = card,
                        imageStore = imageStore,
                        onClick = { onOpenFolder(card.folderId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFoldersCard(hasAnyFilter: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = if (hasAnyFilter) "没有匹配的文件夹" else "还没有文件夹",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (hasAnyFilter) {
                    "换个关键词，或者把类型筛选切回「全部」。"
                } else {
                    "点右上角「新建」建一个 —— 比如「校园植物调查」或「广东常见植物」，" +
                        "再把已经识别过的植物加进去。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 排序项的中文名。放在这里是因为它只服务于这一个页面 */
private fun FolderSort.displayLabel(): String = when (this) {
    FolderSort.UPDATED -> "最近更新"
    FolderSort.CREATED -> "创建时间"
    FolderSort.NAME -> "名称"
}
