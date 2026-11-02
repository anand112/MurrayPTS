package mn.murray.pts.domain

/**
 * Project domain model and the constants/helpers that describe how projects are
 * categorised, coded and status-mapped.
 *
 * Projects belong to exactly one letter-coded category. The category LABEL is what gets stored
 * (`Projects.type`), the letter is the category's key in the catalogue and is also the first token of
 * the project number (`S-01`, `R-12`). Keeping the label in the column means the board needs no lookup
 * per row, and the one-letter prefix keeps codes short enough to read in a task title.
 *
 * The categories themselves became data in Stage 63: `project_types` (V14) holds them, management edits
 * them on the Settings page, and [PROJECT_TYPES] / [TYPE_PREFIX] are the in-process view of that table.
 * [SHIPPED_PROJECT_TYPES] is what V14 seeds and what every helper falls back to before a database has
 * been read.
 *
 * These types were relocated from the original `Data.kt` so the JSON importer and the
 * SQLite repositories share one canonical model. Values written before the V3 migration
 * used four categories and three-letter code prefixes; [migrateType] and
 * [LEGACY_PROJECTS] translate them.
 */

/**
 * One category in the catalogue: the letter its project numbers lead with, and the label stored on the
 * project row. [code] is the primary key, so the pairing is one-to-one by construction — a letter cannot
 * belong to two categories and so cannot produce two projects with the same number.
 */
data class ProjectType(val code: String, val name: String)

/**
 * The nine categories the app shipped with, in the order the boards group them.
 *
 * This is now a *starting* catalogue, not the catalogue: V14 seeds `project_types` from it and management
 * can add, rename and retire rows from Settings. It stays in code for two reasons — a database that has not
 * been read yet still has to answer [typeLetter], [codeMatchesType] and [nextProjectCode] (unit tests and
 * the importer run before any request does), and V14's seed has to come from the same list so the two can
 * never disagree about what the company's categories were on day one.
 */
val SHIPPED_PROJECT_TYPES = listOf(
    ProjectType("M", "Fabrication/Manufacturing"),
    ProjectType("R", "Repair/Maintenance/other services"),
    ProjectType("S", "Site projects"),
    ProjectType("L", "Labor supply"),
    ProjectType("D", "Distributor product sales"),
    ProjectType("E", "Engineering"),
    ProjectType("F", "Internal works"),
    ProjectType("G", "Goods sales"),
    ProjectType("O", "Other"),
)

/**
 * The catalogue as a value, with its two derived views built once.
 *
 * [names] and [prefixes] are precomputed because the view layer reads them per row and per chip: a Projects
 * board asks for the letter of the same category a dozen times while it draws one panel.
 */
private class TypeCatalogue(val rows: List<ProjectType>) {
    val names: List<String> = rows.map { it.name }
    val prefixes: Map<String, String> = rows.associate { it.name to it.code }
}

@Volatile
private var catalogue = TypeCatalogue(SHIPPED_PROJECT_TYPES)

/**
 * Publish [rows] as the catalogue every reader in this process sees.
 *
 * [mn.murray.pts.repository.ProjectTypeRepository.all] is the only caller that matters: reading the table
 * republishes it, so a page that asks the repository is automatically current, and an empty read cannot
 * wipe the catalogue out from under the boards (it falls back to the shipped nine).
 */
fun installProjectTypes(rows: List<ProjectType>) {
    catalogue = TypeCatalogue(rows.ifEmpty { SHIPPED_PROJECT_TYPES })
}

/** Reset to the shipped catalogue; what a test does when it finishes with a live database. */
fun resetProjectTypes() {
    catalogue = TypeCatalogue(SHIPPED_PROJECT_TYPES)
}

/**
 * The category labels, in catalogue order.
 *
 * A getter rather than a list literal since Stage 63 made the categories data: the Projects board's panels,
 * the Forecasts tabs, the create popup's picker and every `?type=` / `fields["type"]` validation on the routes
 * all ask this one question, and they all ask it of the database now.
 */
val PROJECT_TYPES: List<String> get() = catalogue.names

/** Category label -> the letter that leads its project numbers. */
val TYPE_PREFIX: Map<String, String> get() = catalogue.prefixes

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
 *
 * The nine letters the app shipped with each own a class. A category management adds later borrows one
 * by its position in the catalogue: two categories sharing a colour is a cosmetic question, whereas a
 * live category rendered in the generic grey would read like an error on the board.
 */
