package nz.mckenzie.sprayday.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nz.mckenzie.sprayday.BuildConfig

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "spray_day_settings")

/**
 * User-editable settings.
 *
 * The LINZ Basemaps key is stored here rather than being relied on from
 * BuildConfig, because standard access keys **expire every 90 days** - an app
 * that only had a baked-in key would stop showing imagery with no way to fix it.
 */
class SettingsRepository(private val context: Context) {

    private val linzKeyPref = stringPreferencesKey("linz_api_key")
    private val remindersPref = booleanPreferencesKey("reminders_enabled")

    /** What the user has entered, empty when unset. */
    val storedLinzApiKey: Flow<String> =
        context.settingsDataStore.data.map { it[linzKeyPref].orEmpty() }

    /** Stored key if the user has set one, otherwise the build-time default. */
    val linzApiKey: Flow<String> = storedLinzApiKey.map { stored ->
        if (stored.isBlank()) BuildConfig.LINZ_API_KEY else stored
    }

    suspend fun setLinzApiKey(value: String) {
        context.settingsDataStore.edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) prefs.remove(linzKeyPref) else prefs[linzKeyPref] = trimmed
        }
    }

    /** True when a key was supplied at build time (local.properties or CI secret). */
    fun hasBuildTimeKey(): Boolean = BuildConfig.LINZ_API_KEY.isNotBlank()

    /**
     * Whether the app should tell the operator when tracks come due.
     *
     * On by default, because that is the point of a spray calendar - but nothing is
     * posted until Android has been asked for permission, which is its own consent step.
     */
    val remindersEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[remindersPref] ?: true
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[remindersPref] = enabled }
    }
}
