package com.plantidentify.domain.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相似度的单测。
 *
 * 重点在**边界语义**：空值、单字名、一字之差、词序颠倒。
 * 这些正是候选集生成会大量遇到的输入，也是「阈值调不动」的常见根因 ——
 * 如果边界的返回值反直觉，阈值再调也调不出稳定行为。
 */
class SimilarityTest {

    @Test
    fun `编辑距离的经典用例`() {
        assertEquals(3, Similarity.levenshtein("kitten", "sitting"))
        assertEquals(1, Similarity.levenshtein("悬铃木", "悬铃树"))
        assertEquals(0, Similarity.levenshtein("紫薇", "紫薇"))
    }

    @Test
    fun `两边都空算相同 一边空算不同`() {
        // 这个约定必须全项目一致：都空 = 没有分歧；一空 = 无从比较。
        // 反过来写会让候选集凭空多出（或少掉）一批
        assertEquals(1.0, Similarity.editSimilarity("", ""), 1e-9)
        assertEquals(0.0, Similarity.editSimilarity("", "紫薇"), 1e-9)
        assertEquals(0.0, Similarity.editSimilarity("紫薇", ""), 1e-9)
    }

    @Test
    fun `一字之差要有明显但不致命的分差`() {
        val similar = Similarity.nameSimilarity("悬铃木", "悬铃树")
        // 3 字名改 1 字：编辑距离单独看是 0.67，加上 2-gram 加权后更高。
        // 它必须够高（值得提示用户）又不能到 1.0（不能当成同一名字）
        assertTrue("实得 $similar", similar > 0.5)
        assertTrue("实得 $similar", similar < 1.0)
    }

    @Test
    fun `完全相同为 1`() {
        assertEquals(1.0, Similarity.nameSimilarity("紫薇", "紫薇"), 1e-9)
    }

    @Test
    fun `词序颠倒不该得满分`() {
        // Jaccard 对词序不敏感，编辑距离不 —— 两者加权后
        // 「木铃悬」应当明显低于 1.0，否则候选集会淹没在噪声里
        val score = Similarity.nameSimilarity("悬铃木", "木铃悬")
        assertTrue("实得 $score", score < 1.0)
    }

    @Test
    fun `完全不同的名字分数很低`() {
        val score = Similarity.nameSimilarity("紫薇", "银杏")
        assertTrue("实得 $score", score < 0.3)
    }

    @Test
    fun `拉丁学名归一化后相同则满分`() {
        // 作者引证与大小写不该影响判定 —— 这正是 normalizeLatin 的意义
        val score = Similarity.latinSimilarity(
            "Platanus acerifolia (Aiton) Willd.",
            "platanus acerifolia",
        )
        assertEquals(1.0, score, 1e-9)
    }

    @Test
    fun `同属不同种要有区分度`() {
        val sameGenus = Similarity.latinSimilarity(
            "Acer palmatum",
            "Acer buergerianum",
        )
        // 属相同、种不同：不能太高（否则会把同属植物混为一谈）
        assertTrue("实得 $sameGenus", sameGenus < 0.95)
    }

    @Test
    fun `描述相似度两边都空算相同`() {
        assertEquals(1.0, Similarity.descriptionSimilarity(null, null), 1e-9)
        assertEquals(1.0, Similarity.descriptionSimilarity("", ""), 1e-9)
        assertEquals(0.0, Similarity.descriptionSimilarity("落叶乔木", null), 1e-9)
    }

    @Test
    fun `描述相似度对套话不敏感对实词敏感`() {
        val a = "落叶乔木，叶掌状五裂，果球状，常用于行道树与庭院绿化。"
        val b = "落叶乔木，叶掌状,   五裂，果球状，常用于行道树与庭院绿化。"
        val c = "常绿灌木，叶披针形，花紫色，多作花篱。"

        val nearDuplicate = Similarity.descriptionSimilarity(a, b)
        val different = Similarity.descriptionSimilarity(a, c)
        // 标点与空白的差异不该拉低相似度（归一化在做这件事）；
        // 内容不同则必须明显更低
        assertTrue("实得 $nearDuplicate", nearDuplicate > 0.9)
        assertTrue("实得 $different", different < nearDuplicate)
    }

    @Test
    fun `短中文名的相似度必须跨过候选门槛`() {
        // 方案自己举的例子。原先统一 0.5/0.5 权重时实得 0.548，
        // 比 §7.4 级 3 的门槛 0.55 差 0.002 —— 方案想解决的问题依然被漏掉。
        // 这条断言把「门槛必须可达」钉住：光看区间（> 0.5）发现不了它
        val score = Similarity.nameSimilarity("悬铃木", "悬铃树")
        assertTrue("实得 $score，应 >= 0.55", score >= 0.55)
    }

    @Test
    fun `拉丁学名一字之差必须能达到级 4 的门槛`() {
        // 拼写变体是级 4 存在的唯一理由。按**词**算 Jaccard 时
        // 「acerifolia / acerifolius」只有 1/3，加权后 0.52，
        // 离 0.85 差得远 —— 改用字符二元组后能过线。
        // 这是「阈值看起来合理但永远达不到」的典型：不拿真实例子算一遍
        // 是不会发现的
        val score = Similarity.latinSimilarity("Platanus acerifolia", "Platanus acerifolius")
        assertTrue("实得 $score，应 >= 0.85", score >= 0.85)
    }

    @Test
    fun `jaccard 空集语义`() {
        assertEquals(1.0, Similarity.jaccard(emptySet(), emptySet()), 1e-9)
        assertEquals(0.0, Similarity.jaccard(setOf("a"), emptySet()), 1e-9)
        // 交 1（b）、并 3（a,b,c）→ 1/3。写 0.5 是把并集当成了 2 —— 这类
        // 「算错分母」的错误在阈值调参时会表现成「怎么调都不对」
        assertEquals(1.0 / 3.0, Similarity.jaccard(setOf("a", "b"), setOf("b", "c")), 1e-9)
    }
}
