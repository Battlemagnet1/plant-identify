package com.plantidentify.data.ai

/**
 * AI 调用失败的分类（规格书第二十四节 + 验收标准 ③）。
 *
 * ## 为什么不用异常类型直接提示用户
 *
 * `UnknownHostException`、`SocketTimeoutException`、`SSLHandshakeException`
 * 这些名字对用户没有任何意义。用户需要知道的是两件事：
 *  1. **发生了什么**（用生活语言描述）
 *  2. **我该去哪儿改**（设置页的哪一项）
 *
 * 所以每种失败都带 [userMessage] 与 [fixHint] 两个字段。
 * 原始异常只保留在 [causeForLog] 中供排查，**永远不直接展示给用户**。
 *
 * ## 日志红线
 *
 * [causeForLog] 与 [serverDetail] 在生成前都会经过 [sanitize]，
 * 确保 API Key 不会随错误信息进入日志或界面。
 */
sealed class AiFailure(
    /** 展示给用户的中文说明 */
    val userMessage: String,

    /** 该去哪儿修 —— 没有可操作项时为 null */
    val fixHint: String? = null,

    /** 是否值得让用户直接重试（例如网络抖动、服务端 5xx） */
    val canRetry: Boolean = true,

    /** 服务端返回的细节（已脱敏、已截断），用于帮助用户判断 */
    val serverDetail: String? = null,

    /** 原始异常，仅供日志排查 */
    val causeForLog: Throwable? = null,
) {

    /**
     * 这个失败的详情是否**真的来自服务端**。
     *
     * 界面在给详情加「服务返回：」前缀之前必须先问这一句。以前不加区分，
     * 于是本地问题（照片读不出来、还没添加照片）也被写成
     * 「服务返回：照片读取失败」—— 用户会去查 Base URL、换模型、翻网络，
     * 而这个错误跟服务端一点关系都没有。**把用户指向错误的排查方向，
     * 比不提示更糟。**
     */
    open val isFromServer: Boolean = true

    /** 未配置好 AI 服务 */
    class NotConfigured(missing: List<String>) : AiFailure(
        userMessage = "还没有配置 AI 服务，缺少：${missing.joinToString("、")}",
        fixHint = "请到「设置」页填写 AI 服务的 Base URL、模型名与 API Key",
        canRetry = false,
    )

    /** 网络不可达：断网、DNS 失败、连接被拒绝、超时 */
    class Network(
        cause: Throwable,
    ) : AiFailure(
        userMessage = "无法连接到 AI 服务，请检查网络连接后重试",
        fixHint = "若使用自建或局域网服务，请确认手机与该服务在同一网络内",
        causeForLog = cause,
    )

    /** 连接建立失败但网络本身通畅 —— 多半是 Base URL 写错 */
    class EndpointUnreachable(
        cause: Throwable,
    ) : AiFailure(
        userMessage = "无法连接到该地址，请确认 Base URL 填写正确",
        fixHint = "到「设置」页检查 Base URL，注意末尾的 /v1 是否与服务商文档一致",
        causeForLog = cause,
    )

    /** HTTPS 证书问题 */
    class Tls(
        cause: Throwable,
    ) : AiFailure(
        userMessage = "安全连接建立失败，该地址的证书不受信任",
        fixHint = "请确认 Base URL 是正确的服务地址；自建服务需使用有效的 HTTPS 证书",
        canRetry = false,
        causeForLog = cause,
    )

    /**
     * 明文 HTTP 被系统安全策略拦截。
     *
     * 这是用户把 Base URL 填成 `http://` 时几乎必然撞到的错误 —— Android 9 起
     * 默认禁止明文流量，抛出的 `UnknownServiceException` 原文是
     * 「CLEARTEXT communication to xxx not permitted by network security policy」，
     * 直接展示给用户等于没说。这里翻译成「该换成 https」这个明确动作。
     */
    class CleartextBlocked : AiFailure(
        userMessage = "该地址使用明文 HTTP，系统出于安全考虑拒绝了连接",
        fixHint = "请把 Base URL 的 http:// 改为 https://；" +
            "本地服务可改用 127.0.0.1 或 10.0.2.2 这类系统允许的地址",
        canRetry = false,
    )

    /** 401 / 403：API Key 无效、过期、无权限 */
    class Unauthorized(detail: String?) : AiFailure(
        userMessage = "API Key 无效或已过期（服务返回 401/403）",
        fixHint = "到「设置」页重新填写 API Key，注意不要带多余空格",
        canRetry = false,
        serverDetail = detail,
    )

    /** 404：路径不存在 —— 通常是 Base URL 少了或多了路径段 */
    class EndpointNotFound(detail: String?) : AiFailure(
        userMessage = "服务地址不存在（404），Base URL 路径可能不正确",
        fixHint = "常见的 Base URL 形如 https://服务商域名/v1，请对照服务商文档核对",
        canRetry = false,
        serverDetail = detail,
    )

    /** 400 且错误信息指向模型：模型名不存在或未开通 */
    class ModelNotFound(detail: String?) : AiFailure(
        userMessage = "模型名不正确，或该模型未在你的账号下开通",
        fixHint = "到「设置」页核对模型名；豆包需填写控制台的模型 ID 或 ep- 开头的接入点 ID",
        canRetry = false,
        serverDetail = detail,
    )

    /**
     * 模型存在，但它不接受图片输入 —— 也就是配成了纯文本模型。
     *
     * 为什么单列一类：这是配置失误里最常见的一种，而服务端返回的原文
     * 往往形如「Invalid content type: image_url is not supported by this
     * text only model」。这句话里含 "model" 一词，如果笼统地按「模型问题」
     * 归类，用户会去反复核对模型名 —— 而真正的问题是**选错了模型类型**。
     * 分开之后提示才能落到正确的动作上：换一个视觉模型。
     */
    class ModelNotVisionCapable(detail: String?) : AiFailure(
        userMessage = "该模型不接受图片输入，无法用于植物识别",
        fixHint = "请到「设置」页换一个支持图片的视觉模型（模型名通常带 vl / vision 字样）",
        canRetry = false,
        serverDetail = detail,
    )

    /** 400 且与模型无关：多半是请求格式或图片不被接受 */
    class BadRequest(detail: String?) : AiFailure(
        userMessage = "请求被服务拒绝，服务端认为参数不符合要求",
        fixHint = "常见原因：图片尺寸不符合模型要求、图片格式不被支持、或图片数量超出上限。" +
            "可尝试减少照片数量，或换一张常见格式（JPEG / PNG）的照片",
        canRetry = false,
        serverDetail = detail,
    )

    /** 413 / 请求体过大 */
    class PayloadTooLarge(detail: String?) : AiFailure(
        userMessage = "上传的图片体积超过了服务商限制",
        fixHint = "可减少照片数量后再试",
        canRetry = false,
        serverDetail = detail,
    )

    /** 429：限流或额度耗尽 */
    class RateLimited(detail: String?) : AiFailure(
        userMessage = "请求过于频繁或额度已用完（服务返回 429）",
        fixHint = "请稍后再试，或到服务商控制台查看额度",
        serverDetail = detail,
    )

    /** 5xx：服务端问题 */
    class ServerError(
        val statusCode: Int,
        detail: String?,
    ) : AiFailure(
        userMessage = "AI 服务暂时不可用（服务返回 $statusCode）",
        fixHint = "这是服务商侧的问题，稍后重试通常即可恢复",
        serverDetail = detail,
    )

    /** 返回内容不是预期的 JSON 结构 */
    class InvalidResponse(
        detail: String?,
        val rawSnippet: String?,
    ) : AiFailure(
        userMessage = "服务返回的内容不是预期的格式，可能该模型不完全兼容 OpenAI 接口",
        fixHint = "可换用服务商文档中标注「兼容 OpenAI 接口」的模型",
        serverDetail = detail,
    )

    /** 超时（连接、读取、整体） */
    class Timeout(
        cause: Throwable,
    ) : AiFailure(
        userMessage = "请求超时。图片较多时识别耗时会明显变长",
        fixHint = "可稍后重试，或减少照片数量",
        causeForLog = cause,
    )

    /** 其他未归类失败 */
    class Unknown(
        detail: String?,
        cause: Throwable? = null,
    ) : AiFailure(
        userMessage = "识别失败，发生了未预期的问题",
        fixHint = "可稍后重试；若持续出现，请到「设置」页用「测试连接」定位问题",
        serverDetail = detail,
        causeForLog = cause,
    )

    /**
     * 与 AI 服务无关的本地问题：草稿里还没有照片、照片文件读不出来等等。
     *
     * 单列一类而不复用 [Unknown]，是因为两者的**处理方式**根本不同：
     * [Unknown] 带着服务端细节，界面会加「服务返回：」前缀；
     * 而这里的问题出在本机，用户该做的是回去重新添加照片，
     * 提示里不该出现任何指向服务端的字样。
     *
     * 故意不给 [serverDetail] 赋值 —— 没有服务端参与，就没有服务端细节可讲。
     */
    class LocalProblem(
        message: String,
        hint: String? = null,
        cause: Throwable? = null,
    ) : AiFailure(
        userMessage = message,
        fixHint = hint,
        // 重试同样的参数只会得到同样的结果，用户得先动手改点什么
        canRetry = false,
        causeForLog = cause,
    ) {
        override val isFromServer: Boolean get() = false
    }

    companion object {
        /**
         * 从服务端错误响应中提取可读细节。
         *
         * 兼容四种常见结构：
         *  - `{"error": {"code": "...", "message": "..."}}` —— OpenAI 及多数兼容实现
         *  - `{"code": "...", "message": "..."}` —— 部分国内服务（DashScope 系）
         *  - `{"message": "..."}` —— 简单实现
         *  - `{"error": "..."}` —— 更简单的实现
         *
         * **同时保留 code**：像阿里云百炼这类服务，message 可能只有一句
         * `<400> InternalError.Algo.InvalidParameter`，光看它无法定位问题；
         * 而 code 里的 `InvalidParameter` 至少指明了「参数层面」这个方向。
         *
         * @param apiKey 用于脱敏：若服务端把请求内容回显进错误信息，
         *               Key 可能被夹带出来，一律替换掉。
         */
        fun extractDetail(body: String?, apiKey: String? = null): String? {
            if (body.isNullOrBlank()) return null

            val raw = runCatching {
                val json = org.json.JSONObject(body)

                json.optJSONObject("error")
                    ?.let { err -> combine(err.optString("message"), err.optString("code")) }
                    ?.let { return@runCatching it }

                combine(json.optString("message"), json.optString("code"))
                    ?: json.optString("error").takeIf { it.isNotBlank() }
            }.getOrNull() ?: body

            return sanitize(raw, apiKey)
        }

        /** 把 message 与 code 合成一句可读文本，两者相同或缺失时自动退化 */
        private fun combine(message: String?, code: String?): String? {
            val msg = message?.trim().orEmpty()
            val cd = code?.trim().orEmpty()
            return when {
                msg.isNotEmpty() && cd.isNotEmpty() && !msg.contains(cd) -> "$msg（code: $cd）"
                msg.isNotEmpty() -> msg
                cd.isNotEmpty() -> cd
                else -> null
            }
        }

        /**
         * 脱敏与截断。
         *
         * 两道处理都是为了「不让敏感信息出现在日志或界面上」这条验收标准：
         *  - 用 `***` 替换任何形如 API Key 的片段
         *  - 截断到 300 字，避免服务端返回整页 HTML 时把界面撑爆
         */
        fun sanitize(text: String?, apiKey: String? = null): String? {
            if (text.isNullOrBlank()) return null
            val result = redact(text, apiKey).trim()
            return if (result.length <= MAX_DETAIL_LENGTH) {
                result
            } else {
                result.take(MAX_DETAIL_LENGTH) + "…"
            }
        }

        /**
         * 只做脱敏，**不截断**。
         *
         * 拆出来是给崩溃日志用的：那里要落一份完整堆栈，截到 300 字等于白记。
         * 但「API Key 不出现在任何日志中」这条红线对崩溃日志同样成立 ——
         * 所以两处共用同一套匹配规则，而不是各写一份正则（各写一份迟早会漏）。
         */
        fun redact(text: String, apiKey: String? = null): String {
            var result = text
            if (!apiKey.isNullOrBlank() && apiKey.length >= 8) {
                result = result.replace(apiKey, "***")
            }
            // 兜底：即使拿不到具体 Key，也拦掉常见的 Key 字面量形态
            return result
                .replace(Regex("""sk-[A-Za-z0-9_\-]{12,}"""), "***")
                .replace(Regex("""ark-[A-Za-z0-9_\-]{12,}"""), "***")
                .replace(Regex("""Bearer\s+[A-Za-z0-9_\-.]{12,}"""), "Bearer ***")
        }

        /** 错误信息面向用户展示的最长长度 */
        private const val MAX_DETAIL_LENGTH = 300
    }
}
