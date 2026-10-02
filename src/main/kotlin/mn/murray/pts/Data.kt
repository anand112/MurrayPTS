package mn.murray.pts

import kotlinx.serialization.Serializable

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

@Serializable
data class Project(
    val id: Int,
    val code: String,
    val name: String,
    val status: String,
    val progress: Int,
    val manager: String,
    val due: String,
    val type: String = "",
)

val TASK_STATUSES = listOf("Not started", "In progress", "On hold", "Blocked", "Completed")

val ACCESS_LEVELS = listOf("Viewer", "Manager", "Admin")

val PERMISSION_LEVELS = listOf("Viewer", "Manager", "Admin")

@Serializable
data class TaskItem(
    val title: String,
    val project: String,
    val due: String,
    val priority: String,
    val assignee: String = "",
    val status: String = "Not started",
    val id: Int = 0,
    val bucket: String = "Management",
)

@Serializable
data class Member(
    val employeeNumber: String = "",
    val name: String,
    val department: String = "",
    val position: String = "",
)

@Serializable
data class Forecast(val project: String, val period: String, val hours: String, val note: String)

@Serializable
data class Lesson(val title: String, val project: String, val summary: String)

@Serializable
data class TodoItem(val id: Int = 0, val title: String, val done: Boolean, val list: Int = 1)

@Serializable
data class TodoList(val id: Int, val name: String)

@Serializable
data class Position(
    val id: Int,
    val name: String,
    val permissionLevel: String,
    val description: String = ""
)

@Serializable
data class AccessRow(
    val employeeNumber: String,
    val name: String,
    val position: String,
    val access: String,
    val password: String = "",
) {
    /** Effective password: the stored one, or the employee number when none has been set yet. */
    fun effectivePassword(): String = password.ifEmpty { employeeNumber }
}

@Serializable
data class PtsData(
    val projects: List<Project> = emptyList(),
    val assignedTasks: List<TaskItem> = emptyList(),
    val team: List<Member> = emptyList(),
    val forecasts: List<Forecast> = emptyList(),
    val lessons: List<Lesson> = emptyList(),
    val otherTasks: List<TaskItem> = emptyList(),
    val buckets: List<String> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val todoLists: List<TodoList> = emptyList(),
    val access: List<AccessRow> = emptyList(),
    val positions: List<Position> = emptyList(),
)

fun nextProjectCode(projects: List<Project>, type: String): String {
    val prefix = TYPE_PREFIX[type] ?: "PRJ"
    val regex = Regex("^$prefix-(\\d+)$")
    val max = projects.mapNotNull { p ->
        if (p.type == type) regex.find(p.code)?.groupValues?.get(1)?.toInt() else null
    }.maxOrNull() ?: 0
    return "$prefix-${(max + 1).toString().padStart(2, '0')}"
}

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

/** Canonical staff roster: employee number, name, department, position. EMP0000 is the super admin, EMP0001 the CEO. */
val TEAM_ROSTER: List<Member> = listOf(
    Member("EMP0000", "Super Admin", "IT / Systems", "Super Admin"),
    Member("EMP0001", "Baatar Uuganbayar", "Executive", "CEO"),
    Member("EMP0002", "Sarangerel Bold", "Projects", "Project Manager"),
    Member("EMP0003", "Ganbold Erdene", "Operations", "Site Supervisor"),
    Member("EMP0004", "Oyunchimeg Dorj", "Human Resources", "HR Lead"),
    Member("EMP0005", "Munkhbat Tsogt", "HSE", "Safety Officer"),
    Member("EMP0006", "Altantsetseg Khan", "HSE", "Environmental Advisor"),
    Member("EMP0007", "Erdenechuluun Bat", "Engineering", "Mining Engineer"),
    Member("EMP0008", "Solongo Ganbaatar", "Geology", "Geologist"),
    Member("EMP0009", "Temuulen Amgalan", "Engineering", "Drill & Blast Engineer"),
    Member("EMP0010", "Narantsetseg Purev", "Procurement", "Procurement Lead"),
    Member("EMP0011", "Batbayar Nyam", "Engineering", "Mechanical Engineer"),
    Member("EMP0012", "Uyanga Lkham", "Engineering", "Electrical Engineer"),
    Member("EMP0013", "Chuluun Munkh", "Survey", "Surveyor"),
    Member("EMP0014", "Anujin Tseren", "Quality", "QA/QC Engineer"),
    Member("EMP0015", "Ganbaatar Khos", "Logistics", "Logistics Coordinator"),
    Member("EMP0016", "Munkhzaya Dorj", "Finance", "Finance Analyst"),
    Member("EMP0017", "Erdenesukh Baasandorj", "HSE", "HSE Advisor"),
    Member("EMP0018", "Tsetsegmaa Ulziibayar", "Community Relations", "Community Liaison"),
    Member("EMP0019", "Boldbaatar Gantulga", "Operations", "Heavy Equipment Operator"),
    Member("EMP0020", "Khulan Serjee", "Administration", "Camp Administrator"),
    Member("EMP0021", "Jargalsaikhan Otgonbayar", "Logistics", "Fleet Manager"),
    Member("EMP0022", "Myagmarjav Enkhjin", "Human Resources", "Training Coordinator"),
)

