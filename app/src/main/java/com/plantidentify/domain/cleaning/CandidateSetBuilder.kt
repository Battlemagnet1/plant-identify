package com.plantidentify.domain.cleaning

/**
 * 一个待比较的候选对（id 小的在前，保证方向无关）。
 *
 * 它**只是「值得交给 [DuplicateMatcher] 比一比」**，不是「疑似重复」——
 * 判定结论由后者给出。
 */
data class CandidatePair(
    val aId: Long,
    val bId: Long,

    /** 共享的二元组个数；用于超出上限时排序取舍 */
    val sharedGrams: Int,

    /** 拉丁学名的属名是否相同（最强的一类旁证） */
    val sameLatinGenus: Boolean,
)

/** 候选集生成结果 */
data class CandidateResult(
    val pairs: List<CandidatePair>,

    /** 截断前的候选对数 —— 用来判断「这次是不是没扫全」 */
    val totalBeforeCap: Int,

    val totalRecords: Int,

    /** 候选对被硬上限截断（界面要提示「本次为部分扫描」） */
    val partial: Boolean,

    /**
     * 因为倒排表过长被整段丢弃、**因而没有参与任何两两比对**的档案数。
     *
     * 这个数字必须传出去，否则「候选为空」会有两种完全不同的含义
     * 而界面分不出来：真的不重复，还是它们全被丢掉了。
     * 2026-09-23 的 10000 株压测就是后者 —— 12 个物种每个约 833 条，
     * 全部倒排表都超过 [MAX_POSTING_LIST] 被丢弃，候选集为 0，
     * 而清洗中心显示「100 分 · 数据很干净」。
     */
    val skippedRecords: Int = 0,
)

/**
 * 候选集生成（方案 §7.3）：**二元组倒排索引 + 属名分桶**，避免 O(N²)。
 *
 * ## 为什么必须避免 O(N²)
 *
 * 两两比较是最直观的写法，代价是 N(N−1)/2 次名称相似度计算。
 * 300 株时是 4.5 万次（可接受），但档案攒到 3000 株就是 450 万次 ——
 * 每次都要做归一化 + 编辑距离 DP，清洗中心会卡到不可用。
 *
 * 倒排索引把「可能相似」的对挑出来，实测能砍掉 95% 以上的比较。
 *
 * ## 三层筛选，逐层放宽
 *
 * 1. **共享 ≥ 2 个二元组** —— 主路径。「悬铃木」与「悬铃树」共享
 *    「悬铃」，只有 1 个，所以还要看第 3 层
 * 2. **拉丁属名相同** —— 名称完全不像但同属的（「榕树」/「菩提树」），
 *    靠名称索引永远找不到，必须单独分桶
 * 3. **共享 ≥ 1 个二元组且 nameSimilarity ≥ 0.55** —— 召回兜底，
 *    代价是要算相似度，所以先用「共享 ≥1」把范围压住
 *
 * ## 漏掉的部分必须能被上层看见
 *
 * 前两层靠「倒排表太长就整段丢弃」来压住计算量，于是**同一批档案可能
 * 一个候选都不产生**——那不是「它们不重复」，而是「它们没被比较过」。
 * 两者的区别对用户是决定性的（前者可以放心，后者不能），所以这里把
 * [CandidateResult.skippedRecords] 一起交出去，由界面如实说明。
 */
object CandidateSetBuilder {

    /**
     * 倒排表长度上限：超过它的二元组直接丢弃。
     *
     * 「植」「树」「（」这类字几乎出现在每条记录里，**不提供任何区分度**，
     * 却会把 posting list 拉长到几百 —— 而配对数是长度的平方，
     * 它们才是 O(N²) 的真正来源。
     *
     * ## 已知代价（改这个值之前先读）
     *
     * 丢弃是无条件的：几百条**完全同名**的档案共享的全部二元组都会被丢掉，
     * 于是它们之间一个候选都不产生。这是在「让界面可用」与
     * 「覆盖病态数据」之间做的取舍 —— 真出现几百条同名档案时，
     * 用户需要的是搜索 + 批量处理，而不是几万张两两比对的提示卡。
     * 阈值之内（≤300）行为完全正常，见 `CandidateSetBuilderTest`。
     */
    const val MAX_POSTING_LIST = 300

    /**
     * 候选对数硬上限。
     *
     * 20 万对之后即便再算下去，用户也看不完 —— 与其卡住界面，
     * 不如明确告诉他「这次只扫了一部分」。
     */
    const val MAX_PAIRS = 200_000

    /**
     * 长度窗口：名称长度差超过它就不算 `nameSimilarity`。
     *
     * 编辑距离对长度差极敏感（差 5 个字的名字相似度天然接近 0），
     * 算了也是白算。真正接近的名字长度差不会超过 3。
     */
    const val LENGTH_WINDOW = 3

