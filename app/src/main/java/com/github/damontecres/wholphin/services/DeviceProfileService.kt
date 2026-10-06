package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.media3.common.MimeTypes
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AssPlaybackMode
import com.github.damontecres.wholphin.preferences.Av1DecoderMode
import com.github.damontecres.wholphin.preferences.DoviP7Mode
import com.github.damontecres.wholphin.preferences.ExperimentalPreferences
import com.github.damontecres.wholphin.preferences.PlaybackOverrides
import com.github.damontecres.wholphin.preferences.VideoDecoderMode
import com.github.damontecres.wholphin.preferences.enabled
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.profile.MediaCodecCapabilitiesTest
import com.github.damontecres.wholphin.util.profile.createDeviceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.DeviceProfile
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and caches the device direct play/transcoding profile sent to the server for ExoPlayer
 */
@Singleton
class DeviceProfileService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        val mediaCodecCapabilitiesTest by lazy {
            // Created lazily below on another thread since it cn take time
            MediaCodecCapabilitiesTest(context)
        }
        private val mutex = Mutex()

        private var configuration: DeviceProfileConfiguration? = null
        private var deviceProfile: DeviceProfile? = null

        suspend fun getOrCreateDeviceProfile(
            appPrefs: AppPreferences,
            serverVersion: ServerVersion?,
        ): DeviceProfile =
            withContext(WholphinDispatchers.Default) {
                val prefs = appPrefs.playbackPreferences
                mutex.withLock {
                    val newConfig =
                        DeviceProfileConfiguration(
                            maxBitrate = prefs.maxBitrate.toInt(),
                            overrides = prefs.overrides,
                            experimental = appPrefs.experimentalPreferences,
                            jellyfinTenEleven =
                                serverVersion != null && serverVersion >= ServerVersion(10, 11, 0),
                        )
                    if (deviceProfile == null || this@DeviceProfileService.configuration != newConfig) {
                        this@DeviceProfileService.configuration = newConfig
                        val h264DirectPlay =
                            when (newConfig.overrides.h264DecoderMode) {
                                VideoDecoderMode.VIDEO_DECODER_HARDWARE ->
                                    mediaCodecCapabilitiesTest.supportsHardwareDecoder(MimeTypes.VIDEO_H264)
                                VideoDecoderMode.VIDEO_DECODER_SOFTWARE ->
                                    mediaCodecCapabilitiesTest.supportsSoftwareDecoder(MimeTypes.VIDEO_H264)
                                else -> mediaCodecCapabilitiesTest.supportsAVC()
                            }
                        val h265DirectPlay =
                            when (newConfig.overrides.h265DecoderMode) {
                                VideoDecoderMode.VIDEO_DECODER_HARDWARE ->
                                    mediaCodecCapabilitiesTest.supportsHardwareDecoder(MimeTypes.VIDEO_H265)
                                VideoDecoderMode.VIDEO_DECODER_SOFTWARE ->
                                    mediaCodecCapabilitiesTest.supportsSoftwareDecoder(MimeTypes.VIDEO_H265)
                                else -> mediaCodecCapabilitiesTest.supportsHevc()
                            }
                        val dav1dAvailable =
                            runCatching {
                                Class.forName("androidx.media3.decoder.av1.Libdav1dVideoRenderer")
                                true
                            }.getOrDefault(false)
                        val androidSoftwareAv1 = mediaCodecCapabilitiesTest.supportsSoftwareDecoder(MimeTypes.VIDEO_AV1)
                        val hardwareAv1 = mediaCodecCapabilitiesTest.supportsHardwareAV1()
                        val softwareAv1DirectPlay =
                            when (newConfig.overrides.av1DecoderMode) {
                                Av1DecoderMode.AV1_SOFTWARE -> dav1dAvailable || androidSoftwareAv1
                                Av1DecoderMode.AV1_AUTO -> !hardwareAv1 && (dav1dAvailable || androidSoftwareAv1)
                                else -> false
                            }
                        val av1DirectPlay =
                            when (newConfig.overrides.av1DecoderMode) {
                                Av1DecoderMode.AV1_HARDWARE -> hardwareAv1
                                Av1DecoderMode.AV1_SOFTWARE -> softwareAv1DirectPlay
                                else -> hardwareAv1 || dav1dAvailable || androidSoftwareAv1
                            }
                        Timber.i(
                            "Device profile video policy H264=%s H265=%s AV1=%s softwareAV1=%s dav1d=%s",
                            h264DirectPlay,
                            h265DirectPlay,
                            av1DirectPlay,
                            softwareAv1DirectPlay,
                            dav1dAvailable,
                        )
                        this@DeviceProfileService.deviceProfile =
                            createDeviceProfile(
                                mediaTest = mediaCodecCapabilitiesTest,
                                maxBitrate = newConfig.maxBitrate,
                                isAC3Enabled = newConfig.overrides.ac3Supported,
                                downMixAudio = newConfig.overrides.downmixStereo,
                                assDirectPlay = newConfig.overrides.assPlaybackMode != AssPlaybackMode.ASS_TRANSCODE,
                                pgsDirectPlay = newConfig.overrides.directPlayPgs,
                                dolbyVisionELDirectPlay = newConfig.overrides.doviP7Mode != DoviP7Mode.DOVI_P7_OFF,
                                h264DirectPlay = h264DirectPlay,
                                h265DirectPlay = h265DirectPlay,
                                av1DirectPlay = av1DirectPlay,
                                softwareAv1DirectPlay = softwareAv1DirectPlay,
                                preferAc3ForSurround = appPrefs.experimentalPreferences.enabled { preferAc3Surround },
                                jellyfinTenEleven = newConfig.jellyfinTenEleven,
                                maxResolution = newConfig.overrides.maxResolution,
                            )
                    }
                    this@DeviceProfileService.deviceProfile!!
                }
            }
    }

/**
 * The configuration used in [createDeviceProfile]
 */
data class DeviceProfileConfiguration(
    val maxBitrate: Int,
    val overrides: PlaybackOverrides,
    val experimental: ExperimentalPreferences,
    val jellyfinTenEleven: Boolean,
)
