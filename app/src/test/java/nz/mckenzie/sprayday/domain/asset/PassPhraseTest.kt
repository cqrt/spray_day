package nz.mckenzie.sprayday.domain.asset

import nz.mckenzie.sprayday.data.db.AssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the app says how many passes a line takes.
 *
 * The words are the only place the operator is told a track needs walking twice, and the number
 * behind them is the asset's own field: if "Two passes" in the picker did not mean two on the
 * asset, the operator would choose it and get a line the app thinks is done after one walk.
 */
class PassPhraseTest {

    @Test
    fun `the words mean the numbers the asset keeps`() {
        assertEquals(AssetEntity.DEFAULT_PASSES_REQUIRED, PassPhrase.ONE_PASS)
        assertEquals(AssetEntity.TWO_PASSES_REQUIRED, PassPhrase.TWO_PASSES)
        assertEquals(AssetEntity.MAX_PASSES_REQUIRED, PassPhrase.choices.max())
    }

    @Test
    fun `the two counts read as what they are`() {
        assertEquals("One pass", PassPhrase.choice(PassPhrase.ONE_PASS))
        assertEquals("Two passes", PassPhrase.choice(PassPhrase.TWO_PASSES))
    }

    @Test
    fun `a line sprayed once says nothing about passes on the asset page`() {
        assertNull(
            "every line in the app is one pass, so saying so would be noise",
            PassPhrase.detail(PassPhrase.ONE_PASS, null)
        )
    }

    @Test
    fun `a line sprayed twice says so, and how far apart the two run when that is known`() {
        assertEquals("Two passes", PassPhrase.detail(PassPhrase.TWO_PASSES, null))
        assertEquals("Two passes, about 3 m apart", PassPhrase.detail(PassPhrase.TWO_PASSES, 3.0))
        assertEquals(
            "Two passes, about 2.5 m apart",
            PassPhrase.detail(PassPhrase.TWO_PASSES, 2.5)
        )
    }
}
