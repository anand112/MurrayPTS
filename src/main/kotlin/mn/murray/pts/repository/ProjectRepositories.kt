package mn.murray.pts.repository

import mn.murray.pts.db.Buckets
import mn.murray.pts.db.Pins
import mn.murray.pts.db.ProjectAssignees
import mn.murray.pts.db.ProjectBuckets
import mn.murray.pts.db.Projects
import mn.murray.pts.db.TaskChecklistItems
import mn.murray.pts.db.TaskNotes
import mn.murray.pts.db.Tasks
import mn.murray.pts.db.TeamMembers
import mn.murray.pts.db.TypeDefaultBuckets
import mn.murray.pts.domain.Bucket
import mn.murray.pts.domain.ChecklistItem
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.ProjectBucket
import mn.murray.pts.domain.TaskItem
import mn.murray.pts.domain.TaskNote
import mn.murray.pts.domain.nextProjectCode
import mn.murray.pts.domain.normalizeProjectCode
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Data-access layer for projects, their assignees, per-user pins, kanban buckets and
 * tasks. Tasks are returned as the legacy [TaskItem] shape (project + bucket resolved to
 * NAMES) so existing views keep working while storage uses foreign-key ids.
 */

object ProjectRepository {

    private fun empNames(): Map<String, String> =
        TeamMembers.selectAll().associate { it[TeamMembers.employeeNumber] to it[TeamMembers.name] }

    private fun assigneesByProject(): Map<Int, List<String>> =
        ProjectAssignees.selectAll()
            .groupBy { it[ProjectAssignees.projectId] }
            .mapValues { (_, rows) -> rows.map { it[ProjectAssignees.employeeNumber] } }

    private fun map(r: ResultRow, empNames: Map<String, String>, assignees: List<String>) = Project(
        id = r[Projects.id],
        code = r[Projects.code],
        name = r[Projects.name],
        status = r[Projects.status],
        progress = r[Projects.progress],
        manager = r[Projects.managerEmpNo]?.let { empNames[it] } ?: "",
        due = r[Projects.due],
        type = r[Projects.type],
        assignees = assignees,
        client = r[Projects.client],
        start = r[Projects.startDate],
        revenue = r[Projects.revenue],
        invoiced = r[Projects.invoiced],
    )

    fun findAll(): List<Project> = transaction {
        val en = empNames(); val am = assigneesByProject()
        Projects.selectAll().orderBy(Projects.id to SortOrder.ASC).map { map(it, en, am[it[Projects.id]].orEmpty()) }
    }

    fun findById(id: Int): Project? = transaction {
        val en = empNames(); val am = assigneesByProject()
        Projects.selectAll().where { Projects.id eq id }.firstOrNull()?.let { map(it, en, am[id].orEmpty()) }
    }

    fun findByType(type: String): List<Project> = transaction {
        val en = empNames(); val am = assigneesByProject()
        Projects.selectAll().where { Projects.type eq type }.orderBy(Projects.id to SortOrder.ASC)
            .map { map(it, en, am[it[Projects.id]].orEmpty()) }
    }

    fun idByName(): Map<String, Int> = transaction {
        Projects.selectAll().associate { it[Projects.name] to it[Projects.id] }
    }

    /**
     * Insert a project. `due` is the END date (see V3__project_categories.sql).
     * The three commercial fields default so pre-V3 callers keep compiling.
     */
    fun insert(
        code: String,
        name: String,
        status: String,
        progress: Int,
        managerEmpNo: String?,
        due: String,
        type: String,
        client: String = "",
        start: String = "",
        revenue: Double = 0.0,
        invoiced: Double = 0.0,
    ): Int = transaction {
        val now = Instant.now().toString()
        Projects.insert {
            it[Projects.code] = code
            it[Projects.name] = name
            it[Projects.status] = status
            it[Projects.progress] = progress
            it[Projects.managerEmpNo] = managerEmpNo
            it[Projects.due] = due
            it[Projects.type] = type
            it[Projects.client] = client
            it[Projects.startDate] = start
            it[Projects.revenue] = revenue
            it[Projects.invoiced] = invoiced
            it[createdAt] = now
            it[updatedAt] = now
        } get Projects.id
    }

