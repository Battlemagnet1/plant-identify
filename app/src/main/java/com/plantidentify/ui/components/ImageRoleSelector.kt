package com.plantidentify.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.plantidentify.data.local.entity.ImageRole

/**
 * 拍摄部位的中文名。
 *
 * 放在 UI 层而不是枚举里：数据层不应依赖界面文案。
 */
val ImageRole.label: String
    get() = when (this) {
        ImageRole.UNKNOWN -> "未标注"
        ImageRole.WHOLE_PLANT -> "整株"
        ImageRole.LEAF -> "叶片"
        ImageRole.FLOWER -> "花"
        ImageRole.FRUIT -> "果实"
        ImageRole.BARK -> "茎干树皮"
        ImageRole.HABITAT -> "生境"
    }

/**
 * 拍摄部位选择器。
 *
 * 这是对冲「多图联合识别退化」风险的关键交互（见分析报告 Part 1.3 改进建议 8）：
 * 让用户显式标注每张照片拍的是哪个部位，prompt 里就能写成
 * 「照片2 是叶片特写，重点观察叶形与叶序」，把需要跨图推理的任务
 * 拆解为多个单图子任务，绕开模型的短板。
 *
 * 默认「未标注」，**不强制用户填写**，不给使用增加负担。
 */
@Composable
fun ImageRoleSelector(
    current: ImageRole,
    onRoleSelected: (ImageRole) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (current == ImageRole.UNKNOWN) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        onClick = { expanded = true },
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 4.dp else 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = current.label,
                style = if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.labelLarge
                },
                color = if (current == ImageRole.UNKNOWN) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
            if (!compact) {
                Text(
                    text = "▾",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        ImageRole.entries.forEach { role ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = role.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (role == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Unspecified
                        },
                    )
                },
                onClick = {
                    expanded = false
                    if (role != current) onRoleSelected(role)
                },
            )
        }
    }
}
