package com.plantidentify.domain.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选集生成的单测。
 *
 * 这个类的作用是**在保证召回的前提下砍掉比较量**，所以用例分两类：
 * - 「该找到的必须找到」：共享二元组、同属不同名、一字之差
 * - 「该砍掉的必须砍掉」：只有高频字相同、长度差很大的名字
 *
 * 另外钉一条**已知的取舍**（见最后几个用例）：为了不让 O(N²) 拖垮界面，
 * 超高频的二元组会被整段丢弃 —— 代价是同名档案超过阈值时不再两两比较。
 *
 * 而这个「代价」必须能被上层看见：`skippedRecords > 0` 的意思是
 * 「有这么多株压根没被比较过」，界面要说出来，不能让它长得像
 * 「查过了，一条都不重复」。2026-09-23 的 10000 株压测就是栽在这里。
 */
class CandidateSetBuilderTest {

    private fun rec(id: Long, name: String, latin: String? = null) =
        RecordSnapshot(id = id, name = name, latinName = latin)

    private fun pairsOf(vararg records: RecordSnapshot): List<Pair<Long, Long>> =
        CandidateSetBuilder.build(records.toList()).pairs.map { it.aId to it.bId }

    @Test
    fun `少于两条记录时没有候选`() {
        assertTrue(CandidateSetBuilder.build(emptyList()).pairs.isEmpty())
        assertTrue(CandidateSetBuilder.build(listOf(rec(1, "紫薇"))).pairs.isEmpty())
    }

    @Test
    fun `共享两个二元组即成为候选`() {
        // 「悬铃木」的二元组是「悬铃」「铃木」，「悬铃木科」含「悬铃」，
        // 共享 1 个；「一球悬铃木」与「二球悬铃木」共享「悬铃」「铃木」两个
        val pairs = pairsOf(
            rec(1, "一球悬铃木"),
            rec(2, "二球悬铃木"),
            rec(3, "完全不搭的植物"),
        )
        assertTrue(pairs.contains(1L to 2L))
        assertTrue(pairs.none { it.first == 3L || it.second == 3L })
    }

    @Test
    fun `只共享一个二元组时靠相似度兜底`() {
        // 「悬铃木」/「悬铃树」共享「悬铃」，相似度 0.58 ≥ 0.55 → 保留
        val pairs = pairsOf(rec(1, "悬铃木"), rec(2, "悬铃树"))
        assertTrue(pairs.contains(1L to 2L))
    }

    @Test
    fun `只共享一个二元组且名字不像时被砍掉`() {
        // 「紫薇花」与「紫荆花」共享「花」？—— 不共享。构造一对共享一个字
        // 但整体不像的：「榕树」与「树蕨」共享？也不共享。
        // 用「常绿乔木」与「落叶乔木」：共享「乔木」，相似度 0.5 < 0.55
        val pairs = pairsOf(rec(1, "常绿乔木甲"), rec(2, "落叶乔木乙"))
        assertTrue("相似度不够，不该产生候选", pairs.isEmpty())
    }

    @Test
    fun `名称完全不像但同属也要成为候选`() {
        // 「榕树」与「菩提树」二元组毫无交集，靠名称索引永远找不到它们，
        // 必须靠拉丁属名分桶 —— 这一条是召回的关键
        val result = CandidateSetBuilder.build(
            listOf(
                rec(1, "榕树", latin = "Ficus microcarpa"),
                rec(2, "菩提树", latin = "Ficus religiosa"),
                rec(3, "紫薇", latin = "Lagerstroemia indica"),
            ),
        )
        val pair = result.pairs.single()
        assertEquals(1L, pair.aId)
        assertEquals(2L, pair.bId)
        assertTrue("同属必须有标记", pair.sameLatinGenus)
        assertEquals("共享二元组为 0，只能靠属名", 0, pair.sharedGrams)
    }

    @Test
    fun `带作者引证的学名也要能分出属名`() {
        val pairs = pairsOf(
            rec(1, "英国梧桐", latin = "Platanus × acerifolia (Aiton) Willd."),
            rec(2, "美国梧桐", latin = "Platanus occidentalis L."),
        )
        assertTrue(pairs.contains(1L to 2L))
    }

