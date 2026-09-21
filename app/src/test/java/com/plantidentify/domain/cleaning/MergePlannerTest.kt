package com.plantidentify.domain.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段级择优的单测。
 *
 * 合并是**破坏性**操作：被合并的那一株会进回收站，用户几乎不会再翻回去看。
 * 所以这里每条用例问的都是同一个问题 —— **信息丢了没有**。
 */
class MergePlannerTest {

    private fun rec(
        id: Long,
        name: String = "植物",
        latin: String? = null,
        family: String? = null,
        genus: String? = null,
        category: String? = null,
        commonNames: String? = null,
        description: String? = null,
        note: String? = null,
        confidence: Double = 0.0,
        observationCount: Int = 0,
        imageCount: Int = 0,
    ) = RecordSnapshot(
        id = id, name = name, latinName = latin, family = family, genus = genus,
        category = category, commonNames = commonNames, description = description,
        note = note, confidence = confidence,
        observationCount = observationCount, imageCount = imageCount,
    )

    @Test
    fun `名称取保留侧 - 不按长度选`() {
        // 名称是档案的身份。被合并的那一株正是用户判定「其实是同一株」的那株，
        // 让它的名字覆盖主档名等于让用户的选择失效
        val keep = rec(1, name = "紫薇")
        val drop = rec(2, name = "百日红（俗称）")
        val plan = MergePlanner.plan(keep, drop)

        assertEquals("紫薇", plan.value(MergeField.NAME))
        assertEquals(FieldSource.KEEP, plan.choice(MergeField.NAME)!!.source)
    }

    @Test
    fun `拉丁学名取更完整的那个`() {
        val plan = MergePlanner.plan(
            rec(1, latin = "Platanus acerifolia"),
            rec(2, latin = "Platanus × acerifolia (Aiton) Willd."),
        )
        assertTrue(plan.value(MergeField.LATIN_NAME)!!.contains("Willd."))
        assertEquals(FieldSource.DROP, plan.choice(MergeField.LATIN_NAME)!!.source)
    }

    @Test
    fun `一边为空时取非空的那边`() {
        val plan = MergePlanner.plan(rec(1, family = null), rec(2, family = "千屈菜科"))
        assertEquals("千屈菜科", plan.value(MergeField.FAMILY))
        assertEquals(FieldSource.DROP, plan.choice(MergeField.FAMILY)!!.source)
    }

    @Test
    fun `两边都为空时是 null 而不是空串`() {
        val plan = MergePlanner.plan(rec(1), rec(2))
        assertNull(plan.value(MergeField.FAMILY))
    }

    @Test
    fun `科不一致要写进 warnings - 系统替用户做了决定必须告知`() {
        val plan = MergePlanner.plan(
            rec(1, name = "甲", family = "千屈菜科"),
            rec(2, name = "乙", family = "悬铃木科"),
        )
        assertEquals(1, plan.warnings.size)
        assertTrue(plan.warnings.single().contains("科"))
        assertTrue("要写清两边的值", plan.warnings.single().contains("千屈菜科"))
    }

