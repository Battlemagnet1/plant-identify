package com.plantidentify.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 耗时埋点本身也要被测。
 *
 * 它的输出会被当作「哪一段慢」的依据 —— 如果分段算错了，
 * 得出的优化方向就是错的，而且错得很隐蔽（数字看着都挺合理）。
 */
class TimingTraceTest {

    /** 可控时钟。直接把 `current` 当读数用，测试里手动推进 */
    private class FakeClock(var current: Long = 0L) {
        fun read(): Long = current
    }

    @Test
    fun `两段耗时各自记录`() {
        val clock = FakeClock()
        val trace = TimingTrace(clock::read)

        clock.current = 300
        trace.mark("压缩图片")
        clock.current = 1_700
        trace.mark("识别请求")

        val segments = trace.segments()
        assertEquals(2, segments.size)
        assertEquals("压缩图片", segments[0].label)
        assertEquals(300L, segments[0].millis)
        assertEquals("识别请求", segments[1].label)
        assertEquals(1_400L, segments[1].millis)
    }

    @Test
    fun `总计是各段之和`() {
        val clock = FakeClock()
        val trace = TimingTrace(clock::read)

        clock.current = 300
        trace.mark("压缩图片")
        clock.current = 1_700
        trace.mark("识别请求")
        clock.current = 1_900
        trace.mark("落库")

        assertEquals(1_900L, trace.totalMs())
    }

    @Test
    fun `时钟回拨不会算出负数`() {
        // 用户改系统时间、或 NTP 往回校准都会出现。
        // 负耗时会显示成「-5.0s」，比不显示更让人困惑
        val clock = FakeClock(current = 10_000L)
        val trace = TimingTrace(clock::read)

        clock.current = 5_000L
        trace.mark("识别请求")

        assertEquals(0L, trace.segments().single().millis)
        assertEquals(0L, trace.totalMs())
    }

    @Test
    fun `一次都没 mark 时是空的`() {
        val trace = TimingTrace { 0L }

        assertTrue(trace.isEmpty)
        assertEquals(0L, trace.totalMs())
        assertEquals("", trace.render())
    }

    @Test
    fun `零耗时的段仍然会被记下来`() {
        // 不跳过是刻意的：跳过会让「段数」与调用 `mark` 的次数对不上，
        // 读日志的人会以为中间漏记了一段，而去怀疑埋点本身有问题
        val trace = TimingTrace { 1_000L }
        trace.mark("压缩图片")
        trace.mark("识别请求")

        assertEquals(2, trace.segments().size)
        assertTrue(trace.segments().all { it.millis == 0L })
    }

    @Test
    fun `render 按量级切换单位`() {
        val clock = FakeClock()
        val trace = TimingTrace(clock::read)

        clock.current = 320
        trace.mark("压缩图片")
        clock.current = 14_200
        trace.mark("识别请求")

        // 小于一秒给毫秒，否则给一位小数的秒
        assertEquals("压缩图片 320ms · 识别请求 13.9s", trace.render())
    }

    @Test
    fun `render 用点号作小数点而不跟着系统区域走`() {
        // 显式 Locale.US 的用意：德语区域下 %.1f 会输出「1,4」，
        // 而这是一个诊断数字，不该随区域变形
        val clock = FakeClock()
        val trace = TimingTrace(clock::read)
        clock.current = 1_400
        trace.mark("识别请求")

        assertTrue(trace.render().contains("1.4s"))
        assertFalse(trace.render().contains("1,4"))
    }
}
