package com.plantidentify.domain.cleaning

/**
 * 一条疑似重复的候选对。
 *
 * @param level 命中的等级（1–6，见 [DuplicateMatcher]），数字越小证据越硬
 * @param similarity 该等级对应的相似度分值；等级 1、2、5 是「全同/相同」类判据，
 *        没有连续分值，沿用 1.0
 * @param reason 给用户看的中文判据（「凭什么说这是同一株」）
 * @param needsAi 是否需要 AI 复核。**只有落在灰区的才为 true** ——
 *        正常数据一次 AI 都不该调
 */
data class DuplicateCandidate(
    val aId: Long,
    val bId: Long,
    val level: Int,
    val similarity: Double,
    val reason: String,
    val needsAi: Boolean,
) {
    /** 与方向无关的稳定标识（a/b 谁在前不影响） */
    fun pairKey(): String = if (aId <= bId) "$aId:$bId" else "$bId:$aId"

    /** 等级 1、2 是本地**直接可判**的证据；3–6 只是「值得看看」 */
    val isConclusive: Boolean get() = level <= 2
}

/**
 * 六级优先级判定（方案 §7.4）。
 *
 * ## 命中即停，不继续降级
 *
 * 一次比较只返回**一条**结论。这看起来是损失了信息（比如一对档案可能
 * 同时「中文名相似」且「科属相同」），但换来的是可解释性：
 * 用户看到的是「为什么认为它们可能是同一株」的**最强理由**，
 * 而不是一堆需要自己权衡的分数。
 *
 * 这直接决定了界面上怎么显示 —— 一条理由能写成一句话，
 * 五条理由只能摊成表格，而用户要的只是「要不要合并」这个判断。
 *
 * ## 无论哪一级都**绝不自动合并**
 *
 * 这是规格书的硬约束，也写在本类的设计里：本类只产出候选，
 * 唯一的消费者是清洗问题列表与合并预览页，没有任何一条代码路径
 * 会在没有用户点击的情况下调用 `mergeInto`。
 */
object DuplicateMatcher {

    /** 中文名相似度下限：低于它的对根本不进候选（§7.3 ③ 也用同一个值） */
    const val NAME_FLOOR = 0.55

    /** 高于它视为本地强证据，不再麻烦 AI */
    const val NAME_STRONG = 0.90

    /** 拉丁学名相似度下限 */
    const val LATIN_FLOOR = 0.85

    /**
     * 属名不同时拉丁相似度的封顶值。
     *
     * 「同属不同种」与「不同属但学名长得像」是两回事：后者
     * 常常是拼写变体或同名异物。封到 0.60 之后它连 [LATIN_FLOOR]
     * 都够不到，也就不会命中等级 4 —— 这是**故意让它落选**，
     * 而不是把证据强度调低后仍然放行。
     */
    const val LATIN_GENUS_MISMATCH_CAP = 0.60

    /** 简介相似度下限 */
    const val DESCRIPTION_FLOOR = 0.70

