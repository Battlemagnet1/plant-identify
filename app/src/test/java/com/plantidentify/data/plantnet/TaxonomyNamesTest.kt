package com.plantidentify.data.plantnet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 科属中文名的查表（v1.0.2.1018，修「中英文对齐」）。
 *
 * ## 这组用例钉的是「科属永远是拉丁文」那一类缺陷
 *
 * Pl@ntNet 的 `results[].species.family` / `.genus` **只有拉丁名**，
 * 没有 `commonNames` 字段。科属的中文名只在 `otherResults` 里，
 * 而且**只有请求带 `detailed=true` 才返回**。
 *
 * 不处理它的后果有两层：
 * 1. 同一个档案里出现「樟科 / Cinnamomum」这种中拉混排
 * 2. **`DuplicateMatcher` 按科属比对** —— 写法不一致时
 *    「同一科的两种植物」这个判据直接失效，清洗漏判
 *
 * 所以必须把 `otherResults` 解析出来，并且**只在命中时替换**（查不到就保留拉丁名，
 * 绝不编造、也绝不留空 —— 空值会让「同科」的判断彻底失去依据）。
 */
class TaxonomyNamesTest {

    private fun otherResults(json: String): JSONObject = JSONObject(json)

    /** 真实响应的形状：family/genus 两个数组，各自带 commonNames */
    private val realShape = """
        {
          "genus": [
            { "score": 0.41,
              "genus": { "scientificName": "Camphora",
                         "commonNames": ["樟屬"] } },
            { "score": 0.19,
              "genus": { "scientificName": "Cinnamomum",
                         "commonNames": ["樟属", "肉桂属"] } }
          ],
          "family": [
            { "score": 0.78,
              "family": { "scientificName": "Lauraceae",
                          "commonNames": ["樟科"] } },
            { "score": 0.03,
              "family": { "scientificName": "Nyctaginaceae",
                          "commonNames": ["紫茉莉科"] } }
          ]
        }
    """.trimIndent()

    @Test
    fun `科属拉丁名能换成中文名`() {
        val names = TaxonomyNames.fromOtherResults(otherResults(realShape))

        assertEquals("樟科", names.displayOf("Lauraceae"))
        assertEquals("樟屬", names.displayOf("Camphora"))
        assertEquals("樟属", names.displayOf("Cinnamomum"))
        assertEquals("紫茉莉科", names.displayOf("Nyctaginaceae"))
    }

    /** 大小写不一致也要能命中 —— 拉丁名大小写在不同接口里并不统一 */
    @Test
    fun `大小写不敏感`() {
        val names = TaxonomyNames.fromOtherResults(otherResults(realShape))
        assertEquals("樟科", names.displayOf("lauraceae"))
        assertEquals("樟科", names.displayOf("LAURACEAE"))
        assertEquals("樟科", names.displayOf("  Lauraceae  "))
    }

    /**
     * 查不到时**原样返回拉丁名**。
     *
     * 这是本类最重要的约定：绝不能返回 null 或空串。
     * 空值会让 `DuplicateMatcher` 的「同科/同属」判据失效，
     * 而拉丁名至少还能跨语言比对。
     */
    @Test
    fun `查不到时保留拉丁名 不返回空`() {
        val names = TaxonomyNames.fromOtherResults(otherResults(realShape))

        assertEquals("Rosaceae", names.displayOf("Rosaceae"))
        assertEquals("Ficus", names.displayOf("Ficus"))
        assertFalse(names.displayOf("Rosaceae").isNullOrBlank())
    }

    /** otherResults 缺省（没传 detailed=true）时，查表为空，一切原样返回 */
    @Test
    fun `没有 otherResults 时原样返回`() {
        val names = TaxonomyNames.fromOtherResults(null)
        assertFalse(names.hasEntries())
        assertEquals("Lauraceae", names.displayOf("Lauraceae"))
    }

    /** 空字符串 / null 的入参处理：null 进 null 出，不能崩 */
    @Test
    fun `null 与空白拉丁名的处理`() {
        val names = TaxonomyNames.fromOtherResults(otherResults(realShape))

        assertNull(names.displayOf(null))
        assertEquals("樟科", names.displayOf(null, fallback = "樟科"))
        assertTrue(names.displayOf("").isNullOrEmpty())
    }

