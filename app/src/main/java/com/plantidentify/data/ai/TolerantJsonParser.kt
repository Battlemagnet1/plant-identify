package com.plantidentify.data.ai

import com.plantidentify.domain.cleaning.CleaningVerdict
import com.plantidentify.domain.cleaning.CleaningVerdictType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视觉识别返回内容的容错解析（分析报告 Part 3.2 的 6 步链路中的第 3、4、6 步）。
 *
 * ## 为什么不能靠 `response_format` 就够了
 *
 * 各家对 `response_format: {"type":"json_object"}` 的支持程度并不一致：
 *  - OpenAI 支持良好
 *  - Qwen 支持面较广，但官方明确注明最新的 qwen-vl-max 快照版**不在支持范围**
 *  - 豆包有该参数但对视觉模型没有严格 schema 保证
 *  - GLM-4V 无 schema 强制机制
 *
 * 也就是说，只要用户换一家服务，就必须能容忍不规范输出。
 * 这个解析器的存在本身就是一条架构结论，而不是「多加一层保险」。
 *
 * ## 六级降级
 *
 * ```
 * 1. 剥离 ```json / ``` 代码块围栏
 * 2. 整体作为 JSON 解析
 * 3. 失败 → 括号配对扫描，提取第一个完整的 {...}（能正确处理嵌套与字符串内的括号）
 * 4. 字段名归一化匹配：latin_name / latinName / Latin_Name 都认
 * 5. 类型容错：数字给字符串、数组给单值，都尽量救回来
 * 6. 都失败 → 返回 Failed，由调用方降级为「展示原始文本」
 * ```
 *
 * 第 4、5 步是实践中最需要的：JSON 本身合法但键名写法不同，
 * 严格解析会得到一个全是 null 的「假成功」—— 比直接失败更难排查。
 */
object TolerantJsonParser {

    /** 解析尝试的结果 */
    sealed interface ParseAttempt {

        /** 完全解析成功 */
        data class Success(val result: RecognitionResult) : ParseAttempt

        /**
         * 降级解析成功：拿到了名称，但部分字段缺失或结构不规范。
         * 调用方应展示 [note] 让用户知道结果是尽力提取的。
         */
        data class Degraded(
            val result: RecognitionResult,
            val note: String,
        ) : ParseAttempt

        /** 完全失败：调用方应降级为展示原始文本（验收标准 ⑤） */
        data class Failed(val reason: String) : ParseAttempt
    }

    /**
     * 解析模型返回的文本。
     *
     * @param raw 模型返回的 content 原文
     */
    fun parse(raw: String): ParseAttempt {
        val text = raw.trim()
        if (text.isEmpty()) {
            return ParseAttempt.Failed("模型返回了空内容")
        }

        // 步骤 1–2：清洗围栏后直接解析
        val cleaned = stripCodeFence(text)
        parseObject(cleaned)?.let { return it }

        // 步骤 3：括号配对扫描，从混杂文本里挖出第一个完整 JSON 对象
        val extracted = extractFirstJsonObject(cleaned)
        if (extracted != null && extracted != cleaned) {
            parseObject(extracted)?.let { attempt ->
                return when (attempt) {
                    is ParseAttempt.Success -> ParseAttempt.Degraded(
                        result = attempt.result,
                        note = "模型在 JSON 之外还输出了其他文字，已提取其中的结果",
                    )
                    else -> attempt
                }
            }
        }

        // 步骤 6：彻底失败
        return ParseAttempt.Failed("返回内容中找不到可解析的 JSON 结构")
    }

    /** 文字分析的解析结果 */
    sealed interface AnalysisParseResult {

        /**
         * @param note 非空表示有字段缺失（模型只返回了一部分），UI 可据此提示
         */
        data class Ok(val analysis: PlantAnalysis, val note: String? = null) : AnalysisParseResult

        data class Failed(val reason: String) : AnalysisParseResult
    }

