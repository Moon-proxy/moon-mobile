package app.moonmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How wide a landscape window must be for the lists to go two to a row. */
private val TWO_COLUMNS_FROM = 640.dp

/**
 * The main screen's lists — cores, orders, detects, strategies: one column, or two on a tablet
 * (either way up) and in a wide landscape window (a phone on its side). Headers and summaries go
 * across both with [wide]; [content] learns the columns, for a list that lays itself out
 * differently in two.
 */
@Composable
internal fun ListGrid(
    contentPadding: PaddingValues,
    spacing: Dp,
    content: LazyGridScope.(columns: Int) -> Unit,
) {
    val tablet = isTablet()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= TWO_COLUMNS_FROM
        val columns = if (tablet || wide) 2 else 1
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) { content(columns) }
    }
}

/** A row across every column of a [ListGrid]: a header, a summary, a note. */
internal fun LazyGridScope.wide(key: Any? = null, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)
