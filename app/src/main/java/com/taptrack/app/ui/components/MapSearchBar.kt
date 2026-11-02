package com.taptrack.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taptrack.app.utils.SearchResult
import com.taptrack.app.utils.geocodeSearch
import com.taptrack.app.utils.parseCoordinates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

@Composable
fun MapSearchBar(
    onResultSelected: (GeoPoint, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var expanded by remember { mutableStateOf(false) }
    var justExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var showDropdown by remember { mutableStateOf(false) }

    val coordTarget = remember(query) { parseCoordinates(query) }
    val hasDropdownContent = results.isNotEmpty() || coordTarget != null
    val dropdownVisible = showDropdown && hasDropdownContent

    // Define helpers before LaunchedEffects so lambdas can reference them
    fun collapse() {
        query = ""
        results = emptyList()
        showDropdown = false
        focusManager.clearFocus()
        expanded = false
    }

    fun selectResult(point: GeoPoint, label: String) {
        onResultSelected(point, label)
        collapse()
    }

    // When a valid coordinate pair is detected, navigate immediately — no tap needed
    LaunchedEffect(coordTarget) {
        val target = coordTarget ?: return@LaunchedEffect
        delay(400) // brief pause so user can finish editing the second number
        if (parseCoordinates(query) != null) { // still valid after pause
            selectResult(target, "%.6f, %.6f".format(target.latitude, target.longitude))
        }
    }

    LaunchedEffect(query) {
        results = emptyList()
        // Coordinates are handled above; skip geocoding for coordinate input
        if (query.length < 2 || coordTarget != null) {
            isSearching = false
            return@LaunchedEffect
        }
        delay(300) // reduced from 450 ms for faster suggestions
        isSearching = true
        results = geocodeSearch(query, context.packageName)
        isSearching = false
    }

    // Request focus after expansion; justExpanded is already set synchronously in the click handler
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(80)
            focusRequester.requestFocus()
            delay(350)
            justExpanded = false
        }
    }

    // Outer box always fills the parent width so layout is stable
    Box(modifier = modifier) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "search_bar"
        ) { isExpanded ->
            if (isExpanded) {
                // ── Expanded: full search bar ─────────────────────────────
                Column {
                    Surface(
                        shape = if (dropdownVisible)
                            RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                        else
                            RoundedCornerShape(14.dp),
                        shadowElevation = 6.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        TextField(
                            value = query,
                            onValueChange = { query = it; showDropdown = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { state ->
                                    if (!state.isFocused && !justExpanded) {
                                        scope.launch {
                                            delay(150)
                                            showDropdown = false
                                            if (query.isEmpty()) {
                                                delay(100)
                                                expanded = false
                                            }
                                        }
                                    }
                                },
                            placeholder = {
                                Text(
                                    "Search place, landmark, or coordinates…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                if (isSearching)
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                else
                                    Icon(Icons.Default.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                IconButton(onClick = { collapse() }) {
                                    Icon(
                                        if (query.isNotEmpty()) Icons.Default.Close else Icons.Default.Close,
                                        contentDescription = "Close search"
                                    )
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    }

                    // ── Results dropdown ──────────────────────────────────
                    if (dropdownVisible) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                            shadowElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                coordTarget?.let { point ->
                                    item {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        SearchResultRow(
                                            icon = Icons.Default.GpsFixed,
                                            title = "Navigating to coordinates…",
                                            subtitle = "%.6f, %.6f".format(point.latitude, point.longitude),
                                            onClick = {
                                                // Tap to navigate immediately without waiting 400 ms
                                                selectResult(
                                                    point,
                                                    "%.6f, %.6f".format(point.latitude, point.longitude)
                                                )
                                            }
                                        )
                                    }
                                }
                                items(results) { result ->
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    SearchResultRow(
                                        icon = Icons.Default.Place,
                                        title = result.title,
                                        subtitle = result.subtitle,
                                        onClick = {
                                            selectResult(GeoPoint(result.lat, result.lon), result.title)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // ── Collapsed: transparent search icon pill ───────────────
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.28f),
                        shadowElevation = 3.dp,
                        modifier = Modifier.size(46.dp),
                        onClick = { justExpanded = true; expanded = true }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Open search",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
