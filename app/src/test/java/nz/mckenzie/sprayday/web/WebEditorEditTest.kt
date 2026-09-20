package nz.mckenzie.sprayday.web

import nz.mckenzie.sprayday.data.db.AssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone does with an edit from a desk.
 *
 * The two things held here are the two that make the arrangement safe. The desk cannot write a field
 * it did not send, so a spray recorded or an archive made on the phone while a card was open
 * survives an edit from a laptop; and it cannot write one somebody else has already changed, which
 * is what the version is for. The wording of a refusal is not invented here either - a field out of
 * range is refused in [nz.mckenzie.sprayday.ui.AssetEdits]'s own words, and one of these tests holds
 * it to that rather than to a second copy of the rules.
 */
class WebEditorEditTest {

    private val asset = AssetEntity(
        id = 7L,
        name = "Estuary road",
        kind = "ROAD",
        shape = "LINE",
        method = "BOOM",
        groupId = 3L,
        notes = "Both edges",
        intervalDays = 120,
        swathWidthM = 12.5,
        passesRequired = 2,
        passSeparationM = 3.0,
        active = true,
        createdAtEpochMs = 1_700_000_000_000L,
        lastSprayedAtEpochMs = 1_750_000_000_000L,
        lengthM = 3300.0
    )

    private val blockName = "Estuary"

    /** The body a page sends back after opening this asset and changing one or two things. */
    private fun body(
        version: String = WebEditorVersion.of(asset, blockName),
        name: String = asset.name,
        kind: String = asset.kind,
        shape: String = asset.shape,
        method: String = asset.method,
        block: String? = blockName,
        intervalDays: String = asset.intervalDays.toString(),
        swathWidthM: String = asset.swathWidthM.toString(),
        passes: Int = asset.passesRequired,
        separation: String = asset.passSeparationM.toString(),
        notes: String? = asset.notes
    ): String = buildString {
        append("""{"name":"$name","kind":"$kind","shape":"$shape","method":"$method",""")
        append(""""blockName":${if (block == null) "null" else "\"$block\""},""")
        append(""""intervalDays":"$intervalDays","swathWidthM":"$swathWidthM",""")
        append(""""passesRequired":$passes,"passSeparationM":"$separation",""")
        append(""""notes":${if (notes == null) "null" else "\"$notes\""},"version":"$version"}""")
    }

    private fun ok(editBody: String?): WebEditorEditResult.Ok {
        val result = WebEditorEdits.apply(asset, blockName, editBody)
        assertTrue("expected this to be taken: $result", result is WebEditorEditResult.Ok)
        return result as WebEditorEditResult.Ok
    }

    private fun refused(editBody: String?): WebEditorEditResult.Refused {
        val result = WebEditorEdits.apply(asset, blockName, editBody)
        assertTrue("expected this to be refused: $result", result is WebEditorEditResult.Refused)
        return result as WebEditorEditResult.Refused
    }

    @Test
    fun `a good edit comes back as the row to store, and the block it belongs in`() {
        val result = ok(body(name = "Estuary road, lower", intervalDays = "90"))

        assertEquals("Estuary road, lower", result.asset.name)
        assertEquals(90, result.asset.intervalDays)
        assertEquals("Estuary", result.blockName)
        assertEquals("the id is the phone's, not the desk's to send", 7L, result.asset.id)
    }

    @Test
    fun `a field the desk never sent survives the edit`() {
        val edited = ok(body(name = "Estuary road, lower")).asset

        // The whole promise of writing through the row the phone holds now: the page changes details,
        // not records. A spray logged on the phone while the card was open is still there afterwards,
        // and so is everything else the page was never shown.
        assertEquals(1_750_000_000_000L, edited.lastSprayedAtEpochMs)
        assertEquals(asset.active, edited.active)
        assertEquals(asset.createdAtEpochMs, edited.createdAtEpochMs)
        assertEquals(3300.0, edited.lengthM, 0.0)
        assertEquals(asset.groupId, edited.groupId)
    }

