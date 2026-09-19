package com.plantidentify.ui.components

/**
 * 按**总字节数**限制容量的 LRU 缓存。
 *
 * ## 为什么不用 `android.util.LruCache`
 *
 * 一是它能被 limit 的只有「条目数」，而位图之间大小相差上百倍 ——
 * 按条数限制要么浪费内存，要么在遇到几张大图时直接失控。
 * 二是它依赖 android.jar，放进 `src/test` 就只能在设备上跑。
 * 这里做成对元素类型无关、只要求调用方给出「这个元素占多少字节」，
 * 于是纯逻辑本身可以写单元测试。
 *
 * ## 淘汰时**不**回收资源
 *
 * 位图被淘汰时**不能** `recycle()`。淘汰只说明「缓存不再持有它」，
 * 而它很可能正被某个已经取到引用的 Composable 画在屏幕上 ——
 * 回收的瞬间就是 `Canvas: trying to use a recycled bitmap` 崩溃。
 * 淘汰是「松手」，不是「销毁」；真正的回收交给 GC。
 *
 * @param maxBytes 总字节上限。超过就按最近最少使用顺序往外丢。
 * @param sizeOf 元素占用的字节数
 */
internal class SizedLruCache<T : Any>(
    private val maxBytes: Int,
    private val sizeOf: (T) -> Int,
) {
    init {
        require(maxBytes > 0) { "缓存上限必须为正数" }
    }

    // accessOrder = true：每次读取都把条目移到队尾，
    // 于是迭代器从头开始的顺序天然就是「最近最少使用在前」
    private val entries = LinkedHashMap<String, T>(0, 0.75f, true)
    private var usedBytes = 0

    /** 当前占用的字节数，供测试与调试 */
    @get:Synchronized
    val currentBytes: Int
        get() = usedBytes

    @get:Synchronized
    val size: Int
        get() = entries.size

    @Synchronized
    fun get(key: String): T? = entries[key]

    @Synchronized
    fun put(key: String, value: T) {
        val weight = sizeOf(value)

        // 单张就超过总预算的，不缓存。否则它一进来就会把别人全挤掉，
        // 缓存整体退化成「只有一张能命中」，等于白占内存
        if (weight > maxBytes) return

        entries.put(key, value)?.let { usedBytes -= sizeOf(it) }
        usedBytes += weight
        trimToLimit()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        usedBytes = 0
    }

    private fun trimToLimit() {
        val iterator = entries.entries.iterator()
        while (usedBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            usedBytes -= sizeOf(entry.value)
            iterator.remove()
        }
    }
}
