package com.plantidentify.ui.screens.cleaning

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import com.plantidentify.domain.cleaning.CleaningIssue
import com.plantidentify.domain.cleaning.CleaningIssueCategory
import com.plantidentify.domain.cleaning.CleaningIssueType
import com.plantidentify.domain.cleaning.CleaningSeverity
import com.plantidentify.domain.cleaning.RecordSnapshot

/**
 * 数据清洗中心（Phase 3，完整版专属）。
 *
 * ## 页面结构对应三个不同层级的问题
 *
 * 1. **健康度**（一个数字）—— 「要不要现在处理」
 * 2. **分类计数**（四个数字）—— 「问题集中在哪里」
 * 3. **问题列表**（一行一条）—— 「具体是哪一株」
 *
 * 三个按钮的代价差别很大（见 `CleaningViewModel` 的注释），
 * 所以不做成一个「检查」加几个开关，而是**三个并列的按钮**，
 * 各自带上一句代价说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataCleaningScreen(
    viewModel: CleaningViewModel,
    onBack: () -> Unit,
    onOpenIssue: (Long) -> Unit,
    onOpenMerge: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message, state.lastScanNote) {
        val text = state.message ?: state.lastScanNote
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据清洗") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "health") {
                HealthCard(state)
            }

            item(key = "actions") {
                ActionsCard(
                    scanning = state.scanning,
                    advising = state.advising,
                    pendingAi = state.pendingAi,
                    onScan = { viewModel.scan(deep = false) },
                    onDeepScan = { viewModel.scan(deep = true) },
                    onAdvise = viewModel::advise,
                )
            }

            if (state.issues.isEmpty()) {
                item(key = "empty") {
                    EmptyCard(state.health.totalRecords)
                }
            } else {
                item(key = "list-header") {
                    Text(
                        text = "待处理 ${state.issues.size} 项",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(state.issues, key = { it.fingerprint }) { issue ->
                    IssueCard(
                        issue = issue,
                        names = issue.recordIds.mapNotNull { state.snapshots[it]?.name },
                        // 跳转用的是**问题自己的 id**，不是涉及的档案 id。
                        // 这两个 id 空间完全无关，混用的后果是「点哪张卡都进同一条问题」
                        // ——而且看起来还挺合理（确实打开了某个详情页），
                        // 只有对着具体内容才能发现不对
                        onOpen = { onOpenIssue(issue.id) },
                        onMerge = { onOpenMerge(issue.id) },
                    )
                }
            }

            item(key = "footer") {
                Text(
                    text = "清洗只做提示，任何修改都要你确认后才生效。" +
                        "被合并或删除的档案进入回收站，可以随时恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HealthCard(state: CleaningUiState) {
    val health = state.health
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${health.score}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = " 分",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = healthLabel(health.score),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { health.score / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = if (health.openIssues == 0) {
                    "共 ${health.totalRecords} 株档案，没有发现需要处理的问题"
                } else {
                    "共 ${health.totalRecords} 株档案，${health.openIssues} 项待处理，" +
                        "涉及 ${health.affectedRecords} 株"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (health.openIssues > 0) {
                Spacer(Modifier.height(12.dp))
                CleaningIssueCategory.entries.forEach { category ->
                    val count = health.countOf(category)
                    if (count > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(category.label, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "$count 项",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(
    scanning: Boolean,
    advising: Boolean,
    pendingAi: Int,
    onScan: () -> Unit,
    onDeepScan: () -> Unit,
    onAdvise: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onScan, enabled = !scanning && !advising) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("开始检查")
                }
                OutlinedButton(onClick = onDeepScan, enabled = !scanning && !advising) {
                    Text("全库深度检查")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "「开始检查」只看上次之后改过的档案；「全库深度检查」忽略上次结果，" +
                    "把每一株都重新过一遍。两者都在本机完成，不联网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onAdvise,
                enabled = !scanning && !advising && pendingAi > 0,
            ) {
                if (advising) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (pendingAi > 0) "AI 复核（$pendingAi 组）" else "AI 复核")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (pendingAi > 0) {
                    "让 AI 判断这些「拿不准」的候选是不是同一种。会调用文字模型并产生费用，" +
                        "一次最多 200 组。"
                } else {
                    "没有需要复核的候选 —— 名称、学名相同的本机能直接判定，不用花钱。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyCard(totalRecords: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("数据很干净", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (totalRecords == 0) {
                    "还没有档案。添加植物后回这里检查。"
                } else {
                    "没有发现缺失字段、异常坐标、重复照片或疑似重复的档案。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一条问题。
 *
 * 卡片上**直接写出涉及哪几株**（而不是「2 株植物」）——
 * 用户判断「要不要合并」靠的就是「紫薇 和 百日红」这两个名字。
 */
@Composable
private fun IssueCard(
    issue: CleaningIssue,
    names: List<String>,
    onOpen: () -> Unit,
    onMerge: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeverityChip(issue.severity)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = issue.type.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (issue.similarity != null) {
                    Text(
                        text = "${(issue.similarity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            if (names.isNotEmpty()) {
                Text(
                    text = names.joinToString("  ↔  "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = issue.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (issue.aiUsed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "AI 复核：" + (issue.aiReason ?: "已判定"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (issue.type == CleaningIssueType.POSSIBLE_DUPLICATE) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onMerge) { Text("合并预览") }
            }
        }
    }
}

@Composable
private fun SeverityChip(severity: CleaningSeverity) {
    // 用「文字 + 底色」而不是纯色块：色觉障碍的用户看不出红黄绿的区别，
    // 而这三个字直接把严重程度说清楚了
    val container = when (severity) {
        CleaningSeverity.HIGH -> MaterialTheme.colorScheme.errorContainer
        CleaningSeverity.MEDIUM -> MaterialTheme.colorScheme.secondaryContainer
        CleaningSeverity.LOW -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = container, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = severity.label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun healthLabel(score: Int): String = when {
    score >= 95 -> "很干净"
    score >= 80 -> "基本正常"
    score >= 50 -> "有待整理"
    else -> "问题较多"
}
