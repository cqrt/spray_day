package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.data.BackupController
import nz.mckenzie.sprayday.data.BackupRepository
import nz.mckenzie.sprayday.data.HandoverController
import nz.mckenzie.sprayday.data.HandoverRepository
import nz.mckenzie.sprayday.data.ReminderStateStore
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.backup.BackupSummary
import nz.mckenzie.sprayday.offline.KeyCheck
import nz.mckenzie.sprayday.offline.LinzKeyProbe
import nz.mckenzie.sprayday.offline.OfflineTileStore
import nz.mckenzie.sprayday.offline.TileServerHolder
import nz.mckenzie.sprayday.offline.TileStoreSummary
import nz.mckenzie.sprayday.offline.maskKey
import nz.mckenzie.sprayday.reminders.DueReminderCheck
import nz.mckenzie.sprayday.reminders.ReminderNotifier
import nz.mckenzie.sprayday.reminders.ReminderOutcome

/** Where the key currently in use came from. */
enum class KeySource { BUILT_IN, ENTERED, NONE }

/** What the "check the key" button is doing. */
sealed interface KeyCheckState {
    data object Idle : KeyCheckState
    data object Checking : KeyCheckState
    data object Worked : KeyCheckState
    data class Failed(val message: String) : KeyCheckState
}

