package nz.mckenzie.sprayday.web

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.db.GroupEntity
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.backup.GroupRecord
import nz.mckenzie.sprayday.domain.backup.ProductRecord
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.map.WebStyleJson
import nz.mckenzie.sprayday.offline.HttpResponse

/**
 * The documents the editor serves, built from the same repository calls the app's own screens use.
 *
 * Nothing here writes, and nothing here invents: the due colours are the ones the map colours its
 * lines with ([AssetColors.forStatus], on the phone's own clock), the features are the ones the map
 * draws ([AssetGeoJson], geometry included), the style is the phone's own layer tables
 * ([WebStyleJson]), and the page is the copy inside the APK. That is the whole reason the editor is
 * served by the phone rather than shipped as a separate thing: one database, one clock, one set of
 * rules, and a laptop is only another window onto them.
 *
 * It is kept free of Android so the shape of the wire can be tested on the JVM; the wiring - a real
 * repository, the real assets, the real position - happens where the service is started. What it is
 * *allowed* to read is decided here and nowhere else: assets, blocks, products and the phone's own
 * fix. Sprays, recordings and due dates are the phone's business, and an editor that could reach
 * them would be a management application, which the plan says this is not.
 */
class WebEditorDocuments(
    private val assets: AssetRepository,
    private val sprays: SprayRepository,
    /** The token, because the documents contain URLs and those have to carry it too. */
    private val token: String,
    private val basemap: suspend () -> Basemap,
    private val position: suspend () -> GeoPoint?,
    /** One file of the page, from the APK's own assets, or null when it ships no such file. */
    private val readPageFile: (String) -> ByteArray?,
    private val now: () -> Long = System::currentTimeMillis
) : WebEditorData {

    override suspend fun state(authority: String): String {
        val nowMs = now()
        // The same read the map makes, on the same clock: an asset is the colour the phone would
        // draw it, not the colour a laptop worked out for itself.
        val withDue = assets.observeAssetsWithDue(nowProvider = flowOf(nowMs)).first()

        return WebEditorJson.build(
            WebEditorDocument(
                nowEpochMs = nowMs,
                assets = withDue.map { item ->
                    WebEditorJson.record(
                        asset = item.asset,
                        due = item.due,
                        sprayCount = item.sprayCount,
                        groupName = item.groupName
                    )
                },
                groups = assets.observeGroups().first().map { it.toRecord() },
                products = sprays.observeProducts().first().map { it.toRecord() },
                bounds = assets.assetBounds()?.let { WebEditorBounds.of(it) },
                position = position()?.let { WebEditorPosition(lat = it.lat, lng = it.lng) }
            )
        )
    }

    override suspend fun assets(): String {
        val withDue = assets.observeAssetsWithDue(nowProvider = flowOf(now())).first()

        // The same lines the map draws, so a track is where the phone says it is. The stretches of a
        // half-sprayed line are not here yet: that needs the recorded passes, which is later work,
        // and a line drawn whole in its traffic light's colour is what the map showed before that
        // feature existed.
        val lines = withDue.map { item ->
            AssetLine(
                assetId = item.asset.id,
                name = item.asset.name,
                colorHex = AssetColors.forStatus(item.due.status),
                points = assets.getAssetGeometry(item.asset.id),
                kind = AssetKind.fromStorage(item.asset.kind),
                shape = AssetShape.fromStorage(item.asset.shape)
            )
        }
        return AssetGeoJson.build(lines)
    }

    override suspend fun style(authority: String): String {
        val chosen = basemap()
        return WebStyleJson.build(
            basemap = chosen,
            // The page's own requests carry the token in the URL, and MapLibre's requests do not go
            // through the page, so the style hands the map the token too. That is also part of why
            // the token belongs to the run of the switch rather than to the install: off ends it.
            tileUrlTemplate = "http://$authority/tiles/${chosen.id}/{z}/{x}/{y}" +
                "${chosen.tileSuffix}?$TOKEN_PARAM=$token",
            assetsUrl = "http://$authority${WebEditorServer.ASSETS_PATH}?$TOKEN_PARAM=$token"
        )
    }

    override fun page(path: String): HttpResponse? {
        val name = if (path == "/") INDEX else path.removePrefix("/")
        // Exact names only. The page is the one thing here whose name a caller chooses, and a page
        // that could ask for `../../databases/spray_day.db` would be a file server in a page's hat.
        if (name.isEmpty() || name.contains("..") || name.startsWith("/")) return null
        val bytes = readPageFile(name) ?: return null
        return HttpResponse.bytes(200, contentTypeOf(name), bytes)
    }

    private fun contentTypeOf(name: String): String =
        CONTENT_TYPES[name.substringAfterLast('.', "").lowercase()] ?: DEFAULT_CONTENT_TYPE

    private fun GroupEntity.toRecord() = GroupRecord(id = id, name = name, notes = notes)

    private fun ProductEntity.toRecord() = ProductRecord(
        id = id,
        name = name,
        unit = unit,
        rateText = rateText,
        notes = notes,
        archived = archived
    )

    companion object {
        /** What the page file is called inside the APK's `web/` directory. */
        private const val INDEX = "index.html"

        private const val TOKEN_PARAM = WebEditorServer.TOKEN_PARAM

        /**
         * The file types the page uses, by extension.
         *
         * `text/javascript` rather than `application/javascript` on purpose: a browser refuses a
         * module whose type it does not recognise as JavaScript, and the page is plain ES modules -
         * the file you edit is the file that runs. Anything not in the table is served as bytes to
         * download, which is the honest answer for a type nothing here knows.
         */
        private val CONTENT_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "txt" to "text/plain; charset=utf-8"
        )

        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
