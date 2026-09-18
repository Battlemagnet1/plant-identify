package com.plantidentify.data.local.entity

/**
 * 植物文字分析（TextProvider）的生成状态。
 *
 * 为什么需要这个字段：
 * 规格书第三十节要求「文字分析失败不能导致植物识别结果丢失」。
 * 没有状态字段就无法区分「还没分析」和「分析失败了」，
 * 也就无法实现结果页上的「重新生成分析」按钮。
 *
 * 注意：视觉识别结果与文字分析结果分开持久化 ——
 * 只要视觉识别成功，档案就必须可保存可查看，哪怕文字分析失败。
 */
enum class AnalysisStatus {
    /** 用户尚未请求生成文字分析 */
    NOT_REQUESTED,

    /** 正在生成（用于跨页面返回时恢复 UI 状态） */
    PENDING,

    /** 生成成功 */
    SUCCEEDED,

    /** 生成失败 —— 基础识别结果仍应保留并展示 */
    FAILED,
    ;

    /** 是否可以触发（重新）生成 */
    val canGenerate: Boolean
        get() = this != PENDING
}
