package com.plantidentify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 路由参数调试卡。
 *
 * Phase 1 引入，用于在页面尚未实现时确认带参数的导航是否正常
 * （例如 `plantId = 1` 确实传到了详情页）。
 *
 * 它原本定义在植物详情页里，Phase 4 实现详情页后移到这里 ——
 * 因为观察记录页（Phase 5）暂时还需要它。
 * Phase 5 把观察记录页做出来之后，这个组件即可删除。
 */
@Composable
internal fun RouteArgumentCard(key: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "路由参数",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$key = $value",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
