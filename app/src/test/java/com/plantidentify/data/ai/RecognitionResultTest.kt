package com.plantidentify.data.ai

import com.plantidentify.domain.model.ConfidenceGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionResultTest {

    @Test
    fun `低于 0_70 才提示补图`() {
        assertTrue(RecognitionResult(name = "紫薇", confidence = 0.69).needsMorePhotos)
        // 0.70 是「可以接受」的下界，不能提示补图
        assertFalse(RecognitionResult(name = "紫薇", confidence = 0.70).needsMorePhotos)
        assertFalse(RecognitionResult(name = "紫薇", confidence = 0.99).needsMorePhotos)
    }

    @Test
    fun `星级走的是同一套口径`() {
        val result = RecognitionResult(name = "紫薇", confidence = 0.92)
        assertEquals(ConfidenceGrade.stars(0.92), result.qualityStars)
        assertEquals(ConfidenceGrade.label(0.92), result.qualityLabel)
    }

    @Test
    fun `没有候选时 alternatives 为空而不是 null`() {
        assertEquals(emptyList<RecognitionResult.Alternative>(), RecognitionResult(name = "紫薇").alternatives)
    }
}
