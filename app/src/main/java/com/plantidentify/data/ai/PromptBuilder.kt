package com.plantidentify.data.ai

import com.plantidentify.data.local.entity.ImageRole

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
     */
    fun buildRecognitionPrompt(
        roles: List<ImageRole>,
        strategy: PromptStrategy = PromptStrategy.DEFAULT,
        correction: String? = null,
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

            appendLine(JSON_SCHEMA_SPEC.trimIndent())
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
    fun buildConnectivityPrompt(): String =
        "请只回答一个 JSON 对象：{\"ok\": true, \"model_ack\": \"已收到图片\"}。" +
            "不要输出任何其他文字。"

    // ---------------- 片段 ----------------

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
          "name": "中文名称",
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
        - name：必填。无法确定物种时，给出最可能的属或科并加「（疑似）」
        - latin_name / family / genus / category：不确定时填空字符串 ""
        - evidence：2–5 条，说明你是根据哪些形态特征得出结论的
        - missing_information：要提升准确度还需要看到哪些部位的照片
        - possible_alternatives：最多 3 条，按可能性从高到低排列
        - conflicts：没有冲突时填空数组 []
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
