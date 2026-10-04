package nz.mckenzie.sprayday.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the editor is being served, at what address, and the name a dropped track file travels under.
 *
 * The address is the state rather than a boolean beside it, and that is the point: there is no way
 * to be "on" with nothing listening, because "on" *is* having an address to show. The Settings card
 * reads this, so a switch that cannot find the address to serve on comes back off rather than
 * sitting there claiming to work - the failure the operator would otherwise discover on the laptop.
 *
 * The two track-file facts are here for the one thing the page has to get right without being able to
 * check it: the name a dropped file travels under, and how large a file the phone will take. Both are
 * the phone's answers rather than the page's guesses, and a page that guessed the name would send a
 * file the phone found empty - which reads exactly like a GPX or KML file with nothing in it.
 *
 * A process-wide holder in the same spirit as `TrackingState` and `TileServerHolder`: there is one
 * editor, one address and one token, the token lives only here and in the running server, and
 * turning the switch off ends the run because the address goes with it.
 */
object WebEditorState {

    private val _url = MutableStateFlow<String?>(null)

    /** The address to open on the computer, or null when the editor is off. */
    val url: StateFlow<String?> = _url.asStateFlow()

    val isOn: Boolean get() = _url.value != null

    /** What a dropped GPX or KML file is called in the body the page sends: see [WebEditorServer.GPX_FIELD]. */
    val gpxField: String get() = WebEditorServer.GPX_FIELD

    /** How large a track file the phone will take: see [WebEditorServer.MAX_GPX_BYTES]. */
    val maxGpxBytes: Int get() = WebEditorServer.MAX_GPX_BYTES

    internal fun servingAt(url: String) {
        _url.value = url
    }

    internal fun off() {
        _url.value = null
    }
}
