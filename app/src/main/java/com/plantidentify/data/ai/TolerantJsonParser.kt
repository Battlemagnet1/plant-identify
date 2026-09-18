package com.plantidentify.data.ai

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

    /** 步骤 5：字符串容错（数字、布尔也会被转成字符串） */
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

    /** 候选植物最多保留几条 —— 太多反而干扰判断 */
    private const val MAX_ALTERNATIVES = 5
}
