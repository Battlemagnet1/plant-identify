package com.plantidentify.domain.cleaning

/**
 * 字符串相似度。
 *
 * ## 只保留三种算法
 *
 * | 算法 | 角色 | 为什么用它 |
 * |---|---|---|
 * | 编辑距离 | 短名称 | 对「悬铃木 / 悬铃树」这种**一字之差**最敏感 |
 * | Jaccard（n-gram / token） | 主力 | 对词序不敏感、对插入鲁棒，且**不需要分词** |
 * | Token 相似度 | 长文本 | 即 Jaccard 在 token 集上的特化，不重复造轮子 |
 *
 * **刻意不做 SimHash**：它面向「长文档近似重复」（64 位指纹 + Hamming 阈值 + 分桶）。
 * 本项目最长的文本也就几百字，而且候选集已被倒排索引压到接近 O(N) ——
 * 引入它只会增加实现与调参成本，收益为零。
 *
 * ## 空值的语义
 *
 * 「两边都空」= 1.0（没有分歧）；「一边空」= 0.0（无从比较，不能当成相同）。
 * 这个约定必须全项目一致 —— 若这里返回 1.0、那里返回 0.0，
 * 候选集会凭空多出（或少掉）一批，而且极难排查。
 */
object Similarity {

    /** 空值语义：都空 = 1.0；一空 = 0.0 */
    private fun emptyScore(a: String, b: String): Double? = when {
        a.isBlank() && b.isBlank() -> 1.0
        a.isBlank() || b.isBlank() -> 0.0
        else -> null
    }

    /**
     * Levenshtein 编辑距离（滚动数组版）。
     *
     * 用两行而不是完整矩阵：短名称的 DP 开销本就微不足道，
     * 但滚动数组让它对任何长度都不再有 O(n·m) 的内存占用，
     * 于是「描述字段意外很长」也不会让扫描卡住。
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)

        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(
                    prev[j] + 1,        // 删除
                    cur[j - 1] + 1,     // 插入
                    prev[j - 1] + cost, // 替换
                )
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length]
    }

    /** 编辑相似度 = 1 - 距离 / 较长者长度 */
    fun editSimilarity(a: String, b: String): Double {
        emptyScore(a, b)?.let { return it }
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    /** Jaccard：交集 / 并集。两边都空 → 1.0（与 [emptyScore] 一致） */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    /**
     * 名称相似度（中文名）。
     *
     * ```
     * 长名（> 4 字）：token Jaccard × 0.7 + 编辑距离 × 0.3
     * 短名（≤ 4 字）：token Jaccard × 0.5 + 编辑距离 × 0.5
     * ```
     *
     * ## 为什么加权而不是取最大
     *
     * 两者各有一处软肋：Jaccard 对**字序**不敏感（「木铃悬」与「悬铃木」
     * 会得高分），编辑距离对**短名**过于严苛（3 字名改 1 字就掉到 0.67）。
     * 加权后要同时满足「词形接近」与「字序接近」，误判明显更少。
     *
     * ## 为什么短名要单独一套权重（实测后才加的）
     *
     * 中文植物名多为 2–5 字，而短名的 n-gram 集合极小 ——
     * 3 字名只有 2 个二元组，变一个字就丢掉 1/3 的交集，
     * 于是「悬铃木 / 悬铃树」这种**显然该提示**的异名会算出 0.43，
     * 够不到 §7.4 级 3 的 0.55 阈值，直接被漏掉。
     * 短名里编辑距离本身足够可靠（长度 ≤ 4 时不太可能出现
     * 「字序不同但字符相同」的巧合），所以把权重让一半给它。
     *
     * 注意 token 集合取的是**1-gram ∪ 2-gram**：只留二元组时，
     * 两字名（「紫薇」）会退化成单个 token，任何差异都可能让交集归零。
     */
    fun nameSimilarity(a: String, b: String): Double {
        emptyScore(a, b)?.let { return it }
        val na = TextNormalizer.normalizeChinese(a)
        val nb = TextNormalizer.normalizeChinese(b)
        emptyScore(na, nb)?.let { return it }

        val gramScore = jaccard(TextNormalizer.tokens(na), TextNormalizer.tokens(nb))
        val editScore = editSimilarity(na, nb)
        val gramWeight = if (maxOf(na.length, nb.length) <= SHORT_NAME_LENGTH) 0.5 else 0.7
        return gramScore * gramWeight + editScore * (1 - gramWeight)
    }

    /** 超过这个长度就按「长名」加权；见 [nameSimilarity] 的说明 */
    private const val SHORT_NAME_LENGTH = 4

    /** 拉丁学名相似度：先归一化（去作者引证、统一杂交符），再走同一套加权 */
    fun latinSimilarity(a: String, b: String): Double {
        emptyScore(a, b)?.let { return it }
        val na = TextNormalizer.normalizeLatin(a)
        val nb = TextNormalizer.normalizeLatin(b)
        emptyScore(na, nb)?.let { return it }
        return jaccard(tokensOf(na), tokensOf(nb)) * 0.7 + editSimilarity(na, nb) * 0.3
    }

    /**
     * 描述相似度：token Jaccard，长文本先截断。
     *
     * 截断 500 字不是性能考虑（几百字的 Jaccard 毫无压力），
     * 而是**语义**考虑：百科描述的开头是形态特征（区分度最高），
     * 结尾多是「园林用途」这类套话 —— 把套话算进去反而会拉高
     * 两株不同植物的相似度。
     */
    fun descriptionSimilarity(a: String?, b: String?): Double {
        emptyScore(a.orEmpty(), b.orEmpty())?.let { return it }
        val ta = TextNormalizer.tokens(a!!.take(500))
        val tb = TextNormalizer.tokens(b!!.take(500))
        return jaccard(ta, tb)
    }

    private fun tokensOf(text: String): Set<String> = TextNormalizer.tokens(text)
}
