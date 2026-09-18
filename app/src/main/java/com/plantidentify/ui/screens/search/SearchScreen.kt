package com.plantidentify.ui.screens.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.plantidentify.ui.components.PhasePlaceholder

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhasePlaceholder(
        modifier = modifier,
        title = "搜索植物",
        phaseLabel = "Phase 5 · 搜索与筛选",
        onBack = onBack,
        plannedItems = listOf(
            "全文搜索：中文名称、拉丁学名、科、属、植物类型、备注",
            "筛选：科、属、观察日期、观察地点",
            "结果排序：按观察次数与最近观察时间",
            "完全离线可用（已保存档案的搜索不依赖网络）",
        ),
    )
}
