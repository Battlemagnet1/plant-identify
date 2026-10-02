package com.plantidentify.domain.landscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 景观特征提取的单测（v1.0.2 Phase 3）。
 *
 * 这些规则是**启发式**的（从自由文本里认乔木/灌木、认花期、认颜色），
 * 最容易在「真实用户会怎么写」上翻车 —— 所以用例都贴着实际数据的写法，
 * 而不是自己编几个干净的字符串。
 */
class PlantTraitsTest {

    // ---------------------------------------------------------------- 层次

    @Test
    fun `类别字段里的乔木灌木草本都能认出来`() {
        assertEquals(PlantLayer.TREE, PlantTraits.layerOf("乔木"))
        assertEquals(PlantLayer.TREE, PlantTraits.layerOf("落叶乔木"))
        assertEquals(PlantLayer.SHRUB, PlantTraits.layerOf("观花灌木"))
        assertEquals(PlantLayer.HERB, PlantTraits.layerOf("多年生草本"))
        assertEquals(PlantLayer.GROUND_COVER, PlantTraits.layerOf("藤本地被"))
    }

    @Test
    fun `地被优先于草本`() {
        // 「藤本地被」两个词都在，更具体的「地被」胜出 ——
        // 顺序反了会把这株算成草本，地被层就永远是 0
        assertEquals(PlantLayer.GROUND_COVER, PlantTraits.layerOf("藤本地被"))
    }

    @Test
    fun `类别没写时用生长习性兜底`() {
        assertEquals(
            PlantLayer.TREE,
            PlantTraits.layerOf(category = null, growthHabits = "常绿乔木，树冠浓郁"),
        )
    }

    @Test
    fun `全都判不出来时返回未分类而不是硬猜`() {
        // 这一条是刻意的：把判不出的算进任何一层都会让比例失真，
        // 而「未分类」摆在界面上会促使去补数据
        assertEquals(PlantLayer.UNKNOWN, PlantTraits.layerOf(null))
        assertEquals(PlantLayer.UNKNOWN, PlantTraits.layerOf("", "", "叶片椭圆形"))
    }

    // ---------------------------------------------------------------- 季相

    @Test
    fun `月份区间会覆盖到跨越的季节`() {
        // 5-9 月横跨春夏秋，只记一个季节会让季相图失真
        assertEquals(
            setOf(Season.SPRING, Season.SUMMER, Season.AUTUMN),
            PlantTraits.seasonsOf("5-9月"),
        )
    }

    @Test
    fun `全角波浪号与顿号也能解析`() {
        assertEquals(setOf(Season.SPRING, Season.SUMMER), PlantTraits.seasonsOf("4～6月"))
        assertEquals(setOf(Season.SPRING, Season.SUMMER), PlantTraits.seasonsOf("5、6月"))
    }

    @Test
    fun `全年花期归为全年`() {
        assertEquals(setOf(Season.EVERGREEN), PlantTraits.seasonsOf("全年开花"))
        assertEquals(setOf(Season.EVERGREEN), PlantTraits.seasonsOf("四季常开"))
    }

    @Test
    fun `没有数字时按季节词判断`() {
        assertEquals(setOf(Season.SPRING), PlantTraits.seasonsOf("春季"))
        assertEquals(setOf(Season.SPRING, Season.SUMMER), PlantTraits.seasonsOf("春末夏初"))
    }

    @Test
    fun `解析不出来时返回空集合`() {
        assertTrue(PlantTraits.seasonsOf(null).isEmpty())
        assertTrue(PlantTraits.seasonsOf("").isEmpty())
        assertTrue(PlantTraits.seasonsOf("不定期").isEmpty())
    }

    // ---------------------------------------------------------------- 色彩

    @Test
    fun `从描述里提取颜色`() {
        assertTrue("红" in PlantTraits.colorsOf("鸡爪槭", "秋季叶色变为鲜红色"))
        assertTrue("黄" in PlantTraits.colorsOf("银杏", "秋天叶色金黄"))
    }

    @Test
    fun `植物名里的颜色字不算`() {
        // 「紫薇」的名字里有「紫」、果期描述里有「秋」，
        // 但它跟「这株植物的色彩」无关 —— 不剔除的话每种紫薇都会被算成紫色植物
        val colors = PlantTraits.colorsOf("紫薇", "花期长，秋季果熟")
        assertFalse("名字里的紫不该被当色彩", "紫" in colors)
    }

    @Test
    fun `病名里的颜色字不算`() {
        val colors = PlantTraits.colorsOf("月季", "注意防治白粉病与红蜘蛛")
        assertFalse("白" in colors)
        assertFalse("红" in colors)
    }

    // ---------------------------------------------------------------- 常绿

    @Test
    fun `常绿与落叶能区分，判不出来返回 null`() {
        assertEquals(true, PlantTraits.evergreenOf("常绿乔木"))
        assertEquals(false, PlantTraits.evergreenOf("落叶乔木"))
        // 关键：不知道就是不知道，不能返回 false ——
        // 那会让「常绿比例」这个指标系统性偏低
        assertNull(PlantTraits.evergreenOf("叶片秀丽"))
        assertNull(PlantTraits.evergreenOf(null))
    }
}

/**
 * 景观统计的单测。
 *
 * 多样性指数的数值是**手算过**的（不是把程序的输出抄进断言）：
 * 两个物种各一株时 p₁=p₂=0.5，
 *   Shannon H = −(0.5·ln0.5 + 0.5·ln0.5) = ln2 ≈ 0.6931
 *   Simpson D = 1 − (0.5² + 0.5²) = 0.5
 *   Pielou  J = H / ln2 = 1.0（物种数等于样本数，必然最均匀）
 */
