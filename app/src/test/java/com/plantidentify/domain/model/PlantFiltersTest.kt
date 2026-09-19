package com.plantidentify.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantFiltersTest {

    @Test
    fun `默认条件视为未筛选`() {
        assertTrue(PlantFilters().isEmpty)
        assertFalse(PlantFilters().onlyKeyword)
    }

    @Test
    fun `只填关键词不算筛选项`() {
        // 「已筛选 N 项」里的 N 不含关键词 —— 关键词搜索是常态，
        // 把它算进去会让每次搜索都顶着「已筛选 1 项」的提示
        val filters = PlantFilters(keyword = "紫薇")
        assertFalse(filters.isEmpty)
        assertEquals(0, filters.activeCount)
        assertTrue(filters.onlyKeyword)
    }

    @Test
    fun `科与属各算一项`() {
        assertEquals(1, PlantFilters(family = "千屈菜科").activeCount)
        assertEquals(2, PlantFilters(family = "千屈菜科", genus = "紫薇属").activeCount)
    }

    @Test
    fun `日期上下界合并计为一项`() {
        assertEquals(1, PlantFilters(fromDate = 1L).activeCount)
        assertEquals(1, PlantFilters(fromDate = 1L, toDate = 2L).activeCount)
    }

    @Test
    fun `地点算一项`() {
        assertEquals(1, PlantFilters(place = "上海").activeCount)
    }

    @Test
    fun `空白字符不算生效`() {
        // 输入框里敲了个空格就变成「已筛选 1 项」，用户会莫名其妙
        assertTrue(PlantFilters(family = "   ").isEmpty)
        assertTrue(PlantFilters(place = " ").isEmpty)
    }

    @Test
    fun `关键词加筛选项时 onlyKeyword 为假`() {
        val filters = PlantFilters(keyword = "紫薇", family = "千屈菜科")
        assertFalse(filters.onlyKeyword)
        assertEquals(1, filters.activeCount)
    }
}
