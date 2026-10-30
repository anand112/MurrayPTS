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
import mn.murray.pts.domain.PROJECT_BUCKET_MAX
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
        colourSlot = r[ProjectBuckets.colourSlot],
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
     * How many cards are filed in each of this project's lanes, keyed by lane id. A lane with nothing in it
     * is absent rather than zero, so a caller that only wants the occupied ones can read [Map.containsKey].
     *
     * Archived cards are left out (V18). Since a lane's removal now archives its work instead of refusing it, this
     * count is no longer the reason a bucket cannot come off — it is the number the popup prints in the sentence
     * that says what *will* happen, "its N cards go to Archive". Either way an archived card must not be counted as
     * standing in a column: it left the board, and a card that counts twice, once here and once in the archive
     * panel, is a card a reader will try to find on the column it is not on. [mn.murray.pts.repository.TaskRepository.archiveLane]
     * already nulls its lane, so this clause is a second gate over the same fact rather than the only one.
     */
    fun cardCounts(projectId: Int): Map<Int, Int> = transaction {
        Tasks.selectAll().where { (Tasks.projectId eq projectId) and (Tasks.archived eq false) }
            .mapNotNull { it[Tasks.projectBucketId] }
            .groupingBy { it }
            .eachCount()
    }

    /**
     * One of this project's lanes by name, or null. Exact and case-sensitive on purpose: the popup posts a lane
     * by its name (Stage 48) because a form on the general board is not told this project's lane ids, and the
     * three phases every board opens with are the names it can offer without them. Looking the name up **here**
     * rather than trusting it is what makes Stage 75's editable board safe: a name that is not on this project —
     * renamed while the popup was open, taken off it, borrowed from another board, or hand-typed — is a refusal,
     * never a near miss.
     */
    fun findByName(projectId: Int, name: String): ProjectBucket? =
        ofProject(projectId).firstOrNull { it.name == name }

    /**
     * Rename one lane in place (Stage 74), and report whether a row answered.
     *
     * By id, never by name: the cards filed in the lane point at `tasks.project_bucket_id`, so a rename moves
     * the column's heading and nothing else — every card on it stays where it was, with no data to re-file.
     *
     * This says nothing to the category's catalogue (`type_default_buckets`), and that is the point. A project's
     * manager renaming **QA hold** to **QA Hold** on their board is not a decision about what every future
     * project of that category is offered; the bucket that reaches the catalogue is a bucket *added*, which
     * [mn.murray.pts.service.TaskService.saveLanes] does and this does not.
     */
    fun rename(id: Int, name: String): Boolean = transaction {
        ProjectBuckets.update({ ProjectBuckets.id eq id }) { it[ProjectBuckets.name] = name } > 0
    }

    private fun insertRow(projectId: Int, name: String, position: Int, colourSlot: Int? = null): Int = transaction {
        ProjectBuckets.insert {
            it[ProjectBuckets.projectId] = projectId
            it[ProjectBuckets.name] = name
            it[ProjectBuckets.position] = position
            it[ProjectBuckets.colourSlot] = colourSlot
        } get ProjectBuckets.id
    }

    /**
     * Add one lane to the end of this project's board. Returns the new id.
     *
     * [colourSlot] is the hue the reader picked for this column (Stage 80), or null for the positional shade it
     * has always worn — which is what every path that does not ask the question passes, from a create popup's
     * tick-list to a catalogue seeding a board.
     */
    fun insert(projectId: Int, name: String, colourSlot: Int? = null): Int = transaction {
        val maxPos = ProjectBuckets.selectAll().where { ProjectBuckets.projectId eq projectId }
            .map { it[ProjectBuckets.position] }.maxOrNull() ?: -1
        insertRow(projectId, name, maxPos + 1, colourSlot)
    }

    /**
     * Change one lane's colour on its own (Stage 80), and report whether a row answered.
     *
     * Kept apart from [rename] because the popup sends both fields in one post and the service has to be able to
     * say which of the two actually moved: a rename that lands writes the name, a colour that lands writes this,
     * and a post that changes neither is the reader pressing the button for nothing rather than a failure.
     */
    fun setColour(id: Int, colourSlot: Int?): Boolean = transaction {
        ProjectBuckets.update({ ProjectBuckets.id eq id }) { it[ProjectBuckets.colourSlot] = colourSlot } > 0
    }

    /** Take a lane off the board. Its cards keep existing; their `project_bucket_id` goes null by FK. */
    fun delete(id: Int): Int = transaction { ProjectBuckets.deleteWhere { ProjectBuckets.id eq id } }
}