    @Test
    fun `the version follows the fields the desk may write, and only those`() {
        val version = WebEditorVersion.of(asset, blockName)

        assertNotEquals(version, WebEditorVersion.of(asset.copy(name = "Renamed"), blockName))
        assertNotEquals(version, WebEditorVersion.of(asset, "Home block"))
        assertNotEquals(version, WebEditorVersion.of(asset.copy(intervalDays = 90), blockName))
        assertNotEquals(version, WebEditorVersion.of(asset.copy(kind = "TRACK"), blockName))

        // A field the desk cannot write must not refuse an edit that could not overwrite it: spraying
        // a line, archiving it or dragging a vertex does not stop somebody renaming it from the desk.
        assertEquals(
            version,
            WebEditorVersion.of(asset.copy(lastSprayedAtEpochMs = 1_760_000_000_000L), blockName)
        )
        assertEquals(version, WebEditorVersion.of(asset.copy(active = false), blockName))
        assertEquals(version, WebEditorVersion.of(asset.copy(lengthM = 1234.0), blockName))
    }

    @Test
    fun `an edit written against the asset as it was is refused, and says why`() {
        val stale = refused(body(version = WebEditorVersion.of(asset.copy(name = "Renamed"), blockName)))

        assertEquals(WebEditorRefusal.STALE, stale.refusal)
        assertTrue("says where it changed: ${stale.message}", stale.message.contains("phone"))
        assertTrue("says nothing was saved: ${stale.message}", stale.message.contains("nothing was saved"))
    }

    @Test
    fun `a field out of range is refused in the app's own words`() {
        // These sentences are `ui/AssetEdits`'s, word for word: the desk does not get a second set of
        // rules, and this is what holds the two together.
        assertEquals(
            "Give it a name so it can be found later",
            refused(body(name = "   ")).message
        )
        assertEquals(
            "Days between sprays must be a whole number",
            refused(body(intervalDays = "every so often")).message
        )
        assertTrue(
            "says what the range is: ${refused(body(swathWidthM = "300")).message}",
            refused(body(swathWidthM = "300")).message.contains("100")
        )
    }

    @Test
    fun `a kind, a shape or a method the phone does not know is refused, not defaulted`() {
        // Named states arrive already decided, so a name this build does not know is a refusal. Let
        // through, each of these would quietly become a track, a line or "not set".
        assertTrue(refused(body(kind = "PADDOCK")).message.contains("PADDOCK"))
        assertTrue(refused(body(shape = "CIRCLE")).message.contains("CIRCLE"))
        assertTrue(refused(body(method = "DRONE")).message.contains("DRONE"))
    }

    @Test
    fun `an edit that cannot be read at all is refused rather than thrown`() {
        assertEquals(WebEditorRefusal.INVALID, refused(null).refusal)
        assertEquals(WebEditorRefusal.INVALID, refused("").refusal)
        assertEquals(WebEditorRefusal.INVALID, refused("not json at all").refusal)
        // The version is how the phone knows what the desk read, so a body without one is not an
        // edit the phone can take: there is nothing to compare it against.
        assertEquals(WebEditorRefusal.INVALID, refused("""{"name":"Estuary road"}""").refusal)
    }

    @Test
    fun `something in the body this build does not know about is ignored, not refused`() {
        // A page from a later build sending a field this one has never heard of must not be a page
        // that cannot save at all. What it changes is what this build knows how to change.
        val withExtra = ok(body().dropLast(1) + ""","geometry":[[173.8,-41.5],[173.9,-41.6]]}""")

        assertEquals(asset.name, withExtra.asset.name)
    }

    @Test
    fun `the refusal table is the contract, so it is pinned`() {
        assertEquals(404, WebEditorRefusal.MISSING.status)
        assertEquals("not-found", WebEditorRefusal.MISSING.reason)
        assertEquals(409, WebEditorRefusal.STALE.status)
        assertEquals("stale", WebEditorRefusal.STALE.reason)
        assertEquals(400, WebEditorRefusal.INVALID.status)
        assertEquals("invalid", WebEditorRefusal.INVALID.reason)
    }
}
