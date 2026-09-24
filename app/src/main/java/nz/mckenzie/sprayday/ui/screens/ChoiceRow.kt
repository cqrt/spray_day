package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * One chip of a single-choice row: the Material chip, with the tick the sun demands.
 *
 * Shared by the rows the forms offer and by the asset list's filter, so the two cannot drift apart -
 * a chip that means "chosen" on one screen and "not chosen" on another is the kind of thing that is
 * only noticed with a spray pack on.
 */
@Composable
internal fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        // Material marks the chosen chip with a tick as well as the tint. Out in the sun, a tint is
        // not enough to read, and this is a screen the operator uses in exactly that light.
        leadingIcon = if (selected) {
            { AppIcon(glyph = IconGlyph.CHECK, dimension = 18.dp) }
        } else {
            null
        },
        label = { Text(label) }
    )
}

/**
 * One labelled row of single-choice chips.
 *
 * Two screens ask the same three questions - what an asset is, what shape it is, and
 * how it gets sprayed - and they differ only in their words. Sharing one layout keeps
 * them from drifting apart, and keeps the chips wrapping the same way on a narrow
 * phone with gloves on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceRow(
    label: String,
    choices: List<T>,
    selected: T,
    onChoose: (T) -> Unit,
    text: (T) -> String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            choices.forEach { choice ->
                ChoiceChip(
                    selected = choice == selected,
                    label = text(choice),
                    onClick = { onChoose(choice) }
                )
            }
        }
    }
}