    /**
     * 解析文字分析（植物百科）的返回。
     *
     * 与识别结果共用同一套容错手段（剥围栏、括号配对扫描、键名归一化），
     * 只是字段不同。
     *
     * **这里不支持「降级成半结构化」**：识别结果里「植物名称」是可以单独成立的
     * 最小信息，而百科的七个字段没有哪个能独立代表一次成功的分析 ——
     * 一个都拿不到就如实报失败，由调用方按「文字分析失败」处理
     * （此时基础识别结果照常落库，规格书第三十节）。
     */
    fun parseAnalysis(raw: String): AnalysisParseResult {
        val text = raw.trim()
        if (text.isEmpty()) {
            return AnalysisParseResult.Failed("模型返回了空内容")
        }

        val cleaned = stripCodeFence(text)
        parseAnalysisObject(cleaned)?.let { return it }

        val extracted = extractFirstJsonObject(cleaned)
        if (extracted != null && extracted != cleaned) {
            parseAnalysisObject(extracted)?.let { return it }
        }

        return AnalysisParseResult.Failed("返回内容中找不到可解析的 JSON 结构")
    }

    private fun parseAnalysisObject(text: String): AnalysisParseResult? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null

        val analysis = PlantAnalysis(
            commonNames = root.readString(
                "common_names", "commonnames", "common_name", "commonname",
                "alias", "aliases", "other_names", "俗称", "别名", "常用名", "常用名称",
            ),
            description = root.readString(
                "description", "intro", "introduction", "简介", "植物简介", "介绍",
            ),
            morphologicalFeatures = root.readString(
                "morphological_features", "morphologicalfeatures", "morphology",
                "形态特征", "形态",
            ),
            growthHabits = root.readString(
                "growth_habits", "growthhabits", "habits", "生长习性", "习性",
            ),
            floweringPeriod = root.readString(
                "flowering_period", "floweringperiod", "flowering", "花期",
            ),
            fruitingPeriod = root.readString(
                "fruiting_period", "fruitingperiod", "fruiting", "果期",
            ),
            landscapeUses = root.readString(
                "landscape_uses", "landscapeuses", "uses", "园林用途", "用途",
            ),
            careAdvice = root.readString(
                "care_advice", "careadvice", "care", "养护建议", "养护",
            ),
            pestControl = root.readString(
                "pest_control", "pestcontrol", "pests", "disease_control",
                "pest_and_disease", "病虫害防治", "病虫害防治建议", "病虫害", "防治",
            ),
        )

        if (analysis.isEmpty) {
            return AnalysisParseResult.Failed("JSON 中没有任何可用的内容字段")
        }

