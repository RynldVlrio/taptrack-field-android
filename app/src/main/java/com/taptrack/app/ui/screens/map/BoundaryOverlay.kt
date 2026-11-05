package com.taptrack.app.ui.screens.map

import com.taptrack.app.utils.LegendRow
import com.taptrack.app.utils.NamedFeature

data class BoundaryOverlay(
    val id: Long,
    val name: String,
    val fillColor: Int,
    val borderColor: Int,
    val showLabel: Boolean,
    val polygons: List<NamedFeature>,
    val polylines: List<NamedFeature>,
    val points: List<NamedFeature> = emptyList(),
    val weight: Float = 2f,          // line thickness chosen on the web (2 thin · 4 medium · 6 thick)
    val legend: List<LegendRow> = emptyList(),
    val legendTitle: String? = null,
)
