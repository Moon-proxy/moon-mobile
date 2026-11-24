package app.moonmobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.moonmobile.core.AppPrefs
import app.moonmobile.core.ThemeMode

/**
 * The app's colours. Read through these getters, so a screen drawn with them follows the theme
 * the moment it changes.
 */
object Palette {
    /** The light theme is on. */
    var light by mutableStateOf(false)

    val background get() = if (light) Color(0xFFF4F5F7) else Color(0xFF14161A)
    val surface get() = if (light) Color(0xFFFFFFFF) else Color(0xFF1B1E24)
    val surfaceHigh get() = if (light) Color(0xFFE8EAEE) else Color(0xFF242830)
    val outline get() = if (light) Color(0xFFC9CDD4) else Color(0xFF343A46)
    val text get() = if (light) Color(0xFF15181D) else Color(0xFFE6E8EC)
    val textDim get() = if (light) Color(0xFF5E6573) else Color(0xFF9AA1AE)
    val accent get() = if (light) Color(0xFFB57F0F) else Color(0xFFE8B341)
    val onAccent get() = if (light) Color(0xFFFFFFFF) else Color(0xFF1A1405)
    val green get() = if (light) Color(0xFF1E8A52) else Color(0xFF4FC38A)
    val red get() = if (light) Color(0xFFC8372F) else Color(0xFFE5645F)
    val amber get() = if (light) Color(0xFFB57F0F) else Color(0xFFE8B341)
    val grey get() = Color(0xFF6B7280)

    // The chart screen, in the terminal's chart colours.
    val chart get() = if (light) Color(0xFFFFFFFF) else Color(0xFF1E1E1E)
    val chartLabel get() = if (light) Color(0xFF1A1A1A) else Color(0xFFD3D3D3)
    val chartPanel get() = if (light) Color(0xFFECEFF2) else Color(0xFF3A3D45)
    val chartPanelHead get() = if (light) Color(0xFFDDE1E6) else Color(0xFF2C2F36)
    val chartCard get() = if (light) Color(0xFFFFFFFF) else Color(0xFF2A2C32)
    val chartEdge get() = if (light) Color(0xFFB8BEC6) else Color(0xFF4A4A4A)
    val chartButton get() = if (light) Color(0xFFF2F3F5) else Color(0xFF16181C)
    val chartStrong get() = if (light) Color(0xFF000000) else Color(0xFFFFFFFF)
    val terminalGreen get() = if (light) Color(0xFF1E8A52) else Color(0xFF2FA85C)
    val terminalRed get() = if (light) Color(0xFFD02020) else Color(0xFFFF4A4A)
}

@Composable
fun MoonTheme(content: @Composable () -> Unit) {
    val mode by AppPrefs.theme.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    Palette.light = !dark
    val scheme = if (dark) {
        darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.onAccent,
            secondary = Palette.green,
            onSecondary = Palette.onAccent,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.textDim,
            surfaceContainer = Palette.surface,
            surfaceContainerHigh = Palette.surfaceHigh,
            surfaceContainerHighest = Palette.surfaceHigh,
            outline = Palette.outline,
            error = Palette.red,
            onError = Palette.onAccent,
        )
    } else {
        lightColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.onAccent,
            secondary = Palette.green,
            onSecondary = Palette.onAccent,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.textDim,
            surfaceContainer = Palette.surface,
            surfaceContainerHigh = Palette.surfaceHigh,
            surfaceContainerHighest = Palette.surfaceHigh,
            outline = Palette.outline,
            error = Palette.red,
            onError = Palette.onAccent,
        )
    }
    // The status and navigation bar icons dark on the light theme, light on the dark one.
    SystemBarsEffect(dark)
    MaterialTheme(colorScheme = scheme, content = content)
}