        val got = analysis.presentFields.size
        return AnalysisParseResult.Ok(
            analysis = analysis,
            note = if (got < ANALYSIS_FIELD_COUNT) {
                "模型只返回了 $got/$ANALYSIS_FIELD_COUNT 个字段，其余为空"
            } else {
                null
            },
        )
    }

    // ---------------- 内部实现 ----------------

    /** 步骤 1：剥离 markdown 代码块围栏 */
    internal fun stripCodeFence(text: String): String {
        var s = text.trim()

        // ```json ... ``` / ```JSON ... ``` / ``` ... ```
        val fence = Regex("^```[A-Za-z]*\\s*\\n?([\\s\\S]*?)\\n?```$")
        fence.find(s)?.let { match ->
            s = match.groupValues[1].trim()
        }

        // 容忍只有开头围栏、结尾被截断的情况
        if (s.startsWith("```")) {
            s = s.removePrefix("```")
                .removePrefix("json")
                .removePrefix("JSON")
                .removePrefix("\n")
                .trim()
        }
        if (s.endsWith("```")) {
            s = s.removeSuffix("```").trim()
        }

        return s
    }

    /**
     * 步骤 3：括号配对扫描，提取第一个语法完整的 JSON 对象。
     *
     * 用扫描而不是正则的原因：JSON 是嵌套结构，`{...}` 里还有 `{...}`，
     * 且字符串值里可能出现 `{` `}` 与转义引号 —— 正则无法可靠处理这些情况。
     */
    internal fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]

            if (escaped) {
                escaped = false
                continue
            }

            when {
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 步骤 2 + 4 + 5：解析对象并做字段级容错 */
    private fun parseObject(text: String): ParseAttempt? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null

        // 有些服务会把结果包一层（{"result": {...}} / {"data": {...}}）
        val obj = locatePlantObject(root)

        val name = obj.readString("name", "chinese_name", "chinesename", "common_name", "中文名", "名称")
            ?.takeIf { it.isNotBlank() }
            ?: return ParseAttempt.Failed("JSON 中缺少植物名称字段")

        val result = RecognitionResult(
            name = name,
            latinName = obj.readString("latin_name", "latinname", "scientific_name", "学名", "拉丁学名"),
            family = obj.readString("family", "科"),
            genus = obj.readString("genus", "属"),
            category = obj.readString("category", "type", "plant_type", "植物类型", "类型"),
            confidence = obj.readConfidence(),
            evidence = obj.readStringList("evidence", "判定依据", "依据"),
            missingInformation = obj.readStringList(
                "missing_information", "missinginformation", "missing_info", "缺失信息",
            ),
            alternatives = obj.readAlternatives(),
            conflicts = obj.readStringList("conflicts", "conflict", "冲突"),
        )

        // 判定是否为降级结果：核心字段（科/属/置信度）任一缺失都提示用户
        val missingCore = buildList {
            if (result.latinName.isNullOrBlank()) add("拉丁学名")
            if (result.family.isNullOrBlank()) add("科")
            if (result.genus.isNullOrBlank()) add("属")
        }

        return if (missingCore.isEmpty() && result.confidence > 0.0) {
            ParseAttempt.Success(result)
        } else {
            ParseAttempt.Degraded(
                result = result,
                note = "模型未返回完整字段（缺 ${missingCore.joinToString("、").ifEmpty { "置信度" }}），" +
                    "已展示可提取的部分",
            )
        }
    }

    /**
     * 找到真正承载植物信息的对象。
     *
     * 处理两种常见包装：顶层直接是结果，或结果被放在
     * `result` / `data` / `output` / `plant` 之类的单层对象里。
     */
    private fun locatePlantObject(root: JSONObject): JSONObject {
        val nameKeys = listOf("name", "chinese_name", "latin_name", "family", "genus")
        if (nameKeys.any { containsNormalizedKey(root, it) }) return root

        listOf("result", "data", "output", "plant", "answer", "识别结果", "结果").forEach { wrapper ->
            val child = root.normalizedGet(wrapper)
            if (child is JSONObject) return child
        }

        // 再深一层，找第一个含 name 字段的子对象
        root.keys().forEach { key ->
            val child = root.opt(key)
            if (child is JSONObject && nameKeys.any { containsNormalizedKey(child, it) }) {
                return child
            }
        }

        return root
    }

    /**
     * 步骤 4：按归一化键名取值。
     *
     * 归一化 = 去下划线 + 转小写，因此 `latin_name`、`latinName`、
     * `Latin_Name`、`LATINNAME` 全部命中同一个键。
     */
    private fun JSONObject.normalizedGet(vararg candidates: String): Any? {
        // 先精确命中（最快路径）
        candidates.forEach { key ->
            if (has(key)) return opt(key)
        }
        // 再归一化扫描
        val normalizedTargets = candidates.map { it.normalizeKey() }.toSet()
        keys().forEach { actual ->
            if (actual.normalizeKey() in normalizedTargets) {
                return opt(actual)
            }
        }
        return null
    }

    private fun containsNormalizedKey(obj: JSONObject, key: String): Boolean {
        val target = key.normalizeKey()
        // 注意：Android 的 org.json.JSONObject.keys() 返回的是 Iterator<String>，
        // 不是 Collection，因此没有 any {} —— 需要先转成 Sequence
        return obj.keys().asSequence().any { it.normalizeKey() == target }
    }

    private fun String.normalizeKey(): String =
        lowercase().replace("_", "").replace("-", "").replace(" ", "")

    // ---------------- 数据清洗顾问（Phase 3）----------------

    /** 一批清洗判定的解析结果 */
    sealed interface CleaningParseResult {

        /**
         * 解析成功。
         *
         * @param verdicts 有效判定，**只含本地候选里真实存在的 group_id**
         * @param dropped 被丢弃的条数（group_id 对不上、字段缺失等）
         */
        data class Ok(
            val verdicts: List<CleaningVerdict>,
            val dropped: Int = 0,
            val note: String? = null,
        ) : CleaningParseResult

        data class Failed(val reason: String) : CleaningParseResult
    }

    /**
     * 解析「数据清洗顾问」的批量结论。
     *
     * ## 两条刻意的宽容
     *
     * 1. **逐条 try，不因为一条坏掉丢掉整批**。一批 20 组里有一条 group_id
     *    对不上就整批重问，代价是 20 组全再付一次钱，而收益只是那一条。
     * 2. **对不上的直接丢弃并计数，不报错**。调用方拿到 [CleaningParseResult.Ok]
     *    但 `dropped > 0` 时应当把那些组**留在待处理状态**（下次再问），
     *    而不是标成「已判定」—— 漏判比错判容易发现。
     *
     * `type` 认不出来时**不丢弃**：宁可当成 `NOT_SAME` 让它进人工复核，
     * 也不要因为模型换了个词就把一条真实重复放过去。见 [readVerdictType]。
     */
    fun parseCleaningResults(
        raw: String,
        knownIssueIds: Set<Long>,
    ): CleaningParseResult {
        val text = raw.trim()
        if (text.isEmpty()) return CleaningParseResult.Failed("模型返回了空内容")

        val cleaned = stripCodeFence(text)
        parseCleaningObject(cleaned, knownIssueIds)?.let { return it }

        val extracted = extractFirstJsonObject(cleaned)
        if (extracted != null && extracted != cleaned) {
            parseCleaningObject(extracted, knownIssueIds)?.let { return it }
        }
        return CleaningParseResult.Failed("返回内容中找不到可解析的 JSON 结构")
    }

    private fun parseCleaningObject(
        text: String,
        knownIssueIds: Set<Long>,
    ): CleaningParseResult? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null

        // results 数组的键名容错：模型可能写成 result / items / data / 判定
        val array = listOf("results", "result", "items", "data", "判定", "结果")
            .firstNotNullOfOrNull { key ->
                root.normalizedGet(key) as? JSONArray
            } ?: return null

        val verdicts = mutableListOf<CleaningVerdict>()
        var dropped = 0

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: run { dropped++; continue }
            // group_id 可能是 "12" 或 12：prompt 里要求原样返回，
            // 但模型两种都写过，所以两种都要认
            val issueId = item.readString("group_id", "groupid", "id", "组号", "编号")
                ?.trimStart('0')
                ?.toLongOrNull()
                ?: run { dropped++; continue }

            if (issueId !in knownIssueIds) {
                dropped++
                continue
            }

            val type = readVerdictType(item)
            val same = item.readBooleanLike("is_same", "issame", "same", "是否同种", "是同一种")
                ?: type.defaultSame

            verdicts += CleaningVerdict(
                issueId = issueId,
                type = type,
                isSame = same,
                confidence = item.readConfidence().takeIf { it > 0.0 },
                reason = item.readString("reason", "explanation", "why", "理由", "原因"),
            )
        }

        if (verdicts.isEmpty() && dropped == 0) {
            return CleaningParseResult.Failed("results 数组为空")
        }
        return CleaningParseResult.Ok(
            verdicts = verdicts,
            dropped = dropped,
            note = if (dropped > 0) "$dropped 组对不上本地候选，已跳过" else null,
        )
    }

    /**
     * 认得出来的类型，认不出来一律当 [CleaningVerdictType.NOT_SAME]。
     *
     * 为什么**不丢弃**：认不出来通常意味着模型自造了一个词
     * （`DUPLICATE` / `SAME_PLANT` / `不同种`…）。丢弃会让这条候选
     * 一直留在待处理里，而用户下一次检查时它还是同样地被丢弃 ——
     * 表现为「这条问题怎么点检查都处理不完」。
     */
    private fun readVerdictType(item: JSONObject): CleaningVerdictType {
        val raw = item.readString("type", "verdict", "结论", "类型")?.uppercase() ?: return CleaningVerdictType.NOT_SAME
        return when {
            raw.contains("ALIAS") || raw.contains("异名") || raw.contains("别名") ->
                CleaningVerdictType.ALIAS_RELATION
            raw.contains("CONFLICT") || raw.contains("冲突") ->
                CleaningVerdictType.DATA_CONFLICT
            raw.contains("NOT_SAME") || raw.contains("NOTSAME") || raw.contains("不同") ->
                CleaningVerdictType.NOT_SAME
            raw.contains("SAME") || raw.contains("DUP") || raw.contains("重复") || raw.contains("同一") ->
                CleaningVerdictType.POSSIBLE_DUPLICATE
            else -> CleaningVerdictType.NOT_SAME
        }
    }

    /** 布尔容错：`true` / `"true"` / `"是"` / `1` 都要认 */
    private fun JSONObject.readBooleanLike(vararg candidates: String): Boolean? =
        when (val value = normalizedGet(*candidates)) {
            null, JSONObject.NULL -> null
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "yes", "y", "1", "是", "同一种", "相同" -> true
                "false", "no", "n", "0", "否", "不是", "不同" -> false
                else -> null
            }
            else -> null
        }

    /** 字符串容错（数字、布尔也会被转成字符串） */
    private fun JSONObject.readString(vararg candidates: String): String? {
        val value = normalizedGet(*candidates) ?: return null
        val text = when (value) {
            is String -> value
            JSONObject.NULL -> return null
            else -> value.toString()
        }
        return text.trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    /**
     * 步骤 5：置信度容错。
     *
     * 模型可能返回：
     *  - `0.91`（标准）
     *  - `"0.91"`（字符串）
     *  - `91` 或 `"91%"`（百分数）—— 大于 1 时按百分数处理
     *  - 缺失 → 0.0
     */
    private fun JSONObject.readConfidence(): Double {
        val value = normalizedGet(
            "confidence", "confidence_score", "score", "置信度", "可信度",
        ) ?: return 0.0

        val raw = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().removeSuffix("%").toDoubleOrNull() ?: return 0.0
            else -> return 0.0
        }

        return when {
            raw < 0.0 -> 0.0
            raw > 1.0 -> (raw / 100.0).coerceAtMost(1.0)
            else -> raw
        }
    }

    /** 步骤 5：字符串列表容错（单字符串、单对象都要能救） */
    private fun JSONObject.readStringList(vararg candidates: String): List<String> {
        val value = normalizedGet(*candidates) ?: return emptyList()
        return when (value) {
            is JSONArray -> buildList {
                for (i in 0 until value.length()) {
                    when (val item = value.opt(i)) {
                        is String -> item.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                        is JSONObject -> item.optString("name")
                            .trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                        JSONObject.NULL, null -> Unit
                        else -> item.toString().trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
            }
            is String -> listOf(value.trim()).filter { it.isNotEmpty() }
            JSONObject.NULL -> emptyList()
            else -> listOf(value.toString())
        }
    }

    /** 候选植物列表容错：既支持 `[{name, confidence}]`，也支持 `["大花紫薇"]` */
    private fun JSONObject.readAlternatives(): List<RecognitionResult.Alternative> {
        val value = normalizedGet(
            "possible_alternatives", "possiblealternatives", "alternatives",
            "alternatives_list", "候选植物", "其他可能",
        ) ?: return emptyList()

        val items: List<Any?> = when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            is String -> listOf(value)
            JSONObject.NULL -> emptyList()
            else -> listOf(value)
        }

        return items.mapNotNull { item ->
            when (item) {
                is JSONObject -> {
                    val name = item.optString("name").trim()
                    if (name.isEmpty()) {
                        null
                    } else {
                        RecognitionResult.Alternative(
                            name = name,
                            confidence = runCatching {
                                item.optDouble("confidence", 0.0)
                            }.getOrDefault(0.0).let { if (it > 1.0) it / 100.0 else it },
                        )
                    }
                }
                is String -> item.trim().takeIf { it.isNotEmpty() }
                    ?.let { RecognitionResult.Alternative(it, 0.0) }
                else -> null
            }
        }.take(MAX_ALTERNATIVES)
    }

    /**
     * 百科内容字段总数，用于判断模型是否漏了字段。
     *
     * 口径是 [PlantAnalysis.presentFields] 的字段数（不含 common_names ——
     * 它不在「植物百科」区块里渲染，见该属性的注释）。
     * 加字段时这两个数字必须一起改，否则提示会变成
     * 「模型只返回了 8/7 个字段」这种自相矛盾的话。
     */
    private const val ANALYSIS_FIELD_COUNT = 8

    /** 候选植物最多保留几条 —— 太多反而干扰判断 */
    private const val MAX_ALTERNATIVES = 5
}
