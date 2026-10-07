package com.github.damontecres.wholphin.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingProviderPreferencesTest {
    @Test
    fun providerIdsParseDeduplicateSortAndIgnoreInvalidValues() {
        val prefs = JellyfinUserPreferences(streamingProviderIds = " 337,8,foo,8,-1,9, ")

        assertEquals(setOf(8, 9, 337), prefs.streamingProviderIdSet)
    }

    @Test
    fun withProviderIdsStoresStableCsv() {
        val prefs = JellyfinUserPreferences().withStreamingProviderIds(listOf(337, 8, 9, 8, -1))

        assertEquals("8,9,337", prefs.streamingProviderIds)
    }

    @Test
    fun explicitRegionWinsAndIsNormalized() {
        val prefs = JellyfinUserPreferences(streamingRegion = " se ")

        assertEquals("SE", prefs.resolvedStreamingRegion("DK"))
    }

    @Test
    fun blankRegionUsesFallbackAndInvalidFallbackUsesSweden() {
        assertEquals("DK", JellyfinUserPreferences().resolvedStreamingRegion("dk"))
        assertEquals("SE", JellyfinUserPreferences().resolvedStreamingRegion(""))
    }
}
