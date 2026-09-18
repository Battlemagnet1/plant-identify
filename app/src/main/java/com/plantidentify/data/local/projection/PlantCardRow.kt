package com.plantidentify.data.local.projection

/**
 * 植物列表卡片的查询投影。
 *
 * 为什么不直接用 `PlantWithObservationsAndImages`：
 * 列表页每张卡只需要「一次观察数 + 一张封面图」，把全部观察和全部图片都读出来
 * 再在内存里统计，几十株植物就会明显拖慢首屏。让 SQLite 用子查询算好再返回，
 * 传输量与内存占用都降一个量级。
 *
 * 字段名要与 SQL 里的别名一致 —— Room 按名字映射，不是按顺序。
 */
data class PlantCardRow(
    val plantId: Long,
    val name: String,
    val latinName: String?,
    val family: String?,
    val genus: String?,
    val category: String?,
    val confidence: Float,
    val updatedAt: Long,
    /** 观察次数 */
    val observationCount: Int,
    /** 照片张数 */
    val photoCount: Int,
    /** 封面图（优先取代表观察的第一张），可能为 null */
    val coverPath: String?,
)
