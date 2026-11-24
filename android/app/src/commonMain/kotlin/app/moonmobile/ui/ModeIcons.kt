package app.moonmobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The chart's two ways of placing orders, as the mode button and its menu show them: a hand
 * tapping for the touch mode, a mouse for the cursor mode. Outlines in the button's colour.
 */

/**
 * A hand tapping, with the tap's ring above the finger: Material Symbols «touch_app», outlined,
 * weight 300 (Google, Apache License 2.0), drawn on its 960-unit grid.
 */
private val touchApp: ImageVector by lazy {
    ImageVector.Builder(
        name = "touch_app",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).apply {
        // The symbol's grid runs from -960 to 0 upward.
        group(translationY = 960f) {
            addPath(
                pathData = addPathNodes(
                    "M432.85-100q-30.31 0-57.31-12.77-27-12.77-45.15-36.69L126.62-413.39l14.77-15.76" +
                        "q15-16 35.88-19.23 20.88-3.24 39.11 7.92l97.47 52.31v-316.46q0-12.75 8.63-21.38 " +
                        "8.62-8.62 21.38-8.62t21.68 8.62q8.92 8.63 8.92 21.38v422.38l-120.08-65.39L379.15-185" +
                        "q9.85 12.38 24 18.69 14.16 6.31 29.7 6.31h196.76q37.62 0 64.01-26.19Q720-212.39 " +
                        "720-250v-150.77q0-20.6-13.93-34.53-13.94-13.93-34.53-13.93H469.85v-60h201.69q45.19 " +
                        "0 76.82 31.63Q780-445.96 780-400.77v150.74q0 62.18-44.18 106.11Q691.65-100 " +
                        "629.61-100H432.85ZM188.16-614.61q-11.85-20.08-18.08-42.94-6.23-22.86-6.23-47.06 " +
                        "0-74.93 52.54-127.46 52.53-52.54 127.46-52.54 74.92 0 127.46 52.54 52.53 52.53 " +
                        "52.53 127.41 0 24.35-6.23 47.16-6.23 22.81-18.07 42.89l-51.69-30q8-14 12-28.5t4-31.5" +
                        "q0-50-35-85t-85-35q-50 0-85 35t-35 85q0 17 4 31.5t12 28.5l-51.69 30Z",
                ),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()
}

/** The touch mode's mark. */
@Composable
internal fun TouchIcon(size: Dp, tint: Color) {
    Icon(touchApp, contentDescription = "Сенсор", tint = tint, modifier = Modifier.size(size))
}

/** The cursor mode's mark: a mouse in outline, its wheel under a line from the top edge. */
@Composable
internal fun MouseIcon(size: Dp, tint: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val u = this.size.width / 24f
        val line = 1.6f * u
        drawRoundRect(
            tint,
            topLeft = Offset(5.5f * u, 2f * u),
            size = Size(13f * u, 20f * u),
            cornerRadius = CornerRadius(6.5f * u, 6.5f * u),
            style = Stroke(width = line),
        )
        drawLine(tint, Offset(12f * u, 2f * u), Offset(12f * u, 6f * u), strokeWidth = line)
        drawRoundRect(
            tint,
            topLeft = Offset(10.9f * u, 6f * u),
            size = Size(2.2f * u, 4.4f * u),
            cornerRadius = CornerRadius(1.1f * u, 1.1f * u),
            style = Stroke(width = line * 0.9f),
        )
    }
}
