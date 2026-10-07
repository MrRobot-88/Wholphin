package com.github.damontecres.wholphin.util

import android.app.ActivityManager
import android.content.Context

/**
 * Conservative runtime profile for TV hardware with a small Android heap.
 *
 * Some streaming devices don't set ro.config.low_ram even when their per-app heap is
 * small, so also treat a <=192 MB memory class as constrained. This only tunes caches
 * and prefetching; it never disables playback or app features.
 */
data class DevicePerformanceProfile(
    val lowMemory: Boolean,
    val memoryClassMb: Int,
)

fun Context.devicePerformanceProfile(): DevicePerformanceProfile {
    val activityManager = getSystemService(ActivityManager::class.java)
    val memoryClassMb = activityManager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB
    return DevicePerformanceProfile(
        lowMemory = activityManager?.isLowRamDevice == true || memoryClassMb <= LOW_MEMORY_CLASS_MB,
        memoryClassMb = memoryClassMb,
    )
}

private const val LOW_MEMORY_CLASS_MB = 192
private const val DEFAULT_MEMORY_CLASS_MB = 256
