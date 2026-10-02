package com.plantidentify.ui.screens.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「加入文件夹」选择页（v1.0.2 Phase 1，完整版专属）。
 *
 * 从植物详情进入，把**这一株**植物归到任意多个文件夹里。
 * 保存是整体替换语义 —— 取消勾选即等于从那个文件夹移出，
 * 所以底部写了一句明确的说明，避免用户以为「只能加不能减」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    viewModel: FolderPickerViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val folders by viewModel.folders.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    val message by viewModel.message.collectAsState()
    val finished by viewModel.finished.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(finished) {
        if (finished) onSaved()
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
                title = { Text("加入文件夹") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    // 归属还没读出来之前不允许保存：此刻保存会把已有归属全部覆盖掉
                    TextButton(
                        onClick = viewModel::save,
                        enabled = loaded,
                    ) { Text("保存") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "勾选这株植物所属的文件夹。一株植物可以同时属于多个，档案只会保存一份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (folders.isEmpty()) {
                EmptyPickerCard()
            } else {
                FolderSelectionList(
                    folders = folders,
                    selectedIds = selectedIds,
                    onToggle = viewModel::toggle,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "取消勾选会把植物从对应文件夹移出（植物本身不会被删除）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyPickerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("还没有文件夹", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "先在首页进入「文件夹」新建一个，再回来把植物放进去。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
