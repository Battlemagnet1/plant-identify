package com.plantidentify.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清洗顾问 prompt 与解析的单测。
 *
 * 这两块是「一次花钱的调用」的两端，写错的代价不对称：
 * prompt 写错 → 模型给出没用的结论；解析写错 → **结论被静默丢掉**，
 * 表现为「AI 复核跑完了，但界面什么都没变」，而且不报错。
 */
class CleaningPromptTest {

    private fun rec(
        id: Long,
        name: String,
        latin: String? = null,
        family: String? = null,
        genus: String? = null,
        description: String? = null,
    ) = com.plantidentify.domain.cleaning.RecordSnapshot(
        id = id, name = name, latinName = latin, family = family, genus = genus,
        description = description,
    )

    private fun group(id: Long = 12) = PromptBuilder.CleaningGroup(
        issueId = id,
        first = rec(1, "紫薇", "Lagerstroemia indica", "千屈菜科", "紫薇属"),
        second = rec(2, "百日红"),
        localReason = "中文名相似度 57%",
    )

    @Test
    fun `prompt 里必须带上每组的 id`() {
        // 解析侧靠 group_id 把结论对回问题。prompt 里没有它，
        // 模型只能自己编一个 —— 那批结论会全部被丢弃
        val prompt = PromptBuilder.buildCleaningPrompt(listOf(group(12), group(37)))
        assertTrue(prompt.contains("### 候选 12"))
        assertTrue(prompt.contains("### 候选 37"))
    }

    @Test
    fun `prompt 必须明确禁止靠名称相似判同一种`() {
        // 这是灰区被送进 AI 的原因所在。不点明的话，模型看到 prompt 里
        // 给的相似度就会直接附和 —— 那等于花了一次钱做了一次本地判定
        val prompt = PromptBuilder.buildCleaningPrompt(listOf(group()))
        assertTrue(prompt.contains("不要因为名称相似就判同一种"))
    }

    @Test
    fun `prompt 里字段的顺序是 名称-学名-科-属`() {
        val prompt = PromptBuilder.buildCleaningPrompt(listOf(group()))
        val line = prompt.lines().first { it.startsWith("- 甲：") }
        assertTrue(
            "可靠度高的字段要排在前面：$line",
            line.indexOf("Lagerstroemia indica") < line.indexOf("千屈菜科"),
        )
    }

    @Test
    fun `过长的简介在句读处截断而不是硬切`() {
        // 硬切可能把「不是同一种」这种关键否定词切掉一半
        val long = "常绿乔木。" + "很长的描述。".repeat(60)
        val prompt = PromptBuilder.buildCleaningPrompt(
            listOf(group().copy(first = group().first.copy(description = long))),
        )
        val descLine = prompt.lines().first { it.trimStart().startsWith("简介：") }
        assertTrue("截断后不该以半个句子结尾：${descLine.takeLast(10)}", descLine.endsWith("。"))
        assertTrue(descLine.length < 260)
    }

    // ---------------- 解析 ----------------

    @Test
    fun `正常返回能解析出结论`() {
        val raw = """
            {
              "results": [
                {"group_id": "12", "type": "POSSIBLE_DUPLICATE", "is_same": true,
                 "confidence": 0.96, "reason": "拉丁学名一致"}
              ]
            }
        """.trimIndent()
        val parsed = TolerantJsonParser.parseCleaningResults(raw, setOf(12L))
        val ok = parsed as TolerantJsonParser.CleaningParseResult.Ok
        val verdict = ok.verdicts.single()
        assertEquals(12L, verdict.issueId)
        assertEquals(com.plantidentify.domain.cleaning.CleaningVerdictType.POSSIBLE_DUPLICATE, verdict.type)
        assertTrue(verdict.isSame)
        assertTrue(verdict.confirmsDuplicate)
        assertEquals(0.96, verdict.confidence!!, 1e-9)
    }

    @Test
    fun `group_id 是数字也能认`() {
        val raw = """{"results":[{"group_id": 12, "type":"NOT_SAME","is_same":false}]}"""
        val ok = TolerantJsonParser.parseCleaningResults(raw, setOf(12L))
            as TolerantJsonParser.CleaningParseResult.Ok
        assertEquals(12L, ok.verdicts.single().issueId)
    }

    @Test
    fun `对不上本地候选的条目丢弃并计数 不报错`() {
        // 整批重问的代价是 20 组全再付一次钱，而收益只是那一条
        val raw = """
            {"results":[
              {"group_id":"12","type":"NOT_SAME","is_same":false},
              {"group_id":"999","type":"NOT_SAME","is_same":false}
            ]}
        """.trimIndent()
        val ok = TolerantJsonParser.parseCleaningResults(raw, setOf(12L))
            as TolerantJsonParser.CleaningParseResult.Ok
        assertEquals(1, ok.verdicts.size)
        assertEquals(1, ok.dropped)
        assertTrue(ok.note!!.contains("1 组"))
    }

    @Test
    fun `带代码块围栏的返回也能解析`() {
        val raw = "```json\n{\"results\":[{\"group_id\":\"12\",\"type\":\"NOT_SAME\",\"is_same\":false}]}\n```"
        val ok = TolerantJsonParser.parseCleaningResults(raw, setOf(12L))
            as TolerantJsonParser.CleaningParseResult.Ok
        assertEquals(1, ok.verdicts.size)
    }

