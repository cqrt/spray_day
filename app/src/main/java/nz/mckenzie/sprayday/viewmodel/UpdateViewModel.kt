package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.domain.update.AppVersion
import nz.mckenzie.sprayday.domain.update.AvailableUpdate
import nz.mckenzie.sprayday.update.ApkInstaller
import nz.mckenzie.sprayday.update.GitHubReleaseFeed
import nz.mckenzie.sprayday.update.InstallResult
import nz.mckenzie.sprayday.update.UpdateCheck
import nz.mckenzie.sprayday.update.UpdateNotifier

/** What the updates card is showing. */
sealed interface UpdateState {

    data object Idle : UpdateState

    data object Checking : UpdateState

    /** Nothing newer to install, and [message] says what was found. */
    data class NothingToInstall(val message: String) : UpdateState

    data class Available(val update: AvailableUpdate) : UpdateState
}

/** Where an install has got to. */
sealed interface InstallState {

    data object Idle : InstallState

    data class Downloading(val progress: Float) : InstallState

    /** Android has it: it is either asking the operator to confirm or already installing. */
    data object HandedToInstaller : InstallState

    data class Failed(val message: String) : InstallState
}

/**
 * The updates card in Settings: what version this is, whether there is a newer one, and the
 * download-and-install the operator asked for.
 *
 * The check itself is [UpdateCheck] rather than anything here, so the button and the daily
 * background job are the same code and cannot report different things.
 */
class UpdateViewModel(
    private val settings: SettingsRepository,
    private val context: Context,
    private val currentVersion: AppVersion?
) : ViewModel() {

    /** What this build is, for the line that says so. */
    val currentLabel: String = currentVersion?.toString() ?: BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install.asStateFlow()

    val autoCheckEnabled: StateFlow<Boolean> = settings.updateChecksEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /**
     * Whether Android would let an update be installed right now.
     *
     * Read on demand rather than remembered, because the operator leaves for the system
     * screen that grants it and comes back.
     */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { settings.setUpdateChecksEnabled(enabled) }
    }

    /** Asks GitHub now, quietly: the answer is about to be on the screen. */
    fun check() {
        if (_state.value == UpdateState.Checking) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val outcome = UpdateCheck(
                current = currentVersion,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                feed = GitHubReleaseFeed(),
                settings = settings,
                notifier = UpdateNotifier(context)
            ).run(announce = false)

            _state.value = outcome.available
                ?.let { UpdateState.Available(it) }
                ?: UpdateState.NothingToInstall(outcome.message)
        }
    }

    /**
     * Downloads the release and hands it to Android's installer.
     *
     * The progress is kept because this is a 15 MB download on a farm connection, and a
     * screen that says nothing for a minute looks broken.
     */
    fun install(update: AvailableUpdate) {
        if (_install.value is InstallState.Downloading) return
        _install.value = InstallState.Downloading(0f)
        viewModelScope.launch {
            val result = ApkInstaller(context).install(update) { progress ->
                _install.value = InstallState.Downloading(progress)
            }
            _install.value = when (result) {
                is InstallResult.HandedToInstaller -> InstallState.HandedToInstaller
                is InstallResult.Failed -> InstallState.Failed(result.message)
            }
        }
    }

    /** Sends the operator to the one Android switch this app cannot flip for them. */
    fun openInstallPermission() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    UpdateViewModel(
                        settings = SettingsRepository(appContext),
                        context = appContext,
                        currentVersion = AppVersion.parse(BuildConfig.VERSION_NAME)
                    )
                }
            }
        }
    }
}
