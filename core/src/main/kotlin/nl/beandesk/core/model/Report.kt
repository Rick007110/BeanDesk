package nl.beandesk.core.model

/** One tap on "Klopt dit nog?": what a user says about a place right now. Null means "not reported". */
data class Report(
    val placeId: Long,
    val wifi: WifiStatus? = null,
    val power: PowerLevel? = null,
    val quiet: Boolean? = null,
    val laptopPolicy: LaptopPolicy? = null,
    val permanentlyClosed: Boolean = false,
) {
    /** The database refuses reports without any fact. */
    val isEmpty: Boolean
        get() = wifi == null && power == null && quiet == null && laptopPolicy == null && !permanentlyClosed
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
