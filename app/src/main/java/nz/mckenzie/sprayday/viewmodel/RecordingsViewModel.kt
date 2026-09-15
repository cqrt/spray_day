package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.RecordedSessionEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.recording.RecordingStatus

/**
 * One recording, as the browser lists it.
 *
 * [assetName] is null when the session was never tied to a track *or* when the
 * track has since been deleted - recordings deliberately outlive the plans they
 * were recorded against, so the browser says "track deleted" rather than
 * pretending the session had no track.
 */
data class RecordingRow(
    val id: Long,
    val name: String,
    val startedAtEpochMs: Long,
    val distanceM: Double,
    val durationMs: Long,
    val pointCount: Int,
    val status: RecordingStatus,
    val assetId: Long?,
    val assetName: String?
) {
    val isFinished: Boolean get() = status == RecordingStatus.FINISHED

    val assetWasDeleted: Boolean get() = assetId != null && assetName == null
}

/**
 * The recordings browser: every GPS recording on the device, what it was for, and
 * how far it went - the way back to the evidence for a spray.
 */
class RecordingsViewModel(
    private val recordings: RecordingRepository,
    assetRepository: AssetRepository
) : ViewModel() {

    val sessions: StateFlow<List<RecordingRow>> =
        combine(recordings.observeSessions(), assetRepository.observeAssetsWithDue()) { sessions, assetList ->
            val names = assetList.associate { item -> item.asset.id to item.asset.name }
            sessions.map { session -> session.toRow(names) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun delete(sessionId: Long) {
        viewModelScope.launch {
            runCatching { recordings.deleteRecording(sessionId) }
                .onFailure { _message.value = it.message ?: "Could not delete the recording" }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = SprayDayDatabase.get(appContext)
                    RecordingsViewModel(
                        recordings = RecordingRepository(database),
                        assetRepository = AssetRepository(database)
                    )
                }
            }
        }
    }
}

internal fun RecordedSessionEntity.toRow(assetNames: Map<Long, String>) = RecordingRow(
    id = id,
    name = name,
    startedAtEpochMs = startedAtEpochMs,
    distanceM = distanceM,
    durationMs = endedAtEpochMs?.let { it - startedAtEpochMs }?.coerceAtLeast(0L) ?: 0L,
    pointCount = pointCount,
    status = runCatching { RecordingStatus.valueOf(status) }.getOrDefault(RecordingStatus.FINISHED),
    assetId = assetId,
    assetName = assetId?.let { assetNames[it] }
)
