package com.plantidentify.domain.import

import com.plantidentify.domain.cleaning.CandidateSetBuilder
import com.plantidentify.domain.cleaning.DuplicateMatcher
import com.plantidentify.domain.cleaning.RecordSnapshot

/**
 * 一条导入记录的处置判定。
 *
 * 与 [com.plantidentify.data.local.entity.FolderImportStatus] 是**两个层面**的东西：
 * 这里是「算法怎么判的」（一次性算出来），那边是「用户怎么决定的」（要持久化）。
 * 前者可能被重算，后者不会 —— 混成一个枚举会让「重跑一次检测」
 * 顺手把用户的决定抹掉。
 */
enum class ImportDecision {
    /** 与本地任何记录都不沾边，直接入库 */
    NEW,

    /** 本地可以确定是同一条（拉丁学名全同 / 中文名+科+属全同） */
    CONCLUSIVE,

    /** 像是同一条，但没有硬证据 —— **必须问用户** */
    SUSPECTED,

    /** 命中了候选，但科或属对不上 —— 有可能是填错，也有可能根本是两种植物 */
    CONFLICT,
}

/** 一条导入记录的判定结果 */
data class ImportMatch(
    /** 导入进来那条 `plant_record` 的 id（已经入库，所以有真实 id） */
    val importPlantId: Long,

    val decision: ImportDecision,

    /** 匹配到的本地记录；[ImportDecision.NEW] 时为 null */
    val matchedPlantId: Long? = null,

    /** 命中等级（1–6），[ImportDecision.NEW] 时为 null */
    val matchLevel: Int? = null,

    /** 判据原文（「凭什么说它们像」），直接给用户看 */
    val reason: String? = null,
) {
    /** 需要用户逐条过一遍的，才进「待处理」 */
    val needsReview: Boolean
        get() = decision == ImportDecision.SUSPECTED || decision == ImportDecision.CONFLICT
}

/**
 * 匹配结果 + **这一轮扫得全不全**。
 *
 * `partial` / `skippedRecords` 必须一路传到界面上：候选集为 0 有两种完全不同的
 * 含义 —— 「真的没有重复」和「它们压根没被比较过」。分不出来的话，
 * 用户会拿着一份漏报的结果放心地合并（`CandidateSetBuilder` 的候选集
 * 因为有硬上限会被截断，这不是理论问题）。
 */
data class ImportMatchResult(
    val matches: List<ImportMatch>,
    val partial: Boolean = false,
    val skippedRecords: Int = 0,
) {
    val pendingCount: Int get() = matches.count { it.needsReview }
}

/**
 * 导入匹配（v1.0.2 Phase 2 §十/十一）。
 *
 * ## 复用而不是重写
 *
 * 需求原文：「复用当前已经存在的数据清洗和去重能力，不要因为本版本加入
 * 文件夹系统就重新实现一套去重算法」。所以这里一行判定逻辑都不新写 ——
 * 候选集交给 [CandidateSetBuilder]，六级判定交给 [DuplicateMatcher]，
 * 本类只负责**把结果翻译成「导入场景」的语义**：
 *
 * | 清洗模块的说法 | 导入场景的含义 |
 * |---|---|
 * | `isConclusive`（level ≤ 2） | 确定重复，可以建议合并 |
 * | level 3–6 | 疑似重复，必须让用户确认 |
 * | 科/属冲突 | 更严重 —— 连「像」都站不住，单独标出来 |
 *
 * ## 为什么要把两边混在一起跑候选集
 *
 * 只把「导入的记录」两两比对是不够的 —— 真正的重复发生在
 * **导入的记录 × 本地的记录**之间。所以两边一起喂给
 * [CandidateSetBuilder]，再把「两侧同源」的候选对丢掉
 * （本地 vs 本地是清洗模块的活，导入 vs 导入是同一次导入内部的重复）。
 */
object ImportMatcher {

    /**
     * @param imported 刚插入库里的导入记录快照（**已在库中，id 是真实的**）
     * @param local 本来就在库里的记录快照（要排除掉刚插入的那些）
     */
    fun match(
        imported: List<RecordSnapshot>,
        local: List<RecordSnapshot>,
    ): ImportMatchResult {
        if (imported.isEmpty()) return ImportMatchResult(emptyList())

        val importedIds = imported.mapTo(HashSet()) { it.id }
        val all = local + imported
        val candidates = CandidateSetBuilder.build(all)
        val byId = all.associateBy { it.id }

        // 每条导入记录只保留**最强的一条**结论（level 最小 = 证据最硬）。
        // 留多条会让用户在界面上面对「这株可能和本地三株都像，你挑一个」——
        // 那是另一个功能（合并预览）的活，导入这一步要的是「要不要处理它」。
        val best = HashMap<Long, MatchPair>()

        for (pair in candidates.pairs) {
            val aImported = pair.aId in importedIds
            val bImported = pair.bId in importedIds
            // 两侧同源：本地 vs 本地（清洗模块负责）、导入 vs 导入（同批内部重复）
            if (aImported == bImported) continue

            val a = byId[pair.aId] ?: continue
            val b = byId[pair.bId] ?: continue
            val candidate = DuplicateMatcher.match(a, b) ?: continue

            val importId = if (aImported) pair.aId else pair.bId
            val localSnapshot = if (aImported) b else a

            val current = best[importId]
            if (current == null || candidate.level < current.candidate.level) {
                best[importId] = MatchPair(candidate, localSnapshot)
            }
        }

        val matches = imported.map { snapshot ->
            val hit = best[snapshot.id]
            if (hit == null) {
                ImportMatch(snapshot.id, ImportDecision.NEW)
            } else {
                val localSnapshot = hit.local
                val conflicting = conflicts(snapshot, localSnapshot)
                ImportMatch(
                    importPlantId = snapshot.id,
                    decision = when {
                        // 冲突优先于「确定重复」：level 1/2 也可能一边的科属填错了，
                        // 此时按「确定」处理会让用户跳过真正的矛盾
                        conflicting -> ImportDecision.CONFLICT
                        hit.candidate.isConclusive -> ImportDecision.CONCLUSIVE
                        else -> ImportDecision.SUSPECTED
                    },
                    matchedPlantId = localSnapshot.id,
                    matchLevel = hit.candidate.level,
                    reason = hit.candidate.reason,
                )
            }
        }

        return ImportMatchResult(
            matches = matches,
            partial = candidates.partial,
            skippedRecords = candidates.skippedRecords,
        )
    }

    private class MatchPair(
        val candidate: com.plantidentify.domain.cleaning.DuplicateCandidate,
        val local: RecordSnapshot,
    )

    /**
     * 科或属**两边都有值且不同**就算冲突。
     *
     * 与 `DuplicateMatcher` 内部那个 `conflictsWith` 同一个口径，但这里是站在
     * 「导入」的角度重新看一遍 —— **等级只回答了「像到什么程度」，
     * 没回答「有没有硬矛盾」**，这是两个维度。
     *
     * 刻意**不看命中等级**：这个判断在每一级都是安全的，
     * level 2 的定义本身就是「中文名 + 科 + 属三者全同」，
     * 它天然不可能走到这里返回 true。
     *
     * 一边为空不算冲突 —— 「不知道」恰恰是导入数据最常见的样子。
     */
    private fun conflicts(a: RecordSnapshot, b: RecordSnapshot): Boolean =
        differs(a.family, b.family) || differs(a.genus, b.genus)

    private fun differs(a: String?, b: String?): Boolean {
        val left = a?.trim().orEmpty()
        val right = b?.trim().orEmpty()
        return left.isNotEmpty() && right.isNotEmpty() && left != right
    }
}
