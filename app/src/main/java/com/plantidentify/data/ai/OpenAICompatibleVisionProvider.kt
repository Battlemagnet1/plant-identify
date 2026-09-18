package com.plantidentify.data.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 基于 OpenAI Compatible 协议的视觉识别实现。
 *
 * 首版预置的 Qwen / 豆包 / OpenAI 三家都走这一条路径，
 * 差异全部收敛到 [AiEndpointConfig] 的数据里 —— 不为每家写一个类。
 *
 * HTTP 调用、错误分类与响应解析统一交给 [ChatCompletionsClient]，
 * 本类只负责视觉侧特有的部分：多图请求体、部位标注 prompt、容错 JSON 解析。
 *
 * ## 超时取值
 *
 * 5 张 1536px / JPEG 80 的图 base64 后约 1.7–2.7 MB（分析报告 Part 3.3），
 * 且多图联合推理本身较慢，因此写、读超时都给得比较宽（见
 * [ChatCompletionsClient.defaultClient]）。
 */
class OpenAICompatibleVisionProvider(
    private val client: ChatCompletionsClient = ChatCompletionsClient(),
) : VisionProvider {

    override suspend fun recognize(request: VisionRequest): VisionCallResult {
        val config = request.config

        if (!config.isUsable) {
            return VisionCallResult.Failure(AiFailure.NotConfigured(config.missingFields))
        }
        if (request.images.isEmpty()) {
            return VisionCallResult.Failure(AiFailure.Unknown("没有可识别的照片"))
        }

        // 第一轮带上 response_format 与 temperature 争取更稳定的输出；
        // 若服务端表示不认识这些参数，则去掉它们重试（见下方 unsupportedParams 分支）
        var includeStructuredParams = true
        var correction: String? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = postRecognition(request, correction, includeStructuredParams)) {
                is ChatOutcome.Ok -> {
                    val content = client.extractMessageContent(outcome.rawBody)

                    if (content == null) {
                        return VisionCallResult.Failure(
                            AiFailure.InvalidResponse(
                                detail = "响应中找不到模型回复的正文",
                                rawSnippet = AiFailure.sanitize(outcome.rawBody),
                            ),
                        )
                    }

                    when (val parsed = TolerantJsonParser.parse(content)) {
                        is TolerantJsonParser.ParseAttempt.Success ->
                            return VisionCallResult.Success(
                                VisionResponse(
                                    result = parsed.result,
                                    rawText = content,
                                    imageCount = request.images.size,
                                    afterRetry = attempt > 0,
                                ),
                            )

                        is TolerantJsonParser.ParseAttempt.Degraded ->
                            return VisionCallResult.Success(
                                VisionResponse(
                                    result = parsed.result,
                                    rawText = content,
                                    parseNote = parsed.note,
                                    imageCount = request.images.size,
                                    afterRetry = attempt > 0,
                                ),
                            )

                        is TolerantJsonParser.ParseAttempt.Failed -> {
                            if (attempt < MAX_ATTEMPTS - 1) {
                                // 容错链路第 5 步：告诉模型上次哪里不合规，命中率明显高于原样重发
                                correction = "你上次的输出无法解析：${parsed.reason}。" +
                                    "请严格遵守 JSON 格式，只输出一个 JSON 对象。"
                            } else {
                                // 用尽重试：降级为半结构化，保留原文而不丢结果（验收标准 ⑤）
                                return VisionCallResult.Success(
                                    VisionResponse(
                                        result = null,
                                        rawText = content,
                                        parseNote = "模型返回的内容不是规范 JSON（${parsed.reason}），" +
                                            "已保留原文供参考",
                                        imageCount = request.images.size,
                                        afterRetry = true,
                                    ),
                                )
                            }
                        }
                    }
                }

                is ChatOutcome.HttpError -> {
                    if (includeStructuredParams && outcome.unsupportedParams) {
                        includeStructuredParams = false
                    } else {
                        return VisionCallResult.Failure(outcome.failure)
                    }
                }

                is ChatOutcome.TransportError -> return VisionCallResult.Failure(outcome.failure)
            }
        }

        return VisionCallResult.Failure(AiFailure.Unknown("重试后仍未获得可用的识别结果"))
    }

    /**
     * 测试连接。
     *
     * ## 两轮策略（第一轮失败会自动降级，这是必要的）
     *
     * 第一轮带一张探针图，顺带验证「这个模型接不接受图片」。
     * 但如果服务端因**参数校验**拒绝（最常见的是图片尺寸不符合它的最小要求），
     * 那说明问题在探针图而不在配置 —— 此时自动用纯文本再测一次：
     *
     * - 纯文本能通 → [ConnectivityResult.ImageUnverified]（配置是对的，图片没验上）
     * - 纯文本也不通 → [ConnectivityResult.Failure]（确实有问题）
     *
     * 不这样做的后果很具体：曾经用 1×1 的探针图，在阿里云百炼上必然返回
     * `InvalidParameter`（它要求宽高均 > 10 像素、像素数 ≥ 4096），
     * 于是「测试连接」对一个完全正确的配置报「连接失败」，
     * 而实际识别又正常 —— 用户只能陷入困惑。
     */
    override suspend fun testConnection(request: ConnectivityRequest): ConnectivityResult {
        val config = request.config
        if (!config.isUsable) {
            return ConnectivityResult.Failure(AiFailure.NotConfigured(config.missingFields))
        }

        val startedAt = System.currentTimeMillis()

        return when (val outcome = postProbe(config, withImage = true)) {
            is ChatOutcome.Ok -> ConnectivityResult.Success(
                model = client.echoModelOf(outcome.rawBody, config.model),
                latencyMs = System.currentTimeMillis() - startedAt,
            )

            is ChatOutcome.HttpError -> when {
                // 服务端明确表示不接受图片 → 就是配成了纯文本模型
                outcome.isImageRejection ->
                    ConnectivityResult.TextOnlyModel(config.model)

                // 400/422 的参数类拒绝 → 大概率是探针图不合规，去掉图片再试
                outcome.failure is AiFailure.BadRequest ->
                    retryWithoutImage(config, outcome.failure, startedAt)

                else -> ConnectivityResult.Failure(outcome.failure)
            }

            is ChatOutcome.TransportError -> ConnectivityResult.Failure(outcome.failure)
        }
    }

    /**
     * 去掉图片再测一次。
     *
     * 用于「带图请求被参数校验拒绝」的情形 —— 此时地址、Key、模型名
     * 很可能都是对的，只是探针图不符合该服务的最小尺寸规则。
     */
    private suspend fun retryWithoutImage(
        config: AiEndpointConfig,
        imageFailure: AiFailure,
        startedAt: Long,
    ): ConnectivityResult =
        when (val outcome = postProbe(config, withImage = false)) {
            is ChatOutcome.Ok -> ConnectivityResult.ImageUnverified(
                model = client.echoModelOf(outcome.rawBody, config.model),
                latencyMs = System.currentTimeMillis() - startedAt,
                reason = imageFailure.serverDetail,
            )

            is ChatOutcome.HttpError -> ConnectivityResult.Failure(outcome.failure)
            is ChatOutcome.TransportError -> ConnectivityResult.Failure(outcome.failure)
        }

    // ---------------- 请求构造 ----------------

    private suspend fun postRecognition(
        request: VisionRequest,
        correction: String?,
        includeStructuredParams: Boolean,
    ): ChatOutcome = withContext(Dispatchers.IO) {
        // 读取压缩副本并做 base64 编码
        val dataUris = ArrayList<String>(request.images.size)
        for (image in request.images) {
            if (!image.file.isFile) {
                return@withContext ChatOutcome.TransportError(
                    AiFailure.Unknown("有一张待上传的图片已不存在，请重新添加照片"),
                )
            }
            val bytes = runCatching { image.file.readBytes() }.getOrElse { error ->
                return@withContext ChatOutcome.TransportError(
                    AiFailure.Unknown(
                        detail = AiFailure.sanitize("读取图片失败：${error.javaClass.simpleName}"),
                        cause = error,
                    ),
                )
            }
            dataUris += "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        val prompt = PromptBuilder.buildRecognitionPrompt(
            roles = request.images.map { it.role },
            strategy = request.strategy,
            correction = correction,
        )

        val content = JSONArray().apply {
            dataUris.forEach { uri -> put(imagePart(uri)) }
            put(JSONObject().put("type", "text").put("text", prompt))
        }

        val body = JSONObject().apply {
            put("model", request.config.model)
            put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
            // 识别需要稳定输出，不需要创造性
            put("temperature", 0.2)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            if (includeStructuredParams) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
        }

        client.post(request.config, body.toString())
    }

    /** 构造最小请求。`withImage` 为 false 时是纯文本，用于降级重试 */
    private suspend fun postProbe(
        config: AiEndpointConfig,
        withImage: Boolean,
    ): ChatOutcome = withContext(Dispatchers.IO) {
        val content = JSONArray().apply {
            if (withImage) {
                put(imagePart(probeDataUri))
            }
            put(
                JSONObject()
                    .put("type", "text")
                    .put("text", PromptBuilder.buildConnectivityPrompt()),
            )
        }

        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", PROBE_MAX_TOKENS)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", content),
                ),
            )
        }

        client.post(config, body.toString())
    }

    /**
     * 探针图只生成一次。
     *
     * 用 `by lazy` 而不是每次现算：位图绘制 + PNG 编码 + base64 有几十毫秒开销，
     * 而这个内容是固定的。放在 IO 线程生成（见 [postProbe]），不阻塞主线程。
     */
    private val probeDataUri: String by lazy { buildProbeImageDataUri() }

    /**
     * 生成测试连接用的探针图（data URI）。
     *
     * ## 为什么不能是一张 1×1 的小图
     *
     * 这是一次真实事故换来的结论。最初为了把请求体压到最小，硬编码了一张
     * 1×1 的 PNG，结果在阿里云百炼上**必然**报
     * `<400> InternalError.Algo.InvalidParameter`：
     *
     * - 官方限制「图像的宽度和高度均须大于 10 像素」—— 1×1 不满足
     * - `min_pixels` 下限：qwen-vl-max / qwen-vl-plus 为 4096，
     *   Qwen3-VL 系列为 65536 —— 1 像素差得更远
     *
     * 于是出现了一个荒谬的现象：实际识别完全正常，点「测试连接」却报失败。
     *
     * ## 为什么用程序生成而不是硬编码 base64
     *
     * 源码里塞一大段 base64 既难读也难改，而各家服务的最小尺寸要求还会变。
     * 这里留一个可调整的 [PROBE_EDGE] 常量更实际。
     *
     * ## 为什么画图案而不是纯色
     *
     * 部分服务对完全空白的图有额外处理，画个简单方块更保险 —— 成本只有几十字节。
     */
    private fun buildProbeImageDataUri(): String {
        val bitmap = Bitmap.createBitmap(PROBE_EDGE, PROBE_EDGE, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(PROBE_BACKGROUND)
                val inset = PROBE_EDGE / 4f
                drawRect(
                    inset,
                    inset,
                    PROBE_EDGE - inset,
                    PROBE_EDGE - inset,
                    Paint().apply { color = PROBE_FOREGROUND },
                )
            }

            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private fun imagePart(dataUri: String): JSONObject = JSONObject()
        .put("type", "image_url")
        .put("image_url", JSONObject().put("url", dataUri))

    private companion object {
        /** 首次 + 一次重试 */
        private const val MAX_ATTEMPTS = 2

        private const val MAX_OUTPUT_TOKENS = 2048

        /**
         * 测试连接用的探针图边长（像素）。
         *
         * 取 256 而不是更小的值，是被一次真实事故倒逼出来的：
         * 最初用的是硬编码的 1×1 PNG，在阿里云百炼上**必然**返回
         * `<400> InternalError.Algo.InvalidParameter` —— 官方限制是
         * 「宽度和高度均须大于 10 像素」，且 qwen-vl-max / qwen-vl-plus 的
         * `min_pixels` 下限为 4096、Qwen3-VL 系列为 65536。
         *
         * 256 × 256 = 65536，正好满足最严格的那一档，同时对 OpenAI、豆包
         * 这些没有明确下限的服务也完全无害（纯色块 PNG 压缩后仅数百字节）。
         */
        private const val PROBE_EDGE = 256

        private const val PROBE_MAX_TOKENS = 64

        /** 探针图的背景色与前景色 —— 用应用主题色，便于肉眼确认图片确实生成了 */
        private val PROBE_BACKGROUND = Color.rgb(240, 248, 240)
        private val PROBE_FOREGROUND = Color.rgb(46, 107, 50)
    }
}
