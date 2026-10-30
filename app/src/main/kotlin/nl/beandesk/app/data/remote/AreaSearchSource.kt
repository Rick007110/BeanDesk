package nl.beandesk.app.data.remote

import nl.beandesk.core.export.CityExport

/**
 * Searches places around a town or address with the edge function `search`
 * (supabase/functions/search). The answer has the city-export format; its city is the label of
 * what was found and its center the found point.
 */
interface AreaSearchSource {
    /** Throws [SearchException] when nothing comes of it. */
    suspend fun search(query: String): CityExport

    /** Asks for the surroundings of a reported place to be fetched from OpenStreetMap again. */
    suspend fun refreshAround(placeId: Long)
}

enum class SearchFailure {
    // No town or address with that name.
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
    override suspend fun search(query: String): CityExport = throw SearchException(SearchFailure.NOT_SUPPORTED)

    override suspend fun refreshAround(placeId: Long) = Unit
}
