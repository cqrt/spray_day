package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import android.content.Intent
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
import nz.mckenzie.sprayday.backup.BackupTargets
import nz.mckenzie.sprayday.backup.FileBackupTarget
import nz.mckenzie.sprayday.data.BackupController
import nz.mckenzie.sprayday.data.BackupRepository
import nz.mckenzie.sprayday.data.HandoverController
import nz.mckenzie.sprayday.data.HandoverRepository
import nz.mckenzie.sprayday.data.OffsiteBackup
import nz.mckenzie.sprayday.data.OffsiteResult
import nz.mckenzie.sprayday.data.ReminderStateStore
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.backup.BackupDestination
import nz.mckenzie.sprayday.domain.backup.BackupSummary
import nz.mckenzie.sprayday.domain.backup.OffsiteBackupRules
import nz.mckenzie.sprayday.domain.backup.StoredBackup
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

/** What the off-site "Test" button found. */
sealed interface OffsiteCheckState {
    data object Idle : OffsiteCheckState
    data object Checking : OffsiteCheckState

    /**
     * The destination answered. [warning] is set when it will work but should not be used
     * as it is - a public repository being the one that matters.
     */
    data class Worked(val note: String, val warning: String? = null) : OffsiteCheckState

    data class Failed(val message: String) : OffsiteCheckState
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
    private val handover: HandoverController? = null,
    /**
     * The off-site copy, built fresh from the settings each time it is used so that a
     * destination changed on this screen takes effect at once. Null in a test that has no
     * destination, which is also what the screen reports as "not set up yet".
     */
    private val offsite: (suspend () -> OffsiteBackup?)? = null,
    /** Takes a lasting permission on the file the operator chose, so it survives a restart. */
    private val keepFileAccess: (Uri) -> Unit = {},
    /** The chosen file's name, for the screen to say where the copy goes. */
    private val fileLabel: (Uri) -> String = { uri -> uri.lastPathSegment.orEmpty() }
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

    /** A backup that has been read, and what restoring it would replace. */
    data class PendingRestore(
        val source: RestoreSource,
        val file: BackupSummary,
        val current: BackupSummary
    ) {
        /** "The file" or "The copy in GitHub, cqrt/spray-day-backups", for the dialog. */
        val heldBy: String
            get() = when (source) {
                is RestoreSource.File -> "The file"
                is RestoreSource.Offsite -> "The copy in ${source.targetLabel}"
            }

        /**
         * The line about the copy itself: which file, from where, and how old.
         *
         * The age is the number that matters before replacing everything - a copy from
         * three days ago is a different decision from one from March - and it comes from
         * inside the file rather than from when this phone last wrote one.
         */
        fun provenance(nowEpochMs: Long = System.currentTimeMillis()): String? =
            (source as? RestoreSource.Offsite)?.let { copy ->
                "${copy.name} in ${copy.targetLabel} was " +
                    OffsiteBackupRules.ageOf(copy.savedAtEpochMs, nowEpochMs)
            }
    }

    /** Where a copy being restored came from. */
    sealed interface RestoreSource {
        /** The file the operator chose in the system picker. */
        data class File(val uri: Uri) : RestoreSource

        /** A copy in the off-site destination, by the name it is kept under. */
        data class Offsite(
            val reference: String,
            val name: String,
            val targetLabel: String,
            val savedAtEpochMs: Long
        ) : RestoreSource
    }

    val versionLabel: String = "version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    init {
        viewModelScope.launch {
            val stored = settings.storedLinzApiKey.first()
            if (!typed) _keyText.value = stored
            _storedTiles.value = TileStoreSummary(store.storedTileCount(), store.storedBytes())
        }

