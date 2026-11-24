package app.moonmobile.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density

/*
 * The app's scale. On a large screen [MoonApp] lays the screens out larger than the platform's
 * density, so a tablet shows the phone's layout scaled up. On iOS two things take the density
 * where they are used for platform measures given in points, and would be off by the scale:
 * a dialog or menu converts its touches with it, and the system bars and the keyboard are
 * turned into pixels with it. These helpers give them the platform's own density.
 */

/** The platform's density under the app's scaled one, where it has to be told apart (iOS). */
internal val LocalBaseDensity = staticCompositionLocalOf<Density?> { null }

/** A tablet: the screen's shorter side, in the platform's own points, from 600. */
@Composable
internal fun isTablet(): Boolean = smallestScreenWidthDp() >= TABLET_FROM_DP

private const val TABLET_FROM_DP = 600f

/** The steps the settings offer, against a phone's size. */
internal val uiScaleSteps = listOf(0.85f, 1f, 1.15f, 1.3f, 1.45f, 1.6f, 1.8f, 2f)

/** Padding for the system bars, the cutout and the keyboard, as `safeDrawingPadding()`. */
@Composable
internal fun Modifier.safeDrawingPaddingScaled(): Modifier {
    val base = LocalBaseDensity.current ?: return windowInsetsPadding(WindowInsets.safeDrawing)
    val app = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    val direction = LocalLayoutDirection.current
    val padding = with(app) {
        PaddingValues.Absolute(
            left = insets.getLeft(base, direction).toDp(),
            top = insets.getTop(base).toDp(),
            right = insets.getRight(base, direction).toDp(),
            bottom = insets.getBottom(base).toDp(),
        )
    }
    return padding(padding)
}

/** Material's alert dialog, opened at the platform's density and filled at the app's. */
@Composable
internal fun MoonDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    val base = LocalBaseDensity.current
    if (base == null) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            title = title,
            text = text,
        )
        return
    }
    val app = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides base) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = { AtDensity(app, confirmButton) },
            dismissButton = atDensity(app, dismissButton),
            title = atDensity(app, title),
            text = atDensity(app, text),
        )
    }
}

/** Material's dropdown menu, opened at the platform's density and filled at the app's. */
@Composable
internal fun MoonMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = LocalBaseDensity.current
    if (base == null) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
        return
    }
    val app = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides base) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
            CompositionLocalProvider(LocalDensity provides app) { content() }
        }
    }
}

@Composable
private fun AtDensity(density: Density, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalDensity provides density, content = content)

/** A dialog's optional slot filled at [density]. */
private fun atDensity(density: Density, slot: (@Composable () -> Unit)?): (@Composable () -> Unit)? =
    if (slot == null) null else { { AtDensity(density, slot) } }
