package com.plantidentify.domain.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 健康度的单测。
 *
 * 这里的重点不是「算得对」，而是**口径**：一株有 3 个小毛病，
 * 不该比 3 株各有一个大毛病更严重。这种口径错误在界面上表现为
 * 「我修了半天，百分比没怎么动」，用户说不清哪里不对，只会不用它。
 */
class CleaningHealthTest {

    private fun issue(
        type: CleaningIssueType = CleaningIssueType.MISSING_FIELD,
        severity: CleaningSeverity = CleaningSeverity.MEDIUM,
        recordIds: List<Long>,
    ) = CleaningIssue(
        type = type,
        severity = severity,
        recordIds = recordIds,
        reason = "测试",
        discriminator = "t",
    )

    @Test
    fun `没有任何问题是满分`() {
        val health = CleaningHealthCalculator.compute(totalRecords = 17, issues = emptyList())
        assertEquals(100, health.score)
        assertEquals(0, health.openIssues)
        assertTrue(!health.hasIssues)
    }

    @Test
    fun `一株的小毛病不该比三株的大毛病更严重`() {
        // 这是本类存在的理由。按「问题条数」算的话前者的惩罚是后者的 3 倍，
        // 而实际情况恰好相反
        val oneRecordManyIssues = listOf(
            issue(CleaningIssueType.MISSING_FIELD, CleaningSeverity.LOW, listOf(1)),
            issue(CleaningIssueType.INVALID_CATEGORY, CleaningSeverity.LOW, listOf(1)),
            issue(CleaningIssueType.INVALID_JSON, CleaningSeverity.LOW, listOf(1)),
        )
        val threeRecordsOneIssue = listOf(
            issue(CleaningIssueType.ORPHAN_OBSERVATION, CleaningSeverity.HIGH, listOf(1)),
            issue(CleaningIssueType.ORPHAN_OBSERVATION, CleaningSeverity.HIGH, listOf(2)),
            issue(CleaningIssueType.ORPHAN_OBSERVATION, CleaningSeverity.HIGH, listOf(3)),
        )

        val a = CleaningHealthCalculator.compute(10, oneRecordManyIssues)
        val b = CleaningHealthCalculator.compute(10, threeRecordsOneIssue)

        assertTrue("一株三个小毛病（${a.score}）应高于三株各一个大毛病（${b.score}）", a.score > b.score)
        assertEquals("牵连的株数才是分母", 1, a.affectedRecords)
        assertEquals(3, b.affectedRecords)
    }

    @Test
    fun `所有档案都有严重问题是零分`() {
        val issues = (1L..10L).map {
            issue(CleaningIssueType.ORPHAN_OBSERVATION, CleaningSeverity.HIGH, listOf(it))
        }
        assertEquals(0, CleaningHealthCalculator.compute(10, issues).score)
    }

    @Test
    fun `一株有严重问题时扣分明显但不归零`() {
        val issues = listOf(
            issue(CleaningIssueType.ORPHAN_OBSERVATION, CleaningSeverity.HIGH, listOf(1)),
        )
        // 10 株里 1 株严重：100 - 100×8/(10×8) = 90
        assertEquals(90, CleaningHealthCalculator.compute(10, issues).score)
    }

    @Test
    fun `疑似重复牵连两株 - 两边都要扣`() {
        val health = CleaningHealthCalculator.compute(
            10,
            listOf(issue(CleaningIssueType.POSSIBLE_DUPLICATE, CleaningSeverity.HIGH, listOf(3, 7))),
        )
        assertEquals(2, health.affectedRecords)
        assertEquals(80, health.score)
    }

    @Test
    fun `没有关联档案的问题不进分母 - 但要出现在计数里`() {
        // 孤儿照片（找不到所属观察）确实是个问题，但它不属于任何一株，
        // 硬摊到某株头上会让用户点进去发现「查不到」。它出现在分类计数里，
        // 用户在列表里能看到并处理
        val health = CleaningHealthCalculator.compute(
            totalRecords = 10,
            issues = listOf(issue(CleaningIssueType.FOREIGN_KEY, CleaningSeverity.MEDIUM, emptyList())),
        )
        assertEquals(100, health.score)
        assertEquals(1, health.openIssues)
        assertEquals(1, health.countOf(CleaningIssueCategory.MISSING))
    }

    @Test
    fun `引用了已删档案的问题不计入涉及的株数`() {
        // 合并之后有一小段时间，涉及被合并株的问题还没结案。
        // 若不排除已删档案，界面会出现「共 2 株档案，涉及 3 株」——
        // 用户没法理解，也没法处理
        val issues = listOf(
            issue(CleaningIssueType.DUPLICATE_IMAGE, CleaningSeverity.MEDIUM, listOf(1, 2, 3)),
            issue(CleaningIssueType.ORPHAN_OBSERVATION, CleaningSeverity.HIGH, listOf(1)),
        )
        val health = CleaningHealthCalculator.compute(
            totalRecords = 2,
            issues = issues,
            aliveIds = setOf(1L, 3L),
        )
        assertEquals(2, health.affectedRecords)
        assertEquals("条数仍然是全部待处理问题", 2, health.openIssues)
    }

    @Test
    fun `空库且有问题是零分`() {
        // 空库不该显示 100 分：它确实有毛病（例如指向已物理删除档案的观察）
        val health = CleaningHealthCalculator.compute(
            0,
            listOf(issue(CleaningIssueType.ORPHAN_OBSERVATION, CleaningSeverity.HIGH, listOf(9))),
        )
        assertEquals(0, health.score)
    }

    @Test
    fun `空库无问题是满分`() {
        assertEquals(100, CleaningHealthCalculator.compute(0, emptyList()).score)
    }

    @Test
    fun `分类计数与类型计数都按四类归口`() {
        val health = CleaningHealthCalculator.compute(
            10,
            listOf(
                issue(CleaningIssueType.MISSING_FIELD, CleaningSeverity.LOW, listOf(1)),
                issue(CleaningIssueType.MISSING_FIELD, CleaningSeverity.LOW, listOf(2)),
                issue(CleaningIssueType.POSSIBLE_DUPLICATE, CleaningSeverity.MEDIUM, listOf(3, 4)),
                issue(CleaningIssueType.DUPLICATE_IMAGE, CleaningSeverity.LOW, listOf(5)),
            ),
        )
        assertEquals(2, health.countOf(CleaningIssueCategory.MISSING))
        assertEquals(1, health.countOf(CleaningIssueCategory.DUPLICATE))
        assertEquals(1, health.countOf(CleaningIssueCategory.IMAGE))
        assertEquals(0, health.countOf(CleaningIssueCategory.CONFLICT))
        assertEquals(2, health.countOf(CleaningIssueType.MISSING_FIELD))
    }
}
