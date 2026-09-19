package com.plantidentify.ui.screens.observation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.data.local.relation.ObservationWithImages
import com.plantidentify.data.location.placeText
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.data.storage.MediaSaver
import com.plantidentify.ui.components.BackIconButton
import com.plantidentify.ui.components.ImageViewerHost
import com.plantidentify.ui.components.LocalImage
import com.plantidentify.ui.components.ViewerImage
import com.plantidentify.ui.components.label
import com.plantidentify.ui.components.rememberImageViewerState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 观察记录（规格书第十七节）。
 *
 * 每次观察展示：时间 / 地点 / 照片（带部位标注）/ 当次识别结论与置信度 / 备注。
 *
 * ## 为什么把「补图并重新识别」放在这里
 *
 * 规格书第十四点五节要求「对当前观察补图并重新识别时**更新**这条观察，
 * 而不是创建新的观察」。要做这件事就必须先能「选中某一条观察」——
 * 这个页面正是那个入口。Phase 4 没做它，是因为当时还没有地方列出观察。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservationScreen(
    imageStore: ImageStore,
    mediaSaver: MediaSaver,
    viewModel: ObservationViewModel,
    onBack: () -> Unit,
    onReanalysisStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val observations by viewModel.observations.collectAsStateWithLifecycle()
    val plantName by viewModel.plantName.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val draftSeeded by viewModel.draftSeeded.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var editingNoteFor by remember { mutableStateOf<ObservationWithImages?>(null) }
    var deleting by remember { mutableStateOf<ObservationWithImages?>(null) }

    // 全屏查看器：翻页范围是「这一条观察里的照片」——
    // 从哪条观察点进去就只看哪条，不要跨观察串起来
    val viewerState = rememberImageViewerState()

    ImageViewerHost(
        state = viewerState,
        mediaSaver = mediaSaver,
        nameHint = plantName,
    )

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 草稿装好后离开本页，进入添加页继续补图
    LaunchedEffect(draftSeeded) {
        if (draftSeeded) {
            viewModel.consumeDraftSeeded()
            onReanalysisStarted()
        }
    }

    editingNoteFor?.let { target ->
        NoteEditDialog(
            initial = target.observation.note.orEmpty(),
            onConfirm = { note ->
                viewModel.updateNote(target.observation.id, note)
                editingNoteFor = null
            },
            onDismiss = { editingNoteFor = null },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这次观察？") },
            text = {
                Text(
                    "将删除这次观察的 ${target.images.size} 张照片及其识别记录，无法撤销。\n\n" +
                        "植物档案本身不会被删除。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteObservation(target.observation.id)
                        deleting = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("观察记录")
                        if (plantName.isNotBlank()) {
                            Text(
                                text = plantName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackIconButton(onClick = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
    ) { innerPadding ->
        if (observations.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(20.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Text(
                        text = "还没有观察记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp,
                vertical = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(observations, key = { it.observation.id }) { item ->
                ObservationCard(
                    item = item,
                    total = observations.size,
                    imageStore = imageStore,
                    onReanalyze = { viewModel.startReanalysis(item) },
                    onEditNote = { editingNoteFor = item },
                    onDelete = { deleting = item },
                    onOpenImage = { index ->
                        viewerState.open(
                            images = item.images.map {
                                ViewerImage(imageStore.resolve(it.imagePath), it.role.label)
                            },
                            initialIndex = index,
                        )
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ObservationCard(
    item: ObservationWithImages,
    total: Int,
    imageStore: ImageStore,
    onReanalyze: () -> Unit,
    onEditNote: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: (Int) -> Unit,
) {
    val observation = item.observation
    val recognized = parseRecognition(observation.aiResultJson)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatDateTime(observation.timestamp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (observation.isPrimary) {
                    Text(
                        text = "代表观察",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 地名取不到就显示坐标 —— 原来这里只认 locationName，
            // 而国内 ROM 的反向地理编码经常返回 null，于是「有坐标却什么都不显示」
            placeText(
                locationName = observation.locationName,
                latitude = observation.latitude,
                longitude = observation.longitude,
            )?.let { place ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "地点：$place",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(item.images, key = { _, image -> image.id }) { index, image ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val file = imageStore.resolve(image.imagePath)
                        if (file.isFile) {
                            LocalImage(
                                file = file,
                                contentDescription = "照片 ${image.role.label}",
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenImage(index) },
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = image.role.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (recognized != null) {
                Text(
                    text = "当次识别：${recognized.name}　" +
                        "${(recognized.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = "当次识别结果未能解析出结构化内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            observation.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "备注：$note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 补图重识别会把结果写回**这条**观察（规格书第十四点五节），
                // 不新建 —— 按钮文案要把这一点说出来，否则用户不敢点
                OutlinedButton(onClick = onReanalyze) { Text("补图重识别") }
                TextButton(onClick = onEditNote) { Text("改备注") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }

            if (total > 1) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "补图重识别会更新这一次观察，不会新增观察次数。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoteEditDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑观察备注") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注") },
                placeholder = { Text("如 校园南门绿化带，人工栽培") },
                minLines = 3,
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
