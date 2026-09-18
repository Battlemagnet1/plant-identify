package com.plantidentify.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.BuildConfig
import com.plantidentify.domain.model.PlantStatistics

/**
 * 首页（规格书第十五节）。
 *
 * 目标是让用户打开应用后立刻明白要做什么：「我要拍植物 / 看我的植物记录」。
 * 因此结构极简：搜索 → 统计 → 档案列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddPlant: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onOpenPlantDetail: (Long) -> Unit,
    onOpenObservation: (Long) -> Unit,
    onOpenRecognition: () -> Unit,
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val statistics by viewModel.statistics.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "plant Identify",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onSettings) { Text("设置") }
                    TextButton(onClick = onAddPlant) { Text("拍照") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPlant,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text("添加植物")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SearchEntry(onClick = onSearch)

            StatisticsCard(statistics)

            SectionTitle("植物档案")

            EmptyArchiveCard()

            // 仅 debug 构建可见的骨架自检入口。
            // Phase 1 还没有档案列表、也没有识别流程，植物详情 / 观察记录 / 识别结果
            // 三个页面无从进入，而验收标准要求 7 个页面都能跳转 —— 这里提供临时入口，
            // 后续 Phase 有真实入口后即移除。
            if (BuildConfig.DEBUG) {
                SkeletonSelfCheckCard(
                    onOpenPlantDetail = { onOpenPlantDetail(1L) },
                    onOpenObservation = { onOpenObservation(1L) },
                    onOpenRecognition = onOpenRecognition,
                )
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun SkeletonSelfCheckCard(
    onOpenPlantDetail: () -> Unit,
    onOpenObservation: () -> Unit,
    onOpenRecognition: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Phase 1 骨架自检（仅 debug 可见）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "植物详情与观察记录两个页面要到 Phase 5 有档案列表后才有真实入口，" +
                    "用这里的链接验证带参数导航是否正常。识别结果页已可从添加植物页进入，" +
                    "此处的入口用于验证无照片时的空状态。后续接入真实流程后移除本卡片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenPlantDetail) { Text("植物详情 1") }
                TextButton(onClick = onOpenObservation) { Text("观察记录 1") }
                TextButton(onClick = onOpenRecognition) { Text("识别结果空态") }
            }
        }
    }
}

/** 搜索入口 —— 首页只做入口，真正的搜索页在 Phase 5 */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "搜索植物" },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索植物",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "中文名 / 拉丁学名 / 科 / 属",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 统计卡片。
 *
 * 三项指标的口径已于 2026-09-18 确认（规格书原文的「植物记录」已弃用）。
 * 三者分母语义不同，因此必须并列展示并给出说明，避免被误读为同一维度。
 */
@Composable
private fun StatisticsCard(statistics: PlantStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricItem("不同植物", statistics.distinctPlants, Modifier.weight(1f))
                MetricItem("观察次数", statistics.observationCount, Modifier.weight(1f))
                MetricItem("照片数", statistics.imageCount, Modifier.weight(1f))
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "三项分母不同：不同植物为去重后的物种数，观察次数为累计记录数，照片数为图片文件数。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )

            if (statistics.familyCount > 0 || statistics.genusCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "科 ${statistics.familyCount} · 属 ${statistics.genusCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun EmptyArchiveCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "还没有植物档案",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "记录过的植物会以卡片形式列在这里，同一株植物的多次观察会归入同一份档案。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "当前为 Phase 1：工程骨架、导航与本地数据库已就位，拍照与识别将在后续阶段逐步开放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
