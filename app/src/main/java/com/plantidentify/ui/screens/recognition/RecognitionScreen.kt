package com.plantidentify.ui.screens.recognition

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.plantidentify.ui.components.PhasePlaceholder

@Composable
fun RecognitionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhasePlaceholder(
        modifier = modifier,
        title = "识别结果",
        phaseLabel = "Phase 3–4 · 视觉识别与文字分析",
        onBack = onBack,
        plannedItems = listOf(
            "多图联合识别结果：中文名、拉丁学名、科、属、植物类型",
            "AI 置信度与识别质量星级（明确标注为模型置信程度，不是科学鉴定概率）",
            "判定依据（evidence）与缺失信息（missing_information）",
            "候选植物与图片间冲突说明",
            "置信度偏低时提示补充照片，并基于全部照片重新联合识别",
            "视觉识别失败与文字分析失败分开处理：文字失败不丢失识别结果",
            "AI 返回解析失败时降级展示原始文本，不丢弃本次识别",
        ),
    )
}