class LandscapeAnalyzerTest {

    private fun plant(
        id: Long,
        name: String,
        category: String? = null,
        family: String? = null,
        flowering: String? = null,
        description: String? = null,
    ) = LandscapePlant(
        id = id,
        name = name,
        category = category,
        family = family,
        floweringPeriod = flowering,
        description = description,
    )

    @Test
    fun `空列表给出空统计而不是崩`() {
        val stats = LandscapeAnalyzer.analyze(emptyList())
        assertTrue(stats.isEmpty)
        assertEquals(0, stats.plantCount)
        assertEquals(0.0, stats.diversity.shannon, 0.0001)
        assertTrue(stats.suggestions.isEmpty())
    }

    @Test
    fun `两个物种各一株的多样性指数与手算一致`() {
        val stats = LandscapeAnalyzer.analyze(
            listOf(plant(1, "紫薇"), plant(2, "银杏")),
        )
        val d = stats.diversity
        assertEquals(2, d.speciesCount)
        assertEquals(2, d.individualCount)
        assertEquals(0.6931, d.shannon, 0.001)
        assertEquals(0.5, d.simpson, 0.001)
        assertEquals(1.0, d.evenness!!, 0.001)
    }

    @Test
    fun `单一物种的多样性与均匀度都是零`() {
        val stats = LandscapeAnalyzer.analyze(
            listOf(plant(1, "紫薇"), plant(2, "紫薇"), plant(3, "紫薇")),
        )
        assertEquals(1, stats.diversity.speciesCount)
        assertEquals(0.0, stats.diversity.shannon, 0.0001)
        // 只有一种时均匀度没有意义，返回 null 而不是 1.0
        assertNull(stats.diversity.evenness)
        assertEquals("单一物种", stats.diversity.level)
    }

    @Test
    fun `同一种多株时物种数不等于个体数`() {
        // 这条最容易写错：物种数按「去重后的名字」，个体数是档案条数
        val stats = LandscapeAnalyzer.analyze(
            listOf(
                plant(1, "紫薇"), plant(2, "紫薇"), plant(3, "紫薇"),
                plant(4, "银杏"),
            ),
        )
        assertEquals(2, stats.speciesCount)
        assertEquals(4, stats.plantCount)
    }

    @Test
    fun `层次统计正确且未分类单独占一项`() {
        val stats = LandscapeAnalyzer.analyze(
            listOf(
                plant(1, "香樟", category = "乔木"),
                plant(2, "桂花", category = "乔木"),
                plant(3, "杜鹃", category = "灌木"),
                plant(4, "麦冬", category = "地被"),
                plant(5, "某种草", category = null),
            ),
        )
        val byLabel = stats.layers.associate { it.label to it.count }
        assertEquals(2, byLabel["乔木"])
        assertEquals(1, byLabel["灌木"])
        assertEquals(1, byLabel["地被 / 藤本"])
        assertEquals("判不出的要单列", 1, byLabel["未分类"])
    }

    @Test
    fun `季相按春夏秋冬排序而不是按数量`() {
        // 排序被打乱的话，「哪一季空了」就看不出来了 —— 那正是季相图的价值
        val stats = LandscapeAnalyzer.analyze(
            listOf(
                plant(1, "A", flowering = "9-10月"),
                plant(2, "B", flowering = "3-4月"),
                plant(3, "C", flowering = "3-4月"),
                plant(4, "D", flowering = "3-4月"),
            ),
        )
        val order = stats.seasons.map { it.label }
        assertEquals(listOf("春", "秋"), order)
    }

    @Test
    fun `常绿比例只在判得出的样本里算，未标注单独显示`() {
        val stats = LandscapeAnalyzer.analyze(
            listOf(
                plant(1, "香樟", category = "常绿乔木"),
                plant(2, "银杏", category = "落叶乔木"),
                plant(3, "某种树", category = "乔木"),
            ),
        )
        assertEquals(1, stats.evergreen.evergreen)
        assertEquals(1, stats.evergreen.deciduous)
        assertEquals("未标注要单独计数", 1, stats.evergreen.unknown)
        assertEquals(0.5, stats.evergreen.ratio!!, 0.001)
    }

    @Test
    fun `样本太少时不报层次单一`() {
        // 3 株里 3 株乔木不该说「乔木占 100%，层次单一」——
        // 样本不足时任何比例都是噪音
        val stats = LandscapeAnalyzer.analyze(
            listOf(
                plant(1, "A", category = "乔木"),
                plant(2, "B", category = "乔木"),
                plant(3, "C", category = "乔木"),
            ),
        )
        assertFalse(stats.suggestions.any { "层次较单一" in it })
    }

    @Test
    fun `样本足够时会提示层次单一`() {
        val plants = (1..10).map { plant(it.toLong(), "树$it", category = "乔木") }
        val stats = LandscapeAnalyzer.analyze(plants)
        assertTrue(
            "10 株全是乔木，应该提示层次单一",
            stats.suggestions.any { "层次较单一" in it },
        )
    }

    @Test
    fun `提示文本里包含关键统计`() {
        val stats = LandscapeAnalyzer.analyze(
            listOf(plant(1, "香樟", category = "乔木"), plant(2, "杜鹃", category = "灌木")),
        )
        val text = stats.toPromptText()
        assertTrue(text.contains("植物总数：2"))
        assertTrue(text.contains("乔木"))
        assertTrue(text.contains("Shannon"))
    }
}
