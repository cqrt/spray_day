package nz.mckenzie.sprayday.web

import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    /** The line as the phone holds it, which is half of what the version the desk quotes is made of. */
    private val path = listOf(GeoPoint(-41.5, 173.8), GeoPoint(-41.6, 173.9))

    private fun pointJson(points: List<GeoPoint>): String =
        points.joinToString(prefix = "[", postfix = "]") { """{"lat":${it.lat},"lng":${it.lng}}""" }

    /**
     * The body a page sends back after opening this asset and changing one or two things.
     *
     * [points] is whatever the desk has drawn, as JSON written by hand rather than built from
     * [WebEditorPoint]: a test that built its body from the class would keep passing after somebody
     * renamed a field, which is exactly the failure a page would see as "nothing saved".
     */
    private fun body(
        version: String = WebEditorVersion.of(asset, blockName, listOf(path)),
        name: String = asset.name,
        kind: String = asset.kind,
        shape: String = asset.shape,
        method: String = asset.method,
        block: String? = blockName,
        intervalDays: String = asset.intervalDays.toString(),
        swathWidthM: String = asset.swathWidthM.toString(),
        passes: Int = asset.passesRequired,
        separation: String = asset.passSeparationM.toString(),
        notes: String? = asset.notes,
        points: List<GeoPoint>? = null,
        paths: List<List<GeoPoint>>? = null
    ): String = buildString {
        append("""{"name":"$name","kind":"$kind","shape":"$shape","method":"$method",""")
        append(""""blockName":${if (block == null) "null" else "\"$block\""},""")
        append(""""intervalDays":"$intervalDays","swathWidthM":"$swathWidthM",""")
        append(""""passesRequired":$passes,"passSeparationM":"$separation",""")
        append(""""notes":${if (notes == null) "null" else "\"$notes\""},""")
        if (points != null) append(""""points":${pointJson(points)},""")
        if (paths != null) append(""""paths":[${paths.joinToString(",") { pointJson(it) }}],""")
        append(""""version":"$version"}""")
    }

    private fun ok(editBody: String?): WebEditorEditResult.Ok {
        val result = WebEditorEdits.apply(asset, blockName, listOf(path), editBody)
        assertTrue("expected this to be taken: $result", result is WebEditorEditResult.Ok)
        return result as WebEditorEditResult.Ok
    }

    private fun refused(editBody: String?): WebEditorEditResult.Refused {
        val result = WebEditorEdits.apply(asset, blockName, listOf(path), editBody)
        assertTrue("expected this to be refused: $result", result is WebEditorEditResult.Refused)
        return result as WebEditorEditResult.Refused
    }

    /** A new asset, as the desk's drawing mode sends one: no version, because there is no row yet. */
    private fun draftBody(
        name: String = "New track",
        shape: String = asset.shape,
        points: List<GeoPoint>? = path
    ): String = buildString {
        append("""{"name":"$name","kind":"TRACK","shape":"$shape","method":"UNSET",""")
        append(""""blockName":null,"intervalDays":"120","swathWidthM":"",""")
        append(""""passesRequired":1,"passSeparationM":"","notes":null""")
        if (points != null) append(""","points":${pointJson(points)}""")
        append("}")
    }

    private fun drafted(createBody: String?): WebEditorDraft {
        val result = WebEditorEdits.create(createBody, nowEpochMs = 1_790_000_000_000L)
        assertTrue("expected this to be taken: $result", result is WebEditorCreateResult.Ok)
        return (result as WebEditorCreateResult.Ok).draft
    }

    private fun createRefused(createBody: String?): WebEditorCreateResult.Refused {
        val result = WebEditorEdits.create(createBody, nowEpochMs = 1_790_000_000_000L)
        assertTrue("expected this to be refused: $result", result is WebEditorCreateResult.Refused)
        return result as WebEditorCreateResult.Refused
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
        assertEquals(409, WebEditorRefusal.IN_USE.status)
        assertEquals("in-use", WebEditorRefusal.IN_USE.reason)
        assertEquals(400, WebEditorRefusal.INVALID.status)
        assertEquals("invalid", WebEditorRefusal.INVALID.reason)
    }

    @Test
    fun `a path the desk drew is taken, and comes back as the paths to store`() {
        val drawn = listOf(GeoPoint(-41.5, 173.8), GeoPoint(-41.55, 173.85), GeoPoint(-41.6, 173.9))

        val result = ok(body(points = drawn, intervalDays = "90"))

        assertEquals("the line is stored with the row", listOf(drawn), result.paths)
        assertEquals("and the details in the same body came along with it", 90, result.asset.intervalDays)
    }

    @Test
    fun `a body that says nothing about the drawing leaves the line exactly as it is`() {
        // Which is what the details form sends: the same body, one key shorter. Null rather than an
        // empty list, so the difference between "nothing has moved" and "nothing is drawn" survives
        // all the way to the repository.
        assertNull(ok(body()).paths)
    }

    @Test
    fun `a place drawn as a path is refused in the path rules' words`() {
        val place = asset.copy(shape = "POINT")

        val result = WebEditorEdits.apply(
            current = place,
            blockName = blockName,
            paths = listOf(path),
            // The version the place's own card would carry: a place and a line with the same fields
            // hash differently, which is the point of the shape being in the hash at all.
            body = body(
                version = WebEditorVersion.of(place, blockName, listOf(path)),
                shape = "POINT",
                points = path
            )
        )

        assertTrue("expected a refusal: $result", result is WebEditorEditResult.Refused)
        assertTrue(
            "counts the points it was sent: ${(result as WebEditorEditResult.Refused).message}",
            result.message.contains("2 points")
        )
    }

    @Test
    fun `a version quoted against the line as it was is refused once a vertex has moved`() {
        val quoted = WebEditorVersion.of(asset, blockName, listOf(path))
        val moved = path + GeoPoint(-41.7, 174.1)

        val refused = WebEditorEdits.apply(asset, blockName, listOf(moved), body(version = quoted))

        assertEquals(WebEditorRefusal.STALE, (refused as WebEditorEditResult.Refused).refusal)
    }

    @Test
    fun `the version covers the line, so a card cannot undo a move somebody else made`() {
        assertNotEquals(
            WebEditorVersion.of(asset, blockName, listOf(path)),
            WebEditorVersion.of(asset, blockName, listOf(path.dropLast(1) + GeoPoint(-41.7, 174.1)))
        )
        assertNotEquals(
            "a vertex added is a change",
            WebEditorVersion.of(asset, blockName, listOf(path)),
            WebEditorVersion.of(asset, blockName, listOf(path + GeoPoint(-41.7, 174.1)))
        )
        assertEquals(
            "and the same points hash the same whatever else was read with them",
            WebEditorVersion.of(asset, blockName, listOf(path)),
            WebEditorVersion.of(asset, blockName, listOf(path))
        )
    }

    @Test
    fun `the version covers a side track, so a card cannot undo one added on the phone`() {
        val junction = path.last()
        val spur = listOf(junction, GeoPoint(-41.55, 173.95))

        // The same line first, with and without a side track hanging off it: a desk holding the card
        // from before the spur was drawn is holding a card that is out of date.
        assertNotEquals(
            "a side track is a change to the track the desk was handed",
            WebEditorVersion.of(asset, blockName, listOf(path)),
            WebEditorVersion.of(asset, blockName, listOf(path, spur))
        )
        // And the paths are hashed with their boundaries marked, so the same vertices split two ways
        // are two versions rather than one - which is what makes the mark worth having.
        assertNotEquals(
            WebEditorVersion.of(asset, blockName, listOf(path, spur)),
            WebEditorVersion.of(
                asset,
                blockName,
                listOf(listOf(path.first(), spur.last()), listOf(junction))
            )
        )
    }

    @Test
    fun `a new track comes back as a row the database will number, with its line`() {
        val draft = drafted(draftBody(name = "Gully track"))

        assertEquals("Gully track", draft.asset.name)
        assertEquals("TRACK", draft.asset.kind)
        assertEquals(120, draft.asset.intervalDays)
        assertTrue("a new asset is active", draft.asset.active)
        assertEquals("the id is the database's to issue", 0L, draft.asset.id)
        assertEquals(
            "and the date is the phone's clock, not the laptop's",
            1_790_000_000_000L,
            draft.asset.createdAtEpochMs
        )
        assertEquals(path, draft.geometry.line)
        assertNull("no block unless the form named one", draft.blockName)
    }

    @Test
    fun `a new place is one point, and the shape decides which rules judge it`() {
        val draft = drafted(draftBody(name = "Trough", shape = "POINT", points = listOf(path.first())))

        assertEquals("POINT", draft.asset.shape)
        assertEquals(1, draft.geometry.pointCount)
    }

    @Test
    fun `a new asset with no drawing is refused, because there is nowhere for it to be`() {
        assertTrue(
            "a path that was never drawn: ${createRefused(draftBody(points = null)).message}",
            createRefused(draftBody(points = null)).message.contains("draw it on the map")
        )
        assertTrue(
            "an empty path is the same thing said a different way: " +
                createRefused(draftBody(points = emptyList())).message,
            createRefused(draftBody(points = emptyList())).message.contains("A line needs at least two points")
        )
    }

    @Test
    fun `a new asset is judged by the same rules, so an unnamed one is refused in the app's words`() {
        assertEquals("Give it a name so it can be found later", createRefused(draftBody(name = "  ")).message)
        assertTrue(
            "and a kind this build does not know is still a refusal: " +
                createRefused("""{"name":"X","kind":"PADDOCK","shape":"LINE","method":"UNSET","intervalDays":"120","points":[]}""").message,
            createRefused("""{"name":"X","kind":"PADDOCK","shape":"LINE","method":"UNSET","intervalDays":"120","points":[]}""")
                .message.contains("PADDOCK")
        )
    }

    @Test
    fun `a new asset ignores a version, because it has nothing to be stale against`() {
        // The page builds both bodies with one function, so a create arrives carrying the version of
        // whatever card the operator had been looking at. There is no row to compare it with: the
        // only thing that version could refuse is the making of a new track, which is nobody's intent.
        val withVersion = draftBody().dropLast(1) + ""","version":"a-card-from-before"}"""

        assertEquals(path, drafted(withVersion).geometry.line)
    }

    /* ---- A drawing that carries side tracks -------------------------------------------- */

    /** A side track off the far end of the line, which is where one leaves a track. */
    private val spur = listOf(path.last(), GeoPoint(-41.55, 173.85))

    @Test
    fun `a body carrying paths is taken with every path in it`() {
        val taken = ok(body(paths = listOf(path, spur)))

        assertEquals("the line and its side track", 2, taken.paths!!.size)
        assertEquals(path, taken.paths!![0])
        assertEquals("the side track goes back exactly as it came", spur, taken.paths!![1])
    }

    @Test
    fun `a side track that does not start on the line is refused in the app's own words`() {
        val refused = refused(
            body(paths = listOf(path, listOf(GeoPoint(-41.2, 173.2), GeoPoint(-41.3, 173.3))))
        )

        assertTrue("says what to do: ${refused.message}", refused.message.contains("start on the track"))
    }

    @Test
    fun `a body carrying two drawings is refused rather than read one way or the other`() {
        val refused = refused(body(points = path, paths = listOf(path, spur)))

        assertTrue(
            "says the page is confused: ${refused.message}",
            refused.message.contains("two drawings")
        )
        assertTrue(
            "and what to do about it: ${refused.message}",
            refused.message.contains("Reload the page")
        )
    }

    @Test
    fun `a body carrying paths puts them on the version too, so a spur added since is stale`() {
        // The version is built from the paths the phone holds, so a card that never saw the spur is
        // refused by the same arithmetic as one that never saw a rename: this is that arithmetic, from
        // the desk's side - the body's own paths are what a fresh card would quote.
        val quoted = WebEditorVersion.of(asset, blockName, listOf(path))
        val fresh = WebEditorVersion.of(asset, blockName, listOf(path, spur))

        assertNotEquals(quoted, fresh)
        assertEquals(
            "and a body carrying the paths is taken against the version it quoted",
            path,
            ok(body(version = quoted, paths = listOf(path))).paths!!.first()
        )
    }
}
