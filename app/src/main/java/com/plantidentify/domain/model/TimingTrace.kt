package com.plantidentify.domain.model

import java.util.Locale

/**
 * 一次识别的**分段耗时**记录。
 *
 * ## 为什么要有它
 *
 * 用户反馈「说十几秒，实际要两三分钟」—— 但在这之前，全项目**没有任何耗时埋点**，
 * 没人知道那两三分钟花在哪一段：压缩？上传？模型推理？落库？还是随后那次
 * 文字分析？没有数据就只能猜，而猜出来的优化方向往往是错的
 * （例如去降图片分辨率，结果真正的瓶颈是串行的第二个请求）。
 *
 * 所以这个类只干一件事：把「上一个计时点到此刻」这一段记下来并起个名字。
 *
 * ## 用法
 *
 * ```kotlin
 * val trace = TimingTrace()
 * compress()            // 压缩图片
 * trace.mark("压缩图片")  // ← 记下「从创建到此刻」这一段
 * callModel()           // 请求识别
 * trace.mark("识别请求")  // ← 记下「上一段结束到此刻」
 * ```
 *
 * 也就是说：**标签描述的是刚刚结束的那一段**，不是即将开始的那一段。
 * 第一次 `mark` 从对象创建算起，所以要把它建在真正开始计时的位置。
 *
 * ## 为什么时钟可注入
 *
 * 直接调 `System.currentTimeMillis()` 的话这个类就没法单测 ——
 * 而「分段对不对、总和对不对、时钟回拨会不会算出负数」正是它最该被测的部分。
 * 注入一个 `() -> Long` 之后，测试可以精确控制每一段的长度。
 *
 * 用 `currentTimeMillis` 而不是 `nanoTime`：这里量的是秒级到分钟级的网络耗时，
 * 毫秒精度绰绰有余，而且墙上时钟的读数对用户更直观（可直接对着日志看时间点）。
 */
class TimingTrace(
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 一段耗时 */
    data class Segment(val label: String, val millis: Long) {
        /**
         * 给人看的一行，例如「识别请求 14.2s」。
         *
         * 显式用 [Locale.US] 而不是默认区域：`%.1f` 在德语等区域会输出
         * `14,2`，而在阿拉伯语区域连数字形状都会变 —— 一个诊断用的数字
         * 不需要跟着区域走，而且这样单测在任何机器上结果都一样。
         */
        val display: String
            get() = if (millis < 1_000L) {
                "$label ${millis}ms"
            } else {
                "$label ${"%.1f".format(Locale.US, millis / 1000.0)}s"
            }
    }

    private var lastAt: Long = now()
    private val segments = mutableListOf<Segment>()

    /**
     * 记下「上一个计时点到此刻」这一段。
     *
     * 时钟回拨（用户改系统时间、NTP 校准）时用 `coerceAtLeast(0)` 兜住 ——
     * 负数耗时会在界面上显示成「-3.2s」，比不显示更让人困惑。
     */
    fun mark(label: String) {
        val at = now()
        segments += Segment(label, (at - lastAt).coerceAtLeast(0L))
        lastAt = at
    }

    fun segments(): List<Segment> = segments.toList()

    /** 各段之和。注意不含「还没 mark 的那一段」—— 计时是显式结束的 */
    fun totalMs(): Long = segments.sumOf { it.millis }

    val isEmpty: Boolean get() = segments.isEmpty()

    /** 拼成一行，给日志用。例如「压缩图片 3.2s · 识别请求 14.2s」 */
    fun render(): String = segments.joinToString(" · ") { it.display }

    companion object {
        /**
         * 耗时日志的统一 tag。
         *
         * 集中一处是为了能一条命令看全：`adb logcat -s AiTiming`。
         * 分散成几个 tag 的话，排查时要拼好几次过滤条件 ——
         * 而排查耗时问题的场合，正是最不该让人再花力气找日志的时候。
         */
        const val LOG_TAG = "AiTiming"
    }
}
