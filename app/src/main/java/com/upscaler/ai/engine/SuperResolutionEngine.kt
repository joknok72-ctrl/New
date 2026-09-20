package com.upscaler.ai.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.min

/**
 * Real-ESRGAN x4 inference engine on ONNX Runtime.
 *
 * Smart design decisions (this is where the speed comes from):
 *  1. FIXED tile shape. NNAPI (GPU/NPU) compiles a graph per input shape; a dynamic
 *     shape per frame would recompile constantly. We always feed exactly
 *     [1,3,tile,tile] and pad the borders → compiled ONCE, then runs at full speed.
 *  2. Overlapping tiles (8px) with center-crop stitching → zero visible seams.
 *  3. Pre-allocated direct buffers; zero per-frame allocation in the hot loop.
 *  4. fp16 on NNAPI when available (2x throughput on most mobile GPUs/NPUs).
 *  5. Automatic fallback chain: NNAPI → XNNPACK (optimized ARM CPU) → plain CPU.
 */
class SuperResolutionEngine(
    private val ctx: Context,
    private val model: UpscaleModel,
    private val profile: DeviceProfiler.Profile,
    private val preferNnapi: Boolean = true,
) : AutoCloseable {

    companion object {
        private const val TAG = "SREngine"
        const val SCALE = 4
        const val OVERLAP = 8 // px in input space; must be even
    }

    val tile: Int = if (model.relativeCost >= 4f) (profile.tileSize / 2).coerceAtLeast(64) else profile.tileSize
    private val outTile = tile * SCALE
    private val step = tile - 2 * OVERLAP

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private lateinit var session: OrtSession
    var providerName: String = "cpu"
        private set

    // Hot-loop buffers
    private val inBuf: FloatBuffer =
        ByteBuffer.allocateDirect(3 * tile * tile * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val inShape = longArrayOf(1, 3, tile.toLong(), tile.toLong())
    private val inputName: String
    private val tilePixels = IntArray(tile * tile)

    var lastInferMs: Long = 0
        private set

    init {
        val bytes = ModelStore.loadBytes(ctx, model)
        session = createSession(bytes)
        inputName = session.inputNames.first()
        Log.i(TAG, "Loaded ${model.assetName} via $providerName, tile=$tile")
    }

    private fun createSession(bytes: ByteArray): OrtSession {
        // 1) NNAPI (GPU / DSP / NPU)
        if (preferNnapi && profile.hasNnapi) {
            try {
                val o = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
                    setIntraOpNumThreads(profile.threads)
                }
                val s = env.createSession(bytes, o)
                warmupOrThrow(s)
                providerName = "nnapi-fp16"
                return s
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI(fp16,cpu-disabled) failed: ${t.message}")
            }
            try {
                val o = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                    setIntraOpNumThreads(profile.threads)
                }
                val s = env.createSession(bytes, o)
                warmupOrThrow(s)
                providerName = "nnapi"
                return s
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI failed: ${t.message}")
            }
        }
        // 2) XNNPACK (NEON-optimized CPU)
        try {
            val o = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                addXnnpack(mapOf("intra_op_num_threads" to profile.threads.toString()))
                setIntraOpNumThreads(1)
            }
            val s = env.createSession(bytes, o)
            warmupOrThrow(s)
            providerName = "xnnpack"
            return s
        } catch (t: Throwable) {
            Log.w(TAG, "XNNPACK failed: ${t.message}")
        }
        // 3) Plain CPU
        val o = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(profile.threads)
        }
        providerName = "cpu"
        return env.createSession(bytes, o)
    }

    private fun warmupOrThrow(s: OrtSession) {
        val name = s.inputNames.first()
        inBuf.rewind()
        OnnxTensor.createTensor(env, inBuf, inShape).use { t ->
            s.run(mapOf(name to t)).use { r ->
                val out = r[0] as OnnxTensor
                val sh = out.info.shape
                check(sh[2].toInt() == outTile && sh[3].toInt() == outTile) { "bad output shape ${sh.toList()}" }
            }
        }
    }

    /**
     * Upscale an ARGB frame [w x h] to [4w x 4h].
     * @param src   ARGB_8888 pixels, row-major, length w*h
     * @param dst   pre-allocated, length (4w)*(4h)
     */
    fun upscale(src: IntArray, w: Int, h: Int, dst: IntArray) {
        val t0 = SystemClock.elapsedRealtime()
        val ow = w * SCALE
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                // Tile origin so that the "valid" (non-overlap) region starts at (x,y)
                val tx = (x - OVERLAP).coerceAtLeast(0).let { min(it, (w - tile).coerceAtLeast(0)) }
                val ty = (y - OVERLAP).coerceAtLeast(0).let { min(it, (h - tile).coerceAtLeast(0)) }
                extractTile(src, w, h, tx, ty)
                runTile()
                // Copy the part of the output corresponding to [x, x+step) × [y, y+step) clipped to frame
                val vx0 = x
                val vy0 = y
                val vx1 = min(x + step, w)
                val vy1 = min(y + step, h)
                writeBack(dst, ow, tx, ty, vx0, vy0, vx1, vy1)
                x += step
            }
            y += step
        }
        lastInferMs = SystemClock.elapsedRealtime() - t0
    }

    /** Copies a tile×tile region (reflect-padded at frame edges) into inBuf as CHW float [0,1]. */
    private fun extractTile(src: IntArray, w: Int, h: Int, tx: Int, ty: Int) {
        val buf = inBuf
        buf.rewind()
        val plane = tile * tile
        // gather pixels with clamp padding
        var i = 0
        for (yy in 0 until tile) {
            val sy = (ty + yy).coerceIn(0, h - 1)
            val row = sy * w
            for (xx in 0 until tile) {
                val sx = (tx + xx).coerceIn(0, w - 1)
                tilePixels[i++] = src[row + sx]
            }
        }
        // split to planes
        for (p in 0 until plane) {
            val px = tilePixels[p]
            buf.put(p, ((px shr 16) and 0xFF) * (1f / 255f))
            buf.put(plane + p, ((px shr 8) and 0xFF) * (1f / 255f))
            buf.put(2 * plane + p, (px and 0xFF) * (1f / 255f))
        }
    }

    // Pinned output tensor: ORT writes directly into this direct buffer (zero copy, zero GC).
    private val outBuf: FloatBuffer =
        ByteBuffer.allocateDirect(3 * outTile * outTile * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val outShape = longArrayOf(1, 3, outTile.toLong(), outTile.toLong())
    private val outputName: String by lazy { session.outputNames.first() }
    private val outTensor: OnnxTensor by lazy { OnnxTensor.createTensor(env, outBuf, outShape) }
    private val inTensor: OnnxTensor by lazy { OnnxTensor.createTensor(env, inBuf, inShape) }

    private var pinnedOk = true

    private fun runTile() {
        if (pinnedOk) {
            try {
                session.run(mapOf(inputName to inTensor), mapOf(outputName to outTensor)).close()
                return
            } catch (t: Throwable) {
                Log.w(TAG, "pinned output unsupported, falling back to copy: ${t.message}")
                pinnedOk = false
            }
        }
        session.run(mapOf(inputName to inTensor)).use { r ->
            val src = (r[0] as OnnxTensor).floatBuffer
            outBuf.rewind(); outBuf.put(src); outBuf.rewind()
        }
    }

    private fun writeBack(dst: IntArray, ow: Int, tx: Int, ty: Int, vx0: Int, vy0: Int, vx1: Int, vy1: Int) {
        val f = outBuf
        val plane = outTile * outTile
        // output pixel (ox,oy) in frame space ↔ tile-local ((ox - tx*4), (oy - ty*4))
        val oy0 = vy0 * SCALE
        val oy1 = vy1 * SCALE
        val ox0 = vx0 * SCALE
        val ox1 = vx1 * SCALE
        val ltx = tx * SCALE
        val lty = ty * SCALE
        for (oy in oy0 until oy1) {
            val ly = oy - lty
            val lrow = ly * outTile
            val drow = oy * ow
            for (ox in ox0 until ox1) {
                val lx = ox - ltx
                val idx = lrow + lx
                val r = clamp255(f.get(idx))
                val g = clamp255(f.get(plane + idx))
                val b = clamp255(f.get(2 * plane + idx))
                dst[drow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun clamp255(v: Float): Int {
        val x = (v * 255f + 0.5f).toInt()
        return if (x < 0) 0 else if (x > 255) 255 else x
    }

    /** Rough cost estimate: number of tile inferences per frame. */
    fun tilesPerFrame(w: Int, h: Int): Int {
        val nx = (w + step - 1) / step
        val ny = (h + step - 1) / step
        return nx * ny
    }

    override fun close() {
        try { inTensor.close() } catch (_: Throwable) {}
        try { outTensor.close() } catch (_: Throwable) {}
        try { session.close() } catch (_: Throwable) {}
    }
}
