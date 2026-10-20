package mn.murray.pts.domain

/**
 * Project domain model and the constants/helpers that describe how projects are
 * categorised, coded and status-mapped.
 *
 * Projects belong to exactly one of nine letter-coded categories. The category LABEL is
 * what gets stored (`Projects.type`), the letter is derived from [TYPE_PREFIX] and is
 * also the first token of the project number (`S-01`, `R-12`). Keeping the label in the
 * column means the board needs no lookup table, and the one-letter prefix keeps codes
 * short enough to read in a task title.
 *
 * These types were relocated from the original `Data.kt` so the JSON importer and the
 * SQLite repositories share one canonical model. Values written before the V3 migration
 * used four categories and three-letter code prefixes; [migrateType] and
 * [LEGACY_PROJECTS] translate them.
 */

val PROJECT_TYPES = listOf(
    "Fabrication/Manufacturing",
    "Repair/Maintenance/other services",
    "Site projects",
    "Labor supply",
    "Distributor product sales",
    "Engineering",
    "Internal works",
    "Goods sales",
    "Other",
)

/** Category label -> the letter that leads its project numbers. */
val TYPE_PREFIX = mapOf(
    "Fabrication/Manufacturing" to "M",
    "Repair/Maintenance/other services" to "R",
    "Site projects" to "S",
    "Labor supply" to "L",
    "Distributor product sales" to "D",
    "Engineering" to "E",
    "Internal works" to "F",
    "Goods sales" to "G",
    "Other" to "O",
)

/** "Site projects" -> "S — Site projects"; unknown types show their raw label. */
fun typeLetter(type: String): String = TYPE_PREFIX[type].orEmpty()

fun typeLabel(type: String): String =
    TYPE_PREFIX[type]?.let { "$it — $type" } ?: type

/**
 * The chip colour a category prints in, keyed off its letter so it survives a label rename.
 *
 * This is the app's one statement of that pairing: the Projects board's panel heads and the
 * Forecasts sheet's panels both put a category chip above the same rows, and a project filed under
 * one colour on one page and another on the next would read as two different kinds of job.
 */
fun typeChipClass(type: String): String = when (TYPE_PREFIX[type]) {
    "M" -> "t-m"
    "R" -> "t-r"
    "S" -> "t-s"
    "L" -> "t-l"
    "D" -> "t-d"
    "E" -> "t-e"
    "F" -> "t-f"
    "G" -> "t-g"
    "O" -> "t-o"
    else -> "planning"
}

val PROJECT_STATUSES = listOf("Ongoing", "On hold", "Not started", "Blocked", "Completed")

/**
 * The order the projects board sorts by status in: not begun, then live, then stuck, then deliberately
 * parked, then finished. It is deliberately **not** [PROJECT_STATUSES] — that list is the picker order,
 * which puts the most common choice first so it is the shortest click, and reading it top to bottom as a
 * progression would look arbitrary on the board. Anything unknown ranks last rather than disappearing.
 */
val PROJECT_STATUS_ORDER = listOf("Not started", "Ongoing", "Blocked", "On hold", "Completed")

/** Sort key for a status label; unknown or blank goes to the end of the list. */
fun statusRank(status: String): Int =
    PROJECT_STATUS_ORDER.indexOf(status).let { if (it < 0) PROJECT_STATUS_ORDER.size else it }

private val OLD_STATUS_MAP = mapOf(
    "On track" to "Ongoing",
    "Planning" to "Not started",
    "At risk" to "Blocked",
    "Done" to "Completed",
)

fun migrateStatus(status: String): String = OLD_STATUS_MAP[status] ?: status

/** Pre-V3 category labels -> current ones. Unknown values are left alone, not guessed. */
private val OLD_TYPE_MAP = mapOf(
    "Manufacturing" to "Fabrication/Manufacturing",
    "Repair/ Maintenance/ Other Services" to "Repair/Maintenance/other services",
    "Labour supply" to "Labor supply",
    // "Site projects" is unchanged and needs no entry.
)

fun migrateType(type: String): String = OLD_TYPE_MAP[type] ?: type

/** Pre-V3 code prefixes and the category letter each one collapsed into. */
private val OLD_CODE_PREFIX = mapOf("SIT" to "S", "MFG" to "M", "RMS" to "R", "LAB" to "L")

