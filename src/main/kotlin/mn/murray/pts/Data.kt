package mn.murray.pts

import kotlinx.serialization.Serializable
import mn.murray.pts.domain.*
import mn.murray.pts.repository.ForecastRepository
import mn.murray.pts.service.ProjectService
import mn.murray.pts.service.TaskService
import mn.murray.pts.service.TodoService
import mn.murray.pts.service.UserService

/**
 * Transitional bridge file (v2 restructure).
 *
 * The canonical domain models live in `mn.murray.pts.domain`. This file keeps only the
 * pieces that are still root-package concerns:
 *
 *  - [PtsData]   – the legacy JSON aggregate, now used *only* by the one-shot SQLite
 *                  importer (`db/migration`) to decode an archived `pts-data.json`.
 *  - [DemoData]  – a thin read facade over the DB-backed services/repositories. As of
 *                  Stage 6 every getter delegates to SQLite; nothing reads the legacy
 *                  JSON store any more. `seed()` survives purely as the demo dataset the
 *                  importer's normalizer borrows its default position catalog from.
 *  - [memberEmail] / [pinnedIds] – helpers that read through the services.
 *
 * Everything else (entities, constants, code/status helpers) moved to `domain/`.
 */

/** Email for a team member: from their account when one exists, otherwise generated from the name. */
fun memberEmail(member: Member): String =
    DemoData.access.firstOrNull { it.employeeNumber == member.employeeNumber }?.displayEmail()
        ?: autoEmail(member.name)