private val TYPE_CHIP_CLASSES = listOf("t-m", "t-r", "t-s", "t-l", "t-d", "t-e", "t-f", "t-g", "t-o")

fun typeChipClass(type: String): String {
    val letter = TYPE_PREFIX[type] ?: return "planning"
    TYPE_CHIP_CLASSES.firstOrNull { it == "t-${letter.lowercase()}" }?.let { return it }
    val position = catalogue.names.indexOf(type)
    return if (position >= 0) TYPE_CHIP_CLASSES[position % TYPE_CHIP_CLASSES.size] else "planning"
}

/**
 * The five stages a project can be in, in the order a picker lists them and the board sorts them: the progression
 * itself, read top to bottom (**Stage 96**). The list is deliberately **the same five words, in the same order, as
 * [mn.murray.pts.domain.TASK_STATUSES]** — a card's *In progress* and a project's *In progress* used to be two
 * different labels for one idea ("Ongoing"), which is why the Calendar had a map from one to the other and the Team
 * popup had to colour six words to draw five stages. They are now one vocabulary, so that mapping and that extra
 * colour arm are gone. The picker used to lead with the most-used choice to keep the click short; the user asked for
 * the progression instead, and a create form's default (`ProjectService.DEFAULT_STATUS`) is *Not started*, which is
 * this list's first entry, so the shortest click and the honest order are now the same thing.
 */
val PROJECT_STATUSES = listOf("Not started", "In progress", "On hold", "Blocked", "Completed")

/**
 * The order the projects board sorts by status in. Since **Stage 96** it is the progression — the same list the
 * picker prints — because the reader asked the dropdown to read as one sequence and the sort to follow it; keeping
 * two orders that disagreed was the one thing that made the old "most-used first" picker look arbitrary. Anything
 * unknown ranks last rather than disappearing.
 */
val PROJECT_STATUS_ORDER = PROJECT_STATUSES

/** Sort key for a status label; unknown or blank goes to the end of the list. */
fun statusRank(status: String): Int =
    PROJECT_STATUS_ORDER.indexOf(status).let { if (it < 0) PROJECT_STATUS_ORDER.size else it }

private val OLD_STATUS_MAP = mapOf(
    "On track" to "In progress",
    "Ongoing" to "In progress",
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

/**
 * **Stage 109 — a project's money is in its own currency.** Until this stage every amount in the app was
 * tugrik: `projects.revenue` and `projects.invoiced` (V3, V11) were bare `REAL` columns and [mn.murray.pts]
 * printed `₮` on revenue. The user asked for **USD and CNY to be choosable, with tugrik the default when a
 * project is created**, so the currency became a column on the row (V20) and this is its vocabulary.
 *
 * The stored value is the ISO [code]; the [symbol] is what every revenue cell and both revenue boxes print.
 * A project that never chose carries [DEFAULT] — tugrik — which is also what an unrecognised code falls back
 * to in [of], so a hand-edited row can never paint a figure with no sign. Nothing here converts: choosing USD
 * relabels the amount the reader typed, it does not exchange it.
 */
enum class Currency(val code: String, val symbol: String, val label: String) {
    MNT("MNT", "₮", "Tugrik"),
    USD("USD", "$", "US dollar"),
    CNY("CNY", "¥", "Yuan"),
    ;

    companion object {
        /** Tugrik is the default a new project opens on and the fallback for a code the app cannot name. */
        val DEFAULT: Currency = MNT

        /** The currency [code] names, case-insensitively; `null` when it names none (the form's refusal). */
        fun of(code: String?): Currency? =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
    }
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
    /** Contract value in [currency]; 0.0 when not yet quoted. */
    val revenue: Double = 0.0,
    /** How much of the contract has actually been invoiced to the client; 0.0 when nothing billed yet (V11). */
    val invoiced: Double = 0.0,
    /**
     * The currency [revenue] and [invoiced] are written in (Stage 109, V20). Chosen when the project is
     * created and fixed afterwards, like its number; every project written before V20 is [Currency.MNT].
     */
    val currency: Currency = Currency.MNT,
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
