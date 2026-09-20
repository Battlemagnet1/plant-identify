package com.plantidentify.ui.screens.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.AppEdition
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.ui.components.BackIconButton
import com.plantidentify.ui.components.LocalImage

/**
 * 编辑植物档案（规格书第十六节：允许用户手动编辑）。
 *
 * ## 这一页能改什么
 *
 * **除了观察的时间与地点，档案里所有给人看的内容都能改** ——
 * 中文名、俗称、学名、科属、类型、置信度、八个百科字段、备注，
 * 以及照片的增删。理由见 [EditablePlantInfo] 的注释：AI 会认错，
 * 而用户手上就拿着那株植物。
 *
 * ## 为什么分成三块
 *
 * 十几个输入框堆在一起，用户找不到「我想改的那个」。
 * 按「身份 → 百科 → 照片」分区，和他脑子里的归类方式一致。
 *
 * ## 照片为什么是即时的
 *
 * 文本字段要按「保存修改」才生效，照片却是点一下立刻落库 ——
 * 这不是不一致，而是因为照片本来就没有「编辑中」这个中间态：
 * 要么已经导进来了，要么没有。页面上用文案把这点说清楚，
 * 免得用户以为「没点保存就都不会生效」。
 *
 * 只有中文名是必填 —— 其余字段 AI 可能没识别出来，
 * 强制填写会逼用户编造学名，那比留空更糟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantEditScreen(
    imageStore: ImageStore,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PlantEditViewModel,
    modifier: Modifier = Modifier,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val hasChanges by viewModel.hasChanges.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val photoBusy by viewModel.photoBusy.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    /** 正在为哪条观察加照片。系统选图器是异步的，回调里拿不到它 */
    var addTargetObservationId by remember { mutableStateOf<Long?>(null) }

    val pickPhotosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS_PER_PICK),
    ) { uris ->
        val target = addTargetObservationId
        if (target != null && uris.isNotEmpty()) viewModel.addPhotos(target, uris)
        addTargetObservationId = null
    }

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

            SectionHeader("基本信息")

            EditField(
                value = form.name,
                onValueChange = { value -> viewModel.update { copy(name = value) } },
                label = "正式中文名称（必填）",
                singleLine = true,
            )

            if (AppEdition.isFull) {
                EditField(
                    value = form.commonNames,
                    onValueChange = { value -> viewModel.update { copy(commonNames = value) } },
                    label = "常用名称 / 俗称",
                    placeholder = "多个用「、」分隔，如 紫薇花、痒痒树",
                    singleLine = true,
                )
            }

            EditField(
                value = form.latinName,
                onValueChange = { value -> viewModel.update { copy(latinName = value) } },
                label = "拉丁学名",
                placeholder = "如 Lagerstroemia indica",
                singleLine = true,
            )

            EditField(
                value = form.family,
                onValueChange = { value -> viewModel.update { copy(family = value) } },
                label = "科",
                singleLine = true,
            )

            EditField(
                value = form.genus,
                onValueChange = { value -> viewModel.update { copy(genus = value) } },
                label = "属",
                singleLine = true,
            )

            EditField(
                value = form.category,
                onValueChange = { value -> viewModel.update { copy(category = value) } },
                label = "植物类型",
                placeholder = "如 落叶灌木、一年生草本",
                singleLine = true,
            )

            EditField(
                value = form.confidencePercent,
                onValueChange = { value -> viewModel.update { copy(confidencePercent = value) } },
                label = "AI 置信度（0–100，可留空）",
                placeholder = "如 91",
                singleLine = true,
                isError = !form.confidenceValid,
                supportingText = if (form.confidenceValid) {
                    null
                } else {
                    "请填 0–100 之间的整数"
                },
            )

            SectionHeader("植物百科")

            Text(
                text = "这里是 AI 写的百科内容。发现哪一句不对就改成对的 —— " +
                    "模型当初返回的原文仍完整保存在这条观察的识别记录里。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            EditField(
                value = form.description,
                onValueChange = { value -> viewModel.update { copy(description = value) } },
                label = "植物简介",
                minLines = 2,
            )

            EditField(
                value = form.morphologicalFeatures,
                onValueChange = { value -> viewModel.update { copy(morphologicalFeatures = value) } },
                label = "形态特征",
                minLines = 2,
            )

            EditField(
                value = form.growthHabits,
                onValueChange = { value -> viewModel.update { copy(growthHabits = value) } },
                label = "生长习性",
                minLines = 2,
            )

            EditField(
                value = form.floweringPeriod,
                onValueChange = { value -> viewModel.update { copy(floweringPeriod = value) } },
                label = "花期",
                singleLine = true,
            )

            EditField(
                value = form.fruitingPeriod,
                onValueChange = { value -> viewModel.update { copy(fruitingPeriod = value) } },
                label = "果期",
                singleLine = true,
            )

            EditField(
                value = form.landscapeUses,
                onValueChange = { value -> viewModel.update { copy(landscapeUses = value) } },
                label = "园林用途",
                minLines = 2,
            )

            EditField(
                value = form.careAdvice,
                onValueChange = { value -> viewModel.update { copy(careAdvice = value) } },
                label = "养护建议",
                minLines = 2,
            )

            if (AppEdition.isFull) {
                EditField(
                    value = form.pestControl,
                    onValueChange = { value -> viewModel.update { copy(pestControl = value) } },
                    label = "病虫害防治",
                    placeholder = "如 蚜虫：发生时用吡虫啉喷雾，注意叶背",
                    minLines = 2,
                )

                // 照片增删只在完整版提供。
                // 注意这里藏的是**整个区块**（含下面那段说明），不是只藏按钮 ——
                // 否则基础版会看到一段「照片的增删立刻生效」的说明，
                // 却找不到任何可以增删照片的地方
                SectionHeader("照片")

                Text(
                    text = "照片的增删立刻生效，不需要点下面的「保存修改」；" +
                        "名称与百科内容要点了保存才生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                photos.forEach { observation ->
                    ObservationPhotosCard(
                        observation = observation,
                        imageStore = imageStore,
                        busy = photoBusy,
                        onAdd = {
                            addTargetObservationId = observation.observationId
                            pickPhotosLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onDelete = viewModel::deletePhoto,
                    )
                }
            }

            SectionHeader("备注")

            EditField(
                value = form.note,
                onValueChange = { value -> viewModel.update { copy(note = value) } },
                label = "备注",
                placeholder = "如 校园南门绿化带，人工栽培",
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
                Text(
                    when {
                        !form.canSave -> "请先修正必填项"
                        hasChanges -> "保存修改"
                        else -> "没有改动"
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * 统一的输入框。
 *
 * 包一层是为了让十几个字段的调用点只剩「标签 + 值 + 回调」三样 ——
 * 每个都手写 `Modifier.fillMaxWidth()` 与样式参数，改一次样式要改十几处。
 */
@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
    )
}

/** 一次观察的照片：缩略图 + 部位 + 逐张删除 + 追加 */
@Composable
private fun ObservationPhotosCard(
    observation: EditableObservation,
    imageStore: ImageStore,
    busy: Boolean,
    onAdd: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = observation.timeText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            observation.place?.let { place ->
                Text(
                    text = "地点：$place",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            if (observation.images.isEmpty()) {
                Text(
                    text = "这次观察还没有照片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(observation.images, key = { it.id }) { image ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LocalImage(
                                file = imageStore.resolve(image.imagePath),
                                contentDescription = "照片 ${image.roleLabel}",
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = image.roleLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { onDelete(image.id) },
                                enabled = !busy,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = "删除",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(if (busy) "处理中…" else "添加照片")
            }
        }
    }
}

/**
 * 一次最多选几张。
 *
 * 与 `CaptureDraft.MAX_IMAGES`（5）不同：那是**参与识别**的上限，
 * 而这里是事后补图存档，多几张不影响任何 AI 调用，卡在 5 张只会让
 * 「想把当时拍的都补上」这件事变得要来回操作。
 */
private const val MAX_PHOTOS_PER_PICK = 20
