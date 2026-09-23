package com.plantidentify.ui.screens.stress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plantidentify.ui.components.SectionCard

/**
 * 压测工具（Phase 4）。
 *
 * ## 为什么造数据要有界面，而不是一个 SQL 脚本
 *
 * 方案里先写的是「脚本生成 SQL 推到设备执行」，但那条路走不通也不该走：
 * 手拼 SQL 造出来的数据只要有一处与实体定义不一致（列名、外键、相对路径
 * 的格式），压测出来的「慢」就可能是假数据形状导致的 —— 而你会以为是代码慢。
 * 走真实实体插入则保证外键、索引与路径都是**生产环境下真实的样子**。
 *
 * ## 三档的意义
 *
 * 100 / 1000 / 10000 不是「随便挑的三个数」：100 档验证功能正确，
 * 1000 档看趋势是否线性，**10000 档才是用来暴露 O(N²) 的** ——
 * 候选集、全量载入、统计查询这三处的问题都只在那一档才会显形。
 *
 * 这一页只在 `BuildConfig.DEBUG` 下 有入口。release 构建里
 * `if (BuildConfig.DEBUG)` 是编译期常量条件，R8 会把整块（连同对本页的引用）
 * 折掉，所以发布包里既进不来、也不占体积。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StressToolScreen(
    viewModel: StressToolViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var pendingClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("压测工具") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "notice") {
                SectionCard(title = "仅调试构建") {
                    Text(
                        text = "这一页用来造压测数据：直接写数据库与文件系统，" +
                            "会产生上万条档案和上万张照片。正式版里没有入口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "counts") {
                val c = state.counts
                SectionCard(title = "当前数据量") {
                    CountRow("植物档案", "${c.plants}")
                    CountRow("观察记录", "${c.observations}")
                    CountRow("照片", "${c.images}")
                    CountRow("待处理问题", "${c.openIssues}")
                    CountRow("图片占用", formatBytes(c.imageBytes))
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = viewModel::refreshCounts) { Text("刷新") }
                }
            }

            item(key = "seed") {
                SectionCard(title = "造数据") {
                    Text(
                        // 把这条写出来是因为「造完数据后界面变慢」很容易被当成
                        // 被测代码的性能问题，其实是数据量本身
                        text = "每株 1–3 次观察、每次 1–2 张照片。" +
                            "照片放在 images/stress/ 下，清空时整目录删掉，不碰真实照片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100, 1000, 10000).forEach { count ->
                            OutlinedButton(
                                onClick = { viewModel.seed(count) },
                                enabled = !state.busy,
                            ) { Text("$count 株") }
                        }
                    }
                    if (state.busy) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (state.progress.isNotEmpty()) {
                                "生成中… ${state.progress}"
                            } else {
                                "处理中…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { pendingClear = true },
                        enabled = !state.busy,
                    ) { Text("清空全部数据") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "清空会删除全部档案、观察、照片、清洗问题与识别任务。" +
                            "每一档压测都要先清空，否则上一档的数据会让这一档测不准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.lines.isNotEmpty()) {
                item(key = "log") {
                    SectionCard(title = "日志") {
                        // 最新的在最上面：压测时要看的是「刚刚那一档的结果」，
                        // 而不是几十条之前的那条
                        state.lines.reversed().forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text("清空全部数据？") },
            text = {
                Text(
                    "库里所有植物档案、观察、照片、清洗问题与识别任务都会被删除，" +
                        "无法恢复。这个包的 AI 配置不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    pendingClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CountRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
