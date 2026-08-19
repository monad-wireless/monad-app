package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The room's geometry, and — the part that makes it usable — **when it was observed**.
 *
 * A mesh on its own is a map with no place and no time. Three things have to be true before it can be
 * laid alongside a CSI capture, and all three are properties of this module rather than of the file:
 *
 * 1. **One frame.** The mesh vertices are transformed out of each anchor's local space into the same
 *    session-local, gravity-aligned frame the pose track lives in. So the mesh, the trajectory and the
 *    waypoints are in one coordinate system, and the waypoints are what carry all three into the
 *    building's frame at once. A mesh exported in anchor space would need a per-anchor transform the
 *    reader does not have.
 * 2. **One clock.** [MeshObservation] rows are stamped with the same `mono_ns` every other stream
 *    uses, so "the room looked like this from here" is a statement the analysis can act on. ARKit
 *    hands out no timestamps at all — anchors simply change — so the observation log is the only
 *    record that the geometry has a history.
 * 3. **A reference epoch.** `mono_ns` is device-local. `clock.tsv` is what maps it onto the server's
 *    Unix epoch, and the fleet's `csid` nodes are chrony-disciplined to that same epoch — so the chain
 *    from a triangle to a CSI record is: vertex → session frame → (waypoints) site frame, and
 *    `mono_ns` → (clock samples) Unix epoch → fleet capture window.
 *
 * Without any one of the three the mesh is decoration.
 */
@Serializable
data class MeshSummary(
    /** Mesh blocks ARKit was holding at close. Each is a small patch, not the whole room. */
    val anchors: Int,
    val vertices: Long,
    val faces: Long,
    /**
     * True when per-face semantic labels are present (wall / floor / ceiling / table / seat / window /
     * door / none).
     *
     * Requires `ARSceneReconstructionMeshWithClassification`, which not every LiDAR device offers, so
     * this is a fact about the recording rather than about the format.
     */
    val classified: Boolean,
    /** `ply-binary-le`. Named so a reader never has to sniff the file. */
    val format: String,
    @SerialName("bytes") val sizeBytes: Long,
    /**
     * The frame the vertices are in. `session-local-gravity`: origin where tracking started, +y up.
     *
     * Recorded rather than assumed, because it is the same frame as `pose.tsv` and that identity is
     * the whole reason the two files can be read together.
     */
    val frame: String = FRAME,
    /** First and last time any block was seen, on the shared monotonic clock. */
    @SerialName("first_observed_mono_ns") val firstObservedMonotonicNanos: Long? = null,
    @SerialName("last_observed_mono_ns") val lastObservedMonotonicNanos: Long? = null,
    /**
     * Geometry changes logged during the walk (rows in `mesh.tsv`).
     *
     * A high count late in a session is a room that was still being discovered, which is fine. A
     * change to a block the walk had already passed is a room that *moved* — someone shifted a chair —
     * and the exported mesh is then only valid for the window after that change. That is knowable from
     * the log and from nowhere else.
     */
    val revisions: Long = 0,
) {
    companion object {
        const val FRAME: String = "session-local-gravity"
        const val FORMAT: String = "ply-binary-le"
    }
}

/**
 * One observation of one mesh block: it appeared, or its geometry changed.
 *
 * A **change log**, not a periodic dump. Logging every block on every tick would write thousands of
 * identical rows and bury the handful that say something happened. What the analysis needs is the
 * moment a block's geometry became what the exported mesh contains.
 */
