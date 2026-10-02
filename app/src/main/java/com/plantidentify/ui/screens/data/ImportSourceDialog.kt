package com.plantidentify.ui.screens.data

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 导入前的确认框（v1.0.2 Phase 2）。
 *
 * ## 为什么必须有一个独立的确认框，而不是选完文件就开始导
 *
 * 数据管理页上并排躺着「从文件恢复」和「导入数据包」两个入口，
 * 两者都要用系统文件选择器挑一个 `.zip` —— **挑的文件长得一模一样**，
 * 后果却完全相反：恢复会清空现有档案，导入则一条都不动。
 *
 * 所以导入这一步插一个「说清楚要发生什么」的对话框：
 * 用户在这里看到的文字本身就是最后一道防线。
 *
 * ## 来源名为什么要用户填
 *
 * 包名是 `plantIdentify_Backup_2026-10-02_1330.zip` 这种，对人没有意义。
 * 而需求场景是「张三发来的数据」——名字该由用户给，
 * 预填文件名只是让他不必从空白开始。
 */
@Composable
fun ImportSourceDialog(
    defaultName: String,
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入数据包") },
        text = {
            Column {
                Text(
                    text = "这些植物会进入一个新的「协作文件夹」，不会动你现有的档案。" +
                        "重复的会在下一步让你逐条确认。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "文件：$fileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("数据来源（将成为文件夹名）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim().ifEmpty { "导入的数据" }) },
            ) { Text("开始导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
