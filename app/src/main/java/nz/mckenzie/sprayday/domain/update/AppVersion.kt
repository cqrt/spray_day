package nz.mckenzie.sprayday.domain.update

/**
 * A version of the app, as the release tags name them: `v0.6.2` is 0.6.2.
 *
 * Compared as numbers rather than as text, which is the whole point of having this type.
 * "0.6.10" is newer than "0.6.9", and a string comparison says the opposite - an operator
 * on an old build would be told they were up to date, which is the one failure an update
 * check cannot afford.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Reads a version out of the form the releases use, or null.
         *
         * Exactly three numeric parts: a two-part tag like "0.7" would otherwise compare
         * equal to 0.7.0 and could hide an update, and the release workflow refuses to
         * stamp a tag that is not three parts for the same reason.
         */
        fun parse(text: String): AppVersion? {
            val parts = text.trim().removePrefix("v").removePrefix("V").split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            if (numbers.any { it < 0 }) return null
            return AppVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}
