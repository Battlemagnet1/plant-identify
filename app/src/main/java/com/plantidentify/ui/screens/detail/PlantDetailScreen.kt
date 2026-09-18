package com.plantidentify.ui.screens.detail

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
import com.plantidentify.ui.components.PhasePlaceholder

@Composable
fun PlantDetailScreen(
    plantId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhasePlaceholder(
        modifier = modifier,
        title = "植物详情",
        phaseLabel = "Phase 5 · 植物档案",
        onBack = onBack,
        plannedItems = listOf(
            "基本信息：中文名称、拉丁学名、科、属、植物类型",
            "AI 植物分析：简介、形态特征、生长习性、花期、果期、园林用途、养护建议",
            "相似植物与候选物种",
            "观察记录历史：首次观察时间、观察次数、代表性照片",
            "手动编辑与删除档案",
            "删除时同步清理磁盘上的图片文件，避免残留孤儿文件",
        ),
        content = { RouteArgumentCard("plantId", plantId.toString()) },
    )
}

/**
 * 展示从路由收到的参数。
 * Phase 1 用它验证「带参数导航」确实把值传到了目标页面。
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
