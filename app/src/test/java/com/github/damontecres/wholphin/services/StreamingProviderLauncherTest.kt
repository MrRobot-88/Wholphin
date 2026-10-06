package com.github.damontecres.wholphin.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingProviderLauncherTest {
    @Test
    fun resolvesNetflixAndroidTvPackage() {
        assertEquals(
            listOf("com.netflix.ninja"),
            streamingProviderPackages("Netflix"),
        )
    }

    @Test
    fun resolvesPrimeVideoAndroidTvAndAlternatePackage() {
        assertEquals(
            listOf(
                "com.amazon.amazonvideo.livingroom",
                "com.amazon.avod.thirdpartyclient",
            ),
            streamingProviderPackages("Amazon Prime Video"),
        )
    }

    @Test
    fun amazonChannelProvidersOpenPrimeVideo() {
        assertEquals(
            listOf(
                "com.amazon.amazonvideo.livingroom",
                "com.amazon.avod.thirdpartyclient",
            ),
            streamingProviderPackages("Max Amazon Channel"),
        )
    }

    @Test
    fun resolvesDisneyPlusNameWithSymbol() {
        assertEquals(
            listOf("com.disney.disneyplus"),
            streamingProviderPackages("Disney+"),
        )
    }

    @Test
    fun resolvesBothCurrentHboMaxPackageVariants() {
        assertEquals(
            listOf("com.wbd.stream", "com.wbd.hbomax"),
            streamingProviderPackages("HBO Max"),
        )
    }

    @Test
    fun resolvesNordicProviderPackages() {
        assertEquals(listOf("com.viaplay.android"), streamingProviderPackages("Viaplay"))
        assertEquals(listOf("se.svt.android.svtplay"), streamingProviderPackages("SVT Play"))
    }

    @Test
    fun unknownProviderFallsBackToWebOnly() {
        assertTrue(streamingProviderPackages("Some Future Service").isEmpty())
    }
}