    fun update(
        id: Int,
        name: String,
        status: String,
        progress: Int,
        managerEmpNo: String?,
        due: String,
        type: String,
        client: String = "",
        start: String = "",
        revenue: Double = 0.0,
        invoiced: Double = 0.0,
    ) = transaction {
        Projects.update({ Projects.id eq id }) {
            it[Projects.name] = name
            it[Projects.status] = status
            it[Projects.progress] = progress
            it[Projects.managerEmpNo] = managerEmpNo
            it[Projects.due] = due
            it[Projects.type] = type
            it[Projects.client] = client
            it[Projects.startDate] = start
            it[Projects.revenue] = revenue
            it[Projects.invoiced] = invoiced
            it[updatedAt] = Instant.now().toString()
        }
    }

    fun setManager(id: Int, empNo: String?) = transaction {
        Projects.update({ Projects.id eq id }) { it[managerEmpNo] = empNo; it[updatedAt] = Instant.now().toString() }
    }

    /**
     * The manager's employee number, straight from the column. [findById] resolves it to a display
     * name for the board, but a notification has to be addressed to a person, not to a label.
     */
    fun managerOf(id: Int): String? = transaction {
        Projects.selectAll().where { Projects.id eq id }.firstOrNull()?.get(Projects.managerEmpNo)
    }

    fun setProgress(id: Int, progress: Int) = transaction {
        Projects.update({ Projects.id eq id }) { it[Projects.progress] = progress; it[updatedAt] = Instant.now().toString() }
    }

    fun setStatus(id: Int, status: String) = transaction {
        Projects.update({ Projects.id eq id }) { it[Projects.status] = status; it[updatedAt] = Instant.now().toString() }
    }

    fun addAssignee(id: Int, empNo: String) = transaction {
        val exists = ProjectAssignees.selectAll()
            .where { (ProjectAssignees.projectId eq id) and (ProjectAssignees.employeeNumber eq empNo) }
            .firstOrNull()
        if (exists == null) {
            ProjectAssignees.insert { it[projectId] = id; it[employeeNumber] = empNo }
        }
    }

    fun removeAssignee(id: Int, empNo: String) = transaction {
        ProjectAssignees.deleteWhere { (ProjectAssignees.projectId eq id) and (ProjectAssignees.employeeNumber eq empNo) }
    }

    fun delete(id: Int): Int = transaction { Projects.deleteWhere { Projects.id eq id } }

    /**
     * Remove a project and the work filed under it, in one transaction (Stage 60).
     *
     * [delete] alone is not enough: `tasks.project_id` answers `ON DELETE SET NULL`, so dropping the row would
     * leave every card of this project standing on the general boards with no project to name — cards nobody
     * filed and nobody asked to keep. So the project's own assigned cards go first (their checklist steps and
     * notes cascade off `tasks.id`, and the project's lanes, assignees, pins and forecasts cascade off
     * `projects.id`), and the project row goes last. The count returned is the projects deleted (0 or 1), which
     * is what tells the caller the project existed at all.
     */
    fun deleteWithTasks(id: Int): Int = transaction {
        Tasks.deleteWhere { Tasks.projectId eq id }
        Projects.deleteWhere { Projects.id eq id }
    }

    /** Every project number currently in use, upper-cased the way the service stores them. */
    fun existingCodes(): Set<String> = transaction {
        Projects.selectAll().map { normalizeProjectCode(it[Projects.code]) }.toSet()
    }

