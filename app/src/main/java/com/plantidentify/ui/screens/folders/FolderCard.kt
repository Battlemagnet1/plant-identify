package com.plantidentify.ui.screens.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.plantidentify.data.local.projection.FolderCardRow
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.ui.components.LocalImage
import com.plantidentify.ui.components.rememberDateFormatter
import java.util.Date

/**
 * 文件夹卡片。
 *
 * 与 [com.plantidentify.ui.screens.plants.PlantCard] 共用同一套外观语法
 * （圆角方形封面 + 主标题 + 一行灰字 + 右侧标记），让「文件夹列表」与
 * 「植物列表」看起来是同一个应用里的东西，而不是两套设计。
 *
 * 封面用文件夹自己设置的 `coverImage`，没设置时由 SQL 回退到
 * 「成员里第一株植物的封面」—— 所以刚建好、加了几株植物的文件夹
 * 天然就有封面，不必让用户手动挑一张。
 */
@Composable
fun FolderCard(
    card: FolderCardRow,
    imageStore: ImageStore,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 「最后更新」的格式化必须走 rememberDateFormatter：在 composable 里直接写
    // Locale.getDefault() 会被 lint 的 NonObservableLocale 判为 error（构建中断）
    val dateFormat = rememberDateFormatter("yyyy-MM-dd")

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderCover(
                coverPath = card.coverPath,
                name = card.name,
                imageStore = imageStore,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(card.type.label())
                        append(" · ")
                        append(card.plantCount)
                        append(" 株植物")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "最后更新 " + dateFormat.format(Date(card.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 封面。没有可用图片时给一个等尺寸占位块 ——
 * 否则卡片高度会参差不齐，列表看起来像坏了（与 PlantCard 同一处理）。
 */
@Composable
private fun FolderCover(
    coverPath: String?,
    name: String,
    imageStore: ImageStore,
) {
    val cover = coverPath?.let { imageStore.resolve(it) }
    if (cover != null && cover.isFile) {
        LocalImage(
            file = cover,
            contentDescription = name,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
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
                    text = "空",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
