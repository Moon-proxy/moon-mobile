package app.moonmobile.text

actual fun localOffsetMs(epochMs: Long): Long = java.util.TimeZone.getDefault().getOffset(epochMs).toLong()

actual fun nowMs(): Long = System.currentTimeMillis()

actual fun timeZoneId(): String = java.util.TimeZone.getDefault().id