    /**
     * The number to pre-suggest in the create form for each category: the next unused one
     * per type, from the trailing digits of that type's existing codes. One query, because
     * the projects page asks for all nine at once.
     */
    fun suggestedCodes(): Map<String, String> = transaction {
        val all = Projects.selectAll().map {
            Project(
                id = it[Projects.id], code = it[Projects.code], name = "",
                status = "", progress = 0, manager = "", due = "", type = it[Projects.type],
            )
        }
        PROJECT_TYPES.associateWith { nextProjectCode(all, it) }
    }
}

object PinRepository {

    fun pinnedProjectIds(empNo: String): List<Int> = transaction {
        Pins.selectAll().where { Pins.employeeNumber eq empNo }
            .orderBy(Pins.position to SortOrder.ASC).map { it[Pins.projectId] }
    }

    fun isPinned(empNo: String, projectId: Int): Boolean = transaction {
        Pins.selectAll().where { (Pins.employeeNumber eq empNo) and (Pins.projectId eq projectId) }.firstOrNull() != null
    }

    /** Pin if unpinned, unpin if pinned. Returns the new pinned state. */
    fun toggle(empNo: String, projectId: Int): Boolean = transaction {
        val existing = Pins.selectAll().where { (Pins.employeeNumber eq empNo) and (Pins.projectId eq projectId) }.firstOrNull()
        if (existing != null) {
            Pins.deleteWhere { (Pins.employeeNumber eq empNo) and (Pins.projectId eq projectId) }
            false
        } else {
            val maxPos = Pins.selectAll().where { Pins.employeeNumber eq empNo }.map { it[Pins.position] }.maxOrNull() ?: -1
            Pins.insert { it[employeeNumber] = empNo; it[Pins.projectId] = projectId; it[position] = maxPos + 1 }
            true
        }
    }
}

object BucketRepository {

    private fun map(r: ResultRow) = Bucket(
        id = r[Buckets.id],
        name = r[Buckets.name],
        position = r[Buckets.position],
    )

    fun findAll(): List<Bucket> = transaction {
        Buckets.selectAll().orderBy(Buckets.position to SortOrder.ASC).map { map(it) }
    }

    fun names(): List<String> = findAll().map { it.name }

    fun idByName(): Map<String, Int> = transaction {
        Buckets.selectAll().associate { it[Buckets.name] to it[Buckets.id] }
    }

    /** One bucket by id, for the route that is about to rename or empty it. */
    fun find(id: Int): Bucket? = transaction {
        Buckets.selectAll().where { Buckets.id eq id }.firstOrNull()?.let { map(it) }
    }

    /** Every bucket except this one, in board order — the choices a delete offers for where its cards go. */
    fun except(id: Int): List<Bucket> = findAll().filter { it.id != id }

    fun insert(bucketName: String): Int = transaction {
        val maxPos = Buckets.selectAll().map { it[Buckets.position] }.maxOrNull() ?: -1
        Buckets.insert { it[name] = bucketName; it[position] = maxPos + 1 } get Buckets.id
    }

    /** Change a column's name. Cards are untouched: they point at the id, and the board groups by that. */
    fun rename(id: Int, name: String): Boolean = transaction {
        Buckets.update({ Buckets.id eq id }) { it[Buckets.name] = name } > 0
    }

    fun delete(id: Int): Int = transaction { Buckets.deleteWhere { Buckets.id eq id } }
}

/**
 * The lanes of **one project's** board (V7) — the phases of that project's work, written into the project
 * when it is created and added to afterwards by whoever runs it.
 *
 * This is the neighbour of [BucketRepository], not the same thing: that object serves the Other tasks
 * board's shared column list, which `tasks.bucket_id` points at, and `tasks.project_bucket_id` points here.
 * Keeping the two apart is what lets a card be on a project's board and the other board without either read
 * having to filter the other's rows out.
 */
object ProjectBucketRepository {

    private fun map(r: ResultRow) = ProjectBucket(
        id = r[ProjectBuckets.id],
        projectId = r[ProjectBuckets.projectId],
        name = r[ProjectBuckets.name],
        position = r[ProjectBuckets.position],
    )

