package sk.martinvanco.monad.lab.domain

/**
 * Free space on the volume the session database lives on, in bytes.
 *
 * Running out mid-session is not a degraded run, it is a lost one: SQLite begins failing inserts,
 * the sample streams stop, and nothing in the app is in a position to tell the participant — the
 * session simply ends up truncated. It is also entirely predictable before the session starts,
 * which is why the pre-flight asks.
 *
 * Returns 0 when the platform cannot answer; the pre-flight reports that as "unknown" rather than
 * as "full".
 */
expect fun availableStorageBytes(): Long

/** Total size of the same volume, for context on the free figure. 0 when unknown. */
expect fun totalStorageBytes(): Long
