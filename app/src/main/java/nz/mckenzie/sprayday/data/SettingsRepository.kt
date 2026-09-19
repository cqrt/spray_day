package nz.mckenzie.sprayday.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.domain.asset.AssetLayer
import nz.mckenzie.sprayday.domain.backup.BackupDestination
import nz.mckenzie.sprayday.domain.backup.BackupSettingsRecord
import nz.mckenzie.sprayday.domain.backup.BackupSwitches
import nz.mckenzie.sprayday.domain.tiles.Basemap

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "spray_day_settings")

/**
 * User-editable settings.
 *
 * The LINZ Basemaps key is stored here rather than being relied on from
 * BuildConfig, because standard access keys **expire every 90 days** - an app
 * that only had a baked-in key would stop showing imagery with no way to fix it.
 *
 * It also reads and writes the switches a backup carries, because this is where they
 * already live: [BackupSwitches] is the narrow view of them that the backup code is given,
 * so nothing that writes a backup can reach the token or the key by accident.
 */
class SettingsRepository(private val context: Context) : BackupSwitches {

    private val linzKeyPref = stringPreferencesKey("linz_api_key")
    private val basemapPref = stringPreferencesKey("basemap")
    private val hiddenMapLayersPref = stringSetPreferencesKey("hidden_map_layers")
    private val remindersPref = booleanPreferencesKey("reminders_enabled")
    private val updateChecksPref = booleanPreferencesKey("update_checks_enabled")
    private val lastNotifiedUpdatePref = stringPreferencesKey("last_notified_update")
    private val backupDestinationPref = stringPreferencesKey("backup_destination")
    private val backupRepoPref = stringPreferencesKey("backup_repo")
    private val backupTokenPref = stringPreferencesKey("backup_token")
    private val backupFileUriPref = stringPreferencesKey("backup_file_uri")
    private val backupFileNamePref = stringPreferencesKey("backup_file_name")
    private val backUpAutomaticallyPref = booleanPreferencesKey("backup_automatically")
    private val lastOffsiteBackupAtPref = longPreferencesKey("last_offsite_backup_at")
    private val lastOffsiteFailureNotifiedAtPref = longPreferencesKey("last_offsite_failure_notified_at")

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
     * Which map the operator draws under the work.
     *
     * Aerial imagery by default, which is what every existing install has been looking at: a new
     * basemap is an addition to the app, not a change to somebody's maps mid-season. The list of
     * what can be chosen, and everything that differs between the choices, is in
     * [nz.mckenzie.sprayday.domain.tiles.Basemap].
     */
    val basemap: Flow<Basemap> = context.settingsDataStore.data.map { prefs ->
        Basemap.fromStorage(prefs[basemapPref])
    }

    suspend fun setBasemap(value: Basemap) {
        context.settingsDataStore.edit { prefs -> prefs[basemapPref] = value.id }
    }

    /**
     * The layers of the work the map is not drawing.
     *
     * What is stored is what is **hidden**, so the default is everything and a layer added in a
     * later build arrives visible - see [AssetLayer]. An empty set is written as no value at all:
     * that is the same thing to read, and it keeps a fresh install's file to what has actually
     * been chosen.
     */
    val hiddenMapLayers: Flow<Set<AssetLayer>> = context.settingsDataStore.data.map { prefs ->
        AssetLayer.hiddenIn(prefs[hiddenMapLayersPref])
    }

    suspend fun setHiddenMapLayers(layers: Set<AssetLayer>) {
        context.settingsDataStore.edit { prefs ->
            val ids = layers.mapTo(mutableSetOf()) { it.id }
            if (ids.isEmpty()) prefs.remove(hiddenMapLayersPref) else prefs[hiddenMapLayersPref] = ids
        }
    }

