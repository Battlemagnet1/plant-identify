package com.plantidentify.data.local.projection

/**
 * 景观统计用的植物投影（v1.0.2 Phase 3）。
 *
 * 统计要看的字段比导入匹配多（类型、习性、花期、果期、用途、描述…），
 * 但**长文本仍然截断到 500 字符** —— 色彩关键词提取只需要开头那几句，
 * 而一个景观文件夹动辄几百株，读全字段是清洗模块已经栽过一次的坑。
 *
 * ⚠️ 与 `MatchSnapshotRow` 一样：**改 `PlantTraits` 的规则时要同步看这里**。
 * 少取一个字段不会报错，只会让某条统计规则静默失效。
 */
data class LandscapePlantRow(
    val id: Long,
    val name: String,
    val family: String? = null,
    val genus: String? = null,
    /** 植物类型（乔木 / 灌木 / 草本…）—— 层次判断的主要依据 */
    val category: String? = null,
    val growthHabits: String? = null,
    val morphologicalFeatures: String? = null,
    val floweringPeriod: String? = null,
    val fruitingPeriod: String? = null,
    val landscapeUses: String? = null,
    val description: String? = null,
)
