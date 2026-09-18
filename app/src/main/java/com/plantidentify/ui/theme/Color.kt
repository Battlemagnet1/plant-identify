package com.plantidentify.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 植物主题配色（规格书第二十六节）。
 *
 * 设计原则：
 *   - 以植物主题绿作为强调色，不用大面积渐变、不做过度装饰
 *   - **刻意不使用 Material You 动态取色** —— 动态取色会让应用配色随用户壁纸变化，
 *     失去「植物识别应用」的品牌识别度
 *   - 深色模式单独给一套配色，避免深色下绿底白字对比度不足
 */

// ---------------- 浅色 ----------------

private val LightPrimary = Color(0xFF2E6B32)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFB1F1B0)
private val LightOnPrimaryContainer = Color(0xFF00210A)
private val LightSecondary = Color(0xFF52634F)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFD5E8CF)
private val LightOnSecondaryContainer = Color(0xFF101F10)
private val LightTertiary = Color(0xFF39656B)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFBCEBF1)
private val LightOnTertiaryContainer = Color(0xFF001F24)
private val LightBackground = Color(0xFFFCFDF6)
private val LightOnBackground = Color(0xFF1A1C19)
private val LightSurface = Color(0xFFFCFDF6)
private val LightOnSurface = Color(0xFF1A1C19)
private val LightSurfaceVariant = Color(0xFFDEE5D8)
private val LightOnSurfaceVariant = Color(0xFF424940)
private val LightOutline = Color(0xFF72796F)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)

// ---------------- 深色 ----------------

private val DarkPrimary = Color(0xFF96D497)
private val DarkOnPrimary = Color(0xFF003910)
private val DarkPrimaryContainer = Color(0xFF11511C)
private val DarkOnPrimaryContainer = Color(0xFFB1F1B0)
private val DarkSecondary = Color(0xFFB9CCB4)
private val DarkOnSecondary = Color(0xFF243424)
private val DarkSecondaryContainer = Color(0xFF3A4B39)
private val DarkOnSecondaryContainer = Color(0xFFD5E8CF)
private val DarkTertiary = Color(0xFFA0CFD5)
private val DarkOnTertiary = Color(0xFF00363C)
private val DarkTertiaryContainer = Color(0xFF1F4D53)
private val DarkOnTertiaryContainer = Color(0xFFBCEBF1)
private val DarkBackground = Color(0xFF1A1C19)
private val DarkOnBackground = Color(0xFFE2E3DD)
private val DarkSurface = Color(0xFF1A1C19)
private val DarkOnSurface = Color(0xFFE2E3DD)
private val DarkSurfaceVariant = Color(0xFF424940)
private val DarkOnSurfaceVariant = Color(0xFFC2C9BD)
private val DarkOutline = Color(0xFF8C9388)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)

val PlantLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

val PlantDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)