    /** This project's lanes, in the order the board prints them. */
    fun ofProject(projectId: Int): List<ProjectBucket> = transaction {
        ProjectBuckets.selectAll().where { ProjectBuckets.projectId eq projectId }
            .orderBy(ProjectBuckets.position to SortOrder.ASC, ProjectBuckets.id to SortOrder.ASC)
            .map { map(it) }
    }

    /** Every lane by id, for the one join the task read does. */
    fun namesById(): Map<Int, String> = transaction {
        ProjectBuckets.selectAll().associate { it[ProjectBuckets.id] to it[ProjectBuckets.name] }
    }

    fun find(id: Int): ProjectBucket? = transaction {
        ProjectBuckets.selectAll().where { ProjectBuckets.id eq id }.firstOrNull()?.let { map(it) }
    }

    /** The names already used on this project, for the duplicate check a lane add has to make. */
    fun namesOf(projectId: Int): Set<String> = ofProject(projectId).map { it.name }.toSet()

    /**
     * One of this project's lanes by name, or null. Exact and case-sensitive on purpose: the popup posts a lane
     * by its name (Stage 48) because the six sub-categories carry the same name on every project, so a name is
     * the one answer a form on the general board can post without first being told that project's lane ids. A
     * name that is not on this project — renamed while the popup was open, borrowed from another board, or
     * hand-typed — is a refusal, never a near miss.
     */
    fun findByName(projectId: Int, name: String): ProjectBucket? =
        ofProject(projectId).firstOrNull { it.name == name }

    private fun insertRow(projectId: Int, name: String, position: Int): Int = transaction {
        ProjectBuckets.insert {
            it[ProjectBuckets.projectId] = projectId
            it[ProjectBuckets.name] = name
            it[ProjectBuckets.position] = position
        } get ProjectBuckets.id
    }

    /**
     * Give a freshly created project the six standard sub-categories, in [DEFAULT_PROJECT_BUCKETS] order.
     *
     * The shape a board starts from: six lanes, positions 0..5, no name skipped. Called in the same breath as
     * the project insert, which is why a project never has to be given a board before it can hold a card — and
     * the tests use it for the same reason, when they only want a board to exist. V10 handed the same six to
     * every project that predates the list, so this and that migration are the only two places a board comes
     * from, and a lane picker never has to ask which project a card is going to before it can list them.
     */
    fun seedDefaults(projectId: Int): List<Int> =
        DEFAULT_PROJECT_BUCKETS.mapIndexed { idx, name -> insertRow(projectId, name, idx) }

    /** Add one lane to the end of this project's board. Returns the new id. */
    fun insert(projectId: Int, name: String): Int = transaction {
        val maxPos = ProjectBuckets.selectAll().where { ProjectBuckets.projectId eq projectId }
            .map { it[ProjectBuckets.position] }.maxOrNull() ?: -1
        insertRow(projectId, name, maxPos + 1)
    }

    /** Take a lane off the board. Its cards keep existing; their `project_bucket_id` goes null by FK. */
    fun delete(id: Int): Int = transaction { ProjectBuckets.deleteWhere { ProjectBuckets.id eq id } }
}

/**
 * The catalogue of **extra** lanes each project category adds to a new board (V12).
 *
 * Sits beside [ProjectBucketRepository] as a template sits beside a board: this object is what Settings
 * edits and what [mn.murray.pts.service.ProjectService.create] reads when a project is being born, and its
 * rows are never what a card is filed against — the lanes written onto a board by [seedExtras] are ordinary
 * `project_buckets` rows from that moment on. Changing a default here therefore cannot reach a live board,
 * which is the behaviour the panel promises: a default shapes the *next* project of its category.
 */
object TypeDefaultBucketRepository {

