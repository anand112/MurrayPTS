package mn.murray.pts.db.migration

import kotlinx.serialization.json.Json
import mn.murray.pts.PtsData
import mn.murray.pts.db.*
import mn.murray.pts.domain.*
import mn.murray.pts.security.PasswordHasher
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/** Outcome of an import attempt, for logging and tests. */
data class ImportSummary(
    val skipped: Boolean = false,
    val reason: String = "",
    val positions: Long = 0,
    val teamMembers: Long = 0,
    val users: Long = 0,
    val projects: Long = 0,
    val projectAssignees: Long = 0,
    val buckets: Long = 0,
    val tasks: Long = 0,
    val forecasts: Long = 0,
    val lessons: Long = 0,
    val todoLists: Long = 0,
    val todoItems: Long = 0,
    val pins: Long = 0,
) {
    val imported: Boolean get() = !skipped
}

/**
 * One-shot importer: reads the legacy `data/pts-data.json`, normalizes it through the
 * 11 legacy migration steps, and populates the SQLite tables in foreign-key-safe order.
 *
 * Runs only when the `users` table is empty AND the legacy file exists, so it is safe to
 * call on every boot. After a successful import the JSON file is renamed to
 * `*.migrated-<timestamp>` so it is never imported twice (and is kept as a backup).
 *
 * A corrupt JSON file is logged and skipped — we never silently overwrite an existing DB
 * with demo data (that was a legacy data-loss risk; see ARCHITECTURE.md → Migration plan).
 */
object JsonImporter {

