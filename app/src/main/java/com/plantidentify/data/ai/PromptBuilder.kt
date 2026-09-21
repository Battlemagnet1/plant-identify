package com.plantidentify.data.ai

import com.plantidentify.data.local.entity.ImageRole
import com.plantidentify.domain.cleaning.RecordSnapshot

/**
 * 识别 prompt 的策略变体。
 *
 * ## 为什么做成可切换
 *
 * 分析报告 Part 3.4 设计的验证实验要在三种策略间做对照，
 * 而实验的核心问题（多图输入是否真的优于单图、角色标注是否缓解退化）
 * 只有用真实样本跑出来才有答案。
 *
 * 把这个开关做进 App，而不是只在实验室里跑 Python：
 * 用户可以直接拿自己的照片、自己的 API Key 跑 A/B 对照，
 * 结论出来后只需改 [DEFAULT] 一个值。
 *
 * ```
 * A 组  MULTI_PLAIN       多图原样，只说明「这是同一株植物」
 * B 组  MULTI_ROLE_TAGGED 多图 + 逐张标注拍摄部位（当前默认）
 * C 组  逐图提取后汇总    需要多次调用，Phase 3 不实现
 * ```
 *
 * C 组暂不实现的原因：它把一次请求变成 N+1 次，成本与耗时都成倍上升，
 * 是否值得取决于 A/B 的实测差距。在拿到实验数据前就实现它会本末倒置。
 */
