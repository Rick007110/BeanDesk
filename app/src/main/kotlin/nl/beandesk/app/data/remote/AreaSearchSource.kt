package nl.beandesk.app.data.remote

import nl.beandesk.core.export.CityExport

/** What a search found: places by name across the country, or the places around a town or address. */
data class SearchResult(val export: CityExport, val byName: Boolean)

/**
 * Searches on BeanDesk's server (backend/src/search): places whose name matches, or the places
 * around a town or address. The answer has the city-export format; its city is the
 * label of what was found.
 */
interface AreaSearchSource {
    /** Throws [SearchException] when nothing comes of it. */
    suspend fun search(query: String): SearchResult
}

enum class SearchFailure {
    // No place by that name, and no town or address.
    NOT_FOUND,

    // The address lookup (PDOK) could not be reached, and no place has that name.
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
}