/** Pinned project ids for an account, persisted per employee number. */
fun pinnedIds(employeeNumber: String): List<Int> = ProjectService.pinnedIds(employeeNumber)

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
    // Projects + pins aggregate is DB-backed as of Stage 6a.
    val projects: List<Project> get() = ProjectService.all()
    // Tasks + buckets aggregate is DB-backed as of Stage 6b.
    val assignedTasks: List<TaskItem> get() = TaskService.assigned()
    // People aggregate (team / access / positions) is DB-backed as of Stage 6.
    val team: List<Member> get() = UserService.team()
    // Read-only planning content is DB-backed as of Stage 6d. The lessons that used to sit here
    // became a sheet of their own in V9: see LessonService, which is the only reader now.
    val forecasts: List<Forecast> get() = ForecastRepository.findAll()
    val otherTasks: List<TaskItem> get() = TaskService.other()
    val buckets: List<String> get() = TaskService.buckets()
    // To-do aggregate is DB-backed as of Stage 6c.
    val todos: List<TodoItem> get() = TodoService.allItems()
    val todoLists: List<TodoList> get() = TodoService.allLists()
    val access: List<AccessRow> get() = UserService.accessRows()
    val positions: List<Position> get() = UserService.positions()

    fun userFor(employeeNumber: String): AccessRow = UserService.userFor(employeeNumber)

    fun seed(): PtsData = PtsData(
        projects = listOf(
            Project(1, "S-01", "Tavan Tolgoi East Expansion", "Ongoing", 64, "Sarangerel Bold", "2026-12-15", "Site projects",
                client = "Tavan Tolgoi Coal Complex", start = "2026-02-02", revenue = 4_850_000_000.0),
            Project(2, "S-02", "Oyu Tolgoi Haul Road Upgrade", "Blocked", 38, "Ganbold Erdene", "2026-11-02", "Site projects",
                client = "Oyu Tolgoi LLC", start = "2026-01-19", revenue = 6_120_000_000.0),
            Project(3, "M-01", "Erdenet Crusher Modernisation", "Ongoing", 71, "Erdenechuluun Bat", "2027-03-20", "Fabrication/Manufacturing",
                client = "Erdenet Mining Corporation", start = "2026-04-06", revenue = 2_340_000_000.0),
            Project(4, "S-03", "Nalaikh Tailings Remediation", "Not started", 12, "Altantsetseg Khan", "2027-06-30", "Site projects",
                client = "Nalaikh Coal JSC", start = "2026-09-01", revenue = 1_780_000_000.0),
            Project(5, "S-04", "Tsaidam Lithium Exploration", "Ongoing", 45, "Solongo Ganbaatar", "2026-10-18", "Site projects",
                client = "Tsaidam Minerals LLC", start = "2026-03-02", revenue = 940_000_000.0),
            Project(6, "S-05", "Boroo Gold Pit Stage 3", "Completed", 100, "Temuulen Amgalan", "2026-08-01", "Site projects",
                client = "Boroo Gold LLC", start = "2025-10-13", revenue = 3_560_000_000.0),
            Project(7, "S-06", "Zuunmod Camp Construction", "Ongoing", 58, "Khulan Serjee", "2026-12-01", "Site projects",
                client = "South Gobi Resources", start = "2026-05-11", revenue = 1_420_000_000.0),
            Project(8, "R-01", "Darkhan Conveyor Replacement", "Blocked", 26, "Batbayar Nyam", "2026-10-25", "Repair/Maintenance/other services",
                client = "Darkhan Cement JSC", start = "2026-06-01", revenue = 680_000_000.0),
            Project(9, "S-07", "Sainshand Solar Farm Integration", "Not started", 8, "Uyanga Lkham", "2027-05-12", "Site projects",
                client = "Green Energy Mongolia", start = "2026-11-04", revenue = 2_900_000_000.0),
            Project(10, "M-02", "Ulaangom Gravel Production", "Completed", 100, "Ganbaatar Khos", "2026-07-15", "Fabrication/Manufacturing",
                client = "Ulaangom Road Service", start = "2025-12-01", revenue = 510_000_000.0),
            Project(11, "S-08", "Murun Truck Shop Extension", "Ongoing", 52, "Jargalsaikhan Otgonbayar", "2027-01-30", "Site projects",
                client = "Murun Mining LLC", start = "2026-07-20", revenue = 1_260_000_000.0),
            Project(12, "R-02", "Choibalsan Bridge Repair", "Blocked", 33, "Chuluun Munkh", "2026-11-20", "Repair/Maintenance/other services",
                client = "Eastern Province Roads", start = "2026-04-27", revenue = 735_000_000.0),
            Project(13, "L-01", "Altai Operations Crew Supply", "Ongoing", 67, "Anujin Tseren", "2026-12-22", "Labor supply",
                client = "Altai Gold JSC", start = "2026-01-05", revenue = 420_000_000.0),
            Project(14, "R-03", "Khovsgel Slope Stabilisation", "Not started", 15, "Erdenesukh Baasandorj", "2027-04-08", "Repair/Maintenance/other services",
                client = "Khovsgel Minerals LLC", start = "2026-10-01", revenue = 980_000_000.0),
            Project(15, "L-02", "Oyu Tolgoi Underground Crew Supply", "On hold", 49, "Munkhbat Tsogt", "2027-02-14", "Labor supply",
                client = "Oyu Tolgoi LLC", start = "2026-02-16", revenue = 1_150_000_000.0),
            Project(16, "S-09", "Erdenet Training Centre Fit-out", "Completed", 100, "Myagmarjav Enkhjin", "2026-09-01", "Site projects",
                client = "Erdenet Mining Corporation", start = "2026-03-30", revenue = 640_000_000.0),
            Project(17, "D-01", "Spares Distributor Supply — Drills", "Ongoing", 30, "Narantsetseg Dorj", "2026-11-30", "Distributor product sales",
                client = "Sandvik Mongolia", start = "2026-06-15", revenue = 1_540_000_000.0),
            Project(18, "E-01", "Haul Truck Electrical Redesign", "Not started", 5, "Erdenechuluun Bat", "2027-03-01", "Engineering",
                client = "Internal — MMS Engineering", start = "2026-09-14", revenue = 260_000_000.0),
            Project(19, "F-01", "Ulaanbaatar Office Refit", "Ongoing", 44, "Khulan Serjee", "2026-10-31", "Internal works",
                client = "Internal — MMS Head Office", start = "2026-08-03", revenue = 145_000_000.0),
        ),
        assignedTasks = listOf(
            TaskItem("Review S-01 blast vibration report", "Tavan Tolgoi East Expansion", "2026-10-10", "High", "EMP0002", started = "2026-10-01"),
            TaskItem("Approve R-01 conveyor gearbox purchase", "Darkhan Conveyor Replacement", "2026-10-15", "High", "EMP0011", started = "2026-10-03"),
            TaskItem("Sign off S-02 recovery schedule", "Oyu Tolgoi Haul Road Upgrade", "2026-10-20", "Medium", "EMP0003", started = "2026-10-05"),
            TaskItem("S-01 Safety inspection", "Tavan Tolgoi East Expansion", "2026-10-08", "High", "EMP0005", started = "2026-10-04"),
            TaskItem("M-01 Equipment testing", "Erdenet Crusher Modernisation", "2026-10-12", "Medium", "EMP0007", started = "2026-10-06"),
            TaskItem("Weekly team meeting", "", "2026-10-07", "Low", "EMP0002", started = "2026-10-07"),
            TaskItem("S-04 Geological survey", "Tsaidam Lithium Exploration", "2026-10-18", "High", "EMP0008", started = "2026-10-09"),
            TaskItem("R-01 Maintenance review", "Darkhan Conveyor Replacement", "2026-10-14", "Medium", "EMP0011", started = "2026-10-10"),
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
