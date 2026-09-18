package com.plantidentify.ui.screens.observation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.plantidentify.ui.components.PhasePlaceholder
import com.plantidentify.ui.components.RouteArgumentCard

@Composable
fun ObservationScreen(
    observationId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhasePlaceholder(
        modifier = modifier,
        title = "观察记录",
        phaseLabel = "Phase 5 · 多次观察",
        onBack = onBack,
        plannedItems = listOf(
            "观察时间与地点（位置未授权时只显示时间，不阻断使用）",
            "本次观察的照片，按拍摄部位分组展示",
            "本次 AI 识别结果、置信度与原始返回",
            "备注编辑",
            "追加照片并重新识别：更新当前观察，不新建观察记录",
        ),
        content = { RouteArgumentCard("observationId", observationId.toString()) },
    )
}
