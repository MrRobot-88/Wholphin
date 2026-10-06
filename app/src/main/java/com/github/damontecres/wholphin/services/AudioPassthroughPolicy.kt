package com.github.damontecres.wholphin.services

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.github.damontecres.wholphin.preferences.AudioPassthroughCodec
import com.github.damontecres.wholphin.preferences.AudioPassthroughMode
import java.nio.ByteBuffer

/*
 * Adapted from Moonfin-Client/Moonfin-Core (GPL-2.0),
 * AudioPassthroughPolicy.kt, donor commit
 * 36ed696f1d02ba240b459e953d98965ae514648c.
 *
 * The policy can only NARROW Media3/Android route capabilities. It never
 * advertises passthrough support that the active HDMI/ARC/eARC route lacks.
 */
enum class PassthroughMode { DISABLED, AUTO, MANUAL }

data class AudioPassthroughPolicy(
    val mode: PassthroughMode,
    val allowedCodecs: Set<String>,
) {
    private fun allowsBitstream(mimeType: String?): Boolean {
        val codec = codecKeyForMime(mimeType) ?: return true
        return when (mode) {
            PassthroughMode.AUTO -> true
            PassthroughMode.DISABLED -> false
            PassthroughMode.MANUAL -> codec in allowedCodecs
        }
    }

    fun sinkFormatFor(format: Format): Format? {
        if (allowsBitstream(format.sampleMimeType)) return format
        if (format.sampleMimeType != MimeTypes.AUDIO_DTS_HD ||
            !allowsBitstream(MimeTypes.AUDIO_DTS)
        ) {
            return null
        }
        return format.buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_DTS)
            .setChannelCount(minOf(format.channelCount, DTS_CORE_MAX_CHANNELS))
            .setSampleRate(dtsCoreSampleRate(format.sampleRate))
            .build()
    }

    companion object {
        private const val DTS_CORE_MAX_CHANNELS = 6

        fun fromPreferences(
            mode: AudioPassthroughMode,
            codecs: List<AudioPassthroughCodec>,
        ): AudioPassthroughPolicy =
            AudioPassthroughPolicy(
                mode = when (mode) {
                    AudioPassthroughMode.AUDIO_PT_DISABLED -> PassthroughMode.DISABLED
                    AudioPassthroughMode.AUDIO_PT_MANUAL -> PassthroughMode.MANUAL
                    else -> PassthroughMode.AUTO
                },
                allowedCodecs =
                    (if (codecs.isEmpty()) {
                        AudioPassthroughCodec.entries.filterNot { it == AudioPassthroughCodec.UNRECOGNIZED }
                    } else {
                        codecs
                    }).mapNotNull {
                        when (it) {
                            AudioPassthroughCodec.AUDIO_PT_AC3 -> "ac3"
                            AudioPassthroughCodec.AUDIO_PT_EAC3 -> "eac3"
                            AudioPassthroughCodec.AUDIO_PT_DTS -> "dts"
                            AudioPassthroughCodec.AUDIO_PT_DTSHD -> "dtshd"
                            AudioPassthroughCodec.AUDIO_PT_TRUEHD -> "truehd"
                            else -> null
                        }
                    }.toSet(),
            )

        private fun dtsCoreSampleRate(sampleRate: Int): Int = when {
            sampleRate <= 48_000 -> sampleRate
            sampleRate % 48_000 == 0 -> 48_000
            sampleRate % 44_100 == 0 -> 44_100
            else -> sampleRate
        }

        internal fun codecKeyForMime(mimeType: String?): String? = when (mimeType) {
            MimeTypes.AUDIO_AC3 -> "ac3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "dts"
            MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> "dtshd"
            MimeTypes.AUDIO_TRUEHD -> "truehd"
            MimeTypes.AUDIO_AC4 -> "ac4"
            else -> null
        }
    }
}

@UnstableApi
class PassthroughPolicyAudioSink(
    delegate: AudioSink,
    private val policy: AudioPassthroughPolicy,
) : ForwardingAudioSink(delegate) {
    private val dtsCore = DtsCoreExtractor()
    private var sendDtsCore = false
    private var pendingBuffer: ByteBuffer? = null
    private var pendingCore: ByteBuffer? = null

    override fun supportsFormat(format: Format): Boolean {
        val sinkFormat = policy.sinkFormatFor(format) ?: return false
        return super.supportsFormat(sinkFormat)
    }

    override fun getFormatSupport(format: Format): Int {
        val sinkFormat = policy.sinkFormatFor(format)
            ?: return AudioSink.SINK_FORMAT_UNSUPPORTED
        return super.getFormatSupport(sinkFormat)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        val sinkFormat = policy.sinkFormatFor(format)
            ?: return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        return super.getFormatOffloadSupport(sinkFormat)
    }

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val sinkFormat = policy.sinkFormatFor(config.format) ?: config.format
        sendDtsCore = sinkFormat.sampleMimeType != config.format.sampleMimeType
        clearPending()
        val sinkConfig =
            AudioSink.AudioSinkConfig.Builder(sinkFormat)
                .setPreferredBufferSizeOverride(config.preferredBufferSizeOverride)
                .setOutputChannelMapping(config.outputChannelMapping)
                .setTimeline(config.timeline)
                .setMediaPeriodId(config.mediaPeriodId)
                .build()
        super.configure(sinkConfig)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!sendDtsCore) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        var core = pendingCore
        if (core == null || pendingBuffer !== buffer) {
            core = dtsCore.extract(buffer) ?: buffer
            pendingBuffer = buffer
            pendingCore = core
        }
        val handled = super.handleBuffer(core, presentationTimeUs, encodedAccessUnitCount)
        if (handled) {
            buffer.position(buffer.limit())
            clearPending()
        }
        return handled
    }

    override fun flush() {
        clearPending()
        super.flush()
    }

    override fun reset() {
        clearPending()
        super.reset()
    }

    private fun clearPending() {
        pendingBuffer = null
        pendingCore = null
    }
}
