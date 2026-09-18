package com.plantidentify.ui.screens.addplant

import android.Manifest
import android.net.Uri
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantidentify.data.draft.CaptureDraft
import com.plantidentify.data.draft.DraftImage
import com.plantidentify.data.local.entity.ImageRole
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.ui.camera.hasCameraPermission
import com.plantidentify.ui.components.BackIconButton
import com.plantidentify.ui.components.ImageRoleSelector
import com.plantidentify.ui.components.LocalImage
import java.io.File

/**
 * 添加植物页（规格书第三、四节）。
 *
 * Phase 2 的职责：拍照 / 相册多选 / 删除 / 调整顺序 / 部位标注 / 原图落盘。
 * 「开始识别」按钮已就位，真正识别要等 Phase 3。
 *
 * 两个刻意的设计：
 * - **图片一加进来就落盘并写入草稿**，不存在「忘了点保存」丢图的情况
 * - **相机权限被拒不让流程中断** —— 只给提示并保留相册通道（验收标准⑤）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlantScreen(
    draft: CaptureDraft,
    importing: Boolean,
    message: String?,
    uploadPlan: UploadPlan?,
    imageStore: ImageStore,
    capturedTempPath: String?,
    onCapturedTempConsumed: () -> Unit,
    onImportUris: (List<Uri>) -> Unit,
    onImportCapture: (File) -> Unit,
    onRemove: (DraftImage) -> Unit,
    onMoveEarlier: (DraftImage) -> Unit,
    onMoveLater: (DraftImage) -> Unit,
    onSetRole: (DraftImage, ImageRole) -> Unit,
    onDiscardAll: () -> Unit,
    onMessageConsumed: () -> Unit,
    onOpenCamera: () -> Unit,
    onStartRecognition: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var cameraDeniedHint by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val pickImagesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(CaptureDraft.MAX_IMAGES),
    ) { uris -> if (uris.isNotEmpty()) onImportUris(uris) }

    // 相机权限：授权则进相机页，被拒则留在本页给提示（绝不崩溃）
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraDeniedHint = false
            onOpenCamera()
        } else {
            cameraDeniedHint = true
        }
    }

    fun openGallery() {
        pickImagesLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    // 相机拍完回传的临时文件：导入正式目录并消费掉
    LaunchedEffect(capturedTempPath) {
        val path = capturedTempPath ?: return@LaunchedEffect
        onImportCapture(File(path))
        onCapturedTempConsumed()
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onMessageConsumed()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("添加植物") },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    if (!draft.isEmpty) {
                        TextButton(onClick = { showDiscardDialog = true }) { Text("取消") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = "已添加 ${draft.count}/${CaptureDraft.MAX_IMAGES} 张",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (importing) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            if (draft.isEmpty) {
                item { EmptyHintCard() }
            }

            itemsIndexed(
                items = draft.images,
                key = { _, image -> image.key },
            ) { index, image ->
                DraftImageCard(
                    image = image,
                    order = index + 1,
                    canMoveEarlier = index > 0,
                    canMoveLater = index < draft.count - 1,
                    imageStore = imageStore,
                    onRemove = { onRemove(image) },
                    onMoveEarlier = { onMoveEarlier(image) },
                    onMoveLater = { onMoveLater(image) },
                    onSetRole = { role -> onSetRole(image, role) },
                )
            }

            if (draft.canAddMore) {
                item {
                    AddPhotoRow(
                        onCamera = {
                            if (context.hasCameraPermission()) {
                                onOpenCamera()
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onGallery = ::openGallery,
                    )
                }
            }

            if (cameraDeniedHint) {
                item { CameraDeniedCard(onGallery = ::openGallery) }
            }

            if (uploadPlan != null) {
                item { UploadPlanCard(uploadPlan) }
            }

            item { ShootingTipsCard() }

            item {
                Button(
                    onClick = onStartRecognition,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !draft.isEmpty && !importing,
                ) {
                    Text("开始识别")
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃这次添加？") },
            text = { Text("已添加的 ${draft.count} 张照片会从本机删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onDiscardAll()
                    },
                ) { Text("放弃并删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun EmptyHintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "添加植物照片",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "可以拍照，也可以从相册选择。最多 ${CaptureDraft.MAX_IMAGES} 张 —— " +
                    "同一株植物的不同部位一起提供，识别会更准。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 单张草稿照片：缩略图 + 部位标注 + 排序 + 删除 */
@Composable
private fun DraftImageCard(
    image: DraftImage,
    order: Int,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    imageStore: ImageStore,
    onRemove: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onSetRole: (ImageRole) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalImage(
                file = imageStore.resolve(image.relativePath),
                contentDescription = "第 $order 张照片，部位：${image.role.name}",
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "图 $order",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                ImageRoleSelector(
                    current = image.role,
                    onRoleSelected = onSetRole,
                    compact = false,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MoveButton("‹ 前移", enabled = canMoveEarlier, onClick = onMoveEarlier)
                    MoveButton("后移 ›", enabled = canMoveLater, onClick = onMoveLater)
                    TextButton(onClick = onRemove) {
                        Text("删除", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

@Composable
private fun AddPhotoRow(onCamera: () -> Unit, onGallery: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f)) { Text("拍照") }
        OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f)) { Text("从相册选择") }
    }
}

@Composable
private fun CameraDeniedCard(onGallery: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "没有相机权限",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "可以改用相册选择照片，其余功能不受影响。" +
                    "若想拍照，请在系统设置中为本应用开启相机权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onGallery) { Text("改用相册") }
        }
    }
}

/**
 * 上传体积预估。
 *
 * 让用户在真正发起识别之前就知道要传多少数据 ——
 * 对应分析报告 R10「API 成本失控」，把成本可见化。
 */
@Composable
private fun UploadPlanCard(plan: UploadPlan) {
    val allFailed = plan.imageCount > 0 && plan.failedCount == plan.imageCount

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (allFailed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (allFailed) "照片无法用于识别" else "上传副本已就绪",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (allFailed) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
            Spacer(Modifier.height(6.dp))

            if (allFailed) {
                Text(
                    text = "这 ${plan.imageCount} 张照片都读不出内容，请重新拍摄或更换照片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                Text(
                    text = "已把 ${plan.imageCount - plan.failedCount} 张图压缩到最长边 1536px / JPEG 80，" +
                        "合计 ${formatBytes(plan.totalBytes)}；" +
                        "加上 base64 编码后，请求体约 ${formatBytes(plan.estimatedRequestBodyBytes)}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                if (plan.hasFailure) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "有 ${plan.failedCount} 张压缩失败，识别时会跳过。" +
                            "建议删掉它们后重新添加。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "原图完整保存在本机，压缩副本仅用于上传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 拍摄建议（规格书第四节）：给建议但不强制 */
@Composable
private fun ShootingTipsCard() {    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "提高识别准确率",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                "整体植株 —— 观察株型与树姿",
                "叶片 —— 尽量拍清叶形、叶缘与叶序",
                "花 / 果实 —— 如果存在，务必拍上",
                "茎干 / 树皮 —— 木本植物建议提供",
            ).forEach { tip ->
                Text(
                    text = "· $tip",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "不强制全部提供。只拍一张也能识别，只是信息越全结果越可靠。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
