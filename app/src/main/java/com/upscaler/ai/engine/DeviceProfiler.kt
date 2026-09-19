package com.upscaler.ai.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Probes the device to pick the best execution provider and tile size automatically.
 * Goal: "the app is smart" — the user never has to tune anything.
 */
object DeviceProfiler {
    private const val TAG = "DeviceProfiler"

    data class Profile(
        val cpuCores: Int,
        val totalRamMb: Long,
        val hasNnapi: Boolean,
        val socHint: String,
        /** Input tile edge in pixels (before x4). Output tile = 4x. */
        val tileSize: Int,
        /** Intra-op threads for ORT CPU fallback. */
        val threads: Int,
        val isHighEnd: Boolean,
    )

    fun profile(ctx: Context): Profile {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramMb = mi.totalMem / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val hasNnapi = Build.VERSION.SDK_INT >= 27

        // Tile size: bigger tiles = fewer kernel launches = faster, but more memory.
        // Activation memory for 64-feature net at tile T: ~ T*T*64*4 bytes * few buffers.
        val tile = when {
            ramMb >= 8000 -> 256
            ramMb >= 6000 -> 192
            ramMb >= 4000 -> 160
            else -> 128
        }
        val threads = (cores - 1).coerceIn(2, 6)
        val highEnd = ramMb >= 6000 && cores >= 8

        val p = Profile(cores, ramMb, hasNnapi, soc, tile, threads, highEnd)
        Log.i(TAG, "Device profile: $p")
        return p
    }
}
