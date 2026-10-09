package mn.murray.pts.repository

import mn.murray.pts.db.Buckets
import mn.murray.pts.db.Pins
import mn.murray.pts.db.ProjectAssignees
import mn.murray.pts.db.Projects
import mn.murray.pts.db.Tasks
import mn.murray.pts.db.TeamMembers
import mn.murray.pts.domain.Bucket
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.TaskItem
import mn.murray.pts.domain.nextProjectCode
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

    fun insert(code: String, name: String, status: String, progress: Int, managerEmpNo: String?, due: String, type: String): Int = transaction {
        val now = Instant.now().toString()
        Projects.insert {
            it[Projects.code] = code
            it[Projects.name] = name
            it[Projects.status] = status
            it[Projects.progress] = progress
            it[Projects.managerEmpNo] = managerEmpNo
            it[Projects.due] = due
            it[Projects.type] = type
            it[createdAt] = now
            it[updatedAt] = now
        } get Projects.id
    }

    fun update(id: Int, name: String, status: String, progress: Int, managerEmpNo: String?, due: String, type: String) = transaction {
        Projects.update({ Projects.id eq id }) {
            it[Projects.name] = name
            it[Projects.status] = status
            it[Projects.progress] = progress
            it[Projects.managerEmpNo] = managerEmpNo
            it[Projects.due] = due
            it[Projects.type] = type
            it[updatedAt] = Instant.now().toString()
        }
    }

    fun setManager(id: Int, empNo: String?) = transaction {
        Projects.update({ Projects.id eq id }) { it[managerEmpNo] = empNo; it[updatedAt] = Instant.now().toString() }
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

    /** Next `PREFIX-NN` code for a type, computed from existing rows (legacy rule). */
    fun nextCode(type: String): String = transaction {
        val all = Projects.selectAll().map {
            Project(
                id = it[Projects.id], code = it[Projects.code], name = it[Projects.name],
                status = it[Projects.status], progress = it[Projects.progress], manager = "",
                due = it[Projects.due], type = it[Projects.type],
            )
        }
        nextProjectCode(all, type)
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

    fun findAll(): List<Bucket> = transaction {
        Buckets.selectAll().orderBy(Buckets.position to SortOrder.ASC)
            .map { Bucket(id = it[Buckets.id], name = it[Buckets.name], position = it[Buckets.position]) }
    }

    fun names(): List<String> = findAll().map { it.name }

    fun idByName(): Map<String, Int> = transaction {
        Buckets.selectAll().associate { it[Buckets.name] to it[Buckets.id] }
    }

    fun insert(bucketName: String): Int = transaction {
        val maxPos = Buckets.selectAll().map { it[Buckets.position] }.maxOrNull() ?: -1
        Buckets.insert { it[name] = bucketName; it[position] = maxPos + 1 } get Buckets.id
    }

    fun delete(id: Int): Int = transaction { Buckets.deleteWhere { Buckets.id eq id } }
}

object TaskRepository {

    private fun projectNames(): Map<Int, String> =
        Projects.selectAll().associate { it[Projects.id] to it[Projects.name] }

    private fun bucketNames(): Map<Int, String> =
        Buckets.selectAll().associate { it[Buckets.id] to it[Buckets.name] }

    private fun map(r: ResultRow, projectNames: Map<Int, String>, bucketNames: Map<Int, String>) = TaskItem(
        title = r[Tasks.title],
        project = r[Tasks.projectId]?.let { projectNames[it] } ?: "",
        due = r[Tasks.due],
        priority = r[Tasks.priority],
        assignee = r[Tasks.assigneeEmpNo] ?: "",
        status = r[Tasks.status],
        id = r[Tasks.id],
        bucket = r[Tasks.bucketId]?.let { bucketNames[it] } ?: "",
        started = r[Tasks.started],
    )

    fun findByKind(kind: String): List<TaskItem> = transaction {
        val pn = projectNames(); val bn = bucketNames()
        Tasks.selectAll().where { Tasks.kind eq kind }.orderBy(Tasks.id to SortOrder.ASC).map { map(it, pn, bn) }
    }

    fun findById(id: Int): TaskItem? = transaction {
        val pn = projectNames(); val bn = bucketNames()
        Tasks.selectAll().where { Tasks.id eq id }.firstOrNull()?.let { map(it, pn, bn) }
    }

    fun assigneeOf(id: Int): String? = transaction {
        Tasks.selectAll().where { Tasks.id eq id }.firstOrNull()?.get(Tasks.assigneeEmpNo)
    }

    fun insert(
        kind: String, title: String, projectId: Int?, due: String, priority: String,
        assigneeEmpNo: String?, status: String, bucketId: Int?, started: String,
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
            it[Tasks.started] = started
        } get Tasks.id
    }

    fun updateStatus(id: Int, status: String) = transaction {
        Tasks.update({ Tasks.id eq id }) { it[Tasks.status] = status }
    }

    fun setBucket(id: Int, bucketId: Int?) = transaction {
        Tasks.update({ Tasks.id eq id }) { it[Tasks.bucketId] = bucketId }
    }

    fun delete(id: Int): Int = transaction { Tasks.deleteWhere { Tasks.id eq id } }
}