enum class PromptStrategy(
    val displayName: String,
    val description: String,
) {
    /** A 组：多图原样 */
    MULTI_PLAIN(
        displayName = "多图原样（A 组）",
        description = "只告知模型这些是同一株植物的照片，不使用部位标注",
    ),

    /**
     * B 组：多图 + 角色标注 —— 当前默认。
     *
     * 依据：多图输入时模型倾向于退化为「逐图独立描述」，一个重要原因是
     * 它需要自己猜测每张图该看什么部位。由用户显式标注后，
     * prompt 可以把「跨图综合」这个难题拆成若干「单图看特定部位」的简单子任务。
     */
    MULTI_ROLE_TAGGED(
        displayName = "多图 + 部位标注（B 组，推荐）",
        description = "逐张告知模型这张图拍的是哪个部位、该重点观察什么",
    ),
    ;

    companion object {
        /** 默认策略。验证实验结论出来后如需调整，只改这一处 */
        val DEFAULT: PromptStrategy = MULTI_ROLE_TAGGED

        fun fromName(name: String?): PromptStrategy =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 识别 prompt 的构造（规格书第四节、第五节）。
 *
 * 设计要点：
 *
 * 1. **把「联合判断」写成明确指令**
 *    只把多张图塞进请求里，模型很可能逐张描述。prompt 必须显式要求
 *    「综合全部照片给出一个结论」。
 *
 * 2. **要求它承认不确定**
 *    明确说明 confidence 是把握程度而非科学概率，并鼓励证据不足时给低分。
 *    否则模型倾向于一律给出 0.9 左右的高分，置信度就失去了筛选价值。
 *
 * 3. **重试时带上失败原因**
 *    容错链路的第 5 步：告诉模型「你上次的输出不是合法 JSON」，
 *    比原样重发一次命中率明显更高。
 */
object PromptBuilder {

    /**
     * 构造识别 prompt。
     *
     * @param roles 按发送顺序排列的每张图的部位标注
     * @param strategy 策略变体（见 [PromptStrategy]）
     * @param correction 重试时传入的纠正说明；首次请求为 null
     * @param placeHint 拍摄地点，作为弱先验；为 null 时 prompt 不提这一节
     */
    fun buildRecognitionPrompt(
        roles: List<ImageRole>,
        strategy: PromptStrategy = PromptStrategy.DEFAULT,
        correction: String? = null,
        placeHint: String? = null,
    ): String {
        val imageCount = roles.size
        return buildString {
            appendLine(ROLE_DEFINITION.trimIndent())
            appendLine()

            appendLine(
                "用户拍摄了同一株植物的 $imageCount 张照片，" +
                    "它们是这株植物在不同角度、不同部位下的观察结果。",
            )
            appendLine()

            if (strategy == PromptStrategy.MULTI_ROLE_TAGGED) {
                append(roleSection(roles))
                appendLine()
            }

            append(JOINT_INSTRUCTION.trimIndent())
            appendLine()
            appendLine()

            // 地点放在判定规则之后、输出格式之前。
            // 放在最前面的风险是它会先入为主地框住模型对图片的判断 ——
            // 而它本来就只是个参考。
            placeSection(placeHint)?.let { section ->
                appendLine(section)
                appendLine()
            }

            appendLine(JSON_SCHEMA_SPEC.trimIndent())
            appendLine()
            appendLine()

            // 单独再讲一遍 name 该怎么写。只靠 schema 里的字段说明不够 ——
            // 模型把那一整段当「格式要求」扫过去，仍会顺手写下俗称。
            appendLine(NAME_CONSISTENCY_RULE.trimIndent())
            appendLine()

            append(CONFIDENCE_GUIDE.trimIndent())
            appendLine()
            appendLine()

            append(INSUFFICIENT_GUIDE.trimIndent())

            if (correction != null) {
                appendLine()
                appendLine()
                append(
                    """
                    ## 重要：上次输出格式有误

                    $correction

                    请这次**只输出一个 JSON 对象**，不要代码块标记、不要任何解释文字。
                    第一个字符必须是 { ，最后一个字符必须是 } 。
                    """.trimIndent(),
                )
            }
        }
    }

    /**
     * 构造测试连接用的最小 prompt。
     *
     * 目的不是识别，而是验证「地址对不对、Key 有没有效、模型名存不存在、
     * 以及这个模型是否接受图片输入」这四件事。
     * 因此刻意用一张 1×1 的图片，让请求体尽可能小、费用尽可能低。
     */
    /**
     * 构造植物百科分析的 prompt（规格书第九节）。
     *
     * 三条设计要点：
     *
     * 1. **明确要求「不确定就留空」**
     *    模型写百科时有强烈的「填满每个字段」倾向，会编出看似合理但错误的内容。
     *    植物学领域尤其危险 —— 花期月份、养护细节编错了，用户很难发现。
     *    因此把「留空优于编造」写成显式指令。
     *
     * 2. **置信度低时改写成属/科的通用特征**
     *    如果识别本身只有 0.6 的把握，却按某个具体种去写，等于把不确定性
     *    放大成了确定性的错误内容。
     *
     * 3. **明令禁止下鉴定结论**
     *    对应规格书第三十一节「AI 结果不能完全信任」，避免文案越权。
     */
    fun buildAnalysisPrompt(
        name: String,
        latinName: String?,
        family: String?,
        genus: String?,
        category: String?,
        confidence: Double,
        evidence: List<String>,
        placeHint: String? = null,
    ): String {
        val facts = buildList {
            add("- 中文名：$name")
            latinName?.takeIf { it.isNotBlank() }?.let { add("- 拉丁学名：$it") }
            family?.takeIf { it.isNotBlank() }?.let { add("- 科：$it") }
            genus?.takeIf { it.isNotBlank() }?.let { add("- 属：$it") }
            category?.takeIf { it.isNotBlank() }?.let { add("- 植物类型：$it") }
            add("- 图像识别的置信度：${(confidence * 100).toInt()}%")
            if (evidence.isNotEmpty()) {
                add("- 识别依据：${evidence.joinToString("、")}")
            }
        }.joinToString("\n")

        return buildString {
            appendLine(ANALYST_ROLE.trimIndent())
            appendLine()
            appendLine("## 植物信息（已由图像识别给出）")
            appendLine()
            appendLine(facts)
            appendLine()
            appendLine(ANALYSIS_SCHEMA_SPEC.trimIndent())
            appendLine()
            appendLine(ANALYSIS_CONTENT_RULES.trimIndent())

            // 百科同样可以受益于地点：同一物种在南北方的花期、越冬表现差很多。
            // 但仍按弱先验处理 —— 不能因为「种在杭州」就把花期写成江浙的，
            // 用户可能只是在杭州拍到了引种栽培的植株。
            placeSection(placeHint)?.let { section ->
                appendLine()
                appendLine()
                append(section)
            }

            if (confidence < LOW_CONFIDENCE_FOR_ANALYSIS) {
                appendLine()
                appendLine()
                append(LOW_CONFIDENCE_ANALYSIS_RULE.trimIndent())
            }
        }
    }

    fun buildConnectivityPrompt(): String =
        "请只回答一个 JSON 对象：{\"ok\": true, \"model_ack\": \"已收到图片\"}。" +
            "不要输出任何其他文字。"

    // ---------------- 数据清洗顾问（Phase 3）----------------

    /**
     * 一次清洗请求里的一组候选。
     *
     * [issueId] 直接当 prompt 里的 `group_id` 用 —— 方案写的是 `001` 这样的
     * 批次内序号，这里刻意改成 id：序号需要在下标与问题之间做一层映射，
     * 一旦模型少返回一条（很常见），映射就整体错位，**把甲组的结论写到乙组上**。
     * 用 id 则不存在错位的可能，模型只要原样回填即可。
     */
    data class CleaningGroup(
        val issueId: Long,
        val first: RecordSnapshot,
        val second: RecordSnapshot,
        /** 本地判据（如「中文名相似度 57%」），给模型一个起点 */
        val localReason: String,
    )

    /**
     * 构造「数据清洗顾问」的 prompt：一次问一批「这两株是不是同一种」。
     *
     * ## 为什么必须强调「名称相似不足以判定」
     *
     * 本地六级判定里 0.55–0.90 那一段是灰区 —— 它之所以是灰区，
     * 正是因为**中文名的相似与同种之间没有稳定关系**：
     * 「悬铃木 / 悬铃树」是一物异名，而「紫薇 / 紫荆」是两种完全不同的植物。
     * 若不点明，模型很容易顺着本地判据（prompt 里给了相似度）直接附和。
     */
    fun buildCleaningPrompt(groups: List<CleaningGroup>): String = buildString {
        appendLine(CLEANING_ADVISOR_ROLE)
        appendLine()
        appendLine(CLEANING_JUDGE_RULES)
        appendLine()
        appendLine(CLEANING_SCHEMA_SPEC)
        appendLine()
        appendLine("## 待判定候选（共 ${groups.size} 组）")
        appendLine()
        groups.forEach { group ->
            appendLine("### 候选 ${group.issueId}")
            appendLine("- 甲：${describe(group.first)}")
            if (!group.first.description.isNullOrBlank()) {
                appendLine("  简介：${truncate(group.first.description, DESCRIPTION_LIMIT)}")
            }
            appendLine("- 乙：${describe(group.second)}")
            if (!group.second.description.isNullOrBlank()) {
                appendLine("  简介：${truncate(group.second.description, DESCRIPTION_LIMIT)}")
            }
            appendLine("- 本地判据：${group.localReason}")
            appendLine()
        }
    }

    /**
     * 一行描述：名称 / 学名 / 科 / 属 / 类型 / 置信度。
     *
     * 顺序不是随意的：从最可靠到最不可靠。模型对靠前的字段更愿意采信，
     * 把「中文名」放在最前面会让它过度依赖名称。
     */
    private fun describe(record: RecordSnapshot): String = buildList {
        add(record.name.ifBlank { "（无名称）" })
        add(record.latinName?.takeIf { it.isNotBlank() } ?: "（无学名）")
        add(record.family?.takeIf { it.isNotBlank() } ?: "（无科）")
        add(record.genus?.takeIf { it.isNotBlank() } ?: "（无属）")
        record.category?.takeIf { it.isNotBlank() }?.let { add(it) }
        add("置信度 ${"%.2f".format(record.confidence)}")
    }.joinToString(" / ")

    /**
     * 截断长文本。
     *
     * **在最后一个句读处截**而不是硬切：硬切可能把「不是同一种」这种
     * 关键否定词切掉一半，留下「不是同一」—— 那比不截还糟。
     */
    private fun truncate(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val head = text.take(limit)
        val cut = head.indexOfLast { it in "。；;.\n" }
        return if (cut > limit / 2) head.take(cut + 1) else "$head…"
    }

    /** 简介只截到 200 字：区分度集中在开头，结尾多是「园林用途」这类套话 */
    private const val DESCRIPTION_LIMIT = 200

    private val CLEANING_ADVISOR_ROLE = """
        你是一位植物分类学助手。下面若干组植物档案被本地算法判为「可能重复」，
        请逐组判断它们是不是**同一种植物**。
    """.trimIndent()

    private val CLEANING_JUDGE_RULES = """
        ## 判断依据（可靠度从高到低）

        1. **拉丁学名**（双名法）最可靠。相同、或只差拼写与作者引证 → 很可能是同一种
        2. **科 + 属**一致说明是近亲，但**不能**说明是同一种（同属几十个种很常见）
        3. **中文名**：俗称、异名、地区名极多，**名称相似不足以判定同一种**
        4. **简介**仅供参考，不同来源的措辞差异很大

        ## 内容要求

        1. **不要因为名称相似就判同一种**，必须结合学名、科、属、简介一起看
        2. 两株的字段互相矛盾（如中文名与拉丁学名明显不匹配）时，
           结论应是字段有问题，而不是「它们是同一种」
        3. `reason` 用中文，一句话，不超过 50 字
        4. 拿不准时 `is_same` 给 false —— 让用户自己去核对，
           比引导他合并掉两株不同的植物代价小得多
    """.trimIndent()

    private val CLEANING_SCHEMA_SPEC = """
        ## 输出格式

        只输出一个 JSON 对象，不要有任何其他文字，也不要使用 ``` 代码块标记。

        {
          "results": [
            {
              "group_id": "12",
              "type": "POSSIBLE_DUPLICATE",
              "is_same": true,
              "confidence": 0.96,
              "reason": "拉丁学名一致，中文名是其常见异名"
            }
          ]
        }

        - `group_id`：**原样返回**上面的「候选 N」里的那个数字
        - `type` 取值：
          - `POSSIBLE_DUPLICATE` 同一种
          - `DATA_CONFLICT` 不是同一种，但有一边的字段互相矛盾
          - `ALIAS_RELATION` 同一物种的不同名称写法，不需要合并
          - `NOT_SAME` 明确不是同一种
        - `is_same`：布尔值，是否判定为同一种
        - **每一组都必须给出一条结果**，不要省略、不要合并
    """.trimIndent()

    // ---------------- 片段 ----------------

    /** 低于此置信度时，文字分析改写成属/科的通用特征（与补图阈值一致） */
    private const val LOW_CONFIDENCE_FOR_ANALYSIS = 0.70

    private val ANALYST_ROLE = """
        你是一位植物学作者，需要为下面这株植物撰写一份简明的百科介绍。
    """.trimIndent()

    private val ANALYSIS_SCHEMA_SPEC = """
        ## 输出格式

        只输出一个 JSON 对象，不要有任何其他文字，也不要使用 ``` 代码块标记。

        {
          "common_names": "常用名称或俗称，多个用「、」分隔",
          "description": "植物简介",
          "morphological_features": "形态特征",
          "growth_habits": "生长习性",
          "flowering_period": "花期",
          "fruiting_period": "果期",
          "landscape_uses": "园林用途",
          "care_advice": "养护建议",
          "pest_control": "常见病虫害与防治建议"
        }
    """.trimIndent()

    private val ANALYSIS_CONTENT_RULES = """
        ## 内容要求

        1. **准确优先于丰富**
           你不确定的内容，把该字段写成空字符串 ""。**不要编造。**
           一个诚实的空白远比一段似是而非的描述有价值 ——
           用户会当真，而你写错的花期或养护方法他很难发现。

        2. **不要复述已知信息**
           不要写「这是一株紫薇」这类废话，直接写内容。

        3. **篇幅**
           每个字段 1–3 句话，全部加起来不超过 400 字。这是给手机屏幕看的。

        4. **不要下鉴定结论**
           不要出现「可以确定是」「一定是」「保证是」这类表述。
           你只是在写百科内容，鉴定结论由用户的实地观察决定。

        5. **common_names 只写真正在用的俗称**
           写民间口耳相传的叫法（如紫薇的「痒痒树」）。**不要**把
           中文名的缩写、拉丁名的音译、或你自己起的名字写进去。
           **也不要把这株植物的正式中文名本身再写一遍** ——
           那一栏是给「俗称」用的，重复一遍正式名只会让界面显示成
           「紫薇、紫薇」，用户会以为程序出了问题。
           想不出俗称就填空字符串 —— 编一个不存在的别名会直接误导用户。

        6. **pest_control 要具体到「怎么处理」**
           写这株植物常见的病虫害名称，以及对应的防治办法
           （例如「蚜虫：发生时用吡虫啉喷雾，注意叶背」）。
           只写「注意防治病虫害」等于什么都没写。
           不要推荐高毒农药，也不要给出具体用药浓度 —— 那需要专业指导。
    """.trimIndent()

    private val LOW_CONFIDENCE_ANALYSIS_RULE = """
        ## 特别注意：本次识别置信度偏低

        图像识别对这株植物的判断把握不大，物种可能不对。
        因此请**以属或科的通用特征为主**来写，不要写只有某个具体种才有的特征
        （例如具体的花色、精确的花期月份、特定的叶片尺寸）。

        宁可写得笼统一些，也不要基于一个可能错误的物种名展开细节。
    """.trimIndent()

    /**
     * 拍摄地点提示（**弱先验**）。
     *
     * ## 为什么措辞这么啰嗦
     *
     * 「告诉模型地点」这件事本身有风险：模型很擅长把一条弱线索当成结论。
     * 一旦它把「拍摄地在杭州」理解成「这里只可能是杭州的树种」，
     * 识别就被地点绑死了 —— 而用户拍到的完全可能是一株引种栽培、
     * 甚至养在室内的植物。
     *
     * 所以这一段做四件事：
     *  1. 明说它**只影响候选排序**，不是鉴定依据
     *  2. 禁止因此提高 confidence（否则置信度就失去了筛选价值）
     *  3. 禁止写进 evidence —— 依据栏里混进地点，事后就没法复核了
     *  4. 明确冲突时**形态特征优先**
     *
     * 地点为空时返回 null：调用方据此**整节不输出**。
     * 写「地点：未知」反而会让模型自己脑补一个环境。
     */
    private fun placeSection(place: String?): String? {
        val value = place?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return """
            ## 拍摄地点（仅作参照，不是鉴定依据）

            拍摄地：$value

            这个地点**只用于调整候选的先后顺序**：该地区常见的物种可以优先考虑。

            - **不要**把它当成鉴定结论，也不要因此提高 confidence
            - **不要**把地点写进 evidence
            - **不要**因为地点就排除某个物种 —— 引种栽培、室内养护都很常见
            - 形态特征与地点提示冲突时，**一律以形态特征为准**
        """.trimIndent()
    }

    private fun roleSection(roles: List<ImageRole>): String = buildString {
        val labelled = roles.withIndex().filter { it.value != ImageRole.UNKNOWN }
        if (labelled.isEmpty()) {
            // 用户一张都没标注：此时退化成 A 组的行为，不再画蛇添足
            return ""
        }

        appendLine("## 每张照片的拍摄部位")
        appendLine()
        roles.forEachIndexed { index, role ->
            val position = index + 1
            if (role == ImageRole.UNKNOWN) {
                appendLine("- 照片 $position：未标注部位，请自行判断")
            } else {
                appendLine("- 照片 $position：${role.label}，重点观察${role.focusHint}")
            }
        }
        appendLine()
        append("请按照上述部位提示，从每张照片中提取对应特征，再综合成**一个**结论。")
    }

    private val ROLE_DEFINITION = """
        你是一位专业的植物分类学者，擅长依据形态特征进行物种鉴定。
    """

    private val JOINT_INSTRUCTION = """
        ## 关键要求

        1. **综合判断，不要逐张描述**
           上述照片是同一株植物，请给出**一个统一结论**。
           不要分别描述每张照片，也不要因为某张照片里看不到花就降低整体判断。

        2. **优先级**
           能用于鉴定的部位优先级大致为：花 > 果实 > 叶片 > 茎干/树皮 > 整体株型。
           若照片中含花序或果实，请优先依据它们判断。

        3. **冲突要说明**
           如果不同照片给出的线索互相矛盾（例如叶片像 A 种、花像 B 种），
           请在 conflicts 字段中如实说明，不要忽略矛盾强行给结论。

        4. **生境照片不参与鉴定**
           如果某张照片标注为「生境」，它只用于记录拍摄环境，不要作为鉴定依据。
    """.trimIndent()

    private val JSON_SCHEMA_SPEC = """
        ## 输出格式

        只输出一个 JSON 对象，不要有任何其他文字，也不要使用 ``` 代码块标记。

        {
          "name": "正式中文名称",
          "latin_name": "拉丁学名",
          "family": "科",
          "genus": "属",
          "category": "植物类型，如 落叶灌木或小乔木",
          "confidence": 0.91,
          "evidence": ["判定依据1", "判定依据2"],
          "missing_information": ["还缺少哪些信息"],
          "possible_alternatives": [
            {"name": "其他可能的植物名", "confidence": 0.06}
          ],
          "conflicts": ["照片之间不一致之处"]
        }

        字段说明：
        - name：必填。**必须是植物学上的正式中文名称**（如「悬铃木」「紫薇」「木棉」）。
          **不要**用商品名、园艺品种名、花市俗称或地方叫法
          （「法国梧桐」「痒痒树」「英雄树」这类都不行）——
          同一物种在不同记录里出现多个名字，会让档案搜索、去重与合并全部失效。
          请始终使用《中国植物志》体系的正式中文名。
          无法确定物种时，给出最可能的属或科并加「（疑似）」
        - latin_name / family / genus / category：不确定时填空字符串 ""
        - evidence：2–5 条，说明你是根据哪些形态特征得出结论的
        - missing_information：要提升准确度还需要看到哪些部位的照片
        - possible_alternatives：最多 3 条，按可能性从高到低排列
        - conflicts：没有冲突时填空数组 []
    """.trimIndent()

    /**
     * name 字段的写法约束。
     *
     * ## 为什么单独拿出来强调
     *
     * 模型的强烈倾向是「用最常见的叫法」—— 而常见的往往是俗称。
     * 悬铃木会被写成「法国梧桐」，紫薇会被写成「痒痒树」，木棉会被写成「英雄树」。
     * 这在小程序里看起来更亲切，在**档案库**里却是灾难：
     *
     *  - 搜索「悬铃木」搜不到那株被记成「法国梧桐」的
     *  - 去重时同一物种的两条记录名字完全不同，本地算法判不出来
     *  - 归并提示也失效，用户会手动建出重复档案
     *
     * 只在 schema 的字段说明里写一句不够 —— 那里是「格式说明」，
     * 模型会当成格式要求扫过去。这里用独立小节 + 具体例子再讲一遍，
     * 并明确告知「俗称由别的通道提供」，避免它觉得不写俗称就丢了信息。
     */
    private val NAME_CONSISTENCY_RULE = """
        ## 关于 name 的写法

        同一物种必须始终给出**同一个**正式中文名。

        如果你本能想到的是俗称或花市叫法，请换回它的正式中文名：

        | 你想到的 | 请改写成 |
        |---|---|
        | 法国梧桐 | 悬铃木 |
        | 痒痒树 | 紫薇 |
        | 英雄树 | 木棉 |
        | 摇钱树 | 青桐 / 复羽叶栾树（按形态判断） |

        以下都不算正式中文名，不要写进 name：
        - 商品名、园艺品种名（如「紫叶李」「红枫」这类栽培品种叫法）
        - 地方叫法、方言名
        - 拉丁名的音译
        - 你自己造的名字

        俗称不需要你输出 —— 后续的百科通道会单独给出。
        这里只负责「这株植物的正式中文名是什么」。
    """.trimIndent()

    private val CONFIDENCE_GUIDE = """
        ## 关于 confidence

        confidence 取值 0.0–1.0，它表示**你对当前视觉证据的把握程度**，
        不是经过科学验证的物种鉴定概率。

        请如实评估：
        - 0.90 以上：关键部位（花或果实）清晰可辨，且排除了近缘种
        - 0.70–0.90：主要特征吻合，但缺少某个关键部位
        - 0.50–0.70：仅凭叶片或株型推断，近缘种难以区分
        - 0.50 以下：证据严重不足

        **证据不足时请给出较低的分数，不要勉强拔高。**
        一个诚实的低分会提示用户补充照片，比一个虚假的高分更有价值。
    """.trimIndent()

    private val INSUFFICIENT_GUIDE = """
        ## 信息不足时的处理

        如果照片不足以确定到种：
        - name 仍要给出最可能的答案（哪怕是「某属（疑似）」）
        - confidence 给出与之匹配的低分
        - 在 missing_information 中明确列出还需要拍摄哪些部位

        宁可承认不确定，也不要编造一个看起来合理但不准确的学名。
    """.trimIndent()
}

/**
 * 部位在 prompt 中的显示名与观察重点。
 *
 * 放在这里而不是 [ImageRole] 上，是因为这些字符串只服务于 prompt，
 * 属于 AI 层的细节；[ImageRole] 是数据层枚举，承担越少展示职责越好。
 */
private val ImageRole.label: String
    get() = when (this) {
        ImageRole.UNKNOWN -> "未标注"
        ImageRole.WHOLE_PLANT -> "整株"
        ImageRole.LEAF -> "叶片"
        ImageRole.FLOWER -> "花"
        ImageRole.FRUIT -> "果实"
        ImageRole.BARK -> "茎干/树皮"
        ImageRole.HABITAT -> "生境"
    }

private val ImageRole.focusHint: String
    get() = when (this) {
        ImageRole.UNKNOWN -> "可见的特征"
        ImageRole.WHOLE_PLANT -> "株型、树姿、整体比例"
        ImageRole.LEAF -> "叶形、叶缘、叶序、叶脉、叶柄"
        ImageRole.FLOWER -> "花序类型、花冠形态、花色、花瓣数"
        ImageRole.FRUIT -> "果实类型、形状、颜色、着生方式"
        ImageRole.BARK -> "树皮纹理、开裂方式、枝条颜色"
        ImageRole.HABITAT -> "拍摄环境（不参与物种鉴定）"
    }
