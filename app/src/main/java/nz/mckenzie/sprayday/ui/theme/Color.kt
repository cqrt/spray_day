package nz.mckenzie.sprayday.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The brand seed. This green is also the map's "not due" colour, so the buttons and the
// traffic light are the same green rather than two greens that nearly match.
val GreenPrimary = Color(0xFF2E7D32)
val GreenPrimaryDark = Color(0xFF81C784)

// Semantic colours for the track due-status traffic light.
// Green = not due, Yellow = due soon, Red = overdue. These are also used for
// the track strokes on the map, so text on top uses white.
val StatusGreen = Color(0xFF2E7D32)
val StatusYellow = Color(0xFFF9A825)
val StatusRed = Color(0xFFC62828)
val StatusUnknown = Color(0xFF757575)

/**
 * The light scheme.
 *
 * Every role is set, not just primary, secondary and tertiary. Setting three of them
 * left every container and surface role on Material's baseline palette, which is
 * lavender - and that lavender was the legend card, the asset rows, the offline card
 * and the body of every dialog.
 *
 * The tones are not hand-picked. They are the Material 3 tonal palettes for seed
 * #2E7D32 (HCT 145.6 / 53.0 / 46.3), read out of Google's own colour utilities with the
 * Tonal Spot variant, so each role is the tone Material specifies for it: primary 40,
 * containers 90 and 10, surfaces 98 down to 90, outline 50, error 40.
 *
 * Three deliberate departures from that output:
 *
 * - `primary` stays the brand green rather than the generated tone 40 (#3C6939), so the
 *   buttons keep the exact green of the "not due" dots on the map.
 * - `surfaceTint` follows `primary`, so elevated surfaces tint green rather than towards
 *   a green nobody else uses.
 * - the tertiary family comes from a second palette seeded with the old earth brown
 *   (#8D6E63), because that role was the app's warm accent. The tonal spot variant
 *   rotates the tertiary hue 60 degrees, which is why that accent lands on khaki.
 *
 * The expressive *colour* variant of the same generator was tried and rejected: it
 * rotates the seed hue by 240 degrees and returns primary #94483F, a brick red. This is
 * a green app. Expressive here means expressive motion, shape and type - see Theme.kt.
 */
internal val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBCF0B4),
    onPrimaryContainer = Color(0xFF002204),
    inversePrimary = Color(0xFFA1D39A),
    secondary = Color(0xFF52634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E8CE),
    onSecondaryContainer = Color(0xFF111F0F),
    tertiary = Color(0xFF695E2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2E3A8),
    onTertiaryContainer = Color(0xFF211B00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FBF1),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFF7FBF1),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDEE5D8),
    onSurfaceVariant = Color(0xFF424940),
    surfaceTint = GreenPrimary,
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEFF2E9),
    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2C9BD),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7FBF1),
    surfaceDim = Color(0xFFD8DBD2),
    surfaceContainer = Color(0xFFECEFE6),
    surfaceContainerHigh = Color(0xFFE6E9E0),
    surfaceContainerHighest = Color(0xFFE0E4DB),
    surfaceContainerLow = Color(0xFFF1F5EB),
    surfaceContainerLowest = Color(0xFFFFFFFF)
)

/** The dark scheme, the same roles and the same provenance as [LightColors]. */
internal val DarkColors = darkColorScheme(
    primary = GreenPrimaryDark,
    // Not white: on a light green the label has to go dark, which is what the generated
    // tone 20 is for. Leaving this unset is what put dark purple labels on light green.
    onPrimary = Color(0xFF0A390F),
    primaryContainer = Color(0xFF245024),
    onPrimaryContainer = Color(0xFFBCF0B4),
    inversePrimary = Color(0xFF3C6939),
    secondary = Color(0xFFBACCB3),
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3B4B38),
    onSecondaryContainer = Color(0xFFD6E8CE),
    tertiary = Color(0xFFD5C78E),
    onTertiary = Color(0xFF383005),
    tertiaryContainer = Color(0xFF50471A),
    onTertiaryContainer = Color(0xFFF2E3A8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF10140F),
    onBackground = Color(0xFFE0E4DB),
    surface = Color(0xFF10140F),
    onSurface = Color(0xFFE0E4DB),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    surfaceTint = GreenPrimaryDark,
    inverseSurface = Color(0xFFE0E4DB),
    inverseOnSurface = Color(0xFF2D322C),
    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363A34),
    surfaceDim = Color(0xFF10140F),
    surfaceContainer = Color(0xFF1D211B),
    surfaceContainerHigh = Color(0xFF272B25),
    surfaceContainerHighest = Color(0xFF323630),
    surfaceContainerLow = Color(0xFF191D17),
    surfaceContainerLowest = Color(0xFF0B0F0A)
)

