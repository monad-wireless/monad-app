package sk.martinvanco.monad.core.autofill

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one-shot request that closes the autofill session after a sign-in the server accepted.
 */
class CredentialSaveTest {

    @AfterTest
    fun tearDown() = CredentialSave.clear()

    @Test
    fun `nothing is requested by default`() {
        // A commit nobody asked for would close a session mid-typing and lose the fill.
        assertFalse(CredentialSave.requested.value)
        assertFalse(CredentialSave.consume())
    }

    @Test
    fun `a request is consumed exactly once`() {
        CredentialSave.request()
        assertTrue(CredentialSave.requested.value)

        assertTrue(CredentialSave.consume())

        // Twice matters: the framework treats an already-closed session as closed, and the
        // prompt for the NEXT sign-in is the one that goes missing.
        assertFalse(CredentialSave.consume())
        assertFalse(CredentialSave.requested.value)
    }

    @Test
    fun `two requests before a drain still commit once`() {
        CredentialSave.request()
        CredentialSave.request()
        assertTrue(CredentialSave.consume())
        assertFalse(CredentialSave.consume())
    }
}
