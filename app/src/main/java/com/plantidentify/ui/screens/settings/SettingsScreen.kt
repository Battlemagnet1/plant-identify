package com.plantidentify.ui.screens.settings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.data.ai.AiEndpointConfig
import com.plantidentify.data.ai.AiPreset
import com.plantidentify.data.ai.PromptStrategy
import com.plantidentify.ui.components.BackIconButton

/**
 * 设置页（规格书第十节、第二十三节）。
 *
 * Phase 3 只开放**视觉识别服务**的配置：文字分析要等 Phase 4 接入
 * TextProvider 之后才有实际作用，现在放出来只会让用户配一个用不上的东西。
 *
 * 页面刻意把「保存」与「测试连接」分成两个按钮：
 * 测试用的是**表单当前值**而非已保存的配置 —— 用户改完地址后第一件事
 * 就是想试试对不对，要求先保存才能测会很别扭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val apiKeyVisible by viewModel.apiKeyVisible.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val testOutcome by viewModel.testOutcome.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val hasSavedKey by viewModel.hasSavedKey.collectAsStateWithLifecycle()
    val strategy by viewModel.strategy.collectAsStateWithLifecycle()

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
                title = { Text("设置") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .semantics { contentDescription = "设置" },
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ConfigStatusCard(form = form, hasSavedKey = hasSavedKey) }

            item { SectionTitle("视觉识别服务") }

            item {
                PresetChips(
                    selected = form.preset,
                    onSelect = viewModel::selectPreset,
                )
            }

            item {
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = viewModel::updateBaseUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    placeholder = { Text("https://服务商域名/v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = {
                        Text(
                            text = "填到 /v1 即可，末尾是否带斜杠都可以",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            item {
                OutlinedTextField(
                    value = form.model,
                    onValueChange = viewModel::updateModel,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型名") },
                    placeholder = { Text("例如 qwen-vl-max") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = form.preset.modelHint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            item {
                OutlinedTextField(
                    value = form.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    placeholder = { Text("粘贴服务商提供的 Key") },
                    singleLine = true,
                    // 默认隐藏（验收标准 ④）。注意：这里只是视觉遮挡，
                    // 真正防止泄漏的是「不入日志、不落明文盘、不随错误信息外泄」三条
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = viewModel::toggleApiKeyVisible) {
                            Text(if (apiKeyVisible) "隐藏" else "显示")
                        }
                    },
                    supportingText = {
                        Text(
                            text = "加密存放在本机（Android Keystore），不会上传到任何服务器",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            item {
                TestConnectionSection(
                    testing = testing,
                    outcome = testOutcome,
                    onTest = viewModel::testConnection,
                )
            }

            item {
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving && form.isUsable,
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("保存配置")
                    }
                }
            }

            item { SectionTitle("识别策略") }

            item {
                StrategySection(
                    selected = strategy,
                    onSelect = viewModel::selectStrategy,
                )
            }

            item { SecurityNoteCard() }

            item {
                OutlinedButton(
                    onClick = viewModel::clearAll,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清除全部 AI 配置")
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------- 分区组件 ----------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** 顶部状态卡：一眼看出当前配置缺什么 */
@Composable
private fun ConfigStatusCard(form: AiEndpointConfig, hasSavedKey: Boolean) {
    val isReady = form.isUsable
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReady) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isReady) "配置已就绪" else "尚未配置完成",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (isReady) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isReady) {
                    val keyState = if (hasSavedKey) "API Key 已保存" else "API Key 尚未保存"
                    "当前使用 ${form.preset.displayName} · $keyState"
                } else {
                    "还缺少：${form.missingFields.joinToString("、")}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isReady) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }
    }
}

/** 服务商预设选择 */
@Composable
private fun PresetChips(
    selected: AiPreset,
    onSelect: (AiPreset) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(AiPreset.entries.toList(), key = { it.name }) { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                label = {
                    Text(
                        text = preset.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/** 测试连接 */
@Composable
private fun TestConnectionSection(
    testing: Boolean,
    outcome: TestOutcome?,
    onTest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onTest,
            modifier = Modifier.fillMaxWidth(),
            enabled = !testing,
        ) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("测试连接")
            }
        }

        outcome?.let { result ->
            val (container, content) = when (result) {
                is TestOutcome.Success -> MaterialTheme.colorScheme.primaryContainer to
                    MaterialTheme.colorScheme.onPrimaryContainer
                is TestOutcome.TextOnly -> MaterialTheme.colorScheme.tertiaryContainer to
                    MaterialTheme.colorScheme.onTertiaryContainer
                is TestOutcome.Failed -> MaterialTheme.colorScheme.errorContainer to
                    MaterialTheme.colorScheme.onErrorContainer
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = container),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    when (result) {
                        is TestOutcome.Success -> {
                            Text(
                                text = "连接正常",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = content,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "模型 ${result.model} 可用，往返 ${result.latencyMs} ms，" +
                                    "并且接受图片输入。",
                                style = MaterialTheme.typography.bodySmall,
                                color = content,
                            )
                        }

                        is TestOutcome.TextOnly -> {
                            Text(
                                text = "该模型不接受图片",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = content,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "模型 ${result.model} 连接正常，但它看来是纯文本模型 —— " +
                                    "植物识别需要能看图的模型，请换一个视觉模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = content,
                            )
                        }

                        is TestOutcome.Failed -> {
                            Text(
                                text = "连接失败",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = content,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = result.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = content,
                            )
                            result.hint?.let { hint ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = content,
                                )
                            }
                            result.detail?.let { detail ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "服务返回：$detail",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = content,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 识别策略切换（服务于分析报告 Part 3.4 的 A/B 对照实验） */
@Composable
private fun StrategySection(
    selected: PromptStrategy,
    onSelect: (PromptStrategy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "这一项用于验证「多图联合识别」的效果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "切换后重新识别同一组照片，对比两种策略给出的结果，" +
                        "就能看出部位标注是否有帮助。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PromptStrategy.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onSelect(option) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = (if (option == selected) "● " else "○ ") + option.displayName,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/** 安全说明 */
@Composable
private fun SecurityNoteCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "关于你的数据",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "• API Key 用 Android Keystore 加密后存放在本机，不写入日志，不上传",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "• 识别时只会把压缩后的照片（最长边 1536px）发送给你配置的服务商，原图始终留在本机",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "• AI 给出的结果是参考意见，不作为专业鉴定依据",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
