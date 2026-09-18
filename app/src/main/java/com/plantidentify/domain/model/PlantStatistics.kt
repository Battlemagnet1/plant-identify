package com.plantidentify.domain.model

/**
 * 植物统计（规格书第二十节）。
 *
 * 口径已于 2026-09-18 确认，弃用规格书原文中含混的「植物记录」一词：
 *
 * | 指标       | 定义                        | 语义           |
 * |-----------|-----------------------------|----------------|
 * | 不同植物   | plant_record 行数            | 去重物种数     |
 * | 观察次数   | plant_observation 行数       | 累计记录数     |
 * | 照片数     | observation_image 行数       | 图片文件数     |
 *
 * 三者分母语义不同，UI 上必须并列展示并标注清楚，
 * 不能让用户误读为同一维度的计数。
 */
data class PlantStatistics(
    /** 不同植物 —— 去重后的物种数 */
    val distinctPlants: Int = 0,

    /** 观察次数 —— 累计的记录数（同一种植物观察 3 次即计 3） */
    val observationCount: Int = 0,

    /** 照片数 —— 图片文件总数 */
    val imageCount: Int = 0,

    /** 科的数量 */
    val familyCount: Int = 0,

    /** 属的数量 */
    val genusCount: Int = 0,
) {
    companion object {
        val EMPTY = PlantStatistics()
    }
}
