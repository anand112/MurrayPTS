package mn.murray.pts

import kotlinx.serialization.Serializable
import mn.murray.pts.domain.*

/**
 * Transitional bridge file (v2 restructure, Stage 3).
 *
 * The canonical domain models now live in `mn.murray.pts.domain`. This file keeps
 * only the pieces that are still root-package concerns during the migration:
 *
 *  - [PtsData]   – the legacy JSON aggregate (used by JsonStore + the SQLite importer).
 *  - [DemoData]  – the live-store facade and first-run seed. Stage 6 replaces its
 *                  getters with repository calls; Stage 4 moves `seed()` behind the
 *                  `PTS_SEED_DEMO` flag.
 *  - [memberEmail] / [pinnedIds] – helpers that read through [DemoData].
 *
 * Everything else (entities, constants, code/status helpers) moved to `domain/`.
 */

/** Email for a team member: from their account when one exists, otherwise generated from the name. */
fun memberEmail(member: Member): String =
    DemoData.access.firstOrNull { it.employeeNumber == member.employeeNumber }?.displayEmail()
        ?: autoEmail(member.name)

/** Pinned project ids for an account, persisted per employee number. */
fun pinnedIds(employeeNumber: String): List<Int> = DemoData.pins[employeeNumber].orEmpty()

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
    val pins: Map<String, List<Int>> = emptyMap(),
)

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
    val pins: Map<String, List<Int>> get() = JsonStore.data().pins

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
            TaskItem("Review SIT-01 blast vibration report", "Tavan Tolgoi East Expansion", "2026-10-10", "High", "EMP0002", started = "2026-10-01"),
            TaskItem("Approve RMS-01 conveyor gearbox purchase", "Darkhan Conveyor Replacement", "2026-10-15", "High", "EMP0011", started = "2026-10-03"),
            TaskItem("Sign off SIT-02 recovery schedule", "Oyu Tolgoi Haul Road Upgrade", "2026-10-20", "Medium", "EMP0003", started = "2026-10-05"),
            TaskItem("SIT-01 Safety inspection", "Tavan Tolgoi East Expansion", "2026-10-08", "High", "EMP0005", started = "2026-10-04"),
            TaskItem("MFG-01 Equipment testing", "Erdenet Crusher Modernisation", "2026-10-12", "Medium", "EMP0007", started = "2026-10-06"),
            TaskItem("Weekly team meeting", "", "2026-10-07", "Low", "EMP0002", started = "2026-10-07"),
            TaskItem("SIT-04 Geological survey", "Tsaidam Lithium Exploration", "2026-10-18", "High", "EMP0008", started = "2026-10-09"),
            TaskItem("RMS-01 Maintenance review", "Darkhan Conveyor Replacement", "2026-10-14", "Medium", "EMP0011", started = "2026-10-10"),
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
            TaskItem("Update HSE induction deck", "", "2026-10-15", "Low", started = "2026-10-01"),
            TaskItem("Refresh project code register", "", "2026-10-30", "Medium", started = "2026-10-05"),
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
