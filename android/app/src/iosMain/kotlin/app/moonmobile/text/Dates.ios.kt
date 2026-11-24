package app.moonmobile.text

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

actual fun localOffsetMs(epochMs: Long): Long =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)) * 1000L

actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun timeZoneId(): String = NSTimeZone.localTimeZone.name
