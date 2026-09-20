package com.plantidentify.data.local.projection

import com.plantidentify.data.local.entity.RecognitionTaskStatus

/**
 * 任务列表卡片的投影行。
 *
 * ## 为什么要投影而不是直接返回实体
 *
 * 列表每张卡片只需要「第几张图」和「封面」，但如果直接查
 * `RecognitionTaskEntity` 再逐条去取图片行，就是典型的 N+1 查询；
 * 而如果为了省事把图片行全量载入，一个 5 张图的任务就要多读 5 行、
 * 几十个任务下来在列表页滚动时会明显卡。
 *
 * 用一条带子查询的 SQL 一次取出所需的三列，是这个场景的标准解法。
 */
data class TaskCardRow(
    val id: Long,
    val status: RecognitionTaskStatus,
    val createdAt: Long,
    val completedAt: Long?,
    val origin: String,
    val code: String?,

    /** 这个任务有几张图（列表上显示「3 张」） */
    val imageCount: Int,

    /**
     * 封面图路径 = 排序最靠前的那张。
     *
     * 子查询里带 `ORDER BY sortOrder ASC LIMIT 1` ——
     * 用户调序之后封面要跟着换，否则「刚刚把这张挪到第一位」看起来没生效。
     */
    val coverPath: String?,
)
