package com.github.damontecres.wholphin.services

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPassthroughPolicyTest {
    @Test
    fun disabledBlocksBitstreamButLeavesOpusForLocalDecode() {
        val policy = AudioPassthroughPolicy(PassthroughMode.DISABLED, emptySet())

        assertNull(policy.sinkFormatFor(format(MimeTypes.AUDIO_TRUEHD)))

        val opus = policy.sinkFormatFor(format(MimeTypes.AUDIO_OPUS))
        assertNotNull(opus)
        assertEquals(MimeTypes.AUDIO_OPUS, opus!!.sampleMimeType)
    }

    @Test
    fun manualOnlyAllowsSelectedBitstreams() {
        val policy = AudioPassthroughPolicy(PassthroughMode.MANUAL, setOf("eac3"))

        assertNotNull(policy.sinkFormatFor(format(MimeTypes.AUDIO_E_AC3)))
        assertNull(policy.sinkFormatFor(format(MimeTypes.AUDIO_TRUEHD)))
        assertNull(policy.sinkFormatFor(format(MimeTypes.AUDIO_AC3)))
    }

    @Test
    fun manualDtsCanUseDtsCoreForDtsHd() {
        val policy = AudioPassthroughPolicy(PassthroughMode.MANUAL, setOf("dts"))
        val input =
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                .setChannelCount(8)
                .setSampleRate(96_000)
                .build()

        val output = policy.sinkFormatFor(input)
        assertNotNull(output)
        assertEquals(MimeTypes.AUDIO_DTS, output!!.sampleMimeType)
        assertEquals(6, output.channelCount)
        assertEquals(48_000, output.sampleRate)
    }

    @Test
    fun autoLeavesTrueHdAndOpusAvailable() {
        val policy = AudioPassthroughPolicy(PassthroughMode.AUTO, emptySet())

        assertEquals(
            MimeTypes.AUDIO_TRUEHD,
            policy.sinkFormatFor(format(MimeTypes.AUDIO_TRUEHD))!!.sampleMimeType,
        )
        assertEquals(
            MimeTypes.AUDIO_OPUS,
            policy.sinkFormatFor(format(MimeTypes.AUDIO_OPUS))!!.sampleMimeType,
        )
    }

    private fun format(mimeType: String): Format =
        Format.Builder()
            .setSampleMimeType(mimeType)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
}