val ROSTER_BY_NAME: Map<String, Member> = TEAM_ROSTER.associateBy { it.name }

object DemoData {
    val projects: List<Project> get() = JsonStore.data().projects
    val assignedTasks: List<TaskItem> get() = JsonStore.data().assignedTasks
    val team: List<Member> get() = JsonStore.data().team
    val forecasts: List<Forecast> get() = JsonStore.data().forecasts
    val lessons: List<Lesson> get() = JsonStore.data().lessons
    val otherTasks: List<TaskItem> get() = JsonStore.data().otherTasks
    val buckets: List<String> get() = JsonStore.data().buckets
    val todos: List<TodoItem> get() = JsonStore.data().todos
    val todoLists: List<TodoList> get() = JsonStore.data().todoLists
    val access: List<AccessRow> get() = JsonStore.data().access
    val positions: List<Position> get() = JsonStore.data().positions

    fun userFor(employeeNumber: String): AccessRow =
        access.firstOrNull { it.employeeNumber == employeeNumber }
            ?: AccessRow(employeeNumber, "Employee $employeeNumber", "Team Member", "Viewer")

    fun seed(): PtsData = PtsData(
        projects = listOf(
            Project(1, "SIT-01", "Tavan Tolgoi East Expansion", "Ongoing", 64, "Sarangerel Bold", "2026-12-15", "Site projects"),
            Project(2, "SIT-02", "Oyu Tolgoi Haul Road Upgrade", "Blocked", 38, "Ganbold Erdene", "2026-11-02", "Site projects"),
            Project(3, "MFG-01", "Erdenet Crusher Modernisation", "Ongoing", 71, "Erdenechuluun Bat", "2027-03-20", "Manufacturing"),
            Project(4, "SIT-03", "Nalaikh Tailings Remediation", "Not started", 12, "Altantsetseg Khan", "2027-06-30", "Site projects"),
            Project(5, "SIT-04", "Tsaidam Lithium Exploration", "Ongoing", 45, "Solongo Ganbaatar", "2026-10-18", "Site projects"),
            Project(6, "SIT-05", "Boroo Gold Pit Stage 3", "Completed", 100, "Temuulen Amgalan", "2026-08-01", "Site projects"),
            Project(7, "SIT-06", "Zuunmod Camp Construction", "Ongoing", 58, "Khulan Serjee", "2026-12-01", "Site projects"),
            Project(8, "RMS-01", "Darkhan Conveyor Replacement", "Blocked", 26, "Batbayar Nyam", "2026-10-25", "Repair/ Maintenance/ Other Services"),
            Project(9, "SIT-07", "Sainshand Solar Farm Integration", "Not started", 8, "Uyanga Lkham", "2027-05-12", "Site projects"),
            Project(10, "MFG-02", "Ulaangom Gravel Production", "Completed", 100, "Ganbaatar Khos", "2026-07-15", "Manufacturing"),
            Project(11, "SIT-08", "Murun Truck Shop Extension", "Ongoing", 52, "Jargalsaikhan Otgonbayar", "2027-01-30", "Site projects"),
            Project(12, "RMS-02", "Choibalsan Bridge Repair", "Blocked", 33, "Chuluun Munkh", "2026-11-20", "Repair/ Maintenance/ Other Services"),
            Project(13, "LAB-01", "Altai Operations Crew Supply", "Ongoing", 67, "Anujin Tseren", "2026-12-22", "Labour supply"),
            Project(14, "RMS-03", "Khovsgel Slope Stabilisation", "Not started", 15, "Erdenesukh Baasandorj", "2027-04-08", "Repair/ Maintenance/ Other Services"),
            Project(15, "LAB-02", "Oyu Tolgoi Underground Crew Supply", "On hold", 49, "Munkhbat Tsogt", "2027-02-14", "Labour supply"),
            Project(16, "SIT-09", "Erdenet Training Centre Fit-out", "Completed", 100, "Myagmarjav Enkhjin", "2026-09-01", "Site projects"),
        ),
        assignedTasks = listOf(
            TaskItem("Review SIT-01 blast vibration report", "Tavan Tolgoi East Expansion", "2026-10-03", "High", "EMP0002"),
            TaskItem("Approve RMS-01 conveyor gearbox purchase", "Darkhan Conveyor Replacement", "2026-10-06", "High", "EMP0011"),
            TaskItem("Sign off SIT-02 recovery schedule", "Oyu Tolgoi Haul Road Upgrade", "2026-10-10", "Medium", "EMP0003"),
        ),
        team = TEAM_ROSTER,
        forecasts = listOf(
            Forecast("Tavan Tolgoi East Expansion", "Q4 2026", "12,400 hrs", "Crusher civils peak; two crews planned."),
            Forecast("Oyu Tolgoi Haul Road Upgrade", "Q4 2026", "8,900 hrs", "Recovery plan adds night shift from November."),
            Forecast("Erdenet Crusher Modernisation", "Q1 2027", "6,200 hrs", "Commissioning window with tapered crew."),
        ),
        lessons = listOf(
            Lesson("Early procurement of long-lead items", "Boroo Gold Pit Stage 3", "Ordering the crusher liner six months ahead saved five weeks of schedule."),
            Lesson("Monsoon drainage planning", "Choibalsan Bridge Repair", "Undersized culverts caused rework; the design standard needs revising."),
            Lesson("Camp capacity buffers", "Zuunmod Camp Construction", "Keeping 10% spare beds avoided rotation delays during peak crew changes."),
        ),
        otherTasks = listOf(
            TaskItem("Update HSE induction deck", "", "2026-10-15", "Low"),
            TaskItem("Refresh project code register", "", "2026-10-30", "Medium"),
        ),
        buckets = listOf("Management"),
        todos = listOf(
            TodoItem(id = 1, title = "Confirm Q4 fleet servicing slots", done = false, list = 1),
            TodoItem(id = 2, title = "Reply to Erdenet client survey", done = false, list = 1),
        ),
        todoLists = listOf(
            TodoList(1, "General"),
        ),
        access = listOf(
            AccessRow("EMP0000", "Super Admin", "Super Admin", "Admin"),
            AccessRow("EMP0001", "Baatar Uuganbayar", "CEO", "Admin"),
            AccessRow("EMP0002", "Sarangerel Bold", "Project Manager", "Manager"),
            AccessRow("EMP0003", "Ganbold Erdene", "Site Supervisor", "Manager"),
            AccessRow("EMP0016", "Munkhzaya Dorj", "Finance Analyst", "Viewer"),
            AccessRow("EMP0020", "Khulan Serjee", "Camp Administrator", "Viewer"),
        ),
        positions = listOf(
            Position(1, "Super Admin", "Admin", "Full system access including position management"),
            Position(2, "CEO", "Admin", "Executive level access with full permissions"),
            Position(3, "Project Manager", "Manager", "Can manage projects and assigned tasks"),
            Position(4, "Site Supervisor", "Manager", "Can manage projects and assigned tasks"),
            Position(5, "HR Lead", "Manager", "Can manage team and access control"),
            Position(6, "Finance Analyst", "Viewer", "Read-only access to projects and tasks"),
            Position(7, "Camp Administrator", "Viewer", "Read-only access to projects and tasks"),
            Position(8, "Safety Officer", "Viewer", "Read-only access to projects and tasks"),
            Position(9, "Mining Engineer", "Viewer", "Read-only access to projects and tasks"),
            Position(10, "Geologist", "Viewer", "Read-only access to projects and tasks"),
            Position(11, "Drill & Blast Engineer", "Viewer", "Read-only access to projects and tasks"),
            Position(12, "Procurement Lead", "Viewer", "Read-only access to projects and tasks"),
            Position(13, "Mechanical Engineer", "Viewer", "Read-only access to projects and tasks"),
            Position(14, "Electrical Engineer", "Viewer", "Read-only access to projects and tasks"),
            Position(15, "Surveyor", "Viewer", "Read-only access to projects and tasks"),
            Position(16, "QA/QC Engineer", "Viewer", "Read-only access to projects and tasks"),
            Position(17, "Logistics Coordinator", "Viewer", "Read-only access to projects and tasks"),
            Position(18, "HSE Advisor", "Viewer", "Read-only access to projects and tasks"),
            Position(19, "Community Liaison", "Viewer", "Read-only access to projects and tasks"),
            Position(20, "Heavy Equipment Operator", "Viewer", "Read-only access to projects and tasks"),
            Position(21, "Fleet Manager", "Viewer", "Read-only access to projects and tasks"),
            Position(22, "Training Coordinator", "Viewer", "Read-only access to projects and tasks"),
        ),
    )
}
