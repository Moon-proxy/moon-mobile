package app.moonmobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The look of the app's logo, shared by the lock screen and the start page: black, the emblem
// over the wordmark, a still neon frame round the screen.

/** The neon of the app's logo, clockwise from three o'clock as a sweep gradient goes. */
internal val NEON = listOf(
    Color(0xFF3DFF6E), // right: green
    Color(0xFF2CF5D2), // bottom right: sea green
    Color(0xFF52FF3A), // bottom: green
    Color(0xFFFFE23D), // bottom left: yellow
    Color(0xFFFF4B1E), // left: orange red
    Color(0xFFFF1735), // top left: red
    Color(0xFFFF2D6A), // top: crimson
    Color(0xFF3BE7FF), // top right: cyan
    Color(0xFF3DFF6E),
)

internal val NEON_GREEN = Color(0xFF3DFF6E)

/**
 * The logo's glowing frame drawn round the whole screen. Still, not animated: a soft halo in
 * widening strokes and a bright thin core.
 */
@Composable
internal fun NeonFrame() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val inset = 10.dp.toPx()
        val corner = CornerRadius(30.dp.toPx())
        val brush = Brush.sweepGradient(NEON, center)
        val topLeft = Offset(inset, inset)
        val frame = Size(size.width - 2 * inset, size.height - 2 * inset)
        for ((width, alpha) in listOf(28.dp to 0.05f, 18.dp to 0.09f, 11.dp to 0.16f, 6.dp to 0.32f)) {
            drawRoundRect(brush, topLeft, frame, corner, alpha = alpha, style = Stroke(width.toPx()))
        }
        drawRoundRect(brush, topLeft, frame, corner, style = Stroke(2.4.dp.toPx()))
        drawRoundRect(Color.White, topLeft, frame, corner, alpha = 0.45f, style = Stroke(0.8.dp.toPx()))
    }
}

/** The emblem over the wordmark, as on the app's logo. */
@Composable
internal fun Logo(modifier: Modifier = Modifier, emblem: Dp) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painter = imagePainter("lock_emblem"),
            contentDescription = null,
            modifier = Modifier.width(emblem),
        )
        Image(
            painter = imagePainter("lock_wordmark"),
            contentDescription = "MoonMobile",
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 360.dp),
        )
    }
}

/** Colours of the neon screens, the same whatever theme the app is set to. */
internal object Neon {
    val text = Color.White
    val dim = Color(0xB3FFFFFF)
    val card = Color(0x14FFFFFF)
    val edge = Color(0x33FFFFFF)
    val red = Color(0xFFFF4A5A)
    val green = NEON_GREEN
}

/** Material colours for the neon screens: fields, buttons and switches drawn for black. */
@Composable
internal fun NeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NEON_GREEN,
            onPrimary = Color.Black,
            secondary = NEON_GREEN,
            onSecondary = Color.Black,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color(0xFF0E1013),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1A1D22),
            onSurfaceVariant = Neon.dim,
            surfaceContainer = Color(0xFF15181C),
            surfaceContainerHigh = Color(0xFF1A1D22),
            surfaceContainerHighest = Color(0xFF20242A),
            outline = Color(0x59FFFFFF),
            error = Neon.red,
        ),
        typography = MaterialTheme.typography,
        content = content,
    )
}

/**
 * A screen in the logo's style: black, framed in neon, the logo above the content (beside it
 * in landscape) and the content scrolling under it.
 */
@Composable
internal fun NeonScreen(content: @Composable () -> Unit) {
    NeonTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            NeonFrame()
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPaddingScaled()
                    .padding(horizontal = 26.dp, vertical = 22.dp),
            ) {
                val width = maxWidth
                val height = maxHeight
                if (width > height * 1.15f) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Logo(Modifier.weight(1f), emblem = (height * 0.45f).coerceAtMost(220.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        ) { content() }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Logo(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), emblem = (width * 0.42f).coerceAtMost(190.dp))
                        Column(
                            modifier = Modifier
                                .widthIn(max = 480.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { content() }
                    }
                }
            }
        }
    }
}
