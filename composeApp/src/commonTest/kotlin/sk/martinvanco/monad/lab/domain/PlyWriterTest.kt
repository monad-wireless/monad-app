package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exported mesh's byte layout.
 *
 * This is a **published format**. A Python reader will open these files years from now, and a wrong
 * offset or a wrong endianness produces a mesh that loads without error and is geometrically nonsense —
 * sheared, inside out, or pointing at vertices that do not exist. None of that is detectable by looking
 * at the phone, so it is pinned here byte by byte.
 */
class PlyWriterTest {

    /** One triangle, so the arithmetic is checkable by hand. */
    private fun triangle(
        classifications: ByteArray? = null,
        normals: FloatArray? = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f),
    ) = MeshBlock(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
        normals = normals,
        indices = intArrayOf(0, 1, 2),
        classifications = classifications,
    )

    private fun header(bytes: ByteArray): String {
        val marker = "end_header\n"
        val text = bytes.decodeToString(0, minOf(bytes.size, 4096))
        return text.substring(0, text.indexOf(marker) + marker.length)
    }

    private fun bodyStart(bytes: ByteArray): Int = header(bytes).encodeToByteArray().size

    private fun floatAt(bytes: ByteArray, at: Int): Float {
        var bits = 0
        for (i in 0..3) bits = bits or ((bytes[at + i].toInt() and 0xFF) shl (8 * i))
        return Float.fromBits(bits)
    }

    private fun uintAt(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (i in 0..3) value = value or ((bytes[at + i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    @Test
    fun theHeaderDeclaresBinaryLittleEndianAndTheElementCounts() {
        val snapshot = PlyWriter.write(listOf(triangle(), triangle()))
        val header = header(snapshot.bytes)
        assertTrue(header.startsWith("ply\n"), header)
        assertTrue(header.contains("format binary_little_endian 1.0\n"), header)
        assertTrue(header.contains("element vertex 6\n"), header)
        assertTrue(header.contains("element face 2\n"), header)
        assertTrue(header.contains("property list uchar uint vertex_indices\n"), header)
        assertEquals(2, snapshot.anchors)
        assertEquals(6L, snapshot.vertices)
        assertEquals(2L, snapshot.faces)
    }

    @Test
    fun verticesAreLittleEndianFloatTriplesWithNormals() {
        val snapshot = PlyWriter.write(listOf(triangle()))
        var at = bodyStart(snapshot.bytes)
        // First vertex: position (0,0,0) then normal (0,1,0).
        assertEquals(0f, floatAt(snapshot.bytes, at))
        assertEquals(0f, floatAt(snapshot.bytes, at + 4))
        assertEquals(0f, floatAt(snapshot.bytes, at + 8))
        assertEquals(0f, floatAt(snapshot.bytes, at + 12))
        assertEquals(1f, floatAt(snapshot.bytes, at + 16))
        assertEquals(0f, floatAt(snapshot.bytes, at + 20))
        // Second vertex's x is one stride on.
        at += 24
        assertEquals(1f, floatAt(snapshot.bytes, at))
    }

    @Test
    fun aFaceIsACountByteThenThreeUnsignedIndices() {
        val snapshot = PlyWriter.write(listOf(triangle()))
        val faceAt = bodyStart(snapshot.bytes) + 3 * 24
        assertEquals(3, snapshot.bytes[faceAt].toInt())
        assertEquals(0, uintAt(snapshot.bytes, faceAt + 1))
        assertEquals(1, uintAt(snapshot.bytes, faceAt + 5))
        assertEquals(2, uintAt(snapshot.bytes, faceAt + 9))
    }

    @Test
    fun eachBlocksIndicesAreOffsetByTheVerticesBeforeIt() {
        // The defect this catches is the whole reason blocks cannot simply be concatenated. ARKit hands
        // out dozens of patches whose indices are local to themselves; writing them unshifted produces a
        // file where every block's triangles point into the first block's vertices. It loads fine and is
        // a mesh of the first patch smeared over the room.
        val snapshot = PlyWriter.write(listOf(triangle(), triangle()))
        val secondFaceAt = bodyStart(snapshot.bytes) + 6 * 24 + 13
        assertEquals(3, snapshot.bytes[secondFaceAt].toInt())
        assertEquals(3, uintAt(snapshot.bytes, secondFaceAt + 1))
        assertEquals(4, uintAt(snapshot.bytes, secondFaceAt + 5))
        assertEquals(5, uintAt(snapshot.bytes, secondFaceAt + 9))
    }

    @Test
    fun classificationIsWrittenPerFaceWhenEveryBlockHasIt() {
        val snapshot = PlyWriter.write(
            listOf(
                triangle(classifications = byteArrayOf(MeshClassification.WALL.code.toByte())),
                triangle(classifications = byteArrayOf(MeshClassification.SEAT.code.toByte())),
            )
        )
        assertTrue(snapshot.classified)
        assertTrue(header(snapshot.bytes).contains("property uchar classification\n"))
        val faceStride = 14
        val facesAt = bodyStart(snapshot.bytes) + 6 * 24
        assertEquals(MeshClassification.WALL.code, snapshot.bytes[facesAt + 13].toInt())
        assertEquals(MeshClassification.SEAT.code, snapshot.bytes[facesAt + faceStride + 13].toInt())
    }

    @Test
    fun aMixedSetIsWrittenUnclassifiedRatherThanInventingALabel() {
        // The sentinel problem, stated in the writer and pinned here. "This face has no label" and
        // "this face is labelled none" would be the same byte, and they are different facts — an
        // unlabelled surface must not become a surface ARKit declared featureless.
        val snapshot = PlyWriter.write(
            listOf(triangle(classifications = byteArrayOf(1)), triangle(classifications = null))
        )
        assertFalse(snapshot.classified)
        assertFalse(header(snapshot.bytes).contains("classification"))
        // And the record size follows the header, or every face after the first is misread.
        val expected = bodyStart(snapshot.bytes) + 6 * 24 + 2 * 13
        assertEquals(expected, snapshot.bytes.size)
    }

    @Test
    fun aBlockWithoutNormalsStillFillsItsFixedWidthRecord()  {
        // PLY is a fixed-width record format: the element declaration binds every vertex in the file, so
        // a block that omitted its normals would shift every subsequent vertex by twelve bytes.
        val snapshot = PlyWriter.write(listOf(triangle(normals = null), triangle()))
        assertEquals(bodyStart(snapshot.bytes) + 6 * 24 + 2 * 13, snapshot.bytes.size)
        val at = bodyStart(snapshot.bytes)
        assertEquals(0f, floatAt(snapshot.bytes, at + 12), "an absent normal is written as zero")
        // The fourth vertex is the second block's first, and its normal is present.
        assertEquals(1f, floatAt(snapshot.bytes, at + 3 * 24 + 16))
    }

    @Test
    fun theHeaderCarriesTheFrameAndTheClockItIsAlignedTo() {
        // A mesh whose coordinate frame is unstated cannot be laid on anything. The comments are the only
        // place a reader who has the file and not the sidecar can learn it.
        val snapshot = PlyWriter.write(
            listOf(triangle()),
            comments = listOf("frame ${MeshSummary.FRAME}", "aligned_with pose.tsv"),
        )
        val header = header(snapshot.bytes)
        assertTrue(header.contains("comment frame session-local-gravity\n"), header)
        assertTrue(header.contains("comment aligned_with pose.tsv\n"), header)
    }

    @Test
    fun aCommentCannotInjectHeaderLines() {
        // PLY has no escape, so a newline inside a comment would terminate it and the rest would be
        // parsed as a directive. Stripped rather than escaped, because a malformed header is an
        // unreadable file and this is reached with strings from a config bundle.
        val snapshot = PlyWriter.write(
            listOf(triangle()),
            comments = listOf("site fiit\nelement vertex 99"),
        )
        val lines = header(snapshot.bytes).trimEnd('\n').split('\n')
        assertTrue(lines.contains("comment site fiit element vertex 99"), lines.toString())
        // The assertion that matters is per **line**: the injected text survives as comment content, and
        // must not appear as a directive of its own. Substring matching would pass on the comment itself.
        assertEquals(1, lines.count { it.startsWith("element vertex ") }, lines.toString())
        assertTrue(lines.contains("element vertex 3"), lines.toString())
    }

    @Test
    fun anEmptySetIsAHeaderAndNothingElseAndSaysSo() {
        // Never uploaded — the instrument refuses an empty snapshot — but the flag has to be right, or a
        // header-only file could be stored as a successful export of nothing.
        val snapshot = PlyWriter.write(emptyList())
        assertTrue(snapshot.isEmpty)
        assertFalse(snapshot.classified)
        assertEquals(0L, snapshot.faces)
    }
}

class MeshProgressTest {

    private fun observation(
        anchorId: String,
        vertices: Long,
        faces: Long,
        revision: Int = 0,
        classified: Boolean = false,
    ) = MeshObservation(
        monotonicNanos = 1_000,
        wallMillis = 1,
        anchorId = anchorId,
        revision = revision,
        vertices = vertices,
        faces = faces,
        classified = classified,
        x = 0f,
        y = 0f,
        z = 0f,
    )

    @Test
    fun totalsAreAStateNotARunningSum() {
        // The defect this catches. ARKit reports a refined block as a *new count for the same geometry*,
        // so accumulating would inflate the room every time the walk passed the same wall twice — and
        // the operator's "is the scan growing?" readout would say yes while nothing new was found.
        val progress = MeshProgress.IDLE
            .plus(listOf(observation("a", vertices = 100, faces = 50)))
            .plus(listOf(observation("a", vertices = 180, faces = 90, revision = 1)))

        assertEquals(1, progress.anchors)
        assertEquals(180L, progress.vertices)
        assertEquals(90L, progress.faces)
        // Both rows are still in the change log — the history is what says when the block became this.
        assertEquals(2L, progress.revisions)
    }

    @Test
    fun separateBlocksAdd() {
        val progress = MeshProgress.IDLE.plus(
            listOf(
                observation("a", vertices = 100, faces = 50),
                observation("b", vertices = 20, faces = 10),
            )
        )
        assertEquals(2, progress.anchors)
        assertEquals(120L, progress.vertices)
        assertEquals(60L, progress.faces)
    }

    @Test
    fun anEmptyBatchChangesNothingAndCostsNothing() {
        // The common case: the observe loop finds no changes on most ticks.
        val progress = MeshProgress.IDLE.plus(listOf(observation("a", 10, 5)))
        assertEquals(progress, progress.plus(emptyList()))
    }

    @Test
    fun anIdleScanHasNoGeometryRatherThanZeroFaces() {
        assertFalse(MeshProgress.IDLE.hasGeometry)
        assertEquals(0, MeshProgress.IDLE.anchors)
    }

    @Test
    fun classificationIsRememberedOnceSeen() {
        // A later block arriving without labels does not un-classify the session; the PLY decides what it
        // can carry, and the console is reporting whether the device is producing semantics at all.
        val progress = MeshProgress.IDLE
            .plus(listOf(observation("a", 10, 5, classified = true)))
            .plus(listOf(observation("b", 10, 5, classified = false)))
        assertTrue(progress.classified)
    }
}
