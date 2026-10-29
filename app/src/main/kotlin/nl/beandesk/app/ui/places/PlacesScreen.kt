package nl.beandesk.app.ui.places

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.beandesk.app.BuildConfig
import nl.beandesk.app.R
import nl.beandesk.app.icon.AppIcon
import nl.beandesk.app.icon.AppIconManager
import nl.beandesk.core.filter.PlaceFilter
import nl.beandesk.core.model.Category
import nl.beandesk.core.model.Place
import nl.beandesk.core.model.Report
import nl.beandesk.core.route.RouteLinks

@Composable
fun PlacesScreen(viewModel: PlacesViewModel, appIconManager: AppIconManager) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var appIcon by remember { mutableStateOf(appIconManager.selected()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(context.getString(message.textRes))
        }
    }

    PlacesContent(
        state = state,
        snackbarHostState = snackbarHostState,
        appIcon = appIcon,
        onAppIconChange = { icon ->
            appIconManager.select(icon)
            appIcon = icon
        },
        onLocationGranted = viewModel::setLocationGranted,
        onFilterChange = viewModel::setFilter,
        onClearFilters = viewModel::clearFilters,
        onSelect = viewModel::select,
        onShowOnMap = viewModel::showOnMap,
        onToggleList = viewModel::toggleList,
        onRetry = viewModel::refresh,
        onReport = viewModel::report,
    )
}

private val PlacesMessage.textRes: Int
    get() = when (this) {
        PlacesMessage.REPORT_SENT -> R.string.report_sent
        PlacesMessage.REPORT_SAVED_LOCALLY -> R.string.report_saved_locally
        PlacesMessage.REPORT_RATE_LIMITED -> R.string.report_rate_limited
        PlacesMessage.REPORT_FAILED -> R.string.report_failed
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacesContent(
    state: PlacesUiState,
    snackbarHostState: SnackbarHostState,
    appIcon: AppIcon,
    onAppIconChange: (AppIcon) -> Unit,
    onLocationGranted: (Boolean) -> Unit,
    onFilterChange: (PlaceFilter) -> Unit,
    onClearFilters: () -> Unit,
    onSelect: (Long?) -> Unit,
    onShowOnMap: (Long) -> Unit,
    onToggleList: () -> Unit,
    onRetry: () -> Unit,
    onReport: (Report, (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var showIconSheet by rememberSaveable { mutableStateOf(false) }
    var reportPlaceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var recenterRequests by rememberSaveable { mutableIntStateOf(0) }

    // Location: re-checked on every resume, since the user can change it in the system settings.
    var locationAccess by remember { mutableStateOf(context.locationAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { locationAccess = context.locationAccess() }
    LaunchedEffect(locationAccess) { onLocationGranted(locationAccess != LocationAccess.NONE) }
    var locationPromptDismissed by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationAccess = context.locationAccess()
        // Asked once and still no precise location: no more nagging this session.
        if (locationAccess != LocationAccess.PRECISE) locationPromptDismissed = true
    }

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
                    navigationIcon = {
                        Image(
                            painter = painterResource(appIcon.badgeRes),
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 12.dp, end = 4.dp)
                                .size(36.dp),
                        )
                    },
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
                    actions = {
                        IconButton(onClick = { showIconSheet = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_palette),
                                contentDescription = stringResource(R.string.app_icon_title),
                            )
                        }
                    },
                )
                FilterBar(state.filter, onFilterChange)
                if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.hasError) ErrorBanner(onRetry)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    userLocation = state.userLocation,
                    recenterRequests = recenterRequests,
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
                    header = if (!locationPromptDismissed && locationAccess != LocationAccess.PRECISE) {
                        {
                            LocationPrompt(
                                access = locationAccess,
                                onRequest = { permissionLauncher.launch(LOCATION_PERMISSIONS) },
                                onDismiss = { locationPromptDismissed = true },
                            )
                        }
                    } else {
                        null
                    },
                )
            } else {
                SmallFloatingActionButton(
                    onClick = {
                        if (locationAccess == LocationAccess.NONE) {
                            permissionLauncher.launch(LOCATION_PERMISSIONS)
                        } else {
                            recenterRequests++
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_my_location),
                        contentDescription = stringResource(R.string.my_location),
                    )
                }
            }
        }
    }

    val selected = state.selected
    val reporting = reportPlaceId
    if (selected != null && reporting == selected.place.id) {
        ReportSheet(
            place = selected.place,
            isSending = state.isSendingReport,
            sendsReports = state.sendsReports,
            onSend = { report ->
                onReport(report) { done ->
                    // Close both sheets, so the snackbar with the result is visible.
                    if (done) {
                        reportPlaceId = null
                        onSelect(null)
                    }
                }
            },
            onDismiss = { reportPlaceId = null },
        )
    } else if (selected != null) {
        PlaceDetailSheet(
            item = selected,
            showMapButton = state.showList,
            onDismiss = { onSelect(null) },
            onRoute = { openRoute(context, selected.place) },
            onShowOnMap = { onShowOnMap(selected.place.id) },
            onReport = { reportPlaceId = selected.place.id },
        )
    }

    if (showIconSheet) {
        AppIconSheet(
            selected = appIcon,
            onSelect = onAppIconChange,
            onDismiss = { showIconSheet = false },
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
