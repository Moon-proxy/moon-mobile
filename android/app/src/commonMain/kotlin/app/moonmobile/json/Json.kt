package app.moonmobile.json

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * JSON objects and arrays with the API of Android's `org.json`, written in common code so the very
 * same parsing runs on iOS. Behaves as Android's implementation does where the app relies on it:
 * `opt*` convert between numbers, strings and booleans, `optString` of a JSON null is "null",
 * `put` of a null removes the key, keys keep their order, and a whole number in a double is
 * written without ".0".
 *
 * A value is [JSONObject.NULL], a Boolean, Int, Long, Double, String, JSONObject or JSONArray.
 */
class JSONException(message: String) : RuntimeException(message)

class JSONObject() {
    internal val map = LinkedHashMap<String, Any?>()

    /** Parses [text], which must hold an object. */
    constructor(text: String) : this() {
        val value = JsonReader(text).root()
        if (value !is JSONObject) throw JSONException("Value is not a JSON object")
        map.putAll(value.map)
    }

    /** The entries of [copy], their values wrapped as JSON. */
    constructor(copy: Map<*, *>) : this() {
        for ((key, value) in copy) {
            map[key?.toString() ?: throw JSONException("Null key")] = wrap(value)
        }
    }

    fun length(): Int = map.size
    fun has(name: String): Boolean = map.containsKey(name)
    fun isNull(name: String): Boolean = map[name].let { it == null || it === NULL }
    fun opt(name: String): Any? = map[name]
    fun remove(name: String): Any? = map.remove(name)
    fun keys(): Iterator<String> = map.keys.toList().iterator()

    fun names(): JSONArray? = if (map.isEmpty()) null else JSONArray(map.keys.toList())

    fun put(name: String, value: Boolean): JSONObject = apply { map[name] = value }
    fun put(name: String, value: Int): JSONObject = apply { map[name] = value }
    fun put(name: String, value: Long): JSONObject = apply { map[name] = value }
    fun put(name: String, value: Double): JSONObject = apply { map[name] = checkDouble(value) }

    /** A null value removes [name], as on Android. */
    fun put(name: String, value: Any?): JSONObject = apply {
        if (value == null) {
            map.remove(name)
        } else {
            map[name] = normalise(value)
        }
    }

    fun get(name: String): Any = map[name] ?: throw JSONException("No value for $name")

    fun getBoolean(name: String): Boolean = toBoolean(get(name)) ?: throw mismatch(name, "boolean")
    fun getInt(name: String): Int = toInt(get(name)) ?: throw mismatch(name, "int")
    fun getLong(name: String): Long = toLong(get(name)) ?: throw mismatch(name, "long")
    fun getDouble(name: String): Double = toDouble(get(name)) ?: throw mismatch(name, "double")
    fun getString(name: String): String = get(name).toString()
    fun getJSONObject(name: String): JSONObject = get(name) as? JSONObject ?: throw mismatch(name, "JSONObject")
    fun getJSONArray(name: String): JSONArray = get(name) as? JSONArray ?: throw mismatch(name, "JSONArray")

    fun optBoolean(name: String, fallback: Boolean = false): Boolean = toBoolean(map[name]) ?: fallback
    fun optInt(name: String, fallback: Int = 0): Int = toInt(map[name]) ?: fallback
    fun optLong(name: String, fallback: Long = 0L): Long = toLong(map[name]) ?: fallback
    fun optDouble(name: String, fallback: Double = Double.NaN): Double = toDouble(map[name]) ?: fallback
    fun optString(name: String, fallback: String = ""): String = map[name]?.toString() ?: fallback
    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject
    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray

    override fun toString(): String = StringBuilder().also { JsonWriter.write(it, this, 0, 0) }.toString()

    /** Indented by [indent] spaces per level. */
    fun toString(indent: Int): String = StringBuilder().also { JsonWriter.write(it, this, indent, 0) }.toString()

    private fun mismatch(name: String, type: String) = JSONException("Value at $name is not a $type")

    companion object {
        /**
         * JSON's null as a value: equal to itself and to `null`, written as `null`. Kept out of the
         * iOS framework's header, where `NULL` is C's macro.
         */
        @OptIn(ExperimentalObjCRefinement::class)
        @HiddenFromObjC
        val NULL: Any = object {
            override fun equals(other: Any?): Boolean = other === this || other == null
            override fun hashCode(): Int = 0
            override fun toString(): String = "null"
        }
    }
}

class JSONArray() {
    internal val list = ArrayList<Any?>()

