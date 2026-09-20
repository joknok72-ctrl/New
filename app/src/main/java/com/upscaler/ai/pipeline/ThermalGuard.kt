package com.upscaler.ai.pipeline

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Protects the phone: when Android reports SEVERE+ thermal status we pause the AI loop in
 * short slices until it cools down, instead of letting the OS throttle the GPU to a crawl
 * (which would actually make the whole job slower) or shut the app down.
 */
class ThermalGuard(ctx: Context) {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    var pausedMs = 0L
        private set

    suspend fun cooldownIfNeeded() {
        if (Build.VERSION.SDK_INT < 29) return
        var waited = 0L
        while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE && waited < 60_000) {
            if (waited == 0L) Log.w("ThermalGuard", "device hot (status=${pm.currentThermalStatus}), cooling down")
            delay(2_000); waited += 2_000
        }
        pausedMs += waited
    }
}