    /**
     * `commonNames` 里只有拼音时**不能**当成中文名。
     *
     * 与种级 `commonNames` 的处理保持同一判据：Pl@ntNet 会把拼音混进来，
     * 取错就会让档案里出现一个拼音当"中文科名"。
     */
    @Test
    fun `只有拼音时不采用`() {
        val onlyPinyin = """
            {
              "family": [
                { "score": 0.5,
                  "family": { "scientificName": "Lauraceae",
                              "commonNames": ["Zhang ke", "Lauraceae"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(otherResults(onlyPinyin))
        assertFalse("纯拼音不该被当成中文名", names.hasEntries())
        assertEquals("Lauraceae", names.displayOf("Lauraceae"))
    }

    /** 拼音排在汉字前面时，仍要挑出汉字那个 */
    @Test
    fun `拼音在前时仍选出中文`() {
        val mixed = """
            {
              "genus": [
                { "score": 0.5,
                  "genus": { "scientificName": "Camphora",
                             "commonNames": ["Zhang shu", "樟属", "樟屬"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(otherResults(mixed))
        assertEquals("樟属", names.displayOf("Camphora"))
    }

    /**
     * 同一拉丁名出现多次时**先到先得** ——
     * otherResults 按 score 降序，第一条就是最可能的那个。
     */
    @Test
    fun `重复条目取第一条`() {
        val dup = """
            {
              "family": [
                { "score": 0.9,
                  "family": { "scientificName": "Lauraceae",
                              "commonNames": ["樟科"] } },
                { "score": 0.1,
                  "family": { "scientificName": "Lauraceae",
                              "commonNames": ["其他叫法"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(otherResults(dup))
        assertEquals("樟科", names.displayOf("Lauraceae"))
    }

    /**
     * `scientificName` 可能带命名人（`Cinnamomum camphora (L.) J.Presl`），
     * 取属名时要截到第一个空格前。
     */
    @Test
    fun `带命名人时截取属名`() {
        val withAuthor = """
            {
              "genus": [
                { "score": 0.5,
                  "genus": { "scientificName": "Cinnamomum Schaeff.",
                             "commonNames": ["樟属"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(JSONObject(withAuthor))
        assertEquals("樟属", names.displayOf("Cinnamomum"))
    }

    // ---------------------------------------------------------- 简体优先

    /**
     * 简繁并列时必须选**简体**。
     *
     * Pl@ntNet 常给出 `["新木姜子属", "新木薑子屬"]` —— 两个都是中文，
     * 但本项目界面与视觉 AI 通道用简体。若这里选到繁体，
     * 就会出现「樟科（简）+ 樟屬（繁）」这种同概念两种写法的混排。
     */
    @Test
    fun `简繁并列时选简体`() {
        val both = """
            {
              "genus": [
                { "score": 0.5,
                  "genus": { "scientificName": "Neolitsea",
                             "commonNames": ["新木薑子屬", "新木姜子属"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(JSONObject(both))
        assertEquals("新木姜子属", names.displayOf("Neolitsea"))
    }

    /** 只有繁体时退回繁体 —— 有总比没有强，也比留空好 */
    @Test
    fun `只有繁体时仍采用`() {
        val onlyTraditional = """
            {
              "genus": [
                { "score": 0.5,
                  "genus": { "scientificName": "Camphora",
                             "commonNames": ["樟屬"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(JSONObject(onlyTraditional))
        assertEquals("樟屬", names.displayOf("Camphora"))
    }

    /**
     * 同形字**不能**被当成繁体 —— 否则「樟科」会被判成繁体而退回第一条，
     * 反而选错。这是本判据唯一危险的误判方向。
     */
    @Test
    fun `同形字不算繁体`() {
        // 科、樟、蕨、松、梅 这些繁简同形
        assertFalse(TaxonomyNames.containsTraditional("樟科"))
        assertFalse(TaxonomyNames.containsTraditional("松科"))
        assertFalse(TaxonomyNames.containsTraditional("蔷薇科"))
        assertFalse(TaxonomyNames.containsTraditional("梅"))

        // 真正的繁体
        assertTrue(TaxonomyNames.containsTraditional("樟屬"))
        assertTrue(TaxonomyNames.containsTraditional("葉"))
        assertTrue(TaxonomyNames.containsTraditional("樹"))
    }
}
