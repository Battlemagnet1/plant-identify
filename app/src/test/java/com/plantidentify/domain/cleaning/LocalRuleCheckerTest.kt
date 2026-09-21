package com.plantidentify.domain.cleaning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地规则检查器的单测。
 *
 * 重点钉三件事：
 * 1. **指纹必须能区分同一株的不同问题**（字段级 discriminator）——
 *    指纹撞车会让问题被唯一索引静默吞掉，这是最容易漏的一类 bug
 * 2. **软删档案的观察不算孤儿** —— 判定基准是「所有档案」而非「未删档案」
 * 3. **不误报** —— 分类字段是自由文本，不能按白名单判
 */
class LocalRuleCheckerTest {

    private val now = 1_800_000_000_000L   // 固定时间，避免用例随系统时钟漂移

    private fun rec(
        id: Long,
        name: String = "测试植物",
        latin: String? = "Testus plantus",
        family: String? = "测试科",
        genus: String? = "测试属",
        category: String? = null,
        confidence: Double = 0.9,
        description: String? = null,
    ) = RecordSnapshot(
        id = id, name = name, latinName = latin, family = family, genus = genus,
        category = category, confidence = confidence, description = description,
    )

    private fun obs(
        id: Long,
        plantId: Long,
        timestamp: Long = now - 1000,
        latitude: Double? = null,
        longitude: Double? = null,
        json: String? = null,
        images: List<String> = listOf("images/a.png"),
    ) = ObservationSnapshot(
        id = id, plantId = plantId, timestamp = timestamp,
        latitude = latitude, longitude = longitude, aiResultJson = json, imagePaths = images,
    )

    // ------------------------------------------------------ 指纹区分度

    @Test
    fun `同一株缺多个字段必须产生多条问题且指纹互不相同`() {
        // 这是本项目里最容易写错的一处：指纹 = 类型 + id 时，
        // 「缺拉丁名」「缺科」「缺属」的指纹完全相同，
        // 唯一索引会让后两条被静默丢弃 —— 界面只报一个问题，
        // 用户补完拉丁名再检查，才发现还缺科和属
        val r = rec(5, latin = null, family = null, genus = null)
        val issues = LocalRuleChecker.checkMissingFields(listOf(r))

        assertEquals(3, issues.size)
        assertEquals(3, issues.map { it.fingerprint }.toSet().size)
    }

