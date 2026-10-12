package mn.murray.pts.domain

/**
 * Personal to-do boards. A [TodoItem] belongs to a [TodoList]; both are owned by
 * an employee number. List order is significant (drag-to-reorder) and is captured
 * by the `position` column in the DB rather than array order.
 */

@kotlinx.serialization.Serializable
data class TodoItem(
    val id: Int = 0,
    val title: String,
    val done: Boolean,
    val list: Int = 1,
    val owner: String = "",
    val due: String = "",
)

@kotlinx.serialization.Serializable
data class TodoList(
    val id: Int,
    val name: String,
    val done: Boolean = false,
    val owner: String = "",
    val due: String = "",
)
