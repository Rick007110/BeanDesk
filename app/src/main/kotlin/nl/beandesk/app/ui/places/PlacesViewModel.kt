package nl.beandesk.app.ui.places

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.beandesk.app.BeanDeskApplication
import nl.beandesk.app.BuildConfig
import nl.beandesk.app.data.Area
import nl.beandesk.app.data.CityInfo
import nl.beandesk.app.data.PlaceRepository
import nl.beandesk.app.data.location.LocationRepository
import nl.beandesk.app.data.remote.SearchException
import nl.beandesk.app.data.remote.SearchFailure
import nl.beandesk.core.filter.PlaceFilter
import nl.beandesk.core.filter.matchesText
import nl.beandesk.core.geo.distanceMeters
import nl.beandesk.core.hours.OpenStatus
import nl.beandesk.core.hours.OpeningHours
import nl.beandesk.core.hours.WeekTime
import nl.beandesk.core.hours.Weekday
import nl.beandesk.core.model.LatLng
import nl.beandesk.core.model.Place
import nl.beandesk.core.model.Report
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

data class PlaceItem(val place: Place, val status: OpenStatus, val distanceMeters: Double? = null)

data class PlacesUiState(
    // The city, or the town or address that was searched.
    val areaName: String = "",
    val areaKey: String = "",
    // True after a search, so the screen can offer the way back to the city ([homeName]).
    val isSearchArea: Boolean = false,
    val homeName: String = "",
    val searchText: String = "",
    val isSearching: Boolean = false,
    // False with sample data: then only the list itself can be searched.
    val canSearchAreas: Boolean = true,
    val items: List<PlaceItem> = emptyList(),
    // Visible places before the filters, for "3 van 11".
    val totalCount: Int = 0,
    val filter: PlaceFilter = PlaceFilter(),
    // The place whose detail sheet is open.
    val selected: PlaceItem? = null,
    // Where the map should move to: the last place tapped or sent to the map.
    val focus: LatLng? = null,
    val userLocation: LatLng? = null,
    val showList: Boolean = true,
    val center: LatLng? = null,
    val attribution: String = "",
    val isRefreshing: Boolean = false,
    val hasError: Boolean = false,
    val isSendingReport: Boolean = false,
    // False with sample data: reports then stay on the phone.
    val sendsReports: Boolean = false,
)

/** One-off messages for the snackbar. */
enum class PlacesMessage {
    REPORT_SENT,
    REPORT_SAVED_LOCALLY,
    REPORT_RATE_LIMITED,
    REPORT_FAILED,
    SEARCH_NOT_FOUND,
    SEARCH_UNAVAILABLE,
    SEARCH_FAILED,
}

private data class UiFlags(
    val filter: PlaceFilter = PlaceFilter(),
    val isSearching: Boolean = false,
    val selectedId: Long? = null,
    val focusId: Long? = null,
    // The list is the start screen; the map is one tap away.
    val showList: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasError: Boolean = false,
    val isSendingReport: Boolean = false,
)

private data class AreaData(val area: Area, val places: List<Place>, val info: CityInfo?)

