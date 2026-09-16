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
                FilterChip(
                    selected = choice == selected,
                    onClick = { onChoose(choice) },
                    label = { Text(text(choice)) }
                )
            }
        }
    }
}
