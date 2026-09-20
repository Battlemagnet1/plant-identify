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

    // ---- 「后台自动挂靠」的待裁决信息（方案 §6.2），非空 = 等用户处理 ----

    val pendingMergePlantId: Long?,
    val pendingMergeLevel: String?,
    val pendingMergeReason: String?,

    /** 失败原因（面向用户的一句话）；非失败状态为 null */
    val errorMessage: String?,

    /** 该任务产出的观察 id（尚未产出为 null） */
    val resultObservationId: Long?,

    /**
     * 该任务产出结果所在的档案 id。
     *
     * 列表上的「查看档案」要用它导航；挂靠场景下它等于 pendingMergePlantId，
     * 由子查询从观察行取，保证两种落库路径口径一致。
     */
    val resultPlantId: Long?,
)