    @Test
    fun `科相同不产生 warning`() {
        val plan = MergePlanner.plan(
            rec(1, family = "千屈菜科"),
            rec(2, family = "千屈菜科"),
        )
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `俗称取并集 - 不能因为取更长而丢掉别名`() {
        // 与方案 §7.6 的「取更长」不同：俗称是列表，取更长会让一方别名整体消失，
        // 而别名恰好是「这株到底叫什么」最实用的信息
        val plan = MergePlanner.plan(
            rec(1, commonNames = "百日红、痒痒树"),
            rec(2, commonNames = "满堂红"),
        )
        val value = plan.value(MergeField.COMMON_NAMES)!!
        assertTrue(value.contains("百日红"))
        assertTrue(value.contains("痒痒树"))
        assertTrue(value.contains("满堂红"))
        assertEquals(FieldSource.MANUAL, plan.choice(MergeField.COMMON_NAMES)!!.source)
    }

    @Test
    fun `俗称并集要去重且用全角顿号连接`() {
        val plan = MergePlanner.plan(
            rec(1, commonNames = "百日红, 痒痒树"),
            rec(2, commonNames = "百日红；满堂红"),
        )
        assertEquals("百日红、痒痒树、满堂红", plan.value(MergeField.COMMON_NAMES))
    }

    @Test
    fun `只有一边有俗称时来源是那边`() {
        val plan = MergePlanner.plan(rec(1, commonNames = "百日红"), rec(2))
        assertEquals(FieldSource.KEEP, plan.choice(MergeField.COMMON_NAMES)!!.source)
    }

    @Test
    fun `百科字段取更长的那个`() {
        val plan = MergePlanner.plan(
            rec(1, description = "落叶灌木。"),
            rec(2, description = "落叶灌木或小乔木，高可达七米，树皮平滑灰色，花期六到九月。"),
        )
        assertEquals(FieldSource.DROP, plan.choice(MergeField.DESCRIPTION)!!.source)
    }

    @Test
    fun `备注必须拼接 - 用户手写的不能丢`() {
        val plan = MergePlanner.plan(
            rec(1, note = "东门第三棵"),
            rec(2, note = "已开花"),
        )
        val value = plan.value(MergeField.NOTE)!!
        assertTrue(value.contains("东门第三棵"))
        assertTrue(value.contains("已开花"))
    }

    @Test
    fun `备注完全相同时不重复拼`() {
        val plan = MergePlanner.plan(rec(1, note = "东门"), rec(2, note = "东门"))
        assertEquals("东门", plan.value(MergeField.NOTE))
    }

    @Test
    fun `置信度取更高的`() {
        val plan = MergePlanner.plan(rec(1, confidence = 0.72), rec(2, confidence = 0.94))
        assertEquals(0.94, plan.confidence, 1e-9)
        assertEquals(FieldSource.DROP, plan.confidenceSource)
    }

    @Test
    fun `观察数与照片数是两边之和 - 预览要显示合并后的规模`() {
        val plan = MergePlanner.plan(
            rec(1, observationCount = 3, imageCount = 5),
            rec(2, observationCount = 2, imageCount = 4),
        )
        assertEquals(5, plan.observationCount)
        assertEquals(9, plan.imageCount)
    }

    @Test
    fun `默认保留观察次数多的那一株`() {
        val a = rec(1, observationCount = 1)
        val b = rec(2, observationCount = 5)
        assertEquals(2L, MergePlanner.defaultKeep(a, b).id)
    }

    @Test
    fun `观察次数相同时保留编号更小的`() {
        val a = rec(9, observationCount = 2)
        val b = rec(3, observationCount = 2)
        assertEquals(3L, MergePlanner.defaultKeep(a, b).id)
    }

    @Test
    fun `默认保留不按置信度 - 一次误判的高置信度不该成为主档`() {
        val early = rec(1, confidence = 0.60, observationCount = 3)
        val late = rec(2, confidence = 0.99, observationCount = 1)
        assertEquals(1L, MergePlanner.defaultKeep(early, late).id)
    }

    @Test
    fun `用户改选后来源标记为手动`() {
        val plan = MergePlanner.plan(rec(1, family = "甲科"), rec(2, family = "乙科"))
        val edited = plan.withChoice(MergeField.FAMILY, "丙科")
        assertEquals("丙科", edited.value(MergeField.FAMILY))
        assertEquals(FieldSource.MANUAL, edited.choice(MergeField.FAMILY)!!.source)
        assertEquals(listOf(MergeField.FAMILY), edited.changedFields())
        // 用户改的是其中一个字段，其余字段的择优结果不能被连带改掉
        assertEquals(plan.value(MergeField.NAME), edited.value(MergeField.NAME))
    }

    @Test
    fun `字段集合覆盖全部可择优字段`() {
        val plan = MergePlanner.plan(rec(1), rec(2))
        // 少一个字段的后果是「这个字段永远取保留侧」，而用户在预览里
        // 看不到它，也就无从发现
        MergeField.entries.forEach { field ->
            assertTrue("缺少字段 ${field.name}", plan.fields.containsKey(field))
        }
    }
}