        // The off-site fields are seeded the same way, and for the same reason: pasting a
        // token takes seconds on a slow device, and a late read would wipe half of one.
        viewModelScope.launch {
            val repo = settings.backupRepo.first()
            val token = settings.backupToken.first()
            if (!typedRepo) _repoText.value = repo
            if (!typedToken) _tokenText.value = token
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
            runCatching {
                PendingRestore(
                    source = RestoreSource.File(uri),
                    file = controller.inspect(uri),
                    current = controller.currentSummary()
                )
            }
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
            _dataMessage.value = runCatching {
                when (val source = pending.source) {
                    is RestoreSource.File -> controller.restoreFrom(source.uri)
                    is RestoreSource.Offsite -> {
                        val copy = offsite?.invoke()
                            ?: throw IllegalStateException("that destination is no longer set up")
                        copy.restore(source.reference)
                    }
                }
            }
                .map { summary -> "Restored ${summary.describe()}." }
                .getOrElse { "Nothing was restored: ${it.message}" }
            _pendingRestore.value = null
            _dataBusy.value = false
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    /**
     * The off-site copy's own state.
     *
     * Separate from the file card's message and busy flag because the two ask different
     * questions, and a message about one must never appear under the other.
     */
    private val _offsiteMessage = MutableStateFlow<String?>(null)
    val offsiteMessage: StateFlow<String?> = _offsiteMessage

    private val _offsiteBusy = MutableStateFlow(false)
    val offsiteBusy: StateFlow<Boolean> = _offsiteBusy

    private val _offsiteCheck = MutableStateFlow<OffsiteCheckState>(OffsiteCheckState.Idle)
    val offsiteCheck: StateFlow<OffsiteCheckState> = _offsiteCheck

    /** Set only when the destination holds more than one copy: the operator picks. */
    private val _copiesToChoose = MutableStateFlow<List<StoredBackup>?>(null)
    val copiesToChoose: StateFlow<List<StoredBackup>?> = _copiesToChoose

    /** The repository field, seeded from what is stored, with the key field's race guard. */
    private val _repoText = MutableStateFlow("")
    val repoText: StateFlow<String> = _repoText

    private val _tokenText = MutableStateFlow("")
    val tokenText: StateFlow<String> = _tokenText

    private var typedRepo = false
    private var typedToken = false

    val backupDestination: StateFlow<BackupDestination> = settings.backupDestination.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        BackupDestination.OFF
    )

