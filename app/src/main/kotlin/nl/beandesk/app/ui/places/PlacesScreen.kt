package nl.beandesk.app.ui.places

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.beandesk.app.BuildConfig
import nl.beandesk.app.R
import nl.beandesk.core.filter.PlaceFilter
import nl.beandesk.core.model.Category
import nl.beandesk.core.model.Place
import nl.beandesk.core.route.RouteLinks

@Composable
fun PlacesScreen(viewModel: PlacesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlacesContent(
        state = state,
        onFilterChange = viewModel::setFilter,
        onClearFilters = viewModel::clearFilters,
        onSelect = viewModel::select,
        onShowOnMap = viewModel::showOnMap,
        onToggleList = viewModel::toggleList,
        onRetry = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacesContent(
    state: PlacesUiState,
    onFilterChange: (PlaceFilter) -> Unit,
    onClearFilters: () -> Unit,
    onSelect: (Long?) -> Unit,
    onShowOnMap: (Long) -> Unit,
    onToggleList: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current

    // The map loads tiles, so it is only created once someone opens it; after that it stays
    // under the list, so it keeps its camera position.
    var mapOpened by rememberSaveable { mutableStateOf(!state.showList) }
    LaunchedEffect(state.showList) {
        if (!state.showList) mapOpened = true
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.places_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = state.cityName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                FilterBar(state.filter, onFilterChange)
                if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.hasError) ErrorBanner(onRetry)
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(if (state.showList) R.string.show_map else R.string.show_list)) },
                icon = {
                    Icon(
                        painter = painterResource(if (state.showList) R.drawable.ic_map else R.drawable.ic_list),
                        contentDescription = null,
                    )
                },
                onClick = onToggleList,
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (mapOpened || !state.showList) {
                PlaceMap(
                    items = state.items,
                    center = state.center,
                    focus = state.focus,
                    styleUrl = BuildConfig.MAP_STYLE_URL,
                    onPlaceClick = onSelect,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (state.showList) {
                PlaceList(
                    items = state.items,
                    totalCount = state.totalCount,
                    isRefreshing = state.isRefreshing,
                    attribution = state.attribution,
                    onPlaceClick = onSelect,
                    onClearFilters = onClearFilters,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            }
        }
    }

    state.selected?.let { item ->
        PlaceDetailSheet(
            item = item,
            showMapButton = state.showList,
            onDismiss = { onSelect(null) },
            onRoute = { openRoute(context, item.place) },
            onShowOnMap = { onShowOnMap(item.place.id) },
        )
    }
}

@Composable
private fun FilterBar(filter: PlaceFilter, onChange: (PlaceFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(R.string.filter_open_now, filter.openNow) { onChange(filter.copy(openNow = !filter.openNow)) }
        Chip(R.string.filter_wifi, filter.wifi) { onChange(filter.copy(wifi = !filter.wifi)) }
        Chip(R.string.filter_power, filter.power) { onChange(filter.copy(power = !filter.power)) }
        Chip(R.string.filter_laptop, filter.laptopAllowed) {
            onChange(filter.copy(laptopAllowed = !filter.laptopAllowed))
        }
        Chip(R.string.filter_quiet, filter.quiet) { onChange(filter.copy(quiet = !filter.quiet)) }
        Category.entries.forEach { category ->
            Chip(category.filterLabelRes, category in filter.categories) {
                val categories = if (category in filter.categories) {
                    filter.categories - category
                } else {
                    filter.categories + category
                }
                onChange(filter.copy(categories = categories))
            }
        }
    }
}

@Composable
private fun Chip(label: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) })
}

@Composable
private fun ErrorBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.refresh_failed),
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

private fun openRoute(context: Context, place: Place) {
    val intent = Intent(Intent.ACTION_VIEW, RouteLinks.googleMaps(place.location).toUri())
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.route_unavailable, Toast.LENGTH_SHORT).show()
    }
}
