package com.plantidentify.data.local.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRoleTest {

    @Test
    fun `未标注不参与识别`() {
        assertFalse(ImageRole.UNKNOWN.contributesToIdentification)
    }

    @Test
    fun `生境照片不参与物种识别`() {
        // 生境用于调查记录，对「这是哪一种」帮助有限 ——
        // 把它算成有效照片会让「有效照片数」虚高
        assertFalse(ImageRole.HABITAT.contributesToIdentification)
    }

    @Test
    fun `部位照片都参与识别`() {
        listOf(
            ImageRole.WHOLE_PLANT,
            ImageRole.LEAF,
            ImageRole.FLOWER,
            ImageRole.FRUIT,
            ImageRole.BARK,
        ).forEach { role ->
            assertTrue("$role 应当参与识别", role.contributesToIdentification)
        }
    }

    @Test
    fun `枚举覆盖了预期的七种取值`() {
        // 这套取值是数据库里 role 列的取值域。数量变了意味着
        // 库里旧数据的含义变了，需要迁移而不是悄悄改枚举
        assertTrue(ImageRole.entries.size == 7)
    }
}
