package com.plantidentify.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.plantidentify.domain.cleaning.CleaningIssue
import com.plantidentify.domain.cleaning.CleaningIssueStatus
import com.plantidentify.domain.cleaning.CleaningIssueType
import com.plantidentify.domain.cleaning.CleaningSeverity

/**
 * 清洗问题（落库形态）。
 *
 * ## 为什么必须落库
 *
 * 每一次扫描都能重新算出一批问题，但**算得出不等于该再问一遍**：
 * 用户点过「忽略」的问题，下次扫描若还冒出来，这个功能就会被当成噪音关掉。
 * 落库之后「忽略」才有意义 —— 靠 [fingerprint] 的**唯一索引**
 * 让同一问题只存在一行，`insertIgnore` 天然不覆盖用户已做的决定。
 *
 * ## 与档案的关联为什么是 `recordIds` 而不是外键
 *
 * 一条问题可能涉及两株（疑似重复）或多株（同一张照片被多处引用）。
 * 建关联表要引入一张新表 + 一个 DAO，而这里只有「读出来展示」一个用途，
 * 用 `id` 列表足够；何况问题行是**派生数据**，档案没了它也就该清掉，
 * 不需要数据库层面的级联约束来保证一致性。
 */
@Entity(
    tableName = "cleaning_issue",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["status"]),
    ],
)
data class CleaningIssueEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /**
     * 稳定指纹，见 [CleaningIssue.fingerprint]。
     *
     * 唯一索引 = 「同一问题只允许一行」，这是「忽略」能生效的**全部机制**。
     */
    val fingerprint: String,

    val type: CleaningIssueType,

    val severity: CleaningSeverity,

    /**
     * 涉及档案 id，**逗号分隔**（如 `"12,38"`）。
     *
     * 方案里写的是 JSON 串，这里改成逗号串：ids 全是 Long，
     * 逗号串的编解码是两行纯 Kotlin 代码、可直接单测；
     * 而 `org.json` 在 JVM 单测里是打不通的桩（会把测试逼到
     * Robolectric 或 instrumented 上去，为存储格式付出这个代价不值）。
     */
    val recordIds: String,

    /**
     * 指纹的第三段（见 [CleaningIssue.discriminator]）。
     *
     * 它不是冗余字段：留下它才能在库里一眼看出「同一条档案为什么有
     * 三行 MISSING_FIELD」——`WHERE discriminator IN ('latinName','family','genus')`
     * 比对着三行一模一样的记录猜要快得多。
     */
    val discriminator: String? = null,

    /** 相似度 0..1；仅疑似重复有值 */
    val similarity: Double? = null,

    /** 判据说明（本地规则的结论） */
    val reason: String,

    val aiUsed: Boolean = false,

    val aiReason: String? = null,

    val status: CleaningIssueStatus = CleaningIssueStatus.OPEN,

    val createdAt: Long,

    val updatedAt: Long,
) {
    /** 解码 [recordIds]；坏数据不抛异常，退化成空列表 */
    fun recordIdList(): List<Long> =
        recordIds.split(',').mapNotNull { it.trim().toLongOrNull() }

    fun toDomain(): CleaningIssue = CleaningIssue(
        type = type,
        severity = severity,
        recordIds = recordIdList(),
        similarity = similarity,
        reason = reason,
        aiUsed = aiUsed,
        aiReason = aiReason,
        discriminator = discriminator,
    )

    companion object {
        /** 编码 id 列表；空列表会写成空串，读取时解析回空列表 */
        fun encodeIds(ids: List<Long>): String =
            ids.distinct().sorted().joinToString(",")

        /** 由领域模型落库；[taskId] 之外的一切都取自 [issue] */
        fun fromDomain(
            issue: CleaningIssue,
            now: Long,
            id: Long = 0L,
            status: CleaningIssueStatus = CleaningIssueStatus.OPEN,
        ): CleaningIssueEntity = CleaningIssueEntity(
            id = id,
            fingerprint = issue.fingerprint,
            type = issue.type,
            severity = issue.severity,
            recordIds = encodeIds(issue.recordIds),
            discriminator = issue.discriminator,
            similarity = issue.similarity,
            reason = issue.reason,
            aiUsed = issue.aiUsed,
            aiReason = issue.aiReason,
            status = status,
            createdAt = now,
            updatedAt = now,
        )
    }
}
