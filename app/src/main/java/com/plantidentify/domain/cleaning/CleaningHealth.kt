package com.plantidentify.domain.cleaning

/**
 * 数据健康度。
 *
 * ## 为什么不是一个「问题数 ÷ 档案数」
 *
 * 最直观的算法（问题数 / 档案数）有两个致命缺陷：
 *
 * 1. **一个问题算一次** —— 一株植物缺拉丁名、缺科、缺属是三条问题，
 *    按条数算它一个人就顶三株。「一株有 3 个小毛病」和
 *    「3 株各有一个大毛病」在这个口径下等价，而后者严重得多。
 * 2. **严重程度不影响得分** —— 一张重复照片（LOW）和一次「观察无主」（HIGH）
 *    同权。用户会觉得这个百分比不反映他关心的东西。
 *
 * 所以改成按**档案**计权：每株取它名下最严重的一条问题的权重，
 * 汇总后除以「所有档案都有 HIGH 问题」这个理论上限。
 *
 * ```
 * 健康度 = 100 − 100 × Σ(每株最重问题的权重) / (档案数 × HIGH 的权重)
 * ```
 *
 * 直观校验：全部档案都有严重问题 → 0 分；一半有严重问题 → 50 分；
 * 17 株里有 1 株缺拉丁名（MEDIUM，权重 3）→ 100 − 100×3/136 ≈ 98 分。
 */
data class CleaningHealth(
    /** 库里有多少株（不含回收站） */
    val totalRecords: Int,

    /** 待处理问题条数 */
    val openIssues: Int,

    /** 被至少一条问题牵连的株数 */
    val affectedRecords: Int,

    /** 0–100 */
    val score: Int,

    /** 分类计数（页面上的四个数字就是它） */
    val byCategory: Map<CleaningIssueCategory, Int>,

    /** 按类型细分，用于列表排序与图标 */
    val byType: Map<CleaningIssueType, Int>,
) {
    val hasIssues: Boolean get() = openIssues > 0

    fun countOf(category: CleaningIssueCategory): Int = byCategory[category] ?: 0

    fun countOf(type: CleaningIssueType): Int = byType[type] ?: 0

    /** 有没有「严重」级别的问题 —— 页面据此决定要不要标红 */
    val hasSevere: Boolean get() = openIssues > 0 && score < 90

    companion object {
        /** 空库 / 一切正常 */
        val PERFECT = CleaningHealth(
            totalRecords = 0,
            openIssues = 0,
            affectedRecords = 0,
            score = 100,
            byCategory = emptyMap(),
            byType = emptyMap(),
        )
    }
}

object CleaningHealthCalculator {

    /**
     * 算健康度。
     *
     * @param totalRecords 未删除的档案数
     * @param issues 待处理（OPEN）的清洗问题
     * @param aliveIds 未删除的档案 id。给了它才会把「引用了已删档案的问题」
     *        排除在 [CleaningHealth.affectedRecords] 之外 ——
     *        否则界面会出现「共 2 株档案，涉及 3 株」这种自相矛盾的文案
     *        （合并之后，某条涉及被合并株的问题要等下次扫描才会结案）。
     */
    fun compute(
        totalRecords: Int,
        issues: List<CleaningIssue>,
        aliveIds: Set<Long>? = null,
    ): CleaningHealth {
        val byCategory = issues.groupingBy { it.type.category }.eachCount()
        val byType = issues.groupingBy { it.type }.eachCount()

        // 每株取最重的一条。用 map 而不是遍历求最大值，是因为同一条问题
        // 可能牵连多株（疑似重复就是两条 recordIds），两株都要算
        val worstPerRecord = HashMap<Long, Int>()
        for (issue in issues) {
            for (recordId in issue.recordIds) {
                if (aliveIds != null && recordId !in aliveIds) continue
                val weight = issue.severity.weight
                val current = worstPerRecord[recordId] ?: 0
                if (weight > current) worstPerRecord[recordId] = weight
            }
        }

        val score = if (totalRecords <= 0) {
            // 空库不该显示「60 分」：没有数据就没有可评估的对象，
            // 而空库确实可能有问题（孤儿观察指向已物理删除的档案）——
            // 有这类问题时给 0 分提示，否则满分
            if (issues.isEmpty()) 100 else 0
        } else {
            val capacity = totalRecords.toLong() * CleaningSeverity.HIGH.weight
            val used = worstPerRecord.values.sumOf { it.toLong() }
            (100 - 100.0 * used / capacity).toInt().coerceIn(0, 100)
        }

        return CleaningHealth(
            totalRecords = totalRecords,
            openIssues = issues.size,
            affectedRecords = worstPerRecord.size,
            score = score,
            byCategory = byCategory,
            byType = byType,
        )
    }
}
