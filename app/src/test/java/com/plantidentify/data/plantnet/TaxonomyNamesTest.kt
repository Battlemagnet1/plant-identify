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
        // 原始数据是 "樟屬"（繁体），入库前统一转简体
        assertEquals("樟属", names.displayOf("Camphora"))
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

    /**
     * **只给繁体**时必须转成简体（v1.0.2.1018 真机实测发现的问题）。
     *
     * 真站对蔷薇科只回 `["薔薇科"]`、银杏科只回 `["銀杏科"]` —— 没有简体候选。
     * 只做「优先挑简体」的话，这些仍会原样存进档案，于是同一个科在
     * 不同记录里写成 `薔薇科` / `蔷薇科` 两种；而清洗是**按科属字符串比对**的，
     * 这两条就认不出「同一科」。
     */
    @Test
    fun `只有繁体时转成简体`() {
        val onlyTraditional = """
            {
              "family": [
                { "score": 0.9,
                  "family": { "scientificName": "Rosaceae",
                              "commonNames": ["薔薇科"] } }
              ],
              "genus": [
                { "score": 0.8,
                  "genus": { "scientificName": "Camphora",
                             "commonNames": ["樟屬"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(JSONObject(onlyTraditional))
        assertEquals("蔷薇科", names.displayOf("Rosaceae"))
        assertEquals("樟属", names.displayOf("Camphora"))
    }

    /** 银杏科 → 银杏科（銀 → 银） */
    @Test
    fun `银杏科转简体`() {
        val ginkgo = """
            {
              "family": [
                { "score": 0.95,
                  "family": { "scientificName": "Ginkgoaceae",
                              "commonNames": ["銀杏科"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(JSONObject(ginkgo))
        assertEquals("银杏科", names.displayOf("Ginkgoaceae"))
    }

    /** 纯简体的名字必须**原样不动** —— 转换不能把正确的东西改坏 */
    @Test
    fun `简体原名不被改动`() {
        listOf("蔷薇科", "樟科", "樟属", "银杏科", "菊科", "松科", "梅", "蕨")
            .forEach { s ->
                assertEquals("「$s」不应被改动", s, TaxonomyNames.toSimplified(s))
            }
    }

    /** 转换只作用于已知字符；表外字符原样保留 */
    @Test
    fun `表外字符原样保留`() {
        val mixed = "薔薇X科"
        assertEquals("蔷薇X科", TaxonomyNames.toSimplified(mixed))
        assertEquals("", TaxonomyNames.toSimplified(""))
    }

    // ---------------------------------------------------------- 属名规范化

    /**
     * **属名优先挑带「属」字的那条**（v1.0.2.1018 实机实测发现）。
     *
     * 真站对 `Lavandula` 的 commonNames 是：
     *     ["薰衣草", "薰衣草屬"]
     * **首项缺「属」字**，次项才是规范的属名。
     *
     * 直接取第一条，档案里就会出现「薰衣草」这种写法 ——
     * 而物种名也叫「薰衣草」，两者撞在一起，科属展示与比对都含糊。
     */
    @Test
    fun `属名优先挑带属字的`() {
        val lavender = """
            {
              "genus": [
                { "score": 0.9,
                  "genus": { "scientificName": "Lavandula",
                             "commonNames": ["薰衣草", "薰衣草屬"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(JSONObject(lavender))
        assertEquals("薰衣草属", names.displayOf("Lavandula"))
    }

    /** 科名不做「属」后缀处理 —— 科就是「XX科」 */
    @Test
    fun `科名不会被加成属`() {
        val fam = """
            {
              "family": [
                { "score": 0.9,
                  "family": { "scientificName": "Lamiaceae",
                              "commonNames": ["唇形科"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(JSONObject(fam))
        assertEquals("唇形科", names.displayOf("Lamiaceae"))
    }

    /**
     * **绝不能给任意名字无脑补「属」字**。
     *
     * 接口只给 `["薰衣草"]`（没有规范的「薰衣草屬」）时必须原样用 ——
     * 补成「薰衣草属」等于发明 Pl@ntNet 没给的名字。
     */
    @Test
    fun `没有规范属名时不编造`() {
        val onlyPlain = """
            {
              "genus": [
                { "score": 0.9,
                  "genus": { "scientificName": "Lavandula",
                             "commonNames": ["薰衣草"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(JSONObject(onlyPlain))
        assertEquals("薰衣草", names.displayOf("Lavandula"))
    }

    /**
     * 科没有中文名时（实测 `Asteraceae` 只给
     * `["Composite","Daisy Family","Sunflowers"]`）→ 退回拉丁名，
     * **绝不空着**：空值会让「同科」判据彻底失效。
     */
    @Test
    fun `科无中文名时退回拉丁名`() {
        val onlyEnglish = """
            {
              "family": [
                { "score": 0.88,
                  "family": { "scientificName": "Asteraceae",
                              "commonNames": ["Composite", "Daisy Family"] } }
              ]
            }
        """.trimIndent()

        val names = TaxonomyNames.fromOtherResults(JSONObject(onlyEnglish))
        assertEquals("Asteraceae", names.displayOf("Asteraceae"))
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

    // ---------------------------------------------------------- 404 的两种含义

    /**
     * Pl@ntNet 的 404 有**两种完全相反**的含义，只能靠 body 分辨：
     *
     * - 端点/项目库名写错 → 该让用户改 Base URL
     * - `"Species not found"` → **配置完全正确**，只是图里没有植物
     *
     * 混为一谈时，用户拍了一张风景照会看到「请确认 Base URL 填写正确」，
     * 于是跑去反复改一个正确的地址 —— 把用户引向错误的排查方向，
     * 比不提示更糟。
     */
    @Test
    fun `Species not found 判定为图里没植物`() {
        assertTrue(
            PlantNetProvider.isSpeciesNotFound(
                """{"statusCode":404,"error":"Not Found","message":"Species not found"}""",
            ),
        )
        assertTrue(PlantNetProvider.isSpeciesNotFound("""{"message":"SPECIES NOT FOUND"}"""))
    }

    /** 端点写错时的 404 不能被误判成「没植物」，否则用户就不会去改地址了 */
    @Test
    fun `端点错的 404 不等于没植物`() {
        assertFalse(
            PlantNetProvider.isSpeciesNotFound(
                """{"statusCode":404,"error":"Not Found","message":"Not found"}""",
            ),
        )
        assertFalse(PlantNetProvider.isSpeciesNotFound("""{"message":"Server Error"}"""))
        assertFalse(PlantNetProvider.isSpeciesNotFound(""))
    }
}
