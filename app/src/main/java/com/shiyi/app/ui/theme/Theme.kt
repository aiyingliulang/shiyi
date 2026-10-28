package com.shiyi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFFB4442E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF3D0700),
    secondary = Color(0xFF6E5B2E),
    secondaryContainer = Color(0xFFF7E1A6),
    onSecondaryContainer = Color(0xFF241A00),
    tertiary = Color(0xFF3F6A4C),
    tertiaryContainer = Color(0xFFC1F0CB),
    onTertiaryContainer = Color(0xFF00210E),
    background = Color(0xFFFBF7F2),
    onBackground = Color(0xFF221A17),
    surface = Color(0xFFFBF7F2),
    onSurface = Color(0xFF221A17),
    surfaceVariant = Color(0xFFF2E6DF),
    onSurfaceVariant = Color(0xFF53433E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F0EA),
    surfaceContainer = Color(0xFFF2EAE3),
    surfaceContainerHigh = Color(0xFFECE3DC),
    outline = Color(0xFF85736D),
    outlineVariant = Color(0xFFD8C2BB),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB4A3),
    onPrimary = Color(0xFF611205),
    primaryContainer = Color(0xFF8E2D19),
    onPrimaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFFDAC58C),
    secondaryContainer = Color(0xFF554419),
    onSecondaryContainer = Color(0xFFF7E1A6),
    tertiary = Color(0xFFA6D4B0),
    tertiaryContainer = Color(0xFF275136),
    onTertiaryContainer = Color(0xFFC1F0CB),
    background = Color(0xFF1A1714),
    onBackground = Color(0xFFEDE0DB),
    surface = Color(0xFF1A1714),
    onSurface = Color(0xFFEDE0DB),
    surfaceVariant = Color(0xFF53433E),
    onSurfaceVariant = Color(0xFFD8C2BB),
    surfaceContainerLowest = Color(0xFF14110F),
    surfaceContainerLow = Color(0xFF221E1B),
    surfaceContainer = Color(0xFF27221F),
    surfaceContainerHigh = Color(0xFF312C29),
    outline = Color(0xFFA08C86),
    outlineVariant = Color(0xFF53433E),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 业务语义色：不再推荐 / 值得回购 */
@Immutable
data class SemanticColors(
    val avoidBg: Color, val avoidFg: Color,
    val goodBg: Color, val goodFg: Color,
)

val LocalSemantic = staticCompositionLocalOf {
    SemanticColors(Color(0xFFFFDAD6), Color(0xFF93000A), Color(0xFFC1F0CB), Color(0xFF00522A))
}

private val type = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

val Amount = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun ShiYiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val semantic = if (dark) SemanticColors(Color(0xFF93000A), Color(0xFFFFDAD6), Color(0xFF1F5132), Color(0xFFC1F0CB))
    else SemanticColors(Color(0xFFFFDAD6), Color(0xFF93000A), Color(0xFFCDEFD5), Color(0xFF0B5129))
    androidx.compose.runtime.CompositionLocalProvider(LocalSemantic provides semantic) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = type, content = content)
    }
}
