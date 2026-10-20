package mn.murray.pts.repository

import mn.murray.pts.db.DueDateAlerts
import mn.murray.pts.db.ProjectAssignees
import mn.murray.pts.db.Projects
import mn.murray.pts.db.Tasks
import mn.murray.pts.db.TeamMembers
import mn.murray.pts.db.Users
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Data access for the due-date alert feature: the open items a sweep must look at, and the
 * ledger that records what was already said to whom.
 *
 * Nothing here decides *whether* an item deserves an alert — status, progress and the lead-time
 * window live in `service/DueDateAlertService`, where they are unit-testable. This layer only
 * reads rows and writes attempts. Reads are whole-table on purpose: the roster is a few dozen
 * projects and tasks, and pulling the people maps once avoids an N+1 per recipient.
 */

/** A project reduced to what an alert needs, with people as employee numbers (not names). */
data class ProjectDueRow(
    val id: Int,
    val code: String,
    val name: String,
    val status: String,
    val progress: Int,
    val due: String,
    val type: String,
    val managerEmpNo: String,
    val assigneeEmpNos: List<String>,
)

/** An assigned/other task plus its project's identity, so a task alert can copy the manager. */
data class TaskDueRow(
    val id: Int,
    val title: String,
    val status: String,
    val priority: String,
    val due: String,
    val assigneeEmpNo: String,
    val projectId: Int?,
    val projectCode: String,
    val projectName: String,
    val projectManagerEmpNo: String,
)

/** One recorded delivery attempt. Read by the admin "recent alerts" view and by tests. */
data class AlertLedgerRow(
    val id: Int,
    val kind: String,
    val itemId: Int,
    val recipientEmpNo: String,
    val alertDate: String,
    val subject: String,
    val body: String,
    val due: String,
    val delivered: Boolean,
    val channel: String,
    val error: String,
)

object AlertRepository {

    /** Projects whose status is not Completed. Progress and the date window are the service's call. */
    fun openProjects(): List<ProjectDueRow> = transaction {
        val assigneesByProject = ProjectAssignees.selectAll()
            .groupBy({ it[ProjectAssignees.projectId] }, { it[ProjectAssignees.employeeNumber] })
        Projects.selectAll().orderBy(Projects.id to SortOrder.ASC)
            .map { r ->
                ProjectDueRow(
                    id = r[Projects.id],
                    code = r[Projects.code],
                    name = r[Projects.name],
                    status = r[Projects.status],
                    progress = r[Projects.progress],
                    due = r[Projects.due],
                    type = r[Projects.type],
                    managerEmpNo = r[Projects.managerEmpNo].orEmpty(),
                    assigneeEmpNos = assigneesByProject[r[Projects.id]].orEmpty(),
                )
            }
            .filter { it.status != "Completed" }
    }

    /** Tasks whose status is not Completed, with the parent project resolved for the label + manager. */
    fun openTasks(): List<TaskDueRow> = transaction {
        val projects = Projects.selectAll().associate { r ->
            r[Projects.id] to Triple(r[Projects.code], r[Projects.name], r[Projects.managerEmpNo].orEmpty())
        }
        Tasks.selectAll().orderBy(Tasks.id to SortOrder.ASC)
            .map { r ->
                val pid = r[Tasks.projectId]
                val project = pid?.let { projects[it] }
                TaskDueRow(
                    id = r[Tasks.id],
                    title = r[Tasks.title],
                    status = r[Tasks.status],
                    priority = r[Tasks.priority],
                    due = r[Tasks.due],
                    assigneeEmpNo = r[Tasks.assigneeEmpNo].orEmpty(),
                    projectId = pid,
                    projectCode = project?.first.orEmpty(),
                    projectName = project?.second.orEmpty(),
                    projectManagerEmpNo = project?.third.orEmpty(),
                )
            }
            .filter { it.status != "Completed" }
    }

    fun displayNames(): Map<String, String> = transaction {
        TeamMembers.selectAll().associate { it[TeamMembers.employeeNumber] to it[TeamMembers.name] }
    }

