package app.moonmobile.core

/**
 * The Rust bridge (`libmoonmobile.so`, built from `rust/`). Every call takes and returns JSON;
 * see `rust/src/api.rs` for the shapes.
 */
actual object Native {
    init {
        System.loadLibrary("moonmobile")
    }

    /** Opens the configuration and starts the engine. Blocks for about a second (key derivation). */
    @JvmStatic
    actual external fun open(request: String): String

    /** Runs one command on the engine and waits for its answer. */
    @JvmStatic
    actual external fun call(request: String): String

    /** The latest state; with [since] equal to the current revision only says "unchanged". */
    @JvmStatic
    actual external fun snapshot(since: Long): String

    /**
     * Draws one chart frame into [buffer], which must be a DIRECT buffer of `width * height * 4`
     * bytes (premultiplied RGBA, the layout of an ARGB_8888 bitmap). Asking for frames is also
     * what keeps the chart's market subscribed; a chart left alone for a few seconds is dropped.
     */
    @JvmStatic
    external fun chart(request: String, buffer: java.nio.ByteBuffer): String

    /** A request that needs no engine: the app's section of `settings.toml`. */
    @JvmStatic
    actual external fun tool(request: String): String

    /** Stops the engine and every core connection. */
    @JvmStatic
    actual external fun close()

    @JvmStatic
    actual external fun isOpen(): Boolean

    @JvmStatic
    actual external fun version(): String
}
