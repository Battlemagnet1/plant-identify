package com.plantidentify.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.plantidentify.data.export.FileSharing
import com.plantidentify.data.storage.MediaSaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** 大图查看器里的一张图 */
data class ViewerImage(
    /** 应用私有目录里的原图 */
    val file: File,
    /** 图上方的说明，通常是拍摄部位（叶片 / 花 / 果实…） */
    val label: String,
)

/**
 * 大图查看器的开关状态。
 *
 * 做成一个可 remember 的小对象，是为了让调用方**一行**就能挂上查看器：
 * ```
 * val viewer = rememberImageViewerState()
 * ImageViewerHost(state = viewer, mediaSaver = ..., nameHint = plant.name)
 * // 任意位置：
 * viewer.open(images, index)
 * ```
 * 否则每个页面都要各自维护「当前看的是哪几张、第几张、有没有在保存」，
 * 详情页与观察页会写出两份几乎一样的代码。
 */
class ImageViewerState internal constructor() {
    internal var request by mutableStateOf<ViewerRequest?>(null)
        private set

    fun open(images: List<ViewerImage>, initialIndex: Int = 0) {
        if (images.isEmpty()) return
        request = ViewerRequest(images, initialIndex.coerceIn(images.indices))
    }

    fun close() {
        request = null
    }
}

internal data class ViewerRequest(
    val images: List<ViewerImage>,
    val initialIndex: Int,
)

@Composable
fun rememberImageViewerState(): ImageViewerState = remember { ImageViewerState() }

/**
 * 把大图查看器挂到当前界面。
 *
 * 负责三件与「系统」打交道的事，页面本身不必关心：
 *  - **保存到相册**：API 26–28 先申请存储权限，失败给出可读原因
 *  - **分享单张照片**：走 FileProvider（images/ 已在白名单里）
 *  - **状态提示**：保存中 / 已保存 / 失败原因，几秒后自动消失
 *
 * @param nameHint 相册里文件名的前缀，一般传植物名 —— 用户存完照片
 *        在相册里应该看到「紫薇_20260919_2030.jpg」而不是一串 UUID
 */
@Composable
fun ImageViewerHost(
    state: ImageViewerState,
    mediaSaver: MediaSaver,
    nameHint: String,
    modifier: Modifier = Modifier,
) {
    val request = state.request ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    /**
     * 正在等待授权的照片下标。
     *
     * 系统权限框是异步的，回调里拿不到「用户想存哪一张」——
     * 只能先把下标存下来，等授权结果回来再接着存。
     */
    var pendingIndex by remember { mutableStateOf<Int?>(null) }

    // 保存成功后不该一直显示「已保存」，否则下一次打开还挂着上次的提示
    LaunchedEffect(status) {
        if (status != null) {
            delay(STATUS_DISMISS_MS)
            status = null
        }
    }

    /** 真正执行保存。抽成函数是因为「授权成功后」与「本来就无需授权」两条路都要走它 */
    fun performSave(index: Int) {
        val image = request.images.getOrNull(index) ?: return
        scope.launch {
            saving = true
            val result = mediaSaver.saveToGallery(
                source = image.file,
                baseName = MediaSaver.baseName(nameHint),
            )
            status = result.fold(
                onSuccess = { "已保存到相册 Pictures/${MediaSaver.ALBUM}" },
                onFailure = { it.message ?: "保存失败，请重试" },
            )
            saving = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 拿到授权才继续；被拒就给一个能照着做的提示，
        // 而不是让按钮点下去毫无反应
        if (granted) {
            performSave(pendingIndex ?: 0)
        } else {
            status = "没有存储权限，无法保存到相册"
        }
        pendingIndex = null
    }

    ImageViewerDialog(
        images = request.images,
        initialIndex = request.initialIndex,
        saving = saving,
        status = status,
        onDismiss = state::close,
        onSave = { index ->
            if (mediaSaver.needsLegacyPermission()) {
                pendingIndex = index
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                performSave(index)
            }
            Unit
        },
        onShare = { index ->
            val image = request.images.getOrNull(index) ?: return@ImageViewerDialog
            val fired = FileSharing.share(
                context = context,
                file = image.file,
                mimeType = FileSharing.mimeTypeOf(image.file.name),
                subject = nameHint,
            )
            if (!fired) status = "没有找到可以接收照片的应用"
            Unit
        },
        modifier = modifier,
    )
}

/**
 * 全屏大图查看器。
 *
 * ## 交互
 *
 * - 左右滑动翻页（同一株植物的照片）
 * - 双指缩放 + 拖动查看细节
 * - 底部两个动作：保存到相册 / 分享
 *
 * ## 为什么要 `usePlatformDefaultWidth = false`
 *
 * [Dialog] 默认宽度是「平台对话框宽度」（手机上约屏宽的 90%），
 * 对一张要看清叶脉的照片来说太小。关掉它才有真正的整屏。
 */
@Composable
private fun ImageViewerDialog(
    images: List<ViewerImage>,
    initialIndex: Int,
    saving: Boolean,
    status: String?,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onShare: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(images.indices),
        pageCount = { images.size },
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF101214))
                .semantics { contentDescription = "照片查看" },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { it },
            ) { page ->
                val image = images[page]
                ZoomableImage(
                    file = image.file,
                    contentDescription = image.label,
                )
            }

            // 顶栏：序号 + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = Color.White)
                }
            }

            // 底栏：部位说明 + 动作
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = images[pagerState.currentPage].label,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )

                status?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = Color(0xFF9BE7A8),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onSave(pagerState.currentPage) },
                        enabled = !saving,
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text(if (saving) "保存中…" else "保存到相册")
                    }
                    OutlinedButton(
                        onClick = { onShare(pagerState.currentPage) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("分享")
                    }
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "双指可缩放",
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * 可缩放拖动的单张图。
 *
 * 复用 [LocalImage]（`maxEdgePx = PREVIEW_EDGE_PX`）而不是自己解码：
 * 大图预览同样需要降采样与 EXIF 方向处理，[LocalImage] 已经做对了这两件事，
 * 项目里也已有 2048px 的预览档位常量。
 */
@Composable
private fun ZoomableImage(
    file: File,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        // 缩回原始大小时把位移也归零 —— 否则会出现「图已经是 1x 了，
        // 却偏在屏幕外一角」的状态，用户只能重新打开
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        LocalImage(
            file = file,
            contentDescription = contentDescription,
            maxEdgePx = PREVIEW_EDGE_PX,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .transformable(transformState),
        )
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f

/** 保存提示自动消失的时间 */
private const val STATUS_DISMISS_MS = 3_500L
