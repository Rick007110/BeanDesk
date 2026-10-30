package nl.beandesk.app.data.remote

import nl.beandesk.core.export.CityExport

/** What a search found: places by name across the country, or the places around a town or address. */
data class SearchResult(val export: CityExport, val byName: Boolean)

/**
 * Searches with the edge function `search` (supabase/functions/search): places whose name matches,
 * or the places around a town or address. The answer has the city-export format; its city is the
 * label of what was found.
 */
interface AreaSearchSource {
    /** Throws [SearchException] when nothing comes of it. */
    suspend fun search(query: String): SearchResult

    /** Asks for the surroundings of a reported place to be fetched from OpenStreetMap again. */
    suspend fun refreshAround(placeId: Long)
}

enum class SearchFailure {
    // No place by that name, and no town or address.
    NOT_FOUND,

    // OpenStreetMap could not be reached or is busy, and the area is not known yet.
    UNAVAILABLE,

    // Network trouble or anything else.
    FAILED,

    // Sample data: there is no backend to search.
    NOT_SUPPORTED,
}

class SearchException(val reason: SearchFailure, cause: Throwable? = null) : Exception(reason.name, cause)

/** With sample data there is no backend, so only the list itself can be searched. */
object NoAreaSearch : AreaSearchSource {
    override suspend fun search(query: String): SearchResult = throw SearchException(SearchFailure.NOT_SUPPORTED)

    override suspend fun refreshAround(placeId: Long) = Unit
}
