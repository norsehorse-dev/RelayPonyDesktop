package com.relaypony.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val BrandViolet = Color(0xFF5A4FE0)
val BrandCyan = Color(0xFF1E97A8)

/** Light scheme: every role pinned so no component falls back to the Material baseline. */
private val LightScheme = lightColorScheme(
    primary = BrandViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3DFFF),
    onPrimaryContainer = Color(0xFF15006A),
    inversePrimary = Color(0xFFC1BBFF),
    secondary = BrandCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3DFFF),
    onSecondaryContainer = Color(0xFF15006A),
    tertiary = BrandCyan,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEFF4),
    onTertiaryContainer = Color(0xFF00363E),
    background = Color(0xFFFBFBFD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFBFBFD),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE2E2E8),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = BrandViolet,
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F2F5),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF76767F),
    outlineVariant = Color(0xFFC7C7CE),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFBFBFD),
    surfaceDim = Color(0xFFDBDBDE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F5F8),
    surfaceContainer = Color(0xFFEFEFF2),
    surfaceContainerHigh = Color(0xFFE9E9EC),
    surfaceContainerHighest = Color(0xFFE3E3E6),
)

/** Dark scheme: neutral greys, a lighter violet so accent text stays readable on dark surfaces. */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC1BBFF),
    onPrimary = Color(0xFF2A1F8F),
    primaryContainer = Color(0xFF4137C4),
    onPrimaryContainer = Color(0xFFE3DFFF),
    inversePrimary = BrandViolet,
    secondary = Color(0xFF7FD4E2),
    onSecondary = Color(0xFF00363E),
    secondaryContainer = Color(0xFF3A3570),
    onSecondaryContainer = Color(0xFFE3DFFF),
    tertiary = Color(0xFF7FD4E2),
    onTertiary = Color(0xFF00363E),
    tertiaryContainer = Color(0xFF004F5A),
    onTertiaryContainer = Color(0xFFCDEFF4),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF45464C),
    onSurfaceVariant = Color(0xFFC5C6CC),
    surfaceTint = Color(0xFFC1BBFF),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F9097),
    outlineVariant = Color(0xFF45464C),
    scrim = Color.Black,
    surfaceBright = Color(0xFF38393C),
    surfaceDim = Color(0xFF121316),
    surfaceContainerLowest = Color(0xFF0D0E11),
    surfaceContainerLow = Color(0xFF1A1B1E),
    surfaceContainer = Color(0xFF1E1F22),
    surfaceContainerHigh = Color(0xFF282A2D),
    surfaceContainerHighest = Color(0xFF333538),
)

/**
 * Resolves [mode] to light or dark. On macOS and Windows "match system" uses Compose's own check,
 * which follows the OS live. Compose can't see the desktop setting on Linux, so there we ask the
 * desktop ourselves ([SystemAppearance]) and re-check every few seconds while "match system" is on.
 */
@Composable
fun resolveDark(mode: ThemeMode): Boolean {
    val platformDark = isSystemInDarkTheme()
    if (mode == ThemeMode.LIGHT) return false
    if (mode == ThemeMode.DARK) return true
    if (!SystemAppearance.isLinux) return platformDark
    var linuxDark by remember { mutableStateOf(SystemAppearance.cachedLinuxDark) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            linuxDark = withContext(Dispatchers.IO) { SystemAppearance.linuxPrefersDark() }
        }
    }
    return linuxDark
}

/** The Pony-family look: violet→cyan, used as a signal on the moments that matter. */
@Composable
fun RelayPonyTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}



