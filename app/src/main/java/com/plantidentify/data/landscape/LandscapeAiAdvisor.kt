package com.plantidentify.data.landscape

import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.TextCompletionRequest
import com.plantidentify.data.ai.TextCompletionResult
import com.plantidentify.data.ai.TextProvider
import com.plantidentify.data.repository.LandscapeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次景观 AI 分析的结果 */
data class LandscapeAnalysisOutcome(
    /** Markdown 格式的分析报告正文（存库的就是它） */
    val markdown: String,
    val model: String,
    val version: Int,
    /** 本次喂给模型的数据摘要快照 —— 存进结果开头，让「这份结论基于什么数据」可追溯 */
    val statsSummary: String,
)

/**
 * 景观 AI 分析（v1.0.2 Phase 3 §十三）。
 *
 * ## 它分析的是「本地已经算好的统计」，不是原始植物列表
 *
 * 乔灌草比例、多样性指数、季相分布这些数字由 `LandscapeAnalyzer` **本地**算出
 * （又快又稳定还免费），AI 只负责它擅长的部分：拿着这些数字讲出
 * 「这个场地的配置有什么问题、该怎么调整」—— 那需要植物学常识，
 * 是规则写不出来的。
 *
 * 这样还有两个附带好处：prompt 短（几十株的列表浓缩成一段摘要），
 * 以及 AI 数错数的风险消失 —— 数字是程序算的，它只做解读。
 *
 * ## 结果必须持久化（需求 §十三 明确要求）
 *
 * 分析一次存一次（结果 + 模型名 + 时间 + 版本号），打开页面读缓存；
 * 植物成员变化时由仓库标记「需要重新分析」，**但绝不自动重跑** ——
 * AI 调用要花钱，用户可能只是顺手加了一株植物。
 */
class LandscapeAiAdvisor(
    private val aiSettingsStore: AiSettingsStore,
    private val textProvider: TextProvider,
    private val repository: LandscapeRepository,
) {

    suspend fun analyze(folderId: Long, folderName: String): Result<LandscapeAnalysisOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = aiSettingsStore.current().text
                    ?: error("还没有配置文字分析服务，请先到「设置」里配置")

                val data = repository.getData(folderId)
                val stats = repository.statisticsOf(folderId)
                require(!stats.isEmpty) { "这个文件夹里还没有植物，没什么可分析的" }

                val prompt = buildPrompt(
                    folderName = folderName,
                    location = data?.location,
                    projectType = data?.projectType,
                    description = data?.landscapeDescription,
                    statsText = stats.toPromptText(),
                )

                val outcome = textProvider.complete(
                    TextCompletionRequest(
                        prompt = prompt,
                        config = config,
                        // 景观报告比清洗判定长得多：九个维度各写一段
                        maxOutputTokens = 3500,
                        temperature = 0.4,
                    ),
                )

                val raw = when (outcome) {
                    is TextCompletionResult.Success -> outcome.rawText
                    is TextCompletionResult.Failure ->
                        error(outcome.failure.userMessage)
                }

                val cleaned = stripFences(raw)
                val version = repository.saveAnalysis(
                    folderId = folderId,
                    result = cleaned,
                    model = config.model,
                ).getOrThrow()

                LandscapeAnalysisOutcome(
                    markdown = cleaned,
                    model = config.model,
                    version = version,
                    statsSummary = stats.toPromptText(),
                )
            }
        }

    // ---------------------------------------------------------------- prompt

    private fun buildPrompt(
        folderName: String,
        location: String?,
        projectType: String?,
        description: String?,
        statsText: String,
    ): String = buildString {
        appendLine("你是一名园林景观专业的顾问。请基于下面给出的**实测统计数据**，")
        appendLine("对一处绿地做景观分析。数据由程序统计得出，请直接采信，不要重新推算。")
        appendLine()
        appendLine("## 场地信息")
        appendLine("- 名称：$folderName")
        location?.takeIf { it.isNotBlank() }?.let { appendLine("- 位置：$it") }
        projectType?.takeIf { it.isNotBlank() }?.let { appendLine("- 项目类型：$it") }
        description?.takeIf { it.isNotBlank() }?.let { appendLine("- 场地描述：$it") }
        appendLine()
        appendLine("## 统计数据（程序计算，直接采信）")
        appendLine(statsText)
        appendLine()
        appendLine("## 输出要求")
        appendLine("用 Markdown 输出，依次覆盖以下方面（数据不足的方面明确说「数据不足」，不要编造）：")
        appendLine("1. 植物组成与配置评价")
        appendLine("2. 乔灌草层次结构分析")
        appendLine("3. 季相变化评价（哪一季观赏效果好、哪一季可能空缺）")
        appendLine("4. 色彩搭配评价")
        appendLine("5. 植物多样性评价")
        appendLine("6. 可能存在的问题（结合上面第 1-5 点，不要罗列统计里已经写明的数字）")
        appendLine("7. 改进建议（具体到「建议增补哪类植物」，而不是空话）")
        appendLine()
        appendLine("语气：专业但克制。长度 600-900 字。")
        appendLine("不要输出Markdown 代码围栏，直接输出正文。")
        appendLine()
        appendLine("末尾必须单独一行加上这段声明：")
        appendLine("> 本报告由 AI 辅助生成，仅供参考，不代表经过专业人员审核的景观设计结论。")
    }.trimEnd()

    /** 剥掉模型习惯性加的 ``` 围栏 —— 与识别通道同一个处理 */
    private fun stripFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```markdown").removePrefix("```md").removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
