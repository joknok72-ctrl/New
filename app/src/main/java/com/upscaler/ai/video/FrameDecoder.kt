package com.upscaler.ai.video

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer

/** One decoded frame in ARGB_8888 (int per pixel), in *coded* orientation (rotation applied later on GPU). */
class DecodedFrame(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)
    var ptsUs: Long = 0
    var index: Long = 0
}

/**
 * Hardware video decoder → ARGB frames on the CPU.
 * The input is tiny (144p–480p), so the YUV→RGB step is negligible; the AI is the bottleneck.
 * Uses the flexible YUV420 format which every Android decoder must support.
 */
class FrameDecoder(ctx: Context, uri: Uri) : AutoCloseable {
    companion object { private const val TAG = "FrameDecoder" }

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val format: MediaFormat
    val width: Int
    val height: Int
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()
    private var frameIndex = 0L

    init {
        extractor.setDataSource(ctx, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; fmt = f; break }
        }
        require(track >= 0 && fmt != null) { "No video track" }
        extractor.selectTrack(track)
        format = fmt
        width = fmt.getInteger(MediaFormat.KEY_WIDTH)
        height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        Log.i(TAG, "Decoder $mime ${width}x$height")
    }

    /** Returns false when the stream is finished. Blocks until a frame is available. */
    fun nextFrame(out: DecodedFrame): Boolean {
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val img = codec.getOutputImage(outIdx)
                        if (img != null) {
                            yuvToArgb(img, out)
                            img.close()
                            out.ptsUs = info.presentationTimeUs
                            out.index = frameIndex++
                            codec.releaseOutputBuffer(outIdx, false)
                            if (eos) outputDone = true
                            return true
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos) outputDone = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "format changed: ${codec.outputFormat}")
                }
                else -> { /* try again */ }
            }
        }
        return false
    }

    /** Fast YUV_420_888 → ARGB conversion honoring row/pixel strides (handles NV12, NV21, I420). */
    private fun yuvToArgb(img: Image, out: DecodedFrame) {
        val crop = img.cropRect
        val w = crop.width().coerceAtMost(out.width)
        val h = crop.height().coerceAtMost(out.height)
        val planes = img.planes
        val yBuf: ByteBuffer = planes[0].buffer
        val uBuf: ByteBuffer = planes[1].buffer
        val vBuf: ByteBuffer = planes[2].buffer
        val yRs = planes[0].rowStride; val yPs = planes[0].pixelStride
        val uRs = planes[1].rowStride; val uPs = planes[1].pixelStride
        val vRs = planes[2].rowStride; val vPs = planes[2].pixelStride
        val px = out.pixels
        val ox = crop.left; val oy = crop.top
        for (y in 0 until h) {
            val yRow = (oy + y) * yRs
            val cRow = ((oy + y) shr 1)
            val uRow = cRow * uRs
            val vRow = cRow * vRs
            val dRow = y * out.width
            for (x in 0 until w) {
                val yy = (yBuf.get(yRow + (ox + x) * yPs).toInt() and 0xFF) - 16
                val cx = (ox + x) shr 1
                val uu = (uBuf.get(uRow + cx * uPs).toInt() and 0xFF) - 128
                val vv = (vBuf.get(vRow + cx * vPs).toInt() and 0xFF) - 128
                // BT.601 limited range (what virtually all low-res web video uses)
                val y1192 = 1192 * (if (yy < 0) 0 else yy)
                var r = (y1192 + 1634 * vv) shr 10
                var g = (y1192 - 833 * vv - 400 * uu) shr 10
                var b = (y1192 + 2066 * uu) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                px[dRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        try { extractor.release() } catch (_: Throwable) {}
    }
}
