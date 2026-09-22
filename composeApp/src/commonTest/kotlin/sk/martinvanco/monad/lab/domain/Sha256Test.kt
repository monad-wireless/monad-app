package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** FIPS 180-4 vectors. If one fails, every IP-162 manifest digest this app writes is wrong. */
class Sha256Test {

    @Test
    fun emptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0)),
        )
    }

    @Test
    fun abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun twoBlockMessage() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun exactlyOneBlockOfPaddingBoundary() {
        // 55 bytes: the largest message that still fits its padding in one block; 56 bytes needs two.
        val fiftyFive = "a".repeat(55).encodeToByteArray()
        val fiftySix = "a".repeat(56).encodeToByteArray()
        assertEquals("9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318", Sha256.hex(fiftyFive))
        assertEquals("b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a", Sha256.hex(fiftySix))
    }
}