    /**
     * Hides or shows one layer, in a single write.
     *
     * The set is read and written inside the same edit, as one step, rather than from a value the
     * caller is holding: two taps in a row - which is exactly what a screen of switches is - would
     * otherwise both read what was there before either of them, and the second tap would undo the
     * first. DataStore serialises edits, so reading the truth inside one is what makes the two
     * accumulate.
     */
    suspend fun hideMapLayer(layer: AssetLayer, hidden: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val now = AssetLayer.hiddenIn(prefs[hiddenMapLayersPref])
            val ids = (if (hidden) now + layer else now - layer).mapTo(mutableSetOf()) { it.id }
            if (ids.isEmpty()) prefs.remove(hiddenMapLayersPref) else prefs[hiddenMapLayersPref] = ids
        }
    }

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

    /**
     * Whether the app should look for a newer version of itself.
     *
     * On by default. This app is not installed from a store, so nothing else will ever
     * mention a new version - without this, the only way to find out would be to go and
     * read the releases page, which nobody does.
     */
    val updateChecksEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[updateChecksPref] ?: true
    }

    suspend fun setUpdateChecksEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[updateChecksPref] = enabled }
    }

    /**
     * The version the operator has already been told about.
     *
     * Remembered so a daily check does not become a daily notification about the same
     * release. Stored rather than inferred, so it survives a restart.
     */
    val lastNotifiedUpdate: Flow<String> =
        context.settingsDataStore.data.map { prefs -> prefs[lastNotifiedUpdatePref].orEmpty() }

    suspend fun setLastNotifiedUpdate(version: String) {
        context.settingsDataStore.edit { prefs -> prefs[lastNotifiedUpdatePref] = version }
    }

    /**
     * Where the off-site copy goes. Off by default.
     *
     * Not on by default because the off-site half needs something from the operator - a
     * file to write to, or a repository and a token - and a setting that looks enabled and
     * quietly does nothing is worse than one that is plainly off.
     */
    val backupDestination: Flow<BackupDestination> = context.settingsDataStore.data.map { prefs ->
        BackupDestination.fromName(prefs[backupDestinationPref])
    }

    suspend fun setBackupDestination(destination: BackupDestination) {
        context.settingsDataStore.edit { prefs -> prefs[backupDestinationPref] = destination.name }
    }

    /** "owner/name". Seeded with the repository this app was built for. */
    val backupRepo: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[backupRepoPref].orEmpty().ifBlank { DEFAULT_BACKUP_REPO }
    }

    suspend fun setBackupRepo(value: String) {
        context.settingsDataStore.edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) prefs.remove(backupRepoPref) else prefs[backupRepoPref] = trimmed
        }
    }

    /**
     * The fine-grained token that may write to [backupRepo], and to nothing else.
     *
     * Stored like the LINZ key, in this app's own preferences, and deliberately never
     * written into a backup: the backup lives in the repository the token can write to, so a
     * file carrying the token would hand over the repository as well.
     */
    val backupToken: Flow<String> =
        context.settingsDataStore.data.map { prefs -> prefs[backupTokenPref].orEmpty() }

    suspend fun setBackupToken(value: String) {
        context.settingsDataStore.edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) prefs.remove(backupTokenPref) else prefs[backupTokenPref] = trimmed
        }
    }

    /** The file the operator chose, as a `Uri` string. Empty when none has been chosen. */
    val backupFileUri: Flow<String> =
        context.settingsDataStore.data.map { prefs -> prefs[backupFileUriPref].orEmpty() }

    /** The chosen file's name, kept so the screen can say where the copy goes. */
    val backupFileName: Flow<String> =
        context.settingsDataStore.data.map { prefs -> prefs[backupFileNamePref].orEmpty() }

    /** Records the file the operator chose, with its name, in one write. */
    suspend fun setBackupFile(uri: String, name: String) {
        context.settingsDataStore.edit { prefs ->
            if (uri.isBlank()) {
                prefs.remove(backupFileUriPref)
                prefs.remove(backupFileNamePref)
            } else {
                prefs[backupFileUriPref] = uri
                prefs[backupFileNamePref] = name
            }
        }
    }

    /**
     * Whether the app should write the copy on its own.
     *
     * Weekly, not daily: a backup is protection against losing a season, and a season does
     * not change by the hour a man is on a tractor. Off by default, and only ever on with a
     * destination chosen, because what it costs the operator is their data allowance.
     */
    val backUpAutomatically: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[backUpAutomaticallyPref] ?: false
    }

    suspend fun setBackUpAutomatically(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[backUpAutomaticallyPref] = enabled }
    }

    /** When a copy was last written, for the line on the screen. Zero when never. */
    val lastOffsiteBackupAt: Flow<Long> =
        context.settingsDataStore.data.map { prefs -> prefs[lastOffsiteBackupAtPref] ?: 0L }

    suspend fun setLastOffsiteBackupAt(epochMs: Long) {
        context.settingsDataStore.edit { prefs -> prefs[lastOffsiteBackupAtPref] = epochMs }
    }

    /**
     * When a scheduled backup last failed, so the notification is news rather than a daily
     * nag. A backup that has been failing for a month should say so, but not thirty times.
     */
    val lastOffsiteFailureNotifiedAt: Flow<Long> =
        context.settingsDataStore.data.map { prefs -> prefs[lastOffsiteFailureNotifiedAtPref] ?: 0L }

    suspend fun setLastOffsiteFailureNotifiedAt(epochMs: Long) {
        context.settingsDataStore.edit { prefs -> prefs[lastOffsiteFailureNotifiedAtPref] = epochMs }
    }

    /**
     * The switches as a backup carries them.
     *
     * What is deliberately left out is as much of a decision as what is in here: the LINZ
     * key and the GitHub token are credentials, and a backup file may be read by whoever
     * finds it.
     */
    override suspend fun read(): BackupSettingsRecord = BackupSettingsRecord(
        remindersEnabled = remindersEnabled.first(),
        updateChecksEnabled = updateChecksEnabled.first(),
        backUpAutomatically = backUpAutomatically.first(),
        destination = backupDestination.first().name
    )

    /**
     * Puts a restored phone's switches back.
     *
     * The file's destination name is honoured only if it names one this build knows; the
     * token, and the file, stay as this device has them - a restored phone still has to be
     * given its own token, and there is no honest way around that.
     */
    override suspend fun apply(record: BackupSettingsRecord) {
        setRemindersEnabled(record.remindersEnabled)
        setUpdateChecksEnabled(record.updateChecksEnabled)
        setBackUpAutomatically(record.backUpAutomatically)
        record.destination?.let { setBackupDestination(BackupDestination.fromName(it)) }
    }

    companion object {
        /** The repository this app's backups were set up for; the field can be changed. */
        const val DEFAULT_BACKUP_REPO = "cqrt/spray-day-backups"
    }
}
