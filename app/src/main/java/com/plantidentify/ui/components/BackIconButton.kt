package com.plantidentify.ui.components

import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * 顶部返回按钮（用文字箭头，避免为图标引入额外依赖）。
 *
 * 原本和 `PhasePlaceholder` 挤在同一个文件里。Phase 7 清死代码时
 * 差点整文件删掉 —— 其实这个按钮被 9 个页面的顶栏在用。
 * 单独成文件之后，「要不要删」一眼就能看出来。
 */
@Composable
fun BackIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Text(
            text = "←",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
