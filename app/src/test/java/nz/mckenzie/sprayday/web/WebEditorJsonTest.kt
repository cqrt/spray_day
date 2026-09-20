package nz.mckenzie.sprayday.web

import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.backup.GroupRecord
import nz.mckenzie.sprayday.domain.backup.ProductRecord
import nz.mckenzie.sprayday.domain.due.DueCalculator
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import org.junit.Assert.assertEquals
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

    private fun documentWith(asset: AssetEntity, groupName: String? = "Estuary") = WebEditorDocument(
        nowEpochMs = now,
        assets = listOf(WebEditorJson.record(asset, dueOf(asset), sprayCount = 4, groupName = groupName))
    )

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