    /**
     * Alert addresses come from `users.email`, the only address the app owns. A team member with no
     * user row, or a blank email, still gets a ledger row — recorded as "not delivered, no address
     * on file" rather than silently vanishing, so the gap is visible to whoever maintains accounts.
     */
    fun emailsByEmpNo(): Map<String, String> = transaction {
        Users.selectAll().filter { it[Users.email].isNotBlank() }
            .associate { it[Users.employeeNumber] to it[Users.email] }
    }

    /**
     * Every `(kind, item, recipient)` already alerted on [alertDate]. Loading the day once is cheaper
     * than a probe per recipient, and it is the dedupe that survives restarts and manual re-runs.
     */
    fun keysForDay(alertDate: String): Set<String> = transaction {
        DueDateAlerts.selectAll().where { DueDateAlerts.alertDate eq alertDate }
            .map { row -> key(row[DueDateAlerts.kind], row[DueDateAlerts.itemId], row[DueDateAlerts.recipientEmpNo]) }
            .toSet()
    }

    fun key(kind: String, itemId: Int, recipientEmpNo: String): String = "$kind:$itemId:$recipientEmpNo"

    /**
     * Record one delivery attempt. Returns false only when the row already existed (same person,
     * same item, same day): the UNIQUE index is the arbiter, so two overlapping sweeps cannot mail
     * twice. Any other SQLite error is rethrown — losing an alert to a silent constraint swallow
     * would be worse than the stack trace it produces.
     */
    fun record(
        kind: String,
        itemId: Int,
        recipientEmpNo: String,
        alertDate: String,
        subject: String,
        body: String,
        due: String,
        delivered: Boolean,
        channel: String,
        error: String,
        sentAt: String,
    ): Boolean = transaction {
        try {
            DueDateAlerts.insert {
                it[DueDateAlerts.kind] = kind
                it[DueDateAlerts.itemId] = itemId
                it[DueDateAlerts.recipientEmpNo] = recipientEmpNo
                it[DueDateAlerts.alertDate] = alertDate
                it[DueDateAlerts.subject] = subject
                it[DueDateAlerts.body] = body
                it[DueDateAlerts.due] = due
                it[DueDateAlerts.delivered] = delivered
                it[DueDateAlerts.channel] = channel
                it[DueDateAlerts.error] = error
                it[DueDateAlerts.sentAt] = sentAt
            }
            true
        } catch (e: Exception) {
            // Exposed wraps the driver exception (and SQLite reports it as text, not a code), so the
            // whole cause chain is what identifies "this row was already logged today".
            val chain = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
            if ("UNIQUE constraint failed" in chain) false else throw e
        }
    }

    /** Newest first, for "what has this system mailed lately". */
    fun recent(limit: Int = 50): List<AlertLedgerRow> = transaction {
        DueDateAlerts.selectAll()
            .orderBy(DueDateAlerts.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 200))
            .map(::mapLedger)
    }

    /** Ledger rows for one calendar day, oldest first. */
    fun forDay(alertDate: String): List<AlertLedgerRow> = transaction {
        DueDateAlerts.selectAll().where { DueDateAlerts.alertDate eq alertDate }
            .orderBy(DueDateAlerts.id to SortOrder.ASC).map(::mapLedger)
    }

    private fun mapLedger(r: ResultRow) = AlertLedgerRow(
        id = r[DueDateAlerts.id],
        kind = r[DueDateAlerts.kind],
        itemId = r[DueDateAlerts.itemId],
        recipientEmpNo = r[DueDateAlerts.recipientEmpNo],
        alertDate = r[DueDateAlerts.alertDate],
        subject = r[DueDateAlerts.subject],
        body = r[DueDateAlerts.body],
        due = r[DueDateAlerts.due],
        delivered = r[DueDateAlerts.delivered],
        channel = r[DueDateAlerts.channel],
        error = r[DueDateAlerts.error],
    )

    /**
     * Drop ledger history before [cutoffDate] (yyyy-MM-dd). The ledger is an audit trail, not a
     * report, so it gets trimmed to keep the SQLite file bounded. Safe as a string compare because
     * `alert_date` is always the fixed-width form this service writes — unlike the variable-precision
     * ISO instants that [SessionRepository.purgeExpired] has to parse instead.
     */
    fun purgeOlderThan(cutoffDate: String): Int = transaction {
        DueDateAlerts.deleteWhere { DueDateAlerts.alertDate less cutoffDate }
    }
}
