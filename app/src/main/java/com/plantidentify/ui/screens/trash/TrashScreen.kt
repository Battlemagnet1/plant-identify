package com.plantidentify.ui.screens.trash

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import com.plantidentify.data.local.entity.PlantRecordEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站（Phase 3，完整版专属入口）。
 *
 * ## 这一页存在的意义
 *
 * 删除植物是不可逆的高代价操作 —— 一株植物可能带着几十次观察与照片。
 * 有回收站之后，「手滑删了」从**事故**变成**可撤销的操作**。
 *
 * 因此这里的两个动作权限完全不同：
 * - 「恢复」是一键的，因为它只会把东西还回来
 * - 「彻底删除」会连同磁盘上的照片一起消失，所以**一律二次确认**
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onBack: () -> Unit,
) {
    val plants by viewModel.plants.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var pendingPurge by remember { mutableStateOf<PlantRecordEntity?>(null) }
    var showPurgeAll by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    if (plants.isNotEmpty()) {
                        TextButton(onClick = { showPurgeAll = true }) { Text("清空") }
                    }
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
            Text(
                text = "这里是被删除的植物档案。可以随时恢复到档案列表，" +
                    "「彻底删除」则会连同照片一起清除、无法找回。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            if (plants.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("回收站是空的")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "在植物详情页点删除，档案会先移到这里。",
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
                    items(plants, key = { it.id }, contentType = { "trash-card" }) { plant ->
                        TrashCard(
                            plant = plant,
                            onRestore = { viewModel.restore(plant.id) },
                            onPurge = { pendingPurge = plant },
                        )
                    }
                }
            }
        }
    }

    pendingPurge?.let { plant ->
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text("彻底删除「${plant.name}」？") },
            text = {
                Text(
                    "这条档案与它的全部观察、照片都会从本机删除，**无法恢复**。" +
                        "如果只是想让它从列表里消失，用「恢复」把它放回去后再决定也不迟。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.purge(plant.id)
                    pendingPurge = null
                }) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurge = null }) { Text("取消") }
            },
        )
    }

    if (showPurgeAll) {
        AlertDialog(
            onDismissRequest = { showPurgeAll = false },
            title = { Text("清空回收站？") },
            text = {
                Text("${plants.size} 株档案及其照片将被永久删除，**无法恢复**。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.purgeAll()
                    showPurgeAll = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeAll = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TrashCard(
    plant: PlantRecordEntity,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = plant.name,
                style = MaterialTheme.typography.titleSmall,
            )
            plant.latinName?.takeIf { it.isNotBlank() }?.let { latin ->
                Text(
                    text = latin,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "删除于 " + SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                    .format(Date(plant.deletedAt ?: 0L)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRestore) { Text("恢复") }
                TextButton(onClick = onPurge) { Text("彻底删除") }
            }
        }
    }
}
