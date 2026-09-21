package com.plantidentify.ui.screens.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.Manifest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.AppEdition
import com.plantidentify.data.backup.BackupEntry
import com.plantidentify.data.export.ExportMode
import com.plantidentify.data.export.FileSharing
import com.plantidentify.data.export.formatBytes
import com.plantidentify.ui.components.BackIconButton
import com.plantidentify.ui.components.SectionCard
import java.io.File

/**
 * 数据管理（规格书第二十一、二十二节）。
 *
 * 位置记录、HTML 导出、备份与恢复都放在这一页 —— 它们同属
 * 「数据进出的口子」，散落在设置页各处会让用户找不到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
    onBack: () -> Unit,
    onOpenTrash: () -> Unit,
    viewModel: DataManagementViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val task by viewModel.task.collectAsStateWithLifecycle()
    val estimate by viewModel.estimate.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()
    val backups by viewModel.backups.collectAsStateWithLifecycle()
    val locationEnabled by viewModel.locationEnabled.collectAsStateWithLifecycle()
    val locationShareWithAi by viewModel.locationShareWithAi.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    // 打开开关后给用户的明确交代。用枚举而不是布尔值：
    // 「已经有权了」「刚授权成功」「被拒了」是三件不同的事，
    // 之前只有一句「下次进添加页会申请」—— 而那句话是假的。
    var locationHint by remember { mutableStateOf<LocationHint?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        // 同时申请「精确」与「大致」两档。
        //
        // 只要 COARSE 的话，反查出的地点永远停在「XX区」——
        // 网络定位的误差常在几百米到一两公里，只能落到区级。
        // 想要「某路 / 某号 / 某小区」这一级就必须有 FINE 走 GPS。
        //
        // 用户在权限框里选「大致位置」也完全没问题：那时只授予 COARSE，
        // LocationProvider 会自动退回网络定位，只是精度差一档。
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.any { it }
        locationHint = if (granted) LocationHint.GRANTED else LocationHint.DENIED
    }

    // 恢复备份：用系统文件选择器。用 */* 而不是只写 application/zip ——
    // 各家文件提供方对 zip 的 MIME 报法不一致，选不出来比选错更让人困惑，
    // 反正包格式会在读取时严格校验
    val pickBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.prepareRestore(uri) }

    // 只弹提示，**不**顺手清掉任务状态。
    //
    // 曾经写成「弹完 Snackbar 就 consumeTask()」，结果是：导出/备份完成后那张
    // 结果卡片（承载「分享」按钮）在 Snackbar 消失的瞬间一起没了 ——
    // 用户根本来不及点，导出的文件也就永远送不出去。
    // 成功的结果要留在页面上，直到用户主动关掉。
    LaunchedEffect(task) {
        val text = when (val current = task) {
            is DataTask.Done -> current.message
            is DataTask.Failed -> current.message
            else -> null
        } ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        // 失败没有后续动作，提示过就清掉；成功要留着让用户点分享
        if (task is DataTask.Failed) viewModel.consumeTask()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("数据管理") },
                navigationIcon = { BackIconButton(onClick = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .semantics { contentDescription = "数据管理" },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LocationCard(
                enabled = locationEnabled,
                shareWithAi = locationShareWithAi,
                onToggleShare = viewModel::setShareLocationWithAi,
                onToggle = { checked ->
                    viewModel.setLocationEnabled(checked)
                    if (!checked) {
                        locationHint = null
                    } else if (viewModel.hasLocationPermission()) {
                        locationHint = LocationHint.ALREADY
                    } else {
                        // 开关只是「用户愿意记录」，坐标还得靠系统权限。
                        // 既然用户此刻正在做这个决定，就顺手把权限问了 ——
                        // 拖到别处再问，用户根本不知道还有一步没做
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ),
                        )
                    }
                },
            )

            ExportCard(
                estimate = estimate,
                mode = mode,
                running = task is DataTask.Running,
                onSelectMode = viewModel::setMode,
                onExport = viewModel::exportHtml,
            )

            BackupCard(
                running = task is DataTask.Running,
                backups = backups,
                onBackup = viewModel::backup,
                onPickBackup = { pickBackupLauncher.launch(arrayOf("*/*")) },
                onRestoreLocal = viewModel::prepareRestoreFromLocal,
                onDeleteBackup = viewModel::deleteBackup,
            )

            // 回收站只在完整版出现（基础版没有这个页面）
            if (AppEdition.isFull) {
                SectionCard(title = "回收站") {
                    Text(
                        text = "删除的植物档案会先放进回收站，可随时恢复；" +
                            "只有在那里点「彻底删除」才会连同照片一起清除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenTrash,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开回收站") }
                }
            }

            // 进行中 / 刚完成的任务
            when (val current = task) {
                is DataTask.Running -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(current.label, style = MaterialTheme.typography.bodyMedium)
                        current.progress?.let { progress ->
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                is DataTask.Done -> if (current.filePath != null && current.fileName != null) {
                    ResultCard(
                        message = current.message,
                        // 分享之后**不**清掉卡片：用户可能想再分享一次
                        // （发给网盘、再发一份到邮箱），关掉是他自己的决定
                        onShare = {
                            FileSharing.share(
                                context = context,
                                file = File(current.filePath),
                                mimeType = FileSharing.mimeTypeOf(current.fileName),
                                subject = current.fileName,
                            )
                        },
                        onDismiss = viewModel::consumeTask,
                    )
                }

                else -> Unit
            }

            Text(
                text = "导出与备份的文件先存放在应用私有目录，" +
                    "点「分享」即可发送到网盘、邮件或电脑。" +
                    "本应用不联网保存你的档案（AI 识别除外）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text("恢复这份备份？") },
            text = {
                Text(
                    "备份时间：${pending.manifest.createdAtText}\n" +
                        "包含 ${pending.manifest.plantCount} 株植物 · " +
                        "${pending.manifest.observationCount} 次观察 · " +
                        "${pending.manifest.imageCount} 张照片\n\n" +
                        "⚠️ 恢复会清空当前全部档案并替换为备份内容，此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) { Text("取消") }
            },
        )
    }

    locationHint?.let { hint ->
        AlertDialog(
            onDismissRequest = { locationHint = null },
            title = { Text(hint.title) },
            text = { Text(hint.body) },
            confirmButton = {
                TextButton(onClick = { locationHint = null }) { Text("知道了") }
            },
        )
    }
}

