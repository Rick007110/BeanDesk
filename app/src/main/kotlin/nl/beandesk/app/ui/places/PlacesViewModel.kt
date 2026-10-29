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

data class PlaceItem(val place: Place, val status: OpenStatus)

data class PlacesUiState(
    val items: List<PlaceItem> = emptyList(),
    val filter: PlaceFilter = PlaceFilter(),
    val selected: PlaceItem? = null,
    val showList: Boolean = false,
    val center: LatLng? = null,
    val attribution: String = "",
    val isRefreshing: Boolean = false,
    val hasError: Boolean = false,
)

private data class UiFlags(
    val filter: PlaceFilter = PlaceFilter(),
    val selectedId: Long? = null,
    val showList: Boolean = false,
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

    fun select(placeId: Long?) = flags.update { it.copy(selectedId = placeId) }

    fun toggleList() = flags.update { it.copy(showList = !it.showList) }

    private fun buildState(places: List<Place>, info: CityInfo?, flags: UiFlags): PlacesUiState {
        val now = ZonedDateTime.now(ZoneId.of(info?.timezone ?: DEFAULT_TIMEZONE))
        val at = WeekTime(Weekday.entries[now.dayOfWeek.value - 1], now.hour * 60 + now.minute)
        val items = places
            .filterNot { it.isReportedClosed }
            .map { PlaceItem(it, statusOf(it, at)) }
            .filter { flags.filter.matches(it.place, it.status, at) }
            // TODO: sort by distance (core.geo.distanceMeters) once the app knows the user's location.
            .sortedBy { it.place.name.lowercase() }
        return PlacesUiState(
            items = items,
            filter = flags.filter,
            selected = items.firstOrNull { it.place.id == flags.selectedId },
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