/**
 * The catalogue of buckets each project category is made of (V12).
 *
 * Sits beside [ProjectBucketRepository] as a template sits beside a board: this object is what Settings
 * edits and what [mn.murray.pts.service.ProjectService.create] reads when a project is being born, and its
 * rows are never what a card is filed against — the lanes written onto a board by [seedBoard] are ordinary
 * `project_buckets` rows from that moment on.
 *
 * **Since Stage 100 this table is the whole board, not a set of extras.** A new project opens with exactly
 * the rows listed here for its category, in this order; the three phases are no longer added by the code, so a
 * category that wants them lists them and a category whose list is empty cannot make a project. A row here is
 * also read in one more place: Settings' add ([mn.murray.pts.service.BucketDefaultsService.add]) writes the row
 * and then walks every project of the category, putting the column on the boards that do not carry it yet
 * ([seedOne]). A row's *removal* still cannot touch a live board — that asymmetry is the panel's rule, not an
 * accident: a bucket somebody added is a column they asked for, a bucket somebody took off the list is one they
 * stopped asking new projects to have.
 */
object TypeDefaultBucketRepository {

    /** This category's buckets, in the order a new board is cut from them. */
    fun of(type: String): List<ProjectBucket> = transaction {
        TypeDefaultBuckets.selectAll().where { TypeDefaultBuckets.type eq type }
            .orderBy(TypeDefaultBuckets.position to SortOrder.ASC, TypeDefaultBuckets.id to SortOrder.ASC)
            .map { ProjectBucket(id = it[TypeDefaultBuckets.id], projectId = 0, name = it[TypeDefaultBuckets.name], position = it[TypeDefaultBuckets.position], colourSlot = it[TypeDefaultBuckets.colourSlot]) }
    }

    fun find(id: Int): ProjectBucket? = transaction {
        TypeDefaultBuckets.selectAll().where { TypeDefaultBuckets.id eq id }.firstOrNull()?.let {
            ProjectBucket(id = it[TypeDefaultBuckets.id], projectId = 0, name = it[TypeDefaultBuckets.name], position = it[TypeDefaultBuckets.position], colourSlot = it[TypeDefaultBuckets.colourSlot])
        }
    }

    /** The names already offered for this category — the duplicate check an add has to make. */
    fun namesOf(type: String): Set<String> = of(type).map { it.name }.toSet()

    /**
     * Every category's buckets in one read, each list in the order a new board is cut from it.
     *
     * One caller: the create-project popup (Stage 104), which prints what each category would open with, so
     * management can see before they press Create which columns the board gets from Settings and which are the
     * ones they are typing here. Reading it category by category would be one query per category for a block
     * drawn on every Projects page management opens.
     */
    fun allByType(): Map<String, List<ProjectBucket>> = transaction {
        TypeDefaultBuckets.selectAll()
            .orderBy(
                TypeDefaultBuckets.type to SortOrder.ASC,
                TypeDefaultBuckets.position to SortOrder.ASC,
                TypeDefaultBuckets.id to SortOrder.ASC,
            )
            .groupBy({ it[TypeDefaultBuckets.type] }) {
                ProjectBucket(
                    id = it[TypeDefaultBuckets.id],
                    projectId = 0,
                    name = it[TypeDefaultBuckets.name],
                    position = it[TypeDefaultBuckets.position],
                    colourSlot = it[TypeDefaultBuckets.colourSlot],
                )
            }
    }

