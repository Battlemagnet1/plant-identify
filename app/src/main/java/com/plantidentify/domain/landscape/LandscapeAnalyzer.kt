package com.plantidentify.domain.landscape

import kotlin.math.ln
import kotlin.math.roundToInt

/** 分析用的植物输入（只带景观关心的字段） */
data class LandscapePlant(
    val id: Long,
    val name: String,
    val family: String? = null,
    val genus: String? = null,
    val category: String? = null,
    val growthHabits: String? = null,
    val morphologicalFeatures: String? = null,
    val floweringPeriod: String? = null,
    val fruitingPeriod: String? = null,
    val landscapeUses: String? = null,
    val description: String? = null,
) {
    /** 用于物种去重的键：优先拉丁…这里没有拉丁，用名字（已 trim） */
    val speciesKey: String get() = name.trim()
}

/** 一项计数 */
data class Counted(val label: String, val count: Int, val ratio: Double)

/** 常绿统计 */
data class EvergreenStat(
    val evergreen: Int,
    val deciduous: Int,
    /** 判不出来的数量 —— **必须显示**，否则用户会以为剩下都是落叶 */
    val unknown: Int,
) {
    val known: Int get() = evergreen + deciduous
    /** 只在「判得出的那些」里算比例；样本太小时返回 null 而不是给个假数字 */
    val ratio: Double? get() = if (known == 0) null else evergreen.toDouble() / known
}

/**
 * 多样性指标。
 *
 * 三个指数一起给，是因为它们回答的问题不同：
 * - **Shannon（H）** 同时看「种类多少」与「分布均匀度」，是最常用的综合指标
 * - **Simpson（D）** 更看重**优势种**的占比，对「某一种占了绝大多数」特别敏感
 * - **Pielou（J）** 是 H 除以理论最大值，**去掉了物种数的影响** ——
 *   只有它能在「10 种均匀分布」与「50 种里有一种占 90%」之间比较均匀度
 *
 * 单独看 H 会误判：种类多但极不均匀时 H 也可能不低。
 */
data class DiversityStat(
    /** 物种数 S */
    val speciesCount: Int,
    /** 个体数 N */
    val individualCount: Int,
    val shannon: Double,
    val simpson: Double,
    /** 均匀度；S ≤ 1 时无意义，返回 null */
    val evenness: Double?,
) {
    /** 给界面用的定性描述 */
    val level: String
        get() = when {
            speciesCount <= 1 -> "单一物种"
            shannon < 0.8 -> "多样性偏低"
            shannon < 1.6 -> "多样性中等"
            else -> "多样性较高"
        }
}

/**
 * 景观统计（v1.0.2 Phase 3 §十三）。
 *
 * ## 这里算的都是「不需要 AI 的部分」
 *
 * 需求把景观分析写成一段（植物组成 / 多样性 / 乔灌草 / 季相 / 色彩 / 配置 /
 * 空间结构 / 问题 / 建议），但其中**前六项是纯统计**，本地就能算得又准又快，
 * 而且**结果稳定**（同样的数据永远得到同样的数字）。
 *
 * 交给 AI 的只有最后两项（问题与建议）—— 那是它的强项；
 * 而让 AI 去数「乔木 12 株、灌木 5 株」，既慢又可能数错，
 * 还要为此付一次 API 费用。
 *
 * 所以 `LandscapeAiAdvisor` 会把这里的结论作为**输入**喂给 AI，
 * 而不是把原始植物列表丢过去让它自己数。
 */
object LandscapeAnalyzer {

