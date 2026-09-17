package sk.martinvanco.monad.auth.domain

/**
 * Something that has work to do when a user signs in or is about to sign out.
 *
 * Named as a port so [AuthManager] does not import the notifications data layer to register a
 * push token. [onSigningOut] runs *before* the local user row is cleared, because the work it
 * exists for — deleting the push token on the server — needs the bearer token that is about to go.
 */
interface SessionObserver {
    /** After the user row is written. Must return quickly; slow work belongs on the observer's own scope. */
    suspend fun onSignedIn()

    /** Before the user row is cleared, on logout and on account deletion. Must never throw or hang. */
    suspend fun onSigningOut()
}
