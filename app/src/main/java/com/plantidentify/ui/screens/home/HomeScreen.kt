package com.plantidentify.ui.screens.home

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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.AppEdition
import com.plantidentify.BuildConfig
import com.plantidentify.R
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.model.PlantStatistics
import com.plantidentify.ui.screens.plants.PlantCard

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
    onOpenStats: () -> Unit,
    imageStore: ImageStore,
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val statistics by viewModel.statistics.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            // 用资源而不是硬编码：名称只有一个来源，
                            // 否则改一次名字就会漏掉一处
                            text = stringResource(R.string.app_name),
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
        // 用 LazyColumn 而不是 Column + verticalScroll：
        // 后者会把**全部卡片同时组合**，100 株档案就是 100 张卡片一起进组合、
        // 100 张缩略图同时开始解码 —— 滚动掉帧与内存峰值都出在这里。
        // LazyColumn 只组合可见项，配合 LocalImage 的内存缓存，
        // 来回滚动也不会重复解码。
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // 子项自带的 16dp 间距之外，左右留 20dp、底部留出悬浮按钮的位置
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "search", contentType = "entry") {
                SearchEntry(onClick = onSearch)
            }

            // 统计入口只在完整版出现 —— 基础版没有统计页
            if (AppEdition.isFull) {
                item(key = "stats", contentType = "entry") {
                    StatisticsCard(statistics, onClick = onOpenStats)
                }
            }

            item(key = "archive-title", contentType = "header") {
                SectionTitle(
                    if (cards.isEmpty()) "植物档案" else "植物档案（${cards.size}）",
                )
            }

            if (cards.isEmpty()) {
                item(key = "empty", contentType = "empty") { EmptyArchiveCard() }
            } else {
                items(
                    items = cards,
                    key = { it.plantId },
                    // 卡片是同一种布局，声明成同一类让 LazyColumn 能复用已组合的项
                    contentType = { "plant-card" },
                ) { card ->
                    PlantCard(
                        card = card,
                        imageStore = imageStore,
                        onClick = { onOpenPlantDetail(card.plantId) },
                    )
                }
            }

            // 仅 debug 构建可见的骨架自检入口。
            // 这几个页面在 Phase 5 已有真实入口，这张卡只是为了在数据为空时
            // 仍能进去看看页面结构，因此排在最后且不做强调。
            if (BuildConfig.DEBUG && cards.isEmpty()) {
                item(key = "skeleton", contentType = "entry") {
                    SkeletonSelfCheckCard(
                        onOpenPlantDetail = { onOpenPlantDetail(1L) },
                        onOpenObservation = { onOpenObservation(1L) },
                        onOpenRecognition = onOpenRecognition,
                    )
                }
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
private fun StatisticsCard(statistics: PlantStatistics, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
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

            // 卡片能点就必须让人看出来能点 —— 否则这个入口等于不存在
            Spacer(Modifier.height(10.dp))
            Text(
                text = "查看统计详情 ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
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
                // 这里原本写的是「当前为 Phase 1：…拍照与识别将在后续阶段逐步开放」，
                // 是 Phase 1 的过渡文案。到 Phase 5 功能已齐，这句话会随发布包
                // 一起发给用户，且与事实不符 —— 改成引导用户做第一步。
                text = "点右下角「添加植物」拍几张同一株植物的照片，" +
                    "识别结果会自动整理成档案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
