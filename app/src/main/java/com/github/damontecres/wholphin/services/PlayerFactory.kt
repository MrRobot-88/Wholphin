@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import com.github.damontecres.wholphin.mpv.MpvPlayer
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AssPlaybackMode
import com.github.damontecres.wholphin.preferences.Av1DecoderMode
import com.github.damontecres.wholphin.preferences.MediaExtensionStatus
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.get
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.util.BitstreamFilteringCodecAdapterFactory
import com.github.damontecres.wholphin.util.Hdr10PlusMaskingFilter
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructs a [Player] instance for video playback
 */
@Singleton
class PlayerFactory
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:AuthOkHttpClient private val authOkHttpClient: OkHttpClient,
    ) {
        @Volatile
        var currentPlayer: Player? = null
            private set

        suspend fun createVideoPlayer(
            backend: PlayerBackend,
            appPreferences: AppPreferences,
        ): PlayerCreation {
            val prefs = appPreferences.playbackPreferences
            withContext(WholphinDispatchers.Main) {
                if (currentPlayer?.isReleased == false) {
                    Timber.w("Player was not released before trying to create a new one!")
                    currentPlayer?.release()
                }
            }
            var assHandler: AssHandler? = null
            val newPlayer =
                when (backend) {
                    PlayerBackend.PREFER_MPV,
                    PlayerBackend.MPV,
                    -> {
                        val enableHardwareDecoding = prefs.mpvOptions.enableHardwareDecoding
                        val useGpuNext = prefs.mpvOptions.useGpuNext
                        MpvPlayer(context, enableHardwareDecoding, useGpuNext)
                    }

                    PlayerBackend.EXO_PLAYER,
                    PlayerBackend.UNRECOGNIZED,
                    -> {
                        val extensions = prefs.overrides.mediaExtensionsEnabled
                        val useLibAss =
                            prefs.overrides.assPlaybackMode == AssPlaybackMode.ASS_LIBASS
                        val av1DecoderMode = prefs.overrides.av1DecoderMode
                        val audioPassthroughPolicy =
                            AudioPassthroughPolicy.fromPreferences(
                                prefs.overrides.audioPassthroughMode,
                                prefs.overrides.audioPassthroughCodecsList,
                            )
                        val preferDolbyVision =
                            appPreferences.experimentalPreferences
                                .get { preferDolbyVisionOverHdr10Plus } ?: false
                        Timber.v(
                            "extensions=%s, assPlaybackMode=%s",
                            extensions,
                            prefs.overrides.assPlaybackMode,
                        )
                        val rendererMode =
                            when (extensions) {
                                MediaExtensionStatus.MES_FALLBACK -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                                MediaExtensionStatus.MES_PREFERRED -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                                MediaExtensionStatus.MES_DISABLED -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                            }
                        val dataSourceFactory = DefaultDataSource.Factory(context)
                        val extractorsFactory = createExtractorsFactory()
                        var renderersFactory: RenderersFactory =
                            WholphinRenderersFactory(
                                context = context,
                                av1DecoderMode = av1DecoderMode,
                                preferDolbyVisionOverHdr10Plus = preferDolbyVision,
                                audioPassthroughPolicy = audioPassthroughPolicy,
                            ).setEnableDecoderFallback(true)
                                .setExtensionRendererMode(rendererMode)

                        val mediaSourceFactory =
                            if (useLibAss) {
                                val renderType =
                                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                        AssRenderType.OVERLAY_CANVAS
                                    } else {
                                        AssRenderType.OVERLAY_OPEN_GL
                                    }
                                assHandler = AssHandler(renderType)
                                val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
                                renderersFactory = AssRenderersFactory(assHandler, renderersFactory)
                                DefaultMediaSourceFactory(
                                    dataSourceFactory,
                                    extractorsFactory.withAssMkvSupport(
                                        assSubtitleParserFactory,
                                        assHandler,
                                    ),
                                ).setSubtitleParserFactory(assSubtitleParserFactory)
                            } else {
                                DefaultMediaSourceFactory(
                                    dataSourceFactory,
                                    extractorsFactory,
                                )
                            }
                        val disableAudioOffload =
                            appPreferences.experimentalPreferences.get { disableAudioOffload } ?: false
                        val tunneling =
                            appPreferences.experimentalPreferences.get { videoTunnelingEnabled }
                        val trackSelector =
                            createTrackSelector(
                                tunneling = tunneling,
                                disableAudioOffload = disableAudioOffload,
                            )

                        ExoPlayer
                            .Builder(context)
                            .setMediaSourceFactory(mediaSourceFactory)
                            .setRenderersFactory(renderersFactory)
                            .setTrackSelector(trackSelector)
                            .build()
                            .apply {
                                assHandler?.init(this)
                                withContext(WholphinDispatchers.Main) {
                                    setAudioAttributes(
                                        AudioAttributes
                                            .Builder()
                                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                            .build(),
                                        false,
                                    )
                                }
                            }
                    }

                    PlayerBackend.EXTERNAL_PLAYER -> {
                        throw IllegalArgumentException("Cannot create a player for external playback")
                    }
                }
            currentPlayer = newPlayer
            return PlayerCreation(newPlayer, assHandler)
        }

        fun createAudioPlayer(
            disableAudioOffload: Boolean,
            extensions: MediaExtensionStatus = MediaExtensionStatus.MES_FALLBACK,
        ): ExoPlayer {
            val rendererMode =
                when (extensions) {
                    MediaExtensionStatus.MES_FALLBACK -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    MediaExtensionStatus.MES_PREFERRED -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    MediaExtensionStatus.MES_DISABLED -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            val extractorsFactory = createExtractorsFactory()
            val renderersFactory: RenderersFactory =
                WholphinRenderersFactory(
                    context = context,
                    av1DecoderMode = Av1DecoderMode.AV1_HARDWARE,
                ).setEnableDecoderFallback(true)
                    .setExtensionRendererMode(rendererMode)
            val mediaSourceFactory =
                DefaultMediaSourceFactory(
                    OkHttpDataSource.Factory(authOkHttpClient),
                    extractorsFactory,
                )
            val trackSelector = createTrackSelector(disableAudioOffload = disableAudioOffload)
            return ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .build()
                .also {
                    it.setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true,
                    )
                }
        }

        private fun createExtractorsFactory() =
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)

        private fun createTrackSelector(
            tunneling: Boolean? = null,
            disableAudioOffload: Boolean = false,
        ) = DefaultTrackSelector(context).apply {
            val offloadMode =
                if (disableAudioOffload) {
                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                } else {
                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                }
            setParameters(
                buildUponParameters()
                    .apply {
                        tunneling?.let { setTunnelingEnabled(tunneling) }
                    }.setAudioOffloadPreferences(
                        AudioOffloadPreferences
                            .Builder()
                            .setAudioOffloadMode(offloadMode)
                            .build(),
                    ),
            )
        }

        fun createMediaSession(player: Player) =
            MediaSession
                .Builder(context, player)
                .build()
    }

