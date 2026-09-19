package com.plantidentify.ui.screens.stats

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.plantidentify.domain.model.PlantStatistics
import com.plantidentify.ui.components.BackIconButton

/**
 * 植物统计（规格书第二十节）。
 *
 * ## 口径（2026-09-18 已确认，不要再改）
 *
 * | 指标     | 定义                     |
 * |---------|--------------------------|
 * | 不同植物 | plant_record 行数         |
 * | 观察次数 | plant_observation 行数    |
 * | 照片数   | observation_image 行数    |
 *
 * 规格书原文用的是「植物记录」，这个词把「一株植物」和「一次观察」混成了
 * 一个概念 —— 同一株紫薇观察 3 次到底算 1 还是 3，从字面上读不出来。
 * 因此这里一律不用该词，三个指标各自点名自己的单位。
 *
 * ## 为什么要把「分母不同」写在页面上
 *
 * 三项数字并排放在一起，用户的第一反应是「它们应该是对得上的」——
 * 看到 1 / 3 / 6 会以为哪里算错了。写清楚各自数的是什么，
 * 比事后被问「为什么照片数比观察次数多」要省事得多。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel,
    modifier: Modifier = Modifier,
) {
    val statistics by viewModel.statistics.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("植物统计") },
                navigationIcon = { BackIconButton(onClick = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .semantics { contentDescription = "植物统计" },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PrimaryMetricsCard(statistics)

            TaxonomyCard(statistics)

            Text(
                text = "以上数字直接来自本地数据库的行数，每次进入本页重新统计 —— " +
                    "不做本地累计，因此永远与植物档案里实际存在的内容一致。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 三项主指标：不同植物 / 观察次数 / 照片数 */
@Composable
private fun PrimaryMetricsCard(statistics: PlantStatistics) {
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
                MetricColumn("不同植物", statistics.distinctPlants)
                MetricColumn("观察次数", statistics.observationCount)
                MetricColumn("照片数", statistics.imageCount)
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "三项分母不同：不同植物为去重后的物种数，" +
                    "观察次数为累计记录数，照片数为图片文件数。" +
                    "同一种植物观察 3 次、每次拍 2 张，即 1 / 3 / 6。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 科 / 属的数量 —— 去重后的分类阶元数 */
@Composable
private fun TaxonomyCard(statistics: PlantStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricColumn("科", statistics.familyCount)
                MetricColumn("属", statistics.genusCount)
                // 占位，让「科 / 属」两项与上面的三项对齐成等宽列
                Spacer(Modifier.weight(1f).fillMaxWidth())
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "科与属为去重后的分类阶元数量，未填写科属的档案不计入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
