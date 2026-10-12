package mn.murray.pts.service

import mn.murray.pts.domain.TodoItem
import mn.murray.pts.domain.TodoList
import mn.murray.pts.repository.TodoRepository

/**
 * Application-level operations for the personal to-do boards.
 *
 * To-do lists and items are strictly owner-scoped: every mutation re-checks that the target
 * row belongs to the acting employee before writing, which is the invariant the legacy route
 * handlers enforced inline against the JSON store. Reads come in two flavours — per-owner
 * (used by the board) and all-owners (used only by the transitional `DemoData` facade, whose
 * getters take no arguments and let the view filter by session owner).
 */
object TodoService {

    // ---- reads ---------------------------------------------------------------

    fun listsFor(empNo: String): List<TodoList> = TodoRepository.listsByOwner(empNo)

    fun itemsFor(empNo: String): List<TodoItem> = TodoRepository.itemsByOwner(empNo)

    /** All lists across owners (facade use); the view filters by the session owner. */
    fun allLists(): List<TodoList> = TodoRepository.allLists()

    /** All items across owners (facade use); the view filters by the session owner. */
    fun allItems(): List<TodoItem> = TodoRepository.allItems()

    // ---- lists ---------------------------------------------------------------

    /** Create a list owned by [empNo]. Returns the new id, or null when the name is blank. */
    fun createList(empNo: String, name: String, due: String): Int? {
        if (name.isBlank()) return null
        return TodoRepository.insertList(name, empNo, due)
    }

    /** Delete a list (and, via FK cascade, its items) if it belongs to [empNo]. */
    fun deleteList(empNo: String, listId: Int) {
        if (TodoRepository.listOwner(listId) != empNo) return
        TodoRepository.deleteList(listId)
    }

    /** Flip a list's done flag if it belongs to [empNo]. */
    fun toggleList(empNo: String, listId: Int) {
        if (TodoRepository.listOwner(listId) != empNo) return
        TodoRepository.toggleList(listId)
    }

    /** Set a list's end date if it belongs to [empNo]. */
    fun setListDue(empNo: String, listId: Int, due: String) {
        if (TodoRepository.listOwner(listId) != empNo) return
        TodoRepository.setListDue(listId, due)
    }

    /** Move a list one step earlier/later within [empNo]'s ordering. */
    fun moveList(empNo: String, listId: Int, up: Boolean) {
        if (TodoRepository.listOwner(listId) != empNo) return
        TodoRepository.moveList(listId, up)
    }

    /**
     * Persist a drag-reorder of [empNo]'s lists. Ids that are not owned by [empNo] are ignored
     * by the repository's owner-scoped update, matching the legacy `order=` CSV behaviour.
     */
    fun reorderLists(empNo: String, orderedIds: List<Int>) {
        val ids = orderedIds.distinct()
        if (ids.isEmpty()) return
        TodoRepository.reorderLists(empNo, ids)
    }

    // ---- items ---------------------------------------------------------------

    /**
     * Add an item to one of [empNo]'s lists. Falls back to their first list when the requested
     * list is not theirs (legacy behaviour); does nothing when they have no lists.
     */
    fun createItem(empNo: String, title: String, requestedListId: Int, due: String): Int? {
        if (title.isBlank()) return null
        val mine = TodoRepository.listsByOwner(empNo)
        val listId = mine.firstOrNull { it.id == requestedListId }?.id ?: mine.firstOrNull()?.id ?: return null
        return TodoRepository.insertItem(title, listId, empNo, due)
    }

    /** Set an item's end date if it belongs to [empNo]. */
    fun setItemDue(empNo: String, itemId: Int, due: String) {
        if (TodoRepository.itemOwner(itemId) != empNo) return
        TodoRepository.setItemDue(itemId, due)
    }

    /** Flip an item's done flag if it belongs to [empNo]. */
    fun toggleItem(empNo: String, itemId: Int) {
        if (TodoRepository.itemOwner(itemId) != empNo) return
        TodoRepository.toggleItem(itemId)
    }

    /** Delete an item if it belongs to [empNo]. */
    fun deleteItem(empNo: String, itemId: Int) {
        if (TodoRepository.itemOwner(itemId) != empNo) return
        TodoRepository.deleteItem(itemId)
    }
}