val Player.isReleased: Boolean
    get() {
        return when (this) {
            is ExoPlayer -> isReleased
            is MpvPlayer -> isReleased
            else -> throw IllegalStateException("Unknown Player type: ${this::class.qualifiedName}")
        }
    }

data class PlayerCreation(
    val player: Player,
    val assHandler: AssHandler? = null,
)

// Code is adapted from https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java#L436
class WholphinRenderersFactory(
    context: Context,
    private val av1DecoderMode: Av1DecoderMode,
    private val preferDolbyVisionOverHdr10Plus: Boolean = false,
    private val audioPassthroughPolicy: AudioPassthroughPolicy =
        AudioPassthroughPolicy(PassthroughMode.AUTO, emptySet()),
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        val sink = super.buildAudioSink(
            context,
            enableFloatOutput,
            enableAudioOutputPlaybackParams,
        ) ?: return null
        return if (audioPassthroughPolicy.mode == PassthroughMode.AUTO) {
            sink
        } else {
            PassthroughPolicyAudioSink(sink, audioPassthroughPolicy)
        }
    }
    @OptIn(ExperimentalApi::class)
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val videoCodecAdapterFactory =
            if (preferDolbyVisionOverHdr10Plus) {
                BitstreamFilteringCodecAdapterFactory(
                    codecAdapterFactory,
                    listOf(Hdr10PlusMaskingFilter()),
                )
            } else {
                codecAdapterFactory
            }

        val hardwareAv1 = hasHardwareAv1Decoder()
        val dav1dAvailable = isDav1dRendererAvailable()
        val useSoftwareAv1 =
            dav1dAvailable &&
                when (av1DecoderMode) {
                    Av1DecoderMode.AV1_SOFTWARE -> true
                    Av1DecoderMode.AV1_AUTO -> !hardwareAv1
                    else -> false
                }
        val effectiveSelector =
            if (av1DecoderMode == Av1DecoderMode.AV1_HARDWARE) {
                HardwareOnlyAv1CodecSelector(mediaCodecSelector)
            } else {
                mediaCodecSelector
            }

        Timber.i(
            "AV1 mode=%s hardware=%s dav1d=%s softwareSelected=%s",
            av1DecoderMode,
            hardwareAv1,
            dav1dAvailable,
            useSoftwareAv1,
        )

        if (useSoftwareAv1) {
            createDav1dRenderer(
                allowedVideoJoiningTimeMs,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            )?.let {
                out.add(it)
                Timber.i("Loaded Libdav1dVideoRenderer as preferred AV1 renderer.")
            }
        }

        var videoRendererBuilder =
            MediaCodecVideoRenderer
                .Builder(context)
                .setCodecAdapterFactory(videoCodecAdapterFactory)
                .setMediaCodecSelector(effectiveSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                .experimentalSetParseAv1SampleDependencies(false)
                .experimentalSetLateThresholdToDropDecoderInputUs(C.TIME_UNSET)
        if (Build.VERSION.SDK_INT >= 34) {
            videoRendererBuilder =
                videoRendererBuilder.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(false)
        }
        out.add(videoRendererBuilder.build())
    }
}