    /** The file the copy goes to, by name. Empty when none has been chosen. */
    val backupFileName: StateFlow<String> = settings.backupFileName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    val backUpAutomatically: StateFlow<Boolean> = settings.backUpAutomatically
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** "Last off-site copy: saved 5 days ago, on 12 Sep 2026", or null when there is none. */
    val lastBackupLabel: StateFlow<String?> = settings.lastOffsiteBackupAt
        .map { at ->
            if (at <= 0L) {
                null
            } else {
                "Last off-site copy: " + OffsiteBackupRules.ageOf(at, System.currentTimeMillis())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setRepoText(text: String) {
        typedRepo = true
        _repoText.value = text
        _offsiteCheck.value = OffsiteCheckState.Idle
        _offsiteMessage.value = null
    }

    fun setTokenText(text: String) {
        typedToken = true
        _tokenText.value = text
        _offsiteCheck.value = OffsiteCheckState.Idle
        _offsiteMessage.value = null
    }

    /**
     * Saves the repository and the token.
     *
     * Neither is ever written into a backup. The token stays on this phone: the copy lives
     * in the repository the token can write to, so a backup carrying it would hand over the
     * repository as well.
     */
    fun saveOffsite() {
        viewModelScope.launch {
            settings.setBackupRepo(_repoText.value)
            settings.setBackupToken(_tokenText.value)
            _offsiteCheck.value = OffsiteCheckState.Idle
            _offsiteMessage.value = if (_tokenText.value.isBlank()) {
                "Saved, but with no token nothing can be written to the repository."
            } else {
                "Saved. The repository is ${_repoText.value.trim()}, and the token is kept on " +
                    "this phone only - never in a backup."
            }
        }
    }

    /** Turns the off-site copy off, or points it at a file or a repository. */
    fun setBackupDestination(destination: BackupDestination) {
        viewModelScope.launch {
            settings.setBackupDestination(destination)
            _offsiteCheck.value = OffsiteCheckState.Idle
            _offsiteMessage.value = when (destination) {
                BackupDestination.OFF ->
                    "Off-site copies off. The file you export by hand is the only copy."

                BackupDestination.FILE -> if (settings.backupFileUri.first().isBlank()) {
                    "Choose the file the copy should be written to."
                } else {
                    "Copies are written to ${settings.backupFileName.first()}."
                }

                BackupDestination.GITHUB -> if (settings.backupToken.first().isBlank()) {
                    "Enter a token for the repository, and press Save."
                } else {
                    "Copies go to ${settings.backupRepo.first()}."
                }
            }
        }
    }

    /**
     * Puts a copy in the off-site destination, now.
     *
     * The one thing this will not do is replace a good copy with an empty database - the
     * backup itself refuses that, and the message says why rather than quietly doing
     * nothing. A fresh install is exactly the case it exists for.
     */
    fun backUpOffsiteNow() {
        viewModelScope.launch {
            _offsiteBusy.value = true
            _offsiteMessage.value = null
            val copy = offsite?.invoke()
            _offsiteMessage.value = when {
                copy == null -> notSetUpYet()

                else -> when (
                    val result = runCatching { copy.backUpNow() }
                        .getOrElse { failure ->
                            OffsiteResult.Failed(failure.message ?: "the copy could not be written")
                        }
                ) {
                    is OffsiteResult.Written -> {
                        settings.setLastOffsiteBackupAt(System.currentTimeMillis())
                        "Backed up ${result.summary.describe()} to ${copy.destinationLabel}."
                    }

                    OffsiteResult.RefusedEmpty -> OffsiteBackupRules.refusedBecauseEmpty()

                    is OffsiteResult.Failed -> "Nothing was written: ${result.message}"
                }
            }
            _offsiteBusy.value = false
        }
    }

    /**
     * Asks the destination whether it will take the copy - and whether it is private.
     *
     * The privacy answer cannot be got any other way, and it matters: a repository that
     * accepts the token and is public would put a farm's records in public, and nothing else
     * in the app would ever mention it.
     */
    fun testOffsite() {
        viewModelScope.launch {
            _offsiteCheck.value = OffsiteCheckState.Checking
            _offsiteMessage.value = null
            val copy = offsite?.invoke()
            _offsiteCheck.value = when {
                copy == null -> OffsiteCheckState.Failed(notSetUpYet())

                else -> runCatching { copy.check() }
                    .map { answer ->
                        when {
                            answer == null -> OffsiteCheckState.Worked(
                                "A file cannot be tested without writing to it. Use \"Back up now\"."
                            )

                            !answer.isPrivate -> OffsiteCheckState.Worked(
                                note = "${answer.fullName} answered, and the token can write to it.",
                                warning = "That repository is public, so anyone can read your " +
                                    "tracks, sprays and recordings. Make it private before " +
                                    "backing up."
                            )

                            !answer.canPush -> OffsiteCheckState.Worked(
                                note = "${answer.fullName} is private.",
                                warning = "The token can see it but not write to it. A " +
                                    "fine-grained token needs Contents: read and write."
                            )

                            else -> OffsiteCheckState.Worked(
                                "${answer.fullName} is a private repository, and the token can " +
                                    "write the copy to it."
                            )
                        }
                    }
                    .getOrElse { failure ->
                        OffsiteCheckState.Failed(failure.message ?: "the destination could not be reached")
                    }
            }
        }
    }

    /**
     * Turns the weekly backup on or off.
     *
     * Only the setting is written: the application watches it and enqueues or cancels the
     * work, so there is one place that keeps the schedule honest - and a phone restored from
     * a backup starts backing itself up the same way.
     */
    fun setBackUpAutomatically(enabled: Boolean) {
        viewModelScope.launch {
            settings.setBackUpAutomatically(enabled)
            _offsiteMessage.value = if (enabled) {
                "Automatic backup on: the copy is written once a week, when there is a connection."
            } else {
                "Automatic backup off. Nothing is written in the background."
            }
        }
    }

    /**
     * A file chosen as the destination, kept so the copy can be written there again.
     *
     * The permission is taken as well as the `Uri`: without it the file works until the app
     * is restarted and then quietly cannot be written at all.
     */
    fun chooseOffsiteFile(uri: Uri) {
        keepFileAccess(uri)
        val name = fileLabel(uri)
        viewModelScope.launch {
            settings.setBackupFile(uri.toString(), name)
            settings.setBackupDestination(BackupDestination.FILE)
            _offsiteMessage.value = "Off-site copies will be written to $name."
        }
    }

    /** Fetches what the destination holds, and asks before replacing anything with it. */
    fun reviewOffsiteRestore() {
        viewModelScope.launch {
            _offsiteBusy.value = true
            _offsiteMessage.value = null
            val copy = offsite?.invoke()
            if (copy == null) {
                _offsiteMessage.value = notSetUpYet()
            } else {
                runCatching { copy.list() }
                    .onSuccess { copies ->
                        when {
                            copies.isEmpty() ->
                                _offsiteMessage.value = "There is no copy in ${copy.destinationLabel} yet."
                            // One copy is the ordinary case, and asking which one when there
                            // is only one would be a question with no content.
                            copies.size == 1 -> openOffsiteRestore(copy, copies.first())
                            else -> _copiesToChoose.value = copies
                        }
                    }
                    .onFailure {
                        _offsiteMessage.value = "The copies could not be listed: ${it.message}"
                    }
            }
            _offsiteBusy.value = false
        }
    }

    /** The operator has picked which of several copies to restore. */
    fun chooseOffsiteCopy(stored: StoredBackup) {
        _copiesToChoose.value = null
        viewModelScope.launch {
            _offsiteBusy.value = true
            offsite?.invoke()?.let { copy -> openOffsiteRestore(copy, stored) }
            _offsiteBusy.value = false
        }
    }

    fun cancelOffsiteChoice() {
        _copiesToChoose.value = null
    }

    /**
     * Reads the copy's counters and hands them to the same dialog the file path uses.
     *
     * The copy is fetched again when the operator confirms rather than held in memory: a
     * season of recordings is not worth keeping in the heap to save one download, and the
     * second read is what makes the dialog's numbers true of what is actually restored.
     */
    private suspend fun openOffsiteRestore(copy: OffsiteBackup, stored: StoredBackup) {
        runCatching { copy.preview(stored) }
            .onSuccess { preview ->
                _pendingRestore.value = PendingRestore(
                    source = RestoreSource.Offsite(
                        reference = stored.reference,
                        name = stored.name,
                        targetLabel = copy.destinationLabel,
                        savedAtEpochMs = preview.savedAtEpochMs
                    ),
                    file = preview.summary,
                    current = preview.here
                )
            }
            .onFailure { failure ->
                _offsiteMessage.value = "That copy could not be read: ${failure.message}"
            }
    }

    /** Why nothing can happen yet, in terms of the one thing that is missing. */
    private suspend fun notSetUpYet(): String = when (settings.backupDestination.first()) {
        BackupDestination.OFF -> "Choose where the off-site copy should go first."
        BackupDestination.FILE -> "Choose the file the copy should be written to first."
        BackupDestination.GITHUB -> "Enter the token for the repository, and press Save, first."
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
                    val settings = SettingsRepository(appContext)

                    // Cheap: the database is a singleton, and this only holds references.
                    fun backupRepository() = BackupRepository(
                        db = SprayDayDatabase.get(appContext),
                        appVersion = BuildConfig.VERSION_NAME
                    )

                    SettingsViewModel(
                        settings = settings,
                        store = TileServerHolder.store(appContext),
                        runReminderCheck = {
                            DueReminderCheck(
                                assetRepository = AssetRepository(SprayDayDatabase.get(appContext)),
                                store = ReminderStateStore(appContext),
                                notifier = ReminderNotifier(appContext)
                            ).run()
                        },
                        backup = BackupController(
                            repository = backupRepository(),
                            context = appContext,
                            switches = settings
                        ),
                        handover = HandoverController(
                            repository = HandoverRepository(SprayDayDatabase.get(appContext)),
                            context = appContext
                        ),
                        // Built per use rather than held, so changing the destination on
                        // this screen takes effect on the next press of a button.
                        offsite = {
                            val target = BackupTargets.from(appContext, settings)
                            if (target == null) {
                                null
                            } else {
                                val repository = backupRepository()
                                OffsiteBackup(
                                    controller = BackupController(
                                        repository = repository,
                                        context = appContext,
                                        switches = settings
                                    ),
                                    repository = repository,
                                    target = target
                                )
                            }
                        },
                        keepFileAccess = { uri ->
                            runCatching {
                                appContext.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                            }
                            Unit
                        },
                        fileLabel = { uri -> FileBackupTarget(appContext, uri).label }
                    )
                }
            }
        }
    }
}
