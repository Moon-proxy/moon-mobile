package app.moonmobile.core

/**
 * The Rust bridge (`rust/`): `libmoonmobile.so` on Android, the static library linked into the
 * iOS app. Every call takes and returns JSON; see `rust/src/api.rs` for the shapes. Drawing the
 * chart is each platform's own (its pixels go straight into the platform's bitmap).
 */
expect object Native {
    /** Opens the configuration and starts the engine. Blocks for about a second (key derivation). */
    fun open(request: String): String

    /** Runs one command on the engine and waits for its answer. */
    fun call(request: String): String

    /** The latest state; with [since] equal to the current revision only says "unchanged". */
    fun snapshot(since: Long): String

    /** A request that needs no engine: the app's section of `settings.toml`. */
    fun tool(request: String): String

    /** Stops the engine and every core connection. */
    fun close()

    fun isOpen(): Boolean

    fun version(): String
}