    /** Parses [text], which must hold an array. */
    constructor(text: String) : this() {
        val value = JsonReader(text).root()
        if (value !is JSONArray) throw JSONException("Value is not a JSON array")
        list.addAll(value.list)
    }

    /** The items of [copy], wrapped as JSON. */
    constructor(copy: Collection<*>) : this() {
        for (item in copy) list.add(wrap(item))
    }

    fun length(): Int = list.size
    fun opt(index: Int): Any? = list.getOrNull(index)
    fun isNull(index: Int): Boolean = opt(index).let { it == null || it === JSONObject.NULL }
    fun remove(index: Int): Any? = if (index in list.indices) list.removeAt(index) else null

    fun put(value: Boolean): JSONArray = apply { list.add(value) }
    fun put(value: Int): JSONArray = apply { list.add(value) }
    fun put(value: Long): JSONArray = apply { list.add(value) }
    fun put(value: Double): JSONArray = apply { list.add(checkDouble(value)) }
    fun put(value: Any?): JSONArray = apply { list.add(value?.let(::normalise)) }

    /** Sets [index], padding with nulls up to it. */
    fun put(index: Int, value: Any?): JSONArray = apply {
        if (index < 0) throw JSONException("Index $index out of range")
        while (list.size <= index) list.add(null)
        list[index] = value?.let(::normalise)
    }

    fun get(index: Int): Any = opt(index) ?: throw JSONException("Value at $index is null")

    fun getBoolean(index: Int): Boolean = toBoolean(get(index)) ?: throw mismatch(index, "boolean")
    fun getInt(index: Int): Int = toInt(get(index)) ?: throw mismatch(index, "int")
    fun getLong(index: Int): Long = toLong(get(index)) ?: throw mismatch(index, "long")
    fun getDouble(index: Int): Double = toDouble(get(index)) ?: throw mismatch(index, "double")
    fun getString(index: Int): String = get(index).toString()
    fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject ?: throw mismatch(index, "JSONObject")
    fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray ?: throw mismatch(index, "JSONArray")

    fun optBoolean(index: Int, fallback: Boolean = false): Boolean = toBoolean(opt(index)) ?: fallback
    fun optInt(index: Int, fallback: Int = 0): Int = toInt(opt(index)) ?: fallback
    fun optLong(index: Int, fallback: Long = 0L): Long = toLong(opt(index)) ?: fallback
    fun optDouble(index: Int, fallback: Double = Double.NaN): Double = toDouble(opt(index)) ?: fallback
    fun optString(index: Int, fallback: String = ""): String = opt(index)?.toString() ?: fallback
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

    override fun toString(): String = StringBuilder().also { JsonWriter.write(it, this, 0, 0) }.toString()

    fun toString(indent: Int): String = StringBuilder().also { JsonWriter.write(it, this, indent, 0) }.toString()

    private fun mismatch(index: Int, type: String) = JSONException("Value at $index is not a $type")
}

private fun checkDouble(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) throw JSONException("Forbidden numeric value: $value")
    return value
}

/** A value as it is stored: numbers as Int, Long or Double. */
private fun normalise(value: Any): Any = when (value) {
    is Double -> checkDouble(value)
    // Written as the float reads (0.1, not 0.10000000149011612), as Android does.
    is Float -> checkDouble(value.toString().toDouble())
    is Byte -> value.toInt()
    is Short -> value.toInt()
    else -> value
}

/** Any Kotlin value as JSON, as Android's `JSONObject.wrap` does. */
internal fun wrap(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long -> value
    is Number -> normalise(value)
    is Char -> value.toString()
    is Collection<*> -> JSONArray(value)
    is Array<*> -> JSONArray(value.toList())
    is Map<*, *> -> JSONObject(value)
    else -> if (value === JSONObject.NULL) value else value.toString()
}

private fun toBoolean(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is String -> when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> null
    }
    else -> null
}

private fun toDouble(value: Any?): Double? = when (value) {
    is Double -> value
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
}

private fun toInt(value: Any?): Int? = when (value) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.trim().toDoubleOrNull()?.toInt()
    else -> null
}

private fun toLong(value: Any?): Long? = when (value) {
    is Long -> value
    is Number -> value.toLong()
    is String -> value.trim().toDoubleOrNull()?.toLong()
    else -> null
}

