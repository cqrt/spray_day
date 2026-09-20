package nz.mckenzie.sprayday.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the editor is being served, and at what address.
 *
 * The address is the state rather than a boolean beside it, and that is the point: there is no way
 * to be "on" with nothing listening, because "on" *is* having an address to show. The Settings card
 * reads this, so a switch that cannot find the address to serve on comes back off rather than
 * sitting there claiming to work - the failure the operator would otherwise discover on the laptop.
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

    internal fun servingAt(url: String) {
        _url.value = url
    }

    internal fun off() {
        _url.value = null
    }
}
