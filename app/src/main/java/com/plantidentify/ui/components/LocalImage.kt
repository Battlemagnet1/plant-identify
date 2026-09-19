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
 * 解码结果的内存缓存，全应用共享一份。
 *
 * 上限按字节算而不是按条数：384px 的缩略图一张约 590 KB，
 * 2048px 的预览一张约 16 MB，两者差着二十多倍，按条数限制没有意义。
 * 24 MB 大约能装下 40 张缩略图 —— 一屏能看到十几张，来回滚动时足够命中。
 *
 * 键里带上 `lastModified()`：用户编辑时换掉照片文件（改图后覆盖同名文件），
 * 时间戳会变，于是旧缓存自然失效，不会拿旧图当新图显示。
 */
private val thumbnailCache = SizedLruCache<Bitmap>(
    maxBytes = 24 * 1024 * 1024,
    sizeOf = { it.allocationByteCount },
)

/**
 * 展示本地图片文件的轻量组件。
 *
 * ## 为什么手写而不用图片库
 *
 * 本应用只加载**本地文件**。引入 Coil 这类图片库会带来额外的依赖面与
 * 运行时初始化要求（单例 ImageLoader），而这里需要的只是
 * 「按目标尺寸解码 + EXIF 纠正 + 有界内存缓存」这三件事。
 *
 * ## 三个必须做的处理
 *
 * 1. **按目标尺寸降采样解码** —— 原图动辄数千万像素，直接整张解码会迅速吃光内存
 * 2. **应用 EXIF 方向** —— 手机照片常把方向写在 EXIF 里，不处理会显示成横躺或倒置
 * 3. **命中缓存就不再解码** —— 前两项都复用 [ImageCompressor.decodeOriented]，
 *    与上传前的压缩走同一套解码逻辑，避免两处实现出现差异
 */
@Composable
fun LocalImage(
    file: File,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxEdgePx: Int = DEFAULT_THUMB_EDGE_PX,
    contentScale: ContentScale = ContentScale.Crop,
) {
    // 键的三个成分缺一不可：
    //   path           —— 不同文件不能用同一份缓存
    //   lastModified   —— 同一个路径被换成新内容时必须重新解码
    //   maxEdgePx      —— 缩略图与预览是两个尺寸，不能互相顶替
    val cacheKey = "${file.path}|${file.lastModified()}|$maxEdgePx"

    val bitmap by produceState<Bitmap?>(
        // 同步命中缓存时不经过「先占位再解码」，列表滚动回来的那一帧直接就是图，
        // 不会闪一下底色
        initialValue = thumbnailCache.get(cacheKey),
        cacheKey,
    ) {
        thumbnailCache.get(cacheKey)?.let {
            value = it
            return@produceState
        }

        val decoded = withContext(Dispatchers.IO) {
            runCatching { ImageCompressor.decodeOriented(file, maxEdgePx) }.getOrNull()
        }
        if (decoded != null) thumbnailCache.put(cacheKey, decoded)
        value = decoded
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
