package com.plantidentify.ui.screens.recognition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.ai.VisionResponse
import com.plantidentify.domain.model.MergeLevel
import com.plantidentify.domain.model.MergeSuggestion
import com.plantidentify.ui.components.BackIconButton
import kotlin.math.roundToInt

/**
 * 识别结果页（规格书第五、六、七节）。
 *
 * ## 展示上的两条硬约束
 *
 * 1. **不得声称「一定正确」**（规格书第五节明确禁止）。
 *    置信度必须带上下文说明 —— 它是 AI 对当前视觉证据的把握程度，
 *    不是物种鉴定的科学概率。页面上的措辞统一为「模型置信度」。
 *
 * 2. **解析失败也要给出东西**（验收标准 ⑤）。
 *    模型返回非规范 JSON 时展示原文，而不是一个空白页加一句「识别失败」——
 *    模型很可能已经识别对了，只是格式不听话。
 *
 * ## 关于「保存到档案」
 *
 * Phase 3 的结果只存在于内存，页面上如实说明此点。
 * 落库需要处理重复植物归并（规格书第十四点五节的三条铁律），
 * 那是 Phase 5 的范围；现在草率写入会产生需要迁移的脏数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddMorePhotos: () -> Unit,
    onOpenPlantDetail: (Long) -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onAppendToExisting: () -> Unit,
    onCreateNewPlant: () -> Unit,
    onRegenerateAnalysis: () -> Unit,
    viewModel: RecognitionViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()

    // 找到可能对应的已有植物时，先问用户再写入。
    // 用对话框而不是底部栏：这是一个需要明确选择的决策点，
    // 放在底部栏容易和「保存中/已保存」这类进度状态混在一起被忽略。
    (saveState as? SaveState.AwaitingMergeDecision)?.let { decision ->
        MergeDecisionDialog(
            suggestion = decision.suggestion,
            onAppend = onAppendToExisting,
            onCreateNew = onCreateNewPlant,
            onDismiss = { /* 必须二选一，不允许点外部关掉 */ },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("识别结果") },
                navigationIcon = { BackIconButton(onClick = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            // 只有拿到结构化结果才谈得上保存：半结构化（只剩原文）时
            // 没有植物名称等建档案必需的字段，因此不显示保存入口，
            // 页面内的「本次识别」卡片会说明原因
            val result = (state as? RecognitionUiState.Success)?.response?.result
            if (result != null) {
                SaveBar(
                    saveState = saveState,
                    onSave = onSave,
                    onOpenPlantDetail = onOpenPlantDetail,
                    onRegenerateAnalysis = onRegenerateAnalysis,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .semantics { contentDescription = "识别结果" },
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val current = state) {
                RecognitionUiState.Idle, RecognitionUiState.Preparing -> {
                    item { BusyCard(text = "正在准备照片…", indeterminate = true) }
                }

                is RecognitionUiState.Recognizing -> {
                    item {
                        BusyCard(
                            text = buildString {
                                append("正在综合识别 ${current.imageCount} 张照片…")
                                append("\n\n多图联合识别需要模型横向比对各张照片的特征，")
                                append("通常需要十几秒到一分钟，请勿离开本页。")
                                if (current.usedFallbackForSomeImages) {
                                    append("\n\n注意：有照片压缩失败已跳过，结果可能受影响。")
                                }
                            },
                            indeterminate = true,
                        )
                    }
                }

                is RecognitionUiState.Failed -> {
                    item { FailureCard(current) }

                    if (current.canOpenSettings) {
                        item {
                            Button(
                                onClick = onOpenSettings,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("去设置 AI 服务")
                            }
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重新识别")
                        }
                    }
                }

                is RecognitionUiState.Success -> {
                    val response = current.response

                    response.parseNote?.let { note ->
                        item { NoticeCard(note) }
                    }

                    if (response.result != null) {
                        item { IdentityCard(response.result) }
                        item { ConfidenceCard(response.result) }

                        if (response.result.needsMorePhotos) {
                            item {
                                LowConfidenceCard(
                                    result = response.result,
                                    onAddMorePhotos = onAddMorePhotos,
                                )
                            }
                        }

                        if (response.result.evidence.isNotEmpty()) {
                            item { BulletListCard("判定依据", response.result.evidence) }
                        }

                        if (response.result.alternatives.isNotEmpty()) {
                            item { AlternativesCard(response.result.alternatives) }
                        }

                        if (response.result.conflicts.isNotEmpty()) {
                            item { BulletListCard("照片之间的不一致", response.result.conflicts) }
                        }
                    }

                    item { RawTextCard(response) }

                    item { PipelineSummaryCard(response) }

                    item {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重新识别")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------- 状态视图 ----------------

@Composable
private fun BusyCard(text: String, indeterminate: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (indeterminate) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FailureCard(state: RecognitionUiState.Failed) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "识别失败",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.failure.userMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            state.failure.fixHint?.let { hint ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            state.failure.serverDetail?.let { detail ->
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    // 只有确实来自服务端的细节才加这个前缀。
                    // 本地问题（照片读不出来等）走 LocalProblem，
                    // isFromServer 为 false，不会出现「服务返回：照片读取失败」
                    // 这种把人指向错误方向的提示
                    text = if (state.failure.isFromServer) "服务返回：$detail" else detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "结果已尽力提取",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

// ---------------- 结果组件 ----------------

@Composable
private fun IdentityCard(result: RecognitionResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = result.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            result.latinName?.let { latin ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = latin,
                    style = MaterialTheme.typography.titleSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            val taxonomy = listOfNotNull(
                result.family?.takeIf { it.isNotBlank() }?.let { "科：$it" },
                result.genus?.takeIf { it.isNotBlank() }?.let { "属：$it" },
            )
            if (taxonomy.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = taxonomy.joinToString("　"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            result.category?.takeIf { it.isNotBlank() }?.let { category ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ConfidenceCard(result: RecognitionResult) {
    val percent = (result.confidence * 100).roundToInt()
    val stars = "★".repeat(result.qualityStars) + "☆".repeat(5 - result.qualityStars)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "模型置信度",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "识别质量　$stars　${result.qualityLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "这是 AI 对当前视觉证据的把握程度估计，不是经过科学验证的" +
                    "物种鉴定概率。请结合实地观察判断。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LowConfidenceCard(
    result: RecognitionResult,
    onAddMorePhotos: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "识别可信度较低",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (result.missingInformation.isNotEmpty()) {
                    "当前照片信息不足，建议补充："
                } else {
                    "当前照片信息不足，补充更多部位的照片可以提高准确度。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            if (result.missingInformation.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                result.missingInformation.forEach { item ->
                    Text(
                        text = "• $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onAddMorePhotos) {
                Text("添加更多照片")
            }
        }
    }
}

@Composable
private fun BulletListCard(title: String, items: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            items.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AlternativesCard(alternatives: List<RecognitionResult.Alternative>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "其他可能",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            alternatives.forEach { alt ->
                val suffix = if (alt.confidence > 0.0) {
                    "　${(alt.confidence * 100).roundToInt()}%"
                } else {
                    ""
                }
                Text(
                    text = "• ${alt.name}$suffix",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

/** 模型原始返回 —— 只要不是完全规范的结构化结果，就默认展开供核对 */
@Composable
private fun RawTextCard(response: VisionResponse) {
    var expanded by remember(response) { mutableStateOf(response.result == null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "模型原始返回",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开")
                }
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = response.rawText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 本次识别用到了什么 —— 让「模型说不准」时可以回溯是哪一步的影响 */
@Composable
private fun PipelineSummaryCard(response: VisionResponse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "本次识别",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("• 使用照片 ${response.imageCount} 张")
                    if (response.afterRetry) {
                        append("\n• 首次返回格式不合规，已重试一次")
                    }
                    if (!response.isStructured) {
                        append("\n• 结构化解析失败，当前展示的是模型原话")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (response.isStructured) {
                    "确认结果无误后，请点下方的「保存到档案」写入植物档案。"
                } else {
                    "结构化解析失败，没有可用的植物名称，因此无法保存为档案。" +
                        "可点「重新识别」再试一次。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 底部保存栏。
 *
 * 固定在底部而不是放在内容流里：结果页内容较长（识别信息 + 判定依据 +
 * 候选植物 + 原始返回），把主操作埋在页面深处会让用户找不到。
 *
 * 四种状态各自给出**明确的下一步**：
 *  - 未保存 → 明确的「保存到档案」+ 一句免责说明
 *  - 保存中 → 告知正在生成百科（这一步可能要十几秒，不说明会被当成卡死）
 *  - 已保存但有分析 → 「查看植物档案」为主，另给「重新生成」
 *  - 已保存无分析 → 明确写出「基础识别结果不受影响」，避免用户以为白识别了
 */
@Composable
private fun MergeDecisionDialog(
    suggestion: MergeSuggestion,
    onAppend: () -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val plant = suggestion.plant ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (suggestion.level == MergeLevel.EXACT) {
                    "可能已记录过这种植物"
                } else {
                    "是否与已有植物是同一种？"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "本次识别：${plant.name}" +
                        (plant.latinName?.let { "（$it）" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "已有档案：${plant.name}" +
                        (plant.latinName?.let { "（$it）" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                plant.family?.let { family ->
                    Text(
                        text = "科：$family" + (plant.genus?.let { "　属：$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "判断依据：${suggestion.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "该档案已有 ${suggestion.observationCount} 次观察，" +
                        "添加后将是第 ${suggestion.nextObservationNumber} 次。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (suggestion.level == MergeLevel.POSSIBLE) {
                    Text(
                        text = "证据不够充分，请结合实地观察确认 —— " +
                            "系统不会替你决定这是不是同一株。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onAppend) { Text("添加到已有植物") }
        },
        dismissButton = {
            OutlinedButton(onClick = onCreateNew) { Text("创建新的植物") }
        },
    )
}

/**
 * 保存状态栏。
 *
 * 覆盖五种情况（规格书第十六、三十节）：
 *  - 未保存 → 引导保存
 *  - 等待归并决策 → 说明正在等用户选择
 *  - 保存中 → 进度
 *  - 已保存有分析 → 查看详情 / 重新生成
 *  - 已保存无分析 → 明确写出「基础识别结果不受影响」，避免用户以为白识别了
 */
@Composable
private fun SaveBar(
    saveState: SaveState,
    onSave: () -> Unit,
    onOpenPlantDetail: (Long) -> Unit,
    onRegenerateAnalysis: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (saveState) {
                SaveState.NotSaved -> {
                    Text(
                        text = "识别结果仅供参考，请结合实地观察判断。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保存到档案")
                    }
                }

                SaveState.Saving -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "正在保存并生成植物百科，可能需要十几秒…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is SaveState.AwaitingMergeDecision -> {
                    Text(
                        text = "找到可能相同的已有植物，请在对话框中选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is SaveState.SavedToExistingObservation -> {
                    Text(
                        text = "已更新这条观察的识别结果",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "没有新建观察 —— 观察次数保持不变。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onOpenPlantDetail(saveState.plantId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看植物档案")
                    }
                }

                is SaveState.SavedWithAnalysis -> {
                    Text(
                        text = "已保存到植物档案",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    saveState.partialNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onOpenPlantDetail(saveState.plantId) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("查看植物档案")
                        }
                        OutlinedButton(onClick = onRegenerateAnalysis) {
                            Text("重新生成")
                        }
                    }
                }

                is SaveState.SavedWithoutAnalysis -> {
                    Text(
                        text = "已保存到植物档案（植物百科暂缺）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "${saveState.reason}。识别结果本身不受影响，已完整保存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onOpenPlantDetail(saveState.plantId) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("查看植物档案")
                        }
                        OutlinedButton(onClick = onRegenerateAnalysis) {
                            Text("重新生成分析")
                        }
                    }
                }

                is SaveState.Failed -> {
                    Text(
                        text = "保存失败：${saveState.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重试保存")
                    }
                }
            }
        }
    }
}
