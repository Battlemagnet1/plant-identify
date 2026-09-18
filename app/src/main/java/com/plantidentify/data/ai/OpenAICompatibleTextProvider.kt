package com.plantidentify.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 基于 OpenAI Compatible 协议的植物百科生成实现。
 *
 * 与视觉通道共用 [ChatCompletionsClient]：同一个端点、同一套鉴权、
 * 同一套 12 类错误的中文提示。两边如果各写一遍，迟早会出现
 * 「视觉侧的提示改了而文字侧没改」的不一致。
 *
 * ## 为什么这条链路允许失败
 *
 * 规格书第三十节：**文字分析失败不能导致植物识别结果丢失**。
 * 图像识别已经看过照片并给出结论，这部分才是一株植物的「身份」；
 * 百科内容只是锦上添花。因此这里的任何失败都只影响档案里的描述段落，
 * 不影响档案本身是否保存。
 *
 * ## 重试策略
 *
 * 与识别一致：首次带上 `response_format` 与 `temperature`，
 * 服务端不认这些参数就去掉重试；输出不是合法 JSON 就带上纠正说明重试一次。
 * 用尽重试后**如实报失败**，不像识别那样降级成半结构化 ——
 * 百科的七个字段没有哪个能独立代表一次成功的分析。
 */
class OpenAICompatibleTextProvider(
    private val client: ChatCompletionsClient = ChatCompletionsClient(),
) : TextProvider {

    override suspend fun generateAnalysis(request: TextAnalysisRequest): TextAnalysisResult {
        val config = request.config
        if (!config.isUsable) {
            return TextAnalysisResult.Failure(AiFailure.NotConfigured(config.missingFields))
        }

        var includeStructuredParams = true
        var correction: String? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = postAnalysis(request, correction, includeStructuredParams)) {
                is ChatOutcome.Ok -> {
                    val content = client.extractMessageContent(outcome.rawBody)
                        ?: return TextAnalysisResult.Failure(
                            AiFailure.InvalidResponse(
                                detail = "响应中找不到模型回复的正文",
                                rawSnippet = AiFailure.sanitize(outcome.rawBody),
                            ),
                        )

                    when (val parsed = TolerantJsonParser.parseAnalysis(content)) {
                        is TolerantJsonParser.AnalysisParseResult.Ok ->
                            return TextAnalysisResult.Success(
                                analysis = parsed.analysis,
                                rawText = content,
                                parseNote = parsed.note,
                            )

                        is TolerantJsonParser.AnalysisParseResult.Failed -> {
                            if (attempt < MAX_ATTEMPTS - 1) {
                                correction = "你上次的输出无法解析：${parsed.reason}。" +
                                    "请只输出一个 JSON 对象，不要有其他文字或代码块标记。"
                            } else {
                                return TextAnalysisResult.Failure(
                                    AiFailure.InvalidResponse(
                                        detail = parsed.reason,
                                        rawSnippet = AiFailure.sanitize(content),
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
                        return TextAnalysisResult.Failure(outcome.failure)
                    }
                }

                is ChatOutcome.TransportError ->
                    return TextAnalysisResult.Failure(outcome.failure)
            }
        }

        return TextAnalysisResult.Failure(AiFailure.Unknown("重试后仍未获得可用的分析结果"))
    }

    override suspend fun testConnection(request: ConnectivityRequest): ConnectivityResult {
        val config = request.config
        if (!config.isUsable) {
            return ConnectivityResult.Failure(AiFailure.NotConfigured(config.missingFields))
        }

        val startedAt = System.currentTimeMillis()

        // 纯文本最小请求：文字通道不需要验证图片能力
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", PROBE_MAX_TOKENS)
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "请回复两个字：收到"),
                ),
            )
        }

        return when (val outcome = withContext(Dispatchers.IO) {
            client.post(config, body.toString())
        }) {
            is ChatOutcome.Ok -> ConnectivityResult.Success(
                model = client.echoModelOf(outcome.rawBody, config.model),
                latencyMs = System.currentTimeMillis() - startedAt,
            )

            is ChatOutcome.HttpError -> ConnectivityResult.Failure(outcome.failure)
            is ChatOutcome.TransportError -> ConnectivityResult.Failure(outcome.failure)
        }
    }

    private suspend fun postAnalysis(
        request: TextAnalysisRequest,
        correction: String?,
        includeStructuredParams: Boolean,
    ): ChatOutcome = withContext(Dispatchers.IO) {
        val prompt = PromptBuilder.buildAnalysisPrompt(
            name = request.name,
            latinName = request.latinName,
            family = request.family,
            genus = request.genus,
            category = request.category,
            confidence = request.confidence,
            evidence = request.evidence,
        )

        val body = JSONObject().apply {
            put("model", request.config.model)
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", buildString {
                            append(prompt)
                            if (correction != null) {
                                append("\n\n## 重要：上次输出格式有误\n\n")
                                append(correction)
                            }
                        }),
                ),
            )
            // 百科写作允许一点措辞自由度，但不能发散 —— 准确性优先
            put("temperature", 0.3)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            if (includeStructuredParams) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
        }

        client.post(request.config, body.toString())
    }

    private companion object {
        /** 首次 + 一次重试 */
        private const val MAX_ATTEMPTS = 2

        /** 七个字段各 1–3 句，1500 tokens 有充足余量 */
        private const val MAX_OUTPUT_TOKENS = 1500

        /** 测试连接用的极小上限 —— 只要求回两个字 */
        private const val PROBE_MAX_TOKENS = 32
    }
}
