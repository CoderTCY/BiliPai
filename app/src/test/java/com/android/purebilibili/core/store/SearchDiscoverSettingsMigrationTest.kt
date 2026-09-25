package com.android.purebilibili.core.store

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchDiscoverSettingsMigrationTest {
    @Test
    fun oldPersonalizationOptOutDoesNotHideSearchDiscover() {
        val saved = mutablePreferencesOf(booleanPreferencesKey("search_suggestions_enabled") to false)

        assertTrue(SettingsManager.resolveSearchDiscoverSectionEnabled(saved))
    }

    @Test
    fun existingVisibilitySettingRemainsAuthoritative() {
        val oldPersonalization = booleanPreferencesKey("search_suggestions_enabled")
        val visibility = booleanPreferencesKey("search_discover_section_enabled")

        assertFalse(SettingsManager.resolveSearchDiscoverSectionEnabled(
            mutablePreferencesOf(oldPersonalization to true, visibility to false)
        ))
        assertTrue(SettingsManager.resolveSearchDiscoverSectionEnabled(
            mutablePreferencesOf(oldPersonalization to false, visibility to true)
        ))
    }
}
