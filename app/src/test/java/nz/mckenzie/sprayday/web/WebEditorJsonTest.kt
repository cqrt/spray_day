package nz.mckenzie.sprayday.web

import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetPhrase
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.MethodPhrase
import nz.mckenzie.sprayday.domain.asset.PassPhrase
import nz.mckenzie.sprayday.domain.asset.SprayMethod
import nz.mckenzie.sprayday.domain.backup.GroupRecord
import nz.mckenzie.sprayday.domain.backup.ProductRecord
import nz.mckenzie.sprayday.domain.due.DueCalculator
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.ui.AssetEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The document the page is handed.
 *
 * Two things are being held here. One is that the desk is told enough to draw and to answer a
 * question: the traffic light, the day it is due, the block, the catalogue, the box the work is in
 * and where the phone is. The other is that nothing the operator typed on the phone is quietly
 * dropped on the way out - the mapping into the backup vocabulary is written out in
 * [WebEditorJson], and a column forgotten there is a column the desk never sees.
 */
class WebEditorJsonTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val zone = ZoneId.of("Pacific/Auckland")
    private val now = 1_758_000_000_000L

    /** An asset with every field a value of its own, so a dropped column cannot hide. */
    private val everyField = AssetEntity(
        id = 7L,
        name = "Estuary road",
        kind = "ROAD",
        shape = "LINE",
        method = "BOOM",
        groupId = 3L,
        notes = "Both edges, twice a year",
        intervalDays = 120,
        swathWidthM = 12.5,
        passesRequired = 2,
        passSeparationM = 3.0,
        active = true,
        createdAtEpochMs = 1_700_000_000_000L,
        lastSprayedAtEpochMs = 1_750_000_000_000L,
        lengthM = 3300.0
    )

    private fun dueOf(asset: AssetEntity) = DueCalculator.calculate(
        lastSprayedAtEpochMs = asset.lastSprayedAtEpochMs,
        intervalDays = asset.intervalDays,
        leadDays = AssetEntity.DEFAULT_LEAD_DAYS,
        nowEpochMs = now,
        zoneId = zone
    )

    private fun roundTrip(document: WebEditorDocument): WebEditorDocument =
        json.decodeFromString(WebEditorDocument.serializer(), WebEditorJson.build(document))

    /** The line the phone holds for this asset, which the version the desk quotes is hashed over. */
    private val path = listOf(GeoPoint(-41.5, 173.8), GeoPoint(-41.6, 173.9))

    private fun documentWith(
        asset: AssetEntity,
        groupName: String? = "Estuary",
        recordings: Int = 0,
        sprays: Int = 4
    ) = WebEditorDocument(
        nowEpochMs = now,
        assets = listOf(
            WebEditorJson.record(
                asset = asset,
                due = dueOf(asset),
                sprayCount = sprays,
                groupName = groupName,
                paths = listOf(path),
                recordingCount = recordings
            )
        )
    )

    @Test
    fun `a track drawn before the kinds existed reaches the desk as what it is`() {
        // Stored as INFRASTRUCTURE by every build before v0.6.40. The phone reads it back by its
        // shape, here, once - so the page is handed a kind it can compare with the kinds in its own
        // filter list, and holds no copy of the old-kind rule itself.
        val oldLine = everyField.copy(kind = "INFRASTRUCTURE", shape = "LINE")
        val oldSpot = everyField.copy(kind = "INFRASTRUCTURE", shape = "POINT")

        assertEquals(
            "a line is a fenceline or stopbank",
            AssetKind.FENCELINE.name,
            roundTrip(documentWith(oldLine)).assets.single().asset.kind
        )
        assertEquals(
            "and a spot is some other place",
            AssetKind.OTHER_PLACE.name,
            roundTrip(documentWith(oldSpot)).assets.single().asset.kind
        )
        assertEquals(
            "the shape it was stored with still travels, because the card draws a length from it",
            "LINE",
            roundTrip(documentWith(oldLine)).assets.single().asset.shape
        )
    }

    @Test
    fun `every column of an asset survives the trip to the desk`() {
        val restored = roundTrip(documentWith(everyField)).assets.single().asset

        assertEquals(everyField.id, restored.id)
        assertEquals(everyField.name, restored.name)
        assertEquals(everyField.kind, restored.kind)
        assertEquals(everyField.shape, restored.shape)
        assertEquals(everyField.method, restored.method)
        assertEquals(everyField.groupId, restored.groupId)
        assertEquals(everyField.notes, restored.notes)
        assertEquals(everyField.intervalDays, restored.intervalDays)
        assertEquals(everyField.swathWidthM!!, restored.swathWidthM!!, 0.0)
        assertEquals(everyField.passesRequired, restored.passesRequired)
        assertEquals(everyField.passSeparationM!!, restored.passSeparationM!!, 0.0)
        assertEquals(everyField.active, restored.active)
        assertEquals(everyField.createdAtEpochMs, restored.createdAtEpochMs)
        assertEquals(everyField.lastSprayedAtEpochMs, restored.lastSprayedAtEpochMs)
        assertEquals(everyField.lengthM, restored.lengthM, 0.0)
    }

    @Test
    fun `the asset the desk is handed carries the paths a write has to bring back`() {
        // The geometry a page draws comes from the GeoJSON, but a line write has to carry every path -
        // so the paths are in the record beside the version, and a page that ignores them still works:
        // its writes carry one path, and the phone refuses those for a track that has side tracks
        // rather than dropping them.
        val spur = listOf(path.last(), GeoPoint(-41.55, 173.85))
        val record = WebEditorDocument(
            nowEpochMs = now,
            assets = listOf(
                WebEditorJson.record(
                    asset = everyField,
                    due = dueOf(everyField),
                    sprayCount = 4,
                    groupName = "Estuary",
                    paths = listOf(path, spur),
                    recordingCount = 0
                )
            )
        ).let { roundTrip(it) }.assets.single()

        assertEquals("the line first, then its side tracks", 2, record.paths.size)
        assertEquals(path, record.paths[0].map { GeoPoint(it.lat, it.lng) })
        assertEquals(spur, record.paths[1].map { GeoPoint(it.lat, it.lng) })
    }

    @Test
    fun `an asset with nothing drawn carries no paths at all`() {
        val record = WebEditorDocument(
            nowEpochMs = now,
            assets = listOf(
                WebEditorJson.record(
                    asset = everyField,
                    due = dueOf(everyField),
                    sprayCount = 4,
                    groupName = "Estuary",
                    paths = emptyList(),
                    recordingCount = 0
                )
            )
        ).let { roundTrip(it) }.assets.single()

        assertTrue("the desk sees an empty list rather than a guess", record.paths.isEmpty())
    }

    @Test
    fun `the asset carries the traffic light and the day it is next due`() {
        val due = dueOf(everyField)
        val record = roundTrip(documentWith(everyField)).assets.single()

        assertEquals(due.status.name, record.dueStatus)
        assertEquals(due.dueDateEpochMs, record.dueAtEpochMs)
        assertEquals(due.daysUntilDue, record.daysUntilDue)
        assertEquals(4, record.sprayCount)
        assertEquals("Estuary", record.groupName)
    }

    @Test
    fun `an asset never sprayed says so, and has no due day to show`() {
        val never = everyField.copy(lastSprayedAtEpochMs = null)

        val record = roundTrip(documentWith(never)).assets.single()

        assertEquals(DueStatus.NEVER_SPRAYED.name, record.dueStatus)
        assertNull(record.dueAtEpochMs)
        assertNull(record.daysUntilDue)
    }

    @Test
    fun `the geometry is left out of the state, because the geojson carries it once`() {
        val text = WebEditorJson.build(documentWith(everyField))

        assertTrue(roundTrip(documentWith(everyField)).assets.single().asset.points.isEmpty())
        assertTrue("the length still says how long it is: $text", text.contains("\"lengthM\":3300"))
        assertTrue("said empty rather than left to guess: $text", text.contains("\"points\":[]"))
    }

    @Test
    fun `blocks and products travel in the backup's own vocabulary`() {
        val document = WebEditorDocument(
            nowEpochMs = now,
            groups = listOf(GroupRecord(id = 3L, name = "Estuary", notes = "The lower block")),
            products = listOf(
                ProductRecord(id = 1L, name = "Roundup", unit = "mL", rateText = "10 mL/L"),
                ProductRecord(id = 2L, name = "Spray oil", archived = true)
            )
        )

        val restored = roundTrip(document)

        assertEquals(document.groups, restored.groups)
        assertEquals(document.products, restored.products)
    }

    @Test
    fun `the box the work is in and the phone's fix are both offered`() {
        val document = WebEditorDocument(
            nowEpochMs = now,
            bounds = WebEditorBounds.of(
                LatLngBounds(minLat = -41.6, minLng = 173.8, maxLat = -41.4, maxLng = 174.1)
            ),
            position = WebEditorPosition(lat = -41.5, lng = 173.9)
        )

        val restored = roundTrip(document)

        assertEquals(WebEditorBounds(-41.6, 173.8, -41.4, 174.1), restored.bounds)
        assertEquals(WebEditorPosition(-41.5, 173.9), restored.position)
    }

    @Test
    fun `the record carries the version an edit has to quote back`() {
        val record = roundTrip(documentWith(everyField)).assets.single()

        // Worked out here from the row, the block and the line, exactly as the phone will work it out
        // again when the edit comes back: a desk quoting this cannot have been reading another version
        // of the asset, and the version survives the trip to the page as the rest of the record does.
        assertEquals(WebEditorVersion.of(everyField, "Estuary", listOf(path)), record.version)
    }

    @Test
    fun `the record carries what deleting it would take, in the phone's own words`() {
        // A track nothing has ever been recorded against, which is the only kind the desk may delete.
        val clean = roundTrip(documentWith(everyField, sprays = 0)).assets.single()

        assertTrue("a track nothing is recorded against may be deleted", clean.removal.allowed)
        assertTrue(
            "and the sentence says so: ${clean.removal.sentence}",
            clean.removal.sentence.contains("nothing else with it")
        )

        // The four sprays the record already carries are counted by the same rule the delete uses, so a
        // card cannot say "4 sprays recorded" and offer to delete the track in the same breath. The
        // recording is in there too: a recording outlives its asset, and this is the sentence that has
        // to say so before anything goes.
        val sprayed = roundTrip(documentWith(everyField, recordings = 1)).assets.single()
        assertFalse(sprayed.removal.allowed)
        assertTrue(
            "counts both: ${sprayed.removal.sentence}",
            sprayed.removal.sentence.contains("4 sprays and 1 recording")
        )
        assertTrue(
            "and sends the operator to the phone: ${sprayed.removal.sentence}",
            sprayed.removal.sentence.contains("Delete it on the phone")
        )
    }

    @Test
    fun `the desk's form is offered the phone's own words`() {
        val choices = roundTrip(documentWith(everyField)).choices

        // Not a second copy of the vocabulary: the phone's own phrase tables, value and label, so a
        // kind added on the phone appears on the desk with nothing to remember here.
        assertEquals(
            AssetPhrase.kinds.map { it.name to AssetPhrase.kind(it) },
            choices.kinds.map { it.value to it.label }
        )
        assertEquals(
            MethodPhrase.choices.map { it.name to MethodPhrase.choice(it) },
            choices.methods.map { it.value to it.label }
        )
        assertEquals(
            PassPhrase.choices.map { it.toString() to PassPhrase.choice(it) },
            choices.passes.map { it.value to it.label }
        )
        // And the sentences under the fields, which are the phone's own.
        assertEquals(PassPhrase.SEPARATION_HINT, choices.separationHint)
        assertEquals(AssetEdits.SWATH_HINT, choices.swathHint)
        assertEquals(AssetEdits.BLOCK_HINT, choices.blockHint)
        // There is no shape list to offer: whether a thing is a line or a place is what its kind
        // means (`AssetKind.shape`), so the desk is offered eight kinds and no second question.
    }

    @Test
    fun `a new asset is offered the phone's own starting point`() {
        val fresh = roundTrip(documentWith(everyField)).newAsset

        // What a track somebody draws from scratch starts as, taken from the phone's own table rather
        // than written into the page: a default copied into JavaScript is a default that drifts, and the
        // one that drifts is the interval.
        assertEquals(AssetKind.TRACK.name, fresh.kind)
        assertEquals(SprayMethod.UNSET.name, fresh.method)
        assertEquals("120", fresh.intervalDays)
        assertEquals(AssetEntity.DEFAULT_PASSES_REQUIRED, fresh.passesRequired)
        assertEquals(
            "and it is the same default `createAsset` fills in",
            AssetEntity.DEFAULT_INTERVAL_DAYS.toString(),
            fresh.intervalDays
        )
    }

    @Test
    fun `a spray method carries the swath width picking it implies`() {
        val methods = WebEditorChoices.ofApp().methods.associateBy { it.value }

        // The phone's own table, so the desk can move the field the way the phone's form does when a
        // boom becomes a knapsack.
        assertEquals("3", methods.getValue(SprayMethod.BOOM.name).swathM)
        assertEquals("1", methods.getValue(SprayMethod.KNAPSACK.name).swathM)
        assertNull("a method nobody has recorded implies no width", methods.getValue(SprayMethod.UNSET.name).swathM)
        // Nothing else carries one: picking a kind does not imply a width.
        assertNull(WebEditorChoices.ofApp().kinds.first().swathM)
    }

    @Test
    fun `an empty farm is a document with no assets, not a missing document`() {
        val text = WebEditorJson.build(WebEditorDocument(nowEpochMs = now))

        val restored = json.decodeFromString(WebEditorDocument.serializer(), text)

        assertTrue(restored.assets.isEmpty())
        assertEquals(now, restored.nowEpochMs)
        // The page is handed every key every time, so nothing it reads is ever ambiguous.
        assertTrue(text.contains("\"bounds\":null"))
        assertTrue(text.contains("\"position\":null"))
        assertTrue(text.contains("\"assets\":[]"))
    }
}
