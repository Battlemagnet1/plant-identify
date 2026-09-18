package com.plantidentify.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 应用主题。
 *
 * 不接入动态取色（dynamicColor），原因见 [PlantLightColorScheme] 的注释。
 */
@Composable
fun PlantIdentifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PlantDarkColorScheme else PlantLightColorScheme,
        typography = PlantIdentifyTypography,
        content = content,
    )
}
