package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.plantidentify.data.local.entity.RecognitionTaskEntity
import com.plantidentify.data.local.entity.RecognitionTaskStatus
import com.plantidentify.data.local.projection.TaskCardRow
import kotlinx.coroutines.flow.Flow

/**
 * 识别任务表。
 *
 * ## 状态更新为什么要单开 `mark*` 方法
 *
 * 状态流转总是「改 status + 记一个时间戳」成对出现
 * （开始时要写 `startedAt`，结束时要写 `completedAt`）。
 * 如果只暴露一个通用的 `update(entity)`，调用方就得先读整行、改两个字段、再写回，
 * 中间还会把并发期间的其它字段一起覆盖掉。把这些组合收进 DAO 里，
 * 写入的是**单条 UPDATE 语句**，天然原子。
 */
@Dao
interface RecognitionTaskDao {

    // ---------------- 写入 ----------------

    @Insert
    suspend fun insert(task: RecognitionTaskEntity): Long

    @Update
    suspend fun update(task: RecognitionTaskEntity)

    @Query("DELETE FROM recognition_task WHERE id = :taskId")
    suspend fun deleteById(taskId: Long)

    @Query("DELETE FROM recognition_task")
    suspend fun clearAll()

    // ---------------- 状态流转 ----------------

    @Query("UPDATE recognition_task SET status = :status WHERE id = :taskId")
    suspend fun setStatus(taskId: Long, status: RecognitionTaskStatus)

    /**
     * 入队成功：记 QUEUED，不写 `startedAt`（还没真正开跑）。
     *
     * ⚠️ **不给 `status` 写默认值** —— Room 生成的实现类是按 Java 语义覆写的，
     * Kotlin 的默认参数在接口上不会传到实现里，声明了也会被忽略（或直接报错）。
     * DAO 方法一律要求调用方显式传全部参数。
     */
    @Query("UPDATE recognition_task SET status = :status WHERE id = :taskId")
    suspend fun markQueued(taskId: Long, status: RecognitionTaskStatus)

    /** 开跑：记 PROCESSING + `startedAt` */
    @Query("UPDATE recognition_task SET status = :status, startedAt = :startedAt WHERE id = :taskId")
    suspend fun markStarted(taskId: Long, status: RecognitionTaskStatus, startedAt: Long)

    /** 视觉已完成、转后台百科：只换状态，`startedAt` 保持首次开跑的时间 */
    @Query("UPDATE recognition_task SET status = :status WHERE id = :taskId")
    suspend fun markAnalyzing(taskId: Long, status: RecognitionTaskStatus)

    /** 完成：记 COMPLETED + 结束时间 + 产出的观察 id */
    @Query(
        """
        UPDATE recognition_task
        SET status = :status, completedAt = :completedAt, resultObservationId = :observationId,
            errorMessage = NULL
        WHERE id = :taskId
        """,
    )
    suspend fun markCompleted(
        taskId: Long,
        status: RecognitionTaskStatus,
        completedAt: Long,
        observationId: Long?,
    )

    /**
     * 失败 / 取消。
     *
     * `retryCount` 由 Worker 的 `runAttemptCount` 回写 —— 这是唯一能反映
     * 「系统帮我重试了几次」的地方，用户看到「已重试 2 次」才知道不是自己网络的问题。
     */
    @Query(
        """
        UPDATE recognition_task
        SET status = :status, completedAt = :completedAt,
            errorMessage = :message, retryCount = :retryCount
        WHERE id = :taskId
        """,
    )
    suspend fun markFinishedWithError(
        taskId: Long,
        status: RecognitionTaskStatus,
        completedAt: Long,
        message: String?,
        retryCount: Int,
    )

    /** 后台命中已有档案时，写「待裁决」三个字段（方案 §6.2） */
    @Query(
        """
        UPDATE recognition_task
        SET pendingMergePlantId = :plantId, pendingMergeLevel = :level, pendingMergeReason = :reason
        WHERE id = :taskId
        """,
    )
    suspend fun setPendingMerge(taskId: Long, plantId: Long?, level: String?, reason: String?)

    // ---------------- 读取 ----------------

    @Query("SELECT * FROM recognition_task WHERE id = :taskId")
    suspend fun getById(taskId: Long): RecognitionTaskEntity?

    @Query("SELECT * FROM recognition_task WHERE id = :taskId")
    fun observeById(taskId: Long): Flow<RecognitionTaskEntity?>

