package com.plantidentify.domain.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归一化的单测。
 *
 * 这些断言的价值不在「跑通」，而在**锁定语义**：
 * 归一化是全部相似度算法的前置，它改动一点，候选集生成与六级判定
 * 的阈值全都要重估。所以每个用例都写清「为什么要这样」，
 * 下次有人想改时能看见代价。
 */
class TextNormalizerTest {

    @Test
    fun `全角空格与全角字母都要折成半角`() {
        // U+3000 看起来与普通空格一样，但 trim() 并不认它 ——
        // 从网页复制来的植物名经常带这个字符
        assertEquals("紫薇", TextNormalizer.normalizeBase("\u3000紫薇\u3000"))
        assertEquals("ABC", TextNormalizer.normalizeBase("ＡＢＣ"))
    }

    @Test
    fun `连续空白折叠成一个空格`() {
        assertEquals("a b c", TextNormalizer.normalizeBase("a   b\t\nc"))
    }

    @Test
    fun `括号内容是置信度标注而不是名字的一部分`() {
        // 留着「疑似」会让它与「悬铃木」的相似度白白降低
        assertEquals("悬铃木", TextNormalizer.normalizeChinese("悬铃木（疑似）"))
        assertEquals("悬铃木", TextNormalizer.normalizeChinese("悬铃木(二球)"))
    }

    @Test
    fun `sp 后缀被去掉`() {
        assertEquals("悬铃木", TextNormalizer.normalizeChinese("悬铃木 sp."))
        assertEquals("悬铃木", TextNormalizer.normalizeChinese("悬铃木 sp"))
    }

    @Test
    fun `属字不会被去掉`() {
        // 「紫薇属」与「紫薇」一个是属名、一个是种名，把属与种判成同一种
        // 是最典型的一类误合并 —— 所以这里**刻意不**把「属」当噪声后缀
        assertEquals("紫薇属", TextNormalizer.normalizeChinese("紫薇属"))
        assertTrue(Similarity.nameSimilarity("紫薇属", "紫薇") < 0.99)
    }

    @Test
    fun `作者引证被剥离`() {
        assertEquals(
            "Platanus acerifolia",
            TextNormalizer.stripAuthorCitation("Platanus acerifolia (Aiton) Willd."),
        )
    }

    @Test
    fun `种下等级标记后的词必须保留`() {
        // var. atropurpureum 是有效学名的一部分，丢掉会让两个不同的
        // 栽培变种被判成同一物种
        assertEquals(
            "Acer palmatum var. atropurpureum",
            TextNormalizer.stripAuthorCitation("Acer palmatum var. atropurpureum Thunb."),
        )
    }

    @Test
    fun `杂交符的不同写法归一后一致`() {
        // 三种写法在真实数据里都出现过，不统一就会被判成不同物种
        val withTimes = TextNormalizer.normalizeLatin("Platanus × acerifolia")
        val withX = TextNormalizer.normalizeLatin("Platanus x acerifolia")
        assertEquals(withTimes, withX)
        assertTrue(withTimes.startsWith("platanus"))
    }

    @Test
    fun `token 切分同时产出单字与二元组`() {
        val tokens = TextNormalizer.tokens("悬铃木")
        // 单字名（「梅」「兰」）靠 1-gram 才有非空 token 集，
        // 否则它们与任何名字的相似度都会是 0
        assertTrue("悬" in tokens)
        assertTrue("悬铃" in tokens)
        assertTrue("铃木" in tokens)
    }

    @Test
    fun `二元组不跨标点生成`() {
        val tokens = TextNormalizer.tokens("悬铃,木")
        // 「铃,木」这种跨标点的组合无意义，不该出现
        assertTrue(tokens.none { it.contains(",") })
    }

    @Test
    fun `杂交符不能挤掉种加词`() {
        // 杂交符是符号而不是词。不摘掉的话，按空格切出来的第二个词
        // 就是「×」，take(2) 得到「属名 + ×」，种加词被挤掉 ——
        // 结果「Platanus × acerifolia」与「Platanus × hispanica」
        // 归一化成同一个字符串，在六级判定里直接命中「学名完全相同」。
        // 栽培植物带 × 的学名很常见，所以这不是理论问题。
        assertEquals(
            "platanus acerifolia",
            TextNormalizer.normalizeLatin("Platanus × acerifolia (Aiton) Willd."),
        )
        assertNotEquals(
            TextNormalizer.normalizeLatin("Platanus × acerifolia"),
            TextNormalizer.normalizeLatin("Platanus × hispanica"),
        )
    }

    @Test
    fun `杂交符的各种写法仍然统一`() {
        // 与上面一起构成完整契约：符号本身要抹掉，但不同的写法
        // 仍然归一到同一个学名
        val times = TextNormalizer.normalizeLatin("Platanus × acerifolia")
        assertEquals(times, TextNormalizer.normalizeLatin("Platanus x acerifolia"))
        assertEquals(times, TextNormalizer.normalizeLatin("Platanus acerifolia"))
    }

    @Test
    fun `种下等级仍然保留`() {
        // 摘杂交符不能把「第三词是 var.」这条规则一起弄坏：
        // 丢掉种下等级会让两个不同的栽培变种被判成同一物种
        assertEquals(
            "acer palmatum var. atropurpureum",
            TextNormalizer.normalizeLatin("Acer palmatum var. atropurpureum"),
        )
    }

    @Test
    fun `拉丁名按词切分并小写`() {
        val tokens = TextNormalizer.tokens("Platanus Acerifolia")
        assertTrue("platanus" in tokens)
        assertTrue("acerifolia" in tokens)
    }
}