    /** This category's extra lanes, in the order they will be appended to a board. */
    fun of(type: String): List<ProjectBucket> = transaction {
        TypeDefaultBuckets.selectAll().where { TypeDefaultBuckets.type eq type }
            .orderBy(TypeDefaultBuckets.position to SortOrder.ASC, TypeDefaultBuckets.id to SortOrder.ASC)
            .map { ProjectBucket(id = it[TypeDefaultBuckets.id], projectId = 0, name = it[TypeDefaultBuckets.name], position = it[TypeDefaultBuckets.position]) }
    }

    fun find(id: Int): ProjectBucket? = transaction {
        TypeDefaultBuckets.selectAll().where { TypeDefaultBuckets.id eq id }.firstOrNull()?.let {
            ProjectBucket(id = it[TypeDefaultBuckets.id], projectId = 0, name = it[TypeDefaultBuckets.name], position = it[TypeDefaultBuckets.position])
        }
    }

    /** The names already offered for this category — the duplicate check an add has to make. */
    fun namesOf(type: String): Set<String> = of(type).map { it.name }.toSet()

    /** Append one lane to this category's set. The six standard lanes hold 0..5, so extras count from 6. */
    fun insert(type: String, name: String): Int = transaction {
        val maxPos = TypeDefaultBuckets.selectAll().where { TypeDefaultBuckets.type eq type }
            .map { it[TypeDefaultBuckets.position] }.maxOrNull() ?: DEFAULT_PROJECT_BUCKETS.size - 1
        TypeDefaultBuckets.insert {
            it[TypeDefaultBuckets.type] = type
            it[TypeDefaultBuckets.name] = name
            it[TypeDefaultBuckets.position] = maxPos + 1
        } get TypeDefaultBuckets.id
    }

    fun delete(id: Int): Int = transaction { TypeDefaultBuckets.deleteWhere { TypeDefaultBuckets.id eq id } }

    /**
     * Give a freshly created project of [type] its category's extra lanes, after [ProjectBucketRepository.seedDefaults]
     * has written the six. Returns the new lane ids in board order; a category with no defaults writes nothing and
     * the board is exactly the six it was before this table existed.
     */
    fun seedExtras(projectId: Int, type: String): List<Int> {
        val extras = of(type)
        if (extras.isEmpty()) return emptyList()
        return transaction {
            extras.map { lane ->
                ProjectBuckets.insert {
                    it[ProjectBuckets.projectId] = projectId
                    it[ProjectBuckets.name] = lane.name
                    it[ProjectBuckets.position] = lane.position
                } get ProjectBuckets.id
            }
        }
    }
}

object TaskRepository {

    private fun projectNames(): Map<Int, String> =
        Projects.selectAll().associate { it[Projects.id] to it[Projects.name] }

    private fun bucketNames(): Map<Int, String> =
        Buckets.selectAll().associate { it[Buckets.id] to it[Buckets.name] }

    /** The same for a project's lanes (V7), read once per board like every other name map here. */
    private fun projectBucketNames(): Map<Int, String> = ProjectBucketRepository.namesById()

    /**
     * Every card's steps, grouped by task and in the order management wrote them. One query for the
     * whole board rather than one per card — a board is read on every page load, a checklist is not
     * something anybody needs a second time in the same render.
     */
    private fun checklistsByTask(): Map<Int, List<ChecklistItem>> =
        TaskChecklistItems.selectAll()
            .orderBy(TaskChecklistItems.taskId to SortOrder.ASC, TaskChecklistItems.position to SortOrder.ASC)
            .groupBy { it[TaskChecklistItems.taskId] }
            .mapValues { (_, rows) ->
                rows.map { ChecklistItem(it[TaskChecklistItems.id], it[TaskChecklistItems.label], it[TaskChecklistItems.done]) }
            }