    private val log = LoggerFactory.getLogger(JsonImporter::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val SUPER_ADMIN = "EMP0000"

    /** True when the `users` table has no rows — the emptiness guard both bootstrappers use. */
    fun databaseIsEmpty(): Boolean = transaction { Users.selectAll().empty() }

    /** Import only into a fresh database when a legacy file is present. */
    fun shouldImport(legacyFile: File): Boolean = legacyFile.exists() && databaseIsEmpty()

    fun importFromFile(legacyFile: File): ImportSummary {
        if (!shouldImport(legacyFile)) {
            return ImportSummary(skipped = true, reason = "no legacy file or DB already populated")
        }
        val text = legacyFile.readText()
        val raw = runCatching { json.decodeFromString<PtsData>(text) }.getOrElse { e ->
            log.error("Legacy JSON at {} is corrupt ({}); skipping import. DB left empty.", legacyFile.absolutePath, e.message)
            return ImportSummary(skipped = true, reason = "corrupt JSON: ${e.message}")
        }

        val data = LegacyNormalizer.normalize(raw)
        val summary = import(data)

        val backup = File(legacyFile.parentFile, "${legacyFile.name}.migrated-${System.currentTimeMillis()}")
        runCatching { legacyFile.renameTo(backup) }
            .onSuccess { log.info("Legacy JSON archived to {}", backup.name) }
            .onFailure { log.warn("Could not archive legacy JSON ({}); re-import is prevented by the users-table guard.", it.message) }

        log.info(
            "Imported from JSON → SQLite: {} users, {} projects, {} tasks, {} team members, {} todo lists/items, {} pins",
            summary.users, summary.projects, summary.tasks, summary.teamMembers, summary.todoLists, summary.pins
        )
        return summary
    }

    /** Populate an empty database from a normalized [PtsData] graph. */
    fun import(data: PtsData): ImportSummary = transaction {
        val now = Instant.now().toString()

        // ---- 1. positions (catalog) -------------------------------------------
        val positionIdByName = HashMap<String, Int>()
        for (p in data.positions) {
            val id = Positions.insert {
                it[name] = p.name
                it[permissionLevel] = p.permissionLevel
                it[description] = p.description
            } get Positions.id
            positionIdByName[p.name] = id
        }

        // ---- 2. team_members (+ placeholders for any referenced empNo) --------
        val empNoByName = HashMap<String, String>()
        val insertedEmp = LinkedHashSet<String>()
        for (m in data.team) {
            if (m.employeeNumber.isBlank()) continue
            TeamMembers.insert {
                it[employeeNumber] = m.employeeNumber
                it[name] = m.name
                it[department] = m.department
                it[position] = m.position
            }
            insertedEmp += m.employeeNumber
            empNoByName[m.name] = m.employeeNumber
        }
        // Collect every employee number referenced anywhere else and ensure a row exists.
        val referenced = LinkedHashSet<String>()
        data.access.forEach { referenced += it.employeeNumber }
        data.assignedTasks.forEach { if (it.assignee.isNotBlank()) referenced += it.assignee }
        data.otherTasks.forEach { if (it.assignee.isNotBlank()) referenced += it.assignee }
        data.todoLists.forEach { if (it.owner.isNotBlank()) referenced += it.owner }
        data.todos.forEach { if (it.owner.isNotBlank()) referenced += it.owner }
        data.pins.keys.forEach { referenced += it }
        referenced += SUPER_ADMIN
        for (emp in referenced) {
            if (emp.isNotBlank() && emp !in insertedEmp) {
                TeamMembers.insert {
                    it[employeeNumber] = emp
                    it[name] = "Employee $emp"
                    it[department] = ""
                    it[position] = ""
                }
                insertedEmp += emp
            }
        }

        // ---- 3. users (bcrypt-hash the legacy effective password) -------------
        for (row in data.access) {
            val hadExplicitPassword = row.password.isNotBlank()
            val plain = row.effectivePassword()
            Users.insert {
                it[employeeNumber] = row.employeeNumber
                it[name] = row.name
                it[passwordHash] = PasswordHasher.hash(plain)
                it[access] = row.access
                it[email] = row.displayEmail()
                it[positionId] = positionIdByName[row.position]
                it[isSuperAdmin] = (row.employeeNumber == SUPER_ADMIN)
                // Legacy accounts whose password defaulted to the employee number must set one.
                it[mustChangePassword] = !hadExplicitPassword
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        // ---- 4. projects (+ assignees) ----------------------------------------
        val projectIdByName = HashMap<String, Int>()
        val newIdByLegacyId = HashMap<Int, Int>()
        var assigneeCount = 0L
        for (p in data.projects) {
            val managerEmp = empNoByName[p.manager]?.takeIf { it in insertedEmp }
            val newId = Projects.insert {
                it[code] = p.code
                it[name] = p.name
                it[status] = p.status
                it[progress] = p.progress
                it[managerEmpNo] = managerEmp
                it[due] = p.due
                it[type] = p.type   // labels/codes already normalized by LegacyNormalizer step 1b
                it[client] = p.client
                it[startDate] = p.start
                it[revenue] = p.revenue
                it[createdAt] = now
                it[updatedAt] = now
            } get Projects.id
            projectIdByName[p.name] = newId
            newIdByLegacyId[p.id] = newId
            // Every imported project gets the board a project created through the service gets: the three lanes
            // in [DEFAULT_PROJECT_BUCKETS], which is what V16 puts on every other board. The importer writes rows
            // directly rather than calling ProjectService.create, so without these lanes an imported project
            // would open on a board of its own making while every other project opened on the same three it can
            // then edit.
            DEFAULT_PROJECT_BUCKETS.forEachIndexed { laneIdx, laneName ->
                ProjectBuckets.insert {
                    it[projectId] = newId
                    it[name] = laneName
                    it[position] = laneIdx
                }
            }
            for (emp in p.assignees) {
                if (emp.isNotBlank() && emp in insertedEmp) {
                    ProjectAssignees.insert {
                        it[projectId] = newId
                        it[employeeNumber] = emp
                    }
                    assigneeCount++
                }
            }
        }

        // ---- 5. buckets --------------------------------------------------------
        val bucketIdByName = HashMap<String, Int>()
        data.buckets.forEachIndexed { idx, bucketName ->
            val id = Buckets.insert {
                it[name] = bucketName
                it[position] = idx
            } get Buckets.id
            bucketIdByName[bucketName] = id
        }

        // ---- 6. tasks (assigned + other) --------------------------------------
        fun insertTask(t: TaskItem, taskKind: String) {
            val projId = if (t.project.isNotBlank()) projectIdByName[t.project] else null
            val assignee = t.assignee.ifBlank { null }?.takeIf { e -> e in insertedEmp }
            val buckId = if (taskKind == "OTHER") bucketIdByName[t.bucket] else null
            Tasks.insert {
                it[kind] = taskKind
                it[title] = t.title
                it[projectId] = projId
                it[due] = t.due
                it[priority] = t.priority
                it[assigneeEmpNo] = assignee
                it[status] = t.status
                it[bucketId] = buckId
                it[started] = t.started
            }
        }
        data.assignedTasks.forEach { insertTask(it, "ASSIGNED") }
        data.otherTasks.forEach { insertTask(it, "OTHER") }

        // ---- 7. forecasts + lessons (read-only content) -----------------------
        for (f in data.forecasts) {
            Forecasts.insert {
                it[projectId] = if (f.project.isNotBlank()) projectIdByName[f.project] else null
                it[period] = f.period
                it[hours] = f.hours
                it[note] = f.note
            }
        }
        for (l in data.lessons) {
            Lessons.insert {
                it[title] = l.title
                it[projectId] = if (l.project.isNotBlank()) projectIdByName[l.project] else null
                it[summary] = l.summary
                // V9's sheet reads one column, so the imported pair is joined into it here exactly
                // as the migration's backfill joined it for the rows already in the table.
                it[lesson] = lessonTextOf(l.title, l.summary)
            }
        }

        // ---- 8. todo_lists + todo_items (owner-scoped, ordered) ---------------
        val newListIdByLegacy = HashMap<Int, Int>()
        data.todoLists.forEachIndexed { idx, list ->
            val owner = list.owner.ifBlank { SUPER_ADMIN }.takeIf { it in insertedEmp } ?: SUPER_ADMIN
            val newId = TodoLists.insert {
                it[name] = list.name
                it[done] = list.done
                it[ownerEmpNo] = owner
                it[due] = list.due
                it[position] = idx
            } get TodoLists.id
            newListIdByLegacy[list.id] = newId
        }
        val perListCounter = HashMap<Int, Int>()
        for (item in data.todos) {
            val listId = newListIdByLegacy[item.list] ?: newListIdByLegacy.values.firstOrNull() ?: continue
            val owner = item.owner.ifBlank { SUPER_ADMIN }.takeIf { it in insertedEmp } ?: SUPER_ADMIN
            val pos = perListCounter.getOrDefault(listId, 0)
            perListCounter[listId] = pos + 1
            TodoItems.insert {
                it[title] = item.title
                it[done] = item.done
                it[TodoItems.listId] = listId
                it[ownerEmpNo] = owner
                it[due] = item.due
                it[position] = pos
            }
        }

        // ---- 9. pins -----------------------------------------------------------
        var pinCount = 0L
        for ((emp, projIds) in data.pins) {
            if (emp !in insertedEmp) continue
            projIds.forEachIndexed { idx, legacyPid ->
                val newPid = newIdByLegacyId[legacyPid] ?: return@forEachIndexed
                Pins.insert {
                    it[employeeNumber] = emp
                    it[projectId] = newPid
                    it[position] = idx
                }
                pinCount++
            }
        }

        ImportSummary(
            positions = Positions.selectAll().count(),
            teamMembers = TeamMembers.selectAll().count(),
            users = Users.selectAll().count(),
            projects = Projects.selectAll().count(),
            projectAssignees = assigneeCount,
            buckets = Buckets.selectAll().count(),
            tasks = Tasks.selectAll().count(),
            forecasts = Forecasts.selectAll().count(),
            lessons = Lessons.selectAll().count(),
            todoLists = TodoLists.selectAll().count(),
            todoItems = TodoItems.selectAll().count(),
            pins = pinCount,
        )
    }
}
