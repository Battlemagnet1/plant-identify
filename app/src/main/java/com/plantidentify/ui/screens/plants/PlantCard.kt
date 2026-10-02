package com.plantidentify.ui.screens.plants

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.plantidentify.data.local.projection.PlantCardRow
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.model.ConfidenceGrade
import com.plantidentify.ui.components.LocalImage

/**
 * 植物列表卡片（规格书第十六节的列表形态）。
 *
 * 显示「一眼能分辨是不是它」的最小信息集：封面图 + 名称 + 学名 + 科属 +
 * 观察次数。置信度用星级而不是百分比 —— 列表里逐条读数字没有意义，
 * 星级能一眼扫出「哪几条不太确定」。
 *
 * ## 两个可选参数是为文件夹详情页的选择态加的
 *
 * [leading] 放一个勾选框，[onLongClick] 用来「长按进入多选」。
 * **两者都为空时行为与扩展前完全一致** —— 列表、搜索、任务、清洗
 * 那几处调用点不用改一行代码，也不会因为多了一层手势而改变水波纹或点击语义。
 */
@Composable
fun PlantCard(
    card: PlantCardRow,
    imageStore: ImageStore,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 卡片最左侧的可选内容（文件夹选择态放勾选框）。null = 不显示 */
    leading: (@Composable () -> Unit)? = null,
    /** 长按回调。null = 不响应长按，走 Card 自带的可点击重载 */
    onLongClick: (() -> Unit)? = null,
) {
    if (onLongClick == null) {
        // 常规列表：保持原实现的点击与水波纹语义
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            PlantCardContent(card = card, imageStore = imageStore, leading = leading)
        }
    } else {
        // 需要长按的场景：由卡片自己处理手势。
        // 不能既传 Card(onClick=...) 又叠加 combinedClickable ——
        // 两层手势会互相抢占，结果是「有时能长按、有时被点击吃掉」
        Card(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            PlantCardContent(card = card, imageStore = imageStore, leading = leading)
        }
    }
}

/** 卡片内容。抽出来是为了让上面两条分支不会有任何视觉差异 */
@Composable
private fun PlantCardContent(
    card: PlantCardRow,
    imageStore: ImageStore,
    leading: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(2.dp))
        }

        val cover = card.coverPath?.let { imageStore.resolve(it) }
        if (cover != null && cover.isFile) {
            LocalImage(
                file = cover,
                contentDescription = card.name,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            // 没有照片时不留空洞：给一个等尺寸的占位块，
            // 否则卡片高度会参差不齐，列表看起来像坏了
            Card(
                modifier = Modifier.size(64.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "无照片",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            card.latinName?.let { latin ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = latin,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(card.observationCount)
                    append(" 次观察 · ")
                    append(card.photoCount)
                    append(" 张照片")
                } + (card.family?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = ConfidenceGrade.render(card.confidence.toDouble()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}
