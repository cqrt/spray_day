package nz.mckenzie.sprayday.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The Material 3 theme.
 *
 * The colours come from Color.kt, where every role is set rather than three of them.
 *
 * Typography is Material's own scale, untouched. This module used to override two text
 * styles by hand (a semibold title and a body style), which meant the app ran on a
 * half-Material type scale; the parts that were overridden are the parts Material already
 * sets, so the overrides are gone rather than kept alongside the rest of the scale.
 *
 * The one thing that is *not* here is [androidx.compose.material3.MaterialExpressiveTheme]
 * and its expressive motion scheme. Both are declared `internal` in the stable material3
 * artifact, so application code cannot reference them at all; they are public only from
 * material3 1.5.0-alpha, which requires compileSdk 37 and AGP 9.1.0. The expressive
 * *components* that are reachable on this toolchain are used screen by screen instead -
 * see the note in gradle/libs.versions.toml.
 */
@Composable
fun SprayDayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Map detail is easier to read on a predictable palette, so dynamic colour
    // is off by default.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}


