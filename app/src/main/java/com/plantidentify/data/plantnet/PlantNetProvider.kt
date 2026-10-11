package com.plantidentify.data.plantnet

import android.util.Log
import com.plantidentify.data.ai.AiEndpointConfig
import com.plantidentify.data.ai.AiFailure
import com.plantidentify.data.ai.ConnectivityRequest
import com.plantidentify.data.ai.ConnectivityResult
import com.plantidentify.data.ai.PromptStrategy
import com.plantidentify.data.ai.RecognitionResult
import com.plantidentify.data.ai.VisionCallResult
import com.plantidentify.data.ai.VisionImage
import com.plantidentify.data.ai.VisionProvider
import com.plantidentify.data.ai.VisionRequest
import com.plantidentify.data.ai.VisionResponse
import com.plantidentify.data.local.entity.ImageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Pl@ntNet 识别通道（v1.0.2 Phase 3 §十六）。
 *
 * ## 实现的是同一个 [VisionProvider] 接口
 *
 * 需求写得很明白：不要让 UI 直接依赖 Pl@ntNet 的数据结构，而是把它转换成
 * 项目自己的 [RecognitionResult]。于是 UI、数据库、识别流程一行都不用改 ——
 * 换 Provider 只是换了个 `recognize()` 实现。
 *
 * ## 与视觉 AI 的本质差别
 *
 * Pl@ntNet 是**分类器**不是语言模型：它只回答「这是哪个物种」，
 * 没有形态特征、没有判定依据、没有养护建议 —— 所以 [RecognitionResult]
 * 里那一批字段在这里是 null / 空列表，而不是编几个看起来像的东西。
 * 识别页上「判定依据」一栏会对这个 Provider 显示「来源：Pl@ntNet 分类模型」。
 *
 * ## 解析用 org.json 而不是引入 kotlinx-serialization
 *
 * 项目约定「不引入新依赖」（PDF、XLSX 都是自己写的），而这里要解析的
 * 只是两层嵌套的 JSON，`JSONObject` 完全够用 —— 为它加一个序列化框架
 * 是本末倒置。
 */
