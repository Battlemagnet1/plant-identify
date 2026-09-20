package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 识别任务（RecognitionTask）—— 一条「待识别 / 正在识别」的照片组。
 *
 * ## 为什么需要这张表
 *
 * 在此之前，用户点「开始识别」后必须**停在这一页等**：离开就取消，切后台也可能被杀。
 * 有了任务表，用户可以把一批照片一次性丢进队列，然后去做别的事 ——
 * 识别在后台按并发 1 依次跑，完成的结果直接进档案。
 *
 * ## 与现有数据的关系
 *
 * ```
 * recognition_task（一次识别）
 *   └── recognition_task_image（这次识别用的照片，可调序、可标部位）
 *           │ 识别完成后整体迁移过去（路径不变，只换外键）
 *           ↓
 *   plant_observation（一次观察）
 *           └── observation_image
 * ```
 *
 * **任务照片与观察照片是两张表**，不共用一个 `taskId` 列。理由：
 *   1. 「调整照片顺序」是任务页的核心交互，独立表能直接复用 `ObservationImageDao`
 *      已验证过的 `reorder` 范式；
 *   2. 「删某张 / 按 sortOrder 查」是 SQL 的原生能力，不必在 JSON 里手写；
 *   3. 若给 `observation_image` 加 `taskId`，会污染统计 / 封面 / 级联的**所有**既有查询，
 *      风险远大于多一张表的成本。
 *
 * ## DB 是唯一真相
 *
 * WorkManager 只负责「什么时候跑」，**UI 永远读这张表**。
 * 两者的关联靠 `workerId` 之外的 `id`（WorkManager 的 uniqueName 直接用任务 id 拼）。
 * 这样即使 WorkManager 的内部状态被系统清掉，任务列表也不会凭空少行。
 */
@Entity(
    tableName = "recognition_task",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["priority"]),
    ],
)
data class RecognitionTaskEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 见 [RecognitionTaskStatus] 的状态机说明 */
    val status: RecognitionTaskStatus = RecognitionTaskStatus.PENDING,

    /** 入队时间（用于列表排序与「按状态看今天跑了几条」） */
    val createdAt: Long,

    val startedAt: Long? = null,

    val completedAt: Long? = null,

    /**
     * 已自动重试次数。
     *
     * 由 Worker 的 `runAttemptCount` 回写，**只用于展示** ——
     * 真正的重试决策交给 WorkManager 的 Backoff，不自己再数一遍。
     */
    val retryCount: Int = 0,

    /** 失败原因（面向用户的一句话，不是堆栈） */
    val errorMessage: String? = null,

    /** 完成后写入的观察记录 id；null 表示还没产出结果 */
    val resultObservationId: Long? = null,

    /**
     * 优先级：**越大越先执行**。
     *
     * 现场调查给 [PRIORITY_SURVEY]（用户在等着看结果），普通批量任务给 0。
     * 注意排序要在「入队取任务」那一处体现（`getPendingLimited` 带 ORDER BY）。
     */
    val priority: Int = PRIORITY_NORMAL,

    /**
     * 来源：普通任务还是现场调查（[ORIGIN_NORMAL] / [ORIGIN_SURVEY]）。
     *
     * 决定列表里显示 `#018` 还是 `P-001`：现场调查的编号是用户在现场
     * 按顺序念出来的，必须稳定可对；普通任务的编号只是行号。
     */
    val origin: String = ORIGIN_NORMAL,

    /** 现场调查的临时编号（如 `P-001`）；普通任务为 null */
    val code: String? = null,

    // ---------- 「完成后命中已有档案」的待裁决状态（方案 §6.2）----------

    /**
     * 后台任务命中已有档案时，**不自动合并**，而是把结果挂到该档案下、
     * 并把候选写进下面三个字段，等用户在任务列表里裁决。
     *
     * 为什么后台可以「先挂上去」而立即识别只能「先问」：
     * 后台任务没人盯着，弹不出对话框；而照片已经识别完、用户显然希望它进档案，
     * 挂到候选档案下是**可撤销**的（用户点「分离」即可），
     * 比堆在一个「未归类」的角落里更符合预期。
     */
    val pendingMergePlantId: Long? = null,

    /** 命中等级（拉丁名 / 中文名+科 / 模糊名…），用于向用户解释「为什么说是它」 */
    val pendingMergeLevel: String? = null,

    /** 命中的具体依据（如「拉丁学名相同」） */
    val pendingMergeReason: String? = null,

    /** 用户给这条任务写的备注（现场调查时常用，如「东门第三棵」） */
    val note: String? = null,
) {
    companion object {
        const val ORIGIN_NORMAL = "NORMAL"
        const val ORIGIN_SURVEY = "SURVEY"

        const val PRIORITY_NORMAL = 0

        /** 现场调查的优先级：高于普通任务，因为用户正站在树底下等结果 */
        const val PRIORITY_SURVEY = 10
    }
}
