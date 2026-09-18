package com.plantidentify.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.plantidentify.ui.components.PhasePlaceholder

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhasePlaceholder(
        modifier = modifier,
        title = "设置",
        phaseLabel = "Phase 3+ · AI 服务与数据管理",
        onBack = onBack,
        plannedItems = listOf(
            "AI 服务配置：植物识别模型（视觉）与植物分析模型（文字）分别可配",
            "预置 Qwen / 豆包 / OpenAI 三家默认配置，同时保留自定义 OpenAI Compatible 入口",
            "可勾选「使用同一个 AI 服务」，勾选后视觉与文字共用一套配置",
            "测试连接按钮，失败时给出可理解的中文提示",
            "API Key 默认隐藏，使用 Android Keystore 加密后存于本地，不上传任何服务器",
            "数据管理：导出 HTML 报告、备份数据、恢复数据",
            "关于与免责声明（AI 结果仅供参考，不作为专业鉴定依据）",
        ),
    )
}
