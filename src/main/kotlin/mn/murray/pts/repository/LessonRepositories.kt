package mn.murray.pts.repository

import mn.murray.pts.db.Lessons
import mn.murray.pts.db.Projects
import mn.murray.pts.db.Tasks
import mn.murray.pts.db.TeamMembers
import mn.murray.pts.domain.LessonEntry
import mn.murray.pts.domain.LessonTaskChoice
import mn.murray.pts.domain.lessonTextOf
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Data-access layer for the Lesson learnt sheet (V9).
 *
 * The sheet is small and it is read whole — every row, then filtered and sorted by
 * [mn.murray.pts.service.LessonService] — so this file resolves the three foreign keys once per
 * read rather than joining per column: a lesson's person, project and card are looked up in one
 * pass over the three tables they come from, and a row whose key has gone NULL prints as a blank
 * cell instead of disappearing. That is the whole reason the columns are `ON DELETE SET NULL`:
 * losing a project must not silently take its lessons off the page.
 *
 * Nothing here decides rights, dates or what a blank means. [mn.murray.pts.service.LessonService]
 * does, and the routes ask it.
 */
object LessonRepository {

    /** Every lesson, newest first with the undated carry-overs last. */
    fun all(): List<LessonEntry> = transaction {
        val ctx = context()
        Lessons.selectAll()
            .orderBy(Lessons.loggedOn to SortOrder.DESC, Lessons.id to SortOrder.DESC)
            .map { entry(it, ctx) }
    }

    /** One row as it stands in the database, or null when it has gone. */
    fun find(id: Int): LessonEntry? = transaction {
        val ctx = context()
        Lessons.selectAll().where { Lessons.id eq id }.firstOrNull()?.let { entry(it, ctx) }
    }

    /**
     * Write a lesson and answer with its id.
     *
     * `title` is filled with the same text as `lesson` because the V1 column is NOT NULL with no
     * default and the table was widened rather than rebuilt — see V9. Nothing on the page reads it.
     */
    fun create(
        text: String,
        employeeNumber: String?,
        projectId: Int?,
        taskId: Int?,
        loggedOn: String,
        discussed: Boolean = false,
    ): Int = transaction {
        Lessons.insert {
            it[title] = text
            it[summary] = ""
            it[lesson] = text
            it[Lessons.employeeNumber] = employeeNumber
            it[Lessons.projectId] = projectId
            it[Lessons.taskId] = taskId
            it[Lessons.loggedOn] = loggedOn
            it[Lessons.discussed] = discussed
            it[Lessons.archived] = false
        } get Lessons.id
    }

    /** The four fields the popup holds. The author and the archive state are not among them. */
    fun update(
        id: Int,
        text: String,
        projectId: Int?,
        taskId: Int?,
        loggedOn: String,
    ) = transaction {
        Lessons.update({ Lessons.id eq id }) {
            it[title] = text
            it[lesson] = text
            it[Lessons.projectId] = projectId
            it[Lessons.taskId] = taskId
            it[Lessons.loggedOn] = loggedOn
        }
    }

    fun setDiscussed(id: Int, discussed: Boolean) = transaction {
        Lessons.update({ Lessons.id eq id }) { it[Lessons.discussed] = discussed }
    }

    /** Rows actually removed — 0 is how a write against a lesson that has gone reports itself. */
    fun remove(id: Int): Int = transaction { Lessons.deleteWhere { Lessons.id eq id } }

    /** The number the sidebar pill counts: the lessons still on the active sheet. */
    fun countActive(): Int = transaction {
        Lessons.selectAll().where { Lessons.archived eq false }.count().toInt()
    }

    /**
     * Every card the popup may point a lesson at, under its project's number.
     *
     * Both boards' cards are offered, and a card whose project has gone is offered too — its row is
     * still on a page somebody may be writing the lesson about. The service reads the same pairing
     * back off the database rather than trusting what the form says about it.
     */
    fun taskChoices(): List<LessonTaskChoice> = transaction {
        val codes = Projects.selectAll().associate {
            it[Projects.id] to it[Projects.code]
        }
        Tasks.selectAll()
            .where { Tasks.archived eq false }
            .orderBy(Tasks.projectId to SortOrder.ASC, Tasks.title to SortOrder.ASC)
            .map {
                LessonTaskChoice(
                    id = it[Tasks.id],
                    title = it[Tasks.title],
                    projectId = it[Tasks.projectId] ?: 0,
                    projectCode = it[Tasks.projectId]?.let { id -> codes[id] }.orEmpty(),
                )
            }
    }

    /** The project a card belongs to, for the rule that a lesson cannot be filed under two. */
    fun projectOfTask(taskId: Int): Int? = transaction {
        Tasks.selectAll().where { Tasks.id eq taskId }.firstOrNull()?.get(Tasks.projectId)
    }

    fun projectExists(projectId: Int): Boolean = transaction {
        Projects.selectAll().where { Projects.id eq projectId }.any()
    }

    fun taskExists(taskId: Int): Boolean = transaction {
        Tasks.selectAll().where { Tasks.id eq taskId }.any()
    }

    /**
     * The three lookup tables the sheet needs, read once per query.
     *
     * They are built together so a page load costs one pass over the roster, the projects and the
     * cards, and so a row can never be mapped against a half-loaded set of them.
     */
    private class Context(
        val names: Map<String, String>,
        val projects: Map<Int, Pair<String, String>>,
        val tasks: Map<Int, Pair<String, Int>>,
    )

    private fun context(): Context = Context(
        names = TeamMembers.selectAll().associate {
            it[TeamMembers.employeeNumber] to it[TeamMembers.name]
        },
        projects = Projects.selectAll().associate {
            it[Projects.id] to (it[Projects.code] to it[Projects.name])
        },
        tasks = Tasks.selectAll().associate {
            it[Tasks.id] to (it[Tasks.title] to (it[Tasks.projectId] ?: 0))
        },
    )

    private fun entry(r: ResultRow, ctx: Context): LessonEntry {
        val empNo = r[Lessons.employeeNumber]
        val projectId = r[Lessons.projectId]
        val taskId = r[Lessons.taskId]
        val project = projectId?.let { ctx.projects[it] }
        val task = taskId?.let { ctx.tasks[it] }
        return LessonEntry(
            id = r[Lessons.id],
            text = r[Lessons.lesson].ifBlank { lessonTextOf(r[Lessons.title], r[Lessons.summary]) },
            employeeNumber = empNo.orEmpty(),
            employeeName = empNo?.let { ctx.names[it] }.orEmpty(),
            projectId = projectId ?: 0,
            projectCode = project?.first.orEmpty(),
            projectName = project?.second.orEmpty(),
            taskId = taskId ?: 0,
            taskTitle = task?.first.orEmpty(),
            loggedOn = r[Lessons.loggedOn],
            discussed = r[Lessons.discussed],
            archived = r[Lessons.archived],
        )
    }
}
