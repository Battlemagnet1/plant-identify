package com.plantidentify.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX 拍照页（规格书第三节「相机拍照」）。
 *
 * 只负责「拍一张并存成临时文件」，完成后把文件交回给调用方导入。
 * 权限被拒时不崩溃，直接给出可理解的提示并允许返回改用相册
 * （规格书第二十四节要求）。
 *
 * 注意：CameraX 只能写到应用可访问的位置，这里写 cacheDir 下的临时文件；
 * 调用方会把它导入 filesDir 成为正式原图并删除临时文件 ——
 * **原图绝不能直接留在 cacheDir**，系统会静默清理。
 */
@Composable
fun CameraCaptureScreen(
    onCaptured: (File) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val hasPermission = remember { context.hasCameraPermission() }
    var permissionGranted by remember { mutableStateOf(hasPermission) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        runCatching {
            val provider = context.awaitCameraProvider()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
        }.onFailure { throwable ->
            errorMessage = "相机启动失败：${throwable.message ?: "设备可能没有可用摄像头"}"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            !permissionGranted -> PermissionDeniedPanel(onCancel = onCancel)

            errorMessage != null -> CameraErrorPanel(
                message = errorMessage.orEmpty(),
                onCancel = onCancel,
            )

            else -> {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )

                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .systemBarsPadding()
                        .padding(8.dp),
                ) {
                    Text("关闭", color = Color.White)
                }

                    ShutterButton(
                        enabled = !capturing,
                        onClick = {
                            capturing = true
                            captureToTempFile(
                                context = context,
                                imageCapture = imageCapture,
                                scope = scope,
                                onSuccess = { tempFile ->
                                    capturing = false
                                    onCaptured(tempFile)
                                },
                                onFailure = { reason ->
                                    capturing = false
                                    errorMessage = reason
                                },
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .systemBarsPadding()
                            .padding(bottom = 32.dp),
                    )
            }
        }
    }
}

@Composable
private fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(72.dp),
        shape = CircleShape,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
        onClick = { if (enabled) onClick() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(58.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {}
        }
    }
}

@Composable
private fun PermissionDeniedPanel(onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "没有相机权限",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "可以返回后改用相册选择照片，不影响正常使用。\n若想使用拍照，请在系统设置中为本应用开启相机权限。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCancel) { Text("返回") }
    }
}

@Composable
private fun CameraErrorPanel(message: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "相机不可用",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCancel) { Text("返回") }
    }
}

/** 相机权限是否已授予（供「添加植物」页在进入相机前判断） */
internal fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 拍照并把 JPEG 字节写入缓存目录下的临时文件。
 *
 * ## 为什么不用 `takePicture(OutputFileOptions, ...)` 直接写文件
 *
 * 那条路径在写盘后会再调用 `FileUtil.updateFileExif()` 回写 EXIF。
 * 部分设备/模拟器上这一步会抛 `ImageCaptureException: Failed to update Exif data`——
 * 此时 JPEG 其实**已经写好了**，但回调走的是 onError，照片就白拍了。
 *
 * 改成内存回调后由我们自己落盘，不再经过 CameraX 的 EXIF 回写，
 * 既绕开了这个坑，也把「写成什么文件」的控制权收回自己手里。
 * 传感器方向信息仍会保留在字节流的 EXIF 中，由压缩环节按 EXIF 校正。
 */
private fun captureToTempFile(
    context: Context,
    imageCapture: ImageCapture,
    scope: CoroutineScope,
    onSuccess: (File) -> Unit,
    onFailure: (String) -> Unit,
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                // 必须在 use 块内把字节拷出来：ImageProxy 关闭后 buffer 不再有效
                val bytes = runCatching {
                    image.use { proxy ->
                        val buffer = proxy.planes[0].buffer
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    }
                }.getOrNull()

                if (bytes == null || !bytes.looksLikeJpeg()) {
                    // 部分设备/模拟器的虚拟摄像头会返回全 0 的数据帧。
                    // 直接导入会得到一张无法解码的「照片」，不如当场说清楚。
                    onFailure("相机没有返回有效图像，请重试或改用相册")
                    return
                }

                scope.launch(Dispatchers.IO) {
                    val tempFile = File(
                        context.cacheDir,
                        "capture_${System.currentTimeMillis()}.jpg",
                    )
                    val written = runCatching { tempFile.writeBytes(bytes) }
                    withContext(Dispatchers.Main) {
                        if (written.isSuccess) {
                            onSuccess(tempFile)
                        } else {
                            tempFile.delete()
                            onFailure("拍照失败：无法写入临时文件，存储空间可能不足")
                        }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onFailure(friendlyCaptureError(exception))
            }
        },
    )
}

/** JPEG 以 FF D8 开头；用来挡掉全 0 或截断的无效数据帧 */
private fun ByteArray.looksLikeJpeg(): Boolean =
    size >= 4 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()

/** 把 CameraX 的错误码翻成用户能理解的话（规格书第二十四节） */
private fun friendlyCaptureError(exception: ImageCaptureException): String =
    when (exception.imageCaptureError) {
        ImageCapture.ERROR_CAMERA_CLOSED -> "相机已被关闭，请重试"
        ImageCapture.ERROR_CAPTURE_FAILED -> "拍摄没有完成，请重试"
        ImageCapture.ERROR_FILE_IO -> "写入照片失败，存储空间可能不足"
        ImageCapture.ERROR_INVALID_CAMERA -> "相机不可用，可能被其他应用占用"
        else -> "拍照失败：${exception.message ?: "未知原因"}"
    }

/**
 * 等待 CameraX 初始化。
 *
 * 用 `getInstance` + `addListener` 手工桥接，而不是依赖 `awaitInstance`
 * 这类协程扩展 —— 后者在不同 CameraX 版本上的可用性有差异，
 * 而 `getInstance` 是自 1.0 起就稳定的 API。
 */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (throwable: Throwable) {
                    continuation.resumeWithException(throwable)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