    @Test
    fun `指纹与 id 顺序无关`() {
        val a = CleaningIssue(
            type = CleaningIssueType.POSSIBLE_DUPLICATE,
            severity = CleaningSeverity.MEDIUM,
            recordIds = listOf(38, 12),
            reason = "x",
            discriminator = "3",
        )
        val b = a.copy(recordIds = listOf(12, 38, 12))
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun `名称相同的两个字段类问题不会互相覆盖`() {
        val r = rec(9, latin = null)
        val latinIssue = LocalRuleChecker.checkMissingFields(listOf(r)).single()
        val confIssue = LocalRuleChecker.checkConfidence(listOf(r.copy(confidence = 1.5))).single()
        assertNotEquals(latinIssue.fingerprint, confIssue.fingerprint)
    }

    // ------------------------------------------------------ 缺失字段

    @Test
    fun `中文名称为空是严重问题`() {
        val issues = LocalRuleChecker.checkMissingFields(listOf(rec(1, name = "   ")))
        assertEquals(CleaningIssueType.MISSING_FIELD, issues.single().type)
        assertEquals(CleaningSeverity.HIGH, issues.single().severity)
    }

    @Test
    fun `字段齐全时不报缺失`() {
        assertTrue(LocalRuleChecker.checkMissingFields(listOf(rec(1))).isEmpty())
    }

    // ------------------------------------------------------ 孤儿观察

    @Test
    fun `观察指向不存在的档案才是孤儿`() {
        val issues = LocalRuleChecker.checkOrphanObservations(
            observations = listOf(obs(1, plantId = 99)),
            knownPlantIds = setOf(1, 2),
        )
        assertEquals(CleaningIssueType.ORPHAN_OBSERVATION, issues.single().type)
        assertEquals(listOf(99L), issues.single().recordIds)
    }

    @Test
    fun `回收站里档案的观察不算孤儿`() {
        // 软删之后这条最容易漏：若拿「未删除的档案」当基准，
        // 用户删一株植物会让它名下几十次观察立刻变成「严重问题」，
        // 而真相只是他删了一株植物
        val issues = LocalRuleChecker.checkOrphanObservations(
            observations = listOf(obs(1, plantId = 7), obs(2, plantId = 7)),
            knownPlantIds = setOf(7),   // 7 已在回收站，但仍是合法父记录
        )
        assertTrue(issues.isEmpty())
    }

    // ------------------------------------------------------ 照片

    @Test
    fun `观察没有照片`() {
        val issues = LocalRuleChecker.checkObservationsWithoutImage(
            listOf(obs(3, plantId = 1, images = emptyList())),
        )
        assertEquals(CleaningIssueType.NO_IMAGE, issues.single().type)
    }

    @Test
    fun `丢失与损坏分开报 并且按株聚合`() {
        val observations = listOf(
            obs(1, plantId = 1, images = listOf("images/a.png", "images/b.png")),
            obs(2, plantId = 1, images = listOf("images/c.png")),
        )
        val issues = LocalRuleChecker.checkImageFiles(
            observations,
            ImageHealth(
                missing = setOf("images/a.png", "images/b.png"),
                broken = setOf("images/c.png"),
            ),
        )
        assertEquals(2, issues.size)
        val missing = issues.single { it.type == CleaningIssueType.MISSING_IMAGE }
        val broken = issues.single { it.type == CleaningIssueType.BROKEN_IMAGE }
        assertEquals(listOf(1L), missing.recordIds)
        assertTrue("应说明影响了 2 张", missing.reason.contains("2 张"))
        // 两者作用在同一株上，指纹必须不同，否则只有一条能落库
        assertNotEquals(missing.fingerprint, broken.fingerprint)
    }

    @Test
    fun `文件全在时不报图片问题`() {
        assertTrue(
            LocalRuleChecker.checkImageFiles(
                listOf(obs(1, plantId = 1)),
                ImageHealth.NONE,
            ).isEmpty(),
        )
    }

    @Test
    fun `无主照片按计数报一条`() {
        val issues = LocalRuleChecker.checkOrphanImages(4)
        assertEquals(CleaningIssueType.FOREIGN_KEY, issues.single().type)
        assertTrue(issues.single().recordIds.isEmpty())
    }

    // ------------------------------------------------------ 格式

    @Test
    fun `未来时间与过早时间都算异常`() {
        val issues = LocalRuleChecker.checkTimestamps(
            listOf(
                obs(1, plantId = 1, timestamp = now + 7 * 24 * 3600_000L),
                obs(2, plantId = 1, timestamp = 100L),
                obs(3, plantId = 1, timestamp = now - 1000),
            ),
            now = now,
        )
        assertEquals(2, issues.size)
    }

    @Test
    fun `一天内的时钟偏差不算异常`() {
        val issues = LocalRuleChecker.checkTimestamps(
            listOf(obs(1, plantId = 1, timestamp = now + 3600_000L)),
            now = now,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `零点坐标与半个坐标都算异常`() {
        val issues = LocalRuleChecker.checkCoordinates(
            listOf(
                obs(1, plantId = 1, latitude = 0.0, longitude = 0.0),
                obs(2, plantId = 1, latitude = 30.0, longitude = null),
                obs(3, plantId = 1, latitude = 30.0, longitude = 120.0),
            ),
        )
        assertEquals(2, issues.size)
    }

    @Test
    fun `坐标越界单独报`() {
        val issues = LocalRuleChecker.checkCoordinates(
            listOf(obs(1, plantId = 1, latitude = 91.0, longitude = 120.0)),
        )
        assertEquals(CleaningIssueType.INVALID_COORDINATE, issues.single().type)
    }

    @Test
    fun `置信度零是合法值 - 它表示尚未分析`() {
        assertTrue(LocalRuleChecker.checkConfidence(listOf(rec(1, confidence = 0.0))).isEmpty())
        assertTrue(LocalRuleChecker.checkConfidence(listOf(rec(1, confidence = 1.0))).isEmpty())
        assertEquals(
            1,
            LocalRuleChecker.checkConfidence(listOf(rec(1, confidence = 1.01))).size,
        )
    }

    @Test
    fun `AI 原始结果解析不了时才报`() {
        val observations = listOf(
            obs(1, plantId = 1, json = "坏掉的"),
            obs(2, plantId = 1, json = null),
            obs(3, plantId = 1, json = "好的"),
        )
        val issues = LocalRuleChecker.checkInvalidJson(observations) { it == "好的" }
        assertEquals(1, issues.size)
        assertEquals(CleaningSeverity.LOW, issues.single().severity)
    }

    @Test
    fun `分类字段是自由文本 - 正常的写法不能报`() {
        // 按白名单判会把下面这些都报成问题，而它们完全正确。
        // 一个每天误报几十条的功能，用户三天就会关掉
        val records = listOf(
            rec(1, category = "落叶灌木"),
            rec(2, category = "一年生草本"),
            rec(3, category = "常绿乔木"),
            rec(4, category = "多年生水生草本"),
            rec(5, category = null),
        )
        assertTrue(LocalRuleChecker.checkCategory(records).isEmpty())
    }

    @Test
    fun `分类字段写成整句话才算可疑`() {
        val records = listOf(
            rec(1, category = "这是一种叶片对生、边缘有锯齿的常绿小乔木，常见于南方的公园与街道两旁"),
            rec(2, category = "落叶乔木，"),
        )
        assertEquals(2, LocalRuleChecker.checkCategory(records).size)
    }

    // ------------------------------------------------------ 重复照片

    @Test
    fun `跨株重复照片更值得关注`() {
        val observations = listOf(
            obs(1, plantId = 1, images = listOf("images/a.png")),
            obs(2, plantId = 2, images = listOf("images/b.png")),
        )
        val issues = LocalRuleChecker.checkDuplicateImages(
            observations,
            mapOf("hash1" to listOf("images/a.png", "images/b.png")),
        )
        val issue = issues.single()
        assertEquals(CleaningSeverity.MEDIUM, issue.severity)
        assertEquals(listOf(1L, 2L), issue.recordIds)
        assertEquals("hash1", issue.discriminator)
    }

    @Test
    fun `同一株内部的重复照片只算轻微`() {
        val observations = listOf(obs(1, plantId = 1, images = listOf("images/a.png", "images/b.png")))
        val issues = LocalRuleChecker.checkDuplicateImages(
            observations,
            mapOf("hash1" to listOf("images/a.png", "images/b.png")),
        )
        assertEquals(CleaningSeverity.LOW, issues.single().severity)
    }

    @Test
    fun `重复照片的指纹基于内容哈希而不是路径`() {
        // 用户重新导出照片后路径会全变，若指纹用路径/id 拼，
        // 同一条问题会「换个身份」重新出现，「忽略」就失效了
        val observations = listOf(obs(1, plantId = 1, images = listOf("images/new/a.png")))
        val issues = LocalRuleChecker.checkDuplicateImages(
            observations,
            mapOf("deadbeef" to listOf("images/new/a.png")),
        )
        assertTrue(issues.single().fingerprint.contains("deadbeef"))
    }

    // ------------------------------------------------------ 汇总

    @Test
    fun `大检查把各类规则的结果合起来`() {
        val issues = LocalRuleChecker.check(
            records = listOf(rec(1, name = "", latin = null)),
            observations = listOf(
                obs(1, plantId = 99),
                obs(2, plantId = 1, images = emptyList()),
            ),
            knownPlantIds = setOf(1),
            now = now,
        )
        val types = issues.map { it.type }.toSet()
        assertTrue(CleaningIssueType.MISSING_FIELD in types)
        assertTrue(CleaningIssueType.ORPHAN_OBSERVATION in types)
        assertTrue(CleaningIssueType.NO_IMAGE in types)
    }
}
