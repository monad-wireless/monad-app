package sk.martinvanco.monad.notifications.data

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.currentTimeMillis
import kotlinx.datetime.Instant
import sk.martinvanco.monad.notifications.data.api.NotificationsService
import sk.martinvanco.monad.notifications.data.dto.NotificationDto
import sk.martinvanco.monad.notifications.domain.InboxGrouping
import sk.martinvanco.monad.notifications.domain.InboxItem
import sk.martinvanco.monad.notifications.domain.NotificationType

/**
 * The one inbox, held for the whole process (IP-157).
 *
 * A singleton rather than screen state because two surfaces read it — the list, and the badge on
 * the top bar that is visible when the list is not — and both must agree. Reads come from the
 * cache first, so the badge is right on a phone with no route out; the network then appends what
 * arrived since the newest cached row.
 *
 * Marking read is local first and remote best-effort. The local stamp is what the badge and the
 * bold weight read; the server's own stamp arrives on the next refresh and, being older or equal,
 * replaces it harmlessly.
 */
class NotificationInbox(
    private val repository: NotificationRepository,
    private val service: NotificationsService,
    private val users: UserRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Null after a refresh that reached the server; a sentence otherwise. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Cache then network. Safe to call from any screen; never throws. */
    suspend fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        try {
            publish(repository.all())
            val token = users.getCurrentUser()?.token
            if (token == null) {
                _error.value = null
                return
            }
            val after = repository.newestSentAt()?.toString()
            val fetched = service.list(token, after).mapNotNull { it.toItemOrNull() }
            if (fetched.isNotEmpty()) repository.upsertAll(fetched)
            publish(repository.all())
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("[inbox] refresh failed: ${e.message}")
            _error.value = "Could not reach the server. Showing what this phone already has."
        } finally {
            _isRefreshing.value = false
        }
    }

    /** Fire-and-forget refresh, for a push arriving while the app is open. */
    fun requestRefresh() {
        scope.launch { refresh() }
    }

    suspend fun markRead(id: String) {
        val now = Instant.fromEpochMilliseconds(currentTimeMillis())
        repository.markRead(id, now)
        publish(repository.all())
        val token = users.getCurrentUser()?.token ?: return
        runCatching { service.markRead(token, id) }
            .onFailure { Napier.w("[inbox] mark-read did not reach the server: ${it.message}") }
    }

    /** Logout and account deletion. The next login refreshes with `after` unset. */
    suspend fun clear() {
        repository.deleteAll()
        publish(emptyList())
        _error.value = null
    }

    private fun publish(all: List<InboxItem>) {
        _items.value = all
        _unreadCount.value = InboxGrouping.unreadCount(all)
    }

    /** One malformed timestamp costs one row, not the list. */
    private fun NotificationDto.toItemOrNull(): InboxItem? {
        val sent = runCatching { Instant.parse(sentAt) }
            .onFailure { Napier.w("[inbox] row $id has unreadable sent_at '$sentAt'") }
            .getOrNull() ?: return null
        val read = readAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return InboxItem(
            id = id,
            type = NotificationType.fromWire(type),
            title = title,
            body = body,
            questId = questId,
            deepLink = deepLink,
            sentAt = sent,
            readAt = read,
        )
    }
}
