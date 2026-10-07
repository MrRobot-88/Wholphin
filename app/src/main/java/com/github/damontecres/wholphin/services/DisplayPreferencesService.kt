package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.model.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DisplayPreferencesService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
    ) {
        private val mutex = Mutex()

        suspend fun getDisplayPreferences(
            userId: UUID,
            displayPreferencesId: String = DEFAULT_DISPLAY_PREF_ID,
            client: String = DEFAULT_CLIENT,
        ) = api.displayPreferencesApi
            .getDisplayPreferences(
                userId = userId,
                displayPreferencesId = displayPreferencesId,
                client = client,
            ).content

        suspend fun updateDisplayPreferences(
            userId: UUID,
            displayPreferencesId: String = DEFAULT_DISPLAY_PREF_ID,
            client: String = DEFAULT_CLIENT,
            block: MutableMap<String, String?>.() -> Unit,
        ) {
            mutex.withLock {
                val current = getDisplayPreferences(userId, DEFAULT_DISPLAY_PREF_ID)
                val customPrefs =
                    current.customPrefs.toMutableMap().apply {
                        block.invoke(this)
                    }
                api.displayPreferencesApi.updateDisplayPreferences(
                    displayPreferencesId = displayPreferencesId,
                    userId = userId,
                    client = client,
                    data = current.copy(customPrefs = customPrefs),
                )
            }
        }

        companion object {
            const val DEFAULT_DISPLAY_PREF_ID = "default"
            // Performance builds deliberately keep the existing .debug application ID so
            // they must also keep the same Jellyfin display-preferences key. Otherwise an
            // optimized update would look like it lost the user's Home/layout settings.
            val DEFAULT_CLIENT =
                if (BuildConfig.APPLICATION_ID.endsWith(".debug")) "Wholphin (Debug)" else "Wholphin"
        }
    }
