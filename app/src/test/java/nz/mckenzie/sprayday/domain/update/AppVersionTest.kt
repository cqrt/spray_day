package nz.mckenzie.sprayday.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How versions are read and ordered.
 *
 * The ordering is the part that matters: this decides whether an operator is told there is
 * a newer build, so "10" has to beat "9" even though "10" sorts before it as text.
 */
class AppVersionTest {

    @Test
    fun `a release tag is read with or without its v`() {
        assertEquals(AppVersion(0, 6, 2), AppVersion.parse("v0.6.2"))
        assertEquals(AppVersion(0, 6, 2), AppVersion.parse("0.6.2"))
        assertEquals(AppVersion(1, 0, 0), AppVersion.parse("V1.0.0"))
        assertEquals(AppVersion(0, 6, 2), AppVersion.parse("  v0.6.2  "))
    }

    @Test
    fun `a tenth patch is newer than a ninth, which text comparison gets wrong`() {
        val ninth = AppVersion.parse("0.6.9")!!
        val tenth = AppVersion.parse("0.6.10")!!

        assertTrue("0.6.10 must sort above 0.6.9", tenth > ninth)
        assertTrue("and the other way round", ninth < tenth)
    }

    @Test
    fun `major and minor come before patch`() {
        assertTrue(AppVersion.parse("0.7.0")!! > AppVersion.parse("0.6.99")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("0.99.99")!!)
    }

    @Test
    fun `the same version is not newer than itself`() {
        assertEquals(AppVersion.parse("0.6.2"), AppVersion.parse("v0.6.2"))
        assertTrue(AppVersion.parse("0.6.2")!! <= AppVersion.parse("0.6.2")!!)
    }

    @Test
    fun `a tag that is not three numeric parts is refused rather than guessed at`() {
        assertNull("a two-part tag could hide an update", AppVersion.parse("0.7"))
        assertNull("four parts are not what the releases use", AppVersion.parse("0.6.2.1"))
        assertNull("a named release is not a version", AppVersion.parse("latest"))
        assertNull("nor is a pre-release suffix", AppVersion.parse("0.6.2-beta"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun `a version says itself back the way a tag does, minus the v`() {
        assertEquals("0.6.2", AppVersion.parse("v0.6.2").toString())
    }
}
