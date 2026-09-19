package com.plantidentify.ui.screens.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页的输入校验。拦在输入框这里而不是写库时兜底 ——
 * 用户填了「95%」这种带符号的内容，应该当场看到保存按钮不可用，
 * 而不是某天发现置信度莫名变成了 0。
 */
class EditablePlantInfoTest {

    @Test
    fun `中文名是唯一必填项`() {
        assertFalse(EditablePlantInfo().canSave)
        assertFalse(EditablePlantInfo(name = "   ").canSave)
        assertTrue(EditablePlantInfo(name = "紫薇").canSave)
    }

    @Test
    fun `置信度留空视为 0 且合法`() {
        assertTrue(EditablePlantInfo(name = "紫薇", confidencePercent = "").confidenceValid)
        assertEquals(0.0, EditablePlantInfo(confidencePercent = "").confidenceFraction, 1e-9)
    }

    @Test
    fun `置信度接受 0 到 100 的整数`() {
        assertTrue(EditablePlantInfo(confidencePercent = "0").confidenceValid)
        assertTrue(EditablePlantInfo(confidencePercent = "100").confidenceValid)
        assertEquals(0.95, EditablePlantInfo(confidencePercent = "95").confidenceFraction, 1e-9)
    }

    @Test
    fun `置信度拒绝越界与带符号的输入`() {
        assertFalse(EditablePlantInfo(confidencePercent = "101").confidenceValid)
        assertFalse(EditablePlantInfo(confidencePercent = "-1").confidenceValid)
        assertFalse(EditablePlantInfo(confidencePercent = "95%").confidenceValid)
        assertFalse(EditablePlantInfo(confidencePercent = "abc").confidenceValid)
    }

    @Test
    fun `置信度非法时不允许保存`() {
        // 半截的输入（比如刚敲了「1」还没敲完）也要能正确判断
        assertFalse(EditablePlantInfo(name = "紫薇", confidencePercent = "1e2").canSave)
    }

    @Test
    fun `置信度换算时把越界值夹回区间`() {
        // 换算函数本身要防御 —— 它可能在 canSave 被检查之前就被调用
        assertEquals(1.0, EditablePlantInfo(confidencePercent = "999").confidenceFraction, 1e-9)
        assertEquals(0.0, EditablePlantInfo(confidencePercent = "-5").confidenceFraction, 1e-9)
    }
}
