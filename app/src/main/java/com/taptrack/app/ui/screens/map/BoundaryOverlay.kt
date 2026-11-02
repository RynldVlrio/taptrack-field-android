package com.taptrack.app.ui.screens.map

import com.taptrack.app.utils.NamedFeature

data class BoundaryOverlay(
    val id: Long,
    val name: String,
    val fillColor: Int,
    val borderColor: Int,
    val showLabel: Boolean,
    val polygons: List<NamedFeature>,
    val polylines: List<NamedFeature>
)
