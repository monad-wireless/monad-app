package sk.martinvanco.monad.lab.domain

/**
 * The digest of an enrollment's frozen step snapshot, as the app received it at quest start (IP-162).
 *
 * The evidence manifest names it as `snapshot_sha256` so a reader can tell which frozen
 * configuration a recording ran under without asking the backend. The rows live in the quest
 * feature's step journal, which the lab's upload path must not name directly — hence a port here
 * and an adapter beside the other quest adapters.
 */
interface SnapshotDigestSource {
    /** Lower-case hex SHA-256, or null when the enrollment's steps are no longer on this device. */
    suspend fun snapshotSha256(enrollmentId: String): String?
}