private class HardwareOnlyAv1CodecSelector(
    private val delegate: MediaCodecSelector,
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ) = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder).let { infos ->
        if (!mimeType.equals(MimeTypes.VIDEO_AV1, ignoreCase = true)) infos
        else infos.filterNot { isSoftwareCodecName(it.name) }
    }
}

private fun hasHardwareAv1Decoder(): Boolean =
    runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(MimeTypes.VIDEO_AV1, ignoreCase = true) } &&
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !isSoftwareCodecName(info.name)
                }
        }
    }.getOrDefault(false)

private fun isSoftwareCodecName(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("omx.google.") ||
        n.startsWith("c2.android.") ||
        n.startsWith("c2.google.") ||
        n.contains("ffmpeg") ||
        n.contains("software") ||
        n.endsWith(".sw.decoder")
}

private fun isDav1dRendererAvailable(): Boolean =
    runCatching {
        Class.forName("androidx.media3.decoder.av1.Libdav1dVideoRenderer")
        true
    }.getOrDefault(false)

private fun createDav1dRenderer(
    allowedVideoJoiningTimeMs: Long,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedFramesToNotify: Int,
): Renderer? =
    runCatching {
        val clazz = Class.forName("androidx.media3.decoder.av1.Libdav1dVideoRenderer")
        val constructor = clazz.getConstructor(
            Long::class.javaPrimitiveType,
            Handler::class.java,
            VideoRendererEventListener::class.java,
            Int::class.javaPrimitiveType,
        )
        constructor.newInstance(
            allowedVideoJoiningTimeMs,
            eventHandler,
            eventListener,
            maxDroppedFramesToNotify,
        ) as Renderer
    }.onFailure {
        Timber.w(it, "Unable to instantiate dav1d AV1 renderer")
    }.getOrNull()