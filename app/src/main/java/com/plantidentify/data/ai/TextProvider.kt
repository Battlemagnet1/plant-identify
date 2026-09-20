package com.plantidentify.data.ai

/**
 * 一次文字分析请求。
 *
 * 只把**已经由视觉识别确认过的信息**交给文字模型，而不是把照片再给一遍：
 *  - 视觉模型已经看过图并给出了结论，文字模型的任务是「把结论写成百科」
 *  - 再传一次图片既慢又贵，还可能出现两次识别结论不一致
 */
data class TextAnalysisRequest(
    val name: String,
    val latinName: String? = null,
    val family: String? = null,
    val genus: String? = null,
    val category: String? = null,
    val confidence: Double = 0.0,
    val evidence: List<String> = emptyList(),
    val config: AiEndpointConfig,
    /** 拍摄地点，作为**弱先验**（同 [VisionRequest.place]）。为 null 时 prompt 不提这一节 */
    val place: String? = null,
)

/**
 * 植物百科分析结果（规格书第九节）。
 *
 * 字段与 [com.plantidentify.data.local.entity.PlantRecordEntity] 一一对应，
 * 便于直接落库。
 *
 * ## 关于「允许为空」
 *
 * 每个字段都可空，这是刻意的：模型对某个字段不确定时应当**留空**而不是编造。
 * prompt 里明确要求了这一点，UI 上也会跳过空字段而不是显示「暂无」占位 ——
 * 一个诚实的空白比一段编造的内容有价值得多。
 */
data class PlantAnalysis(
    /**
     * 常用名称 / 俗称，多个用「、」分隔。
     *
     * 与中文名分列的另一个原因：中文名参与归并匹配，改起来有副作用
     * （改了就匹配不到旧档案）；俗称纯展示，用户想怎么改都行。
     */
    val commonNames: String? = null,

    /** 植物简介 */
    val description: String? = null,

    /** 形态特征 */
    val morphologicalFeatures: String? = null,

    /** 生长习性 */
    val growthHabits: String? = null,

    /** 花期 */
    val floweringPeriod: String? = null,

    /** 果期 */
    val fruitingPeriod: String? = null,

    /** 园林用途 */
    val landscapeUses: String? = null,

    /** 养护建议 */
    val careAdvice: String? = null,

    /** 病虫害防治建议 */
    val pestControl: String? = null,
) {
    /** 一个字段都没拿到 —— 视为失败而不是「成功的空结果」 */
    val isEmpty: Boolean
        get() = listOf(
            description, morphologicalFeatures, growthHabits,
            floweringPeriod, fruitingPeriod, landscapeUses, careAdvice, pestControl,
        ).all { it.isNullOrBlank() }

    /**
     * 拿到了哪些字段，供 UI 决定渲染哪几块。
     *
     * **口径：只数「植物百科」区块里会渲染的字段。**
     * [commonNames] 不在此列 —— 它显示在身份区（中文名下面），
     * 不属于百科列表；把它算进来会让 [TolerantJsonParser] 的
     * 「模型只返回了 N/M 个字段」提示对不上用户实际看到的块数。
     */
    val presentFields: List<Pair<String, String>>
        get() = buildList {
            description?.takeIf { it.isNotBlank() }?.let { add("植物简介" to it) }
            morphologicalFeatures?.takeIf { it.isNotBlank() }?.let { add("形态特征" to it) }
            growthHabits?.takeIf { it.isNotBlank() }?.let { add("生长习性" to it) }
            floweringPeriod?.takeIf { it.isNotBlank() }?.let { add("花期" to it) }
            fruitingPeriod?.takeIf { it.isNotBlank() }?.let { add("果期" to it) }
            landscapeUses?.takeIf { it.isNotBlank() }?.let { add("园林用途" to it) }
            careAdvice?.takeIf { it.isNotBlank() }?.let { add("养护建议" to it) }
            pestControl?.takeIf { it.isNotBlank() }?.let { add("病虫害防治" to it) }
        }
}

/** 文字分析调用的结果 */
sealed interface TextAnalysisResult {

    data class Success(
        val analysis: PlantAnalysis,
        /** 模型原始返回，保留以便解析异常时追溯 */
        val rawText: String,
        /** 降级说明（部分字段缺失或结构不规范） */
        val parseNote: String? = null,
    ) : TextAnalysisResult

    data class Failure(val failure: AiFailure) : TextAnalysisResult
}

/**
 * 文字分析通道（规格书第九节）。
 *
 * ## 与视觉通道的关系
 *
 * 这条链路是**可选且可失败**的：规格书第三十节明确要求
 * 「文字分析失败不能导致植物识别结果丢失」。
 * 因此调用方必须把 [TextAnalysisResult.Failure] 当作「暂缺内容」处理，
 * 而不是当作整次流程的失败 —— 基础识别结果照常保存。
 *
 * ## 为什么也只有一个实现
 *
 * 与视觉通道同理：三家预置服务都提供 OpenAI Compatible 端点，
 * 差异落在 [AiPreset] 的文字模型配置里，不需要为每家写一个类。
 */
interface TextProvider {

    /**
     * 生成植物百科分析。
     *
     * 实现约定：**不抛异常**，所有失败收敛到 [TextAnalysisResult.Failure]。
     */
    suspend fun generateAnalysis(request: TextAnalysisRequest): TextAnalysisResult

    /**
     * 测试文字通道的连接。
     *
     * 复用 [ConnectivityResult]：文字侧只可能返回 [ConnectivityResult.Success]
     * 或 [ConnectivityResult.Failure]，图片相关的两个状态不适用。
     *
     * 之所以不让设置页复用视觉侧的测试，是因为视觉侧会发一张探针图 ——
     * 拿它去测一个纯文本模型必然得到「该模型不接受图片」的结论，
     * 而那对文字分析来说根本不是问题。
     */
    suspend fun testConnection(request: ConnectivityRequest): ConnectivityResult
}
