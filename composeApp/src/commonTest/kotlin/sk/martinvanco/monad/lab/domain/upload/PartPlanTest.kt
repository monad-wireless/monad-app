package sk.martinvanco.monad.lab.domain.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a large artefact is cut, with no network in scope.
 *
 * The arithmetic is small and every one of its edges has a field consequence. An undersized
 * *interior* part fails at completion — after the whole transfer has been spent — rather than when
 * it is sent, so getting the last part right is the difference between an object and a wasted
 * upload. An empty plan would look like a successful upload of nothing.
 */
class PartPlanTest {

    private fun upload(totalBytes: Long, minPartBytes: Int = 5 * 1024 * 1024) = PartedUpload(
        sessionId = "s-1",
        participantId = "p-1",
        artefact = "mesh.ply",
        uploadId = "u-1",
        totalBytes = totalBytes,
        minPartBytes = minPartBytes,
    )

    @Test
    fun theRealMeshIsCutIntoWholePartsAndOneShortTail() {
        // 102.94 MB — the artefact the 2026-08-26 survey walk lost.
        val total = 102_940_000L
        val plan = upload(total).plan(PartedUpload.PART_BYTES)

        assertEquals(13, plan.size)
        assertEquals(total, plan.sumOf { it.length.toLong() }, "every byte must be in exactly one part")
        assertEquals(listOf(1, 13), listOf(plan.first().number, plan.last().number))
        assertTrue(plan.dropLast(1).all { it.length == PartedUpload.PART_BYTES })
        assertTrue(plan.last().length < PartedUpload.PART_BYTES)
    }

    @Test
    fun partsAreContiguousAndOnlyTheLastSaysSo() {
        val plan = upload(20L * 1024 * 1024).plan(PartedUpload.PART_BYTES)

        var expected = 0L
        plan.forEach { span ->
            assertEquals(expected, span.offset, "part ${span.number} must start where ${span.number - 1} ended")
            expected += span.length
        }
        assertEquals(listOf(false, false, true), plan.map { it.isLast })
    }

    @Test
    fun aStoreThatDemandsLargerPartsGetsThem() {
        // The floor comes from the server's `partSizeHint`, not from a client constant, so a store
        // with a 16 MiB minimum costs a config change rather than a release.
        val plan = upload(20L * 1024 * 1024, minPartBytes = 16 * 1024 * 1024).plan(PartedUpload.PART_BYTES)

        assertEquals(2, plan.size)
        assertEquals(16 * 1024 * 1024, plan.first().length)
        assertEquals(4 * 1024 * 1024, plan.last().length)
    }

    @Test
    fun anArtefactSmallerThanOnePartIsStillOnePart() {
        // Zero parts would be an empty manifest, which the store refuses — and a refused completion
        // reads as "the artefact uploaded and is empty", which is the worst available answer.
        val plan = upload(1_024L).plan(PartedUpload.PART_BYTES)

        assertEquals(1, plan.size)
        assertEquals(PartSpan(number = 1, offset = 0, length = 1_024, isLast = true), plan.single())
    }

    @Test
    fun anArtefactThatDividesEvenlyHasNoEmptyTail() {
        val plan = upload(PartedUpload.PART_BYTES * 2L).plan(PartedUpload.PART_BYTES)

        assertEquals(2, plan.size, "a trailing zero-length part would be rejected as an empty part")
        assertTrue(plan.last().isLast)
    }

    @Test
    fun theThresholdIsAtOrAboveTheStoresInteriorFloor() {
        // If the threshold sat below 5 MiB, a parted upload could be opened for an artefact whose
        // only interior part is illegal — and that fails at completion, not at send.
        assertTrue(PartedUpload.THRESHOLD_BYTES >= 5L * 1024 * 1024)
        assertEquals(PartedUpload.THRESHOLD_BYTES, PartedUpload.PART_BYTES.toLong())
    }
}