data class MeshObservation(
    val monotonicNanos: Long,
    val wallMillis: Long,
    /** ARKit's anchor UUID. Stable across updates, which is what makes a revision meaningful. */
    val anchorId: String,
    /** 0 on first sighting, incremented on each detected change. */
    val revision: Int,
    val vertices: Long,
    val faces: Long,
    val classified: Boolean,
    /** Where the block sits, in the session-local frame — the anchor's own translation. */
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * The bytes of an exported mesh, plus what they contain.
 *
 * The payload travels with its own counts so the summary never has to be recomputed by re-parsing the
 * file, and so an export that produced a header and no triangles is visible as such.
 */
class MeshSnapshot(
    val bytes: ByteArray,
    val anchors: Int,
    val vertices: Long,
    val faces: Long,
    val classified: Boolean,
) {
    val format: String get() = MeshSummary.FORMAT

    val isEmpty: Boolean get() = vertices == 0L || faces == 0L

    fun summary(
        firstObservedMonotonicNanos: Long?,
        lastObservedMonotonicNanos: Long?,
        revisions: Long,
    ): MeshSummary = MeshSummary(
        anchors = anchors,
        vertices = vertices,
        faces = faces,
        classified = classified,
        format = format,
        sizeBytes = bytes.size.toLong(),
        firstObservedMonotonicNanos = firstObservedMonotonicNanos,
        lastObservedMonotonicNanos = lastObservedMonotonicNanos,
        revisions = revisions,
    )
}

/**
 * A mesh block's geometry, already in the session-local frame.
 *
 * The hand-off between the platform (which owns Metal buffers) and the writer (which owns bytes).
 * Plain arrays on purpose: this is the one shape both sides can hold without either knowing about the
 * other, and it is what lets the PLY format be pinned by a test with no device in the room.
 *
 * @param positions `3 * vertexCount` floats, xyz interleaved.
 * @param normals `3 * vertexCount` floats, or null when the source had none.
 * @param indices `3 * faceCount` vertex indices, local to this block.
 * @param classifications one byte per **face**, or null when the session did not request semantics.
 */
class MeshBlock(
    val positions: FloatArray,
    val normals: FloatArray?,
    val indices: IntArray,
    val classifications: ByteArray?,
) {
    val vertexCount: Int get() = positions.size / 3
    val faceCount: Int get() = indices.size / 3

    init {
        require(positions.size % 3 == 0) { "positions must be xyz triples, got ${positions.size}" }
        require(indices.size % 3 == 0) { "indices must be triangles, got ${indices.size}" }
        require(normals == null || normals.size == positions.size) {
            "normals must match positions (${normals?.size} vs ${positions.size})"
        }
        require(classifications == null || classifications.size == faceCount) {
            "classifications are per face (${classifications?.size} vs $faceCount)"
        }
    }
}

/**
 * ARKit's semantic labels, as they land in the PLY's per-face `classification` byte.
 *
 * Here rather than left as bare integers because the numbers are the contract with the analysis side,
 * and a reader that has to guess whether 5 is a seat or a window will guess. The values are ARKit's
 * `ARMeshClassification`, carried through unchanged — this is a mirror, not a mapping.
 *
 * Load-bearing for the channel simulator: a wall and a seat have different permittivity, and the
 * whole reason to collect classified geometry rather than a bare mesh is that the ray tracer wants
 * materials rather than surfaces.
 */
enum class MeshClassification(val code: Int) {
    NONE(0),
    WALL(1),
    FLOOR(2),
    CEILING(3),
    TABLE(4),
    SEAT(5),
    WINDOW(6),
    DOOR(7),
    ;

    companion object {
        fun fromCode(code: Int): MeshClassification =
            entries.firstOrNull { it.code == code } ?: NONE
    }
}

/**
 * What the scan has found so far, for the console.
 *
 * Held by the instrument rather than the screen, like the pose progress, so backing out of the console
 * mid-walk does not reset the readout.
 *
 * The number worth watching is [faces] against time. A mesh that stops growing halfway through a walk
 * is LiDAR looking at something it cannot resolve — a window, a dark corridor, a surface past range —
 * and the fix is to walk that stretch again, slower. Discovered after the session it is a gap in the
 * geometry with no explanation.
 *
 * Not a data class, deliberately. The totals are **derived from the latest revision of each block**
 * rather than accumulated: ARKit reports a refined block as a new count for the same geometry, so a
 * running sum would inflate the total every time the walk passed the same wall twice. Holding the
 * per-block state is therefore part of what this is, and a `copy()` that dropped it would silently
 * reset the totals to whatever the last batch happened to contain.
 */
class MeshProgress private constructor(
    /** Latest observation per block. The state the totals are read off. */
    private val latest: Map<String, MeshObservation>,
    /** Rows written to the change log — first sightings plus detected changes. */
    val revisions: Long,
) {
    /** Distinct blocks seen. */
    val anchors: Int get() = latest.size

    val vertices: Long get() = latest.values.sumOf { it.vertices }

    val faces: Long get() = latest.values.sumOf { it.faces }

    val classified: Boolean get() = latest.values.any { it.classified }

    val hasGeometry: Boolean get() = faces > 0

    /** Fold a batch of change-log rows in, replacing each block's previous revision. */
    fun plus(observations: List<MeshObservation>): MeshProgress {
        if (observations.isEmpty()) return this
        val next = latest.toMutableMap()
        observations.forEach { next[it.anchorId] = it }
        return MeshProgress(next, revisions + observations.size)
    }

    companion object {
        val IDLE = MeshProgress(emptyMap(), 0)
    }
}
