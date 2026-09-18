package com.plantidentify.data.ai

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * 一次 chat/completions 请求的结果。
 *
 * 分成三类而不是「成功/失败」两类，是因为调用方对它们处理方式不同：
 *  - [Ok]：继续解析业务内容
 *  - [HttpError]：服务端明确回复了错误，可按状态码给出针对性提示；
 *    其中 [HttpError.unsupportedParams] 与 [HttpError.isImageRejection]
 *    还允许调用方做「去掉某个参数再试一次」之类的自适应降级
 *  - [TransportError]：连都没连上，只能提示网络或地址问题
 */
sealed interface ChatOutcome {

    /** 2xx，携带原始响应体（尚未解析 choices） */
    data class Ok(val rawBody: String) : ChatOutcome

    data class HttpError(
        val failure: AiFailure,
        /** 是否为「不认识的参数」类错误 —— 触发去掉 response_format 重试 */
        val unsupportedParams: Boolean = false,
        /** 是否与服务端拒绝图片输入有关 */
        val isImageRejection: Boolean = false,
    ) : ChatOutcome

    data class TransportError(val failure: AiFailure) : ChatOutcome
}

/**
 * OpenAI Compatible 的 chat/completions 调用。
 *
 * ## 为什么要有这一层
 *
 * 视觉识别与文字分析走的是同一个端点、同一套鉴权、同一套错误分类。
 * 如果不抽出来，两家 Provider 各自实现一遍「12 类错误的中文提示」，
 * 迟早会出现「视觉侧的提示改了、文字侧没改」这种不一致 ——
 * 而用户看到的是同一个「设置」页，不一致会直接暴露。
 *
 * 因此这里承担三件事：
 *  1. 拼 URL、发请求、读响应（含协程取消时真正 cancel 掉请求）
 *  2. 把传输层异常与 HTTP 状态码翻译成 [AiFailure]
 *  3. 从响应体里取出模型回复的正文
 *
 * ## 日志红线
 *
 * 绝不打印 API Key、Authorization 头、请求体、响应体全文。
 * 异常只记类型名；服务端细节经 [AiFailure.sanitize] 脱敏。
 */
class ChatCompletionsClient(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    /**
     * 发出请求。
     *
     * @param body 已构造好的请求体（由调用方决定带哪些字段）
     */
    suspend fun post(config: AiEndpointConfig, body: String): ChatOutcome {
        val request = try {
            Request.Builder()
                .url(buildChatCompletionsUrl(config.baseUrl))
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } catch (error: IllegalArgumentException) {
            // URL 非法，例如用户只填了 "abcd"
            return ChatOutcome.TransportError(AiFailure.EndpointUnreachable(error))
        }

        val response = try {
            httpClient.newCall(request).await()
        } catch (error: IOException) {
            return ChatOutcome.TransportError(classifyTransportError(error))
        }

        return response.use { res ->
            // 响应体必须读完才能释放连接；读失败时不抛给上层
            val text = runCatching { res.body.string() }.getOrDefault("")

            if (res.isSuccessful) {
                ChatOutcome.Ok(text)
            } else {
                val detail = AiFailure.extractDetail(text, config.apiKey)
                ChatOutcome.HttpError(
                    failure = classifyHttpError(res.code, detail),
                    unsupportedParams = isUnsupportedParamError(res.code, detail),
                    isImageRejection = isImageRejectionError(detail),
                )
            }
        }
    }

    /**
     * 取出模型回复的正文。
     *
     * 兼容两种形态：`content` 为字符串（常见），或为内容块数组
     * （部分多模态实现会把回复也拆成块）。
     *
     * @return 取不到时返回 null，由调用方转成 [AiFailure.InvalidResponse]
     */
    fun extractMessageContent(rawBody: String): String? {
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

    /** 服务端回显的模型名（可能与我们请求的一致，也可能是别名） */
    fun echoModelOf(rawBody: String, fallback: String): String =
        runCatching { JSONObject(rawBody).optString("model") }
            .getOrNull()
            .orEmpty()
            .ifBlank { fallback }

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
            Log.w(TAG, "请求传输失败：${error.javaClass.simpleName}")
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

    /**
     * 400 的错误信息是否指向模型名 —— 决定提示用户改模型名还是改别的。
     *
     * 取舍说明：不能只匹配裸的 `model` —— 服务端的图片拒绝信息里也常带
     * 那个词，会把「模型类型不对」误判成「模型名不对」。
     * 但也不能只匹配连写的 `model does not exist`：真实响应往往是
     * `The model \`xxx\` does not exist`，中间插了模型名，连写匹配不到。
     *
     * 因此采用「具体短语 + 泛化的存在性否定」组合。
     * 泛化短语之所以安全，是因为本函数只在 image rejection 判断**之后**被调用。
     */
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

    companion object {
        private const val TAG = "ChatClient"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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

        private val IMAGE_REJECTION_HINTS = listOf(
            "does not support image", "does not support vision",
            "not support image", "not support vision",
            "image_url is not supported", "unsupported image",
            "text only model", "text-only model",
            "不支持图片", "不支持图像",
        )

        /**
         * 拼接 chat/completions 的完整地址。
         *
         * 用户填 Base URL 的习惯不一：有人填到 `/v1`，有人把完整路径都填进来，
         * 也有人只填域名。这里统一兜底，避免用户因为「路径多写或少写一段」而失败。
         */
        fun buildChatCompletionsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return when {
                trimmed.endsWith("/chat/completions") -> trimmed
                trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
                else -> "$trimmed/v1/chat/completions"
            }
        }

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
