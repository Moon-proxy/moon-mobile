package app.moonmobile.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.moonmobile.text.fmt

/**
 * The settings gear, drawn rather than typed: eight rounded teeth in the accent colour, lit from
 * the top left, with a hub and a ring in the middle.
 */
@Composable
internal fun GearIcon(size: Dp = 24.dp, tint: Color = Palette.accent) {
    Canvas(modifier = Modifier.size(size)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val outer = this.size.minDimension * 0.48f
        val root = outer * 0.76f
        val teeth = 8
        val step = 2.0 * PI / teeth
        val top = step * 0.20
        val base = step * 0.30
        fun at(r: Float, a: Double) = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        val body = Path().apply {
            fillType = PathFillType.EvenOdd
            for (i in 0 until teeth) {
                val a = i * step - PI / 2
                val p0 = at(root, a - base)
                if (i == 0) moveTo(p0.x, p0.y) else lineTo(p0.x, p0.y)
                at(outer, a - top).let { lineTo(it.x, it.y) }
                at(outer, a + top).let { lineTo(it.x, it.y) }
                at(root, a + base).let { lineTo(it.x, it.y) }
                // The rim between two teeth, as a short arc.
                val next = a + step - base
                for (k in 1..3) {
                    val t = a + base + (next - (a + base)) * k / 4.0
                    at(root, t).let { lineTo(it.x, it.y) }
                }
            }
            close()
            // The hole of the hub.
            addOval(androidx.compose.ui.geometry.Rect(c, outer * 0.30f))
        }
        val light = Brush.linearGradient(
            listOf(tint.copy(alpha = 1f), tint.copy(alpha = 0.62f)),
            start = Offset(0f, 0f),
            end = Offset(this.size.width, this.size.height),
        )
        drawPath(body, light)
        // A thin ring round the hub, the gear's machined face.
        drawCircle(tint.copy(alpha = 0.9f), radius = root * 0.62f, center = c, style = Stroke(width = outer * 0.07f))
    }
}

/**
 * The button with the new version's number, shown beside the settings while there is one: drawn
 * like the lock screen — the logo's neon frame round plain black (white in the light theme), its
 * frame dimming and coming back once every five seconds.
 */
@Composable
internal fun UpdateButton(onClick: () -> Unit) {
    val version = pendingUpdateVersion() ?: return
    val glow by rememberInfiniteTransition(label = "update").animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = UPDATE_BLINK_MS
                1f at 0
                1f at UPDATE_BLINK_MS - 1_400 using FastOutSlowInEasing
                0.12f at UPDATE_BLINK_MS - 700 using FastOutSlowInEasing
                1f at UPDATE_BLINK_MS
            },
        ),
        label = "frame",
    )
    val shape = RoundedCornerShape(10.dp)
    val light = Palette.light
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .drawWithContent {
                drawContent()
                val corner = CornerRadius(10.dp.toPx())
                val brush = Brush.sweepGradient(NEON, center)
                // A soft halo in widening strokes, then the bright thin line — the lock screen's
                // frame, small.
                for ((width, alpha) in listOf(7.dp to 0.08f, 4.dp to 0.18f, 2.2.dp to 0.36f)) {
                    drawRoundRect(brush, cornerRadius = corner, alpha = alpha * glow, style = Stroke(width.toPx()))
                }
                drawRoundRect(brush, cornerRadius = corner, alpha = glow, style = Stroke(1.4.dp.toPx()))
                if (!light) {
                    drawRoundRect(Color.White, cornerRadius = corner, alpha = 0.4f * glow, style = Stroke(0.5.dp.toPx()))
                }
            }
            .clip(shape)
            .background(if (light) Color.White else Color.Black)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "v$version",
            color = if (light) Color.Black else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** How often the update button's frame dims and comes back. */
private const val UPDATE_BLINK_MS = 5_000
