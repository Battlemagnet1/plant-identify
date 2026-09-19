package com.plantidentify.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图片缩略图缓存的核心。做成对元素类型无关、只要求一个「占多少字节」
 * 的函数，就是为了能在这里用字符串把它测清楚 —— 用 Bitmap 测的话
 * 只能在设备上跑。
 */
class SizedLruCacheTest {

    /** 每个字符算 1 字节，key 就是内容本身，便于直读测试意图 */
    private fun cache(maxBytes: Int) = SizedLruCache<String>(maxBytes) { it.length }

    @Test
    fun `存了能取回来`() {
        val c = cache(100)
        c.put("a", "hello")
        assertEquals("hello", c.get("a"))
    }

    @Test
    fun `没存过的返回 null`() {
        assertNull(cache(100).get("missing"))
    }

    @Test
    fun `超出上限时按最久未用淘汰`() {
        val c = cache(10)
        c.put("a", "12345") // 5
        c.put("b", "12345") // 5  → 已满
        c.put("c", "12345") // 再进一张，必须挤掉一张

        assertEquals(2, c.size)
        assertEquals(10, c.currentBytes)
        // a 最早进来且此后再没被访问过，先被淘汰
        assertNull(c.get("a"))
        assertNotNull(c.get("b"))
        assertNotNull(c.get("c"))
    }

    @Test
    fun `读过的会被保护`() {
        val c = cache(10)
        c.put("a", "12345")
        c.put("b", "12345")
        c.get("a")          // a 变成最近使用
        c.put("c", "12345") // 应当淘汰 b

        assertNotNull(c.get("a"))
        assertNull(c.get("b"))
    }

    @Test
    fun `单张就超预算的不缓存`() {
        // 不拦的话它一进来就把别人全挤掉，缓存退化成「只有一张能命中」
        val c = cache(4)
        c.put("big", "1234567890")
        assertEquals(0, c.size)
        assertEquals(0, c.currentBytes)
    }

    @Test
    fun `同键覆盖时字节数不重复累计`() {
        val c = cache(100)
        c.put("a", "12345")   // 5
        c.put("a", "1234567") // 覆盖成 7
        assertEquals(1, c.size)
        assertEquals(7, c.currentBytes)
    }

    @Test
    fun `clear 之后一切归零`() {
        val c = cache(100)
        c.put("a", "12345")
        c.clear()
        assertEquals(0, c.size)
        assertEquals(0, c.currentBytes)
        assertNull(c.get("a"))
    }

    @Test
    fun `容量必须是正数`() {
        // 0 或负数会让 trimToLimit 每次都把刚放进去的丢掉 —— 白写的缓存
        try {
            SizedLruCache<String>(0) { it.length }
            throw AssertionError("上限为 0 时应当直接拒绝")
        } catch (expected: IllegalArgumentException) {
            // 正是预期
        }
    }
}
