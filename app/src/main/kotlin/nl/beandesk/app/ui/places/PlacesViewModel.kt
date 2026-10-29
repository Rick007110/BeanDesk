package nl.beandesk.app.ui.places

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.beandesk.app.BeanDeskApplication
import nl.beandesk.app.BuildConfig
import nl.beandesk.app.data.CityInfo
import nl.beandesk.app.data.PlaceRepository
import nl.beandesk.core.filter.PlaceFilter
import nl.beandesk.core.hours.OpenStatus
import nl.beandesk.core.hours.OpeningHours
import nl.beandesk.core.hours.WeekTime
import nl.beandesk.core.hours.Weekday
import nl.beandesk.core.model.LatLng
import nl.beandesk.core.model.Place
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

data class PlaceItem(val place: Place, val status: OpenStatus)

data class PlacesUiState(
    val cityName: String = "",
    val items: List<PlaceItem> = emptyList(),
    // Visible places before the filters, for "3 van 11".
    val totalCount: Int = 0,
    val filter: PlaceFilter = PlaceFilter(),
    // The place whose detail sheet is open.
    val selected: PlaceItem? = null,
    // Where the map should move to: the last place tapped or sent to the map.
    val focus: LatLng? = null,
    val showList: Boolean = true,
    val center: LatLng? = null,
    val attribution: String = "",
    val isRefreshing: Boolean = false,
    val hasError: Boolean = false,
)

private data class UiFlags(
    val filter: PlaceFilter = PlaceFilter(),
    val selectedId: Long? = null,
    val focusId: Long? = null,
    // The list is the start screen; the map is one tap away.
    val showList: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasError: Boolean = false,
)

class PlacesViewModel(
    private val repository: PlaceRepository,
    private val city: String,
) : ViewModel() {
    private val flags = MutableStateFlow(UiFlags())

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

    val state: StateFlow<PlacesUiState> =
        combine(repository.places(city), repository.cityInfo(city), flags, minuteTicks) { places, info, uiFlags, _ ->
            buildState(places, info, uiFlags)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacesUiState())

    init {
        refresh()
    }

    fun refresh() {
        if (flags.value.isRefreshing) return
        viewModelScope.launch {
            flags.update { it.copy(isRefreshing = true, hasError = false) }
            val failed = try {
                repository.refresh(city)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refreshing $city failed", e)
                true
            }
            flags.update { it.copy(isRefreshing = false, hasError = failed) }
        }
    }

    fun setFilter(filter: PlaceFilter) = flags.update { it.copy(filter = filter) }

    fun clearFilters() = flags.update { it.copy(filter = PlaceFilter()) }

    /** Opens the detail sheet of a place, or closes it with null. */
    fun select(placeId: Long?) = flags.update {
        it.copy(selectedId = placeId, focusId = placeId ?: it.focusId)
    }

    /** Switches to the map, centred on the place. */
    fun showOnMap(placeId: Long) = flags.update {
        it.copy(showList = false, selectedId = null, focusId = placeId)
    }

    fun toggleList() = flags.update { it.copy(showList = !it.showList) }

    private fun buildState(places: List<Place>, info: CityInfo?, flags: UiFlags): PlacesUiState {
        val now = ZonedDateTime.now(ZoneId.of(info?.timezone ?: DEFAULT_TIMEZONE))
        val at = WeekTime(Weekday.entries[now.dayOfWeek.value - 1], now.hour * 60 + now.minute)
        val visible = places.filterNot { it.isReportedClosed }
        val items = visible
            .map { PlaceItem(it, statusOf(it, at)) }
            .filter { flags.filter.matches(it.place, it.status, at) }
            // Open places first. TODO: sort by distance once the app knows the user's location.
            .sortedWith(compareBy<PlaceItem>({ it.status.sortRank() }, { it.place.name.lowercase() }))
        return PlacesUiState(
            cityName = city.replaceFirstChar { it.titlecase(Locale.ROOT) },
            items = items,
            totalCount = visible.size,
            filter = flags.filter,
            selected = items.firstOrNull { it.place.id == flags.selectedId },
            focus = visible.firstOrNull { it.id == flags.focusId }?.location,
            showList = flags.showList,
            center = info?.center,
            attribution = info?.attribution.orEmpty(),
            isRefreshing = flags.isRefreshing,
            hasError = flags.hasError,
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
                PlacesViewModel(app.container.placeRepository, BuildConfig.DEFAULT_CITY)
            }
        }
    }
}
