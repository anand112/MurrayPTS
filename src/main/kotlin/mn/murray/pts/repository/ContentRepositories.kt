package mn.murray.pts.repository

import mn.murray.pts.db.Forecasts
import mn.murray.pts.db.Lessons
import mn.murray.pts.db.Projects
import mn.murray.pts.db.TodoItems
import mn.murray.pts.db.TodoLists
import mn.murray.pts.domain.Forecast
import mn.murray.pts.domain.Lesson
import mn.murray.pts.domain.TodoItem
import mn.murray.pts.domain.TodoList
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Data-access layer for read-only planning content (forecasts, lessons) and the
 * personal to-do boards. Forecasts/lessons resolve their project FK back to a NAME so the
 * legacy view shape is preserved. To-do lists/items keep owner scoping and explicit
 * `position` ordering (the legacy store relied on array order).
 */

object ForecastRepository {
    fun findAll(): List<Forecast> = transaction {
        val pn = Projects.selectAll().associate { it[Projects.id] to it[Projects.name] }
        Forecasts.selectAll().orderBy(Forecasts.id to SortOrder.ASC).map {
            Forecast(
                project = it[Forecasts.projectId]?.let { id -> pn[id] } ?: "",
                period = it[Forecasts.period],
                hours = it[Forecasts.hours],
                note = it[Forecasts.note],
            )
        }
    }
}

object LessonRepository {
    fun findAll(): List<Lesson> = transaction {
        val pn = Projects.selectAll().associate { it[Projects.id] to it[Projects.name] }
        Lessons.selectAll().orderBy(Lessons.id to SortOrder.ASC).map {
            Lesson(
                title = it[Lessons.title],
                project = it[Lessons.projectId]?.let { id -> pn[id] } ?: "",
                summary = it[Lessons.summary],
            )
        }
    }
}

object TodoRepository {

    private fun mapList(r: ResultRow) = TodoList(
        id = r[TodoLists.id],
        name = r[TodoLists.name],
        done = r[TodoLists.done],
        owner = r[TodoLists.ownerEmpNo],
        due = r[TodoLists.due],
    )

    private fun mapItem(r: ResultRow) = TodoItem(
        id = r[TodoItems.id],
        title = r[TodoItems.title],
        done = r[TodoItems.done],
        list = r[TodoItems.listId],
        owner = r[TodoItems.ownerEmpNo],
        due = r[TodoItems.due],
    )

    fun listsByOwner(empNo: String): List<TodoList> = transaction {
        TodoLists.selectAll().where { TodoLists.ownerEmpNo eq empNo }
            .orderBy(TodoLists.position to SortOrder.ASC).map(::mapList)
    }

    /**
     * Every list across all owners, grouped by owner then ordered by position. Used by the
     * transitional [mn.murray.pts.DemoData] facade, which returns all rows and lets the view
     * filter by the current session's owner (legacy behaviour).
     */
    fun allLists(): List<TodoList> = transaction {
        TodoLists.selectAll()
            .orderBy(TodoLists.ownerEmpNo to SortOrder.ASC, TodoLists.position to SortOrder.ASC)
            .map(::mapList)
    }

    fun ListsByOder(empNo: String): List<TodoList> = transaction {
        TodoLists.selectAll().where { TodoLists.ownerEmpNo eq empNo }
            .orderBy(TodoLists.position to SortOrder.ASC).map(::mapList)
    }

    fun itemsByOwner(empNo: String): List<TodoItem> = transaction {
        TodoItems.selectAll().where { TodoItems.ownerEmpNo eq empNo }
            .orderBy(TodoItems.position to SortOrder.ASC).map(::mapItem)
    }

    /** Every to-do item across all owners, grouped by owner then ordered by position (facade use). */
    fun allItems(): List<TodoItem> = transaction {
        TodoItems.selectAll()
            .orderBy(TodoItems.ownerEmpNo to SortOrder.ASC, TodoItems.position to SortOrder.ASC)
            .map(::mapItem)
    }

    fun itemsByList(listId: Int): List<TodoItem> = transaction {
        TodoItems.selectAll().where { TodoItems.listId eq listId }
            .orderBy(TodoItems.position to SortOrder.ASC).map(::mapItem)
    }