    /**
     * Append one bucket to this category's list. An empty list starts its run at 0 (Stage 100): there is no
     * fixed opening set above it any more, so the first row a category is given is its board's first column.
     *
     * [colourSlot] (Stage 80) is the hue management picked for this offer, and it is the shade the board a new
     * project of this category gets for that column — [seedBoard] carries it onto the lane it writes. Null is
     * the positional shade, which is every default written before Stage 80.
     */
    fun insert(type: String, name: String, colourSlot: Int? = null): Int = transaction {
        val maxPos = TypeDefaultBuckets.selectAll().where { TypeDefaultBuckets.type eq type }
            .map { it[TypeDefaultBuckets.position] }.maxOrNull() ?: -1
        TypeDefaultBuckets.insert {
            it[TypeDefaultBuckets.type] = type
            it[TypeDefaultBuckets.name] = name
            it[TypeDefaultBuckets.position] = maxPos + 1
            it[TypeDefaultBuckets.colourSlot] = colourSlot
        } get TypeDefaultBuckets.id
    }

    fun delete(id: Int): Int = transaction { TypeDefaultBuckets.deleteWhere { TypeDefaultBuckets.id eq id } }

    /**
     * Cut a freshly created project's board from [type]'s list, in catalogue order.
     *
     * Since Stage 100 [mn.murray.pts.service.ProjectService.create] passes this category's **whole catalogue**
     * ([namesOf]) and nothing else, so a new board holds exactly the rows Settings › Project bucket settings
     * lists for it — the same set [mn.murray.pts.service.BucketDefaultsService.add] puts on the boards that
     * already exist. The [chosen] parameter is what makes that a set rather than a copy: a caller that wants
     * only some of the catalogue names can hand those, and a name the catalogue does not know is written anyway
     * (last, in the order it was posted) because it belongs to the project rather than to the category.
     *
     * The board order is the catalogue order, not the order the form posted them in — a lane's `position` is a
     * fact about the set, and two people starting the same kind of project should get the same board out of
     * it. Each one is appended through [ProjectBucketRepository.insert], so the lanes number from 0 with no gap.
     */
    fun seedBoard(projectId: Int, type: String, chosen: Set<String>): List<Int> {
        val fromCatalogue = of(type).filter { lane -> chosen.any { it.equals(lane.name, ignoreCase = true) } }
        // Name and colour travel together from here, because since Stage 80 a default's hue is part of what it
        // offers: the board gets the shade management set on Settings, and a name the catalogue does not know —
        // a bucket typed on the create form and left this project's own — has no shade to inherit.
        val lanes: List<Pair<String, Int?>> = fromCatalogue.map { it.name to it.colourSlot } +
            chosen.filter { name -> fromCatalogue.none { it.name.equals(name, ignoreCase = true) } }
                .map { it to (null as Int?) }
        if (lanes.isEmpty()) return emptyList()
        return transaction { lanes.map { (name, colourSlot) -> ProjectBucketRepository.insert(projectId, name, colourSlot) } }
    }

    /**
     * Put one named bucket of this category's catalogue on a board that does not carry it yet. Returns the new
     * lane id, or null when this category offers no such bucket, which is the caller's refusal, never an
     * invented lane.
     *
     * Two callers, one rule: Settings' add walks the category's projects with this (Stage 98), and a project
     * page's picker uses it for a bucket the form named but the board did not hold. The caller is responsible
     * for the duplicate check — a board that already carries the name should not be given a second column.
     *
     * The lane wears the catalogue's colour when that default has one (Stage 80), which is what makes the shade
     * on a Settings row the shade on the board it seeded rather than an accident of position.
     */
    fun seedOne(projectId: Int, type: String, name: String): Int? =
        of(type).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?.let { ProjectBucketRepository.insert(projectId, it.name, it.colourSlot) }
}

object TaskRepository {

    /**
     * A project as a task's chip needs it: the name every board groups by, and the code that says which world a
     * card on a cross-project board came from (Stage 88). Read once per board, like every name map here.
     */
    private data class ProjectLabel(val name: String, val code: String)

