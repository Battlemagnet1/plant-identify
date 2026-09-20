package com.plantidentify.data.recognition

import com.plantidentify.data.ai.AiFailure
import com.plantidentify.data.ai.AiSettingsStore
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.ai.VisionCallResult
import com.plantidentify.data.ai.VisionImage
import com.plantidentify.data.ai.VisionProvider
import com.plantidentify.data.ai.VisionRequest
import com.plantidentify.data.ai.VisionResponse
import com.plantidentify.data.draft.CaptureDraft
import com.plantidentify.data.image.ImageCompressor
import com.plantidentify.data.location.LocationSettingsStore
import com.plantidentify.data.location.aiPlaceHint
import com.plantidentify.data.repository.PlantRepository
import com.plantidentify.data.storage.ImageStore
import com.plantidentify.domain.model.MergeSuggestion
import com.plantidentify.domain.model.TimingTrace

/**
 * 识别流程的共享执行体。
 *
 * ## 为什么要有它
 *
 * 在此之前，「压缩 → 视觉识别 → 落库」这段逻辑长在 `RecognitionViewModel` 里，
 * 于是**只有界面在的时候才能识别**：用户不能离开识别页，切后台也可能被杀。
 *
 * Phase 2 要给识别加任务队列，而队列的执行者是 WorkManager 的 Worker ——
 * 它没有 Activity、没有 ViewModel。最省事的做法是在 Worker 里**再写一遍**，
 * 但那意味着两份 prompt 组装、两份压缩参数、两份落库顺序；
 * 它们会在某次修改后悄悄分叉，而分叉的后果是「同一个模型，
 * 前台识别和后台识别结果不一样」这种极难排查的问题。
 *
 * 所以把这段抽成不依赖任何 Android UI 的类，两条路径都调它。
 *
 * ## 它不负责的东西
 *
 * - **不碰任何界面状态**。返回结构化结果（[RecognizeOutcome] / [PersistOutcome]），
 *   由调用方决定怎么映射成 UI 状态。这是「同一份实现给两种场景用」的前提。
 * - **不做分析**。文字分析由 [AnalysisRunner] 负责，调用方决定在哪跑
 *   （前台用应用级作用域、后台用 Worker）—— 两条路径的时机不同，不该硬编在这里。
 */