/**
 * 打开位置开关后的三种结果。
 *
 * 分开表达的原因：这三种情况下应用**能做到的事完全不同**，
 * 用一句笼统的「已开启」盖过去，用户下次发现没记上地点时
 * 只会觉得是应用坏了。
 */
private enum class LocationHint(val title: String, val body: String) {
    ALREADY(
        title = "已开启地点记录",
        body = "定位权限也已就绪。保存植物档案时会一并记下拍摄地点，" +
            "地点只存在本机。\n\n" +
            "若还想让它参与识别判断，可另外开启下方的「用地点辅助识别」——" +
            "那一步才会把地点发给 AI 服务。",
    ),
    GRANTED(
        title = "已开启并获得定位权限",
        body = "之后保存植物档案时会一并记下拍摄地点，地点只存在本机。\n\n" +
            "若还想让它参与识别判断，可另外开启下方的「用地点辅助识别」——" +
            "那一步才会把地点发给 AI 服务。",
    ),
    DENIED(
        title = "缺少定位权限",
        body = "开关已经打开，但系统定位权限被拒绝，" +
            "暂时记不了地点。\n\n" +
            "可以稍后在手机「设置 → 应用 → 权限」里授予；" +
            "也可以在「添加植物」页的地点行上点一下重试。\n\n" +
            "不影响拍照、识别与档案。",
    ),
}

/**
 * 位置设置卡。
 *
 * 两个开关而不是一个，是因为它们管的是**两件隐私含义完全不同**的事：
 *  - 「记录观察地点」= 在本机记下一个坐标，数据不出设备
 *  - 「用地点辅助识别」= 把坐标随识别请求发给第三方 AI 服务
 *
 * 合成一个开关就等于「想记地点就必须同意上传」，那是不合理的捆绑。
 * 后者默认关闭，且只在已经开启前者时才显示 —— 没在记地点就没有地点可发，
 * 摆一个无效开关只会让人困惑。
 */