    fun analyze(plants: List<LandscapePlant>): LandscapeStatistics {
        val layers = mutableMapOf<PlantLayer, Int>()
        val families = mutableMapOf<String, Int>()
        val seasons = mutableMapOf<Season, Int>()
        val colors = mutableMapOf<String, Int>()
        var evergreen = 0
        var deciduous = 0
        var evergreenUnknown = 0

        plants.forEach { plant ->
            // 层次
            val layer = PlantTraits.layerOf(
                category = plant.category,
                growthHabits = plant.growthHabits,
                morphologicalFeatures = plant.morphologicalFeatures,
            )
            layers[layer] = (layers[layer] ?: 0) + 1

            // 科
            plant.family?.trim()?.takeIf { it.isNotEmpty() }?.let {
                families[it] = (families[it] ?: 0) + 1
            }

            // 季相：花期与果期都算观赏期
            val observed = PlantTraits.seasonsOf(plant.floweringPeriod) +
                PlantTraits.seasonsOf(plant.fruitingPeriod)
            observed.forEach { seasons[it] = (seasons[it] ?: 0) + 1 }

            // 色彩
            PlantTraits.colorsOf(
                plant.name,
                plant.description,
                plant.morphologicalFeatures,
                plant.landscapeUses,
                plant.category,
            ).forEach { colors[it] = (colors[it] ?: 0) + 1 }

            // 常绿
            when (PlantTraits.evergreenOf(plant.category, plant.growthHabits, plant.description)) {
                true -> evergreen++
                false -> deciduous++
                null -> evergreenUnknown++
            }
        }

        val total = plants.size
        val speciesCount = plants.map { it.speciesKey }.toSet().size

        return LandscapeStatistics(
            plantCount = total,
            speciesCount = speciesCount,
            layers = layers.toCounted(total),
            families = families.toCounted(total).take(MAX_FAMILIES),
            seasons = seasons.toCounted(total).let { list ->
                // 季节按「春夏秋冬全年」的自然顺序排，不按数量 ——
                // 季相图的价值在看出「哪一季空了」，顺序被打乱就看不出来
                val order = Season.entries.map { it.label }
                list.sortedBy { order.indexOf(it.label) }
            },
            colors = colors.toCounted(total),
            evergreen = EvergreenStat(evergreen, deciduous, evergreenUnknown),
            diversity = diversityOf(plants.map { it.speciesKey }),
            suggestions = suggestionsFor(
                total = total,
                speciesCount = speciesCount,
                layers = layers,
                seasons = seasons,
                families = families,
                evergreen = evergreen,
                deciduous = deciduous,
            ),
        )
    }

    // ---------------------------------------------------------------- 多样性

    /** 物种 → 个体数 分布。景观里通常一株一个个体，但同名多株就是多个 */
    private fun diversityOf(speciesKeys: List<String>): DiversityStat {
        val counts = speciesKeys.groupingBy { it }.eachCount().values
        val n = speciesKeys.size
        val s = counts.size
        if (n == 0 || s == 0) return DiversityStat(0, 0, 0.0, 0.0, null)

        var shannon = 0.0
        var simpson = 0.0
        counts.forEach { count ->
            val p = count.toDouble() / n
            shannon -= p * ln(p)
            simpson += p * p
        }
        // Simpson 统一成「1 - Σp²」：值越大越多样，与直觉一致
        val simpsonIndex = 1.0 - simpson
        val evenness = if (s > 1) shannon / ln(s.toDouble()) else null

        return DiversityStat(
            speciesCount = s,
            individualCount = n,
            shannon = shannon,
            simpson = simpsonIndex,
            evenness = evenness,
        )
    }

    // ---------------------------------------------------------------- 规则提示

    /**
     * 规则能直接说出的问题。
     *
     * 与 AI 建议分开：这些是**从数字直接推出来的事实**
     * （「乔木占了 92%」），不需要模型来判断；AI 那部分给的是
     * 「所以该补什么」这类需要常识的结论。
     *
     * 阈值都偏保守 —— 宁可少报，也不要让用户看一堆无意义的提醒。
     */
    private fun suggestionsFor(
        total: Int,
        speciesCount: Int,
        layers: Map<PlantLayer, Int>,
        seasons: Map<Season, Int>,
        families: Map<String, Int>,
        evergreen: Int,
        deciduous: Int,
    ): List<String> {
        if (total == 0) return emptyList()
        val result = mutableListOf<String>()

        if (total >= MIN_SAMPLE_FOR_LAYER) {
            val unknownLayers = layers[PlantLayer.UNKNOWN] ?: 0
            if (unknownLayers.toDouble() / total > 0.3) {
                result += "有 ${unknownLayers} 株（${percent(unknownLayers, total)}）" +
                    "未能判断乔木/灌木/草本——建议补全「植物类型」字段，层次分析才准确"
            }
            PlantLayer.entries
                .filter { it != PlantLayer.UNKNOWN }
                .forEach { layer ->
                    val count = layers[layer] ?: 0
                    if (count.toDouble() / total > LAYER_DOMINANCE) {
                        result += "${layer.label}占了 ${percent(count, total)}，层次较单一"
                    }
                }
        }

        if (speciesCount in 1..(total / 3).coerceAtLeast(1) && total >= MIN_SAMPLE_FOR_LAYER) {
            result += "$total 株只有 $speciesCount 个物种，重复度偏高"
        }

        // 季相：只有「有花期数据」的那些才能判断空季
        val withSeason = seasons.values.sum().coerceAtLeast(0)
        if (withSeason >= MIN_SAMPLE_FOR_SEASON) {
            val empty = listOf(Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER)
                .filter { (seasons[it] ?: 0) == 0 }
            if (empty.size >= 2) {
                result += "${empty.joinToString("、") { it.label }}季缺少明确的观赏植物，" +
                    "季相变化偏单调"
            }
        }

        if (evergreen + deciduous >= MIN_SAMPLE_FOR_LAYER) {
            val ratio = evergreen.toDouble() / (evergreen + deciduous)
            // 常绿过高会显得「一片绿、没有季节感」；过低则冬季光秃
            if (ratio > 0.8) result += "常绿植物占 ${(ratio * 100).toInt()}%，冬季景观缺少变化"
            if (ratio < 0.2) result += "常绿植物仅占 ${(ratio * 100).toInt()}%，冬季可能较为空旷"
        }

        families.maxByOrNull { it.value }?.let { (family, count) ->
            if (count.toDouble() / total > FAMILY_DOMINANCE) {
                result += "$family 一个科就占了 ${percent(count, total)}，科属集中度较高"
            }
        }

        return result
    }

