package com.plantidentify.ui.screens.addplant

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.Locale

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
    askLocation: Boolean,
    locationUi: LocationUiState,
    requestLocationPermission: Boolean,
    onLocationAllowed: () -> Unit,
    onLocationDenied: () -> Unit,
    onLocationPermissionDenied: () -> Unit,
    onLocationPermissionRequested: () -> Unit,
    onRetryLocation: () -> Unit,
    onCaptureLocation: () -> Unit,
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

    // 位置权限（规格书第十八节）。拒绝是正常路径，不做任何惩罚性处理 ——
    // 应用照常拍照、识别、存档案，只是不记录地点。
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onCaptureLocation()
        } else {
            // 被拒不是“什么都不做”：要让界面知道，
            // 否则地点行会一直停在「正在获取…」，看起来像卡住了
            onLocationPermissionDenied()
        }
    }

    // 已开关但没权限时，页面一进来就补申请 ——
    // 用户在「数据管理」里打开开关后不会再经过询问框，
    // 权限只能靠这里补上（否则就是「开了开关却永远没地点」）
    LaunchedEffect(requestLocationPermission) {
        if (requestLocationPermission) {
            onLocationPermissionRequested()
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
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

            // 地点行。以前只在“真的拿到了地名”时才显示，
            // 于是失败与未开启在界面上完全一样 ——
            // 用户看到的就是「开了功能，但什么都没发生」。
            // 现在每种状态都有明确的一行字，失败时还可以点一下重试。
            if (locationUi != LocationUiState.Hidden) {
                item {
                    LocationRow(
                        state = locationUi,
                        onAction = onRetryLocation,
                    )
                }
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

    // 首次使用位置功能时的询问（规格书第十八节的文案）。
    // 只问一次：之后再进来直接按上次的选择走，用户想改去设置页。
    if (askLocation) {
        AlertDialog(
            onDismissRequest = { /* 必须显式选择，不允许点外部关掉 */ },
            title = { Text("记录观察地点？") },
            text = {
                Text(
                    "开启后，保存植物档案时会一并记下拍摄地点，" +
                        "方便日后按地点查找。\n\n" +
                        "地点只存在本机，不会上传。不开启也不影响识别与档案。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onLocationAllowed()
                        locationPermissionLauncher.launch(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    },
                ) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = onLocationDenied) { Text("暂不允许") }
            },
        )
    }
}

/**
 * 地点状态行。
 *
 * 每种状态都给出明确叙述，而不是“有就显示、没有就消失” ——
 * 后者让用户无法判断到底是功能没开、还是定位坏了。
 */
@Composable
private fun LocationRow(state: LocationUiState, onAction: () -> Unit) {
    val (text, actionable) = when (state) {
        LocationUiState.Hidden -> return
        LocationUiState.NeedPermission -> "📍 未获得定位权限 · 点此授权" to true
        LocationUiState.Fetching -> "📍 正在获取地点…" to false
        LocationUiState.Unavailable -> "📍 未能获取位置 · 点此重试" to true
        is LocationUiState.Ready -> "📍 ${state.text}" to true
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (actionable) Modifier.clickable(onClick = onAction) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * 把字节数格式化成易读文本。
 *
 * 显式指定 [Locale.US] 而不是依赖默认 locale：在某些区域（如德语、法语）
 * 默认格式化会用逗号做小数点，「1,5 MB」这类输出在这个场景里只会让人困惑，
 * 而且同一份数据在不同手机上显示不一致也无从排查。
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
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
