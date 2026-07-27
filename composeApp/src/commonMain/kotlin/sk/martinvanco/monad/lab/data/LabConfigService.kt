package sk.martinvanco.monad.lab.data

import io.github.aakira.napier.Napier
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.core.data.repository.SettingsRepository
import sk.martinvanco.monad.lab.domain.LabConfig

/**
 * Fetches and caches the lab bundle (`GET /api/lab/config`).
 *
 * Nothing about a deployment is compiled into the app: SSIDs, the collector endpoint, the beacon
 * UUID and its zone map, and the traffic profiles all arrive from the backend. Moving to a
 * different room, or standing up a second rig, is then a config change rather than a release —
 * which matters because the instrument is meant to be usable in a lab, by hand, on a bench.
 *
 * The last good bundle is cached locally so a session can still start with no connectivity, which
 * is the normal state once the phone has joined an AP that has no route to the internet.
 */
class LabConfigService(
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _config = MutableStateFlow(LabConfig.EMPTY)
    val config: StateFlow<LabConfig> = _config.asStateFlow()

    private val _source = MutableStateFlow(Source.NONE)
    val source: StateFlow<Source> = _source.asStateFlow()

    enum class Source { NONE, CACHE, NETWORK, MANUAL }

    /** Load the cached bundle. Call at startup before any network is available. */
    suspend fun loadCached(): LabConfig {
        val raw = settings.getSetting(KEY_CONFIG) ?: return LabConfig.EMPTY
        return runCatching { json.decodeFromString<LabConfig>(raw) }
            .onSuccess {
                _config.value = it
                _source.value = Source.CACHE
            }
            .getOrElse {
                Napier.w("[lab] cached config unreadable: ${it.message}")
                LabConfig.EMPTY
            }
    }

    /** Fetch from the backend and cache on success. Falls back to whatever is already loaded. */
    suspend fun refresh(): Result<LabConfig> = runCatching {
        val fetched: LabConfig = KtorClient.client.get("/api/lab/config").body()
        settings.setSetting(KEY_CONFIG, json.encodeToString(LabConfig.serializer(), fetched))
        _config.value = fetched
        _source.value = Source.NETWORK
        Napier.i("[lab] config v${fetched.version} site=${fetched.site} " +
            "aps=${fetched.accessPoints.size} zones=${fetched.beacons.zones.size}")
        fetched
    }.onFailure {
        Napier.w("[lab] config refresh failed: ${it.message}")
    }

    /**
     * Operator override from the debug console. A bench rig often exists before the backend knows
     * about it, and requiring a server round-trip to point the instrument at a host on the desk in
     * front of you would make the tool useless exactly when it is most needed.
     */
    suspend fun overrideLocally(config: LabConfig) {
        settings.setSetting(KEY_CONFIG, json.encodeToString(LabConfig.serializer(), config))
        _config.value = config
        _source.value = Source.MANUAL
    }

    suspend fun clear() {
        settings.deleteSetting(KEY_CONFIG)
        _config.value = LabConfig.EMPTY
        _source.value = Source.NONE
    }

    private companion object {
        const val KEY_CONFIG = "lab_config_json"
    }
}
