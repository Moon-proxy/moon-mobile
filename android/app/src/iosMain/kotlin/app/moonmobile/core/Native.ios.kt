package app.moonmobile.core

import app.moonmobile.platform.Ios
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned

actual object Native {
    actual fun open(request: String): String = Ios.host.bridgeOpen(request)
    actual fun call(request: String): String = Ios.host.bridgeCall(request)
    actual fun snapshot(since: Long): String = Ios.host.bridgeSnapshot(since)
    actual fun tool(request: String): String = Ios.host.bridgeTool(request)
    actual fun close() = Ios.host.bridgeClose()
    actual fun isOpen(): Boolean = Ios.host.bridgeIsOpen()
    actual fun version(): String = Ios.host.bridgeVersion()

    /** One chart frame into [pixels]: premultiplied RGBA, `width * height * 4` bytes. */
    @OptIn(ExperimentalForeignApi::class)
    fun chart(request: String, pixels: ByteArray): String {
        if (pixels.isEmpty()) return """{"ok":false,"error":"bad_request","message":"no pixels"}"""
        return pixels.usePinned { pinned ->
            Ios.host.bridgeChart(request, pinned.addressOf(0).toLong(), pixels.size.toLong())
        }
    }
}
