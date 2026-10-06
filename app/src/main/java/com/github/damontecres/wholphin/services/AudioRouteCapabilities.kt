package com.github.damontecres.wholphin.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/*
 * Adapted from Moonfin-Client/Moonfin-Core (GPL-2.0),
 * android/app/.../AudioCapabilities.kt, donor commit
 * 36ed696f1d02ba240b459e953d98965ae514648c.
 *
 * This probe is diagnostic/advisory. Media3's AudioSink remains the final
 * authority for whether a compressed format can actually be bitstreamed on
 * the current route.
 */
data class AudioRouteCapabilities(
    val route: String,
    val passthroughCodecs: Set<String>,
    val maxPcmChannels: Int,
    val canIecLow: Boolean,
    val canIecMid: Boolean,
    val canIecHbr: Boolean,
) {
    fun describe(): String =
        "route=$route passthrough=${passthroughCodecs.sorted().joinToString(",").ifEmpty { "none" }} " +
            "pcmChannels=$maxPcmChannels iecLow=$canIecLow iecMid=$canIecMid iecHbr=$canIecHbr"
}

object AudioRouteCapabilitiesDetector {
    private const val ROUTE_HDMI = "hdmi"
    private const val ROUTE_ARC = "arc"
    private const val ROUTE_EARC = "earc"
    private const val ROUTE_BLUETOOTH = "bluetooth"
    private const val ROUTE_SPEAKER = "speaker"
    private const val ROUTE_HEADPHONES = "headphones"
    private const val ROUTE_OTHER = "other"

    private val mediaAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

    fun query(context: Context): AudioRouteCapabilities {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return baseline()
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return baseline()

        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val activeOutputs = resolveActiveMediaDevices(audioManager)
        val routeOutputs = activeOutputs.ifEmpty { allOutputs }
        val route = classifyRoute(routeOutputs.map { it.type }.toSet())
        val bitstreamOutputs = allOutputs.filter(::isBitstreamOutputDevice)

        val encodings = mutableSetOf<Int>()
        encodings += bitstreamOutputs.flatMap { it.encodings.toList() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            encodings += bitstreamOutputs.flatMap { device ->
                device.audioProfiles.map { profile -> profile.format }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            encodings += runCatching {
                audioManager.getDirectProfilesForAttributes(mediaAttributes).map { it.format }
            }.getOrDefault(emptyList())
        }

        val codecs = linkedSetOf<String>()
        if (supportsEncoding(audioManager, AudioFormat.ENCODING_AC3, encodings)) codecs += "ac3"
        if (supportsEncoding(audioManager, AudioFormat.ENCODING_E_AC3, encodings)) codecs += "eac3"
        if (supportsEncoding(audioManager, AudioFormat.ENCODING_DTS, encodings)) codecs += "dts"
        if (supportsEncoding(audioManager, AudioFormat.ENCODING_DTS_HD, encodings)) codecs += "dtshd"
        if (supportsEncoding(audioManager, AudioFormat.ENCODING_DOLBY_TRUEHD, encodings)) codecs += "truehd"

        return AudioRouteCapabilities(
            route = route,
            passthroughCodecs = codecs,
            maxPcmChannels = detectMaxPcmChannels(routeOutputs, route),
            canIecLow = probeIecCarrier(48_000, AudioFormat.CHANNEL_OUT_STEREO),
            canIecMid = probeIecCarrier(192_000, AudioFormat.CHANNEL_OUT_STEREO),
            canIecHbr = probeIecCarrier(192_000, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND),
        )
    }

    private fun baseline() =
        AudioRouteCapabilities(
            route = ROUTE_OTHER,
            passthroughCodecs = emptySet(),
            maxPcmChannels = 2,
            canIecLow = false,
            canIecMid = false,
            canIecHbr = false,
        )

    private fun supportsEncoding(
        audioManager: AudioManager,
        encoding: Int,
        reportedEncodings: Set<Int>,
    ): Boolean {
        if (encoding in reportedEncodings) return true
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                candidateFormats(encoding).any { format ->
                    runCatching {
                        AudioManager.getDirectPlaybackSupport(format, mediaAttributes) !=
                            AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
                    }.getOrDefault(false)
                }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                candidateFormats(encoding).any { format ->
                    runCatching { AudioTrack.isDirectPlaybackSupported(format, mediaAttributes) }
                        .getOrDefault(false)
                }
            else -> false
        }
    }

    private fun candidateFormats(encoding: Int): List<AudioFormat> {
        val masks = intArrayOf(
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
            AudioFormat.CHANNEL_OUT_5POINT1,
            AudioFormat.CHANNEL_OUT_STEREO,
        )
        val rates = intArrayOf(48_000, 96_000, 192_000)
        return buildList {
            for (mask in masks) {
                for (rate in rates) {
                    runCatching {
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setChannelMask(mask)
                            .setSampleRate(rate)
                            .build()
                    }.getOrNull()?.let(::add)
                }
            }
        }
    }

    private fun probeIecCarrier(sampleRate: Int, channelMask: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val format =
            runCatching {
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_IEC61937)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            }.getOrNull() ?: return false

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                runCatching {
                    AudioManager.getDirectPlaybackSupport(format, mediaAttributes) !=
                        AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
                }.getOrDefault(false)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                runCatching { AudioTrack.isDirectPlaybackSupported(format, mediaAttributes) }
                    .getOrDefault(false)
            channelMask == AudioFormat.CHANNEL_OUT_STEREO ->
                runCatching {
                    AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_IEC61937) > 0
                }.getOrDefault(false)
            else -> false
        }
    }

    private fun resolveActiveMediaDevices(audioManager: AudioManager): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
        return runCatching {
            audioManager.getAudioDevicesForAttributes(mediaAttributes).toList()
        }.getOrDefault(emptyList())
    }

    private fun isBitstreamOutputDevice(device: AudioDeviceInfo): Boolean =
        when (device.type) {
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            -> true
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                device.type == AudioDeviceInfo.TYPE_HDMI_EARC
        }

    private fun classifyRoute(types: Set<Int>): String {
        if (types.any(::isBluetoothType)) return ROUTE_BLUETOOTH
        if (types.contains(AudioDeviceInfo.TYPE_WIRED_HEADPHONES) ||
            types.contains(AudioDeviceInfo.TYPE_WIRED_HEADSET) ||
            types.contains(AudioDeviceInfo.TYPE_USB_HEADSET)
        ) return ROUTE_HEADPHONES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && types.contains(AudioDeviceInfo.TYPE_HDMI_EARC)) {
            return ROUTE_EARC
        }
        if (types.contains(AudioDeviceInfo.TYPE_HDMI_ARC)) return ROUTE_ARC
        if (types.contains(AudioDeviceInfo.TYPE_HDMI) || types.contains(AudioDeviceInfo.TYPE_LINE_DIGITAL)) {
            return ROUTE_HDMI
        }
        if (types.contains(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) ||
            types.contains(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        ) return ROUTE_SPEAKER
        return ROUTE_OTHER
    }

    private fun detectMaxPcmChannels(devices: List<AudioDeviceInfo>, route: String): Int {
        val reported = devices.flatMap { it.channelCounts.toList() }.maxOrNull() ?: 0
        if (reported > 0) return reported
        return when (route) {
            ROUTE_EARC, ROUTE_HDMI -> 8
            ROUTE_ARC -> 6
            else -> 2
        }
    }

    private fun isBluetoothType(type: Int): Boolean =
        when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            -> true
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
}
