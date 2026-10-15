package nz.mckenzie.sprayday.domain.geo

import kotlin.math.roundToInt

/**
 * A line's height in the words the operator reads.
 *
 * *"≈520–664 m, up 140 m"* - how low and how high it goes, then the way it mostly goes. The "≈" is
 * there because the height came off a device rather than a survey, so this is a shape to read and not
 * a number to check work against; and a whole metre is as much as a device's height can be said to.
 */
object ElevationPhrase {

    /** The range and the way it goes, or null when there is no height to say anything about. */
    fun range(profile: ElevationProfile?): String? {
        profile ?: return null
        val way = when {
            profile.climbM > 0.0 && profile.climbM >= profile.descentM ->
                ", up ${metres(profile.climbM)} m"
            profile.descentM > 0.0 ->
                ", down ${metres(profile.descentM)} m"
            else -> ""
        }
        return "≈${metres(profile.lowestM)}–${metres(profile.highestM)} m$way"
    }

    /** A height in whole metres: a device's height is not accurate to the centimetre it prints. */
    private fun metres(value: Double): String = value.roundToInt().toString()
}