    private fun projectLabels(): Map<Int, ProjectLabel> =
        Projects.selectAll().associate { it[Projects.id] to ProjectLabel(it[Projects.name], it[Projects.code]) }

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
        projectLabels: Map<Int, ProjectLabel>,
        bucketNames: Map<Int, String>,
        projectBucketNames: Map<Int, String>,
        checklists: Map<Int, List<ChecklistItem>>,
        notes: Map<Int, List<TaskNote>>,
    ): TaskItem {
        val label = r[Tasks.projectId]?.let { projectLabels[it] }
        return TaskItem(
            title = r[Tasks.title],
            project = label?.name ?: "",
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
            projectId = r[Tasks.projectId] ?: 0,
            checklist = checklists[r[Tasks.id]] ?: emptyList(),
            notes = notes[r[Tasks.id]] ?: emptyList(),
            archived = r[Tasks.archived],
            archivedLaneId = r[Tasks.archivedLaneId] ?: 0,
            archivedLane = r[Tasks.archivedLane] ?: "",
            archivedOn = r[Tasks.archivedOn] ?: "",
            projectCode = label?.code ?: "",
        )
    }

    /**
     * Every card of one board, **except the archived ones**.
     *
     * The flag is filtered here rather than at each page because this is the one funnel every board, pill,
     * calendar row, Team count and roll-up reads through ([mn.murray.pts.service.TaskService.assigned] and
     * [mn.murray.pts.service.TaskService.other] are its only callers on the view side), and an archived card has
     * to be invisible to *all* of them at once: a card that left the board but still counted on the sidebar pill,
     * or still got a due-date mail, would be work that looks unfinished and cannot be started. The archive's own
     * read is [findArchived], and the popup's read is [findById], which deliberately does not filter — restoring
     * a card means being able to find it.
     */
    fun findByKind(kind: String): List<TaskItem> = transaction {
        val pn = projectLabels(); val bn = bucketNames(); val pbn = projectBucketNames()
        val cl = checklistsByTask(); val nt = notesByTask()
        Tasks.selectAll().where { (Tasks.kind eq kind) and (Tasks.archived eq false) }
            .orderBy(Tasks.id to SortOrder.ASC).map {
                map(it, pn, bn, pbn, cl, nt)
            }
    }

    /**
     * This project's archived cards, newest archive first (V18).
     *
     * Scoped to a project because the panel that prints it is opened from that project's header, the same way the
     * prototype it comes from filters its archive list by the project being looked at. [checklist] and [notes] come
     * back with the cards, which is the point of the flag design: an archived card is the same row with the same
     * children, so the panel's rows can show a card's plan and log exactly as they stood the day it left.
     */
    fun findArchived(projectId: Int): List<TaskItem> = transaction {
        val pn = projectLabels(); val bn = bucketNames(); val pbn = projectBucketNames()
        val cl = checklistsByTask(); val nt = notesByTask()
        Tasks.selectAll().where { (Tasks.projectId eq projectId) and (Tasks.archived eq true) }
            .orderBy(Tasks.archivedOn to SortOrder.DESC, Tasks.id to SortOrder.DESC).map {
                map(it, pn, bn, pbn, cl, nt)
            }
    }

    /**
     * Put one card in the Archive, remembering the lane it came from.
     *
     * [laneId] / [laneName] are the card's own lane at the moment of the call, snapshotted by the caller rather than
     * read here so a card whose column is about to be deleted is archived under the name its reader can still see
     * on the screen. The card's `project_bucket_id` is cleared in the same statement: an archived card that still
     * pointed at a lane would be counted by [ProjectBucketRepository.cardCounts] as work held in that column, which
     * is exactly the claim a column's removal has to stop making once its cards are archived.
     */
    fun archive(id: Int, laneId: Int?, laneName: String, day: String): Boolean = transaction {
        Tasks.update({ Tasks.id eq id }) {
            it[Tasks.archived] = true
            it[Tasks.archivedLaneId] = laneId
            it[Tasks.archivedLane] = laneName
            it[Tasks.archivedOn] = day
            it[Tasks.projectBucketId] = null
        } > 0
    }

    /**
     * Archive every card filed in one lane, in one statement, and report how many went (V18).
     *
     * This is what a removed bucket does to the work in it: the column and its cards go together, so the cards never
     * fall through to the `ON DELETE SET NULL` clause that would otherwise leave them un-filed on an Unsorted column
     * nobody chose. Filtered on `archived eq false` so a card cannot be archived twice — a second write would move
     * its "was in X" snapshot to a lane it has not stood in since the first removal.
     */
    fun archiveLane(laneId: Int, laneName: String, day: String): Int = transaction {
        Tasks.update({ (Tasks.projectBucketId eq laneId) and (Tasks.archived eq false) }) {
            it[Tasks.archived] = true
            it[Tasks.archivedLaneId] = laneId
            it[Tasks.archivedLane] = laneName
            it[Tasks.archivedOn] = day
            it[Tasks.projectBucketId] = null
        }
    }

    /**
     * Bring one card back off the Archive and file it in [laneId] (V18), clearing the snapshot with the flag.
     *
     * The snapshot is cleared rather than kept for history: [archivedLaneId] exists to answer "where did this card
     * go back", and once the card has gone back the answer is on the board as `project_bucket_id`. Leaving a stale
     * snapshot behind would be a second, quieter truth about the same card.
     */
    fun restore(id: Int, laneId: Int?): Boolean = transaction {
        Tasks.update({ Tasks.id eq id }) {
            it[Tasks.archived] = false
            it[Tasks.archivedLaneId] = null
            it[Tasks.archivedLane] = null
            it[Tasks.archivedOn] = null
            it[Tasks.projectBucketId] = laneId
        } > 0
    }

    /** How many of this project's cards are sitting in the Archive — the number on the header's button. */
    fun archivedCount(projectId: Int): Int = transaction {
        Tasks.selectAll().where { (Tasks.projectId eq projectId) and (Tasks.archived eq true) }.count().toInt()
    }

    fun findById(id: Int): TaskItem? = transaction {
        val pn = projectLabels(); val bn = bucketNames(); val pbn = projectBucketNames()
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
 *
 * [finishAll] and [restoreAll] are the two halves of one rule — a card that is finished has a plan
 * that is all ticked, and a card pulled back out of *Completed* gets the plan it had before. They
 * loop over the card's rows writing one statement per step because the memory each write has to keep
 * is that step's own previous value; see [TaskChecklistItems.preCompleted].
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

    /**
     * Append one step below the ones already on the card, **unticked**.
     *
     * There is no "start it done" argument (Stage 101): writing a line is saying the work is still to do,
     * so the row is written with `done = false` and the tick is a separate write ([setDone], or [finishAll]
     * when the card is moved into *Completed*). A step written onto a card that is already finished used to
     * arrive ticked, which read the card's status back as a claim about a line its owner had just noticed;
     * it now lands the way every other line does. It also carries no completion memory, so [restoreAll]
     * leaves it exactly as it was written.
     */
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

    /**
     * Tick every step on one card, having first stored where each one stood in [TaskChecklistItems.preCompleted].
     * This is the completion half of the plan rule in [mn.murray.pts.service.TaskService.updateStatus], and it
     * is deliberately a per-row write rather than one bulk `SET done = 1`: the memory has to be each step's own
     * previous value, which a single statement could not carry.
     *
     * Returns how many steps it touched, which is 0 for a card nobody has planned — a finished card with no
     * plan is not a card with an empty plan to restore later.
     */
    fun finishAll(taskId: Int): Int = transaction {
        val steps = TaskChecklistItems.selectAll().where { TaskChecklistItems.taskId eq taskId }
            .map { it[TaskChecklistItems.id] to it[TaskChecklistItems.done] }
        steps.forEach { (itemId, was) ->
            TaskChecklistItems.update({ TaskChecklistItems.id eq itemId }) {
                it[preCompleted] = was
                it[done] = true
            }
        }
        steps.size
    }

    /**
     * Put a card's steps back exactly as they were before [finishAll] ticked them, and forget the
     * memory so the next completion takes a fresh one. Only the steps that carry a memory move, so a
     * step added while the card was finished — which carries none — stays exactly as it was written
     * (unticked, since Stage 101) rather than being moved to a state it never had.
     *
     * Returns how many steps it moved. Zero is the ordinary answer for a card whose steps were all
     * unticked when it was finished, and for one that was never finished at all.
     */
    fun restoreAll(taskId: Int): Int = transaction {
        val remembered = TaskChecklistItems.selectAll().where { TaskChecklistItems.taskId eq taskId }
            .mapNotNull { row ->
                val was = row[TaskChecklistItems.preCompleted] ?: return@mapNotNull null
                row[TaskChecklistItems.id] to was
            }
        remembered.forEach { (itemId, was) ->
            TaskChecklistItems.update({ TaskChecklistItems.id eq itemId }) {
                it[done] = was
                it[preCompleted] = null
            }
        }
        remembered.size
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