    private fun notesByTask(): Map<Int, List<TaskNote>> =
        TaskNotes.selectAll()
            .orderBy(TaskNotes.taskId to SortOrder.ASC, TaskNotes.position to SortOrder.DESC)
            .groupBy { it[TaskNotes.taskId] }
            .mapValues { (_, rows) ->
                rows.map { TaskNote(it[TaskNotes.id], it[TaskNotes.body], it[TaskNotes.authorEmpNo], it[TaskNotes.created]) }
            }

    private fun map(
        r: ResultRow,
        projectNames: Map<Int, String>,
        bucketNames: Map<Int, String>,
        projectBucketNames: Map<Int, String>,
        checklists: Map<Int, List<ChecklistItem>>,
        notes: Map<Int, List<TaskNote>>,
    ) = TaskItem(
        title = r[Tasks.title],
        project = r[Tasks.projectId]?.let { projectNames[it] } ?: "",
        due = r[Tasks.due],
        priority = r[Tasks.priority],
        assignee = r[Tasks.assigneeEmpNo] ?: "",
        status = r[Tasks.status],
        id = r[Tasks.id],
        bucketId = r[Tasks.bucketId] ?: 0,
        bucket = r[Tasks.bucketId]?.let { bucketNames[it] } ?: "",
        kind = r[Tasks.kind],
        projectBucket = r[Tasks.projectBucketId]?.let { projectBucketNames[it] } ?: "",
        projectBucketId = r[Tasks.projectBucketId] ?: 0,
        started = r[Tasks.started],
        checklist = checklists[r[Tasks.id]] ?: emptyList(),
        notes = notes[r[Tasks.id]] ?: emptyList(),
    )

    fun findByKind(kind: String): List<TaskItem> = transaction {
        val pn = projectNames(); val bn = bucketNames(); val pbn = projectBucketNames()
        val cl = checklistsByTask(); val nt = notesByTask()
        Tasks.selectAll().where { Tasks.kind eq kind }.orderBy(Tasks.id to SortOrder.ASC).map {
            map(it, pn, bn, pbn, cl, nt)
        }
    }

    fun findById(id: Int): TaskItem? = transaction {
        val pn = projectNames(); val bn = bucketNames(); val pbn = projectBucketNames()
        val cl = checklistsByTask(); val nt = notesByTask()
        Tasks.selectAll().where { Tasks.id eq id }.firstOrNull()?.let { map(it, pn, bn, pbn, cl, nt) }
    }

    fun assigneeOf(id: Int): String? = transaction {
        Tasks.selectAll().where { Tasks.id eq id }.firstOrNull()?.get(Tasks.assigneeEmpNo)
    }

    /**
     * Which board a row belongs to — `ASSIGNED` or `OTHER` — is on [mn.murray.pts.domain.TaskItem.kind],
     * which [map] fills from the same column, so a caller never needs a second read for it.
     */
    fun insert(
        kind: String, title: String, projectId: Int?, due: String, priority: String,
        assigneeEmpNo: String?, status: String, bucketId: Int?, started: String,
        projectBucketId: Int? = null,
    ): Int = transaction {
        Tasks.insert {
            it[Tasks.kind] = kind
            it[Tasks.title] = title
            it[Tasks.projectId] = projectId
            it[Tasks.due] = due
            it[Tasks.priority] = priority
            it[Tasks.assigneeEmpNo] = assigneeEmpNo
            it[Tasks.status] = status
            it[Tasks.bucketId] = bucketId
            it[Tasks.projectBucketId] = projectBucketId
            it[Tasks.started] = started
        } get Tasks.id
    }

    fun updateStatus(id: Int, status: String) = transaction {
        Tasks.update({ Tasks.id eq id }) { it[Tasks.status] = status }
    }

    /**
     * File a card on its project's board, or unfile it when [bucketId] is null (V7).
     *
     * Deliberately its own statement rather than a column of [update]: the editor writes seven fields on one
     * path and an assignee saving their own due date must not be able to clear the lane their card sits in.
     * A drop on the board and the editor's lane picker both come here, and only here.
     */
    fun setProjectBucket(id: Int, bucketId: Int?) = transaction {
        Tasks.update({ Tasks.id eq id }) { it[Tasks.projectBucketId] = bucketId }
    }

