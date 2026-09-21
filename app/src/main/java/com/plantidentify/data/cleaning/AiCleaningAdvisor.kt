package com.plantidentify.data.cleaning

import android.util.Log
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.PromptBuilder
import com.plantidentify.data.ai.TextCompletionRequest
import com.plantidentify.data.ai.TextCompletionResult
import com.plantidentify.data.ai.TextProvider
import com.plantidentify.data.ai.TolerantJsonParser
import com.plantidentify.data.local.PlantIdentifyDatabase
import com.plantidentify.domain.cleaning.CleaningIssueStatus
import com.plantidentify.domain.cleaning.CleaningIssueType
import com.plantidentify.domain.cleaning.CleaningSeverity
import com.plantidentify.domain.cleaning.CleaningVerdict
import com.plantidentify.domain.cleaning.CleaningVerdictType
import com.plantidentify.domain.cleaning.RecordSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一批最多问几组。
 *
 * 20 是**输出侧**的约束：一组结论约 80 tokens（含理由），20 组 1600，
 * 落在 `max_tokens = 2000` 之内。再多就会被截断 —— 而被截断的输出
 * 表现为「后半批没有结论」，看不出是模型的问题还是网络的问题。
 */
private const val MAX_GROUPS_PER_BATCH = 20

/**
 * prompt 长度上限。
 *
 * 12k 字符 ≈ 6k tokens，配上 20 组的输出仍然是任何主流模型都能一次吃完的量。
 * 真正超限只可能来自超长的简介 —— 已经在 [PromptBuilder] 里截到 200 字，
 * 这里再兜一道，防止将来有人把截断去掉。
 */
private const val MAX_PROMPT_CHARS = 12_000

/**
 * 一次清洗最多调几次 AI。
 *
 * 这是**花钱的硬上限**，也是「用户点了检查却要等十分钟」的护栏。
 * 10 次 × 20 组 = 200 组，超出部分留到下次 —— 界面上要如实说明，
 * 否则用户会以为「检查完了，就这几个问题」。
 */
private const val MAX_AI_CALLS = 10

/** 一次清洗最多送多少组给 AI */
private const val MAX_GROUPS_TOTAL = MAX_GROUPS_PER_BATCH * MAX_AI_CALLS

/**
 * AI 清洗顾问（方案 §8）。
 *
 * ## 它只做一件本地做不了的事
 *
 * 「这两株是不是同一种」—— 本地六级判定能算出**相似度**，
 * 算不出**结论**：0.55–0.90 那一段里，既有一物异名，也有完全不同的两种植物。
 * 所以 AI 只接灰区，而且**一次问一批**。
 *
 * ## 成本护栏（三层，缺一不可）
 *
 * 1. **只有灰区会被送进来**：`needsAi = false` 的候选（级 1、2）根本不入队，
 *    正常数据一次 AI 都不调
 * 2. **单批 ≤ 20 组且 prompt ≤ 12k 字符**：避免截断与超长请求
 * 3. **一次最多 10 批**：200 组的硬上限，超出的如实告诉用户「还有 X 组没判」
 *
 * ## 失败的处理
 *
 * 一批失败**不重试**（方案 §8.3）：一批 20 组重问一次就是 20 组再付一次钱，
 * 而收益只是那一批。失败的组**保持 `aiUsed = 0`**，下次检查时自动重新入队 ——
 * 这比在失败时写一条「未判定」的假结论要诚实，也不会让用户以为已经判过了。
 *
 * 连续失败时**提前中断**：认证错、模型名错这类问题会让后面每一批
 * 都以同样的方式失败，继续跑只是把 10 次额度烧光。
 */