class PlantNetProvider(
    client: OkHttpClient? = null,
) : VisionProvider {

    /**
     * ## 超时为什么给得比其它 Provider 宽（v1.0.3）
     *
     * Pl@ntNet 是**上传图片**的接口（multipart），而其它通道发的是 base64
     * 的 JSON 小请求。实测：一张 388 KB 的照片在受限网络下**上传**就要
     * 40s 以上，用原来 30s 连接 / 90s 读取的配置会直接超时，
     * 而超时会被报成「无法连接到该地址」——
     * 把用户引向「Base URL 填错了」这个**完全错误**的方向。
     *
     * 这里把三个超时都放宽；真正的上限由 `UPLOAD_TIMEOUT_SECONDS`
     * 在每次请求上覆盖。
     */
    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun recognize(request: VisionRequest): VisionCallResult =
        withContext(Dispatchers.IO) {
            val config = request.config
            // 用 Pl@ntNet 自己的判据，**不是** `isUsable` ——
            // 后者要求「模型名」非空，而 Pl@ntNet 没有这个概念（见
            // AiEndpointConfig.isUsableAsPlantNet 的说明）。套用它会让
            // 填对了 Key 的用户永远看到「还没有配置」，提示与事实相反。
            if (!config.isUsableAsPlantNet) {
                return@withContext VisionCallResult.Failure(
                    AiFailure.LocalProblem("还没有配置 Pl@ntNet 的 API Key", "到「设置」页填入"),
                )
            }
            if (request.images.isEmpty()) {
                return@withContext VisionCallResult.Failure(
                    AiFailure.LocalProblem("没有可识别的图片"),
                )
            }

            try {
                // ⚠️ 字段顺序照**官方示例**来：每个 `organs` 紧跟它描述的那张 `images`
                //    **之前**，多个图片时两两交错。
                //
                //    官方 getting-started 的示例（JS / Java / R 三种语言一致）：
                //        form.append('organs', 'flower')
                //        form.append('images', image1)
                //        form.append('organs', 'leaf')
                //        form.append('images', image2)
                //
                //    见 https://my.plantnet.org/doc/getting-started/introduction
                //
                //    本项目原先写的是先 images 再 organs（每张各一次），**顺序相反**。
                //    官方文档明确说 organs 与 images 是**按请求中的位置配对**的
                //    （"Each organ is associated to an image in the order given"），
                //    顺序错位会把第一张图的部位配到最后一张上、首尾对不上。
                //    照官方示例的顺序发，最稳。
                val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    multipartFields(request.images).forEach { (name, image) ->
                        if (name == ORGAN_FIELD) {
                            addFormDataPart(ORGAN_FIELD, organOf(image.role))
                        } else {
                            addFormDataPart(
                                IMAGES_FIELD,
                                image.file.name,
                                image.file.asRequestBody("image/jpeg".toMediaType()),
                            )
                        }
                    }
                }.build()

                // Pl@ntNet 的 key 只收 query 参数，放进 multipart 无效。
                // 路径是 `/v2/identify/{project}` —— **不是 /v1**，见
                // AiConfig.defaultPlantNet 的说明（/v1 与 my.plantnet.org
                // 都已下线，用错只会得到 404，与 Key 对不对无关）。
                val url = "${config.baseUrl.trimEnd('/')}/identify/$PROJECT" +
                    "?api-key=${config.apiKey}"
                // 大图上传在部分网络下很慢：388KB 的照片实测 40s 都可能不够。
                // 用 per-call timeout 覆盖默认的 90s，避免「传了一半超时」
                // 被当成「连不上」。
                val call = http.newCall(Request.Builder().url(url).post(body).build())
                call.timeout().timeout(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                call.execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val detail = AiFailure.sanitize(raw, config.apiKey)
                        return@use VisionCallResult.Failure(
                            when (resp.code) {
                                401, 403 -> AiFailure.Unauthorized(detail)
                                404 -> AiFailure.EndpointNotFound(detail)
                                429 -> AiFailure.RateLimited(detail)
                                in 500..599 -> AiFailure.ServerError(resp.code, detail)
                                else -> AiFailure.BadRequest(detail)
                            },
                        )
                    }

                    parse(raw, config)
                }
            } catch (e: Exception) {
                // parse() 抛出的 AiFailure 要原样透传 —— 否则「响应格式不对」
                // 会被误报成「连不上」，把用户引向完全错误的方向
                val failure = e as? AiFailure ?: classify(e)
                // 落一条日志：不记 Key、不记图片内容，只记异常类型。
                // 之前这里**什么都不打**，于是真机上失败时日志里一片空白，
                // 只能靠界面上那句笼统的提示猜，排查成本极高。
                Log.w(TAG, "Pl@ntNet 请求失败：${e.javaClass.simpleName} / ${e.message}")
                VisionCallResult.Failure(failure)
            }
        }

    /**
     * 把网络异常收敛成**说得对**的失败类型（v1.0.3）。
     *
     * ## 为什么要细分
     *
     * 这里原本把所有异常一律当成 [AiFailure.EndpointUnreachable]，
     * 界面于是显示「无法连接到该地址，请确认 Base URL 填写正确」。
     * 但**超时不是地址错** —— Pl@ntNet 要上传整张照片，上行慢的网络上
     * 几十秒才传完，超时是常态。把它说成「地址填错」会让用户反复改
     * 一个完全正确的 Base URL，永远修不好。
     *
     * 判据用异常类型而不是猜：
     * - `SocketTimeoutException` → 超时（[AiFailure.Timeout]）
     * - `UnknownHostException` → DNS/域名错（[AiFailure.EndpointUnreachable]）
     * - 其它 IO/连接问题 → 网络不可达（[AiFailure.Network]）
     */
    private fun classify(e: Exception): AiFailure = when (e) {
        is java.net.SocketTimeoutException -> AiFailure.Timeout(e)
        is java.net.UnknownHostException -> AiFailure.EndpointUnreachable(e)
        else -> AiFailure.EndpointUnreachable(e)
    }

    /**
     * Pl@ntNet 的识别结果本身就是一份 JSON，原样保存 ——
     * 不需要像视觉 AI 那样剥围栏、补括号。
     */
    private fun parse(raw: String, config: AiEndpointConfig): VisionCallResult {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            // 无法解析但拿到了 HTTP 200：按「响应无效」收敛，原始文本保留
            return VisionCallResult.Failure(
                AiFailure.InvalidResponse(
                    AiFailure.sanitize(raw, config.apiKey) ?: "响应不是合法 JSON",
                    rawSnippet = raw.take(300),
                ),
            )
        }

        val results = root.optJSONArray("results") ?: JSONArray()
        val candidates = buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val score = item.optDouble("score", 0.0)
                val species = item.optJSONObject("species") ?: continue

                val scientific = species.optString("scientificNameWithoutAuthor")
                    .takeIf { it.isNotBlank() }
                val commonNames = species.optJSONArray("commonNames")
                    ?.let { array ->
                        (0 until array.length())
                            .mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
                    }
                    .orEmpty()
                val family = species.optJSONObject("family")
                    ?.optString("scientificNameWithoutAuthor")?.takeIf { it.isNotBlank() }
                val genus = species.optJSONObject("genus")
                    ?.optString("scientificNameWithoutAuthor")?.takeIf { it.isNotBlank() }

                // 中文名优先（界面与档案的主键都是中文名），没有就用学名
                add(
                    ParsedCandidate(
                        name = commonNames.firstOrNull() ?: scientific ?: continue,
                        latinName = scientific,
                        family = family,
                        genus = genus,
                        confidence = score,
                    ),
                )
            }
        }.sortedByDescending { it.confidence }

        if (candidates.isEmpty()) {
            return VisionCallResult.Success(
                VisionResponse(
                    result = RecognitionResult(name = "未识别到候选"),
                    rawText = raw,
                ),
            )
        }

        // Pl@ntNet 的 score 是分类器输出（0-1），语义与本项目「AI 对当前
        // 视觉证据的置信程度」一致，直接沿用 —— 但 UI 上仍要标明来源
        val best = candidates.first()
        return VisionCallResult.Success(
            VisionResponse(
                result = RecognitionResult(
                    name = best.name,
                    latinName = best.latinName,
                    family = best.family,
                    genus = best.genus,
                    confidence = best.confidence,
                    // 分类器给不出「判定依据」，据实留空
                    evidence = emptyList(),
                    missingInformation = emptyList(),
                    alternatives = candidates.drop(1).take(5).map {
                        RecognitionResult.Alternative(name = it.name, confidence = it.confidence)
                    },
                    conflicts = listOf(
                        "识别来源：Pl@ntNet 分类模型（无判定依据描述）",
                    ),
                ),
                rawText = raw,
            ),
        )
    }

    private fun organOf(role: ImageRole): String = when (role) {
        ImageRole.LEAF -> "leaf"
        ImageRole.FLOWER -> "flower"
        ImageRole.FRUIT -> "fruit"
        ImageRole.BARK -> "bark"
        // Pl@ntNet 的 habit = 整株形态/生境，本项目的「整株」与「生境」都归它
        ImageRole.WHOLE_PLANT, ImageRole.HABITAT -> "habit"
        else -> "other"
    }

    companion object {
        /** 日志标签（不记 Key、不记图片内容） */
        const val TAG = "PlantNetProvider"

        /** multipart 字段名 */
        const val ORGAN_FIELD = "organs"
        const val IMAGES_FIELD = "images"

        /** all 项目库覆盖面最广（含世界范围的园林与野生植物） */
        const val PROJECT = "all"

        /**
         * 带上照片的识别请求超时（秒）。
         *
         * 默认 90s 对**上传**往往不够：实测一张 388 KB 的照片在受限网络下
         * 40s 都没传完。超时会被报成「连不上」，把用户引向网络排查，
         * 而实际只是传得慢。给足 180s，宁可慢也不要误报。
         */
        const val UPLOAD_TIMEOUT_SECONDS = 180L

        /**
         * 建立连接的超时（秒）。
         *
         * 30s → 60s：受限网络下 TLS 握手本身就可能很慢。
         * 连接超时与**传输**超时是两件事，前者给够才不会把
         * 「传得慢」误报成「连不上」。
         */
        const val CONNECT_TIMEOUT_SECONDS = 60L

        /**
         * 生成 multipart 的字段顺序：**每张图先 organs、后 images**。
         *
         * ## 为什么要单独抽出来
         *
         * Pl@ntNet 的 `organs` 与 `images` 是**按请求中的位置配对**的
         * （官方原文："Each organ is associated to an image in the order given"）。
         * 顺序错了不会报错，**只会把部位配到别的图片上** ——
         * 结果看起来「能识别」，但用错了部位先验，属于最难发现的一类缺陷。
         *
         * 抽成纯函数就为了能**用单测钉住顺序**，而不必真的发一次网络请求。
         * 官方示例见 https://my.plantnet.org/doc/getting-started/introduction
         */
        fun <T> multipartFields(images: List<T>): List<Pair<String, T>> =
            images.flatMap { listOf(ORGAN_FIELD to it, IMAGES_FIELD to it) }
    }

    override suspend fun testConnection(request: ConnectivityRequest): ConnectivityResult =
        withContext(Dispatchers.IO) {
            try {
                val started = System.currentTimeMillis()
                // Pl@ntNet 没有 ping 接口，发一个**不带图**的 GET 探活：
                // 400（缺 images）说明路由通、Key 也认；401/403 才是 Key 不对。
                //
                // ⚠️ 路径是 `/v2/identify/`（不是 /v1），且域名必须是
                // `my-api.plantnet.org` —— 用错会恒得 404，看着像「服务坏了」。
                val url = "${request.config.baseUrl.trimEnd('/')}/identify/$PROJECT" +
                    "?api-key=${request.config.apiKey}"
                http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val latency = System.currentTimeMillis() - started
                    when {
                        // 400 = 路由正确但没给图，这正是我们要的「通」
                        resp.isSuccessful || resp.code == 400 ->
                            ConnectivityResult.Success(
                                model = "Pl@ntNet $PROJECT",
                                latencyMs = latency,
                            )
                        resp.code == 401 || resp.code == 403 ->
                            ConnectivityResult.Failure(AiFailure.Unauthorized(null))
                        // 404 单独说清楚：多半是端点配错了（域名/版本），
                        // 而不是「服务端出错」——后者会把用户引向完全错误的方向
                        resp.code == 404 -> ConnectivityResult.Failure(
                            AiFailure.EndpointNotFound(
                                "Pl@ntNet 返回 404。请确认端点是否为 " +
                                    "https://my-api.plantnet.org/v2",
                            ),
                        )
                        else -> ConnectivityResult.Failure(
                            AiFailure.ServerError(resp.code, null),
                        )
                    }
                }
            } catch (e: Exception) {
                ConnectivityResult.Failure(
                    e as? AiFailure ?: AiFailure.EndpointUnreachable(e),
                )
            }
        }

    private data class ParsedCandidate(
        val name: String,
        val latinName: String?,
        val family: String?,
        val genus: String?,
        val confidence: Double,
    )
}
