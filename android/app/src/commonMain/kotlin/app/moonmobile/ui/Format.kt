package app.moonmobile.ui

import kotlin.math.abs
import app.moonmobile.text.fmt
import app.moonmobile.text.DateFormat

/** A price with as many decimals as its size calls for; a dash when unknown. */
fun formatPrice(value: Double): String {
    if (value == 0.0 || value.isNaN() || value.isInfinite()) return "—"
    val magnitude = abs(value)
    val decimals = when {
        magnitude >= 1000.0 -> 2
        magnitude >= 1.0 -> 4
        magnitude >= 0.01 -> 6
        else -> 8
    }
    val text = fmt("%.${decimals}f", value)
    return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}

/** An amount of money or coins: two decimals, thousands kept together. */
fun formatAmount(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    return fmt("%,.2f", value).replace(',', ' ')
}

/** A signed percentage with two decimals. */
fun formatPercent(value: Double): String =
    fmt("%+.2f%%", value)

/** Local wall-clock time of a Unix-millisecond stamp. */
fun formatTime(ms: Double): String {
    if (ms <= 0.0 || ms.isNaN()) return ""
    return DateFormat("HH:mm:ss").format(ms.toLong())
}
