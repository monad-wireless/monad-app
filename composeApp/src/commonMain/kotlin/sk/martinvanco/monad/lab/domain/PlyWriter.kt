package sk.martinvanco.monad.lab.domain

/**
 * Writes mesh blocks as one binary PLY.
 *
 * Pure, and that is deliberate: the format is a published artefact that a Python reader on the other
 * side has to parse years from now, so it is pinned by a test rather than by a device. Everything
 * platform-specific — Metal buffers, anchor transforms — happens before this is called.
 *
 * ### Why binary, and why PLY
 *
 * **Binary** because ASCII costs three to five times the bytes for numbers nobody reads by eye. A room
 * at ARKit's mesh resolution is a couple of hundred thousand triangles, so the difference is tens of
 * megabytes over a cellular upload.
 *
 * **PLY** because it is the one mesh format that carries *arbitrary per-face properties* and is read
 * by everything in the analysis chain — `plyfile`, `trimesh`, Open3D, MeshLab, CloudCompare, Blender.
 * OBJ cannot carry the per-face semantic label at all, which would mean shipping the classification
 * as a side-car array whose row order silently has to match the face order. glTF could, at the cost of
 * a JSON envelope and a buffer-view spec for a file nobody will open in a browser.
 *
 * ### The two things a reader must know
 *
 * * **Blocks are concatenated with offset indices.** ARKit hands out dozens of separate patches, each
 *   with vertex indices local to itself. They are merged into one vertex list here and each block's
 *   indices are shifted by the running vertex count. Vertices are **not** deduplicated across blocks:
 *   neighbouring patches genuinely overlap slightly, and welding them would move geometry to make a
 *   file look tidier.
 * * **`classification` is a face property that follows a list property.** That is legal PLY and is
 *   what every ARKit export does, but it is the part of the format most likely to trip a strict
 *   reader. `plyfile` and `trimesh` handle it. A reader that only wants triangles can ignore it.
 */
object PlyWriter {

    /** Bytes per vertex: three position floats plus three normal floats. */
    private const val VERTEX_STRIDE = 24

    /** `uchar` count plus three `uint` indices. */
    private const val FACE_STRIDE = 13

    /**
     * Merge [blocks] into one binary PLY.
     *
     * Classification is written when **every** block carries it. Mixing classified and unclassified
     * blocks in one file would need a sentinel for "this face has no label", which is exactly the same
     * byte as [MeshClassification.NONE] — an unlabelled surface and a surface labelled "none" are
     * different facts, and a format that cannot tell them apart should not claim either. So a mixed
     * set is written unclassified and the summary says so.
     *
     * @param comments free-text header lines. The frame and the clock belong here: a PLY that travels
     *   without them is a mesh in an unstated coordinate system.
     */
    fun write(blocks: List<MeshBlock>, comments: List<String> = emptyList()): MeshSnapshot {
        val vertexCount = blocks.sumOf { it.vertexCount }
        val faceCount = blocks.sumOf { it.faceCount }
        val classified = blocks.isNotEmpty() && blocks.all { it.classifications != null }

        val header = buildString {
            append("ply\n")
            append("format binary_little_endian 1.0\n")
            comments.forEach { line ->
                // Newlines would terminate the comment and inject a header line; stripped rather than
                // escaped, because PLY has no escape and a malformed header is an unreadable file.
                append("comment ").append(line.replace('\n', ' ').replace('\r', ' ')).append('\n')
            }
            append("element vertex ").append(vertexCount).append('\n')
            append("property float x\n")
            append("property float y\n")
            append("property float z\n")
            append("property float nx\n")
            append("property float ny\n")
            append("property float nz\n")
            append("element face ").append(faceCount).append('\n')
            append("property list uchar uint vertex_indices\n")
            if (classified) append("property uchar classification\n")
            append("end_header\n")
        }.encodeToByteArray()

        val faceStride = if (classified) FACE_STRIDE + 1 else FACE_STRIDE
        val bytes = ByteArray(header.size + vertexCount * VERTEX_STRIDE + faceCount * faceStride)
        header.copyInto(bytes)

        var at = header.size
        blocks.forEach { block ->
            val normals = block.normals
            for (v in 0 until block.vertexCount) {
                at = putFloat(bytes, at, block.positions[v * 3])
                at = putFloat(bytes, at, block.positions[v * 3 + 1])
                at = putFloat(bytes, at, block.positions[v * 3 + 2])
                if (normals != null) {
                    at = putFloat(bytes, at, normals[v * 3])
                    at = putFloat(bytes, at, normals[v * 3 + 1])
                    at = putFloat(bytes, at, normals[v * 3 + 2])
                } else {
                    // A zero normal is the conventional "unknown" and every reader tolerates it.
                    // Omitting the property for one block is not an option — PLY is a fixed-width
                    // record format, so the element declaration binds every vertex in the file.
                    at = putFloat(bytes, at, 0f)
                    at = putFloat(bytes, at, 0f)
                    at = putFloat(bytes, at, 0f)
                }
            }
        }

        var vertexBase = 0
        blocks.forEach { block ->
            val classes = block.classifications
            for (f in 0 until block.faceCount) {
                bytes[at++] = 3
                at = putUInt(bytes, at, vertexBase + block.indices[f * 3])
                at = putUInt(bytes, at, vertexBase + block.indices[f * 3 + 1])
                at = putUInt(bytes, at, vertexBase + block.indices[f * 3 + 2])
                if (classified) bytes[at++] = classes!![f]
            }
            vertexBase += block.vertexCount
        }

        return MeshSnapshot(
            bytes = bytes,
            anchors = blocks.size,
            vertices = vertexCount.toLong(),
            faces = faceCount.toLong(),
            classified = classified,
        )
    }

    /** IEEE-754 little-endian, which is what `binary_little_endian` declares. */
    private fun putFloat(target: ByteArray, at: Int, value: Float): Int =
        putUInt(target, at, value.toRawBits())

    private fun putUInt(target: ByteArray, at: Int, value: Int): Int {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value ushr 8) and 0xFF).toByte()
        target[at + 2] = ((value ushr 16) and 0xFF).toByte()
        target[at + 3] = ((value ushr 24) and 0xFF).toByte()
        return at + 4
    }
}
