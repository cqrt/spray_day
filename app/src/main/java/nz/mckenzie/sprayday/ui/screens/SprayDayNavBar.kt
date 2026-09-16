package nz.mckenzie.sprayday.ui.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * The five places the work starts from.
 *
 * The map, the assets being sprayed, recording a line, the recordings that prove it, and
 * the imagery kept for the places with no reception. Everything else - a drawn line, one
 * asset, a spray, an area being chosen - is opened from one of these and is not a tab.
 *
 * The names match MainActivity's destinations, which is how a tap down here becomes a
 * screen change up there without either file learning the other's vocabulary.
 */
enum class Tab(val label: String, val glyph: IconGlyph) {
    MAP("Map", IconGlyph.MAP),
    ASSETS("Assets", IconGlyph.ASSETS),
    RECORD("Record", IconGlyph.RECORD),
    RECORDINGS("Recordings", IconGlyph.RECORDINGS),
    OFFLINE("Offline", IconGlyph.OFFLINE)
}

/**
 * The bar along the bottom of every tab.
 *
 * Material 3 Expressive's navigation bar rather than the full-height one: it is shallower,
 * and it marks the current tab with a pill behind its icon. This is the one expressive
 * component the stable material3 artifact exposes to applications - see the note in
 * gradle/libs.versions.toml for why the expressive theme and motion are not here yet.
 *
 * Each screen draws its own, inside its own `Scaffold`, so the bar is part of the screen
 * rather than pinned over it: the maps get to fill the space they are given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SprayDayNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    ShortNavigationBar {
        Tab.entries.forEach { tab ->
            ShortNavigationBarItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = { AppIcon(tab.glyph) },
                label = { Text(tab.label) }
            )
        }
    }
}
