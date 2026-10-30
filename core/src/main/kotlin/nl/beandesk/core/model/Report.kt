package nl.beandesk.core.model

/** One tap on "Klopt dit nog?": what a user says about a place right now. Null means "not reported". */
data class Report(
    val placeId: Long,
    val wifi: WifiStatus? = null,
    val power: PowerLevel? = null,
    val quiet: Boolean? = null,
    val laptopPolicy: LaptopPolicy? = null,
    val permanentlyClosed: Boolean = false,
    // 1 to 5; stored apart from the facts, one rating per user per place.
    val stars: Int? = null,
) {
    /** Whether there is a fact for place_reports; the database refuses reports without one. */
    val hasFacts: Boolean
        get() = wifi != null || power != null || quiet != null || laptopPolicy != null || permanentlyClosed

    val isEmpty: Boolean
        get() = !hasFacts && stars == null
}

/**
 * The rating after someone gives [stars], counted as a new rating. When that user had rated the
 * place before, the next refresh corrects it: the database keeps one rating per user.
 */
fun Rating?.withStars(stars: Int): Rating {
    val current = this ?: Rating(0.0, 0)
    val count = current.count + 1
    return Rating((current.average * current.count + stars) / count, count)
}

/**
 * The community facts after [report], following the same rule as the database trigger:
 * the latest report wins per fact, and closure reports add up.
 */
fun CommunityFacts?.withReport(report: Report): CommunityFacts {
    val current = this ?: CommunityFacts()
    return CommunityFacts(
        wifi = report.wifi ?: current.wifi,
        power = report.power ?: current.power,
        quiet = report.quiet ?: current.quiet,
        laptopPolicy = report.laptopPolicy ?: current.laptopPolicy,
        closedReports = current.closedReports + if (report.permanentlyClosed) 1 else 0,
    )
}
