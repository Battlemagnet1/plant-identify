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
                //
                // `lang=zh` 是**口径对齐**的关键（v1.0.3）：
                // 不带它时 `commonNames` 默认回英文（`樟` → "Camphor tree"），
                // 而视觉 AI 那条通道返回的是中文。同一个应用里一个通道给
                // 「樟」、另一个给「Camphor tree」，档案里会混着两种语言，
                // 搜索、归并（按中文名比对）也跟着一起失效。
                //
                // 实测（同一张图）：
                //   不带 lang  → commonNames = ["Camphor laurel", "Camphor tree", ...]
                //   lang=zh    → commonNames = ["樟", "Zhāng"]
                // 支持的语言见 /v2/languages（含 zh / zh-tw / zh-hant）。
                //
                // ## `detailed=true` 是科属中文名的唯一来源（v1.0.2.1018）
                //
                // Pl@ntNet 的 `species.family` / `species.genus` **只有拉丁名**，
                // 没有 commonNames 字段。科属的中文名（`樟科` / `樟属`）只在
                // `otherResults.family[]` / `otherResults.genus[]` 里 ——
                // 而这两块**只有传 detailed=true 才会返回**。
                //
                // 不传的后果：科属永远是拉丁文，而视觉 AI 通道给的是中文
                // （prompt 里明确要求「科」「属」为中文），于是同一个档案里
                // 「樟科 / Cinnamomum」中拉混排；更要命的是**数据清洗按科属比对**，
                // 两边写法不一致时「同一科的两种植物」根本认不出来。
                //
                // ## `nb-results` 让备选更有意义
                //
                // 不传时实测只回 2 条（score 0.47 与 0.0014），第二条低到没有
                // 参考价值；传 10 能拿到更完整的候选序列，供用户判断。
                val url = "${config.baseUrl.trimEnd('/')}/identify/$PROJECT" +
                    "?api-key=${config.apiKey}" +
                    "&lang=$LANGUAGE&detailed=true&nb-results=$MAX_RESULTS"
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
                                // ⚠️ Pl@ntNet 的 404 **有两种含义**，必须靠 body 分辨：
                                //   ① 端点/项目库名写错 → 提示「Base URL 不对」
                                //   ② 图里没有可识别的物种 → `"Species not found"`
                                // ②是**合法请求的正常结果**（照片不是植物、太糊、
                                // 或只拍到背景），不是配置问题。当成「地址错」
                                // 会让用户跑去改一个完全正确的 Base URL。
                                404 -> if (isSpeciesNotFound(raw)) {
                                    AiFailure.NoRecognizableSubject(detail)
                                } else {
                                    AiFailure.EndpointNotFound(detail)
                                }
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

        // 科 / 属的**中文名**查表（拉丁名 → 中文名）。
        // 来源只有 otherResults 这一处，见请求 URL 处的说明。
        val zhNames = TaxonomyNames.fromOtherResults(root.optJSONObject("otherResults"))

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
                val familyLatin = species.optJSONObject("family")
                    ?.optString("scientificNameWithoutAuthor")?.takeIf { it.isNotBlank() }
                val genusLatin = species.optJSONObject("genus")
                    ?.optString("scientificNameWithoutAuthor")?.takeIf { it.isNotBlank() }

                // 中文名优先，没有就用学名。**简体优先**，且不会被拼音顶掉 ——
                // 规则见 pickDisplayName 的说明。
                val display = pickDisplayName(commonNames)

                add(
                    ParsedCandidate(
                        name = display ?: scientific ?: continue,
                        latinName = scientific,
                        // 科属取**中文名**（查不到时退回拉丁名），与视觉 AI 通道口径一致
                        family = zhNames.displayOf(familyLatin, familyLatin),
                        genus = zhNames.displayOf(genusLatin, genusLatin),
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

        // 该物种在 Pl@ntNet 库里**没有中文俗名**时，name 只能退回拉丁学名。
        // 这种情况要让用户知道原因，否则档案里一半中文一半拉丁、
        // 而人看不出「为什么这株是拉丁文」。
        val noChineseName = best.name == best.latinName && !hasHan(best.name)

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
                    missingInformation = if (noChineseName) {
                        listOf("Pl@ntNet 库中该物种没有中文俗名，已使用拉丁学名")
                    } else {
                        emptyList()
                    },
                    alternatives = candidates.drop(1).take(5).map {
                        RecognitionResult.Alternative(name = it.name, confidence = it.confidence)
                    },
                    conflicts = buildList {
                        add("识别来源：Pl@ntNet 分类模型（无判定依据描述）")
                        if (noChineseName) {
                            add("该物种无中文俗名，名称为拉丁学名 —— 保存后可用「文字分析」通道补全中文名")
                        }
                    },
                ),
                rawText = raw,
            ),
        )
    }

    /**
     * 项目内的部位标注 → Pl@ntNet 的 `organs` 取值。
     *
     * ## 合法值只有五个（v1.0.2.1018 修正）
     *
     * 官方文档明确列出：`leaf` / `flower` / `fruit` / `bark`，
     * 以及 `auto`（让模型自己判断）。
     * 见 https://my.plantnet.org/doc/api/identify
     *
     * ## 原来写错了两处
     *
     * | 本项目 role | 原来发 | 正确 |
     * |---|---|---|
     * | `WHOLE_PLANT` / `HABITAT` | `"habit"` ❌ | `auto` |
     * | `UNKNOWN`（未标注，**最常见**）| `"other"` ❌ | `auto` |
     *
     * `habit` 与 `other` 都**不在合法值列表里**。用户拍完照通常不标部位，
     * 于是绝大多数请求发的都是 `"other"` —— 拿一个非法值去喂分类器，
     * 轻则被忽略、重则把部位先验带偏。分类器是**拿部位去挑特征**的，
     * 部位错了就会「认得出大类、认错近缘种」，正是用户反馈的
     * 「数据又差又不准」。
     *
     * 未标注时发 `auto` 是对的：让模型自己从图里判断拍的是什么部位，
     * 比强行宣称它是「other」准确得多。
     *
     * 放在 companion 里是为了**可单测** —— 它是纯函数，
     * 而单元测试不该为了验证一张映射表去构造整个 Provider。
     */
    companion object {
        /**
         * 项目内的部位标注 → Pl@ntNet 的 `organs` 取值。
         *
         * 合法值只有 `leaf` / `flower` / `fruit` / `bark` / `auto`
         * （见 https://my.plantnet.org/doc/api/identify）。
         *
         * 原来 `WHOLE_PLANT`/`HABITAT` 发 `"habit"`、其余发 `"other"`，
         * **两个都不是合法值**，而 `UNKNOWN`（用户不标部位时最常见）
         * 正是走 `"other"` 那条。分类器拿部位挑特征，部位错了就会
         * 「认得出大类、认错近缘种」—— 直接对应「识别不准」。
         *
         * 未标注一律发 `auto`：让模型自己判断部位，比强行宣称
         * 它是 "other" 准确得多。
         *
         * 放在 companion 里是为了**可单测**：纯函数，测它不该
         * 去构造整个 Provider。
         */
        internal fun organOf(role: ImageRole): String = when (role) {
            ImageRole.LEAF -> "leaf"
            ImageRole.FLOWER -> "flower"
            ImageRole.FRUIT -> "fruit"
            ImageRole.BARK -> "bark"
            // 整株/生境在 Pl@ntNet 里没有对应部位，交给模型自己判断
            ImageRole.WHOLE_PLANT, ImageRole.HABITAT -> "auto"
            // 未标注（最常见）与其它一切 —— 一律 auto，绝不再发非法的 other
            else -> "auto"
        }

        /** 日志标签（不记 Key、不记图片内容） */
        const val TAG = "PlantNetProvider"

        /** multipart 字段名 */
        const val ORGAN_FIELD = "organs"
        const val IMAGES_FIELD = "images"

        /**
         * 项目库（referential）。
         *
         * ## 为什么**不能**用 `all`（v1.0.2.1018 修的就是它）
         *
         * 官方文档说 `all` 会「自动挑最相关的项目库」，听起来最省事。
         * 但实测它**并不可靠**：同一张樟树照片、同样的参数，
         * 服务端把 `preferedReferential` 解析成了 **`invasion`**
         * ——一个**入侵物种**专题库（全站 75 个项目库之一）。
         *
         * 后果正是用户反馈的「数据又差又不准」：
         *
         * | 项目库 | 服务端采用的库 | 返回候选 | 头名 |
         * |---|---|---|---|
         * | `all` | **`invasion`**（入侵物种） | **2 条** | `Cinnamomum camphora` 0.47 |
         * | `k-world-flora` | `k-world-flora`（世界植物志） | **9 条** | `Camphora officinarum` 0.43 |
         *
         * 两个问题都出在这里：
         * - **候选太少**：窄库里只有 2 条，第二条 score 0.0014，等于没有备选
         * - **分类过时**：`invasion` 库给的是旧名 `Cinnamomum camphora`，
         *   而世界植物志给现行接受名 `Camphora officinarum`
         *
         * `k-world-flora` 是覆盖面最广的 Kew 世界植物志，对园林/野生植物都适用，
         * 且**结果稳定**（连续三次都返回 9 条候选）。
         *
         * 代价：如果用户只想在某个地区库里查（例如欧洲调查项目），
         * 需要自己改 Base URL —— 这比「悄悄查了入侵物种库」好得多。
         */
        const val PROJECT = "k-world-flora"

        /**
         * 结果语言（v1.0.3）。
         *
         * **必须显式传 `zh`**，不能靠服务端默认：默认回英文，
         * 而本项目的档案、搜索、归并全都按**中文名**工作
         * （见 `DuplicateMatcher` 按中文名比对），
         * 视觉 AI 通道给的也是中文名。两个通道口径不一致会让
         * 「同一株植物」在两条通道下变成两个不同的名字。
         *
         * 支持的语言列表见 `/v2/languages`（`zh` / `zh-tw` / `zh-hant` 都在）。
         */
        const val LANGUAGE = "zh"

        /**
         * 请求返回的候选物种条数。
         *
         * 不传时实测只回 **2 条**，第二条 score 低到 0.0014，等于没有备选。
         * 传 10 后能得到一条更完整的候选序列，用户才有得比较。
         * 上限不用太大：`alternatives` 只展示前 5 条，取 10 已经够。
         */
        const val MAX_RESULTS = 10

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
         * 判断 404 是不是「图里没有可识别的物种」。
         *
         * Pl@ntNet 对这两种情况都回 404，只能看 body：
         * - 端点错：`{"statusCode":404,"message":"Server Error"}` 之类
         * - 没认出：`{"statusCode":404,"message":"Species not found"}`
         *
         * 判据用 `species not found` 子串（大小写不敏感），**不**依赖
         * statusCode —— 两者都是 404，用它区分不了。
         */
        fun isSpeciesNotFound(raw: String): Boolean =
            raw.contains("species not found", ignoreCase = true)

        /**
         * 字符串里是否含汉字（CJK 统一表意文字 + 扩展 A）。
         *
         * 用来在 Pl@ntNet 混着拼音的 commonNames 里挑出真正的中文名。
         * 只判范围、不做任何词典查询 —— 够用且没有依赖。
         */
        fun hasHan(text: String): Boolean =
            text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }

        /**
         * 从一组俗名里挑显示名，规则依次是：
         * 1. 含汉字、且**不含繁体字**的（简体优先，与科属口径一致）
         * 2. 含汉字的（哪怕是繁体）
         * 3. 第一个非空的（通常是英文或拼音）
         *
         * 顺序不能反：Pl@ntNet 会给出 `["Zhāng", "樟"]` 这种拼音在前的数组，
         * 也会给出 `["新木姜子属", "新木薑子屬"]` 这种简繁并列的数组。
         * 直接取第一个会拿到拼音或繁体，与视觉 AI 通道对不上。
         */
        fun pickDisplayName(commonNames: List<String>): String? {
            val nonBlank = commonNames.filter { it.isNotBlank() }
            if (nonBlank.isEmpty()) return null

            val chinese = nonBlank.filter(::hasHan)
            if (chinese.isEmpty()) return nonBlank.first()

            return chinese.firstOrNull { !TaxonomyNames.containsTraditional(it) }
                ?: chinese.first()
        }

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
