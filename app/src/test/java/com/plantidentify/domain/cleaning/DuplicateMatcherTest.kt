package com.plantidentify.domain.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 六级判定的单测。
 *
 * 每一级至少一条用例，另外重点钉三件事：
 * 1. **命中即停** —— 一对档案只能产出一条结论，且是证据最强的那条
 * 2. **needsAi 的门槛** —— 正常情况下不该调 AI（成本与延迟都在这里）
 * 3. **空值不算证据** —— 「两边都没填」不能被当成「两边一致」
 */
class DuplicateMatcherTest {

    private fun rec(
        id: Long,
        name: String,
        latin: String? = null,
        family: String? = null,
        genus: String? = null,
        description: String? = null,
    ) = RecordSnapshot(
        id = id,
        name = name,
        latinName = latin,
        family = family,
        genus = genus,
        description = description,
    )

    @Test
    fun `一级 - 拉丁学名完全相同`() {
        // 带作者引证的学名与干净学名必须判为同一条（学名归一化的价值所在）
        val a = rec(1, "二球悬铃木", latin = "Platanus × acerifolia (Aiton) Willd.")
        val b = rec(2, "英国梧桐", latin = "Platanus acerifolia")

        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(1, hit.level)
        assertFalse("本地直接可判，不该花 AI 的钱", hit.needsAi)
        assertTrue(hit.isConclusive)
    }

    @Test
    fun `一级要求学名非空 - 两边都没填不算相同`() {
        // 这条最容易写错：两个 null 归一化后都是空串，`==` 成立。
        // 但那是「都不知道」而不是「是同一株」，据此判同种会误报一片
        val a = rec(1, "某种植物")
        val b = rec(2, "另一种植物")
        assertNull(DuplicateMatcher.match(a, b))
    }

    @Test
    fun `二级 - 中文名科属三者全同`() {
        val a = rec(1, "紫薇", latin = null, family = "千屈菜科", genus = "紫薇属")
        val b = rec(2, "紫薇", latin = null, family = "千屈菜科", genus = "紫薇属")

        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(2, hit.level)
        assertFalse(hit.needsAi)
    }

    @Test
    fun `二级要求科属非空 - 只靠名字相同不足以定性`() {
        // 名字相同、科属都没填 → 落到级 3（名字相似 1.0）
        val a = rec(1, "榕树")
        val b = rec(2, "榕树")

        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(3, hit.level)
    }

    @Test
    fun `三级 - 一字之差进灰区需要 AI`() {
        val a = rec(1, "悬铃木")
        val b = rec(2, "悬铃树")

        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(3, hit.level)
        assertTrue("0.55–0.90 是灰区，必须交给 AI 复核", hit.needsAi)
    }

    @Test
    fun `三级 - 名字完全相同但科属冲突时不视为本地强证据`() {
        // 场景真实存在：两株都识别成「榕树」，但模型给的科不同。
        // 若只按「相似度 1.0 → 强证据」，用户会被引导去合并两株不同的植物
        val a = rec(1, "榕树", family = "桑科", genus = "榕属")
        val b = rec(2, "榕树", family = "木兰科", genus = "榕属")

        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(3, hit.level)
        assertTrue("科属矛盾时必须让 AI 看一眼", hit.needsAi)
    }

    @Test
    fun `三级 - 名字高相似且科属一致时不再调 AI`() {
        // 名字相似度 1.0 且科属都一致，但有一边科属为空导致没进级 2 —— 本地可判
        val a = rec(1, "紫薇", family = "千屈菜科", genus = "紫薇属")
        val b = rec(2, "紫薇")

        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(3, hit.level)
        assertFalse(hit.needsAi)
    }

    @Test
    fun `四级 - 学名一字之差且同属`() {
        val a = rec(1, "法国梧桐", latin = "Platanus acerifolia", family = "悬铃木科", genus = "悬铃木属")
        val b = rec(2, "北美悬铃木", latin = "Platanus acerifolius", family = "悬铃木科", genus = "悬铃木属")

        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(4, hit.level)
        assertTrue(hit.similarity >= DuplicateMatcher.LATIN_FLOOR)
        assertTrue(hit.needsAi)
    }

    @Test
    fun `四级 - 属名不同时学名相似度封顶 不会命中`() {
        // 「不同属但学名长得像」常是拼写变体或同名异物，封顶后应当落选，
        // 而不是「证据强度调低后仍然放行」
        val a = rec(1, "甲", latin = "Platanus acerifolia", family = "悬铃木科", genus = "悬铃木属")
        val b = rec(2, "乙", latin = "Platanus acerifolius", family = "蔷薇科", genus = "梨属")

        assertNull(DuplicateMatcher.match(a, b))
    }

    @Test
    fun `五级 - 科属相同但名称完全不同`() {
        val a = rec(1, "榕树", family = "桑科", genus = "榕属")
        val b = rec(2, "菩提树", family = "桑科", genus = "榕属")

        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(5, hit.level)
        assertTrue("同属几十个种很常见，本地只能说沾亲", hit.needsAi)
    }

    @Test
    fun `六级 - 简介内容高度相似`() {
        // 名字必须挑成**毫无共同点**的：六级是最后一级，
        // 任何更靠前的判据命中都会把它挡住。
        // （初版这里用了「甲植物 / 乙植物」，只差一个字 → 先命中级 3，
        //   级 6 的用例其实什么都没验证到）
        val text = "常绿乔木，高可达二十米，树皮灰色平滑，叶互生革质，喜温暖湿润气候，适合作行道树。"
        val a = rec(1, "甲木", description = text)
        val b = rec(2, "乙草", description = text)

        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(6, hit.level)
        assertTrue(hit.needsAi)
    }

    @Test
    fun `六级要求两边简介都非空`() {
        // 一边为 null 时 descriptionSimilarity 走「仅一空 = 0.0」的约定，
        // 所以这里其实不会命中 —— 但断言写下来，防止哪天约定被改
        val a = rec(1, "甲木", description = "常绿乔木")
        val b = rec(2, "乙草")
        assertNull(DuplicateMatcher.match(a, b))
    }

    @Test
    fun `命中即停 - 一对档案只出一条结论且取最强证据`() {
        val text = "常绿乔木，高可达二十米，树皮灰色平滑，叶互生革质。"
        val a = rec(1, "同一株", latin = "Platanus acerifolia", family = "悬铃木科", genus = "悬铃木属", description = text)
        val b = rec(2, "同一株", latin = "Platanus acerifolia", family = "悬铃木科", genus = "悬铃木属", description = text)

        // 这一对同时满足 1/2/3/4/6 三级，返回的必须是级 1
        val hit = DuplicateMatcher.match(a, b)!!
        assertEquals(1, hit.level)
    }

    @Test
    fun `自己与自己不产生候选`() {
        val a = rec(1, "紫薇")
        assertNull(DuplicateMatcher.match(a, a))
    }

    @Test
    fun `候选对的稳定键与方向无关`() {
        // 候选集里 (A,B) 与 (B,A) 必须归到同一条问题，
        // 否则「忽略」只对其中一个方向生效，另一个方向下次还会冒出来
        val a = rec(7, "紫薇", family = "千屈菜科", genus = "紫薇属")
        val b = rec(12, "紫薇", family = "千屈菜科", genus = "紫薇属")
        val real = DuplicateMatcher.match(a, b)!!
        assertEquals("7:12", real.pairKey())
        assertEquals(real.pairKey(), real.copy(aId = 12, bId = 7).pairKey())
    }
}
