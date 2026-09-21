package com.plantidentify.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.AppEdition
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.local.entity.ObservationImageEntity
import com.plantidentify.data.local.entity.PlantRecordEntity
import com.plantidentify.data.location.placeText
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.data.storage.MediaSaver
import com.plantidentify.domain.model.ConfidenceGrade
import com.plantidentify.ui.components.BackIconButton
import com.plantidentify.ui.components.ImageViewerHost
import com.plantidentify.ui.components.LocalImage
import com.plantidentify.ui.components.ViewerImage
import com.plantidentify.ui.components.label
import com.plantidentify.ui.components.rememberImageViewerState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 植物详情页（规格书第十六节）。
 *
 * Phase 4 交付的是**可查看**：识别结果、照片、植物百科。
 * 编辑、删除、观察历史、位置入口等归 Phase 5。
 *
 * ## 关于置信度的表述
 *
 * 页面上统一写「模型置信度」，并明确标注它不是科学鉴定概率
 * （规格书第五节明令禁止「一定正确」「保证鉴定」类表述）。
 *
 * ## 文字分析失败时的表现
 *
 * 基础信息照常完整展示，只有百科区块显示「暂缺」并给出重新生成的入口。
 * 这一点是 Phase 4 验收标准 ② 的直接体现 ——
 * **识别结果的可用性不依赖文字分析是否成功**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantDetailScreen(
    imageStore: ImageStore,
    mediaSaver: MediaSaver,
    viewModel: PlantDetailViewModel,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenObservations: (Long) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val analyzing by viewModel.analyzing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val alternatives by viewModel.alternatives.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    // 大图查看器挂在这一层，照片横条与观察卡片共用同一个实例 ——
    // 两张照片列表点开的是同一个查看器，翻页范围由各自的列表决定
    val viewerState = rememberImageViewerState()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 档案已删除 → 直接退回上一级，避免停留在一个永远显示「档案不存在」的页面
    LaunchedEffect(deleted) {
        if (deleted) onDeleted()
    }

    ImageViewerHost(
        state = viewerState,
        mediaSaver = mediaSaver,
        nameHint = detail?.plant?.name.orEmpty(),
    )

    if (confirmDelete) {
        val observationCount = detail?.observations?.size ?: 0
        val photoCount = detail?.observations.orEmpty().sumOf { it.images.size }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这份植物档案？") },
            text = {
                Text(
                    "这份档案连同 $observationCount 次观察、$photoCount 张照片会移入回收站。\n\n" +
                        "照片不会立刻消失，你可以随时在「回收站」里恢复它，" +
                        "或者在那里彻底删除。",
                )
            },
            // 确认按钮刻意不叫「删除」：顶栏那个也写着「删除」，
            // 两个同名按钮同屏会让用户（和自动化脚本）点错。
            // 破坏性操作的按钮本来就该把动作写全。
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        viewModel.deletePlant()
                    },
                ) { Text("移入回收站") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(detail?.plant?.name ?: "植物档案") },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    detail?.plant?.let { plant ->
                        TextButton(onClick = { onEdit(plant.id) }) { Text("编辑") }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
    ) { innerPadding ->
        val plant = detail?.plant

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .semantics { contentDescription = "植物详情" },
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (plant == null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Text(
                            text = "档案不存在或已被删除。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                return@LazyColumn
            }

            item { IdentityCard(plant) }

            item { ConfidenceCard(plant) }

            val images = detail?.observations.orEmpty().flatMap { it.images }
            if (images.isNotEmpty()) {
                item {
                    PhotoStrip(
                        images = images,
                        imageStore = imageStore,
                        observationCount = detail?.observations?.size ?: 0,
                        onOpen = { index ->
                            viewerState.open(
                                images = images.map { ViewerImage(imageStore.resolve(it.imagePath), it.role.label) },
                                initialIndex = index,
                            )
                        },
                    )
                }
            }

            item { AnalysisSection(plant = plant, analyzing = analyzing, onRegenerate = viewModel::regenerateAnalysis) }

            // 相似植物：来自最近一次识别时模型给出的候选。
            // 放在百科之后、观察记录之前 —— 它是「还有可能是别的什么」的提示，
            // 应该紧挨着识别结论，而不是压在页面最底部
            if (alternatives.isNotEmpty()) {
                item { AlternativesCard(alternatives) }
            }

            item {
                ObservationSummaryCard(
                    plantId = plant.id,
                    observations = detail?.observations.orEmpty(),
                    onOpenObservations = onOpenObservations,
                )
            }

            item { DisclaimerCard() }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun IdentityCard(plant: PlantRecordEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = plant.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            plant.latinName?.takeIf { it.isNotBlank() }?.let { latin ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = latin,
                    style = MaterialTheme.typography.titleSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            // 俗称放在学名下面、科属上面：它是「这株植物还可能叫什么」，
            // 与人辨认植物的顺序一致（先想它叫什么，再看它属于哪一科）。
            // 该字段是完整版功能，基础版不呈现
            if (AppEdition.isFull) {
                plant.commonNames?.takeIf { it.isNotBlank() }?.let { alias ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "俗称：$alias",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                }
            }

            val taxonomy = listOfNotNull(
                plant.family?.takeIf { it.isNotBlank() }?.let { "科：$it" },
                plant.genus?.takeIf { it.isNotBlank() }?.let { "属：$it" },
            )
            if (taxonomy.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = taxonomy.joinToString("　"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            plant.category?.takeIf { it.isNotBlank() }?.let { category ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ConfidenceCard(plant: PlantRecordEntity) {
    val percent = (plant.confidence * 100).roundToInt()
    val stars = ConfidenceGrade.render(plant.confidence)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "模型置信度",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "识别质量　$stars　${ConfidenceGrade.label(plant.confidence)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "这是 AI 对当前视觉证据的把握程度估计，不是经过科学验证的" +
                    "物种鉴定概率。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 照片横条 —— 展示这株植物的全部照片（含部位标注）。
 *
 * 每张图可点：进入全屏查看器，可缩放、可保存到相册、可分享。
 * 没有点击入口时，用户看着缩略图却点不动，只能去系统相册里找原图 ——
 * 而原图存在应用私有目录，系统相册根本看不到。
 */
@Composable
private fun PhotoStrip(
    images: List<ObservationImageEntity>,
    imageStore: ImageStore,
    observationCount: Int,
    onOpen: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "照片",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${images.size} 张 · ${observationCount} 次观察 · 点击看大图",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(images, key = { _, image -> image.id }) { index, image ->
                LocalImage(
                    file = imageStore.resolve(image.imagePath),
                    contentDescription = "植物照片 ${image.role.label}",
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        // 大图查看只在完整版提供。基础版照片照常显示，
                        // 只是点不开 —— 与其给一个点了没反应的区域，不如不给
                        .then(
                            if (AppEdition.isFull) Modifier.clickable { onOpen(index) }
                            else Modifier,
                        ),
                )
            }
        }
    }
}

/**
 * 植物百科区块。
 *
 * 四种状态各自给出明确交代：
 *  - 生成成功 → 展示七个字段（空的字段直接跳过，不显示「暂无」占位）
 *  - 生成中 → 进度提示
 *  - 失败 / 未请求 → 说明原因 + 提供重新生成入口
 */
@Composable
private fun AnalysisSection(
    plant: PlantRecordEntity,
    analyzing: Boolean,
    onRegenerate: () -> Unit,
) {
    val sections = analysisSections(plant)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "植物百科",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (analyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            when {
                analyzing -> {
                    Text(
                        text = "正在生成，可能需要十几秒…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                sections.isNotEmpty() -> {
                    if (plant.analysisStatus == AnalysisStatus.FAILED) {
                        Text(
                            text = "上次生成失败，以下是之前的版本。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    sections.forEachIndexed { index, (title, content) ->
                        if (index > 0) Spacer(Modifier.height(12.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                plant.analysisStatus == AnalysisStatus.FAILED -> {
                    Text(
                        text = "详细植物分析暂时生成失败。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "这不影响上面的识别结果 —— 名称、科属与照片都已完整保存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        text = "尚未生成植物百科。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "生成后会补充简介、形态特征、习性、花期、用途与养护建议。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!analyzing) {
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onRegenerate, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sections.isEmpty()) "生成植物百科" else "重新生成")
                }
            }
        }
    }
}

/** 免责声明（规格书第三十一节） */
/**
 * 相似植物（规格书第十六节的「相似植物」区块）。
 *
 * 数据来自最近一次识别时模型给出的候选，不是另做一次检索 ——
 * 那会再产生一次 API 调用，而且结论可能与已有的识别结果不一致。
 */
@Composable
private fun AlternativesCard(alternatives: List<RecognitionResult.Alternative>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "相似植物",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "识别时模型认为也可能的物种，供你对照实地特征判断。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            alternatives.forEach { alternative ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = alternative.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${(alternative.confidence * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 观察记录摘要（规格书第十六节：首次观察 / 观察次数 / 查看所有观察）。
 *
 * 只给摘要与入口，具体列表在观察记录页 ——
 * 详情页已经有百科等长内容，把观察逐条铺开会让页面失控地长。
 *
 * **地点取最近一次观察的**：用户打开一份档案想确认的通常是
 * 「我最近一次是在哪儿见到它的」，而不是第一次。
 */
@Composable
private fun ObservationSummaryCard(
    plantId: Long,
    observations: List<com.plantidentify.data.local.relation.ObservationWithImages>,
    onOpenObservations: (Long) -> Unit,
) {
    val firstAt = observations.minOfOrNull { it.observation.timestamp }
    val latestAt = observations.maxOfOrNull { it.observation.timestamp }

    // 地点三件套：优先地名，地名取不到（国内 ROM 的反向地理编码经常返回 null）
    // 就退化成经纬度 —— 有个坐标总比整行消失有用
    val latestPlace = observations
        .maxByOrNull { it.observation.timestamp }
        ?.observation
        ?.let { placeText(it.locationName, it.latitude, it.longitude) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "观察记录",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))

            SummaryRow("首次观察", firstAt?.let { formatDate(it) } ?: "—")
            SummaryRow("最近观察", latestAt?.let { formatDate(it) } ?: "—")
            SummaryRow("最近地点", latestPlace ?: "未记录")
            SummaryRow("观察次数", observations.size.toString())
            SummaryRow("照片总数", observations.sumOf { it.images.size }.toString())

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onOpenObservations(plantId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("查看所有观察")
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 日期格式化：观察记录只看年月日，时分秒对用户没有意义 */
private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun DisclaimerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = "本页内容由 AI 生成，仅供参考，不作为专业鉴定依据。" +
                "请以实地观察与专业资料为准。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** 取档案里非空的百科字段；空字段直接跳过，不显示「暂无」占位 */
private fun analysisSections(plant: PlantRecordEntity): List<Pair<String, String>> = buildList {
    plant.description?.takeIf { it.isNotBlank() }?.let { add("植物简介" to it) }
    plant.morphologicalFeatures?.takeIf { it.isNotBlank() }?.let { add("形态特征" to it) }
    plant.growthHabits?.takeIf { it.isNotBlank() }?.let { add("生长习性" to it) }
    plant.floweringPeriod?.takeIf { it.isNotBlank() }?.let { add("花期" to it) }
    plant.fruitingPeriod?.takeIf { it.isNotBlank() }?.let { add("果期" to it) }
    plant.landscapeUses?.takeIf { it.isNotBlank() }?.let { add("园林用途" to it) }
    plant.careAdvice?.takeIf { it.isNotBlank() }?.let { add("养护建议" to it) }
    // 病虫害防治是完整版字段。基础版的库结构、AI 请求与完整版完全相同
    // （所以两边的备份包可以互相迁移），只是界面不呈现这一项
    if (AppEdition.isFull) {
        plant.pestControl?.takeIf { it.isNotBlank() }?.let { add("病虫害防治" to it) }
    }
}
