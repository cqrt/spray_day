package nz.mckenzie.sprayday.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading GitHub's answer, and choosing the file to install.
 *
 * The fixture is a real answer's shape rather than a convenient one: GitHub sends a couple
 * of dozen fields per release and per asset that this app has no use for, and a release
 * must not be refused because one of them changed.
 */
class ReleaseCatalogTest {

    private val running = AppVersion(0, 6, 2)

    private val releaseBody = """
        {
          "url": "https://api.github.com/repos/cqrt/spray_day/releases/1",
          "id": 1,
          "tag_name": "v0.6.3",
          "target_commitish": "main",
          "name": "Spray Day 0.6.3",
          "draft": false,
          "prerelease": false,
          "author": { "login": "github-actions[bot]", "id": 41898282 },
          "published_at": "2026-10-01T09:00:00Z",
          "assets": [
            {
              "url": "https://api.github.com/repos/cqrt/spray_day/releases/assets/10",
              "id": 10,
              "name": "spray-day-0.6.3-armeabi-v7a.apk",
              "content_type": "application/vnd.android.package-archive",
              "state": "uploaded",
              "size": 12400000,
              "download_count": 3,
              "browser_download_url": "https://github.com/cqrt/spray_day/releases/download/v0.6.3/spray-day-0.6.3-armeabi-v7a.apk"
            },
            {
              "id": 11,
              "name": "spray-day-0.6.3-arm64-v8a.apk",
              "size": 16400000,
              "browser_download_url": "https://github.com/cqrt/spray_day/releases/download/v0.6.3/spray-day-0.6.3-arm64-v8a.apk"
            },
            {
              "id": 12,
              "name": "spray-day-0.6.3-universal.apk",
              "size": 50200000,
              "browser_download_url": "https://github.com/cqrt/spray_day/releases/download/v0.6.3/spray-day-0.6.3-universal.apk"
            },
            {
              "id": 13,
              "name": "spray-day-0.6.3.aab",
              "size": 23300000,
              "browser_download_url": "https://github.com/cqrt/spray_day/releases/download/v0.6.3/spray-day-0.6.3.aab"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `the fields this app does not use do not stop it reading the ones it does`() {
        val release = ReleaseCatalog.parseRelease(releaseBody)

        assertNotNull("the release parsed", release)
        assertEquals("v0.6.3", release!!.tagName)
        assertEquals(4, release.assets.size)
    }

    @Test
    fun `a newer release offers the APK built for this phone`() {
        val update = ReleaseCatalog.updateFor(
            ReleaseCatalog.parseRelease(releaseBody),
            current = running,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
        )

        assertNotNull(update)
        assertEquals(AppVersion(0, 6, 3), update!!.version)
        assertEquals("spray-day-0.6.3-arm64-v8a.apk", update.asset.name)
        assertEquals("16.4 MB", update.sizeLabel)
    }

    @Test
    fun `a 32-bit phone gets the 32-bit APK, not the arm64 one`() {
        val update = ReleaseCatalog.updateFor(
            ReleaseCatalog.parseRelease(releaseBody),
            current = running,
            supportedAbis = listOf("armeabi-v7a", "armeabi")
        )

        assertEquals("spray-day-0.6.3-armeabi-v7a.apk", update?.asset?.name)
    }

    @Test
    fun `an architecture the release does not carry falls back to the universal APK`() {
        val update = ReleaseCatalog.updateFor(
            ReleaseCatalog.parseRelease(releaseBody),
            current = running,
            supportedAbis = listOf("x86_64")
        )

        assertEquals("spray-day-0.6.3-universal.apk", update?.asset?.name)
    }

    @Test
    fun `a release with no APK this phone can install offers nothing`() {
        val noApks = """{"tag_name":"v0.6.3","assets":[{"name":"notes.txt","browser_download_url":"https://example.invalid/notes.txt"}]}"""

        assertNull(
            ReleaseCatalog.updateFor(
                ReleaseCatalog.parseRelease(noApks),
                current = running,
                supportedAbis = listOf("x86_64")
            )
        )
    }

    @Test
    fun `an asset with no download URL is not offered`() {
        val broken = """{"tag_name":"v0.6.3","assets":[{"name":"spray-day-0.6.3-arm64-v8a.apk","browser_download_url":""}]}"""

        assertNull(
            ReleaseCatalog.updateFor(
                ReleaseCatalog.parseRelease(broken),
                current = running,
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun `there is nothing to install when the release is not newer`() {
        val sameVersion = releaseBody.replace("v0.6.3", "v0.6.2")
        val older = releaseBody.replace("v0.6.3", "v0.5.9")

        assertNull(
            ReleaseCatalog.updateFor(
                ReleaseCatalog.parseRelease(sameVersion), running, listOf("arm64-v8a")
            )
        )
        assertNull(
            ReleaseCatalog.updateFor(ReleaseCatalog.parseRelease(older), running, listOf("arm64-v8a"))
        )
    }

    @Test
    fun `a draft or a pre-release is never offered, whatever it is called`() {
        val draft = releaseBody.replace("\"draft\": false", "\"draft\": true")
        val prerelease = releaseBody.replace("\"prerelease\": false", "\"prerelease\": true")

        assertNull(
            ReleaseCatalog.updateFor(ReleaseCatalog.parseRelease(draft), running, listOf("arm64-v8a"))
        )
        assertNull(
            ReleaseCatalog.updateFor(ReleaseCatalog.parseRelease(prerelease), running, listOf("arm64-v8a"))
        )
    }

    @Test
    fun `an answer that is not a release at all is refused rather than crashing`() {
        assertNull(
            "a rate-limit page is not a release",
            ReleaseCatalog.updateFor(
                ReleaseCatalog.parseRelease("<html>rate limited</html>"),
                running,
                listOf("arm64-v8a")
            )
        )
        assertNull(
            "and neither is an empty object",
            ReleaseCatalog.updateFor(ReleaseCatalog.parseRelease("{}"), running, listOf("arm64-v8a"))
        )
    }

    @Test
    fun `the title falls back to the version when the release has no name`() {
        val unnamed = releaseBody.replace("\"name\": \"Spray Day 0.6.3\",", "")

        val update = ReleaseCatalog.updateFor(
            ReleaseCatalog.parseRelease(unnamed),
            current = running,
            supportedAbis = listOf("arm64-v8a")
        )

        assertEquals("Spray Day 0.6.3", update?.title)
    }
}
