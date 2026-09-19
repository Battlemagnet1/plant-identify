package com.plantidentify.data.export

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportModelsTest {

    @Test
    fun `字节数按量级选择单位`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `小数点永远是点号，不随系统语言变化`() {
        // 德语、法语等区域用逗号做小数点，「1,5 MB」在这个场景里只会让人困惑，
        // 而且同一份数据在不同手机上显示不一致时根本无从排查
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5 MB", formatBytes(1024L * 1024 * 3 / 2))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `零与负数不会崩`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("-1 B", formatBytes(-1))
    }

    @Test
    fun `照片数超过 200 张时要求 zip`() {
        assertEquals(200, ExportThresholds.DOWNGRADE_PHOTOS)
        assertFalse(estimate(photoCount = 200).requiresZip)
        assertTrue(estimate(photoCount = 201).requiresZip)
    }

    @Test
    fun `原图体积超过 150 MB 时要求 zip`() {
        assertFalse(estimate(originalBytes = 150L * 1024 * 1024).requiresZip)
        assertTrue(estimate(originalBytes = 150L * 1024 * 1024 + 1).requiresZip)
    }

    @Test
    fun `缩略图模式超过 50 MB 时给出提示`() {
        assertFalse(estimate(thumbnailBytes = 50L * 1024 * 1024).thumbnailExceedsTarget)
        assertTrue(estimate(thumbnailBytes = 50L * 1024 * 1024 + 1).thumbnailExceedsTarget)
    }

    @Test
    fun `base64 内嵌的体积要算上膨胀`() {
        val e = estimate(originalBytes = 1_000_000, thumbnailBytes = 1_000_000)
        // 内嵌图片会膨胀约 4/3，按原图大小报给用户会让他以为文件只有一半大
        assertTrue(e.estimateFor(ExportMode.THUMBNAIL) > e.estimatedThumbnailBytes)
        assertTrue(e.estimateFor(ExportMode.ORIGINAL) > e.originalBytes)
        // zip 里的图片是二进制存进去的，不膨胀
        assertEquals(e.originalBytes, e.estimateFor(ExportMode.FOLDER_ZIP))
    }

    private fun estimate(
        plantCount: Int = 1,
        photoCount: Int = 1,
        originalBytes: Long = 1024,
        thumbnailBytes: Long = 1024,
    ) = ExportEstimate(
        plantCount = plantCount,
        photoCount = photoCount,
        originalBytes = originalBytes,
        estimatedThumbnailBytes = thumbnailBytes,
    )
}
