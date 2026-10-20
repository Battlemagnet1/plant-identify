package com.plantidentify.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用百科模型的结果纠正名称 / 科 / 属（v1.0.2.1018）。
 *
 * ## 这组用例保护的是「不覆盖」
 *
 * 识别（视觉 AI / Pl@ntNet）与百科（文字模型）是**两次独立调用**，
 * 可能给出互相矛盾的结论。若百科模型说「这其实是 XX」就无脑改名，
 * 会把视觉 AI 的正确结论推翻，而用户完全不知道档案被改过。
 *
 * 所以规则是**只补不覆盖**：只有当现有值是空的、或是拉丁文/拼音时
 * 才写入模型给的中文值。下面一半的用例都在钉这条。
 */
class TaxonomyCorrectorTest {

    private fun suggested(
        name: String? = null,
        family: String? = null,
        genus: String? = null,
    ) = PlantAnalysis(
        canonicalName = name,
        canonicalFamily = family,
        canonicalGenus = genus,
    )

    // ---------------------------------------------------------- 该补的情况

    /**
     * Pl@ntNet 对没有中文俗名的物种会直接把拉丁学名当 name。
     * 这时百科模型给出的中文名应该补上。
     */
    @Test
    fun `名称是拉丁名时补中文`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "Camphora officinarum",
            currentLatinName = "Camphora officinarum",
            currentFamily = "樟科",
            currentGenus = "樟属",
            suggested = suggested(name = "樟"),
        )
        assertEquals("樟", plan.name)
    }

    /** 拼音当名字（Pl@ntNet 混入过拼音）也应当补成汉字 */
    @Test
    fun `名称是拼音时补中文`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "Ye zi hua",
            currentLatinName = "Bougainvillea spectabilis",
            currentFamily = "紫茉莉科",
            currentGenus = "叶子花属",
            suggested = suggested(name = "叶子花"),
        )
        assertEquals("叶子花", plan.name)
    }

    /** 名称为空时当然要补 */
    @Test
    fun `名称为空时补上`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "",
            currentLatinName = null,
            currentFamily = null,
            currentGenus = null,
            suggested = suggested(name = "薰衣草"),
        )
        assertEquals("薰衣草", plan.name)
    }

    /**
     * **最核心的场景**：Pl@ntNet 给不出中文科名时退回拉丁名
     * （实测 `Asteraceae` 只回 `["Composite","Daisy Family"]`），
     * 百科模型能补成「菊科」。
     */
    @Test
    fun `拉丁科名补成中文科`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "向日葵",
            currentLatinName = "Helianthus giganteus",
            currentFamily = "Asteraceae",
            currentGenus = "向日葵属",
            suggested = suggested(family = "菊科"),
        )
        assertEquals("菊科", plan.family)
        assertNull("属已经是中文，不该动", plan.genus)
        assertNull("名称已经是中文，不该动", plan.name)
    }

    @Test
    fun `拉丁属名补成中文属`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "薰衣草",
            currentLatinName = "Lavandula angustifolia",
            currentFamily = "唇形科",
            currentGenus = "Lavandula",
            suggested = suggested(genus = "薰衣草属"),
        )
        assertEquals("薰衣草属", plan.genus)
    }

    // ---------------------------------------------------------- 不该覆盖的情况

    /**
     * **档案里已有中文名就不动** —— 哪怕模型建议另一个名字。
     *
     * 视觉 AI 的结论与用户手改都属于「已有判断」，百科模型无权推翻。
     * 名字不同不代表模型对：两者看的证据不同，混淆的代价由用户承担。
     */
    @Test
    fun `已有中文名时绝不覆盖`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "月季",
            currentLatinName = "Rosa chinensis",
            currentFamily = "蔷薇科",
            currentGenus = "蔷薇属",
            suggested = suggested(
                name = "香水月季",
                family = "别的科",
                genus = "别的属",
            ),
        )
        assertTrue("三个字段都不该动", plan.isEmpty)
    }

    /** 用户手改过的中文科属也一样不许覆盖 */
    @Test
    fun `已有中文科属时绝不覆盖`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "银杏",
            currentLatinName = "Ginkgo biloba",
            currentFamily = "银杏科",
            currentGenus = "银杏属",
            suggested = suggested(family = "某个别的科", genus = "某个别的属"),
        )
        assertTrue(plan.isEmpty)
    }

    // ---------------------------------------------------------- 候选值的格式校验

    /**
     * 科必须以「科」结尾、属必须以「属」结尾。
     *
     * 模型有把属名写成「薰衣草」（缺「属」字）的倾向 —— 那正是
     * Pl@ntNet 的原始数据里的问题，不能让它从百科通道再流进来一次。
     */
    @Test
    fun `属名不以属结尾时不采用`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "薰衣草",
            currentLatinName = "Lavandula angustifolia",
            currentFamily = "唇形科",
            currentGenus = "Lavandula",
            suggested = suggested(genus = "薰衣草"),   // 缺「属」字
        )
        assertNull(plan.genus)
    }

    @Test
    fun `科名不以科结尾时不采用`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "向日葵",
            currentLatinName = "Helianthus",
            currentFamily = "Asteraceae",
            currentGenus = null,
            suggested = suggested(family = "菊"),      // 缺「科」字
        )
        assertNull(plan.family)
    }

    /** 科属串栏（把「菊科」填进属一栏）也要挡住 */
    @Test
    fun `科属串栏时不采用`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "向日葵",
            currentLatinName = "Helianthus",
            currentFamily = "Asteraceae",
            currentGenus = "Helianthus",
            suggested = suggested(family = "菊科", genus = "菊科"),
        )
        assertEquals("菊科", plan.family)
        assertNull("「菊科」不能当属名", plan.genus)
    }

    /** 模型没能给出中文（还是拉丁）时不能拿它去替换 —— 否则白跑一趟 */
    @Test
    fun `候选仍是拉丁时不采用`() {
        val plan = TaxonomyCorrector.plan(
            currentName = "向日葵",
            currentLatinName = "Helianthus giganteus",
            currentFamily = "Asteraceae",
            currentGenus = "Helianthus",
            suggested = suggested(name = "Helianthus", family = "Asteraceae", genus = "Helianthus"),
        )
        assertTrue("候选没有中文，一律不采用", plan.isEmpty)
    }

    /** 空/空白候选一律忽略 */
    @Test
    fun `空白候选忽略`() {
        val plan = TaxonomyCorrector.plan(
            currentName = null,
            currentLatinName = null,
            currentFamily = null,
            currentGenus = null,
            suggested = suggested(name = "  ", family = "", genus = " "),
        )
        assertTrue(plan.isEmpty)
    }

    /** 只有一个字的后缀（如「科」）没有信息量，不采用 */
    @Test
    fun `只有后缀时不采用`() {
        val plan = TaxonomyCorrector.plan(
            currentName = null,
            currentLatinName = null,
            currentFamily = null,
            currentGenus = null,
            suggested = suggested(family = "科", genus = "属"),
        )
        assertTrue(plan.isEmpty)
    }
}