    /**
     * Write every editable column of a task in one statement. `kind` and `bucket_id` are left alone: a card
     * never changes board, and its column of the backlog moves through [setBucket] the same way its lane moves
     * through [setProjectBucket] — separately, so a save that only books a due date cannot refile it.
     */
    fun update(
        id: Int, title: String, projectId: Int?, due: String, priority: String,
        assigneeEmpNo: String?, status: String, started: String,
    ) = transaction {
        Tasks.update({ Tasks.id eq id }) {
            it[Tasks.title] = title
            it[Tasks.projectId] = projectId
            it[Tasks.due] = due
            it[Tasks.priority] = priority
            it[Tasks.assigneeEmpNo] = assigneeEmpNo
            it[Tasks.status] = status
            it[Tasks.started] = started
        }
    }

    /**
     * Move one card to the other board (Stage 48) by rewriting its `kind`.
     *
     * Its own statement for the same reason [setProjectBucket] and [setBucket] are: [update] serves both boards
     * and must not be able to move a card between them as a side effect of renaming it, and [TaskService] calls
     * this only when the card's "where it lives" answer actually changed. The columns the two boards file a card
     * by are cleared by their own writers in the same save, so a row never ends up pointing at a project lane
     * and a backlog column at once.
     */
    fun setKind(id: Int, kind: String) = transaction {
        Tasks.update({ Tasks.id eq id }) { it[Tasks.kind] = kind }
    }

    /**
     * Move one card to another column of the backlog board — the editor popup's Column picker (Stage 42). Its
     * own statement for the same reason [setProjectBucket] is one: [update] serves both boards and must not be
     * able to refile a card as a side effect of renaming it. A whole column moving at once is
     * [moveBucketCards], which is what a column's delete runs before the row goes.
     */
    fun setBucket(id: Int, bucketId: Int?) = transaction {
        Tasks.update({ Tasks.id eq id }) { it[Tasks.bucketId] = bucketId }
    }

    /**
     * How many cards sit in this backlog column, which is what decides whether a delete has to be
     * told where they go. Filtered by the bucket id alone rather than by `kind`: a card that points at
     * a column is read as being in it, whatever row it belongs to.
     */
    fun countInBucket(bucketId: Int): Int = transaction {
        Tasks.selectAll().where { Tasks.bucketId eq bucketId }.count().toInt()
    }

    /**
     * Re-point every card of one backlog column at another, in one statement.
     *
     * This is the half of a delete that must happen **before** the row goes: `tasks.bucket_id` is
     * `ON DELETE SET NULL`, so dropping a bucket that still holds cards would quietly un-file them from
     * every column on the board rather than move them — a card nobody can see is work that has gone missing.
     */
    fun moveBucketCards(fromBucketId: Int, toBucketId: Int): Int = transaction {
        Tasks.update({ Tasks.bucketId eq fromBucketId }) { it[Tasks.bucketId] = toBucketId }
    }

    fun delete(id: Int): Int = transaction { Tasks.deleteWhere { Tasks.id eq id } }
}

/**
 * The tickable steps underneath one task card (V5). Read through [TaskRepository] in bulk for a
 * board render; written one step at a time here, because a tick and an added step are both single
 * statements and never a read-modify-write of the list. Deleting a card removes its steps through
 * the FK's own cascade, so there is no cleanup call to forget.
 */
object ChecklistRepository {

    private fun map(r: ResultRow) = ChecklistItem(
        id = r[TaskChecklistItems.id],
        label = r[TaskChecklistItems.label],
        done = r[TaskChecklistItems.done],
    )

    fun find(itemId: Int): ChecklistItem? = transaction {
        TaskChecklistItems.selectAll().where { TaskChecklistItems.id eq itemId }.firstOrNull()?.let(::map)
    }

