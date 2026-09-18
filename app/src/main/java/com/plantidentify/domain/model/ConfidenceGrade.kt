package com.plantidentify.domain.model

/**
 * 置信度的展示分级（规格书第七节的五星制）。
 *
 * 规格书给出了五档名称但未给阈值，这里按区间映射。
 * 注意这是**展示层的分级**，不改变 confidence 本身的含义，
 * 也不参与「低于 0.70 提示补图」这类业务判断。
 *
 * 抽成独立类型而不是挂在某个实体上，是因为它同时被
 * 识别结果（[com.plantidentify.data.ai.RecognitionResult]）与
 * 植物档案（[com.plantidentify.data.local.entity.PlantRecordEntity]）需要 ——
 * 两处各写一份很快就会不一致，而用户会在两个页面上看到同一个 91%。
 */
object ConfidenceGrade {

    /** 星级 1–5 */
    fun stars(confidence: Double): Int = when {
        confidence >= 0.90 -> 5
        confidence >= 0.80 -> 4
        confidence >= 0.70 -> 3
        confidence >= 0.50 -> 2
        else -> 1
    }

    /** 与 [stars] 一一对应的文字描述 */
    fun label(confidence: Double): String = when (stars(confidence)) {
        5 -> "优秀"
        4 -> "良好"
        3 -> "一般"
        2 -> "较低"
        else -> "很低"
    }

    /** 渲染成 "★★★★☆" 形式 */
    fun render(confidence: Double): String {
        val filled = stars(confidence)
        return "★".repeat(filled) + "☆".repeat(5 - filled)
    }
}
