package com.plantidentify.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.ui.components.BackIconButton
import com.plantidentify.ui.screens.plants.PlantCard
import com.plantidentify.ui.screens.plants.PlantListViewModel

/**
 * 搜索与筛选（规格书第十九节）。
 *
 * 搜索覆盖：中文名 / 拉丁学名 / 科 / 属 / 植物类型 / 备注。
 * 筛选覆盖：科 / 属 / 日期 / 地点。
 *
 * ## 筛选候选来自库里的实际值
 *
 * 不是一份写死的植物学分类表 —— 用户拍到什么，候选里才有什么。
 * 写死一份的话，列表里会出现一辈子用不上的选项，而用户真正需要的那一项
 * 可能因为学名写法不同而选不到。
 *
 * ## 日期筛选只给粗粒度
 *
 * 只提供「最近 7 天 / 30 天 / 今年」几个区间，不做日期选择器：
 * 植物观察的实用粒度就是这些，多做一个日历弹窗只会增加出错面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenPlantDetail: (Long) -> Unit,
    imageStore: ImageStore,
    viewModel: PlantListViewModel,
    modifier: Modifier = Modifier,
) {
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val families by viewModel.families.collectAsStateWithLifecycle()
    val genera by viewModel.genera.collectAsStateWithLifecycle()
    val allCards by viewModel.allCards.collectAsStateWithLifecycle()

    val keyboard = LocalSoftwareKeyboardController.current
    val now = System.currentTimeMillis()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("搜索植物") },
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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = filters.keyword,
                onValueChange = viewModel::setKeyword,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索植物") },
                placeholder = { Text("中文名、拉丁学名、科、属、类型或备注") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboard?.hide() },
                ),
            )

            // ---- 科 / 属：有可选值时才显示这一行
            if (families.isNotEmpty()) {
                FilterRow(
                    title = "科",
                    options = families,
                    selected = filters.family,
                    onSelect = viewModel::setFamily,
                )
            }
            if (genera.isNotEmpty()) {
                FilterRow(
                    title = "属",
                    options = genera,
                    selected = filters.genus,
                    onSelect = viewModel::setGenus,
                )
            }

            // ---- 日期区间
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "日期",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DateChip("不限", filters.fromDate == null) {
                    viewModel.setDateRange(null, null)
                }
                DateChip("近 7 天", filters.fromDate == daysAgo(now, 7)) {
                    viewModel.setDateRange(daysAgo(now, 7), null)
                }
                DateChip("近 30 天", filters.fromDate == daysAgo(now, 30)) {
                    viewModel.setDateRange(daysAgo(now, 30), null)
                }
                DateChip("今年", filters.fromDate == yearStart(now)) {
                    viewModel.setDateRange(yearStart(now), null)
                }
            }

            // ---- 结果计数 + 清除
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "找到 ${cards.size} 株植物" +
                        if (filters.activeCount > 0) "（已筛选 ${filters.activeCount} 项）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!filters.isEmpty) {
                    TextButton(onClick = viewModel::clearAll) { Text("清除全部") }
                }
            }

            if (cards.isEmpty()) {
                EmptyResultHint(
                    hasAnyPlant = allCards.isNotEmpty(),
                    keyword = filters.keyword,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cards, key = { it.plantId }) { card ->
                        PlantCard(
                            card = card,
                            imageStore = imageStore,
                            onClick = { onOpenPlantDetail(card.plantId) },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 只显示前几个：科属可能几十个，纵向堆叠会把结果挤出屏幕。
        // 想选别的可以先输关键词，把范围收窄
        options.take(MAX_FILTER_CHIPS).forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(if (selected == option) "" else option) },
                label = { Text(option) },
            )
        }
        if (options.size > MAX_FILTER_CHIPS) {
            Text(
                text = "等 ${options.size} 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun EmptyResultHint(hasAnyPlant: Boolean, keyword: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (!hasAnyPlant) "还没有植物档案" else "没有匹配的植物",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    !hasAnyPlant ->
                        "先去「添加植物」拍几张照片，识别后保存就会出现在这里。"
                    keyword.isNotBlank() ->
                        "试试更短的关键词，或换用拉丁学名的一部分（例如 «Lagerstroemia»）。"
                    else ->
                        "当前筛选条件下没有结果，可以放宽条件再试。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MAX_FILTER_CHIPS = 6

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun daysAgo(now: Long, days: Int): Long = now - days * DAY_MS

/**
 * 今年 1 月 1 日零点。
 *
 * 用 Calendar 而不是 java.time：minSdk 是 26，两者都可用，
 * 但这里只需要「把时分秒抹掉」这一个动作，Calendar 更直接。
 */
private fun yearStart(now: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = now
    set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY)
    set(java.util.Calendar.DAY_OF_MONTH, 1)
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis
