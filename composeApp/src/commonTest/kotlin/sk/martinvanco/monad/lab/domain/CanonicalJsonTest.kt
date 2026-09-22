package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The canonicalisation vectors from `monad-knowledge/monad_knowledge/lab/contracts/fixtures/canonical/vectors.json`,
 * inlined. The full fixture set runs in `androidUnitTest`; these are the ones a pure test can carry.
 */
class CanonicalJsonTest {

    @Test
    fun keyOrderAndLiterals() {
        val input = Json.parseToJsonElement("""{"b": 1, "a": [true, null, "x"]}""")
        assertEquals("""{"a":[true,null,"x"],"b":1}""", CanonicalJson.encode(input))
        assertEquals("54a65415ad370228851a1da4b31b6fd42dc58b19a50d35cae759325f7388ce64", CanonicalJson.sha256(input))
    }

    @Test
    fun unicodeSlashQuoteTab() {
        val input = Json.parseToJsonElement("""{"z": {"y": "žluťoučký kůň", "x": "tab\there"}, "a": "slash/quote\""}""")
        assertEquals("""{"a":"slash/quote\"","z":{"x":"tab\there","y":"žluťoučký kůň"}}""", CanonicalJson.encode(input))
        assertEquals("6f65438472468d146611d8319814b3e091186f78a97cd4c524294864c7fdb30a", CanonicalJson.sha256(input))
    }

    @Test
    fun nanosecondStringAndControlCharacter() {
        val input = Json.parseToJsonElement(
            """{"mono_ns": "1234567890123456789", "wall_ms": 1758441600000, "nested": {"k": [1, 2, {"c": "\u0001"}]}}""",
        )
        assertEquals(
            """{"mono_ns":"1234567890123456789","nested":{"k":[1,2,{"c":"\u0001"}]},"wall_ms":1758441600000}""",
            CanonicalJson.encode(input),
        )
        assertEquals("1eecfc4372e3802312bed0d2186866dc6b7cac353d832f6a8980f22b8efc648b", CanonicalJson.sha256(input))
    }

    @Test
    fun emptyContainers() {
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", CanonicalJson.sha256(Json.parseToJsonElement("{}")))
        assertEquals("4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", CanonicalJson.sha256(Json.parseToJsonElement("[]")))
    }

    @Test
    fun aFloatIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalJson.encode(Json.parseToJsonElement("""{"a": 1.5}"""))
        }
    }

    @Test
    fun keysSortByCodePointNotUtf16Unit() {
        // U+FF01 (one UTF-16 unit, 0xFF01) sorts BEFORE U+1F600 (surrogate pair, high unit 0xD83D)
        // by code point, and AFTER it by UTF-16 unit. The Python reference sorts by code point and
        // prints {"！":1,"😀":2}; a UTF-16 sort would print the emoji first.
        val input = Json.parseToJsonElement("""{"😀": 2, "！": 1}""")
        assertEquals("{\"！\":1,\"😀\":2}", CanonicalJson.encode(input))
    }
}