/**
 * The app's settings: the LINZ Basemaps key, and whether to be reminded about tracks
 * that come due.
 *
 * The key matters because a standard-access key expires every 90 days and the failure
 * is silent: the map just stops drawing. Before this screen there was no way to enter
 * a new key at all, so an expired key meant waiting for a new build.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val store: OfflineTileStore,
    private val probe: LinzKeyProbe = LinzKeyProbe(),
    /** Injected so tests never reach LINZ. */
    private val checkKey: suspend (String) -> KeyCheck = { probe.check(it) },
    /**
     * Runs one due-reminder check. Built by the factory rather than here, so this view
     * model needs no database and a test can answer without one.
     */
    private val runReminderCheck: (suspend () -> ReminderOutcome)? = null,
    /** Backup files: chosen by the operator, read and written by the app. */
    private val backup: BackupController? = null,
    /** The season as a handover record. */
    private val handover: HandoverController? = null
) : ViewModel() {

    /** The key as typed, seeded from what is stored rather than from the default. */
    private val _keyText = MutableStateFlow("")
    val keyText: StateFlow<String> = _keyText

    /**
     * True once the operator has touched the field, so the seeding read below cannot
     * land late and wipe a key that is half typed. This is a real race, not a
     * theoretical one: the field is seeded from DataStore, and on a slow device the
     * read can take longer than it takes to start typing.
     */
    private var typed = false

    val keySource: StateFlow<KeySource> = settings.storedLinzApiKey
        .map { stored ->
            when {
                stored.isNotBlank() -> KeySource.ENTERED
                BuildConfig.LINZ_API_KEY.isNotBlank() -> KeySource.BUILT_IN
                else -> KeySource.NONE
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialSource())

    /** The key actually in force, masked: enough to tell two keys apart, no more. */
    val activeKeyLabel: StateFlow<String> = settings.linzApiKey
        .map { key -> if (key.isBlank()) "" else maskKey(key) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            maskKey(BuildConfig.LINZ_API_KEY)
        )

    private val _check = MutableStateFlow<KeyCheckState>(KeyCheckState.Idle)
    val check: StateFlow<KeyCheckState> = _check

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _storedTiles = MutableStateFlow(TileStoreSummary(0, 0))
    val storedTiles: StateFlow<TileStoreSummary> = _storedTiles

    /**
     * Whether the app tells the operator when tracks come due.
     *
     * Turning it on or off only writes the setting: the application watches it and
     * enqueues or cancels the background work, so there is one place that keeps the
     * schedule honest.
     */
    val remindersEnabled: StateFlow<Boolean> = settings.remindersEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /** What the last Check now said, in the operator's words. */
    private val _reminderOutcome = MutableStateFlow<String?>(null)
    val reminderOutcome: StateFlow<String?> = _reminderOutcome

    private val _checkingReminders = MutableStateFlow(false)
    val checkingReminders: StateFlow<Boolean> = _checkingReminders

    /** What the last backup, restore or handover said. One line, because it is one card. */
    private val _dataMessage = MutableStateFlow<String?>(null)
    val dataMessage: StateFlow<String?> = _dataMessage

    private val _dataBusy = MutableStateFlow(false)
    val dataBusy: StateFlow<Boolean> = _dataBusy

    /** A chosen file waiting to be restored, with both sides of the trade in numbers. */
    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore

    /** A backup file that has been read, and what restoring it would replace. */
    data class PendingRestore(
        val uri: Uri,
        val file: BackupSummary,
        val current: BackupSummary
    )

    val versionLabel: String = "version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    init {
        viewModelScope.launch {
            val stored = settings.storedLinzApiKey.first()
            if (!typed) _keyText.value = stored
            _storedTiles.value = TileStoreSummary(store.storedTileCount(), store.storedBytes())
        }
    }

    fun setKeyText(text: String) {
        typed = true
        _keyText.value = text
        // Any edit invalidates a previous verdict.
        _check.value = KeyCheckState.Idle
        _message.value = null
    }

    fun save() {
        viewModelScope.launch {
            val value = _keyText.value.trim()
            settings.setLinzApiKey(value)
            _message.value = if (value.isBlank()) {
                "Cleared: the key built into this build is in use again"
            } else {
                "Saved. Press Check to make sure LINZ accepts it."
            }
        }
    }

    /** Falls back to the key shipped in the build, if there is one. */
    fun clearEnteredKey() {
        viewModelScope.launch {
            typed = true
            _keyText.value = ""
            settings.setLinzApiKey("")
            _check.value = KeyCheckState.Idle
            _message.value = "Cleared: the key built into this build is in use again"
        }
    }

    /** Asks LINZ whether it accepts the key, which is the only honest answer. */
    fun check() {
        viewModelScope.launch {
            _check.value = KeyCheckState.Checking
            _message.value = null
            val key = settings.linzApiKey.first()
            _check.value = when {
                key.isBlank() -> KeyCheckState.Failed("There is no key to check")
                else -> when (val result = runCatching { checkKey(key) }.getOrElse { failure ->
                    KeyCheck.Failed(failure.message ?: "the check could not run")
                }) {
                    KeyCheck.Works -> KeyCheckState.Worked
                    is KeyCheck.Failed -> KeyCheckState.Failed(result.message)
                }
            }
        }
    }

    /** Turns the twice-daily check on or off. The application keeps the work in step. */
    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setRemindersEnabled(enabled)
            _reminderOutcome.value = if (enabled) {
                "Reminders on: tracks are checked twice a day."
            } else {
                "Reminders off. Nothing will be posted in the background."
            }
        }
    }

    /**
     * Runs the check straight away and reports what it did, rather than making the
     * operator wait half a day to find out whether reminders work.
     */
    fun checkRemindersNow() {
        val check = runReminderCheck ?: return
        viewModelScope.launch {
            _checkingReminders.value = true
            _reminderOutcome.value = runCatching { check() }
                .map { it.message }
                .getOrElse { it.message ?: "The reminder check could not run" }
            _checkingReminders.value = false
        }
    }

    /** "spray-day-backup-2026-09-14.json" - offered as the file name in the picker. */
    fun backupFileName(): String = backup?.suggestedFileName() ?: "spray-day-backup.json"

    /** Writes everything the app holds to the file the operator chose. */
    fun exportBackupTo(uri: Uri) {
        val controller = backup ?: return
        viewModelScope.launch {
            _dataBusy.value = true
            _dataMessage.value = runCatching { controller.exportTo(uri) }
                .map { summary -> "Backed up ${summary.describe()}." }
                .getOrElse { "Nothing was written: ${it.message}" }
            _dataBusy.value = false
        }
    }

    /** "spray-day-sprays-2026-09-14.csv" - the season as a spreadsheet for somebody else. */
    fun handoverFileName(): String = handover?.suggestedFileName() ?: "spray-day-sprays.csv"

    /** Writes every spray of every track as a handover record. */
    fun exportHandoverTo(uri: Uri) {
        val controller = handover ?: return
        viewModelScope.launch {
            _dataBusy.value = true
            _dataMessage.value = runCatching { controller.exportTo(uri) }
                .map { sprays ->
                    if (sprays == 0) {
                        "Nothing has been sprayed yet, so the file has only column names."
                    } else {
                        "Handed over $sprays spray${if (sprays == 1) "" else "s"}."
                    }
                }
                .getOrElse { "Nothing was written: ${it.message}" }
            _dataBusy.value = false
        }
    }

    /**
     * Reads the chosen file and asks first. Restoring replaces everything, so the
     * numbers on both sides are shown before anything is touched.
     */
    fun chooseBackupToRestore(uri: Uri) {
        val controller = backup ?: return
        viewModelScope.launch {
            _dataBusy.value = true
            _dataMessage.value = null
            runCatching { PendingRestore(uri, controller.inspect(uri), controller.currentSummary()) }
                .onSuccess { _pendingRestore.value = it }
                .onFailure { _dataMessage.value = it.message }
            _dataBusy.value = false
        }
    }

    fun confirmRestore() {
        val controller = backup ?: return
        val pending = _pendingRestore.value ?: return
        viewModelScope.launch {
            _dataBusy.value = true
            _dataMessage.value = runCatching { controller.restoreFrom(pending.uri) }
                .map { summary -> "Restored ${summary.describe()}." }
                .getOrElse { "Nothing was restored: ${it.message}" }
            _pendingRestore.value = null
            _dataBusy.value = false
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Until the stored setting has been read, the key baked into the build is the
         * one in force, so the screen says so at once instead of flashing "no key" on a
         * slow device and correcting itself a moment later.
         */
        private fun initialSource(): KeySource =
            if (BuildConfig.LINZ_API_KEY.isNotBlank()) KeySource.BUILT_IN else KeySource.NONE

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    SettingsViewModel(
                        settings = SettingsRepository(appContext),
                        store = TileServerHolder.store(appContext),
                        runReminderCheck = {
                            DueReminderCheck(
                                tracks = TrackRepository(SprayDayDatabase.get(appContext)),
                                store = ReminderStateStore(appContext),
                                notifier = ReminderNotifier(appContext)
                            ).run()
                        },
                        backup = BackupController(
                            repository = BackupRepository(
                                db = SprayDayDatabase.get(appContext),
                                appVersion = BuildConfig.VERSION_NAME
                            ),
                            context = appContext
                        ),
                        handover = HandoverController(
                            repository = HandoverRepository(SprayDayDatabase.get(appContext)),
                            context = appContext
                        )
                    )
                }
            }
        }
    }
}