    @Test
    fun `长度差超过窗口的名字不做相似度计算`() {
        // 「紫薇」与「紫薇属植物的一整个长名字」共享「紫薇」，
        // 但长度差远超窗口 —— 编辑距离算出来必然很低，算了是白算
        val pairs = pairsOf(
            rec(1, "紫薇"),
            rec(2, "紫薇的栽培品种与园艺变种总览"),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `候选对的方向无关且 id 小的在前`() {
        val result = CandidateSetBuilder.build(
            listOf(rec(12, "悬铃木"), rec(7, "悬铃树")),
        )
        assertEquals(7L to 12L, result.pairs.single().let { it.aId to it.bId })
    }

    @Test
    fun `结果里不重复出现同一对`() {
        // 两个名字可能共享多个二元组，逐二元组累积时同一对会被遇到多次 ——
        // 必须归并成一条，否则候选数虚高、界面出现重复条目
        val result = CandidateSetBuilder.build(
            listOf(rec(1, "一球悬铃木"), rec(2, "二球悬铃木")),
        )
        assertEquals(1, result.pairs.size)
    }

    @Test
    fun `同属且名字也像时不会产生两条`() {
        val result = CandidateSetBuilder.build(
            listOf(
                rec(1, "悬铃木", latin = "Platanus acerifolia"),
                rec(2, "悬铃树", latin = "Platanus acerifolius"),
            ),
        )
        assertEquals(1, result.pairs.size)
        assertTrue(result.pairs.single().sameLatinGenus)
    }

    @Test
    fun `单字名也能进索引`() {
        val pairs = pairsOf(rec(1, "松"), rec(2, "松"))
        assertTrue(pairs.contains(1L to 2L))
    }

    @Test
    fun `全库同名且超出降噪阈值时不再两两比较 - 已知取舍`() {
        // 这是「砍掉 O(N²)」的代价，写成用例是为了让它**可见**：
        // 301 条同名档案共享同一个二元组，该二元组的倒排表超过阈值被丢弃，
        // 于是它们之间不产生任何候选。
        //
        // 这是有意为之：真实数据里不会出现几百条完全同名的档案；
        // 真出现了，用户需要的是「搜索 + 批量处理」，而不是三百条
        // 两两比对的提示卡片。取舍写在这里，改阈值前先读一遍。
        //
        // 但**「没有候选」绝不能等于「没有问题」**：这些株一对比都没做过，
        // 所以 skippedRecords 必须如实报出来（2026-09-23 压测里，10000 株
        // 时 12 个物种每个约 833 条的倒排表全被丢弃，候选集为 0，
        // 而界面显示「100 分 · 数据很干净」）。
        val records = (1L..301L).map { rec(it, "测试植物") }
        val result = CandidateSetBuilder.build(records)

        assertTrue("同名超阈值后不再产生候选", result.pairs.isEmpty())
        assertEquals(301, result.totalRecords)
        assertFalse("不是被候选数上限截断的", result.partial)
        assertEquals("301 株全都只出现在被丢弃的倒排表里", 301, result.skippedRecords)
    }

    @Test
    fun `正常规模下没有档案被漏掉`() {
        // 反面的钉子：没触发丢弃时必须报 0，否则界面会对每一份干净的库
        // 都挂一句「有 N 株没能参与比对」，提示就失去意义了
        val result = CandidateSetBuilder.build(
            listOf(rec(1, "悬铃木"), rec(2, "悬铃树"), rec(3, "紫薇")),
        )
        assertEquals(0, result.skippedRecords)
    }

    @Test
    fun `一部分倒排表被丢弃时只报真正漏掉的那些株`() {
        // 4 条同名（二元组超频被丢）+ 2 条共享稀有二元组的名字。
        // 「悬铃木甲」「悬铃木乙」之间有候选，所以它们**没有**被漏掉；
        // 被漏掉的只有那 4 条同名档案。阈值降到 3 让同名那一组触发丢弃。
        val records = listOf(
            rec(1, "测试植物"), rec(2, "测试植物"), rec(3, "测试植物"), rec(4, "测试植物"),
            rec(5, "悬铃木甲"), rec(6, "悬铃木乙"),
        )
        val result = CandidateSetBuilder.build(records, maxPostingList = 3)

        assertEquals(1, result.pairs.size)
        assertEquals(5L to 6L, result.pairs.single().let { it.aId to it.bId })
        assertEquals("只有那 4 条同名档案没参与比对", 4, result.skippedRecords)
    }

    @Test
    fun `阈值之内仍然正常产生候选`() {
        val records = (1L..100L).map { rec(it, "测试植物") }
        val result = CandidateSetBuilder.build(records)
        // 100 条两两组合 = 4950 对
        assertEquals(100 * 99 / 2, result.pairs.size)
        assertFalse(result.partial)
    }

    @Test
    fun `超过硬上限时截断并标记为部分扫描`() {
        // 上限做成参数就是为了这条用例能存在：真实阈值下要触发截断
        // 得造 1500 条档案（单次尝试会把测试 JVM 打爆，这个分支就永远测不到）。
        // 20 条同名档案 = 190 对，配 maxPairs = 50 就能精确验证截断。
        val records = (1L..20L).map { rec(it, "测试植物") }
        val result = CandidateSetBuilder.build(records, maxPairs = 50)

        assertTrue(result.partial)
        assertEquals(50, result.pairs.size)
        assertEquals(190, result.totalBeforeCap)
    }

    @Test
    fun `截断时优先保留证据更强的候选`() {
        // 50 条同属但名字毫不相干的 + 一条通过共享二元组进来的，
        // 上限只留 1 个 —— 留下的必须是同属的那对（属名是更硬的证据）
        val records = (1L..51L).map { rec(it, "植物${it}号", latin = "Ficus species$it") } +
            listOf(rec(100, "悬铃木甲"), rec(101, "悬铃木乙"))
        val result = CandidateSetBuilder.build(records, maxPairs = 1)

        assertTrue(result.partial)
        val kept = result.pairs.single()
        assertTrue("应优先保留同属的候选", kept.sameLatinGenus)
    }

    @Test
    fun `降噪阈值可注入 - 小规模也能验证超频字被丢弃`() {
        // 5 条同名档案，把阈值降到 4 → 共享的二元组全部超频被丢弃 → 无候选
        val records = (1L..5L).map { rec(it, "测试植物") }
        val dropped = CandidateSetBuilder.build(records, maxPostingList = 4)
        assertTrue(dropped.pairs.isEmpty())
        assertEquals("5 株全被漏掉，界面必须说得出这个数字", 5, dropped.skippedRecords)
        // 阈值放到 5 就正常了，此时一个人都没漏
        val kept = CandidateSetBuilder.build(records, maxPostingList = 5)
        assertEquals(10, kept.pairs.size)
        assertEquals(0, kept.skippedRecords)
    }
}