    private fun percent(count: Int, total: Int): String =
        if (total == 0) "0%" else "${(count * 100.0 / total).roundToInt()}%"

    private fun Map<*, Int>.toCounted(total: Int): List<Counted> =
        entries
            .sortedByDescending { it.value }
            .map { (key, count) ->
                val label = when (key) {
                    is PlantLayer -> key.label
                    is Season -> key.label
                    else -> key.toString()
                }
                Counted(label, count, if (total == 0) 0.0 else count.toDouble() / total)
            }

    private const val MAX_FAMILIES = 12
    private const val LAYER_DOMINANCE = 0.85
    private const val FAMILY_DOMINANCE = 0.5

    /** 样本太少时算比例没有意义 —— 3 株植物里 1 株乔木不叫「乔木占 33%」 */
    private const val MIN_SAMPLE_FOR_LAYER = 5
    private const val MIN_SAMPLE_FOR_SEASON = 5
}

/** 统计分析结果（`LandscapeStatistics` 的完整定义放在这里，与算法挨着） */
data class LandscapeStatistics(
    val plantCount: Int,
    val speciesCount: Int,
    /** 乔木 / 灌木 / 草本 / 地被 分布 */
    val layers: List<Counted>,
    val families: List<Counted>,
    /** 花期 / 果期覆盖的季节分布 */
    val seasons: List<Counted>,
    /** 按关键词统计的色彩（**非精确色彩分析**，界面要标明） */
    val colors: List<Counted>,
    val evergreen: EvergreenStat,
    val diversity: DiversityStat,
    /** 规则直接能看出的问题（AI 建议另算） */
    val suggestions: List<String>,
) {
    val isEmpty: Boolean get() = plantCount == 0

    /** 给 AI / 报告用的一段文字摘要 */
    fun toPromptText(): String = buildString {
        appendLine("植物总数：$plantCount，物种数：$speciesCount")
        if (layers.isNotEmpty()) {
            appendLine("层次分布：" + layers.joinToString("、") { "${it.label} ${it.count}" })
        }
        if (families.isNotEmpty()) {
            appendLine("主要科：" + families.take(6).joinToString("、") { "${it.label} ${it.count}" })
        }
        if (seasons.isNotEmpty()) {
            appendLine("观赏季相：" + seasons.joinToString("、") { "${it.label} ${it.count}" })
        }
        if (colors.isNotEmpty()) {
            appendLine("色彩关键词：" + colors.joinToString("、") { "${it.label} ${it.count}" })
        }
        val evergreenNote =
            if (evergreen.unknown > 0) "（另有 ${evergreen.unknown} 株未标注）" else ""
        appendLine("常绿/落叶：${evergreen.evergreen}/${evergreen.deciduous}$evergreenNote")
        appendLine(
            "多样性：Shannon ${"%.2f".format(diversity.shannon)}、" +
                "Simpson ${"%.2f".format(diversity.simpson)}、" +
                "均匀度 ${diversity.evenness?.let { "%.2f".format(it) } ?: "—"}（${diversity.level}）",
        )
        if (suggestions.isNotEmpty()) {
            appendLine("本地规则发现：" + suggestions.joinToString("；"))
        }
    }
}
