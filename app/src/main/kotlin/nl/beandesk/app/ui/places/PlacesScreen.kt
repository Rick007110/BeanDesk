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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
        onSelect = viewModel::select,
        onToggleList = viewModel::toggleList,
        onRetry = viewModel::refresh,
    )
}

@Composable
private fun PlacesContent(
    state: PlacesUiState,
    onFilterChange: (PlaceFilter) -> Unit,
    onSelect: (Long?) -> Unit,
    onToggleList: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onToggleList) {
                Text(stringResource(if (state.showList) R.string.show_map else R.string.show_list))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterBar(state.filter, onFilterChange)
            if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.hasError) ErrorBanner(onRetry)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The map stays composed under the list, so it keeps its camera and lifecycle.
                PlaceMap(
                    items = state.items,
                    center = state.center,
                    styleUrl = BuildConfig.MAP_STYLE_URL,
                    onPlaceClick = onSelect,
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.showList) {
                    PlaceList(
                        items = state.items,
                        attribution = state.attribution,
                        onPlaceClick = { id ->
                            onSelect(id)
                            onToggleList()
                        },
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    )
                } else {
                    state.selected?.let { item ->
                        PlaceCard(
                            item = item,
                            onRoute = { openRoute(context, item.place) },
                            onClose = { onSelect(null) },
                            // Leaves room for the floating button below the card.
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(filter: PlaceFilter, onChange: (PlaceFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(R.string.filter_wifi, filter.wifi) { onChange(filter.copy(wifi = !filter.wifi)) }
        Chip(R.string.filter_power, filter.power) { onChange(filter.copy(power = !filter.power)) }
        Chip(R.string.filter_open_now, filter.openNow) { onChange(filter.copy(openNow = !filter.openNow)) }
        Chip(R.string.filter_quiet, filter.quiet) { onChange(filter.copy(quiet = !filter.quiet)) }
        Chip(R.string.filter_laptop, filter.laptopAllowed) {
            onChange(filter.copy(laptopAllowed = !filter.laptopAllowed))
        }
        Category.entries.forEach { category ->
            Chip(category.labelRes, category in filter.categories) {
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

private val Category.labelRes: Int
    get() = when (this) {
        Category.CAFE -> R.string.category_cafe
        Category.LIBRARY -> R.string.category_library
        Category.COWORKING -> R.string.category_coworking
    }

private fun openRoute(context: Context, place: Place) {
    val intent = Intent(Intent.ACTION_VIEW, RouteLinks.googleMaps(place.location).toUri())
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.route_unavailable, Toast.LENGTH_SHORT).show()
    }
}