    /**
     * 两个上限做成参数而不是写死，是为了**让它们可测**：
     * 要在真实阈值下触发截断，得先造 1500 条档案（每个倒排表上限 300、
     * 每表最多 4.5 万对候选）—— 那样的「单测」会把测试 JVM 打爆，
     * 于是这个分支永远不会被测到。可注入之后，
     * 20 条记录 + `maxPairs = 50` 就能精确验证。
     */
    fun build(
        records: List<RecordSnapshot>,
        maxPostingList: Int = MAX_POSTING_LIST,
        maxPairs: Int = MAX_PAIRS,
    ): CandidateResult {
        if (records.size < 2) {
            return CandidateResult(emptyList(), 0, records.size, partial = false)
        }

        val normalized = records.map { TextNormalizer.normalizeChinese(it.name) }

        // ---------------- 二元组倒排 ----------------
        // 键是 bigram，值是记录下标。用下标而不是 id，避免每次查记录
        val gramIndex = HashMap<String, MutableList<Int>>()
        for (i in records.indices) {
            for (gram in bigrams(normalized[i])) {
                gramIndex.getOrPut(gram) { mutableListOf() }.add(i)
            }
        }

        // ---------------- 拉丁属名分桶 ----------------
        val genusIndex = HashMap<String, MutableList<Int>>()
        for (i in records.indices) {
            val genus = latinGenusOf(records[i].latinName) ?: continue
            genusIndex.getOrPut(genus) { mutableListOf() }.add(i)
        }

        // 被整段丢弃的倒排表让一部分档案**根本没参与比对**。
        // 这里同时记住「进过保留列表的」和「只出现在被丢弃列表里的」，
        // 差集就是这一轮真正漏掉的档案（见 CandidateResult.skippedRecords）。
        val enumerated = HashSet<Int>()
        val onlyDropped = HashSet<Int>()

        // 累积每个候选对的共享二元组数。键把两个 id 打包成一个 Long ——
        // 用 Pair 作键会让这个百万级的 map 撑爆内存
        val shared = HashMap<Long, Int>()
        val genusSame = HashSet<Long>()

        for ((_, list) in gramIndex) {
            if (list.size > maxPostingList) {
                onlyDropped.addAll(list)
                continue
            }
            enumerated.addAll(list)
            for (x in list.indices) {
                for (y in x + 1 until list.size) {
                    val key = pack(records[list[x]].id, records[list[y]].id) ?: continue
                    shared.merge(key, 1, Int::plus)
                }
            }
        }

        for ((_, list) in genusIndex) {
            if (list.size > maxPostingList) {
                onlyDropped.addAll(list)
                continue
            }
            enumerated.addAll(list)
            for (x in list.indices) {
                for (y in x + 1 until list.size) {
                    val key = pack(records[list[x]].id, records[list[y]].id) ?: continue
                    genusSame.add(key)
                }
            }
        }

        onlyDropped.removeAll(enumerated)

        // ---------------- 判定哪些对真的是候选 ----------------
        val byId = records.associateBy { it.id }
        val pairs = ArrayList<CandidatePair>(shared.size + genusSame.size)

        // 场景一：共享 ≥ 2 个二元组，或共享 ≥ 1 且名字确实像
        for ((key, count) in shared) {
            val (aId, bId) = unpack(key) ?: continue
            val a = byId[aId] ?: continue
            val b = byId[bId] ?: continue
            val sameGenus = key in genusSame

            val candidate = when {
                count >= 2 -> true
                sameGenus -> true
                else -> {
                    // count == 1：可能只是共享了一个「树」字，得看真实相似度。
                    // 先卡长度窗口，避免为明显不像的名字对白算编辑距离
                    val nameA = TextNormalizer.normalizeChinese(a.name)
                    val nameB = TextNormalizer.normalizeChinese(b.name)
                    if (kotlin.math.abs(nameA.length - nameB.length) > LENGTH_WINDOW) {
                        false
                    } else {
                        Similarity.nameSimilarity(a.name, b.name) >= DuplicateMatcher.NAME_FLOOR
                    }
                }
            }
            if (candidate) pairs += CandidatePair(aId, bId, count, sameGenus)
        }

        // 场景二：名称完全不像但同属 —— shared 里根本没有这些对
        for (key in genusSame) {
            if (shared.containsKey(key)) continue
            val (aId, bId) = unpack(key) ?: continue
            pairs += CandidatePair(aId, bId, sharedGrams = 0, sameLatinGenus = true)
        }

        val total = pairs.size
        val partial = total > maxPairs
        val bounded = if (partial) {
            // 按证据强度取舍：共享二元组多的、以及同属的优先留下
            pairs.sortedWith(
                compareByDescending<CandidatePair> { it.sameLatinGenus }
                    .thenByDescending { it.sharedGrams },
            ).take(maxPairs)
        } else {
            pairs
        }

        return CandidateResult(
            pairs = bounded,
            totalBeforeCap = total,
            totalRecords = records.size,
            partial = partial,
            skippedRecords = onlyDropped.size,
        )
    }

    // ---------------------------------------------------------------- 工具

    /** 中文名按字符二元组切分；单字名退化成它自己（否则索引里没有它） */
    private fun bigrams(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length == 1) return listOf(text)
        return (0 until text.length - 1).map { text.substring(it, it + 2) }
    }

    /**
     * 拉丁学名的属名 = 归一化后的第一个词。
     *
     * 不去查植物学词典：双名法的第一段就是属名，这是命名法规本身规定的，
     * 不需要额外知识。
     */
    private fun latinGenusOf(latinName: String?): String? =
        TextNormalizer.normalizeLatin(latinName.orEmpty())
            .substringBefore(' ')
            .takeIf { it.isNotBlank() }

    /**
     * 把两个 id 打包成一个 Long（大的在前，保证方向无关）。
     *
     * 要求 id 小于 2^31 —— 自增主键在可预见的未来都远小于它。
     * 超出时返回 null（放弃该对，而不是把两个 id 混成同一个键：
     * 那会让 A、C 被当成 B、D，产生一批**根本不存在的候选**）。
     */
    private fun pack(a: Long, b: Long): Long? {
        if (a < 0 || b < 0) return null
        if (a > Int.MAX_VALUE || b > Int.MAX_VALUE) return null
        val hi = maxOf(a, b).toInt().toLong() and 0xFFFFFFFFL
        val lo = minOf(a, b).toInt().toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }

    private fun unpack(key: Long): Pair<Long, Long>? {
        val hi = (key ushr 32) and 0xFFFFFFFFL
        val lo = key and 0xFFFFFFFFL
        if (hi == 0L || lo == 0L) return null
        return lo to hi   // 小 id 在前
    }
}
