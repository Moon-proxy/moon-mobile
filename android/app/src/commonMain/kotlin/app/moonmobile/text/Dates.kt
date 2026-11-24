package app.moonmobile.text

/** Milliseconds the phone's clock is ahead of UTC at the moment [epochMs], daylight saving included. */
expect fun localOffsetMs(epochMs: Long): Long

/** The phone's clock, Unix milliseconds. */
expect fun nowMs(): Long

/** The phone's time zone, as IANA names it (`America/Winnipeg`). */
expect fun timeZoneId(): String

/** The start of the phone's day that holds [epochMs]: local midnight, as Unix milliseconds. */
fun localDayStart(epochMs: Long): Long {
    val day = 86_400_000L
    val local = epochMs + localOffsetMs(epochMs)
    val sinceMidnight = ((local % day) + day) % day
    val guess = epochMs - sinceMidnight
    // A zone change that day (daylight saving) moves midnight by the difference.
    return guess - (localOffsetMs(guess) - localOffsetMs(epochMs))
}

/**
 * `java.text.SimpleDateFormat` in common code, for the subset the app uses: `yyyy` `yy` `MM` `M`
 * `dd` `d` `HH` `H` `mm` `ss` `SSS`, in the phone's zone; text in single quotes and any other
 * character are written as they are.
 */
class DateFormat(private val pattern: String) {
    fun format(epochMs: Long): String {
        val local = epochMs + localOffsetMs(epochMs)
        val days = floorDiv(local, DAY_MS)
        val inDay = local - days * DAY_MS
        val (year, month, day) = civil(days)
        val hour = (inDay / 3_600_000).toInt()
        val minute = (inDay / 60_000 % 60).toInt()
        val second = (inDay / 1000 % 60).toInt()
        val milli = (inDay % 1000).toInt()

        val out = StringBuilder()
        var at = 0
        while (at < pattern.length) {
            val ch = pattern[at]
            if (ch == '\'') {
                val end = pattern.indexOf('\'', at + 1).let { if (it < 0) pattern.length else it }
                if (end == at + 1) out.append('\'') else out.append(pattern, at + 1, end)
                at = end + 1
                continue
            }
            var run = 1
            while (at + run < pattern.length && pattern[at + run] == ch) run++
            when (ch) {
                'y' -> out.append(if (run == 2) two(year % 100) else year.toString().padStart(run, '0'))
                'M' -> out.append(month.toString().padStart(run, '0'))
                'd' -> out.append(day.toString().padStart(run, '0'))
                'H' -> out.append(hour.toString().padStart(run, '0'))
                'm' -> out.append(minute.toString().padStart(run, '0'))
                's' -> out.append(second.toString().padStart(run, '0'))
                'S' -> out.append(milli.toString().padStart(3, '0').take(run).padEnd(run, '0'))
                else -> repeat(run) { out.append(ch) }
            }
            at += run
        }
        return out.toString()
    }

    private companion object {
        const val DAY_MS = 86_400_000L

        fun two(value: Int) = value.toString().padStart(2, '0')

        fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
        }

        /** Year, month (1–12) and day of the proleptic Gregorian date [days] after 1970-01-01. */
        fun civil(days: Long): Triple<Int, Int, Int> {
            val z = days + 719_468
            val era = floorDiv(z, 146_097)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
            return Triple(year, month, day)
        }
    }
}
