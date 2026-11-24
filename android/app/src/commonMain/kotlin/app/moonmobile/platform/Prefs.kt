package app.moonmobile.platform

/**
 * Small settings kept by name, with the API of Android's SharedPreferences so the shared code reads
 * as it did: SharedPreferences on Android, NSUserDefaults on iOS.
 */
interface Prefs {
    fun getString(key: String, fallback: String?): String?
    fun getBoolean(key: String, fallback: Boolean): Boolean
    fun getInt(key: String, fallback: Int): Int
    fun getLong(key: String, fallback: Long): Long
    fun getFloat(key: String, fallback: Float): Float
    fun getStringSet(key: String, fallback: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putStringSet(key: String, value: Set<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/** The settings file [name] of the app. */
expect fun prefsOf(context: PlatformContext, name: String): Prefs
