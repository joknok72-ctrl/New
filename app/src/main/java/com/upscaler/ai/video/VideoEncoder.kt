package com.upscaler.ai.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware H.265 (HEVC) / H.264 encoder writing to an MP4 via MediaMuxer.
 * Audio from the source is copied bit-for-bit (no re-encode → zero quality loss, zero time).
 */
class VideoEncoder(
    private val ctx: Context,
    outPath: String,
    val width: Int,
    val height: Int,
    private val fps: Float,
    private val sourceUri: Uri,
    copyAudio: Boolean,
    preferHevc: Boolean = true,
) : AutoCloseable {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val TIMEOUT_US = 10_000L

        /** Bits per pixel per frame target. ~0.09 gives crisp results at 1080p (≈ 11 Mbps @30fps). */
        fun recommendedBitrate(w: Int, h: Int, fps: Float): Int {
            val bpp = when {
                w * h >= 3840 * 2160 -> 0.06f
                w * h >= 1920 * 1080 -> 0.09f
                else -> 0.12f
            }
            return (w * h * fps * bpp).toInt().coerceIn(2_000_000, 60_000_000)
        }

        fun hasEncoder(mime: String, w: Int, h: Int): Boolean {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, true) }) continue
                try {
                    val caps = info.getCapabilitiesForType(mime).videoCapabilities
                    if (caps.isSizeSupported(w, h)) return true
                } catch (_: Throwable) {}
            }
            return false
        }
    }

    val mime: String
    private val codec: MediaCodec
    val inputSurface: Surface
    private val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val info = MediaCodec.BufferInfo()
    private var audioExtractor: MediaExtractor? = null
    private var audioFormat: MediaFormat? = null
    var framesEncoded = 0L
        private set

    init {
        mime = if (preferHevc && hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height))
            MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val bitrate = recommendedBitrate(width, height, fps)
        val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setFloat(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        codec = MediaCodec.createEncoderByType(mime)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        Log.i(TAG, "Encoder $mime ${width}x$height @${fps}fps ${bitrate / 1_000_000} Mbps")

        if (copyAudio) prepareAudio()
    }

    private fun prepareAudio() {
        try {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, sourceUri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    ex.selectTrack(i)
                    audioExtractor = ex
                    audioFormat = f
                    return
                }
            }
            ex.release()
        } catch (t: Throwable) {
            Log.w(TAG, "audio copy unavailable: ${t.message}")
        }
    }

    /** Drain encoder output; call after each frame draw and at the end with endOfStream=true. */
    fun drain(endOfStream: Boolean) {
        if (endOfStream) codec.signalEndOfInputStream()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 50_000 else TIMEOUT_US)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    audioFormat?.let { audioTrack = muxer.addTrack(it) }
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf: ByteBuffer = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrack, buf, info)
                        framesEncoded++
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Copies all audio samples untouched. */
    private fun copyAudioSamples() {
        val ex = audioExtractor ?: return
        if (audioTrack < 0 || !muxerStarted) return
        val maxSize = audioFormat?.let {
            if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        } ?: 0
        val buf = ByteBuffer.allocate(maxSize.coerceAtLeast(256 * 1024))
        val bi = MediaCodec.BufferInfo()
        var n = 0
        while (true) {
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            bi.offset = 0; bi.size = size; bi.presentationTimeUs = ex.sampleTime
            bi.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(audioTrack, buf, bi)
            ex.advance(); n++
        }
        Log.i(TAG, "copied $n audio samples")
    }

    fun finish() {
        drain(true)
        copyAudioSamples()
    }

    override fun close() {
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        try { inputSurface.release() } catch (_: Throwable) {}
        try { if (muxerStarted) muxer.stop() } catch (t: Throwable) { Log.w(TAG, "muxer stop: ${t.message}") }
        try { muxer.release() } catch (_: Throwable) {}
        try { audioExtractor?.release() } catch (_: Throwable) {}
    }
}
