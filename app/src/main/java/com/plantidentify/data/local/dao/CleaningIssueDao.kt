package com.plantidentify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plantidentify.data.local.entity.CleaningIssueEntity
import com.plantidentify.domain.cleaning.CleaningIssueStatus
import kotlinx.coroutines.flow.Flow

/**
 * 清洗问题的读写。
 *
 * ## `insertIgnore` 是这张表的核心语义
 *
 * 用 `OnConflictStrategy.IGNORE` 配 `fingerprint` 唯一索引，效果是：
 * **同一问题只存在一行，且重复扫描不改动已有行的 status**。
 *
 * 这一点必须用 IGNORE 而不是 REPLACE —— REPLACE 会把
 * 「用户点过忽略」的那行重置成 OPEN，用户下次打开清洗中心
 * 会看到自己明明忽略过的问题又回来了（而他没有任何办法知道为什么）。
 *
 * ## 排序为什么不在 SQL 里做
 *
 * `severity` 存的是枚举名（`HIGH`/`LOW`/`MEDIUM`），按 TEXT 排序的
 * 字典序恰好是错的（HIGH < LOW < MEDIUM）。要么写一个 `CASE WHEN`
 * 把权重硬编码进 SQL，要么在 Kotlin 里按 [com.plantidentify.domain.cleaning.CleaningSeverity.weight] 排。
 * 选后者：权重的唯一定义留在枚举上，SQL 里不出现第二份「严重程度排序」。
 */
@Dao
interface CleaningIssueDao {

    /**
     * 批量写入，**已存在的指纹直接跳过**（不更新、不报错）。
     *
     * 返回每条新插入行的 rowId，被跳过的位置是 -1（Room 的行为）。
     * 调用方通常不关心这个值。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(issues: List<CleaningIssueEntity>): List<Long>

    @Query("SELECT * FROM cleaning_issue WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): CleaningIssueEntity?

    @Query("SELECT * FROM cleaning_issue WHERE id = :id")
    suspend fun getById(id: Long): CleaningIssueEntity?

    @Query("SELECT * FROM cleaning_issue WHERE id = :id")
    fun observeById(id: Long): Flow<CleaningIssueEntity?>

    /**
     * 待处理的问题（全量）。
     *
     * 一次返回全部而不是分页 / 分组：清洗问题在同一时刻最多是
     * 「档案数 × 少量规则」的量级（几百条已经是极端），
     * 而界面要同时算出四个分类计数与健康度 —— 分页反而会让计数不准。
     * 排序交给 Kotlin（见类注释）。
     */
    @Query("SELECT * FROM cleaning_issue WHERE status = 'OPEN'")
    fun observeOpen(): Flow<List<CleaningIssueEntity>>

    @Query("SELECT * FROM cleaning_issue WHERE status = 'OPEN'")
    suspend fun getOpen(): List<CleaningIssueEntity>

    @Query("SELECT COUNT(*) FROM cleaning_issue WHERE status = 'OPEN'")
    fun observeOpenCount(): Flow<Int>

    /** 一次性读条数（扫描结束时要用它填进结果概览） */
    @Query("SELECT COUNT(*) FROM cleaning_issue WHERE status = 'OPEN'")
    suspend fun countOpen(): Int

    /**
     * 涉及某条档案的待处理问题。
     *
     * ## 为什么用带分隔符的 LIKE 而不是 `LIKE '%12%'`
     *
     * `recordIds` 是逗号串（`"12,38"`）。直接 `LIKE '%12%'` 会把
     * id = 112 的档案也匹配进来 —— 于是用户合并掉 12 号之后，
     * 112 号的问题被误标为「已解决」，从此再也看不到。
     *
     * 两侧补上逗号再匹配 `%,12,%` 才能保证是**整段**相等。
     * （`recordIds` 编码时已排序去重，所以不需要担心 `"38,12"` 这种顺序。）
     */
    @Query(
        "SELECT * FROM cleaning_issue " +
            "WHERE status = 'OPEN' AND ',' || recordIds || ',' LIKE '%,' || :recordId || ',%'",
    )
    suspend fun getOpenTouchingRecord(recordId: Long): List<CleaningIssueEntity>

    @Query("SELECT * FROM cleaning_issue WHERE status = :status")
    suspend fun getByStatus(status: CleaningIssueStatus): List<CleaningIssueEntity>

    @Query("UPDATE cleaning_issue SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: CleaningIssueStatus, now: Long)

    /**
     * 写入 AI 判定结果。
     *
     * 只更新 AI 相关字段与 `reason`/`similarity`，**不动 `createdAt`** ——
     * 问题的发现时间不该因为后来补了一次 AI 判定而改变。
     */
    @Query(
        "UPDATE cleaning_issue SET aiUsed = 1, aiReason = :aiReason, " +
            "similarity = COALESCE(:similarity, similarity), " +
            "reason = :reason, updatedAt = :now WHERE id = :id",
    )
    suspend fun applyAiVerdict(
        id: Long,
        aiReason: String?,
        similarity: Double?,
        reason: String,
        now: Long,
    )

    @Query("DELETE FROM cleaning_issue")
    suspend fun clearAll()
}