class AiCleaningAdvisor(
    private val aiSettingsStore: AiSettingsStore,
    private val textProvider: TextProvider,
    private val database: PlantIdentifyDatabase,
    private val loader: CleaningDataLoader,
) {

    private val issueDao = database.cleaningIssueDao()

    sealed interface Outcome {

        /** 没配文字模型 —— 不是错误，只是这次没法判 */
        data class NotConfigured(val reason: String) : Outcome

        data class Ran(
            /** 实际调用了几次 AI */
            val calls: Int,

            /** 有多少组拿到了结论 */
            val judged: Int,

            /** 其中被判为「不是同一种 / 名称差异」而自动结案的 */
            val dismissed: Int,

            /** 其中被确认是同一株的（用户接下来要合并） */
            val confirmed: Int,

            /** 因为超出 200 组上限而没送的 */
            val skipped: Int,

            /** 涉及的档案已经不在了（多半是刚被合并/删除），直接结案的条数 */
            val stale: Int,

            /** 失败的批次原因；null 表示全部成功 */
            val failure: String?,
        ) : Outcome
    }

    suspend fun advise(): Outcome = withContext(Dispatchers.IO) {
        val config = aiSettingsStore.current().effectiveText
        if (!config.isUsable) {
            return@withContext Outcome.NotConfigured(
                "尚未配置文字分析模型，无法让 AI 复核疑似重复",
            )
        }

        // 多取一条用来判断「是不是还有剩下的」
        val pending = issueDao.getPendingAi(MAX_GROUPS_TOTAL + 1)
        if (pending.isEmpty()) {
            return@withContext Outcome.Ran(0, 0, 0, 0, 0, 0, null)
        }

        val skipped = (pending.size - MAX_GROUPS_TOTAL).coerceAtLeast(0)
        val batchCandidates = pending.take(MAX_GROUPS_TOTAL)

        val dataset = loader.load()
        val now = System.currentTimeMillis()

        // 涉及的档案已经不在了 → 这条问题失去意义，直接结案。
        // 不这么做的话，它每次都会进候选、每次都因为查不到档案被跳过，
        // 永远留在待处理里（用户点「检查」多少次都清不掉）
        val groups = mutableListOf<PromptBuilder.CleaningGroup>()
        var stale = 0
        for (issue in batchCandidates) {
            val ids = issue.recordIdList()
            val records = ids.mapNotNull { dataset.recordById(it) }
            if (records.size != 2) {
                issueDao.setStatus(issue.id, CleaningIssueStatus.RESOLVED, now)
                stale++
                continue
            }
            groups += PromptBuilder.CleaningGroup(
                issueId = issue.id,
                first = records[0],
                second = records[1],
                localReason = issue.reason,
            )
        }

        if (groups.isEmpty()) {
            return@withContext Outcome.Ran(0, 0, 0, 0, skipped, stale, null)
        }

        var calls = 0
        var judged = 0
        var dismissed = 0
        var confirmed = 0
        var failure: String? = null

        for (batch in splitIntoBatches(groups)) {
            val prompt = PromptBuilder.buildCleaningPrompt(batch)
            val outcome = textProvider.complete(
                TextCompletionRequest(prompt = prompt, config = config),
            )
            calls++

            when (outcome) {
                is TextCompletionResult.Failure -> {
                    // 提前中断：认证/模型名这类问题会让后面每一批同样失败
                    failure = outcome.failure.userMessage
                    Log.w(TAG, "清洗顾问调用失败，已中断后续批次：${outcome.failure.userMessage}")
                    break
                }

                is TextCompletionResult.Success -> {
                    val parsed = TolerantJsonParser.parseCleaningResults(
                        raw = outcome.rawText,
                        knownIssueIds = batch.mapTo(HashSet()) { it.issueId },
                    )
                    when (parsed) {
                        is TolerantJsonParser.CleaningParseResult.Failed -> {
                            failure = parsed.reason
                            Log.w(TAG, "清洗顾问返回无法解析（本批 ${batch.size} 组留待下次）")
                        }

                        is TolerantJsonParser.CleaningParseResult.Ok -> {
                            for (verdict in parsed.verdicts) {
                                if (apply(verdict, now)) {
                                    judged++
                                    if (verdict.dismissesIssue) dismissed++
                                    if (verdict.confirmsDuplicate) confirmed++
                                }
                            }
                        }
                    }
                }
            }
        }

        Outcome.Ran(
            calls = calls,
            judged = judged,
            dismissed = dismissed,
            confirmed = confirmed,
            skipped = skipped,
            stale = stale,
            failure = failure,
        )
    }

    /**
     * 把结论写进问题行。返回 false 表示那条问题已经不存在（被并发清掉了）。
     *
     * 三处更新一起做（见 `CleaningIssueDao.updateAiVerdict`）：
     * `aiUsed` 标记已判定（下次不再入队）、`severity` 让确认的重复排到最前、
     * `status` 让无事可做的直接结案。
     */
    private suspend fun apply(verdict: CleaningVerdict, now: Long): Boolean {
        val entity = issueDao.getById(verdict.issueId) ?: return false
        // 用户在 AI 跑的过程中自己点了「忽略」——尊重用户的决定，不要覆盖回去
        if (entity.status != CleaningIssueStatus.OPEN) return false

        val severity = when {
            verdict.confirmsDuplicate -> CleaningSeverity.HIGH
            verdict.type == CleaningVerdictType.DATA_CONFLICT -> CleaningSeverity.MEDIUM
            else -> entity.severity
        }

        issueDao.updateAiVerdict(
            id = entity.id,
            aiUsed = true,
            verdict = verdict.type,
            reason = verdict.reason,
            severity = severity,
            status = if (verdict.dismissesIssue) CleaningIssueStatus.RESOLVED else CleaningIssueStatus.OPEN,
            now = now,
        )
        return true
    }

    /**
     * 按「组数」与「prompt 长度」两个约束切批。
     *
     * 两个都要判：20 张超长简介能把 prompt 撑到 30k，
     * 而 200 组一句话的候选合起来仍然太长。只判一个都会漏。
     */
    private fun splitIntoBatches(
        groups: List<PromptBuilder.CleaningGroup>,
    ): List<List<PromptBuilder.CleaningGroup>> {
        val batches = mutableListOf<List<PromptBuilder.CleaningGroup>>()
        var current = mutableListOf<PromptBuilder.CleaningGroup>()
        var currentChars = 0

        for (group in groups) {
            // 用「组自身的字符量」估算，比真正拼出 prompt 再测长度便宜得多
            val estimate = roughCharsOf(group)
            val wouldOverflow = current.isNotEmpty() &&
                (current.size >= MAX_GROUPS_PER_BATCH || currentChars + estimate > MAX_PROMPT_CHARS)

            if (wouldOverflow) {
                batches += current
                current = mutableListOf()
                currentChars = 0
            }
            current += group
            currentChars += estimate
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun roughCharsOf(group: PromptBuilder.CleaningGroup): Int =
        120 + estimateOf(group.first) + estimateOf(group.second)

    private fun estimateOf(record: RecordSnapshot): Int =
        record.name.length +
            (record.latinName?.length ?: 0) +
            (record.family?.length ?: 0) +
            (record.genus?.length ?: 0) +
            (record.category?.length ?: 0) +
            (record.description?.length ?: 0).coerceAtMost(200)

    private companion object {
        const val TAG = "AiCleaningAdvisor"
    }
}
