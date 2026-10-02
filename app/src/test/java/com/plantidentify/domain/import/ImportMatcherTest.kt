package com.plantidentify.domain.import

import com.plantidentify.domain.cleaning.RecordSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入匹配的单测（v1.0.2 Phase 2）。
 *
 * 判定逻辑本身已经由 `DuplicateMatcherTest` 覆盖过了，所以这里**不复测六级规则**，
 * 只钉住本类新增的那层语义：
 * 两侧同源的候选对要被丢掉、每条导入记录只留最强的一条、冲突要盖过「确定重复」。
 */
class ImportMatcherTest {

    private fun rec(
        id: Long,
        name: String,
        latin: String? = null,
        family: String? = null,
        genus: String? = null,
    ) = RecordSnapshot(
        id = id,
        name = name,
        latinName = latin,
        family = family,
        genus = genus,
    )

    private fun decisionOf(result: ImportMatchResult, importId: Long): ImportMatch =
        result.matches.first { it.importPlantId == importId }

    @Test
    fun `与本地毫无关系的记录判为新记录`() {
        val local = listOf(rec(1, "紫薇", "Lagerstroemia indica", "千屈菜科", "紫薇属"))
        val imported = listOf(rec(100, "凤凰木", "Delonix regia", "豆科", "凤凰木属"))

        val result = ImportMatcher.match(imported, local)

        val match = decisionOf(result, 100)
        assertEquals(ImportDecision.NEW, match.decision)
        assertNull(match.matchedPlantId)
        assertEquals(0, result.pendingCount)
    }

    @Test
    fun `拉丁学名全同判为确定重复`() {
        val local = listOf(rec(1, "紫薇", "Lagerstroemia indica", "千屈菜科", "紫薇属"))
        // 中文名写法不同，但拉丁名一致 —— 级 1 是最硬的证据
        val imported = listOf(rec(100, "百日红", "Lagerstroemia indica", "千屈菜科", "紫薇属"))

        val result = ImportMatcher.match(imported, local)

        val match = decisionOf(result, 100)
        assertEquals(ImportDecision.CONCLUSIVE, match.decision)
        assertEquals(1L, match.matchedPlantId)
        assertEquals(1, match.matchLevel)
        // 确定重复不算「待用户逐条确认」
        assertTrue(!match.needsReview)
    }

    @Test
    fun `名字相似但证据不足的判为疑似，需要用户确认`() {
        val local = listOf(rec(1, "紫薇", null, "千屈菜科", "紫薇属"))
        // 名字接近、没有拉丁名兜底 → 落在灰区
        val imported = listOf(rec(100, "紫薇花", null, "千屈菜科", "紫薇属"))

        val result = ImportMatcher.match(imported, local)

        val match = decisionOf(result, 100)
        assertEquals(ImportDecision.SUSPECTED, match.decision)
        assertTrue("疑似项必须进待处理", match.needsReview)
        assertEquals(1, result.pendingCount)
    }

    @Test
    fun `科属对不上时判为冲突，优先于确定重复`() {
        // 拉丁名完全相同，但科属两边都填了且不一致 —— 典型的一边填错
        val local = listOf(rec(1, "榕树", "Ficus microcarpa", "桑科", "榕属"))
        val imported = listOf(rec(100, "榕树", "Ficus microcarpa", "大戟科", "乌桕属"))

        val result = ImportMatcher.match(imported, local)

        val match = decisionOf(result, 100)
        assertEquals(ImportDecision.CONFLICT, match.decision)
        assertTrue(match.needsReview)
    }

    @Test
    fun `同一次导入内部的两条记录不会互相匹配`() {
        // 两条导入记录彼此高度相似，但本地库是空的 —— 它们都该是「新记录」。
        // 如果那层过滤漏了，用户会看到「导入的数据与导入的数据重复」这种怪提示
        val imported = listOf(
            rec(100, "紫薇", "Lagerstroemia indica", "千屈菜科", "紫薇属"),
            rec(101, "紫薇", "Lagerstroemia indica", "千屈菜科", "紫薇属"),
        )

        val result = ImportMatcher.match(imported, emptyList())

        assertEquals(ImportDecision.NEW, decisionOf(result, 100).decision)
        assertEquals(ImportDecision.NEW, decisionOf(result, 101).decision)
        assertEquals(0, result.pendingCount)
    }

    @Test
    fun `每条导入记录只保留最强的一条结论`() {
        val local = listOf(
            // 级 1：拉丁名完全相同
            rec(1, "紫薇", "Lagerstroemia indica", "千屈菜科", "紫薇属"),
            // 级 3：只是名字像
            rec(2, "紫薇花", "Lagerstroemia speciosa", "千屈菜科", "紫薇属"),
        )
        val imported = listOf(rec(100, "紫薇", "Lagerstroemia indica", "千屈菜科", "紫薇属"))

        val result = ImportMatcher.match(imported, local)

        val match = decisionOf(result, 100)
        assertEquals("应该匹配到级 1 那条，而不是名字相似的", 1L, match.matchedPlantId)
        assertEquals(1, match.matchLevel)
    }

    @Test
    fun `没有导入记录时返回空结果`() {
        val result = ImportMatcher.match(emptyList(), listOf(rec(1, "紫薇")))
        assertTrue(result.matches.isEmpty())
        assertEquals(0, result.pendingCount)
    }

    @Test
    fun `只有一条记录时不产生任何候选`() {
        // 候选集生成至少要两条记录才可能配对 —— 这条防止「单条导入」误报
        val result = ImportMatcher.match(listOf(rec(100, "紫薇")), emptyList())
        assertEquals(1, result.matches.size)
        assertEquals(ImportDecision.NEW, result.matches.first().decision)
    }
}
