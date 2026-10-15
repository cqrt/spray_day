package nz.mckenzie.sprayday.domain.geo

/**
 * One point of a line's height: how far along the line it was taken, and how high it was.
 *
 * [alongM] is measured along the whole line - every point, whether it had a height or not - so a
 * chart drawn from these samples has the line's real distance under it rather than the distance
 * between only the points a file happened to give a height for.
 */
data class ElevationSample(val alongM: Double, val heightM: Double)

/**
 * The height of a line, worked out from the line's own points.
 *
 * This is the arithmetic a height is worth, kept in one place so the phone and the desk cannot
 * disagree about it, and kept pure so a JVM test holds it rather than an eye. Only the points that
 * carry a height take part: a line drawn on a map has none, and a file usually has some on every
 * point.
 *
 * **A gap is read across, not as a cliff.** With a height missing in the middle, the climb between
 * the last height and the next is counted as one step, because the ground between them did rise or
 * fall by that much; what the app cannot say is *where* on the gap it happened. The same reading the
 * map gives a pass that lost its fixes: the recording is the line it drew, not the fixes it held.
 *
 * [of] returns null when there is nothing to draw - a line with fewer than two heights has no shape
 * to show, and saying "0 m of climb" over one known point would be a measurement where there is only
 * an absence.
 */
data class ElevationProfile(
    val lowestM: Double,
    val highestM: Double,
    val climbM: Double,
    val descentM: Double,
    val samples: List<ElevationSample>
) {
    /** How much of the line's height there is: the highest point less the lowest. */
    val rangeM: Double get() = highestM - lowestM

    companion object {

        /**
         * The height of [points], or null when fewer than two of them carry one.
         *
         * The line is read in order and heights are compared between the points that have them, so a
         * missing height is a step rather than a break. `alongM` still steps the line's whole length,
         * so a sample is placed where the point it came from is.
         */
        fun of(points: List<GeoPoint>): ElevationProfile? {
            var along = 0.0
            var previous: GeoPoint? = null
            var climb = 0.0
            var descent = 0.0
            val samples = ArrayList<ElevationSample>()

            for (point in points) {
                previous?.let { along += haversineMeters(it.lat, it.lng, point.lat, point.lng) }
                previous = point
                val height = point.altitudeM ?: continue
                samples.lastOrNull()?.let { last ->
                    val delta = height - last.heightM
                    if (delta > 0) climb += delta else descent -= delta
                }
                samples += ElevationSample(along, height)
            }

            if (samples.size < 2) return null
            return ElevationProfile(
                lowestM = samples.minOf { it.heightM },
                highestM = samples.maxOf { it.heightM },
                climbM = climb,
                descentM = descent,
                samples = samples
            )
        }
    }
}