    /**
     * 任务列表卡片。
     *
     * 用两个标量子查询带出「几张图」和「封面」，避免在列表里对每行再查一次图片表
     * （N+1）。封面取 `sortOrder` 最小的那张 —— 用户调序之后封面要跟着换。
     */
    @Query(
        """
        SELECT t.id AS id,
               t.status AS status,
               t.createdAt AS createdAt,
               t.completedAt AS completedAt,
               t.origin AS origin,
               t.code AS code,
               (SELECT COUNT(*) FROM recognition_task_image i WHERE i.taskId = t.id) AS imageCount,
               (SELECT i.imagePath FROM recognition_task_image i
                 WHERE i.taskId = t.id ORDER BY i.sortOrder ASC LIMIT 1) AS coverPath,
               t.pendingMergePlantId AS pendingMergePlantId,
               t.pendingMergeLevel AS pendingMergeLevel,
               t.pendingMergeReason AS pendingMergeReason,
               t.errorMessage AS errorMessage,
               t.resultObservationId AS resultObservationId,
               (SELECT o.plantId FROM plant_observation o
                 WHERE o.id = t.resultObservationId) AS resultPlantId
        FROM recognition_task t
        ORDER BY t.createdAt DESC
        """,
    )
    fun observeTaskCards(): Flow<List<TaskCardRow>>

    // ---------------- 队列取用 ----------------

    /**
     * 待执行队列：**优先级高的先跑，同优先级先来先跑**。
     *
     * 「越大越先执行」是产品语义（现场调查 10 > 普通 0），
     * 所以这里是 `priority DESC`；而 `createdAt ASC` 保证普通任务之间不会插队。
     */
    @Query(
        """
        SELECT * FROM recognition_task
        WHERE status = 'PENDING'
        ORDER BY priority DESC, createdAt ASC
        """,
    )
    suspend fun getPending(): List<RecognitionTaskEntity>

    @Query(
        """
        SELECT * FROM recognition_task
        WHERE status = 'PENDING'
        ORDER BY priority DESC, createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getPendingLimited(limit: Int): List<RecognitionTaskEntity>

    /**
     * 在途任务（[RecognitionTaskStatus.isBusy] 的同一口径）。
     *
     * 用途：应用启动时的「僵尸状态自愈」—— 上一次进程被杀时留下
     * `PROCESSING` 的任务永远不会有 Worker 来推进，必须主动发现并复位。
     */
    @Query("SELECT * FROM recognition_task WHERE status IN ('QUEUED', 'PROCESSING', 'ANALYZING')")
    suspend fun getInFlight(): List<RecognitionTaskEntity>

    /**
     * 复位僵尸任务：把在途状态打回 PENDING。
     *
     * 只改状态、**清掉 `startedAt`**，这样重新开跑时耗时的起点是新的，
     * 不会把「上次跑到一半被杀」的时间算进这一轮。
     */
    @Query(
        """
        UPDATE recognition_task
        SET status = 'PENDING', startedAt = NULL
        WHERE status IN ('QUEUED', 'PROCESSING', 'ANALYZING')
        """,
    )
    suspend fun resetInFlightToPending(): Int

    /**
     * 终态且**早于 cutoff** 的任务。
     *
     * 自动清理（方案外的用户新增需求：任务完成后不用手动清）只挑这三类，
     * 且**跳过待裁决**（`pendingMergePlantId IS NULL`）——
     * 挂靠裁决信息只存在于任务行上，行没了用户就永远没机会「拆分」了。
     */
    @Query(
        """
        SELECT * FROM recognition_task
        WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')
          AND pendingMergePlantId IS NULL
          AND completedAt IS NOT NULL AND completedAt < :cutoff
        """,
    )
    suspend fun getFinishedBefore(cutoff: Long): List<RecognitionTaskEntity>

    // ---------------- 计数 ----------------

    @Query("SELECT COUNT(*) FROM recognition_task WHERE status = :status")
    fun observeCountByStatus(status: RecognitionTaskStatus): Flow<Int>

    @Query("SELECT COUNT(*) FROM recognition_task WHERE status = :status")
    suspend fun countByStatus(status: RecognitionTaskStatus): Int

    @Query("SELECT COUNT(*) FROM recognition_task WHERE status = :status AND createdAt >= :since")
    suspend fun countByStatusSince(status: RecognitionTaskStatus, since: Long): Int

    @Query("SELECT COUNT(*) FROM recognition_task WHERE origin = :origin AND createdAt >= :since")
    suspend fun countSurveySince(origin: String, since: Long): Int
}
