package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canonical JSON bytes every IP-162 digest is taken over.
 *
 * The rules are the ones `monad_knowledge/lab/contracts/README.md` states and its
 * `fixtures/canonical/vectors.json` pins: object keys sorted by Unicode code point, no whitespace,
 * non-ASCII written as itself, only the escapes JSON requires (`\"`, `\\`, `\b` `\f` `\n` `\r`
 * `\t`, and `\u00XX` for the other C0 controls), `/` unescaped, literals as literals, and **no
 * floating-point numbers at all** — three languages print doubles three ways, so a non-integer
 * quantity is a decimal string.
 *
 * `kotlinx.serialization`'s encoder does none of the sorting and will happily write a double, so
 * this is a hand-rolled writer over a [JsonElement] rather than a `Json` configuration.
 */
object CanonicalJson {

    private val INTEGER = Regex("^-?(0|[1-9][0-9]*)$")

    fun encode(element: JsonElement): String {
        val sb = StringBuilder()
        write(element, sb)
        return sb.toString()
    }

    fun bytes(element: JsonElement): ByteArray = encode(element).encodeToByteArray()

    /** SHA-256 of [bytes], lower-case hex. */
    fun sha256(element: JsonElement): String = Sha256.hex(bytes(element))

    private fun write(element: JsonElement, sb: StringBuilder) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> {
                if (element.isString) {
                    writeString(element.content, sb)
                } else {
                    val literal = element.content
                    require(literal == "true" || literal == "false" || INTEGER.matches(literal)) {
                        "canonical JSON forbids the number $literal: use an integer or a decimal string"
                    }
                    sb.append(literal)
                }
            }
            is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    write(item, sb)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                element.keys.sortedWith(CODE_POINT_ORDER).forEachIndexed { index, key ->
                    if (index > 0) sb.append(',')
                    writeString(key, sb)
                    sb.append(':')
                    write(element.getValue(key), sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(value: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> {
                    sb.append("\\u00")
                    val code = ch.code
                    sb.append(HEX[code ushr 4]).append(HEX[code and 0x0f])
                }
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    private const val HEX = "0123456789abcdef"

    /**
     * Code-point order, which is also UTF-8 byte order. Kotlin's `String.compareTo` compares UTF-16
     * code units, and the two disagree exactly where a supplementary character meets a BMP character
     * above U+D7FF — rare in a key, and the one place a Python and a Kotlin digest would silently differ.
     */
    private val CODE_POINT_ORDER = Comparator<String> { a, b -> compareCodePoints(a, b) }

    private fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val (ca, na) = codePointAt(a, i)
            val (cb, nb) = codePointAt(b, j)
            if (ca != cb) return ca.compareTo(cb)
            i += na
            j += nb
        }
        return (a.length - i).compareTo(b.length - j)
    }

    /** `(code point, chars consumed)` at [index]. A lone surrogate is taken as its own code unit. */
    private fun codePointAt(s: String, index: Int): Pair<Int, Int> {
        val high = s[index]
        if (high.isHighSurrogate() && index + 1 < s.length) {
            val low = s[index + 1]
            if (low.isLowSurrogate()) {
                val cp = 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
                return cp to 2
            }
        }
        return high.code to 1
    }
}
