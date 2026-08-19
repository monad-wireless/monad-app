package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.ARKit.ARCamera
import platform.ARKit.ARGeometryElement
import platform.ARKit.ARGeometrySource
import platform.ARKit.ARMeshGeometry
import platform.ARKit.ARMeshAnchor
import platform.ARKit.ARSceneReconstructionMesh
import platform.ARKit.ARSceneReconstructionMeshWithClassification
import platform.ARKit.ARSession
import platform.ARKit.ARTrackingState
import platform.ARKit.ARTrackingStateReason
import platform.ARKit.ARWorldAlignment
import platform.ARKit.ARWorldTrackingConfiguration
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np
import sk.martinvanco.monad.core.util.currentTimeMillis

/**
 * iOS visual-inertial odometry — ARKit world tracking, sampled.
 *
 * Three decisions here are the whole implementation, and each one is a trap avoided:
 *
 * **1. No view controller.** `ARSession` produces frames from a configuration and a camera; nothing
 * requires them to be *drawn*. The room-scan sensor module refuses for the opposite reason — a mesh
 * needs anchors accumulated by a rendering session — but a pose is available from
 * `session.currentFrame` the moment tracking starts. So the walk needs no AR view, and the phone
 * shows the lab console while it tracks.
 *
 * **2. Polling, not the delegate.** `ARSessionDelegate` fires at the camera's frame rate, 60 Hz,
 * which is thirty-six thousand rows for a ten-minute walk and far more resolution than a walking
 * body has. Polling `currentFrame` on a commanded period gives the rate the operator asked for, and
 * gives the health monitor a commanded rate to judge delivery against. A dropped poll is then
 * *visible* as delivery shortfall rather than invisible as a slower delegate.
 *
 * **3. The frame's own timestamp, moved onto the app's clock.** `ARFrame.timestamp` is on
 * `CLOCK_UPTIME_RAW` (it stops while the device sleeps); every other stream in this instrument is
 * stamped on `CLOCK_MONOTONIC_RAW` (it does not). Stamping a pose with "now" instead of when the
 * frame was captured would add up to one poll period of lag, and mixing the two clock bases would
 * add however long the device had slept since boot — a constant, unknown, and arbitrarily large
 * error. So the offset between the two is read at every sample and applied, which bounds the
 * conversion error to whatever sleep occurred inside one sample period. In a foreground AR session
 * that is zero.
 *
 * Foreground-only, and that is stated rather than worked around: ARKit pauses when the app leaves
 * the foreground, exactly as iOS advertising stops being readable by the fleet. Both roles a
 * fingerprinting walk needs have the same posture, so the activity is somebody holding a phone.
 */
