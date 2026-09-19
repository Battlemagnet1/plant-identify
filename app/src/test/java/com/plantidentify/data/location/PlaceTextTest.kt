package com.plantidentify.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 地点显示的口径曾经散在三处各写一遍，导致详情页说「未记录」、
 * 导出的报告里却有坐标。合并成一个函数之后，这里就是唯一的口径定义。
 */
class PlaceTextTest {

    @Test
    fun `有地名时优先显示地名`() {
        assertEquals("上海市徐汇区", placeText("上海市徐汇区", 31.18, 121.43))
    }

    @Test
    fun `地名是空白串时退回经纬度`() {
        // 反向地理编码失败时常返回空串而不是 null
        assertEquals("31.1800, 121.4300", placeText("   ", 31.18, 121.43))
    }

    @Test
    fun `没有地名时显示经纬度`() {
        assertEquals("39.9099, 116.3924", placeText(null, 39.9099, 116.3924))
    }

    @Test
    fun `经纬度保留四位小数`() {
        // 四位约合 11 米，够定位到一株植物；多写只显得像在炫耀精度
        assertEquals("31.1235, 121.4568", placeText(null, 31.123456, 121.456789))
    }

    @Test
    fun `只有一半坐标时不显示`() {
        assertNull(placeText(null, 31.18, null))
        assertNull(placeText(null, null, 121.43))
    }

    @Test
    fun `什么都没有时返回 null`() {
        // 返回 null 而不是「未知」：让调用方自己决定是整行不显示
        // 还是写「未记录」—— 两种场景都有
        assertNull(placeText(null, null, null))
    }
}
