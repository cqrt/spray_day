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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.data.RecordingProgress
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.SprayProductQuantity
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.AssetSprayCoverage
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.Coverage
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.RecordedPass
import nz.mckenzie.sprayday.domain.geo.RecordingBreak
import nz.mckenzie.sprayday.domain.geo.TwoPasses
import nz.mckenzie.sprayday.domain.geo.TwoPassPhrase
import nz.mckenzie.sprayday.domain.geo.formatCoveragePercent
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.geo.splitAtBreaks
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.map.AssetStretch
import nz.mckenzie.sprayday.tracking.FusedLocationSource
import nz.mckenzie.sprayday.tracking.LocationSource
import nz.mckenzie.sprayday.tracking.TrackingService
import nz.mckenzie.sprayday.tracking.TrackingState
import nz.mckenzie.sprayday.tracking.frameOnDevice
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
    private val context: Context,
    /**
     * Where the phone is, for the first frame. Recording is driving a thing you are
     * standing next to, so opening on a neutral view of the country is no use.
     */
    private val locationSource: LocationSource
) : ViewModel() {

    /** One product line as the operator enters it. */
    data class SprayRow(
        val productId: Long,
        val name: String,
        val quantityText: String
    )

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Which map to draw the pass on: the operator's choice in Settings. */
    val basemap: StateFlow<Basemap> = settingsRepository.basemap
        .stateIn(viewModelScope, SharingStarted.Eagerly, Basemap.DEFAULT)

    val tracking: StateFlow<TrackingState.State> = TrackingState.state

    /** Planned tracks to choose from, with their due colours. */
    val assetsToSpray: StateFlow<List<AssetWithDue>> = assetRepository.observeAssetsWithDue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _initialFrame = MutableStateFlow<LatLngBounds?>(null)

    /** The frame for the camera when the map opens: around the phone, if it knows. */
    val initialFrame: StateFlow<LatLngBounds?> = _initialFrame

    init {
        viewModelScope.launch {
            _initialFrame.value = locationSource.frameOnDevice()
        }
    }

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
     * The pass that has just been saved.
     *
     * Pressing Finish used to wipe the screen it was pressed on: the line went out of the
     * drawing, the distance and the points went back to zero, the coverage vanished and the
     * track's length with it, leaving a message about a pass that the operator could no longer
     * see. The pass is kept here instead, so that the screen a moment after the save still
     * shows what was driven and what it covered.
     *
     * Held while the operator is here to see it, and given up the moment they say they are done
     * with it: choosing another track, starting another recording, or leaving the recorder
     * altogether - see [onScreenLeft].
     */
    private val _finished = MutableStateFlow<FinishedPass?>(null)
    val finished: StateFlow<FinishedPass?> = _finished

    /**
     * What the chosen line still owes, when it takes two passes: null for everything else.
     *
     * Worked out from the recordings the line already has, so the card can say "one pass still to
     * go" before the operator sets off, rather than only after they finish. Re-read whenever the
     * choice changes or a pass is saved - see [refreshTwoPasses] - and it is the same reading the
     * two-pass maths makes everywhere else, so the sentence and the line's colour agree.
     */
    private val _twoPasses = MutableStateFlow<TwoPasses.Result?>(null)
    val twoPasses: StateFlow<TwoPasses.Result?> = _twoPasses

    /**
     * Whether the app is waiting for the operator's word that both sides were done.
     *
     * Set at Finish, when the line has had two passes that cannot be told apart - the same way
     * along it, on sides too close together to separate - and there is nothing left to read. The
     * screen shows a dialog while this is set: the answer is the only thing that can settle it,
     * and it is written down with the pass it was given for - see [confirmBothSides].
     */
    private val _pendingBothSides = MutableStateFlow(false)
    val pendingBothSides: StateFlow<Boolean> = _pendingBothSides

    /**
     * Whether the operator wants the map to keep the phone in the middle of the screen.
     *
     * Their wish, not the map's behaviour: [followPhone] is what the map is told.
     */
    private val _following = MutableStateFlow(false)
    val following: StateFlow<Boolean> = _following

    /**
     * What the map is told: follow the phone while a pass is being driven, and only while the
     * operator has not taken the map for themselves by dragging it.
     *
     * Not while the recording is paused - that is the operator stopped, with both hands free
     * and the map theirs to read - and not after it is finished, so the pass they have just
     * finished stays where it ended rather than sliding around under them.
     */
    val followPhone: StateFlow<Boolean> = combine(tracking, _following) { live, wanted ->
        wanted && live.status == RecordingStatus.RECORDING
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * How much of the chosen track's line this run has covered, or null when there is nothing
     * to compare - no track chosen yet, or nothing recorded.
     *
     * The number the screen shows, which for a moment after a save is the saved pass's: a
     * plain state flow rather than a combination of two, so it stays a value anybody can read,
     * and the fallback to the saved pass happens where the number is worked out rather than in
     * a second flow beside it.
     */
    private val _coverage = MutableStateFlow<Double?>(null)
    val coverage: StateFlow<Double?> = _coverage

    /**
     * The numbers on the card: the pass being driven, or the one just saved.
     *
     * One flow rather than the screen reaching into [TrackingState] itself, so that what the
     * card says and what the map draws come from the same place and cannot disagree about
     * which pass is being looked at.
     */
    val summary: StateFlow<PassSummary> = combine(tracking, _finished) { live, done ->
        when {
            live.sessionId != null -> live.summary()
            done != null -> done.summary
            else -> live.summary()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PassSummary.EMPTY)

    /** One pass as the card reads it, whether it is still being driven or has just been saved. */
    data class PassSummary(
        val status: RecordingStatus?,
        val startedAtEpochMs: Long?,
        val endedAtEpochMs: Long?,
        val distanceM: Double,
        val pointCount: Int,
        val accuracyM: Float?,
        val rejectedFixes: Int
    ) {
        companion object {
            val EMPTY = PassSummary(null, null, null, 0.0, 0, null, 0)
        }
    }

    /** A pass that has been saved: what it was for, what was driven, and what that covered. */
    data class FinishedPass(
        val trackName: String?,
        val planned: List<GeoPoint>,
        val recorded: List<GeoPoint>,
        /** The stretches of it that were paused for, so the colours and the line stay honest. */
        val breaks: List<RecordingBreak> = emptyList(),
        val coverage: Double?,
        val summary: PassSummary
    ) {

        /** The saved pass in the shape the drawing and the coverage maths read. */
        fun toPass() = RecordedPass(
            atEpochMs = recorded.lastOrNull()?.timeMs ?: 0L,
            points = recorded,
            breaks = breaks
        )
    }

    /** The live pass's numbers, in the shape the card reads. */
    private fun TrackingState.State.summary(
        status: RecordingStatus? = this.status,
        endedAtEpochMs: Long? = null
    ) = PassSummary(
        status = status,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        distanceM = distanceM,
        pointCount = pointCount,
        accuracyM = lastAccuracyM,
        rejectedFixes = rejectedFixes
    )

    /**
     * The fixes of the recording that is happening now - what the screen draws, and what the
     * coverage above is measured from.
     *
     * Followed from [TrackingState] rather than collected when a session starts, so that
     * exactly one collection is alive at a time: `flatMapLatest` drops the previous session's
     * the moment the session changes. Collecting a session's points and merely moving on does
     * not work, and the way it fails is not obvious - a flow over `recorded_points` re-emits
     * whenever *that table* changes, whichever session was written to, so a recording that has
     * already finished keeps handing its whole geometry back. The line then flips between the
     * one being walked and the one that is finished (which is what "the track keeps
     * disappearing" looks like), and the coverage is taken against the finished pass - and
     * when that pass is the one the track was made from, it covers the line by definition and
     * the screen says 100% with half the track still to drive.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sessionPoints: StateFlow<List<GeoPoint>> = TrackingState.state
        .map { it.sessionId }
        .distinctUntilChanged()
        .flatMapLatest { sessionId ->
            if (sessionId == null) flowOf(emptyList()) else recordings.observePoints(sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The breaks in the recording that is happening now: the stretches the operator paused for.
     *
     * Followed exactly as the fixes are, so the number on the card and the colour of the line
     * are worked out from the pass as it actually was. A pause in the middle of a pass leaves
     * ground that was not sprayed, and no amount of fixes after it changes that - which is why
     * the two have to reach the coverage maths together.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sessionBreaks: StateFlow<List<RecordingBreak>> = TrackingState.state
        .map { it.sessionId }
        .distinctUntilChanged()
        .flatMapLatest { sessionId ->
            if (sessionId == null) flowOf(emptyList()) else recordings.observeBreaks(sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The pass being driven, in the shape the coverage maths and the drawing read. */
    private val livePass: StateFlow<RecordedPass> =
        combine(sessionPoints, sessionBreaks) { points, breaks ->
            RecordedPass(
                atEpochMs = points.lastOrNull()?.timeMs ?: 0L,
                points = points,
                breaks = breaks
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordedPass(0L, emptyList()))

    private val plannedGeometry = MutableStateFlow<List<GeoPoint>>(emptyList())

    /**
     * What the map draws: the planned line, cut into the part this pass has sprayed and the
     * part still to do, in the colours the rest of the app uses for those two states.
     *
     * The line carries the answer, not only a percentage beside it - and the two come from
     * the same [Coverage.splitByCoverage] call, so the screen cannot say one thing and draw
     * another. Reading *this* pass is the point: a track half sprayed a fortnight ago is not
     * this run's business, and the coverage beside these colours is this run's too.
     *
     * Drawn as one line instead, the plan vanished: the recording is drawn over it, both are
     * the same width, so an operator driving the line saw only their own trail and had no way
     * to see which part of the track was left, or that the track had ended at all.
     *
     * With no track chosen there is nothing to compare against, and the recording itself is
     * drawn - that is the "record a new line" case, where the line being made is the point.
     */
    val recordedGeoJson: StateFlow<String> =
        combine(livePass, plannedGeometry, _finished) { pass, planned, done ->
            when {
                // A pass being driven, or a track chosen and waiting for one: the plan is the
                // thing on the screen, in the colours of how much of it is done.
                pass.points.isNotEmpty() || planned.isNotEmpty() -> planned to pass
                // Nothing live. The pass that has just been saved stays where it was, rather
                // than the map going blank the moment Save is pressed.
                done != null -> done.planned to done.toPass()
                else -> emptyList<GeoPoint>() to RecordedPass(0L, emptyList())
            }
        }
            .map { (planned, pass) -> routeGeoJson(planned, pass) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                AssetGeoJson.build(emptyList())
            )

    /**
     * The planned track in the colours of the job: green for the part this pass has sprayed,
     * red for the part still waiting for a tank.
     *
     * The pass is dated from its newest fix rather than from the session row, so this stays a
     * pure function of the two lists it is given.
     */
    private fun routeGeoJson(planned: List<GeoPoint>, pass: RecordedPass): String {
        if (planned.size >= 2) {
            val stretches = Coverage.splitByCoverage(planned, listOf(pass))
                .map { stretch ->
                    AssetStretch(
                        colorHex = if (stretch.lastSprayedAtEpochMs != null) {
                            AssetColors.GREEN
                        } else {
                            AssetColors.RED
                        },
                        points = stretch.points
                    )
                }
            return AssetGeoJson.build(
                listOf(
                    AssetLine(
                        assetId = PLANNED_ID,
                        name = "Planned",
                        colorHex = AssetColors.RED,
                        points = planned,
                        stretches = stretches
                    )
                )
            )
        }

        return AssetGeoJson.build(
            listOf(
                AssetLine(
                    assetId = RECORDING_ID,
                    name = "Recording",
                    colorHex = AssetColors.RED,
                    points = pass.points,
                    // The line this pass drove, broken where the operator paused. The rest of a
                    // gap in the fixes is ground the pass crossed, and is drawn through - the
                    // two come from the same rule the plan's colours do.
                    stretches = pass.points.splitAtBreaks(pass.breaks).map { piece ->
                        AssetStretch(colorHex = AssetColors.RED, points = piece)
                    }
                )
            )
        )
    }

    /**
     * How long the chosen track is, or null when there is nothing chosen.
     *
     * The operator's own job is longer than the track they picked whenever they picked the
     * wrong one, and until now nothing on this screen said how long the track was - so
     * "Covered 100%" could only be taken on trust. Beside the distance driven so far it is
     * the comparison that matters. A pass that has just been saved keeps its length on the
     * screen for the same reason: it is the pair of numbers, driven and to drive, that says
     * whether the job was done.
     */
    val trackLengthM: StateFlow<Double?> = combine(plannedGeometry, _finished) { planned, done ->
        when {
            planned.size >= 2 -> polylineLengthMeters(planned)
            done != null && done.planned.size >= 2 -> polylineLengthMeters(done.planned)
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /**
     * The name suggested for the track a finished recording is about to become, or
     * null when no name is being asked for. The screen shows a dialog while this is
     * set, so a new line always gets a name before it is saved as a track.
     */
    private val _pendingTrackName = MutableStateFlow<String?>(null)
    val pendingTrackName: StateFlow<String?> = _pendingTrackName

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

        // A new pass is a map that should be moving with the operator: following comes back on
        // by itself whenever a session begins, so driving a line one-handed never starts with
        // the map parked somewhere else. Only a drag, or the control, takes it off again.
        viewModelScope.launch {
            tracking.map { it.sessionId }
                .distinctUntilChanged()
                .collect { sessionId -> if (sessionId != null) _following.value = true }
        }

        // Live coverage. collectLatest plus a short delay makes this a debounce: a
        // fresh fix cancels the pending calculation rather than queueing another.
        viewModelScope.launch {
            combine(livePass, plannedGeometry, _finished) { pass, planned, done ->
                Triple(planned, pass, done)
            }
                .collectLatest { (planned, pass, done) ->
                    if (planned.size < 2 || pass.points.isEmpty()) {
                        // Nothing to measure: no track chosen yet, or the track has just been
                        // sprayed and saved, in which case the number that belongs on the
                        // screen is the one the saved pass came to.
                        _coverage.value = done?.coverage
                        return@collectLatest
                    }
                    delay(COVERAGE_DEBOUNCE_MS)
                    _coverage.value = withContext(Dispatchers.Default) {
                        Coverage.coveredFraction(planned, pass.points, breaks = pass.breaks)
                    }
                }
        }

        viewModelScope.launch {
            // Reattach to a session left running, e.g. the app was killed mid-spray.
            val unfinished = recordings.findUnfinishedSession() ?: return@launch
            // Its fixes are still in the database, so how far it has got is too. A
            // screen that came back saying "0 m" would sit beside a coverage measured
            // from every fix of the pass - and the coverage would be the honest one.
            val progress = runCatching { recordings.recordingProgress(unfinished.id) }
                .getOrDefault(RecordingProgress.EMPTY)
            TrackingState.begin(
                sessionId = unfinished.id,
                startedAtEpochMs = unfinished.startedAtEpochMs,
                pointCount = progress.pointCount,
                distanceM = progress.distanceM
            )
            val status = runCatching { RecordingStatus.valueOf(unfinished.status) }
                .getOrDefault(RecordingStatus.RECORDING)
            TrackingState.setStatus(status)
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
        // Choosing a track is the start of a new job, so the pass that was on the screen gives
        // way to the one being set up. Cancelling the choice - assetId null - is not: that is
        // the operator backing out of the picker, with the finished pass still the last thing
        // they did.
        if (assetId != null) _finished.value = null
        viewModelScope.launch {
            if (assetId == null) {
                plannedGeometry.value = emptyList()
                _twoPasses.value = null
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

            // And what the line still owes, for a line that takes two passes: the card says so
            // before the operator sets off, rather than only after they have finished.
            refreshTwoPasses(assetId)
        }
    }

    /**
     * Reads what the chosen line still owes, and puts it on the card.
     *
     * Nothing at all for a line sprayed in one pass, and for a line with no geometry to measure
     * against: both are "there is nothing to say about two passes here".
     */
    private suspend fun refreshTwoPasses(assetId: Long?) {
        val id = assetId ?: run {
            _twoPasses.value = null
            return
        }
        val asset = runCatching { assetRepository.getAsset(id) }.getOrNull()
        if (asset == null || asset.passesRequired < AssetEntity.TWO_PASSES_REQUIRED) {
            _twoPasses.value = null
            return
        }
        val planned = plannedGeometry.value.takeIf { it.size >= 2 }
            ?: runCatching { assetRepository.getAssetGeometry(id) }.getOrDefault(emptyList())
        if (planned.size < 2) {
            _twoPasses.value = null
            return
        }

        val coverage = runCatching { assetRepository.getSprayCoverage(id) }
            .getOrDefault(AssetSprayCoverage.NONE)
        _twoPasses.value = TwoPasses.split(
            planned = planned,
            passes = coverage.passes,
            handSprayedAtEpochMs = coverage.lastWithoutRecordingAtEpochMs,
            separationM = asset.passSeparationM
        )
    }

    /**
     * Whether finishing needs the operator's word that both sides were done.
     *
     * Read before the recording is closed, because the answer is what decides whether the spray is
     * recorded: cancelling the dialog leaves the pass running, exactly as cancelling the naming
     * dialog does.
     */
    private suspend fun bothSidesQuestion(assetId: Long): Boolean {
        val sessionId = sessionIdOrNull() ?: return false
        val geometry = runCatching { recordings.getPoints(sessionId) }.getOrDefault(emptyList())
        val breaks = runCatching { recordings.getBreaks(sessionId) }.getOrDefault(emptyList())
        return twoPassOutcome(assetId, geometry, breaks, bothSides = false)
            ?.result
            ?.needsAnswer == true
    }

    /** What one pass has done to a line that takes two, as the recorder has to see it. */
    private data class TwoPassOutcome(
        /** The line after this pass: what is done, and what is still owed. */
        val result: TwoPasses.Result,
        /** The ground driven on the legs already finished, which the spray record carries. */
        val legsM: Double,
        /** Whether the operator has said the two passes were both sides. */
        val claimed: Boolean
    )

    /**
     * Reads the line's two-pass state with this pass on the end of it.
     *
     * Null when there is nothing to read: the chosen asset is not there, it takes one pass, or
     * there is no line to measure against. That is what makes "the job is done" the answer for
     * every line sprayed once, which is every line the app has ever known.
     *
     * Read twice over: the state before this pass is what says how many legs the job already had,
     * and the state after it is what says whether the job is done.
     */
    private suspend fun twoPassOutcome(
        assetId: Long,
        geometry: List<GeoPoint>,
        breaks: List<RecordingBreak>,
        bothSides: Boolean
    ): TwoPassOutcome? {
        val asset = runCatching { assetRepository.getAsset(assetId) }.getOrNull() ?: return null
        if (asset.passesRequired < AssetEntity.TWO_PASSES_REQUIRED) return null
        // The plan the screen is drawing, or - if Finish has been pressed before it got there, which
        // is a database read away - the plan as it is stored. Reading one pass over a line that
        // takes two as a whole job is the one mistake this must not make.
        val planned = plannedGeometry.value.takeIf { it.size >= 2 }
            ?: runCatching { assetRepository.getAssetGeometry(assetId) }.getOrDefault(emptyList())
        if (planned.size < 2 || geometry.isEmpty()) return null

        val coverage = runCatching { assetRepository.getSprayCoverage(assetId) }
            .getOrDefault(AssetSprayCoverage.NONE)
        val before = TwoPasses.split(
            planned = planned,
            passes = coverage.passes,
            handSprayedAtEpochMs = coverage.lastWithoutRecordingAtEpochMs,
            separationM = asset.passSeparationM
        )
        val pass = RecordedPass(
            // The same stamp the spray event will carry, so the pass and the record it becomes
            // are one thing by the clock as well as by session.
            atEpochMs = System.currentTimeMillis(),
            points = geometry,
            breaks = breaks,
            bothSidesClaimed = bothSides
        )
        val after = TwoPasses.split(
            planned = planned,
            passes = coverage.passes + pass,
            handSprayedAtEpochMs = coverage.lastWithoutRecordingAtEpochMs,
            separationM = asset.passSeparationM
        ) ?: return null

        return TwoPassOutcome(
            result = after,
            legsM = before?.pending.orEmpty().sumOf { polylineLengthMeters(it.points) },
            claimed = bothSides
        )
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
        // A new pass, so the last one leaves the screen. Following is turned on by the session
        // itself beginning - see the initialiser - rather than twice over here.
        _finished.value = null
        viewModelScope.launch {
            _message.value = null
            val sessionId = recordings.startRecording(
                name = defaultName(),
                assetId = _selectedAssetId.value
            )
            TrackingState.begin(sessionId, System.currentTimeMillis())
            TrackingService.start(context, sessionId)
        }
    }

    fun pause() = sessionIdOrNull()?.let { TrackingService.pause(context) }

    /**
     * Carrying on is a pass being driven again, so following comes back on: the operator
     * stopped for a reason that has now passed, and the map is theirs to look at until then.
     */
    fun resume() {
        _following.value = true
        sessionIdOrNull()?.let { TrackingService.resume(context) }
    }

    /** The follow control: the operator saying yes, or no, to the map moving with them. */
    fun setFollowing(value: Boolean) {
        _following.value = value
    }

    /**
     * The operator dragged the map while it was following them.
     *
     * Following gives way rather than fighting the drag at the next fix. It stays off until
     * they ask for it again, which the screen's control does - and the same control is what
     * tells them it is off, so the map does not appear to have stopped working.
     */
    fun onMapPanned() {
        _following.value = false
    }

    /**
     * The operator has left the recorder: another tab, back to the map, or the app put down.
     *
     * A saved pass belongs to the visit, not to the screen for good. Left behind, a card that is
     * waiting to start the next job still carries the last job's distance, its points and the
     * sentence about saving it, and so reads as if that pass were still the one in hand - and
     * because this view model lives as long as the activity does, the only way to clear it was to
     * kill the app.
     *
     * A pass still being driven is left alone. Leaving mid-spray loses nothing - the service owns
     * the recording and the database has its fixes - and the message on the card is that pass's
     * own, not a leftover.
     */
    fun onScreenLeft() {
        if (sessionIdOrNull() != null) return
        _finished.value = null
        _twoPasses.value = null
        _message.value = null
    }

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
        //
        // A line that takes two passes may have one question of its own first: whether two passes
        // that look the same were both sides. That is read from the fixes, so the path is a
        // coroutine even though nothing else about finishing is.
        val assetId = _selectedAssetId.value
        if (assetId == null) {
            _pendingTrackName.value = defaultTrackName()
            return
        }
        viewModelScope.launch {
            if (bothSidesQuestion(assetId)) {
                _pendingBothSides.value = true
            } else {
                completeFinish(newTrackName = null)
            }
        }
    }

    /**
     * From the dialog: whether both sides were done on the pass that has just ended.
     *
     * "Yes" is the operator's word for the second side, which is what closes the job and records
     * the spray; "no" finishes the pass and leaves the line owing the other one, with nothing
     * recorded. Both answers end the recording, because the pass itself has ended either way.
     */
    fun confirmBothSides(bothSides: Boolean) {
        completeFinish(newTrackName = null, bothSides = bothSides)
    }

    /** From the naming dialog: saves the recorded line as a new, named track. */
    fun confirmFinish(name: String) {
        completeFinish(newTrackName = name.trim().ifBlank { defaultTrackName() })
    }

    /** Dismisses whichever dialog is up without ending the recording. */
    fun cancelFinish() {
        _pendingTrackName.value = null
        _pendingBothSides.value = false
    }

    private fun completeFinish(newTrackName: String?, bothSides: Boolean = false) {
        val sessionId = sessionIdOrNull() ?: return
        viewModelScope.launch {
            _pendingTrackName.value = null
            _pendingBothSides.value = false

            val distanceM = TrackingState.current.distanceM
            val points = TrackingState.current.pointCount
            val sessionStart = TrackingState.current.startedAtEpochMs ?: System.currentTimeMillis()
            val existingAssetId = _selectedAssetId.value
            val lines = _rows.value.mapNotNull { row ->
                parseQuantityMl(row.quantityText)?.let { ml -> SprayProductQuantity(row.productId, ml) }
            }
            val coverageNow = _coverage.value

            // The pass as it stands this instant, kept whole. A moment from now the live
            // session is closed and cleared, and the screen should be showing what was just
            // done rather than nothing at all: the line it drove, the colours and the number
            // that came from the same split, and the distance, time and points it took.
            val plannedNow = plannedGeometry.value
            val numbersNow = TrackingState.current.summary(
                // The pass is finished by the time this is read off the screen, whatever the
                // service's last word on it was.
                status = RecordingStatus.FINISHED,
                endedAtEpochMs = System.currentTimeMillis()
            )

            // Read the geometry back from the database rather than the live flow: the
            // newest fix may not have reached the flow yet, and it must not be missing
            // from the track.
            val geometry = runCatching { recordings.getPoints(sessionId) }
                .getOrDefault(emptyList())
            // And the pauses with it, for the same reason: the saved pass is drawn, and its
            // colours are worked out, from what the database holds rather than from what the
            // screen happened to have collected.
            val breaks = runCatching { recordings.getBreaks(sessionId) }
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
            // Clearing the tracking state is what empties the drawing and stops the coverage:
            // these are the running session's fixes, and there is no longer one running.
            TrackingState.clear()
            plannedGeometry.value = emptyList()

            var message = "Saved $points ${if (points == 1) "point" else "points"}"
            message += when {
                newTrackName == null -> ""
                assetId != null -> " as \"$newTrackName\", which is on the Assets page now"
                trackError != null -> " - the track could not be saved: $trackError"
                else -> " - too few points for a track, so it is in Recordings only"
            }
            if (coverageNow != null) {
                message += " · covered ${formatCoveragePercent(coverageNow)} of the line"
            }

            // Whether this pass finishes the job, for a line that takes two passes. Read before
            // the spray is written, because it is what decides whether there is a spray to write:
            // a line walked once is not half sprayed, it is not sprayed, and its record waits for
            // the pass that finishes it. Null - and so no waiting - for everything else, which is
            // every line sprayed in one pass.
            val twoPass = assetId?.let { id -> twoPassOutcome(id, geometry, breaks, bothSides) }

            message += when {
                assetId == null -> ""
                // Not the job yet: a line walked once is not half sprayed, it is not sprayed, so
                // there is nothing to record and the card says what is still owed.
                twoPass != null && !twoPass.result.isComplete ->
                    " · " + TwoPassPhrase.notRecorded(twoPass.result)

                else -> runCatching {
                    sprays.recordSpray(
                        assetId = assetId,
                        products = lines,
                        // Both legs of a two-pass job are ground that was driven: the pass being
                        // finished, and the one it was waiting on.
                        distanceM = distanceM + (twoPass?.legsM ?: 0.0),
                        recordedSessionId = sessionId
                    )
                    // The operator's word for the second side, where the fixes could not say:
                    // written with the pass it was given for, so the question is not asked again.
                    if (twoPass != null && twoPass.claimed) recordings.claimBothSides(sessionId)
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
            // The sentence on the card follows the pass: the line that was just walked once now
            // reads as owing its other one, from the same reading the colours come from.
            _twoPasses.value = twoPass?.result
            _finished.value = FinishedPass(
                trackName = sprayTrackName,
                planned = plannedNow,
                recorded = geometry,
                breaks = breaks,
                coverage = coverageNow,
                summary = numbersNow
            )
            _selectedAssetId.value = null
            _rows.value = _rows.value.map { it.copy(quantityText = "") }
        }
    }

    private fun sessionIdOrNull(): Long? = TrackingState.current.sessionId

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
                        context = appContext,
                        locationSource = FusedLocationSource(appContext)
                    )
                }
            }
        }
    }
}
