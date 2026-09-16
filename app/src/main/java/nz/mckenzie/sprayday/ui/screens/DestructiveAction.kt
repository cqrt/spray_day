package nz.mckenzie.sprayday.ui.screens

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The look of the taps that destroy something.
 *
 * Material 3 keeps the `error` colour for exactly this, and the app used it once already -
 * on the notification-permission warning in Settings. Everywhere else a delete was drawn
 * in the same green as Save, which made the one irreversible tap on the screen the one
 * that looked like the safe one. That matters here more than it would in a form on a desk:
 * this is a job done one-handed, with gloves on, next to a spray tank.
 *
 * The two colour functions are separate from the two buttons because the confirm button of
 * a dialog is already written out, and there only the colours change.
 */
@Composable
internal fun destructiveTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(
    contentColor = MaterialTheme.colorScheme.error
)

@Composable
internal fun destructiveOutlinedButtonColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
    contentColor = MaterialTheme.colorScheme.error
)

@Composable
internal fun DestructiveTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        colors = destructiveTextButtonColors()
    ) {
        Text(text)
    }
}

@Composable
internal fun DestructiveOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = destructiveOutlinedButtonColors()
    ) {
        Text(text)
    }
}