@OptIn(ExperimentalForeignApi::class)
actual class PoseTracker actual constructor() {

    private val _samples = MutableSharedFlow<PoseSample>(
        replay = 0,
        // A walk polls at single-digit hertz and the consumer batches into SQLite, so the buffer is
        // sized for a stalled write rather than for throughput. Suspending the producer would be
        // wrong: the sampler must keep its phase, and a pose delayed by a database write is a pose
        // recorded at the wrong place.
        extraBufferCapacity = 512,
    )
    actual val samples: Flow<PoseSample> = _samples.asSharedFlow()

    private var session: ARSession? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var running = false
    private var meshEnabled = false

    /**
     * Per-anchor fingerprint of the geometry last logged, keyed by ARKit's anchor UUID.
     *
     * ARKit publishes no revision counter — an anchor's `geometry` simply becomes a different object —
     * so a change has to be *detected*. See [fingerprint] for what is and is not caught.
     */
    private val meshRevisions = mutableMapOf<String, MeshRevision>()

    private data class MeshRevision(val revision: Int, val fingerprint: Long)

    actual suspend fun probe(): LabSensorModule.Availability = when {
        !ARWorldTrackingConfiguration.isSupported() ->
            LabSensorModule.Availability.Unsupported("this device does not support ARKit world tracking")

        AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) !=
            AVAuthorizationStatusAuthorized ->
            LabSensorModule.Availability.NeedsPermission("camera")

        else -> LabSensorModule.Availability.Available
    }

    actual suspend fun start(rateHz: Double): Result<PoseTrackReport> {
        if (running) return Result.failure(IllegalStateException("already tracking"))
        when (val availability = probe()) {
            is LabSensorModule.Availability.Available -> Unit
            is LabSensorModule.Availability.NeedsPermission ->
                return Result.failure(
                    IllegalStateException("pose tracking needs the ${availability.permission} permission")
                )

            is LabSensorModule.Availability.Unsupported ->
                return Result.failure(IllegalStateException(availability.reason))
        }
        if (rateHz <= 0.0 || rateHz > MAX_RATE_HZ) {
            return Result.failure(IllegalArgumentException("pose rate $rateHz Hz is outside 0 < r <= $MAX_RATE_HZ"))
        }

        val notes = mutableListOf<String>()
        val configuration = ARWorldTrackingConfiguration()
        // Gravity-aligned rather than gravity-and-heading: heading alignment waits on a compass fix
        // and silently degrades indoors near steel, which is where every one of these walks happens.
        // A session-local frame plus scanned waypoints recovers the site frame without a compass.
        configuration.worldAlignment = ARWorldAlignment.ARWorldAlignmentGravity
        configuration.lightEstimationEnabled = false

        // Classification first, plain mesh second. The semantic labels are not a nicety here: the
        // ray-traced channel simulator wants materials rather than surfaces, and a wall and a seat have
        // different permittivity. Asking for classification when the device cannot supply it would fail
        // the whole configuration, so the fallback is checked rather than assumed.
        val classified =
            ARWorldTrackingConfiguration.supportsSceneReconstruction(
                ARSceneReconstructionMeshWithClassification
            )
        val plainMesh = ARWorldTrackingConfiguration.supportsSceneReconstruction(ARSceneReconstructionMesh)
        val depthAssisted = classified || plainMesh
        when {
            classified -> {
                configuration.sceneReconstruction = ARSceneReconstructionMeshWithClassification
                notes += "LiDAR mesh with semantic classification — depth-assisted tracking, and the " +
                    "exported geometry carries per-face wall/floor/ceiling/table/seat/window/door"
            }

            plainMesh -> {
                configuration.sceneReconstruction = ARSceneReconstructionMesh
                notes += "LiDAR mesh without classification on this device — depth-assisted tracking, " +
                    "geometry exported unlabelled"
            }

            else ->
                notes += "no LiDAR on this device — tracking is camera and IMU only, scale drifts " +
                    "further and no geometry is exported"
        }
        meshEnabled = depthAssisted

        // Created on the main thread: ARSession touches AVCaptureSession, which UIKit expects to be
        // configured there. Polling afterwards happens off it.
        val newSession = withContext(Dispatchers.Main) {
            ARSession().also { it.runWithConfiguration(configuration) }
        }
        session = newSession

        val periodNanos = (1_000_000_000.0 / rateHz).toLong().coerceAtLeast(1L)
        val newScope = CoroutineScope(Dispatchers.Default)
        scope = newScope
        running = true

        job = newScope.launch {
            // Absolute scheduling against an origin, for the same reason the illuminator does it: a
            // per-iteration delay accumulates every overshoot into a permanent phase drift, and a
            // pose stream whose phase drifts cannot be aligned to anything.
            val origin = monotonicNanos()
            var index = 0L
            while (isActive) {
                val frame = newSession.currentFrame
                val camera: ARCamera? = frame?.camera
                if (camera != null) {
                    val state = camera.trackingState
                    val quality = quality(state)
                    val reason = reason(state, camera.trackingStateReason)
                    // Read both clocks adjacently so the offset between them is measured at this
                    // instant, not inherited from session start.
                    val monotonicNow = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()).toLong()
                    val uptimeNow = clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()).toLong()
                    val frameUptimeNanos = (frame.timestamp * 1_000_000_000.0).toLong()
                    val stampNanos = frameUptimeNanos + (monotonicNow - uptimeNow)

                    val pose = camera.transform.useContents {
                        // `columns` is four contiguous `simd_float4` (the struct is 64 bytes, four
                        // 16-byte lanes), so reinterpreting it as sixteen floats is exact and avoids
                        // Kotlin/Native's experimental vector accessors entirely. Element `4*c + r`
                        // is row r of column c — column-major, as Metal and simd define it.
                        val m = columns.reinterpret<FloatVar>()
                        val c0 = floatArrayOf(m[0], m[1], m[2])
                        val c1 = floatArrayOf(m[4], m[5], m[6])
                        val c2 = floatArrayOf(m[8], m[9], m[10])
                        // Column 3 of a column-major rigid transform is the translation.
                        val translation = floatArrayOf(m[12], m[13], m[14])
                        val q = PoseGeometry.quaternion(c0, c1, c2)
                        PoseSample(
                            monotonicNanos = stampNanos,
                            wallMillis = currentTimeMillis(),
                            x = translation[0],
                            y = translation[1],
                            z = translation[2],
                            qx = q[0],
                            qy = q[1],
                            qz = q[2],
                            qw = q[3],
                            quality = quality,
                            reason = reason,
                        )
                    }
                    _samples.tryEmit(pose)
                }
                index++
                val dueAt = origin + index * periodNanos
                val waitNanos = dueAt - monotonicNanos()
                if (waitNanos > 0) {
                    // At least one millisecond. Integer division of a sub-millisecond wait yields
                    // delay(0), which does not sleep — it yields and comes straight back, so a rate
                    // near the polling ceiling would spin instead of pacing.
                    delay((waitNanos / 1_000_000L).coerceAtLeast(1L))
                } else {
                    // Behind schedule: skip to the next slot rather than firing a burst to catch up.
                    // A burst would put several poses at nearly the same instant and none at the
                    // times that were missed, which is worse than a gap the health monitor can see.
                    //
                    // The `+ 1` is load-bearing. Without it the recomputed slot is by construction
                    // already in the past, so the next iteration is late again, recomputes the same
                    // index, and the loop becomes a tight spin that never sleeps — a pegged core for
                    // the length of the walk, on the device that is also holding the camera open.
                    index = (monotonicNanos() - origin) / periodNanos + 1
                }
            }
        }

        Napier.i("[lab] pose tracking started at $rateHz Hz, depth=$depthAssisted")
        return Result.success(
            PoseTrackReport(
                implementation = IMPLEMENTATION,
                commandedRateHz = rateHz,
                depthAssisted = depthAssisted,
                worldAlignment = "gravity",
                notes = notes,
            )
        )
    }


    // ---- mesh -------------------------------------------------------------------------------

    actual suspend fun observeMesh(): List<MeshObservation> = withContext(Dispatchers.Main) {
        if (!meshEnabled) return@withContext emptyList()
        val frame = session?.currentFrame ?: return@withContext emptyList()
        val monotonic = monotonicNanos()
        val wall = currentTimeMillis()
        val changed = mutableListOf<MeshObservation>()

        frame.anchors.filterIsInstance<ARMeshAnchor>().forEach { anchor ->
            val id = anchor.identifier.UUIDString
            val geometry = anchor.geometry
            val vertexCount = geometry.vertices.count
            val faceCount = geometry.faces.count
            val print = fingerprint(geometry, vertexCount, faceCount)
            val previous = meshRevisions[id]
            if (previous != null && previous.fingerprint == print) return@forEach

            val revision = if (previous == null) 0 else previous.revision + 1
            meshRevisions[id] = MeshRevision(revision, print)
            val translation = anchor.transform.useContents {
                val m = columns.reinterpret<FloatVar>()
                floatArrayOf(m[12], m[13], m[14])
            }
            changed += MeshObservation(
                monotonicNanos = monotonic,
                wallMillis = wall,
                anchorId = id,
                revision = revision,
                vertices = vertexCount,
                faces = faceCount,
                classified = geometry.classification != null,
                x = translation[0],
                y = translation[1],
                z = translation[2],
            )
        }
        changed
    }

    /**
     * On the main thread, deliberately.
     *
     * ARKit publishes anchor updates on its own queue and UIKit-adjacent objects expect main-thread
     * access. Walking the anchor set anywhere else is the same class of undefined behaviour as touching
     * `UIApplication` off it: mostly fine, intermittently not, and on a device rather than here. This runs
     * once per session on the close path, so the cost is a few hundred milliseconds of a screen the
     * operator has already finished with.
     */
    actual suspend fun snapshotMesh(): Result<MeshSnapshot> = withContext(Dispatchers.Main) {
        if (!meshEnabled) {
            return@withContext Result.failure(
                IllegalStateException("scene reconstruction is not enabled on this session")
            )
        }
        val live = session
            ?: return@withContext Result.failure(IllegalStateException("no ARKit session to export from"))
        // Hold the frame, **then** pause. Holding it retains the frame and, through it, every anchor's
        // `ARMeshGeometry` and the Metal buffers underneath — so pausing cannot free what is about to be
        // read. Reading a running session's buffers means reading memory ARKit may be rebuilding on its
        // own queue as an anchor is refined, which is a torn mesh at best and a bad access at worst.
        //
        // Pausing here is safe to do twice: `stop()` pauses again a moment later and a paused session
        // ignores it.
        val frame = live.currentFrame
            ?: return@withContext Result.failure(IllegalStateException("no ARKit frame to export"))
        live.pause()
        val anchors = frame.anchors.filterIsInstance<ARMeshAnchor>()
        if (anchors.isEmpty()) {
            // A stated failure rather than an empty file. A PLY carrying a header and no triangles is
            // an export that looks successful and contains nothing, which is the outcome the whole
            // subsystem exists to avoid.
            return@withContext Result.failure(
                IllegalStateException("ARKit holds no mesh anchors — nothing was scanned")
            )
        }

        runCatching {
            val blocks = anchors.mapNotNull { anchor -> block(anchor) }
            if (blocks.isEmpty()) {
                throw IllegalStateException("every mesh anchor was unreadable")
            }
            PlyWriter.write(
                blocks = blocks,
                comments = listOf(
                    "MonadCount lab session mesh",
                    "source arkit-scene-reconstruction",
                    // The two facts a reader cannot recover from the geometry, and without which the
                    // file cannot be laid on anything else.
                    "frame ${MeshSummary.FRAME} (origin where tracking started, +y up against gravity)",
                    "aligned_with pose.tsv (identical frame) and mesh.tsv (identical mono_ns clock)",
                    "classification arkit ARMeshClassification: " +
                        MeshClassification.entries.joinToString(" ") { "${it.code}=${it.name.lowercase()}" },
                ),
            )
        }
    }

    /**
     * One anchor's geometry, lifted out of Metal and into the session-local frame.
     *
     * Two things happen here and both are necessary:
     *
     * **The buffers are read by stride, not by assuming packing.** `ARGeometrySource` declares an
     * `offset` and a `stride` precisely because the data need not be tightly packed — a `float3` is
     * commonly padded to sixteen bytes. Reading it as `3 * count` contiguous floats would produce
     * geometry that looks plausible and is sheared.
     *
     * **Every vertex is transformed by the anchor transform.** ARKit's vertices are in the anchor's own
     * local space; the transform places the block in the world. Exporting local coordinates would give a
     * file whose blocks all sit on top of each other at the origin, and no reader has the transforms.
     * Normals get the rotation only — the anchor transform is rigid, so no inverse-transpose is needed.
     */
    private fun block(anchor: ARMeshAnchor): MeshBlock? {
        val geometry = anchor.geometry
        val vertexSource = geometry.vertices
        val faceElement = geometry.faces
        val vertexCount = vertexSource.count.toInt()
        val faceCount = faceElement.count.toInt()
        if (vertexCount <= 0 || faceCount <= 0) return null

        // Three floats per vertex is the only layout ARKit produces here, and a different one would mean
        // reading a buffer as something it is not.
        if (vertexSource.componentsPerVector.toInt() != 3) {
            Napier.w("[lab] mesh anchor has ${vertexSource.componentsPerVector} components per vertex, skipped")
            return null
        }
        // Triangles. ARKit documents triangles and nothing else, but a quad list read as triangles is a
        // silently wrong mesh rather than an error.
        if (faceElement.indexCountPerPrimitive.toInt() != 3) {
            Napier.w("[lab] mesh anchor is not triangulated (${faceElement.indexCountPerPrimitive}), skipped")
            return null
        }

        val transform = anchor.transform.useContents {
            val m = columns.reinterpret<FloatVar>()
            FloatArray(16) { m[it] }
        }

        val positions = readVectors(vertexSource, vertexCount) ?: return null
        val normals = readVectors(geometry.normals, vertexCount)
        val indices = readIndices(faceElement, faceCount) ?: return null
        val classifications = readClassifications(geometry.classification, faceCount)

        // In place: a room mesh is hundreds of thousands of floats and a second array would double the
        // peak allocation on the close path, next to the sidecar render and the upload.
        for (v in 0 until vertexCount) {
            val i = v * 3
            val x = positions[i]
            val y = positions[i + 1]
            val z = positions[i + 2]
            positions[i] = transform[0] * x + transform[4] * y + transform[8] * z + transform[12]
            positions[i + 1] = transform[1] * x + transform[5] * y + transform[9] * z + transform[13]
            positions[i + 2] = transform[2] * x + transform[6] * y + transform[10] * z + transform[14]
            if (normals != null) {
                val nx = normals[i]
                val ny = normals[i + 1]
                val nz = normals[i + 2]
                normals[i] = transform[0] * nx + transform[4] * ny + transform[8] * nz
                normals[i + 1] = transform[1] * nx + transform[5] * ny + transform[9] * nz
                normals[i + 2] = transform[2] * nx + transform[6] * ny + transform[10] * nz
            }
        }

        return MeshBlock(
            positions = positions,
            normals = normals,
            indices = indices,
            classifications = classifications,
        )
    }

    /** `count` three-component float vectors, honouring the source's offset and stride. */
    private fun readVectors(source: ARGeometrySource, count: Int): FloatArray? {
        val base = source.buffer.contents() ?: return null
        val stride = source.stride.toInt()
        val offset = source.offset.toInt()
        val bytes = base.reinterpret<ByteVar>()
        val out = FloatArray(count * 3)
        for (v in 0 until count) {
            val at = bytes + (offset + v * stride)
            val floats = at!!.reinterpret<FloatVar>()
            out[v * 3] = floats[0]
            out[v * 3 + 1] = floats[1]
            out[v * 3 + 2] = floats[2]
        }
        return out
    }

    /**
     * `3 * count` vertex indices.
     *
     * `bytesPerIndex` is read rather than assumed: ARKit uses 32-bit indices today, and a 16-bit buffer
     * read as 32-bit would produce triangles pointing at vertices that do not exist — which crashes a
     * reader in analysis, weeks later, not here.
     */
    private fun readIndices(element: ARGeometryElement, faceCount: Int): IntArray? {
        val base = element.buffer.contents() ?: return null
        val total = faceCount * 3
        return when (val width = element.bytesPerIndex.toInt()) {
            4 -> {
                val ints = base.reinterpret<IntVar>()
                IntArray(total) { ints[it] }
            }

            2 -> {
                val shorts = base.reinterpret<ShortVar>()
                IntArray(total) { shorts[it].toInt() and 0xFFFF }
            }

            else -> {
                Napier.w("[lab] mesh anchor uses $width-byte indices, skipped")
                null
            }
        }
    }

    /** One classification byte per face, or null when the session did not request semantics. */
    private fun readClassifications(source: ARGeometrySource?, faceCount: Int): ByteArray? {
        if (source == null) return null
        if (source.count.toInt() != faceCount) {
            Napier.w("[lab] classification count ${source.count} != $faceCount faces, dropped")
            return null
        }
        val base = source.buffer.contents() ?: return null
        val bytes = base.reinterpret<ByteVar>()
        val stride = source.stride.toInt().coerceAtLeast(1)
        val offset = source.offset.toInt()
        return ByteArray(faceCount) { bytes[offset + it * stride] }
    }

    /**
     * A cheap change detector for one anchor's geometry.
     *
     * Counts plus a strided sample of the vertex buffer. **It can miss a small local change** that
     * leaves the counts identical and touches none of the sampled vertices, and that limitation is
     * stated rather than hidden: the alternative is hashing hundreds of thousands of floats per anchor
     * on a timer, on a phone that is simultaneously holding the camera open and pacing a pose stream.
     *
     * What it reliably catches is what matters — a block appearing, a block being refined as the walk
     * gets closer to it, and a block being rebuilt because something in the room moved.
     */
    private fun fingerprint(geometry: ARMeshGeometry, vertices: Long, faces: Long): Long {
        var hash = vertices * 31 + faces
        val source = geometry.vertices
        val base = source.buffer.contents() ?: return hash
        val count = vertices.toInt()
        if (count <= 0) return hash
        val stride = source.stride.toInt()
        val offset = source.offset.toInt()
        val bytes = base.reinterpret<ByteVar>()
        val step = (count / FINGERPRINT_SAMPLES).coerceAtLeast(1)
        var v = 0
        while (v < count) {
            val at = (bytes + (offset + v * stride))!!.reinterpret<FloatVar>()
            hash = hash * 1_000_003L + at[0].toRawBits()
            hash = hash * 1_000_003L + at[2].toRawBits()
            v += step
        }
        return hash
    }

    actual fun stop() {
        if (!running) return
        running = false
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        session?.pause()
        session = null
        meshEnabled = false
        meshRevisions.clear()
        Napier.i("[lab] pose tracking stopped")
    }

    actual fun diagnostics(): List<String> = buildList {
        val supported = ARWorldTrackingConfiguration.isSupported()
        add("ARKit world tracking: ${if (supported) "supported" else "NOT SUPPORTED"}")
        if (supported) {
            val depth =
                ARWorldTrackingConfiguration.supportsSceneReconstruction(ARSceneReconstructionMesh)
            add("LiDAR scene reconstruction: ${if (depth) "available" else "absent"}")
        }
        val camera = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        add(
            "camera authorization: " +
                if (camera == AVAuthorizationStatusAuthorized) "granted" else "MISSING"
        )
        add("tracking is foreground-only — ARKit pauses when the app is backgrounded")
    }

    private fun quality(state: ARTrackingState): TrackingQuality = when (state) {
        ARTrackingState.ARTrackingStateNormal -> TrackingQuality.NORMAL
        ARTrackingState.ARTrackingStateLimited -> TrackingQuality.LIMITED
        else -> TrackingQuality.UNAVAILABLE
    }

    private fun reason(state: ARTrackingState, reason: ARTrackingStateReason): String? {
        if (state == ARTrackingState.ARTrackingStateNormal) return null
        return when (reason) {
            ARTrackingStateReason.ARTrackingStateReasonInitializing -> "initializing"
            ARTrackingStateReason.ARTrackingStateReasonRelocalizing -> "relocalizing"
            ARTrackingStateReason.ARTrackingStateReasonExcessiveMotion -> "excessive_motion"
            ARTrackingStateReason.ARTrackingStateReasonInsufficientFeatures ->
                "insufficient_features"

            else -> "unspecified"
        }
    }

    private companion object {
        const val IMPLEMENTATION = "arkit-world-tracking"

        /**
         * Above the camera's own frame rate polling returns the same frame repeatedly, which would
         * write duplicate rows and inflate every count that reads them.
         */
        const val MAX_RATE_HZ = 60.0

        /**
         * Vertices sampled per anchor when fingerprinting its geometry.
         *
         * Sixteen. Enough that a refined block changes the hash in practice, cheap enough that a
         * hundred anchors cost sixteen hundred strided reads per tick rather than millions.
         */
        const val FINGERPRINT_SAMPLES = 16
    }
}
