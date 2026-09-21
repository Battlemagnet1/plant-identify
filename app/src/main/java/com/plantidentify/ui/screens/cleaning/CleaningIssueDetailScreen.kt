package com.plantidentify.ui.screens.cleaning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.cleaning.CleaningIssueType
import com.plantidentify.domain.cleaning.RecordSnapshot
import com.plantidentify.ui.components.LocalImage

/**
 * 清洗问题详情（Phase 3）。
 *
 * ## 两种形态，因为用户要做的判断不同
 *
 * - **疑似重复**：要判断「这是不是同一株」→ 把两株**并排**摆出来
 *   （照片、名称、学名、科属），把相似度与 AI 结论放在中间
 * - **字段/图片异常**：不需要比较，只需要知道「哪一株、哪里不对、
 *   去哪里改」→ 一株一张卡 + 跳转到档案的入口
 *
 * 把它做成同一个「详情页模板」会让前者的比较失效（用户要来回滚动才能对比），
 * 所以按类型分叉是刻意的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleaningIssueDetailScreen(
    viewModel: CleaningIssueDetailViewModel,
    imageStore: ImageStore,
    onBack: () -> Unit,
    onOpenPlant: (Long) -> Unit,
    onOpenMerge: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("问题详情") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            state.gone -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("这条问题已经不存在了", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "涉及的档案可能已经被合并或删除。返回刷新一下列表即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                val issue = state.issue ?: return@Scaffold
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = issue.type.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(issue.reason, style = MaterialTheme.typography.bodyMedium)
                                if (issue.aiUsed) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = "AI 复核：" + (issue.aiReason ?: "已判定"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }

                    if (issue.type == CleaningIssueType.POSSIBLE_DUPLICATE &&
                        state.records.size == 2
                    ) {
                        item(key = "compare") {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                state.records.forEach { record ->
                                    PlantBrief(
                                        record = record,
                                        coverPath = state.covers[record.id],
                                        imageStore = imageStore,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenPlant(record.id) },
                                    )
                                }
                            }
                        }
                        if (issue.similarity != null) {
                            item(key = "similarity") {
                                Text(
                                    text = "本机算出的名称相似度：${(issue.similarity * 100).toInt()}%" +
                                        "。相似不等于同一种 —— 判断请结合学名与科属。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item(key = "merge") {
                            // 同样用问题 id：合并预览要靠它去 loadMergePlan
                            OutlinedButton(onClick = { onOpenMerge(issue.id) }) {
                                Text("合并预览")
                            }
                        }
                    } else {
                        item(key = "plants") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                state.records.forEach { record ->
                                    PlantBrief(
                                        record = record,
                                        coverPath = state.covers[record.id],
                                        imageStore = imageStore,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { onOpenPlant(record.id) },
                                    )
                                }
                            }
                        }
                        item(key = "hint") {
                            Text(
                                text = "点开档案即可修改字段或补照片 —— " +
                                    "改完之后回到这一页会自动发现这条问题已解决。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item(key = "ignore") {
                        OutlinedButton(onClick = viewModel::ignore) { Text("忽略这条") }
                    }
                }
            }
        }
    }
}

/**
 * 一株植物的简介卡。
 *
 * 只放判断「是不是同一株」需要的字段：照片、名称、学名、科属、规模。
 * 不放描述正文 —— 两段 200 字的描述并排放在手机上没法对比，
 * 用户真正会看的是学名和科属。
 */
@Composable
private fun PlantBrief(
    record: RecordSnapshot,
    coverPath: String?,
    imageStore: ImageStore,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            val cover = coverPath
            if (cover != null) {
                LocalImage(
                    file = imageStore.resolve(cover),
                    contentDescription = record.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(0.dp)),
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text("没有照片", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = record.name.ifBlank { "（无名称）" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                LabeledLine("学名", record.latinName)
                LabeledLine("科", record.family)
                LabeledLine("属", record.genus)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${record.observationCount} 次观察 · ${record.imageCount} 张照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String?) {
    Text(
        text = "$label：${value?.takeIf { it.isNotBlank() } ?: "—"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
