package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What a list says when there is nothing in it yet.
 *
 * An empty list used to be one grey sentence on a blank page, which reads like something
 * went wrong rather than like nothing has been done yet - and on the recordings screen,
 * which is evidence the operator will go looking for, that is the wrong thing to say.
 *
 * Material 3's answer is what this is: a large icon, a headline, a sentence saying what
 * would appear here, and the action that would put something here. The action is the point
 * of it - the shortest path from "there is nothing" to "there is something".
 */
@Composable
internal fun EmptyState(
    glyph: IconGlyph,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppIcon(
            glyph = glyph,
            dimension = 48.dp,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}
