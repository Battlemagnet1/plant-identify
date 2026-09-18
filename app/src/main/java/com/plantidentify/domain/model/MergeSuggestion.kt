package com.plantidentify.domain.model

import com.plantidentify.data.local.entity.PlantRecordEntity

/**
 * 归并提示的档位（规格书第十四点五节）。
 *
 * 规格书把匹配结果分成「高度匹配 / 可能匹配 / 无匹配」三档，
 * 这里**只区分证据强度，不改变任何自动行为** —— 无论哪一档，
 * 系统都只提示，绝不自动合并两条已有档案。
 */
enum class MergeLevel {
    /** 证据充分（拉丁学名相同，或中文名+科+属全同） */
    EXACT,

    /** 证据不足（只有中文名，或名称相近）—— 规格书明确要求不得据此绝对判断 */
    POSSIBLE,

    /** 没有候选 */
    NONE,
}

/**
 * 保存前的归并建议。
 *
 * @param plant 命中的已有档案；[MergeLevel.NONE] 时为 null
 * @param level 证据强度
 * @param reason 匹配依据的中文说明，直接展示给用户 ——
 *        用户需要知道「系统凭什么认为这是同一株」才能做判断
 * @param alternatives 次要候选（名称相近的档案），让用户有机会改选
 * @param observationCount 命中档案已有的观察次数，用于提示「这将是第 N 次观察」
 */
data class MergeSuggestion(
    val plant: PlantRecordEntity?,
    val level: MergeLevel,
    val reason: String,
    val alternatives: List<PlantRecordEntity> = emptyList(),
    val observationCount: Int = 0,
) {
    val hasCandidate: Boolean get() = plant != null

    /** 命中档案的下一次观察序号（1 起） */
    val nextObservationNumber: Int get() = observationCount + 1

    companion object {
        fun none(): MergeSuggestion = MergeSuggestion(
            plant = null,
            level = MergeLevel.NONE,
            reason = "没有找到名称或学名相近的已有植物",
        )
    }
}
