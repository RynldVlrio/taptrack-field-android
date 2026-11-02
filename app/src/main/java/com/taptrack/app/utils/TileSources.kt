package com.taptrack.app.utils

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex

/**
 * Esri World Imagery (satellite). Its URL order is z/y/x.
 * Max zoom 18: for this area Esri has no real imagery above ~z18 and returns "Map data not yet available"
 * pictures instead of errors. With the source capped at 18, osmdroid enlarges the z18 tiles when zooming in further
 * (same as the web map's maxNativeZoom 18).
 */
object EsriImagery : OnlineTileSourceBase(
    "EsriWorldImagery", 0, 18, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Tiles © Esri",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

fun tileSourceFor(satellite: Boolean): ITileSource = if (satellite) EsriImagery else TileSourceFactory.MAPNIK
