package nz.mckenzie.sprayday.domain.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One file attached to a GitHub release. */
@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0L
)

/**
 * A GitHub release, as much of it as this app has an opinion about.
 *
 * Everything is defaulted, and the parser ignores keys it does not know: GitHub's answer
 * carries a couple of dozen fields this app has no use for, and a release being refused
 * because GitHub added another one would be a silly way to lose an update.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList()
)

/** A newer version, and the file this phone would install to get it. */
data class AvailableUpdate(
    val version: AppVersion,
    val title: String,
    val asset: ReleaseAsset
) {
    /** "6.0 MB", for the screen that has to say what a download will cost. */
    val sizeLabel: String
        get() = when {
            asset.size <= 0L -> "unknown size"
            asset.size < 1_000_000L -> "${asset.size / 1000} kB"
            else -> "%.1f MB".format(asset.size / 1_000_000.0)
        }
}

/**
 * Turns GitHub's answer into "should this phone install something, and from where".
 *
 * Releases are published per architecture plus a universal build (see the release
 * workflow), so picking the file is a decision rather than a detail: the universal APK is
 * about four times the size, which on a farm connection is the difference between a
 * download and an abandoned one.
 */
object ReleaseCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    /** Null when the body is not a release at all, or not one GitHub would serve. */
    fun parseRelease(body: String): GitHubRelease? =
        runCatching { json.decodeFromString<GitHubRelease>(body) }.getOrNull()

    /**
     * The update to offer, or null when there is nothing newer *and installable*.
     *
     * Null covers all of these on purpose, because the screen says the same thing about
     * each of them - "you are up to date" - and the operator does not need to know which
     * one it was: no release, a draft, a pre-release, a tag that is not a version, a
     * version that is not newer, or a release with no APK this phone can install.
     */
    fun updateFor(
        release: GitHubRelease?,
        current: AppVersion,
        supportedAbis: List<String>
    ): AvailableUpdate? {
        if (release == null || release.draft || release.prerelease) return null
        val version = AppVersion.parse(release.tagName) ?: return null
        if (version <= current) return null
        val asset = assetFor(release.assets, supportedAbis) ?: return null
        return AvailableUpdate(
            version = version,
            title = release.name.ifBlank { "Spray Day $version" },
            asset = asset
        )
    }

    /**
     * The APK for this phone: the first of its own architectures the release carries,
     * otherwise the universal build.
     *
     * The order of [supportedAbis] is the device's own order of preference, and the
     * architecture names are Android's, which is also what the release workflow names the
     * files after - so this is a name match rather than a guess. Nothing is returned when
     * the release has neither: installing some other architecture's APK would fail at
     * install time with a message about native libraries, which is worse than saying
     * there is no update for this phone.
     */
    fun assetFor(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() }
        supportedAbis.forEach { abi ->
            apks.firstOrNull { it.name.endsWith("-$abi.apk", ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
    }
}
