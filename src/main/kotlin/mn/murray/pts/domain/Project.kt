package mn.murray.pts.domain

/**
 * Project domain model and the constants/helpers that describe how projects are
 * coded, typed and status-mapped.
 *
 * These types were relocated verbatim from the original `Data.kt` so the JSON
 * importer and the SQLite repositories share one canonical model.
 */

val PROJECT_TYPES = listOf(
    "Site projects",
    "Manufacturing",
    "Repair/ Maintenance/ Other Services",
    "Labour supply",
)

val TYPE_PREFIX = mapOf(
    "Site projects" to "SIT",
    "Manufacturing" to "MFG",
    "Repair/ Maintenance/ Other Services" to "RMS",
    "Labour supply" to "LAB",
)

val PROJECT_STATUSES = listOf("Ongoing", "On hold", "Not started", "Blocked", "Completed")

private val OLD_STATUS_MAP = mapOf(
    "On track" to "Ongoing",
    "Planning" to "Not started",
    "At risk" to "Blocked",
    "Done" to "Completed",
)

fun migrateStatus(status: String): String = OLD_STATUS_MAP[status] ?: status

@kotlinx.serialization.Serializable
data class Project(
    val id: Int,
    val code: String,
    val name: String,
    val status: String,
    val progress: Int,
    val manager: String,
    val due: String,
    val type: String = "",
    val assignees: List<String> = emptyList(),
)

/** Old code -> (new code, type, renamed name or null). Applied once to files written before types existed. */
val LEGACY_PROJECTS = mapOf(
    "TT-01" to Triple("SIT-01", "Site projects", null),
    "OT-02" to Triple("SIT-02", "Site projects", null),
    "ER-03" to Triple("MFG-01", "Manufacturing", null),
    "NT-04" to Triple("SIT-03", "Site projects", null),
    "TS-05" to Triple("SIT-04", "Site projects", null),
    "BT-06" to Triple("SIT-05", "Site projects", null),
    "ZM-07" to Triple("SIT-06", "Site projects", null),
    "DK-08" to Triple("RMS-01", "Repair/ Maintenance/ Other Services", null),
    "SN-09" to Triple("SIT-07", "Site projects", null),
    "UG-10" to Triple("MFG-02", "Manufacturing", "Ulaangom Gravel Production"),
    "MT-11" to Triple("SIT-08", "Site projects", null),
    "CB-12" to Triple("RMS-02", "Repair/ Maintenance/ Other Services", null),
    "AT-13" to Triple("LAB-01", "Labour supply", "Altai Operations Crew Supply"),
    "HS-14" to Triple("RMS-03", "Repair/ Maintenance/ Other Services", null),
    "OT-15" to Triple("LAB-02", "Labour supply", "Oyu Tolgoi Underground Crew Supply"),
    "ET-16" to Triple("SIT-09", "Site projects", null),
)

fun nextProjectCode(projects: List<Project>, type: String): String {
    val prefix = TYPE_PREFIX[type] ?: "PRJ"
    val regex = Regex("^$prefix-(\\d+)$")
    val max = projects.mapNotNull { p ->
        if (p.type == type) regex.find(p.code)?.groupValues?.get(1)?.toInt() else null
    }.maxOrNull() ?: 0
    return "$prefix-${(max + 1).toString().padStart(2, '0')}"
}
