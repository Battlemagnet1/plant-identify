package com.plantidentify.data.local.entity

/**
 * 识别任务的状态机。
 *
 * ```
 * PENDING ──入队──> QUEUED ──Worker 取到──> PROCESSING ──视觉完成──> ANALYZING ──百科完成──> COMPLETED
 *                      │                       │                       │
 *                      └───────────────────────┴───────────────────────┴──> FAILED / CANCELLED
 * ```
 *
 * ## 两个设计决定
 *
 * 1. **`PROCESSING` 与 `ANALYZING` 必须分开。**
 *    视觉识别与文字分析是两次独立的网络请求，而用户抱怨的「慢」通常卡在后者。
 *    合成一个「处理中」会让用户无法判断到底在等哪一步 ——
 *    这和 Phase 1 给结果页加耗时卡是同一个动机：把「它很慢」变成
 *    「它卡在识别请求」这种可行动的信息。
 *
 * 2. **终态判断只走 [isTerminal] / [isBusy]。**
 *    不要在业务代码里手写 `status == COMPLETED || status == FAILED` ——
 *    以后加一个状态，那种写法必漏（而且漏的地方往往正是清理逻辑）。
 */
enum class RecognitionTaskStatus {

    /** 已创建、尚未提交给 WorkManager（例如图片还没准备齐） */
    PENDING,

    /** 已入队，等待约束满足（联网 / 并发名额） */
    QUEUED,

    /** 正在做视觉识别（含图片压缩与上传） */
    PROCESSING,

    /** 视觉已完成并落库，正在跑文字分析（植物百科） */
    ANALYZING,

    /** 全部完成，结果已写入档案 */
    COMPLETED,

    /** 失败（重试次数已耗尽，[RecognitionTaskEntity.errorMessage] 里有原因） */
    FAILED,

    /** 用户取消 */
    CANCELLED,
    ;

    /** 终态：不会再有状态变化，也不会再有 Worker 碰它 */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    /**
     * 在途：正占着 Worker 的并发名额。
     *
     * 与 `RecognitionTaskDao.getInFlight()` 的 SQL 条件是**同一口径**，
     * 改这里就要改那里 —— 应用启动时的「僵尸状态自愈」靠这两个一致才成立。
     */
    val isBusy: Boolean
        get() = this == QUEUED || this == PROCESSING || this == ANALYZING
}