    /**
     * 比较两条档案，返回命中的候选；没有命中返回 null。
     *
     * 调用方通常是候选集里的每一对（已由倒排索引筛过一遍）。
     */
    fun match(a: RecordSnapshot, b: RecordSnapshot): DuplicateCandidate? {
        if (a.id == b.id) return null

        val latinA = TextNormalizer.normalizeLatin(a.latinName.orEmpty())
        val latinB = TextNormalizer.normalizeLatin(b.latinName.orEmpty())
        val nameA = TextNormalizer.normalizeChinese(a.name)
        val nameB = TextNormalizer.normalizeChinese(b.name)
        val familyA = TextNormalizer.normalizeChinese(a.family.orEmpty())
        val familyB = TextNormalizer.normalizeChinese(b.family.orEmpty())
        val genusA = TextNormalizer.normalizeChinese(a.genus.orEmpty())
        val genusB = TextNormalizer.normalizeChinese(b.genus.orEmpty())

        // ---- 级 1：归一化拉丁学名完全相同（且非空）----
        //
        // 「且非空」不能省：两条都没填学名时它们「相同」，
        // 但那是「都不知道」而不是「是同一株」。
        if (latinA.isNotEmpty() && latinA == latinB) {
            return DuplicateCandidate(
                a.id, b.id, level = 1, similarity = 1.0,
                reason = "拉丁学名完全相同（$latinA）",
                needsAi = false,
            )
        }

        // ---- 级 2：中文名 + 科 + 属三者全同 ----
        //
        // 同样要求三者都非空。植物名重名的概率不低（「榕树」在全国
        // 指的不是一个物种），科属一致才能真正把它钉下去；
        // 而「两边的科都没填」显然不是一致性证据。
        if (nameA.isNotEmpty() && nameA == nameB &&
            familyA.isNotEmpty() && familyA == familyB &&
            genusA.isNotEmpty() && genusA == genusB
        ) {
            return DuplicateCandidate(
                a.id, b.id, level = 2, similarity = 1.0,
                reason = "中文名、科、属三者相同（$nameA / ${a.family} / ${a.genus}）",
                needsAi = false,
            )
        }

        // ---- 级 3：中文名相似 ----
        val nameScore = Similarity.nameSimilarity(a.name, b.name)
        if (nameScore >= NAME_FLOOR) {
            val strong = nameScore >= NAME_STRONG
            // 方案说「s ≥ 0.90 视为本地强证据」，但如果两边名字一模一样
            // 而科或属**都有值且不同**，那说明至少有一边是错的 ——
            // 这正是 AI 该出场的「疑难灰区」，本地不能拿强证据把它压过去。
            //
            // 触发场景真实存在：用户先后识别出两株都叫「榕树」的植物，
            // 一个是桑科榕属、一个被模型写成了别的科。此时本地判
            // 「名字相同 → 强证据」会让用户直接合并掉两株不同的植物。
            val contradicting = conflictsWith(familyA, familyB) || conflictsWith(genusA, genusB)
            return DuplicateCandidate(
                a.id, b.id, level = 3, similarity = nameScore,
                reason = if (contradicting) {
                    "中文名高度一致（${a.name.trim()}），但科或属不一致，需要进一步核对"
                } else {
                    "中文名相似度 ${percent(nameScore)}（${a.name.trim()} ↔ ${b.name.trim()}）"
                },
                // 0.55–0.90 是灰区：这类「一字之差」既可能是异名也可能是
                // 完全不同的两种植物，本地算法判不了，交给 AI
                needsAi = !strong || contradicting,
            )
        }

        // ---- 级 4：拉丁学名高度相似 ----
        if (latinA.isNotEmpty() && latinB.isNotEmpty()) {
            val rawLatin = Similarity.latinSimilarity(latinA, latinB)
            val sameGenus = genusA.isNotEmpty() && genusA == genusB
            val latinScore = if (sameGenus) rawLatin else minOf(rawLatin, LATIN_GENUS_MISMATCH_CAP)
            if (latinScore >= LATIN_FLOOR) {
                return DuplicateCandidate(
                    a.id, b.id, level = 4, similarity = latinScore,
                    reason = "拉丁学名高度相似（${percent(latinScore)}）",
                    needsAi = true,
                )
            }
        }

        // ---- 级 5：科、属都相同（名称完全不同）----
        //
        // 到这一步名称已经不像了，但科属一致说明是同属的亲戚。
        // 这是最容易误报的一级（同属几十个种很常见），
        // 所以一律需要 AI —— 本地只能说「它们沾亲」，说不了「是同一株」。
        if (familyA.isNotEmpty() && familyA == familyB &&
            genusA.isNotEmpty() && genusA == genusB
        ) {
            return DuplicateCandidate(
                a.id, b.id, level = 5, similarity = 0.0,
                reason = "科、属相同但名称不同（${a.family} / ${a.genus}）",
                needsAi = true,
            )
        }

        // ---- 级 6：简介内容高度相似 ----
        val descScore = Similarity.descriptionSimilarity(a.description, b.description)
        if (descScore >= DESCRIPTION_FLOOR &&
            !a.description.isNullOrBlank() && !b.description.isNullOrBlank()
        ) {
            return DuplicateCandidate(
                a.id, b.id, level = 6, similarity = descScore,
                reason = "植物简介内容高度相似（${percent(descScore)}）",
                needsAi = true,
            )
        }

        return null
    }

    private fun percent(value: Double): String = "${(value * 100).toInt()}%"

    /**
     * 两边**都有值且不同**才算冲突。
     *
     * 一边为空不算冲突 —— 「不知道」与「说的不一样」是两回事：
     * 前者恰恰是 AI 最容易帮忙补上的信息，后者才是矛盾。
     */
    private fun conflictsWith(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && a != b
}