    /** Which card a step hangs off — the tick rule is the card's, so it needs the card's assignee. */
    fun taskOf(itemId: Int): Int? = transaction {
        TaskChecklistItems.selectAll().where { TaskChecklistItems.id eq itemId }
            .firstOrNull()?.get(TaskChecklistItems.taskId)
    }

    fun itemsOf(taskId: Int): List<ChecklistItem> = transaction {
        TaskChecklistItems.selectAll().where { TaskChecklistItems.taskId eq taskId }
            .orderBy(TaskChecklistItems.position to SortOrder.ASC).map(::map)
    }

    /** Append one step below the ones already on the card. */
    fun insert(taskId: Int, label: String): Int = transaction {
        val maxPos = TaskChecklistItems.selectAll().where { TaskChecklistItems.taskId eq taskId }
            .map { it[TaskChecklistItems.position] }.maxOrNull() ?: -1
        TaskChecklistItems.insert {
            it[TaskChecklistItems.taskId] = taskId
            it[TaskChecklistItems.label] = label
            it[done] = false
            it[position] = maxPos + 1
        } get TaskChecklistItems.id
    }

    /** Returns false when the step is gone, so a tick on a deleted card cannot look like a success. */
    fun setDone(itemId: Int, done: Boolean): Boolean = transaction {
        TaskChecklistItems.update({ TaskChecklistItems.id eq itemId }) { it[TaskChecklistItems.done] = done } > 0
    }

    fun delete(itemId: Int): Boolean = transaction {
        TaskChecklistItems.deleteWhere { TaskChecklistItems.id eq itemId } > 0
    }
}

/**
 * The running log under one task card (V6). Read in bulk through [TaskRepository] for a board or an
 * editor render, written one note at a time here — an add is one INSERT, never a read-modify-write of
 * the list, which is the whole reason this is a table and not a column. Deleting a card takes its
 * notes with it through the FK's own cascade.
 *
 * There is no `setBody`: a note is a record of a moment and the app does not rewrite history. The
 * newest-first order is [TaskNotes.position] descending, the append counter, so two notes added in the
 * same day still print in the order they were written.
 */
object NoteRepository {

    private fun map(r: ResultRow) = TaskNote(
        id = r[TaskNotes.id],
        body = r[TaskNotes.body],
        author = r[TaskNotes.authorEmpNo],
        created = r[TaskNotes.created],
    )

    fun find(noteId: Int): TaskNote? = transaction {
        TaskNotes.selectAll().where { TaskNotes.id eq noteId }.firstOrNull()?.let(::map)
    }

    /** Which card a note hangs off — the write rule is the card's, so it needs the card's assignee. */
    fun taskOf(noteId: Int): Int? = transaction {
        TaskNotes.selectAll().where { TaskNotes.id eq noteId }.firstOrNull()?.get(TaskNotes.taskId)
    }

    fun notesOf(taskId: Int): List<TaskNote> = transaction {
        TaskNotes.selectAll().where { TaskNotes.taskId eq taskId }
            .orderBy(TaskNotes.position to SortOrder.DESC).map(::map)
    }

    /** Append one note above the ones already on the card, because the log reads newest first. */
    fun insert(taskId: Int, body: String, authorEmpNo: String, created: String): Int = transaction {
        val maxPos = TaskNotes.selectAll().where { TaskNotes.taskId eq taskId }
            .map { it[TaskNotes.position] }.maxOrNull() ?: -1
        TaskNotes.insert {
            it[TaskNotes.taskId] = taskId
            it[TaskNotes.body] = body
            it[TaskNotes.authorEmpNo] = authorEmpNo
            it[TaskNotes.created] = created
            it[position] = maxPos + 1
        } get TaskNotes.id
    }

    fun delete(noteId: Int): Boolean = transaction {
        TaskNotes.deleteWhere { TaskNotes.id eq noteId } > 0
    }
}
