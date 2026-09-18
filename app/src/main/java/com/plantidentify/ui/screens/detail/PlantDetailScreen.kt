package com.plantidentify.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.model.ConfidenceGrade
import com.plantidentify.ui.components.BackIconButton
import com.plantidentify.ui.components.LocalImage
import kotlin.math.roundToInt

/**
 * 植物详情页（规格书第十六节）。
 *
 * Phase 4 交付的是**可查看**：识别结果、照片、植物百科。
 * 编辑、删除、观察历史、位置入口等归 Phase 5。
 *
 * ## 关于置信度的表述
 *
 * 页面上统一写「模型置信度」，并明确标注它不是科学鉴定概率
 * （规格书第五节明令禁止「一定正确」「保证鉴定」类表述）。
 *
 * ## 文字分析失败时的表现
 *
 * 基础信息照常完整展示，只有百科区块显示「暂缺」并给出重新生成的入口。
 * 这一点是 Phase 4 验收标准 ② 的直接体现 ——
 * **识别结果的可用性不依赖文字分析是否成功**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantDetailScreen(
    imageStore: ImageStore,
    viewModel: PlantDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val analyzing by viewModel.analyzing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(detail?.plant?.name ?: "植物档案") },
                navigationIcon = { BackIconButton(onClick = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
    ) { innerPadding ->
        val plant = detail?.plant

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .semantics { contentDescription = "植物详情" },
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (plant == null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Text(
                            text = "档案不存在或已被删除。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                return@LazyColumn
            }

            item { IdentityCard(plant) }

            item { ConfidenceCard(plant) }

            val images = detail?.observations.orEmpty().flatMap { it.images }
            if (images.isNotEmpty()) {
                item {
                    PhotoStrip(
                        paths = images.map { it.imagePath },
                        imageStore = imageStore,
                        observationCount = detail?.observations?.size ?: 0,
                    )
                }
            }

            item { AnalysisSection(plant = plant, analyzing = analyzing, onRegenerate = viewModel::regenerateAnalysis) }

            item { DisclaimerCard() }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun IdentityCard(plant: PlantRecordEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = plant.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            plant.latinName?.takeIf { it.isNotBlank() }?.let { latin ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = latin,
                    style = MaterialTheme.typography.titleSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            val taxonomy = listOfNotNull(
                plant.family?.takeIf { it.isNotBlank() }?.let { "科：$it" },
                plant.genus?.takeIf { it.isNotBlank() }?.let { "属：$it" },
            )
            if (taxonomy.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = taxonomy.joinToString("　"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            plant.category?.takeIf { it.isNotBlank() }?.let { category ->
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
private fun ConfidenceCard(plant: PlantRecordEntity) {
    val percent = (plant.confidence * 100).roundToInt()
    val stars = ConfidenceGrade.render(plant.confidence)

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
                text = "识别质量　$stars　${ConfidenceGrade.label(plant.confidence)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "这是 AI 对当前视觉证据的把握程度估计，不是经过科学验证的" +
                    "物种鉴定概率。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 照片横条 —— 展示本次观察的全部照片（含部位标注） */
@Composable
private fun PhotoStrip(
    paths: List<String>,
    imageStore: ImageStore,
    observationCount: Int,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "照片",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${paths.size} 张 · ${observationCount} 次观察",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(paths, key = { it }) { path ->
                LocalImage(
                    file = imageStore.resolve(path),
                    contentDescription = "植物照片",
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

/**
 * 植物百科区块。
 *
 * 四种状态各自给出明确交代：
 *  - 生成成功 → 展示七个字段（空的字段直接跳过，不显示「暂无」占位）
 *  - 生成中 → 进度提示
 *  - 失败 / 未请求 → 说明原因 + 提供重新生成入口
 */
@Composable
private fun AnalysisSection(
    plant: PlantRecordEntity,
    analyzing: Boolean,
    onRegenerate: () -> Unit,
) {
    val sections = analysisSections(plant)

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
                    text = "植物百科",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (analyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            when {
                analyzing -> {
                    Text(
                        text = "正在生成，可能需要十几秒…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                sections.isNotEmpty() -> {
                    if (plant.analysisStatus == AnalysisStatus.FAILED) {
                        Text(
                            text = "上次生成失败，以下是之前的版本。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    sections.forEachIndexed { index, (title, content) ->
                        if (index > 0) Spacer(Modifier.height(12.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                plant.analysisStatus == AnalysisStatus.FAILED -> {
                    Text(
                        text = "详细植物分析暂时生成失败。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "这不影响上面的识别结果 —— 名称、科属与照片都已完整保存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        text = "尚未生成植物百科。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "生成后会补充简介、形态特征、习性、花期、用途与养护建议。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!analyzing) {
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onRegenerate, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sections.isEmpty()) "生成植物百科" else "重新生成")
                }
            }
        }
    }
}

/** 免责声明（规格书第三十一节） */
@Composable
private fun DisclaimerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = "本页内容由 AI 生成，仅供参考，不作为专业鉴定依据。" +
                "请以实地观察与专业资料为准。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** 取档案里非空的百科字段；空字段直接跳过，不显示「暂无」占位 */
private fun analysisSections(plant: PlantRecordEntity): List<Pair<String, String>> = buildList {
    plant.description?.takeIf { it.isNotBlank() }?.let { add("植物简介" to it) }
    plant.morphologicalFeatures?.takeIf { it.isNotBlank() }?.let { add("形态特征" to it) }
    plant.growthHabits?.takeIf { it.isNotBlank() }?.let { add("生长习性" to it) }
    plant.floweringPeriod?.takeIf { it.isNotBlank() }?.let { add("花期" to it) }
    plant.fruitingPeriod?.takeIf { it.isNotBlank() }?.let { add("果期" to it) }
    plant.landscapeUses?.takeIf { it.isNotBlank() }?.let { add("园林用途" to it) }
    plant.careAdvice?.takeIf { it.isNotBlank() }?.let { add("养护建议" to it) }
}
