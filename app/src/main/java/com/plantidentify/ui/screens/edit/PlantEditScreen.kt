package com.plantidentify.ui.screens.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.ui.components.BackIconButton

/**
 * 编辑植物档案（规格书第十六节：允许用户手动编辑）。
 *
 * 只有中文名是必填 —— 其余字段 AI 可能没识别出来，
 * 强制填写会逼用户编造学名，那比留空更糟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PlantEditViewModel,
    modifier: Modifier = Modifier,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val hasChanges by viewModel.hasChanges.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(saved) {
        if (saved) onSaved()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("编辑植物档案") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Text(
                    text = "这里只改「是哪种植物」相关的字段。置信度与植物百科由 AI 生成，" +
                        "观察记录由识别流程维护 —— 它们不在这里编辑，以免档案失去可追溯性。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = viewModel::setName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("中文名称（必填）") },
                singleLine = true,
            )

            OutlinedTextField(
                value = form.latinName,
                onValueChange = viewModel::setLatinName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("拉丁学名") },
                placeholder = { Text("如 Lagerstroemia indica") },
                singleLine = true,
            )

            OutlinedTextField(
                value = form.family,
                onValueChange = viewModel::setFamily,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("科") },
                singleLine = true,
            )

            OutlinedTextField(
                value = form.genus,
                onValueChange = viewModel::setGenus,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("属") },
                singleLine = true,
            )

            OutlinedTextField(
                value = form.category,
                onValueChange = viewModel::setCategory,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("植物类型") },
                placeholder = { Text("如 落叶灌木、一年生草本") },
                singleLine = true,
            )

            OutlinedTextField(
                value = form.note,
                onValueChange = viewModel::setNote,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注") },
                placeholder = { Text("如 校园南门绿化带，人工栽培") },
                minLines = 3,
            )

            Text(
                text = "修改名称或学名后，下次识别同一株植物时可能就不再匹配到这份档案 —— " +
                    "归并判断依赖名称与学名。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth(),
                enabled = form.canSave && hasChanges,
            ) {
                Text(if (hasChanges) "保存修改" else "没有改动")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