private object JsonWriter {
    fun write(out: StringBuilder, value: Any?, indent: Int, depth: Int) {
        when (value) {
            null -> out.append("null")
            is JSONObject -> {
                out.append('{')
                var first = true
                for ((key, item) in value.map) {
                    if (!first) out.append(',')
                    first = false
                    newline(out, indent, depth + 1)
                    quote(out, key)
                    out.append(if (indent > 0) ": " else ":")
                    write(out, item, indent, depth + 1)
                }
                if (!first) newline(out, indent, depth)
                out.append('}')
            }
            is JSONArray -> {
                out.append('[')
                var first = true
                for (item in value.list) {
                    if (!first) out.append(',')
                    first = false
                    newline(out, indent, depth + 1)
                    write(out, item, indent, depth + 1)
                }
                if (!first) newline(out, indent, depth)
                out.append(']')
            }
            is String -> quote(out, value)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Double -> out.append(number(value))
            is Number -> out.append(number(value.toDouble()))
            else -> if (value === JSONObject.NULL) out.append("null") else quote(out, value.toString())
        }
    }

    /** A whole number without ".0", as Android writes it. */
    private fun number(value: Double): String {
        if (value == 0.0 && 1.0 / value < 0) return "-0"
        val whole = value.toLong()
        return if (value == whole.toDouble()) whole.toString() else value.toString()
    }

    private fun newline(out: StringBuilder, indent: Int, depth: Int) {
        if (indent <= 0) return
        out.append('\n')
        repeat(indent * depth) { out.append(' ') }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                ' ', ' ' -> out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> if (ch < ' ') {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }
}

/** Strict JSON with Android's number types: whole numbers as Int or Long, others as Double. */
private class JsonReader(private val text: String) {
    private var at = 0

    fun root(): Any {
        val value = value()
        return value ?: JSONObject.NULL
    }

    private fun fail(what: String): Nothing = throw JSONException("$what at character $at")

    private fun skipSpace() {
        while (at < text.length) {
            val ch = text[at]
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') at++ else return
        }
    }

    private fun value(): Any? {
        skipSpace()
        if (at >= text.length) fail("End of input")
        return when (val ch = text[at]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", JSONObject.NULL)
            else -> if (ch == '-' || ch in '0'..'9') number() else fail("Unexpected '$ch'")
        }
    }

    private fun literal(word: String, result: Any): Any {
        if (!text.startsWith(word, at)) fail("Expected $word")
        at += word.length
        return result
    }

    private fun obj(): JSONObject {
        val result = JSONObject()
        at++
        skipSpace()
        if (at < text.length && text[at] == '}') {
            at++
            return result
        }
        while (true) {
            skipSpace()
            if (at >= text.length || text[at] != '"') fail("Expected a name")
            val key = string()
            skipSpace()
            if (at >= text.length || text[at] != ':') fail("Expected ':'")
            at++
            result.map[key] = value()
            skipSpace()
            if (at >= text.length) fail("Unterminated object")
            when (text[at++]) {
                ',' -> continue
                '}' -> return result
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun array(): JSONArray {
        val result = JSONArray()
        at++
        skipSpace()
        if (at < text.length && text[at] == ']') {
            at++
            return result
        }
        while (true) {
            result.list.add(value())
            skipSpace()
            if (at >= text.length) fail("Unterminated array")
            when (text[at++]) {
                ',' -> continue
                ']' -> return result
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        at++
        val start = at
        // The common case: no escapes, one substring.
        while (at < text.length) {
            val ch = text[at]
            if (ch == '"') return text.substring(start, at).also { at++ }
            if (ch == '\\') break
            at++
        }
        val out = StringBuilder().appendRange(text, start, at)
        while (at < text.length) {
            val ch = text[at++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (at >= text.length) fail("Unterminated escape")
                    when (val esc = text[at++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (at + 4 > text.length) fail("Bad \\u escape")
                            val code = text.substring(at, at + 4).toIntOrNull(16) ?: fail("Bad \\u escape")
                            out.append(code.toChar())
                            at += 4
                        }
                        else -> fail("Bad escape '\\$esc'")
                    }
                }
                else -> out.append(ch)
            }
        }
        fail("Unterminated string")
    }

    private fun number(): Any {
        val start = at
        var decimal = false
        if (text[at] == '-') at++
        while (at < text.length) {
            val ch = text[at]
            when {
                ch in '0'..'9' -> at++
                ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || (ch == '-' && at > start) -> {
                    decimal = true
                    at++
                }
                else -> break
            }
        }
        val literal = text.substring(start, at)
        if (!decimal) {
            literal.toLongOrNull()?.let { whole ->
                return if (whole in Int.MIN_VALUE..Int.MAX_VALUE) whole.toInt() else whole
            }
        }
        return literal.toDoubleOrNull() ?: fail("Bad number '$literal'")
    }
}
