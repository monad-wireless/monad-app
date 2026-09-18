package sk.martinvanco.monad.auth.domain

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sk.martinvanco.monad.core.data.repository.SettingsRepository

/**
 * Whether this handset's account may reach the operator half of the app.
 *
 * The app has always been two apps wearing one coat. A student walks quests, scans codes and reads
 * a board. An operator opens the lab console, drives a walk, reads per-stream liveness and inspects
 * an instrument that a participant has no way to act on. Until now every student carried the
 * second one on their home screen: a "console" badge in the greeting, a beacon counter, and an
 * instrument status card that says "Not recording" for the whole of a life that never records.
 *
 * So this is one boolean, read from `GET /api/auth/me` as `is_operator`, and it decides what the
 * app DRAWS. Three properties are deliberate:
 *
 * 1. **It is a cache of a server fact, never the authorization.** The server gates every operator
 *    surface that touches it — `/api/quests` withholds operator takes, `/admin` and `/mcp` refuse
 *    the request. Editing this value on a rooted phone buys a participant some empty panels and
 *    nothing else. A UI gate that is also the authorization is the bug this comment exists to
 *    prevent someone reintroducing.
 * 2. **It fails closed.** The default is false, a missing field reads as false, and a failed
 *    refresh leaves the last known value rather than assuming a promotion. A participant who
 *    briefly sees no console has lost nothing; a participant who briefly sees one has been shown a
 *    screen full of words about an instrument they are not holding.
 * 3. **It is persisted.** The home screen renders before `/api/auth/me` answers, and an operator
 *    whose console appears two seconds after launch would reasonably conclude it had moved.
 */
class OperatorAccess(
    private val settings: SettingsRepository,
) {
    private val _isOperator = MutableStateFlow(false)

    /** True while the signed-in account holds the operator grant. Safe to read from a composable. */
    val isOperator: StateFlow<Boolean> = _isOperator.asStateFlow()

    /** Load the last known value, so the first frame after launch is already right. */
    suspend fun restore() {
        _isOperator.value = runCatching { settings.isOperator() }
            .onFailure { Napier.w("[auth] operator flag unreadable, assuming participant: ${it.message}") }
            .getOrDefault(false)
    }

    /** Record what the server just said. Called wherever `GET /api/auth/me` is answered. */
    suspend fun remember(isOperator: Boolean) {
        if (_isOperator.value != isOperator) {
            Napier.i("[auth] operator access -> $isOperator")
        }
        _isOperator.value = isOperator
        runCatching { settings.setOperator(isOperator) }
            .onFailure { Napier.w("[auth] operator flag not persisted: ${it.message}") }
    }

    /** Sign-out. The next account on this handset starts as a participant. */
    suspend fun clear() {
        _isOperator.value = false
        runCatching { settings.setOperator(false) }
            .onFailure { Napier.w("[auth] operator flag not cleared: ${it.message}") }
    }
}