/**
 * `SIT-01` -> `S-01`: the V3 categories lead their numbers with a single letter, so rows
 * and files written under the three-letter scheme are re-coded on the way in. Anything
 * that is not one of those four prefixes is left exactly as it was.
 */
fun migrateCode(code: String): String {
    val upper = normalizeProjectCode(code)
    val dash = upper.indexOf('-')
    if (dash <= 0) return upper
    val letter = OLD_CODE_PREFIX[upper.substring(0, dash)] ?: return upper
    return "$letter${upper.substring(dash)}"
}

@kotlinx.serialization.Serializable
data class Project(
    val id: Int,
    val code: String,
    /** The project description — the free-text name shown as the project title. */
    val name: String,
    val status: String,
    val progress: Int,
    val manager: String,
    /** End date, `yyyy-MM-dd`. The Calendar and the due-date alerts both key off this. */
    val due: String,
    val type: String = "",
    val assignees: List<String> = emptyList(),
    val client: String = "",
    /** Start date, `yyyy-MM-dd`; blank when the project has no recorded kick-off. */
    val start: String = "",
    /** Contract value in the company's reporting currency; 0.0 when not yet quoted. */
    val revenue: Double = 0.0,
    /** How much of the contract has actually been invoiced to the client; 0.0 when nothing billed yet (V11). */
    val invoiced: Double = 0.0,
)

/** Old code -> (new code, type, renamed name or null). Applied once to files written before types existed. */
val LEGACY_PROJECTS = mapOf(
    "TT-01" to Triple("S-01", "Site projects", null),
    "OT-02" to Triple("S-02", "Site projects", null),
    "ER-03" to Triple("M-01", "Fabrication/Manufacturing", null),
    "NT-04" to Triple("S-03", "Site projects", null),
    "TS-05" to Triple("S-04", "Site projects", null),
    "BT-06" to Triple("S-05", "Site projects", null),
    "ZM-07" to Triple("S-06", "Site projects", null),
    "DK-08" to Triple("R-01", "Repair/Maintenance/other services", null),
    "SN-09" to Triple("S-07", "Site projects", null),
    "UG-10" to Triple("M-02", "Fabrication/Manufacturing", "Ulaangom Gravel Production"),
    "MT-11" to Triple("S-08", "Site projects", null),
    "CB-12" to Triple("R-02", "Repair/Maintenance/other services", null),
    "AT-13" to Triple("L-01", "Labor supply", "Altai Operations Crew Supply"),
    "HS-14" to Triple("R-03", "Repair/Maintenance/other services", null),
    "OT-15" to Triple("L-02", "Labor supply", "Oyu Tolgoi Underground Crew Supply"),
    "ET-16" to Triple("S-09", "Site projects", null),
)

/**
 * A project number is `<category letter>-<tail>`, the tail being 1-16 characters of upper
 * case alphanumerics or hyphens (`S-01`, `R-12`, `M-2026-A`). Compared after upper-casing.
 */
private val CODE_PATTERN = Regex("[A-Z]-[A-Z0-9][A-Z0-9-]{0,15}")

fun normalizeProjectCode(code: String): String = code.trim().uppercase()

fun isValidProjectCode(code: String): Boolean = CODE_PATTERN.matches(code.trim().uppercase())

/** True when [code] starts with the letter belonging to [type] (`S-01` for Site projects). */
fun codeMatchesType(code: String, type: String): Boolean {
    val prefix = TYPE_PREFIX[type] ?: return false
    return normalizeProjectCode(code).substringBefore('-') == prefix
}

/**
 * The trailing number of every code already used by [type] — the digits after the first
 * hyphen, so legacy `SIT-04` counts towards Site projects alongside `S-04`.
 */
fun nextProjectNumber(projects: List<Project>, type: String): Int {
    val used = projects.filter { migrateType(it.type) == type }.mapNotNull { p ->
        normalizeProjectCode(p.code).substringAfter('-', "").takeWhile { it.isDigit() }.toIntOrNull()
    }
    return (used.maxOrNull() ?: 0) + 1
}

/** The number to pre-fill the create form with: the next free one for [type]. */
fun nextProjectCode(projects: List<Project>, type: String): String {
    val prefix = TYPE_PREFIX[type] ?: "P"
    return "$prefix-${nextProjectNumber(projects, type).toString().padStart(2, '0')}"
}
