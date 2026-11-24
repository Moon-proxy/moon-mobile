package app.moonmobile.text

/**
 * `String.format(Locale.US, pattern, args)` in common code, for the subset the app uses:
 * `%s`, `%d`, `%x`, `%f` and `%%`, with the flags `-` `+` `,` `0`, a width and a precision.
 * Decimals round half up on the shortest form of the number, as Java's formatter does, so
 * `fmt("%.2f", 1.005)` is `1.01` on both platforms.
 */
fun fmt(pattern: String, vararg args: Any?): String {
    val out = StringBuilder()
    var next = 0
    var at = 0
    while (at < pattern.length) {
        val ch = pattern[at]
        if (ch != '%') {
            out.append(ch)
            at++
            continue
        }
        at++
        if (at >= pattern.length) throw IllegalArgumentException("Bad format: $pattern")
        var left = false
        var plus = false
        var group = false
        var zero = false
        while (at < pattern.length) {
            when (pattern[at]) {
                '-' -> left = true
                '+' -> plus = true
                ',' -> group = true
                '0' -> zero = true
                else -> break
            }
            at++
        }
        var width = 0
        while (at < pattern.length && pattern[at].isDigit()) width = width * 10 + (pattern[at++] - '0')
        var precision = -1
        if (at < pattern.length && pattern[at] == '.') {
            at++
            precision = 0
            while (at < pattern.length && pattern[at].isDigit()) precision = precision * 10 + (pattern[at++] - '0')
        }
        if (at >= pattern.length) throw IllegalArgumentException("Bad format: $pattern")
        val conversion = pattern[at++]
        if (conversion == '%') {
            out.append('%')
            continue
        }
        if (conversion == 'n') {
            out.append('\n')
            continue
        }
        val arg = args.getOrNull(next++)
        val body: String = when (conversion) {
            's', 'S' -> arg.toString().let { if (precision >= 0 && it.length > precision) it.take(precision) else it }
                .let { if (conversion == 'S') it.uppercase() else it }
            'd' -> {
                val value = (arg as? Number)?.toLong() ?: throw IllegalArgumentException("%d needs a number")
                signed(value < 0, groupDigits(if (value < 0) value.toString().drop(1) else value.toString(), group), plus)
            }
            'x', 'X' -> {
                val text = when (arg) {
                    is Byte -> (arg.toInt() and 0xFF).toString(16)
                    is Short -> (arg.toInt() and 0xFFFF).toString(16)
                    is Int -> arg.toUInt().toString(16)
                    is Long -> arg.toULong().toString(16)
                    else -> throw IllegalArgumentException("%x needs an integer")
                }
                if (conversion == 'X') text.uppercase() else text
            }
            'f' -> {
                val value = (arg as? Number)?.toDouble() ?: throw IllegalArgumentException("%f needs a number")
                when {
                    value.isNaN() -> "NaN"
                    value.isInfinite() -> if (value > 0) (if (plus) "+Infinity" else "Infinity") else "-Infinity"
                    else -> {
                        val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
                        val digits = fixed(kotlin.math.abs(value), if (precision < 0) 6 else precision)
                        val dot = digits.indexOf('.')
                        val whole = if (dot < 0) digits else digits.substring(0, dot)
                        val frac = if (dot < 0) "" else digits.substring(dot)
                        signed(negative, groupDigits(whole, group) + frac, plus)
                    }
                }
            }
            else -> throw IllegalArgumentException("Unsupported conversion %$conversion")
        }
        out.append(pad(body, width, left, zero && conversion != 's'))
    }
    return out.toString()
}

private fun signed(negative: Boolean, digits: String, plus: Boolean): String = when {
    negative -> "-$digits"
    plus -> "+$digits"
    else -> digits
}

private fun groupDigits(whole: String, group: Boolean): String {
    if (!group || whole.length <= 3) return whole
    val out = StringBuilder()
    val first = whole.length % 3
    if (first > 0) out.append(whole, 0, first)
    var at = first
    while (at < whole.length) {
        if (out.isNotEmpty()) out.append(',')
        out.append(whole, at, at + 3)
        at += 3
    }
    return out.toString()
}

private fun pad(body: String, width: Int, left: Boolean, zero: Boolean): String {
    if (body.length >= width) return body
    val fill = width - body.length
    return when {
        left -> body + " ".repeat(fill)
        zero -> {
            val sign = if (body.startsWith('-') || body.startsWith('+')) body.substring(0, 1) else ""
            sign + "0".repeat(fill) + body.substring(sign.length)
        }
        else -> " ".repeat(fill) + body
    }
}

/**
 * A non-negative finite [value] with [decimals] digits after the point, rounded half up on its
 * shortest decimal form.
 */
internal fun fixed(value: Double, decimals: Int): String {
    // The shortest form: "1.005", "1.0E-5", "1.2345E7".
    val text = value.toString()
    val mark = text.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (mark < 0) text else text.substring(0, mark)
    val exponent = if (mark < 0) 0 else text.substring(mark + 1).removePrefix("+").toInt()
    val dot = mantissa.indexOf('.')
    val wholePart = if (dot < 0) mantissa else mantissa.substring(0, dot)
    val fracPart = if (dot < 0) "" else mantissa.substring(dot + 1)
    var digits = (wholePart + fracPart).toMutableList()
    var point = wholePart.length + exponent
    if (point < 0) {
        repeat(-point) { digits.add(0, '0') }
        point = 0
    }
    val keep = point + decimals
    while (digits.size < keep) digits.add('0')
    if (digits.size > keep) {
        val roundUp = digits[keep] >= '5'
        digits = digits.subList(0, keep).toMutableList()
        if (roundUp) {
            var i = digits.size - 1
            while (i >= 0) {
                if (digits[i] == '9') {
                    digits[i] = '0'
                    i--
                } else {
                    digits[i] = digits[i] + 1
                    break
                }
            }
            if (i < 0) {
                digits.add(0, '1')
                point++
            }
        }
    }
    val whole = digits.subList(0, point).joinToString("").trimStart('0').ifEmpty { "0" }
    val frac = digits.subList(point, point + decimals).joinToString("")
    return if (decimals == 0) whole else "$whole.$frac"
}
