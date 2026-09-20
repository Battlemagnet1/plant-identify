package com.plantidentify.data.ai

import com.plantidentify.domain.model.ConfidenceGrade

/**
 * 视觉识别的结果（规格书第五节）。
 *
 * ## 关于 confidence 的语义（重要，规格书明确要求）
 *
 * [confidence] 是「AI 对当前视觉证据的置信程度估计」，
 * **不是经过科学实验验证的物种鉴定概率**。
 * UI 上不得出现「91% 一定正确」「保证鉴定」这类表述。
 *
 * ## 为什么原始文本必须保留
 *
 * 规格书与分析报告都要求：**解析失败不等于识别失败**。
 * 模型很可能已经识别正确，只是 JSON 格式不规范。此时若直接丢弃、
 * 让用户重新拍照，是糟糕的体验。因此 [VisionResponse.rawText]
 * 永远保留模型原话，解析成功与否都能展示。
 */
data class RecognitionResult(
    /**
     * **正式中文名称**（如「悬铃木」「紫薇」）。
     *
     * prompt 里明确要求不给俗称、商品名与园艺品种名 —— 这个字段会直接
     * 落进 `PlantRecordEntity.name`，而那一栏是搜索、去重与归并的入口。
     * 俗称由文字分析通道的 `PlantAnalysis.commonNames` 单独提供。
     */
    val name: String,

    /** 拉丁学名 */
    val latinName: String? = null,

    /** 科 */
    val family: String? = null,

    /** 属 */
    val genus: String? = null,

    /** 植物类型，如「落叶灌木或小乔木」 */
    val category: String? = null,

    /** AI 置信度 0.0 ~ 1.0（语义见类注释） */
    val confidence: Double = 0.0,

    /** 判定依据：模型是根据哪些特征得出结论的 */
    val evidence: List<String> = emptyList(),

    /** 缺失信息：要提升准确度还需要看到什么 */
    val missingInformation: List<String> = emptyList(),

    /** 候选植物（含各自置信度），用于低置信度时给出参考 */
    val alternatives: List<Alternative> = emptyList(),

    /** 图片之间的冲突说明（多图联合识别时可能出现） */
    val conflicts: List<String> = emptyList(),
) {

    data class Alternative(
        val name: String,
        val confidence: Double,
    )

    /** 是否低于补图阈值（规格书第六节：confidence < 0.70 时提示补图） */
    val needsMorePhotos: Boolean get() = confidence < CONFIDENCE_THRESHOLD_POOR

    /**
     * 识别质量星级（规格书第七节，1–5 星）。
     *
     * 分级规则见 [com.plantidentify.domain.model.ConfidenceGrade] ——
     * 抽出去是因为植物详情页展示同一个置信度时也要用，
     * 两处各写一份必然会不一致。
     */
    val qualityStars: Int
        get() = ConfidenceGrade.stars(confidence)

    /** 识别质量的文字描述，与 [qualityStars] 一一对应 */
    val qualityLabel: String
        get() = ConfidenceGrade.label(confidence)

    companion object {
        /** 低于此值提示补充照片（规格书第六节的 0.70） */
        const val CONFIDENCE_THRESHOLD_POOR = 0.70

        /** 置信度的展示上限 —— 避免模型返回 1.0 时被用户理解为「确定无误」 */
        const val CONFIDENCE_DISPLAY_CAP = 0.99
    }
}

/**
 * 一次视觉调用的完整结果。
 *
 * 三种情形都用这一个类型表达，避免调用方在多个分支间跳转：
 *  - 完全解析成功：[result] 有值，[parseNote] 为 null
 *  - 降级解析成功（模型返回的 JSON 不完整但有名称）：[result] 有值，[parseNote] 非 null
 *  - 完全解析失败：[result] 为 null，只展示 [rawText]（验收标准 ⑤）
 */
data class VisionResponse(
    /** 结构化结果；完全解析失败时为 null */
    val result: RecognitionResult?,

    /** 模型返回的原始文本 —— 永远保留，不做丢弃 */
    val rawText: String,

    /** 降级说明，例如「模型返回的 JSON 不完整，已尽力提取」 */
    val parseNote: String? = null,

    /** 本次实际发送的图片张数，用于结果页说明 */
    val imageCount: Int = 0,

    /** 是否为重试后的结果 */
    val afterRetry: Boolean = false,
) {
    val isStructured: Boolean get() = result != null
}
