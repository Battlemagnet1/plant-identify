package com.plantidentify.domain.model

import com.plantidentify.data.local.entity.FolderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderFiltersTest {

    @Test
    fun `默认条件视为未筛选`() {
        val filter = FolderFilter()
        assertTrue(filter.isEmpty)
        assertEquals(0, filter.activeCount)
        assertNull(filter.type)
    }

    @Test
    fun `类型筛选算一项 关键词不算`() {
        // 与 PlantFilters 同一口径：关键词搜索是常态，
        // 算进「已筛选 N 项」会让每次搜索都顶着提示
        val filter = FolderFilter(type = FolderType.LANDSCAPE, keyword = "校园")
        assertFalse(filter.isEmpty)
        assertEquals(1, filter.activeCount)
        assertFalse(filter.onlyKeyword)
    }

    @Test
    fun `只填关键词时不算筛选项`() {
        val filter = FolderFilter(keyword = "广东")
        assertFalse(filter.isEmpty)
        assertEquals(0, filter.activeCount)
        assertTrue(filter.onlyKeyword)
    }

    @Test
    fun `纯空白的关键词不算生效`() {
        // 输入框里敲了个空格就变成「已筛选 1 项」，用户会莫名其妙
        assertTrue(FolderFilter(keyword = "   ").isEmpty)
        assertFalse(FolderFilter(keyword = "   ").onlyKeyword)
    }

    @Test
    fun `排序默认值`() {
        assertEquals(FolderSort.UPDATED, FolderSort.DEFAULT)
        assertEquals(FolderPlantSort.JOINED, FolderPlantSort.DEFAULT)
    }

    @Test
    fun `排序枚举名就是 SQL 的排序键 不能随意改名`() {
        // FolderDao.observeFolderCards / FolderPlantDao.observeFolderPlantCards 里
        // 写的是 CASE WHEN :sort = 'UPDATED' 这样的字符串比较。
        // 重命名枚举常量不会编译报错，只会让排序静默失效（顺序不对但没有异常）——
        // 这个用例把「名字即协议」钉住。
        assertEquals("UPDATED", FolderSort.UPDATED.name)
        assertEquals("CREATED", FolderSort.CREATED.name)
        assertEquals("NAME", FolderSort.NAME.name)

        assertEquals("JOINED", FolderPlantSort.JOINED.name)
        assertEquals("UPDATED", FolderPlantSort.UPDATED.name)
        assertEquals("NAME", FolderPlantSort.NAME.name)
    }

    @Test
    fun `文件夹类型枚举名也是存库值`() {
        // Room 的内置枚举转换器存的是 name()。改名会让老库 valueOf 抛异常 ——
        // 所以类型只能往后加，这个用例把已有的三个钉住。
        assertEquals("LANDSCAPE", FolderType.LANDSCAPE.name)
        assertEquals("COLLABORATION", FolderType.COLLABORATION.name)
        assertEquals("CUSTOM", FolderType.CUSTOM.name)
    }
}
