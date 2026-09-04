@file:OptIn(ExperimentalUuidApi::class)

package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import sk.martinvanco.monad.core.data.repository.SettingsRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The installation's identity, generated once and kept (IP-149).
 *
 * NOT `identifierForVendor`, NOT `ANDROID_ID`. Those survive a reinstall, and a reinstall is a new
 * instrument state — new permissions, new local clock epoch, new settings — so a new id is the
 * honest answer. It is also the privacy answer: the backend then holds no platform device
 * identifier, only a UUID this app minted, and the inventory groups installations by `machine`
 * to see the phones behind them. Owner decision, IP-149 Q1.
 *
 * Kept in the settings store beside the onboarding flag, because that store is the one thing that
 * lives as long as the installation does and no longer.
 */
class HandsetIdentity(private val settings: SettingsRepository) {

    suspend fun id(): String {
        val existing = settings.getSetting(KEY)
        if (!existing.isNullOrBlank()) return existing
        val fresh = Uuid.random().toString()
        settings.setSetting(KEY, fresh)
        return fresh
    }

    /**
     * The full descriptor for this installation, right now — or null when the platform probe
     * threw. The descriptor is evidence about a run; evidence must never be able to stop the run.
     */
    suspend fun describe(): HandsetDescriptor? =
        runCatching { describeHandset(id()) }
            .onFailure { Napier.w("[lab] handset descriptor unavailable: ${it.message}") }
            .getOrNull()

    companion object {
        const val KEY = "handset_id"
    }
}
