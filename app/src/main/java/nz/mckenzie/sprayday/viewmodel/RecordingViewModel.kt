package nz.mckenzie.sprayday.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.SprayProductQuantity
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.Coverage
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.formatCoveragePercent
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.tracking.TrackingService
import nz.mckenzie.sprayday.tracking.TrackingState
import nz.mckenzie.sprayday.ui.formatQuantityMl
import nz.mckenzie.sprayday.ui.formatShortDate
import nz.mckenzie.sprayday.ui.parseQuantityMl

/**
 * Drives the recorder screen.
 *
 * The service owns the recording loop; this only starts/stops it and mirrors
 * [TrackingState] plus the growing geometry from the database, so leaving and
 * re-entering the screen (or restarting the app) loses nothing.
 *
 * Beyond recording the line, this is where a spray is recorded *while driving it*:
 * pick the planned track, type the amounts as they go in, watch the live coverage
 * tell you whether the whole line has been done, and finish - which saves the
 * recording and the spray together, linked by session id.
 */
class RecordingViewModel(
    private val recordings: RecordingRepository,
    private val assetRepository: AssetRepository,
    private val sprays: SprayRepository,
    settingsRepository: SettingsRepository,
    private val context: Context
) : ViewModel() {

    /** One product line as the operator enters it. */
    data class SprayRow(
        val productId: Long,
        val name: String,
        val quantityText: String
    )

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val tracking: StateFlow<TrackingState.State> = TrackingState.state

    /** Planned tracks to choose from, with their due colours. */
    val assetsToSpray: StateFlow<List<AssetWithDue>> = assetRepository.observeAssetsWithDue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _selectedAssetId = MutableStateFlow<Long?>(null)
    val selectedAssetId: StateFlow<Long?> = _selectedAssetId

    val selectedTrackName: StateFlow<String?> =
        combine(_selectedAssetId, assetsToSpray) { id, list ->
            list.firstOrNull { it.asset.id == id }?.asset?.name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _rows = MutableStateFlow<List<SprayRow>>(emptyList())
    val rows: StateFlow<List<SprayRow>> = _rows

    /** The selected track's remembered amounts, applied as rows appear. */
    private var trackDefaults: Map<Long, Double> = emptyMap()

    private val _rememberDefaults = MutableStateFlow(true)
    val rememberDefaults: StateFlow<Boolean> = _rememberDefaults

    /**
     * How much of the chosen track's line this run has covered so far, or null when
     * there is nothing to compare (no track chosen, or nothing recorded yet).
     */
    private val _coverage = MutableStateFlow<Double?>(null)
    val coverage: StateFlow<Double?> = _coverage

    private val sessionPoints = MutableStateFlow<List<GeoPoint>>(emptyList())
    private val plannedGeometry = MutableStateFlow<List<GeoPoint>>(emptyList())

    /**
     * What the map draws: the planned line being followed, in grey, and the line as
     * recorded so far in red - so the gap still to drive is visible rather than
     * something to be worked out from a percentage.
     */
    val recordedGeoJson: StateFlow<String> =
        combine(sessionPoints, plannedGeometry) { recorded, planned ->
            AssetGeoJson.build(
                listOf(
                    AssetLine(
                        assetId = PLANNED_ID,
                        name = "Planned",
                        colorHex = AssetColors.UNKNOWN,
                        points = planned
                    ),
                    AssetLine(
                        assetId = RECORDING_ID,
                        name = "Recording",
                        colorHex = AssetColors.RED,
                        points = recorded
                    )
                )
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                AssetGeoJson.build(emptyList())
            )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /**
     * The name suggested for the track a finished recording is about to become, or
     * null when no name is being asked for. The screen shows a dialog while this is
     * set, so a new line always gets a name before it is saved as a track.
     */
    private val _pendingTrackName = MutableStateFlow<String?>(null)
    val pendingTrackName: StateFlow<String?> = _pendingTrackName

    private var observingSession: Long? = null

    init {
        viewModelScope.launch {
            // Products, so the amounts can be entered without leaving the map.
            sprays.observeProducts().collect { products ->
                val typed = _rows.value.associate { it.productId to it.quantityText }
                _rows.value = products.map { product ->
                    SprayRow(
                        productId = product.id,
                        name = product.name,
                        // Blank means "not entered yet", so a remembered amount still
                        // applies - which matters because products can load before a
                        // track is chosen, or after.
                        quantityText = typed[product.id]?.takeIf { it.isNotBlank() }
                            ?: trackDefaults[product.id]?.let { formatQuantityMl(it) }
                            ?: ""
                    )
                }
            }
        }

        // Live coverage. collectLatest plus a short delay makes this a debounce: a
        // fresh fix cancels the pending calculation rather than queueing another.
        viewModelScope.launch {
            combine(sessionPoints, plannedGeometry) { recorded, planned -> planned to recorded }
                .collectLatest { (planned, recorded) ->
                    if (planned.size < 2 || recorded.isEmpty()) {
                        _coverage.value = null
                        return@collectLatest
                    }
                    delay(COVERAGE_DEBOUNCE_MS)
                    _coverage.value = withContext(Dispatchers.Default) {
                        Coverage.coveredFraction(planned, recorded)
                    }
                }
        }

        viewModelScope.launch {
            // Reattach to a session left running, e.g. the app was killed mid-spray.
            val unfinished = recordings.findUnfinishedSession() ?: return@launch
            TrackingState.begin(unfinished.id, unfinished.startedAtEpochMs)
            val status = runCatching { RecordingStatus.valueOf(unfinished.status) }
                .getOrDefault(RecordingStatus.RECORDING)
            TrackingState.setStatus(status)
            observe(unfinished.id)
            unfinished.assetId?.let { selectTrack(it) }

            // A foreground service does not survive a process kill, so a session
            // still marked RECORDING has nothing collecting for it: start the
            // service again rather than silently leaving a dead session running.
            if (status == RecordingStatus.RECORDING && hasLocationPermission()) {
                TrackingService.start(context, unfinished.id)
                _message.value = "Resumed recording the track that was already in progress"
            } else {
                _message.value = "Reattached to the track that was already recording"
            }
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun onPermissionDenied() {
        _message.value = "Location permission is needed to record a track"
    }

    /**
     * Chooses which planned track is being sprayed. Its last amounts come back as
     * the starting point, so a repeat spray is a confirmation rather than a retype.
     * Can be called before or during a recording.
     */
    fun selectTrack(assetId: Long?) {
        _selectedAssetId.value = assetId
        viewModelScope.launch {
            if (assetId == null) {
                plannedGeometry.value = emptyList()
                return@launch
            }

            plannedGeometry.value = assetRepository.getAssetGeometry(assetId)

            // Only products the track actually remembers an amount for; a null
            // default means "show the product with an empty amount".
            trackDefaults = runCatching { sprays.getAssetDefaultLines(assetId) }
                .getOrDefault(emptyList())
                .mapNotNull { line -> line.defaultQuantityMl?.let { line.productId to it } }
                .toMap()

            val typed = _rows.value.associate { it.productId to it.quantityText }
            _rows.value = _rows.value.map { row ->
                row.copy(
                    quantityText = typed[row.productId]?.takeIf { it.isNotBlank() }
                        ?: trackDefaults[row.productId]?.let { formatQuantityMl(it) }
                        ?: ""
                )
            }

            // Record which track this session is for, even if it was started first.
            TrackingState.current.sessionId?.let { sessionId ->
                runCatching { recordings.setSessionAsset(sessionId, assetId) }
            }
        }
    }

    fun updateQuantity(productId: Long, text: String) {
        _rows.value = _rows.value.map { row ->
            if (row.productId == productId) row.copy(quantityText = text) else row
        }
    }

    fun setRememberDefaults(value: Boolean) {
        _rememberDefaults.value = value
    }

    fun start() {
        if (!hasLocationPermission()) {
            onPermissionDenied()
            return
        }
        viewModelScope.launch {
            _message.value = null
            val sessionId = recordings.startRecording(
                name = defaultName(),
                assetId = _selectedAssetId.value
            )
            TrackingState.begin(sessionId, System.currentTimeMillis())
            observe(sessionId)
            TrackingService.start(context, sessionId)
        }
    }

    fun pause() = sessionIdOrNull()?.let { TrackingService.pause(context) }

    fun resume() = sessionIdOrNull()?.let { TrackingService.resume(context) }

    /**
     * Closes the session with the distance the service accumulated, then records the
     * spray against the chosen track if amounts were entered - linked by
     * `SprayEventEntity.recordedSessionId`, so a spray record can always be traced
     * back to the GPS evidence for it, and the track picks up its new due date.
     *
     * The recording is saved first and on its own: if recording the spray fails, the
     * line is still safe on the device and the message says exactly that.
     */
    fun finish() {
        // Spraying a track that already exists finishes straight away - it has a
        // name. Recording a brand new line asks what to call it first, because on
        // this screen "record" means "make a track": a recording that only ever
        // appeared in the recordings list is not what anyone goes looking for.
        if (_selectedAssetId.value != null) {
            completeFinish(newTrackName = null)
        } else {
            _pendingTrackName.value = defaultTrackName()
        }
    }

    /** From the naming dialog: saves the recorded line as a new, named track. */
    fun confirmFinish(name: String) {
        completeFinish(newTrackName = name.trim().ifBlank { defaultTrackName() })
    }

    /** Dismisses the naming dialog without ending the recording. */
    fun cancelFinish() {
        _pendingTrackName.value = null
    }

    private fun completeFinish(newTrackName: String?) {
        val sessionId = sessionIdOrNull() ?: return
        viewModelScope.launch {
            _pendingTrackName.value = null

            val distanceM = TrackingState.current.distanceM
            val points = TrackingState.current.pointCount
            val sessionStart = TrackingState.current.startedAtEpochMs ?: System.currentTimeMillis()
            val existingAssetId = _selectedAssetId.value
            val lines = _rows.value.mapNotNull { row ->
                parseQuantityMl(row.quantityText)?.let { ml -> SprayProductQuantity(row.productId, ml) }
            }
            val coverageNow = _coverage.value

            // Read the geometry back from the database rather than the live flow: the
            // newest fix may not have reached the flow yet, and it must not be missing
            // from the track.
            val geometry = runCatching { recordings.getPoints(sessionId) }
                .getOrDefault(emptyList())

            var trackError: String? = null
            val assetId = when {
                existingAssetId != null -> existingAssetId
                newTrackName == null -> null
                geometry.size < 2 -> null
                else -> runCatching { assetRepository.createAsset(name = newTrackName, geometry = geometry) }
                    .onFailure { trackError = it.message ?: "could not save the track" }
                    .getOrNull()
            }

            // Read the name from the database, not from the flow the screen collects:
            // whether a recording gets its proper name must not depend on the UI
            // happening to be watching.
            val sprayTrackName = newTrackName ?: assetId?.let { id ->
                runCatching { assetRepository.getAsset(id)?.name }.getOrNull()
            }

            recordings.finishRecording(sessionId, distanceM = distanceM)

            // Name the recording after the work it is: a new line takes the name the
            // operator typed, and a repeat pass over an existing track takes that
            // track's name and the date, which is how the three passes a year are
            // told apart in the recordings list. Neither asks a question - a dialog
            // on the common path is friction for an answer the app already has.
            val sessionName = when {
                newTrackName != null -> newTrackName
                sprayTrackName != null -> "$sprayTrackName · ${formatShortDate(sessionStart)}"
                else -> null
            }
            sessionName?.let { runCatching { recordings.renameSession(sessionId, it) } }
            assetId?.let { runCatching { recordings.setSessionAsset(sessionId, it) } }

            TrackingService.stop(context)
            TrackingState.clear()
            observingSession = null
            sessionPoints.value = emptyList()
            plannedGeometry.value = emptyList()

            var message = "Saved $points ${if (points == 1) "point" else "points"}"
            message += when {
                newTrackName == null -> ""
                assetId != null -> " as \"$newTrackName\", which is on the Tracks page now"
                trackError != null -> " - the track could not be saved: $trackError"
                else -> " - too few points for a track, so it is in Recordings only"
            }
            if (coverageNow != null) {
                message += " · covered ${formatCoveragePercent(coverageNow)} of the line"
            }

            message += when {
                assetId == null -> ""
                lines.isEmpty() -> " · no products entered, so no spray was recorded"

                else -> runCatching {
                    sprays.recordSpray(
                        assetId = assetId,
                        products = lines,
                        distanceM = distanceM,
                        recordedSessionId = sessionId
                    )
                    if (_rememberDefaults.value) sprays.rememberDefaultsForTrack(assetId, lines)
                }.fold(
                    onSuccess = { " · recorded the spray for ${sprayTrackName ?: "the track"}" },
                    onFailure = { failure ->
                        " · the recording is saved, but the spray was not recorded: " +
                            (failure.message ?: "unknown error")
                    }
                )
            }

            _message.value = message
            _selectedAssetId.value = null
            _rows.value = _rows.value.map { it.copy(quantityText = "") }
        }
    }

    private fun sessionIdOrNull(): Long? = TrackingState.current.sessionId

    private fun observe(sessionId: Long) {
        if (observingSession == sessionId) return
        observingSession = sessionId
        viewModelScope.launch {
            recordings.observePoints(sessionId).collect { sessionPoints.value = it }
        }
    }

    private fun defaultName(): String = "Spray run"

    /** Suggests a track name: "Track 14 Sep" beats an empty box to type into. */
    private fun defaultTrackName(): String = "Track " + formatShortDate(System.currentTimeMillis())

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val RECORDING_ID = -2L
        private const val PLANNED_ID = -3L

        /** Long enough that a burst of fixes causes one calculation, not several. */
        private const val COVERAGE_DEBOUNCE_MS = 750L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = SprayDayDatabase.get(appContext)
                    RecordingViewModel(
                        recordings = RecordingRepository(database),
                        assetRepository = AssetRepository(database),
                        sprays = SprayRepository(database),
                        settingsRepository = SettingsRepository(appContext),
                        context = appContext
                    )
                }
            }
        }
    }
}
