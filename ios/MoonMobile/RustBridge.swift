import Foundation

/// The Rust bridge (libmoonmobile.a, built from rust/) through its C functions. Every call takes
/// and returns JSON; see rust/src/api.rs. The shared Kotlin code reaches it through `Host`.
enum RustBridge {
    /// The bridge's own version.
    static func version() -> String { take(mm_version()) }

    /// Opens the configuration and starts the engine. Blocks for about a second: not on the
    /// main thread.
    static func open(_ request: String) -> String { request.withCString { take(mm_open($0)) } }

    /// Runs one command on the engine and waits for its answer.
    static func call(_ request: String) -> String { request.withCString { take(mm_call($0)) } }

    /// A request that needs no engine: the app's section of settings.toml.
    static func tool(_ request: String) -> String { request.withCString { take(mm_tool($0)) } }

    /// The latest state; with `since` equal to the current revision only says "unchanged".
    static func snapshot(since: Int64) -> String { take(mm_snapshot(since)) }

    /// Stops the engine and every core connection.
    static func close() { mm_close() }

    static var isOpen: Bool { mm_is_open() }

    /// One chart frame into [length] bytes at [pixels]: premultiplied RGBA.
    static func chart(_ request: String, pixels: UnsafeMutablePointer<UInt8>, length: Int) -> String {
        request.withCString { take(mm_chart($0, pixels, length)) }
    }

    /// The reply as a Swift string; the bridge's copy is freed.
    private static func take(_ reply: UnsafeMutablePointer<CChar>?) -> String {
        guard let reply = reply else {
            return #"{"ok":false,"error":"internal","message":"no reply from the bridge"}"#
        }
        defer { mm_free(reply) }
        return String(cString: reply)
    }
}