    fun listOwner(listId: Int): String? = transaction {
        TodoLists.selectAll().where { TodoLists.id eq listId }.firstOrNull()?.get(TodoLists.ownerEmpNo)
    }

    fun itemOwner(itemId: Int): String? = transaction {
        TodoItems.selectAll().where { TodoItems.id eq itemId }.firstOrNull()?.get(TodoItems.ownerEmpNo)
    }

    fun insertList(name: String, owner: String, due: String = ""): Int = transaction {
        val maxPos = TodoLists.selectAll().where { TodoLists.ownerEmpNo eq owner }
            .map { it[TodoLists.position] }.maxOrNull() ?: -1
        TodoLists.insert {
            it[TodoLists.name] = name
            it[done] = false
            it[ownerEmpNo] = owner
            it[TodoLists.due] = due
            it[position] = maxPos + 1
        } get TodoLists.id
    }

    fun insertItem(title: String, listId: Int, owner: String, due: String = ""): Int = transaction {
        val maxPos = TodoItems.selectAll().where { TodoItems.listId eq listId }
            .map { it[TodoItems.position] }.maxOrNull() ?: -1
        TodoItems.insert {
            it[TodoItems.title] = title
            it[done] = false
            it[TodoItems.listId] = listId
            it[ownerEmpNo] = owner
            it[TodoItems.due] = due
            it[position] = maxPos + 1
        } get TodoItems.id
    }

    /** Flip an item's done flag; returns the new state, or null if the item is gone. */
    fun toggleItem(itemId: Int): Boolean? = transaction {
        val cur = TodoItems.selectAll().where { TodoItems.id eq itemId }.firstOrNull()?.get(TodoItems.done)
            ?: return@transaction null
        TodoItems.update({ TodoItems.id eq itemId }) { it[done] = !cur }
        !cur
    }

    fun setItemDue(itemId: Int, due: String) = transaction {
        TodoItems.update({ TodoItems.id eq itemId }) { it[TodoItems.due] = due }
    }

    fun deleteItem(itemId: Int): Int = transaction { TodoItems.deleteWhere { TodoItems.id eq itemId } }

    fun toggleList(listId: Int) = transaction {
        val cur = TodoLists.selectAll().where { TodoLists.id eq listId }.firstOrNull()?.get(TodoLists.done)
            ?: return@transaction
        TodoLists.update({ TodoLists.id eq listId }) { it[done] = !cur }
    }

    fun setListDue(listId: Int, due: String) = transaction {
        TodoLists.update({ TodoLists.id eq listId }) { it[TodoLists.due] = due }
    }

    fun renameList(listId: Int, name: String) = transaction {
        TodoLists.update({ TodoLists.id eq listId }) { it[TodoLists.name] = name }
    }

    fun deleteList(listId: Int): Int = transaction { TodoLists.deleteWhere { TodoLists.id eq listId } }

    /** Persist a drag-reorder of an owner's lists (legacy: `order=` CSV of list ids). */
    fun reorderLists(owner: String, orderedIds: List<Int>) = transaction {
        orderedIds.forEachIndexed { idx, listId ->
            TodoLists.update({ (TodoLists.id eq listId) and (TodoLists.ownerEmpNo eq owner) }) {
                it[position] = idx
            }
        }
    }

    /** Move a list one step up/down within its owner's ordering (legacy move?dir=). */
    fun moveList(listId: Int, up: Boolean) = transaction {
        val owner = TodoLists.selectAll().where { TodoLists.id eq listId }.firstOrNull()?.get(TodoLists.ownerEmpNo)
            ?: return@transaction
        val ordered = TodoLists.selectAll().where { TodoLists.ownerEmpNo eq owner }
            .orderBy(TodoLists.position to SortOrder.ASC).map { it[TodoLists.id] }.toMutableList()
        val from = ordered.indexOf(listId)
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to < 0 || to >= ordered.size) return@transaction
        ordered[from] = ordered[to].also { ordered[to] = ordered[from] }
        ordered.forEachIndexed { idx, id ->
            TodoLists.update({ TodoLists.id eq id }) { it[position] = idx }
        }
    }
}
