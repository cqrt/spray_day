package nz.mckenzie.sprayday.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup file itself: what goes in, what comes out, and what is refused.
 *
 * A backup is the operator's only copy of a season, so the interesting cases are the
 * awkward ones - text with quotes and macrons in it, an empty app, a file from the
 * wrong app, and a file from a future build.
 */
class BackupFormatTest {

    private fun document(
        format: String = BackupDocument.FORMAT,
        version: Int = BackupDocument.VERSION,
        trackName: String = "Winter block"
    ) = BackupDocument(
        format = format,
        version = version,
        exportedAtEpochMs = 1_789_344_000_000L,
        appVersion = "0.5.0",
        products = listOf(
            ProductRecord(id = 1, name = "Glyphosate", unit = "mL", rateText = "10 mL/L", archived = false),
            ProductRecord(id = 2, name = "Retired product", archived = true)
        ),
        tracks = listOf(
            TrackRecord(
                id = 7,
                name = trackName,
                areaLabel = "Home",
                notes = "spray the fenceline twice",
                intervalDays = 45,
                swathWidthM = 6.0,
                createdAtEpochMs = 1_700_000_000_000L,
                lastSprayedAtEpochMs = 1_789_000_000_000L,
                lengthM = 1234.5,
                points = listOf(LinePointRecord(-41.5, 173.95), LinePointRecord(-41.51, 173.96))
            )
        ),
        sprayEvents = listOf(
            SprayEventRecord(
                id = 3,
                trackId = 7,
                sprayedAtEpochMs = 1_789_000_000_000L,
                waterLitres = 400.0,
                operatorName = "Matt",
                distanceM = 1200.0,
                areaSqm = 7200.0,
                recordedSessionId = 5,
                products = listOf(SprayProductRecord(productId = 1, quantityMl = 1500.0))
            )
        ),
        trackDefaults = listOf(TrackDefaultRecord(trackId = 7, productId = 1, defaultQuantityMl = 1500.0)),
        recordings = listOf(
            RecordingRecord(
                id = 5,
                name = "Winter block \u00b7 14 Sep",
                trackId = 7,
                startedAtEpochMs = 1_788_000_000_000L,
                endedAtEpochMs = 1_788_000_600_000L,
                status = "FINISHED",
                distanceM = 1200.0,
                durationMs = 600_000L,
                pointCount = 2,
                points = listOf(
                    RecordedPointRecord(
                        sequence = 0,
                        lat = -41.5,
                        lng = 173.95,
                        altitudeM = 120.0,
                        accuracyM = 4.5f,
                        speedMps = 2.2f,
                        bearingDeg = 180.0f,
                        recordedAtEpochMs = 1_788_000_000_000L
                    ),
                    RecordedPointRecord(sequence = 1, lat = -41.51, lng = 173.96, recordedAtEpochMs = 1_788_000_001_000L)
                )
            )
        )
    )

    @Test
    fun `everything survives the round trip`() {
        val original = document()

        assertEquals(original, BackupFormat.decode(BackupFormat.encode(original)))
    }

    @Test
    fun `awkward text survives, because names come from paddocks not from keyboards`() {
        val awkward = "Wh\u0101nau \"north\" block \\ backslash\nsecond line \u00b7 \ud83d\ude9c"

        val restored = BackupFormat.decode(BackupFormat.encode(document(trackName = awkward)))

        assertEquals(awkward, restored.tracks.single().name)
    }

    @Test
    fun `an app with nothing in it still writes a usable file`() {
        val empty = BackupDocument(exportedAtEpochMs = 1L, appVersion = "0.5.0")

        val restored = BackupFormat.decode(BackupFormat.encode(empty))

        assertEquals(0, BackupFormat.summarise(restored).tracks)
        assertTrue(BackupFormat.summarise(restored).isEmpty)
    }

    @Test
    fun `a file from another app is refused, not half-read`() {
        val otherApp = BackupFormat.encode(document(format = "some-other-backup"))

        val failure = runCatching { BackupFormat.decode(otherApp) }.exceptionOrNull()

        assertTrue("should be a BackupFileException, was $failure", failure is BackupFileException)
        assertTrue(failure!!.message!!.contains("not a Spray Day backup"))
    }

    @Test
    fun `a file from a newer build is refused with a reason`() {
        val fromTheFuture = BackupFormat.encode(document(version = BackupDocument.VERSION + 1))

        val failure = runCatching { BackupFormat.decode(fromTheFuture) }.exceptionOrNull()

        assertTrue(failure is BackupFileException)
        assertTrue(
            "the message should say why: ${failure!!.message}",
            failure.message!!.contains("newer version")
        )
    }

    @Test
    fun `nonsense is refused rather than partly read`() {
        for (text in listOf("", "{not json", "[1,2,3]", "{\"format\":\"spray-day-backup\"}")) {
            val failure = runCatching { BackupFormat.decode(text) }.exceptionOrNull()

            assertTrue("\"$text\" should have been refused, was $failure", failure is BackupFileException)
        }
    }

    @Test
    fun `a file missing newer fields still restores`() {
        // What an older build's file looks like: the fields it never wrote are absent.
        val older = """
            {
              "format": "spray-day-backup",
              "version": 1,
              "exportedAtEpochMs": 1789344000000,
              "appVersion": "0.4.0",
              "tracks": [ { "id": 7, "name": "Home block", "intervalDays": 120, "createdAtEpochMs": 1 } ]
            }
        """.trimIndent()

        val restored = BackupFormat.decode(older)

        assertEquals("Home block", restored.tracks.single().name)
        assertEquals(120, restored.tracks.single().intervalDays)
        assertEquals(0.0, restored.tracks.single().lengthM, 0.0)
        assertTrue(restored.tracks.single().points.isEmpty())
    }

    @Test
    fun `the summary describes what a person would check`() {
        val summary = BackupFormat.summarise(document())

        assertEquals(1, summary.tracks)
        assertEquals(1, summary.sprays)
        assertEquals(1, summary.recordings)
        assertEquals(2, summary.products)
        assertEquals("1 track, 1 spray, 1 recording, 2 products", summary.describe())
    }

    @Test
    fun `the summary counts the points that make up lines as well as recordings`() {
        val summary = BackupFormat.summarise(document())

        assertEquals("two line points plus two recorded fixes", 4, summary.points)
    }
}
