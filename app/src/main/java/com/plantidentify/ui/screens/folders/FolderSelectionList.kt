package com.plantidentify.ui.screens.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.plantidentify.data.local.entity.FolderEntity

/**
 * 可复用的「文件夹多选列表」（v1.0.2 Phase 1）。
 *
 * 三处共用，所以抽成一个组件而不是各写一遍：
 *   1. 植物详情的「加入文件夹」选择页（多选，带保存）
 *   2. 文件夹详情批量操作的「加入其他文件夹」（多选，弹窗内）
 *   3. 文件夹详情批量操作的「移动到其他文件夹」（单选形态，用 [singleChoice]）
 *
 * [excludeIds] 用来把「当前所在文件夹」排除掉 —— 移动到自己所在的文件夹
 * 是无意义的操作，让它出现在列表里只会让用户点了一下发现没反应。
 */
@Composable
fun FolderSelectionList(
    folders: List<FolderEntity>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    modifier: Modifier = Modifier,
    excludeIds: Set<Long> = emptySet(),
) {
    val visible = folders.filterNot { it.id in excludeIds }

    if (visible.isEmpty()) {
        Text(
            text = "没有可选的文件夹。「新建文件夹」之后再来这里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(
        modifier = modifier
            // 弹窗里可能塞进几十个文件夹：限高 + 内部滚动，
            // 否则对话框会撑满整屏、按钮被挤出可视区
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        visible.forEach { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(folder.id) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = folder.id in selectedIds,
                    onCheckedChange = { onToggle(folder.id) },
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = folder.type.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
