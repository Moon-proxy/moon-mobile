package app.moonmobile.core

import app.moonmobile.json.JSONObject
import app.moonmobile.platform.PlatformContext as Context

/**
 * The station on a Linux server (Settings → Станция), set up and kept by the bridge with
 * MoonTerminal's own code. The phone's SSH key — the one the station's administrator `moon` lets
 * in — is kept in the phone's secure store and handed to the bridge before any server step.
 */
object StationClient {
    private const val KEY = "station_app_key"

    /** A server as typed: `1.2.3.4` or `1.2.3.4:2222`. */
    data class Address(val host: String, val port: Int) {
        val text: String get() = if (port == 22) host else "$host:$port"
    }

    fun parseAddress(text: String): Address? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val colon = trimmed.lastIndexOf(':')
        // One colon is a port; an IPv6 address has several and goes without one.
        if (colon > 0 && trimmed.count { it == ':' } == 1) {
            val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return Address(trimmed.substring(0, colon), port)
        }
        return Address(trimmed, 22)
    }

    /** The station set up from this phone, or null. */
    fun saved(context: Context): Address? {
        val host = AppPrefs.text(context, "station_host", "")
        if (host.isEmpty()) return null
        return Address(host, AppPrefs.text(context, "station_port", "22").toIntOrNull() ?: 22)
    }

    fun save(context: Context, address: Address?) {
        AppPrefs.setText(context, "station_host", address?.host ?: "")
        AppPrefs.setText(context, "station_port", (address?.port ?: 22).toString())
    }

    /** Hands the phone's key to the bridge, making and keeping one the first time; the error or null. */
    suspend fun ensureKey(context: Context): String? {
        val kept = SecureStore.get(context, KEY)
        val request = JSONObject().put("op", "station_key")
        if (kept != null) request.put("key", kept)
        val reply = Session.call(request)
        if (!reply.ok) return reply.message
        val made = reply.json.optString("key", "")
        if (kept == null && made.isNotEmpty() && !SecureStore.put(context, KEY, made)) {
            return "Не удалось сохранить ключ станции в защищённом хранилище телефона."
        }
        return null
    }

    /** Starts the job [op] on [address]; the reply says whether it started. */
    suspend fun start(op: String, address: Address, fill: JSONObject.() -> Unit = {}): Reply =
        Session.call(
            JSONObject()
                .put("op", op)
                .put("server", JSONObject().put("host", address.host).put("port", address.port))
                .apply(fill),
        )

    /** The running or last job: `kind`, `running`, `lines`, and `result` or `error` once it ended. */
    suspend fun job(): JSONObject = Session.call(JSONObject().put("op", "station_job")).json
}
