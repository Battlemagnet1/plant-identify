package com.plantidentify.domain.cleaning

/**
 * AI 对一组候选给出的结论类型（方案 §8.2）。
 *
 * ## 为什么除了「是不是同一种」还要分三类
 *
 * 用户的动作不一样：
 * - [POSSIBLE_DUPLICATE] → 去合并预览
 * - [DATA_CONFLICT] → 去编辑页改字段（两株不是同一株，但有一边的字段是错的）
 * - [ALIAS_RELATION] → 什么都不用做，只是名称差异（俗称/异名）
 * - [NOT_SAME] → 直接结案
 *
 * 只给「是不是同一种」的话，界面只能一律显示「去合并」——
 * 而其中一半的正确答案是「改字段」或「什么都不用做」。
 */
enum class CleaningVerdictType(val label: String, val defaultSame: Boolean) {
    /** 确实可能是同一株 */
    POSSIBLE_DUPLICATE("疑似重复", defaultSame = true),

    /** 不是同一株，但某一边的字段值互相矛盾（如中文名与学名不匹配） */
    DATA_CONFLICT("信息冲突", defaultSame = false),

    /** 同一物种的不同名称写法（异名 / 俗称），不需要合并 */
    ALIAS_RELATION("名称差异", defaultSame = false),

    /** 明确不是同一株 */
    NOT_SAME("并非同一株", defaultSame = false),
}

/**
 * AI 对一条候选的判定。
 *
 * @param issueId 对应哪条清洗问题（本地候选的 id）
 * @param isSame 结论：是否同一株。**核心字段**，其它都是解释
 * @param confidence AI 自评的把握（0–1），仅用于展示与排序
 * @param reason 一句话中文理由（prompt 里要求 ≤50 字）
 */
data class CleaningVerdict(
    val issueId: Long,
    val type: CleaningVerdictType,
    val isSame: Boolean,
    val confidence: Double? = null,
    val reason: String? = null,
) {
    /**
     * 该不该自动结案。
     *
     * 只有 [CleaningVerdictType.NOT_SAME] 与 [CleaningVerdictType.ALIAS_RELATION]
     * 会自动把问题标成「已解决」：[NOT_SAME] 是明确否定，
     * [ALIAS_RELATION] 是「名字不同但不用管」——两者都无事可做，
     * 留在待办里只会让列表变噪音。
     *
     * [DATA_CONFLICT] **不自动结案**：它虽然也不是「同一种」，
     * 但意味着有一边的字段是错的，用户需要去改 —— 那是待办。
     */
    val dismissesIssue: Boolean
        get() = type == CleaningVerdictType.NOT_SAME || type == CleaningVerdictType.ALIAS_RELATION

    /** AI 确认「是同一株」——这类问题要提到最前面 */
    val confirmsDuplicate: Boolean
        get() = type == CleaningVerdictType.POSSIBLE_DUPLICATE && isSame
}
