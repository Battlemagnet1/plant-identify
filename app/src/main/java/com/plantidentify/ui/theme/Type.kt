package com.plantidentify.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体排版。
 *
 * 目前只微调了几处，其余沿用 Material 3 默认值 —— 默认字号在中文环境下的
 * 行高偏紧，因此对正文类样式补足 lineHeight，避免中文长段落显得拥挤。
 * 拉丁学名（如 Lagerstroemia indica）用等宽字体的场景留给具体页面处理。
 */
val PlantIdentifyTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontSize = 14.sp,
            lineHeight = 22.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.Medium,
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = FontFamily.Default,
            letterSpacing = 0.5.sp,
        ),
    )
}
