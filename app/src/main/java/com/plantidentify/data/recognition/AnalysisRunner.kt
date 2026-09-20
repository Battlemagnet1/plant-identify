package com.plantidentify.data.recognition

import android.util.Log
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.ai.TextAnalysisRequest
import com.plantidentify.data.ai.TextAnalysisResult
import com.plantidentify.data.ai.TextProvider
import com.plantidentify.data.local.entity.AnalysisStatus
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.domain.model.TimingTrace

/**
 * 植物百科（文字分析）的执行体。
 *
 * ## 为什么单独抽出来
 *
 * 这段逻辑原先长在 `RecognitionViewModel.runAnalysis()` 里，被两件事同时绑住：
 *
 *  1. **它挂在保存的关键路径上** —— 用户点「保存」之后要等它跑完才离开识别页，
 *     而它是一次完整的网络请求。感知上「保存好慢」的根因就在这里。
 *  2. **它只能在识别页被触发** —— 换文字模型、分析失败后重试，都得回到那一页。
 *
 * 抽成不依赖 ViewModel 的类之后：识别页可以把落库与它**解耦**
 * （落库一完成就返回，分析交给应用级作用域在后台跑），
 * 而 Phase 2 的任务队列也能直接复用它 —— 两条路径共用同一份实现，
 * 不会出现「前台一套、后台一套」的分叉。
 *
 * ## 它的失败是「可接受」的
 *
 * 规格书第三十节要求「文字分析失败不能导致植物识别结果丢失」。
 * 落库已经先完成，所以这里无论怎么失败，档案都在 ——
 * 因此本类**只标记状态，绝不抛异常**，也不撤销任何东西。
 */
class AnalysisRunner(
    private val aiSettingsStore: AiSettingsStore,
    private val textProvider: TextProvider,
    private val repository: PlantRepository,
) {

    /** 分析结束时的结果，供调用方决定要不要提示、提示什么 */
    sealed interface Outcome {

        /** 成功。`partialNote` 非空表示模型只返回了部分字段 */
        data class Succeeded(val partialNote: String?) : Outcome

        /**
         * 没配文字模型 —— **这不是错误**。
         *
         * 用户完全可能只想用视觉识别。但要把「完全没配」与「配了一半」
         * 区分开，否则看到「尚未配置」会以为自己没动过设置。
         */
        data class NotConfigured(val reason: String) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /**
     * 生成百科并写回档案。
     *
     * @param result 视觉识别的结果；为 null 表示只有档案上的字段可用
     * @param place 地点弱先验，已由调用方按「允许上传」开关处理过；未开启时为 null
     */
    suspend fun run(
        plantId: Long,
        result: RecognitionResult?,
        plantName: String? = null,
        place: String? = null,
    ): Outcome {
        val config = aiSettingsStore.current().effectiveText

        if (!config.isUsable) {
            repository.updateAnalysis(plantId, AnalysisStatus.NOT_REQUESTED)

            val nothingConfigured =
                config.baseUrl.isBlank() && config.model.isBlank() && config.apiKey.isBlank()
            return Outcome.NotConfigured(
                reason = if (nothingConfigured) {
                    "尚未配置文字分析模型，植物百科未能生成"
                } else {
                    "文字分析配置不完整（缺 ${config.missingFields.joinToString("、")}），" +
                        "植物百科未能生成"
                },
            )
        }

        val name = plantName ?: result?.name
        if (name.isNullOrBlank()) {
            repository.updateAnalysis(plantId, AnalysisStatus.FAILED)
            return Outcome.Failed("缺少植物名称，无法生成百科")
        }

        repository.markAnalysisPending(plantId)

        val trace = TimingTrace()
        val analysis = textProvider.generateAnalysis(
            TextAnalysisRequest(
                name = name,
                latinName = result?.latinName,
                family = result?.family,
                genus = result?.genus,
                category = result?.category,
                confidence = result?.confidence ?: 0.0,
                evidence = result?.evidence.orEmpty(),
                config = config,
                place = place,
            ),
        )
        trace.mark("百科生成")

        // 这一段单独打点：它是保存之后才跑的，识别页早已被移除，
        // 界面上的耗时卡看不到它 —— 只有日志能证明它花了多久
        Log.i(TimingTrace.LOG_TAG, "植物 #$plantId 后台分析 ${trace.render()}")

        return when (analysis) {
            is TextAnalysisResult.Success -> {
                repository.updateAnalysis(
                    plantId = plantId,
                    status = AnalysisStatus.SUCCEEDED,
                    analysis = analysis.analysis,
                )
                Outcome.Succeeded(partialNote = analysis.parseNote)
            }

            is TextAnalysisResult.Failure -> {
                repository.updateAnalysis(plantId, AnalysisStatus.FAILED)
                Outcome.Failed(reason = analysis.failure.userMessage)
            }
        }
    }
}
