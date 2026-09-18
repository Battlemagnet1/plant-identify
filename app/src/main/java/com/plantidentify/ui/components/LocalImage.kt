package com.plantidentify.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.plantidentify.data.image.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 网格缩略图的解码目标边长（像素），约合 96dp @ 3x */
private const val DEFAULT_THUMB_EDGE_PX = 384

/** 大图预览的解码目标边长 */
const val PREVIEW_EDGE_PX = 2048

/**
 * 展示本地图片文件的轻量组件。
 *
 * ## 为什么手写而不用图片库
 *
 * 本应用只加载**本地文件**，且单个观察最多 5 张缩略图。引入 Coil 这类图片库
 * 会带来额外的依赖面与运行时初始化要求（单例 ImageLoader），在这个规模下收益很小。
 * 等 Phase 5 出现「大量植物卡片列表」需要内存/磁盘二级缓存时，再评估替换。
 *
 * ## 两个必须做的处理
 *
 * 1. **按目标尺寸降采样解码** —— 原图动辄数千万像素，直接整张解码会迅速吃光内存
 * 2. **应用 EXIF 方向** —— 手机照片常把方向写在 EXIF 里，不处理会显示成横躺或倒置
 *
 * 两者都复用 [ImageCompressor.decodeOriented]，与上传前的压缩走同一套解码逻辑，
 * 避免两处实现出现差异。
 */
@Composable
fun LocalImage(
    file: File,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxEdgePx: Int = DEFAULT_THUMB_EDGE_PX,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        file.path,
        file.lastModified(),
        maxEdgePx,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageCompressor.decodeOriented(file, maxEdgePx) }.getOrNull()
        }
    }

    val decoded = bitmap
    when {
        decoded != null -> Image(
            bitmap = decoded.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )

        // 文件存在但还没解码完：先占位，避免布局跳动
        file.isFile -> Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        )

        // 文件确实不在（例如用户从文件管理器删除了图片）
        else -> Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "图片缺失",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
