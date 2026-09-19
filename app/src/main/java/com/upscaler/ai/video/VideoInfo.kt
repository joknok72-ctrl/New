package com.upscaler.ai.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val fps: Float,
    val frameCountEstimate: Long,
    val videoMime: String,
    val hasAudio: Boolean,
    val bitrate: Int,
    val displayName: String,
) {
    /** Width/height after applying rotation metadata (what the user actually sees). */
    val displayWidth get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight get() = if (rotation == 90 || rotation == 270) width else height

    companion object {
        fun probe(ctx: Context, uri: Uri): VideoInfo {
            val mmr = MediaMetadataRetriever()
            var fpsFromTrack = 0f
            var mime = "video/avc"
            var hasAudio = false
            try {
                mmr.setDataSource(ctx, uri)
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(ctx, uri, null)
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                        if (m.startsWith("video/")) {
                            mime = m
                            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                fpsFromTrack = try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                                catch (_: Throwable) { try { f.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Throwable) { 0f } }
                            }
                        } else if (m.startsWith("audio/")) hasAudio = true
                    }
                } finally { ex.release() }

                val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                val frames = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                val fpsMeta = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
                var fps = when {
                    fpsFromTrack > 1f -> fpsFromTrack
                    fpsMeta > 1f -> fpsMeta
                    frames > 0 && dur > 0 -> frames * 1000f / dur
                    else -> 30f
                }
                if (fps > 120f) fps = 30f
                val frameCount = if (frames > 0) frames else (dur / 1000f * fps).toLong()
                val name = queryDisplayName(ctx, uri)
                return VideoInfo(w, h, rot, dur, fps, frameCount, mime, hasAudio, br, name)
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
        }

        private fun queryDisplayName(ctx: Context, uri: Uri): String {
            try {
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return c.getString(0) ?: "video"
                }
            } catch (_: Throwable) {}
            return uri.lastPathSegment ?: "video"
        }
    }
}