@Composable
private fun LocationCard(
    enabled: Boolean,
    shareWithAi: Boolean,
    onToggle: (Boolean) -> Unit,
    onToggleShare: (Boolean) -> Unit,
) {
    SectionCard(title = "位置") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("记录观察地点", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "保存档案时一并记下拍摄地点。" +
                        if (shareWithAi && enabled) {
                            "地点会随识别请求发送给你配置的 AI 服务作为参考。"
                        } else {
                            "地点只存在本机，不会上传。"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        if (enabled) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("用地点辅助识别", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        // 注意：Compose 的 Text **不渲染 markdown**，
                        // 写 **参考** 会在屏幕上原样显示星号（Phase6+ 踩过这个坑）
                        text = "把拍摄地点作为参考线索发给 AI 服务，" +
                            "帮助它优先考虑该地区常见的物种。\n\n" +
                            "开启后你的行踪信息会交给该服务商 —— 默认关闭，请自行权衡。\n" +
                            "关闭与否都不影响识别本身，只是少一条参考线索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = shareWithAi, onCheckedChange = onToggleShare)
            }
        }
    }
}

@Composable
private fun ExportCard(
    estimate: com.plantidentify.data.export.ExportEstimate?,
    mode: ExportMode,
    running: Boolean,
    onSelectMode: (ExportMode) -> Unit,
    onExport: () -> Unit,
) {
    SectionCard(title = "导出 HTML") {
        Text(
            text = "把全部植物档案导出成一份可在电脑浏览器打开的 HTML 报告。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        estimate?.let { value ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = "当前 ${value.plantCount} 株植物 · ${value.photoCount} 张照片 · " +
                    "原图共 ${formatBytes(value.originalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "按所选模式预计约 ${formatBytes(value.estimateFor(mode))}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (value.requiresZip) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "照片数或原图体积已超过单文件上限，导出将自动改为 " +
                        "「HTML + 图片文件夹（zip）」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (value.thumbnailExceedsTarget) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "缩略图模式预计超过 50 MB，单文件打开会比较慢，" +
                        "建议改用 zip 模式（可选原图）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ExportMode.THUMBNAIL,
                onClick = { onSelectMode(ExportMode.THUMBNAIL) },
                label = { Text("缩略图（推荐）") },
            )
            FilterChip(
                selected = mode == ExportMode.ORIGINAL,
                onClick = { onSelectMode(ExportMode.ORIGINAL) },
                label = { Text("原图") },
            )
        }

        if (mode == ExportMode.ORIGINAL) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "原图模式会把每张照片完整嵌进 HTML，文件可能非常大，" +
                    "部分设备上会打不开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
        ) { Text("导出 HTML") }
    }
}

@Composable
private fun BackupCard(
    running: Boolean,
    backups: List<BackupEntry>,
    onBackup: () -> Unit,
    onPickBackup: () -> Unit,
    onRestoreLocal: (BackupEntry) -> Unit,
    onDeleteBackup: (BackupEntry) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<BackupEntry?>(null) }

    SectionCard(title = "备份与恢复") {
        Text(
            text = "备份包含全部档案、观察记录、AI 分析结果、备注与照片原图，" +
                "可换机迁移。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onBackup,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
        ) { Text("备份数据") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onPickBackup,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
        ) { Text("从文件恢复（换机用）") }

        // 本机备份列表 —— 备份存在应用私有目录，系统文件选择器看不到那里，
        // 所以必须在这里给出直接恢复的入口
        if (backups.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "本机备份（${backups.size}）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            backups.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.manifest?.createdAtText ?: entry.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = entry.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onRestoreLocal(entry) },
                        enabled = !running && entry.manifest != null,
                    ) { Text("恢复") }
                    TextButton(
                        onClick = { pendingDelete = entry },
                        enabled = !running,
                    ) { Text("删除") }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个备份？") },
            text = {
                Text(
                    "${entry.manifest?.createdAtText ?: entry.fileName}\n\n" +
                        "只删除备份文件，不影响当前档案。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteBackup(entry)
                    },
                ) { Text("删除备份") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ResultCard(message: String, onShare: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare) { Text("分享") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