    @Test
    fun `认不出来的 type 当成并非同一株而不是丢弃`() {
        // 丢弃会让这条候选永远留在待处理里：用户点多少次检查都是同样结果，
        // 表现为「这条问题怎么都处理不完」。
        //
        // 测试数据刻意用**完全认不出来**的词：初版这里写了
        // "SAME_PLANT_MAYBE"，它含 SAME，本来就会被认成「疑似重复」——
        // 那样测的其实是「能认出来」，而不是「认不出来怎么办」
        val raw = """{"results":[{"group_id":"12","type":"WEIRD_VERDICT_XYZ","is_same":false}]}"""
        val ok = TolerantJsonParser.parseCleaningResults(raw, setOf(12L))
            as TolerantJsonParser.CleaningParseResult.Ok
        assertEquals(
            com.plantidentify.domain.cleaning.CleaningVerdictType.NOT_SAME,
            ok.verdicts.single().type,
        )
    }

    @Test
    fun `模型自造的近义词要尽力认出来`() {
        // 与上一条互补：认不出来才兜底，认得出的一律按语义归类 ——
        // 模型把结论写成 SAME_PLANT / 同一株 是常见事
        val mapping = mapOf(
            "SAME_PLANT" to com.plantidentify.domain.cleaning.CleaningVerdictType.POSSIBLE_DUPLICATE,
            "DUPLICATE" to com.plantidentify.domain.cleaning.CleaningVerdictType.POSSIBLE_DUPLICATE,
            "ALIAS" to com.plantidentify.domain.cleaning.CleaningVerdictType.ALIAS_RELATION,
            "DATA_CONFLICT" to com.plantidentify.domain.cleaning.CleaningVerdictType.DATA_CONFLICT,
            "NOT_SAME_PLANT" to com.plantidentify.domain.cleaning.CleaningVerdictType.NOT_SAME,
        )
        mapping.forEach { (literal, expected) ->
            val raw = """{"results":[{"group_id":"12","type":"$literal","is_same":false}]}"""
            val ok = TolerantJsonParser.parseCleaningResults(raw, setOf(12L))
                as TolerantJsonParser.CleaningParseResult.Ok
            assertEquals(literal, expected, ok.verdicts.single().type)
        }
    }

    @Test
    fun `布尔字段的各种写法都要认`() {
        // 模型把 true 写成 "是" / 1 都是常见事
        val cases = mapOf(
            "\"true\"" to true,
            "\"是\"" to true,
            "1" to true,
            "\"false\"" to false,
            "\"否\"" to false,
            "0" to false,
        )
        cases.forEach { (literal, expected) ->
            val raw = """{"results":[{"group_id":"12","type":"POSSIBLE_DUPLICATE","is_same":$literal}]}"""
            val ok = TolerantJsonParser.parseCleaningResults(raw, setOf(12L))
                as TolerantJsonParser.CleaningParseResult.Ok
            assertEquals("is_same=$literal", expected, ok.verdicts.single().isSame)
        }
    }

    @Test
    fun `缺 is_same 时按 type 推断`() {
        val same = TolerantJsonParser.parseCleaningResults(
            """{"results":[{"group_id":"12","type":"POSSIBLE_DUPLICATE"}]}""",
            setOf(12L),
        ) as TolerantJsonParser.CleaningParseResult.Ok
        assertTrue(same.verdicts.single().isSame)

        val notSame = TolerantJsonParser.parseCleaningResults(
            """{"results":[{"group_id":"12","type":"NOT_SAME"}]}""",
            setOf(12L),
        ) as TolerantJsonParser.CleaningParseResult.Ok
        assertTrue(!notSame.verdicts.single().isSame)
    }

    @Test
    fun `空内容与找不到 JSON 都报失败`() {
        assertTrue(
            TolerantJsonParser.parseCleaningResults("   ", setOf(1L))
                is TolerantJsonParser.CleaningParseResult.Failed,
        )
        assertTrue(
            TolerantJsonParser.parseCleaningResults("模型说它不想回答", setOf(1L))
                is TolerantJsonParser.CleaningParseResult.Failed,
        )
    }

    @Test
    fun `results 为空数组报失败而不是空成功`() {
        // 「成功的空结果」会让调用方以为跑完了，而那 20 组其实一个都没判，
        // 且下次检查时因为 aiUsed 已经是 true 而不再入队 —— 永远没人管了
        assertTrue(
            TolerantJsonParser.parseCleaningResults("""{"results":[]}""", setOf(1L))
                is TolerantJsonParser.CleaningParseResult.Failed,
        )
    }

    @Test
    fun `只有名称差异与明确否定会自动结案`() {
        val alias = com.plantidentify.domain.cleaning.CleaningVerdict(
            issueId = 1,
            type = com.plantidentify.domain.cleaning.CleaningVerdictType.ALIAS_RELATION,
            isSame = false,
        )
        val conflict = alias.copy(type = com.plantidentify.domain.cleaning.CleaningVerdictType.DATA_CONFLICT)
        val notSame = alias.copy(type = com.plantidentify.domain.cleaning.CleaningVerdictType.NOT_SAME)

        assertTrue("异名无需处理", alias.dismissesIssue)
        assertTrue("明确否定无需处理", notSame.dismissesIssue)
        assertTrue("字段冲突要用户去改，不能自动结案", !conflict.dismissesIssue)
    }
}