class RecognitionExecutor(
    private val aiSettingsStore: AiSettingsStore,
    private val visionProvider: VisionProvider,
    private val imageCompressor: ImageCompressor,
    private val imageStore: ImageStore,
    private val repository: PlantRepository,
    private val locationSettingsStore: LocationSettingsStore,
) {

    // ------------------------------------------------------------------
    // 视觉识别
    // ------------------------------------------------------------------

    /** 视觉识别的结果。失败与「没配好」共用 [Failed]，由调用方按 [AiFailure] 决定引导去哪 */
    sealed interface RecognizeOutcome {

        data class Recognized(
            val response: VisionResponse,
            /** 各阶段耗时，直接喂给结果页的「本次耗时」卡 */
            val timing: List<TimingTrace.Segment>,
            /**
             * 本次用的地点弱先验（未允许上传时为 null）。
             *
             * **回传给调用方保存**，因为保存阶段要把它复用给文字分析：
             * 保存成功后草稿会被清掉，那时再算只会得到 null，
             * 于是视觉请求带了地点、百科请求却没带 —— 同一株植物两次请求口径不一致。
             */
            val placeHint: String?,
            val imageCount: Int,
            /** 有图片压缩失败、实际只上传了一部分 */
            val usedFallbackForSomeImages: Boolean,
        ) : RecognizeOutcome

        data class Failed(
            val failure: AiFailure,
            /**
             * 是否属于「去设置页多半能解决」的错误。
             *
             * 判定放在这里而不是界面，是因为它取决于 [AiFailure] 的类型学；
             * 把这个 switch 写在 Compose 里，下次加一种失败类型就会漏。
             */
            val canOpenSettings: Boolean = failure.isConfigRelated,
        ) : RecognizeOutcome
    }

    /**
     * 压缩 + 视觉识别。
     *
     * 计时从进入本方法就开始 —— 这样「压缩图片」那一段才是真的压缩耗时，
     * 而不是把读草稿、校验配置的时间也算进去。
     *
     * @param onCompressed 压缩完成、即将发请求时回调（图片数、是否有压缩失败被跳过）。
     *   界面靠它把状态从「准备中」切到「正在请求模型」。
     *   **不传也不影响结果** —— 后台任务没有界面，就不传。
     *   之所以用回调而不是让调用方自己先压缩：压缩参数（1536px / JPEG 80）
     *   必须只有一处，否则前台和后台迟早会压出两种图。
     */
    suspend fun recognize(
        draft: CaptureDraft,
        onCompressed: ((imageCount: Int, usedFallback: Boolean) -> Unit)? = null,
    ): RecognizeOutcome {
        val trace = TimingTrace()

        val config = aiSettingsStore.current()
        if (!config.vision.isUsable) {
            return RecognizeOutcome.Failed(AiFailure.NotConfigured(config.vision.missingFields))
        }

        // 逐张生成（或复用）压缩副本。单张失败不中断 —— 有一张能用就继续识别
        val images = draft.images.mapNotNull { draftImage ->
            imageCompressor
                .compressedFor(imageStore.resolve(draftImage.relativePath))
                .getOrNull()
                ?.let { compressed -> VisionImage(file = compressed, role = draftImage.role) }
        }

        if (images.isEmpty()) {
            // 本机文件的问题，不是服务端的问题 —— 用 LocalProblem
            // 才不会在界面上被冠以「服务返回：」
            return RecognizeOutcome.Failed(
                AiFailure.LocalProblem("照片读取失败，请回到上一步重新添加照片"),
            )
        }

        trace.mark("压缩图片")
        onCompressed?.invoke(images.size, images.size < draft.count)

        val place = placeHintFor(draft)

        val vision = visionProvider.recognize(
            VisionRequest(
                images = images,
                config = config.vision,
                strategy = config.promptStrategy,
                place = place,
            ),
        )
        trace.mark("识别请求")

        return when (vision) {
            is VisionCallResult.Success -> RecognizeOutcome.Recognized(
                response = vision.response,
                timing = trace.segments(),
                placeHint = place,
                imageCount = images.size,
                usedFallbackForSomeImages = images.size < draft.count,
            )

            is VisionCallResult.Failure -> RecognizeOutcome.Failed(vision.failure)
        }
    }

    /**
     * 算地点弱先验。
     *
     * 只有用户在设置里**明确允许上传**时才有值 —— 默认关闭，
     * 且「关」意味着 prompt 里连这一节都不出现（不是填空字符串）。
     *
     * 是 suspend：`LocationSettingsStore.current()` 要读 DataStore。
     */
    suspend fun placeHintFor(draft: CaptureDraft): String? {
        val choice = locationSettingsStore.current()
        return aiPlaceHint(
            shareWithAi = choice.shareWithAi,
            locationName = draft.locationName,
            latitude = draft.latitude,
            longitude = draft.longitude,
        )
    }

    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    sealed interface PersistOutcome {

        /** 新建了植物档案（连带一条观察） */
        data class Created(val plantId: Long) : PersistOutcome

        /** 追加到已有植物的观察下（用户确认过，或后台自动挂靠） */
        data class AttachedToExisting(val plantId: Long) : PersistOutcome

        /** 更新了已有观察（补图重识别），不新建也不提示 */
        data class UpdatedObservation(val plantId: Long) : PersistOutcome

        /**
         * 命中候选档案，**还没有写入任何东西**，等用户裁决。
         *
         * 规格书第十四点五节：归并只提示、绝不自动合并。
         */
        data class AwaitingDecision(val suggestion: MergeSuggestion) : PersistOutcome
    }

    /**
     * 立即识别的落库。
     *
     * ## 三条分支（与 Phase 1 行为完全一致）
     *
     * | 草稿状态 | 行为 |
     * |---|---|
     * | 指向已有观察（补图重识别） | 更新那条观察，不新建、不提示 |
     * | 普通新建 + 找到候选 | 返回 [PersistOutcome.AwaitingDecision]，由用户决定 |
     * | 普通新建 + 无候选 | 直接建新档案 |
     *
     * 补图那条跳过归并提示是有意的：用户点「补图并重新识别」时已经明确表示
     * 「这是同一株、同一次观察」，再问一遍「要不要加到已有植物」
     * 是重复确认，只会让人怀疑自己点错了。
     *
     * ## 它不启动文字分析
     *
     * 落库完成就返回 —— 档案在那一刻已经完整（可查、可搜、可导出）。
     * 由调用方决定要不要、以及在哪个作用域跑 [AnalysisRunner]。
     */
    suspend fun persistNow(
        result: RecognitionResult,
        rawAiJson: String,
        draft: CaptureDraft,
        /**
         * 跳过归并检查。
         *
         * 用户在归并提示里已经点过「创建新的植物」时必须传 true ——
         * 否则这里会**再问一次**，而界面上那次询问已经被消费掉了，
         * 结果就是点了按钮没反应（返回的依旧是 [PersistOutcome.AwaitingDecision]）。
         */
        skipMergeCheck: Boolean = false,
    ): Result<PersistOutcome> {
        // ---- 分支一：给已有观察补图 → 写回原观察
        if (draft.isReanalysis) {
            val targetId = draft.targetObservationId
                ?: return Result.failure(IllegalStateException("补图模式缺少目标观察 id"))
            return repository
                .reanalyzeObservation(observationId = targetId, result = result, rawAiJson = rawAiJson)
                .map { plantId -> PersistOutcome.UpdatedObservation(plantId) }
        }

        // ---- 分支二：先问归并，再决定
        if (!skipMergeCheck) {
            val suggestion = repository.findMergeSuggestion(result)
            if (suggestion.hasCandidate) {
                return Result.success(PersistOutcome.AwaitingDecision(suggestion))
            }
        }

        // ---- 分支三：直接建新档案
        return repository
            .saveAsNewPlant(result = result, rawAiJson = rawAiJson)
            .map { plantId -> PersistOutcome.Created(plantId) }
    }

    /** 用户确认「添加到已有植物」 */
    suspend fun appendToExisting(
        plantId: Long,
        result: RecognitionResult,
        rawAiJson: String,
    ): Result<Long> = repository.appendObservation(
        plantId = plantId,
        result = result,
        rawAiJson = rawAiJson,
    )
}

/**
 * 「这个错误去设置页多半能解决吗」。
 *
 * 配置类错误（没配、Key 错、模型名错、地址错、明文被拦）应当直接引导用户去设置页；
 * 其余（超时、限流、服务端 5xx、图片被拒）留在本页重试更顺手。
 */
private val AiFailure.isConfigRelated: Boolean
    get() = this is AiFailure.NotConfigured ||
        this is AiFailure.Unauthorized ||
        this is AiFailure.ModelNotFound ||
        this is AiFailure.ModelNotVisionCapable ||
        this is AiFailure.EndpointNotFound ||
        this is AiFailure.CleartextBlocked