@OptIn(ExperimentalCoroutinesApi::class)
class PlacesViewModel(
    private val repository: PlaceRepository,
    private val locationRepository: LocationRepository,
    city: String,
    private val canSearchAreas: Boolean,
) : ViewModel() {
    private val homeArea = Area(key = city, label = city.replaceFirstChar { it.titlecase(Locale.ROOT) })
    private val area = MutableStateFlow(homeArea)
    private val flags = MutableStateFlow(UiFlags())

    /**
     * What is typed in the search field; narrows the list while typing. Compose state that the
     * field reads directly: going through [state], which is built on a background thread, hands
     * the field an older value while the user types on, and letters end up in the wrong place.
     */
    var searchText by mutableStateOf("")
        private set

    private val locationGranted = MutableStateFlow(false)
    private val messageChannel = Channel<PlacesMessage>(Channel.BUFFERED)
    val messages: Flow<PlacesMessage> = messageChannel.receiveAsFlow()

    // Parsed opening hours per distinct opening_hours text; many places share the same value.
    // Only touched from the combine below, which runs sequentially.
    private val hoursCache = HashMap<String, OpeningHours?>()

    // Re-evaluates "nu open" and "sluit over" every minute.
    private val minuteTicks = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    // Location updates run only while the screen collects the state and permission is granted.
    private val userLocation: Flow<LatLng?> = locationGranted.flatMapLatest { granted ->
        if (granted) locationRepository.updates() else flowOf(null)
    }

    private val areaData: Flow<AreaData> = area.flatMapLatest { current ->
        combine(repository.places(current.key), repository.cityInfo(current.key)) { places, info ->
            AreaData(current, places, info)
        }
    }

    val state: StateFlow<PlacesUiState> =
        combine(areaData, flags, snapshotFlow { searchText }, userLocation, minuteTicks) { data, uiFlags, text, location, _ ->
            buildState(data, uiFlags, text, location)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacesUiState())

    init {
        refresh()
    }

    /** Downloads the current area again: the city, or the last search. */
    fun refresh() {
        if (flags.value.isRefreshing) return
        val current = area.value
        viewModelScope.launch {
            flags.update { it.copy(isRefreshing = true, hasError = false) }
            val failed = try {
                if (current.query != null) repository.search(current.query) else repository.refresh(current.key)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refreshing ${current.key} failed", e)
                true
            }
            flags.update { it.copy(isRefreshing = false, hasError = failed) }
        }
    }

    fun setSearchText(text: String) {
        searchText = text
    }

    /**
     * Looks up the typed town or address and shows the places around it. The first search of
     * an area can take a while: the backend then fetches it from OpenStreetMap.
     */
    fun searchArea() {
        val query = searchText.trim()
        if (query.length < 2 || flags.value.isSearching || !canSearchAreas) return
        viewModelScope.launch {
            flags.update { it.copy(isSearching = true) }
            try {
                val found = repository.search(query)
                area.value = found
                // The text was a place, not a name to filter on; the list now shows that place.
                searchText = ""
                flags.update { it.copy(selectedId = null, focusId = null, hasError = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Searching \"$query\" failed", e)
                messageChannel.send(
                    when ((e as? SearchException)?.reason) {
                        SearchFailure.NOT_FOUND -> PlacesMessage.SEARCH_NOT_FOUND
                        SearchFailure.UNAVAILABLE -> PlacesMessage.SEARCH_UNAVAILABLE
                        else -> PlacesMessage.SEARCH_FAILED
                    },
                )
            } finally {
                flags.update { it.copy(isSearching = false) }
            }
        }
    }

    /** Back from a search to the city. */
    fun showHomeArea() {
        area.value = homeArea
        flags.update { it.copy(selectedId = null, focusId = null, hasError = false) }
    }

    fun setLocationGranted(granted: Boolean) {
        locationGranted.value = granted
    }

    fun setFilter(filter: PlaceFilter) = flags.update { it.copy(filter = filter) }

    fun clearFilters() {
        searchText = ""
        flags.update { it.copy(filter = PlaceFilter()) }
    }

    /** Opens the detail sheet of a place, or closes it with null. */
    fun select(placeId: Long?) = flags.update {
        it.copy(selectedId = placeId, focusId = placeId ?: it.focusId)
    }

    /** Switches to the map, centred on the place. */
    fun showOnMap(placeId: Long) = flags.update {
        it.copy(showList = false, selectedId = null, focusId = placeId)
    }

    fun toggleList() = flags.update { it.copy(showList = !it.showList) }

    /** Calls [onDone] with true when the report was sent or saved, so the form can close. */
    fun report(report: Report, onDone: (Boolean) -> Unit) {
        if (report.isEmpty || flags.value.isSendingReport) return
        viewModelScope.launch {
            flags.update { it.copy(isSendingReport = true) }
            val message = try {
                repository.report(report)
                if (repository.sendsReports) PlacesMessage.REPORT_SENT else PlacesMessage.REPORT_SAVED_LOCALLY
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sending report failed", e)
                // The database trigger raises "rate_limit: ..." past the daily limit.
                if (e.message?.contains("rate_limit") == true) PlacesMessage.REPORT_RATE_LIMITED else PlacesMessage.REPORT_FAILED
            }
            flags.update { it.copy(isSendingReport = false) }
            messageChannel.send(message)
            onDone(message == PlacesMessage.REPORT_SENT || message == PlacesMessage.REPORT_SAVED_LOCALLY)
            if (message == PlacesMessage.REPORT_SENT) repository.refreshAround(report.placeId)
        }
    }

    private fun buildState(data: AreaData, flags: UiFlags, searchText: String, location: LatLng?): PlacesUiState {
        val (current, places, info) = data
        val now = ZonedDateTime.now(ZoneId.of(info?.timezone ?: DEFAULT_TIMEZONE))
        val at = WeekTime(Weekday.entries[now.dayOfWeek.value - 1], now.hour * 60 + now.minute)
        val visible = places.filterNot { it.isReportedClosed }
        // After a search the nearest to the searched place come first, otherwise the nearest to the user.
        val anchor = if (current != homeArea) info?.center else location
        val items = visible
            .map { PlaceItem(it, statusOf(it, at), location?.let { here -> distanceMeters(here, it.location) }) }
            .filter { flags.filter.matches(it.place, it.status, at) && it.place.matchesText(searchText) }
            // Open places first, then the nearest (or by name while there is nothing to measure from).
            .sortedWith(
                compareBy<PlaceItem>(
                    { it.status.sortRank() },
                    { item -> anchor?.let { distanceMeters(it, item.place.location) } ?: Double.MAX_VALUE },
                    { it.place.name.lowercase() },
                ),
            )
        return PlacesUiState(
            areaName = info?.label ?: current.label,
            areaKey = current.key,
            isSearchArea = current != homeArea,
            homeName = homeArea.label,
            searchText = searchText,
            isSearching = flags.isSearching,
            canSearchAreas = canSearchAreas,
            items = items,
            totalCount = visible.size,
            filter = flags.filter,
            selected = items.firstOrNull { it.place.id == flags.selectedId },
            focus = visible.firstOrNull { it.id == flags.focusId }?.location,
            userLocation = location,
            showList = flags.showList,
            center = info?.center,
            attribution = info?.attribution.orEmpty(),
            isRefreshing = flags.isRefreshing,
            hasError = flags.hasError,
            isSendingReport = flags.isSendingReport,
            sendsReports = repository.sendsReports,
        )
    }

    private fun statusOf(place: Place, at: WeekTime): OpenStatus {
        val text = place.openingHours ?: return OpenStatus.Unknown
        val hours = if (text in hoursCache) hoursCache[text] else OpeningHours.parse(text).also { hoursCache[text] = it }
        return hours?.status(at) ?: OpenStatus.Unknown
    }

    private fun OpenStatus.sortRank(): Int = when (this) {
        is OpenStatus.Open -> 0
        OpenStatus.Unknown -> 1
        is OpenStatus.Closed -> 2
    }

    companion object {
        private const val TAG = "PlacesViewModel"
        private const val DEFAULT_TIMEZONE = "Europe/Amsterdam"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BeanDeskApplication
                PlacesViewModel(
                    repository = app.container.placeRepository,
                    locationRepository = app.container.locationRepository,
                    city = BuildConfig.DEFAULT_CITY,
                    canSearchAreas = !BuildConfig.USE_SAMPLE_DATA,
                )
            }
        }
    }
}
