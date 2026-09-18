package com.plantidentify.data.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 基于 OpenAI Compatible 协议的视觉识别实现。
 *
 * 首版预置的 Qwen / 豆包 / OpenAI 三家都走这一条路径，
 * 差异全部收敛到 [AiEndpointConfig] 的数据里 —— 不为每家写一个类。
 *
 * ## 日志红线（验收标准 ④）
 *
 * 本类**绝不**打印以下内容：API Key、Authorization 请求头、请求体、响应体全文。
 * 异常只记录类型名，服务端返回的细节一律先经 [AiFailure.sanitize] 脱敏。
 * 这也是刻意不引入 okhttp 的 logging-interceptor 的原因 ——
 * 它默认会把 `Authorization: Bearer sk-xxx` 打进 Logcat。
 *
 * ## 超时取值
 *
 * 5 张 1536px / JPEG 80 的图 base64 后约 1.7–2.7 MB（分析报告 Part 3.3），
 * 且多图联合推理本身较慢，因此写、读超时都给得比较宽。
 */
class OpenAICompatibleVisionProvider(
    private val client: OkHttpClient = defaultClient(),
) : VisionProvider {

    override suspend fun recognize(request: VisionRequest): VisionCallResult {
        val config = request.config

        if (!config.isUsable) {
            return VisionCallResult.Failure(AiFailure.NotConfigured(config.missingFields))
        }
        if (request.images.isEmpty()) {
            return VisionCallResult.Failure(
                AiFailure.Unknown("没有可识别的照片"),
            )
        }

        // 第一轮带上 response_format 与 temperature 争取更稳定的输出；
        // 若服务端表示不认识这些参数，则去掉它们重试（见下方 unsupportedParams 分支）
        var includeStructuredParams = true
        var correction: String? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = postRecognition(request, correction, includeStructuredParams)) {
                is HttpOutcome.Ok -> {
                    val content = extractMessageContent(outcome.rawBody)

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

                is HttpOutcome.HttpError -> {
                    if (includeStructuredParams && outcome.unsupportedParams) {
                        includeStructuredParams = false
                    } else {
                        return VisionCallResult.Failure(outcome.failure)
                    }
                }

                is HttpOutcome.TransportError -> return VisionCallResult.Failure(outcome.failure)
            }
        }

        return VisionCallResult.Failure(AiFailure.Unknown("重试后仍未获得可用的识别结果"))
    }

    override suspend fun testConnection(request: ConnectivityRequest): ConnectivityResult {
        val config = request.config
        if (!config.isUsable) {
            return ConnectivityResult.Failure(AiFailure.NotConfigured(config.missingFields))
        }

        // 带一张 1×1 的 PNG：请求体几乎为零，又能顺带验证该模型是否接受图片输入
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", PROBE_MAX_TOKENS)
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(imagePart(PROBE_IMAGE_DATA_URI))
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", PromptBuilder.buildConnectivityPrompt()),
                                ),
                        ),
                ),
            )
        }

        val startedAt = System.currentTimeMillis()

        return when (val outcome = postChatCompletions(config, body.toString())) {
            is HttpOutcome.Ok -> {
                val echoModel = runCatching {
                    JSONObject(outcome.rawBody).optString("model")
                }.getOrNull().orEmpty().ifBlank { config.model }

                val content = extractMessageContent(outcome.rawBody)
                if (content == null) {
                    ConnectivityResult.Success(
                        model = echoModel,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        acceptsImages = true,
                    )
                } else {
                    // 拿到了回复就说明链路通畅；此处不再校验回复内容是否符合我们的模板
                    ConnectivityResult.Success(
                        model = echoModel,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        acceptsImages = true,
                    )
                }
            }

            is HttpOutcome.HttpError ->
                // 配成纯文本模型是最常见的配置失误，单列一类给出针对性提示
                if (outcome.isImageRejection) {
                    ConnectivityResult.TextOnlyModel(config.model)
                } else {
                    ConnectivityResult.Failure(outcome.failure)
                }

            is HttpOutcome.TransportError -> ConnectivityResult.Failure(outcome.failure)
        }
    }

    // ---------------- 请求构造与执行 ----------------

    /** HTTP 层的结果 —— 已区分「传输失败」与「服务端返回错误状态」两类 */
    private sealed interface HttpOutcome {

        /** 2xx，携带原始响应体（尚未解析 choices） */
        data class Ok(val rawBody: String) : HttpOutcome

        data class HttpError(
            val failure: AiFailure,
            /** 是否为「不认识的参数」类错误 —— 触发去掉 response_format 重试 */
            val unsupportedParams: Boolean = false,
            /** 是否与服务端拒绝图片输入有关 */
            val isImageRejection: Boolean = false,
        ) : HttpOutcome

        data class TransportError(val failure: AiFailure) : HttpOutcome
    }

    private suspend fun postRecognition(
        request: VisionRequest,
        correction: String?,
        includeStructuredParams: Boolean,
    ): HttpOutcome = withContext(Dispatchers.IO) {
        // 读取压缩副本并做 base64 编码
        val dataUris = ArrayList<String>(request.images.size)
        for (image in request.images) {
            if (!image.file.isFile) {
                return@withContext HttpOutcome.TransportError(
                    AiFailure.Unknown("有一张待上传的图片已不存在，请重新添加照片"),
                )
            }
            val bytes = runCatching { image.file.readBytes() }.getOrElse { error ->
                return@withContext HttpOutcome.TransportError(
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

        postChatCompletions(request.config, body.toString())
    }

    /**
     * 发出 chat/completions 请求。
     *
     * 所有 IO 异常在此转成 [AiFailure]，保证调用方永远拿到的是分类好的失败而非裸异常。
     */
    private suspend fun postChatCompletions(
        config: AiEndpointConfig,
        body: String,
    ): HttpOutcome {
        val httpRequest = try {
            Request.Builder()
                .url(buildChatCompletionsUrl(config.baseUrl))
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } catch (error: IllegalArgumentException) {
            // URL 非法，例如用户只填了 "abcd"
            return HttpOutcome.TransportError(AiFailure.EndpointUnreachable(error))
        }

        val response = try {
            client.newCall(httpRequest).await()
        } catch (error: IOException) {
            return HttpOutcome.TransportError(classifyTransportError(error))
        }

        return response.use { res ->
            // 响应体必须读完才能释放连接；读失败时不抛给上层
            val text = runCatching { res.body.string() }.getOrDefault("")

            if (res.isSuccessful) {
                HttpOutcome.Ok(text)
            } else {
                val detail = AiFailure.extractDetail(text, config.apiKey)
                HttpOutcome.HttpError(
                    failure = classifyHttpError(res.code, detail),
                    unsupportedParams = isUnsupportedParamError(res.code, detail),
                    isImageRejection = isImageRejectionError(detail),
                )
            }
        }
    }

    // ---------------- 响应解析 ----------------

    /**
     * 取出模型回复的正文。
     *
     * 兼容两种形态：`content` 为字符串（常见），或为内容块数组
     * （部分多模态实现会把回复也拆成块）。
     *
     * @return 取不到时返回 null，由调用方转成 [AiFailure.InvalidResponse]
     */
    private fun extractMessageContent(rawBody: String): String? {
        val json = runCatching { JSONObject(rawBody) }.getOrNull() ?: return null
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null

        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null

        when (val content = message.opt("content")) {
            is String -> return content.takeIf { it.isNotBlank() }

            is JSONArray -> {
                val text = buildString {
                    for (i in 0 until content.length()) {
                        val block = content.optJSONObject(i) ?: continue
                        if (block.optString("type") == "text") {
                            append(block.optString("text"))
                        }
                    }
                }
                return text.takeIf { it.isNotBlank() }
            }

            else -> Unit
        }

        // 少数服务把正文放在 reasoning_content
        return message.optString("reasoning_content").takeIf { it.isNotBlank() }
    }

    // ---------------- 错误分类 ----------------

    /**
     * 传输层异常 → 用户可理解的失败。
     *
     * 判断顺序有意义：子类必须排在父类之前，
     * 否则 `SocketTimeoutException` 会被兜底的 `IOException` 分支先吃掉。
     */
    private fun classifyTransportError(error: IOException): AiFailure = when {
        // 明文 HTTP 被网络安全策略拦截 —— 用户把 Base URL 填成 http:// 时的典型错误
        error is UnknownServiceException &&
            error.message.orEmpty().contains("CLEARTEXT", ignoreCase = true) ->
            AiFailure.CleartextBlocked()

        error is SocketTimeoutException -> AiFailure.Timeout(error)

        error is UnknownHostException -> AiFailure.Network(error)

        error is ConnectException -> AiFailure.EndpointUnreachable(error)

        error is SSLException -> AiFailure.Tls(error)

        // OkHttp 包了一层的连接失败
        error.message.orEmpty().contains("Failed to connect", ignoreCase = true) ->
            AiFailure.EndpointUnreachable(error)

        else -> {
            // 只记录异常类型名。message 可能含请求 URL 或服务端回显的凭据，不进日志
            Log.w(TAG, "识别请求传输失败：${error.javaClass.simpleName}")
            AiFailure.Network(error)
        }
    }

    private fun classifyHttpError(code: Int, detail: String?): AiFailure = when (code) {
        // 顺序有意义：先判「模型不接受图片」，再判「模型名不对」。
        // 服务端的图片拒绝信息里常常也含 "model" 一词
        // （如「...not supported by this text only model」），
        // 若先按模型名问题归类，用户会去反复核对一个其实正确的模型名。
        400, 422 -> when {
            isImageRejectionError(detail) -> AiFailure.ModelNotVisionCapable(detail)
            isModelError(detail) -> AiFailure.ModelNotFound(detail)
            else -> AiFailure.BadRequest(detail)
        }

        401, 403 -> AiFailure.Unauthorized(detail)

        404 -> AiFailure.EndpointNotFound(detail)

        413 -> AiFailure.PayloadTooLarge(detail)

        429 -> AiFailure.RateLimited(detail)

        in 500..599 -> AiFailure.ServerError(code, detail)

        else -> AiFailure.Unknown(detail ?: "服务返回 $code")
    }

    /** 400 的错误信息是否指向模型名 —— 决定提示用户改模型名还是改别的 */
    private fun isModelError(detail: String?): Boolean {
        val text = detail?.lowercase() ?: return false
        return MODEL_ERROR_HINTS.any { it in text }
    }

    /** 错误信息是否在抱怨我们不认识的参数 */
    private fun isUnsupportedParamError(code: Int, detail: String?): Boolean {
        if (code != 400 && code != 422) return false
        val text = detail?.lowercase() ?: return false
        return UNSUPPORTED_PARAM_HINTS.any { it in text }
    }

    /** 错误信息是否在抱怨图片输入不被支持 */
    private fun isImageRejectionError(detail: String?): Boolean {
        val text = detail?.lowercase() ?: return false
        return IMAGE_REJECTION_HINTS.any { it in text }
    }

    // ---------------- 工具 ----------------

    /**
     * 拼接 chat/completions 的完整地址。
     *
     * 用户填 Base URL 的习惯不一：有人填到 `/v1`，有人把完整路径都填进来，
     * 也有人只填域名。这里统一兜底，避免用户因为「路径多写或少写一段」而失败。
     */
    internal fun buildChatCompletionsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun imagePart(dataUri: String): JSONObject = JSONObject()
        .put("type", "image_url")
        .put("image_url", JSONObject().put("url", dataUri))

    /**
     * 把 OkHttp 的异步调用桥接成挂起函数，并在协程取消时真正取消请求。
     *
     * 直接 `execute()` 也能用，但用户中途返回上一页时请求会跑到底，
     * 白白消耗流量与费用。
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }

        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        // 协程已取消但响应仍到达：必须显式关闭，否则泄漏连接
                        runCatching { response.close() }
                    }
                }
            },
        )
    }

    companion object {
        private const val TAG = "VisionProvider"

        /** 首次 + 一次重试 */
        private const val MAX_ATTEMPTS = 2

        private const val MAX_OUTPUT_TOKENS = 2048
        private const val PROBE_MAX_TOKENS = 64

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 1×1 像素的 PNG，用于「测试连接」时验证模型是否接受图片输入 */
        private const val PROBE_IMAGE_DATA_URI =
            "data:image/png;base64," +
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

        /**
         * 指向「模型名不存在」的短语。
         *
         * 取舍说明：不能只匹配裸的 `model` —— 服务端的图片拒绝信息里也常带
         * 那个词（「not supported by this text only model」），会把
         * 「模型类型不对」误判成「模型名不对」。
         * 但也不能只匹配连写的 `model does not exist`：真实响应往往是
         * `The model \`xxx\` does not exist`，中间插了模型名，连写匹配不到。
         *
         * 因此采用「具体短语 + 泛化的存在性否定」组合。
         * 泛化短语之所以安全，是因为本函数只在 image rejection 判断**之后**
         * 被调用 —— 图片类错误已经被前一步领走了。
         */
        private val MODEL_ERROR_HINTS = listOf(
            "model not found", "model does not exist", "model not exist",
            "model_not_found", "unsupported model", "invalid model",
            "unknown model", "no such model", "no permission to access model",
            "does not exist", "not exist", "not found",
            "模型不存在", "模型名不正确", "不支持的模型",
        )

        private val UNSUPPORTED_PARAM_HINTS = listOf(
            "response_format", "temperature", "max_tokens",
            "unsupported parameter", "unknown parameter", "unrecognized",
        )

        /**
         * 指向「该模型不吃图片」的短语。
         *
         * 刻意不匹配裸的 `image_url`：服务端也可能因为别的原因提到它
         * （例如「image_url is required」是要求**提供**图片，含义正相反）。
         * 只匹配能确定「图片被拒绝」的表述。
         */
        private val IMAGE_REJECTION_HINTS = listOf(
            "does not support image", "does not support vision",
            "not support image", "not support vision",
            "image_url is not supported", "unsupported image",
            "text only model", "text-only model",
            "不支持图片", "不支持图像",
        )

        /**
         * 默认 OkHttp 客户端。
         *
         * - 连接 20s：给弱网留余量，又不至于让用户干等
         * - 写入 60s：5 张图 base64 后约 2.7 MB，慢速网络上传需要时间
         * - 读取 180s：多图联合推理较慢，超时给足，否则用户会看到「超时」
         *   而实际上模型马上就要答完了
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
