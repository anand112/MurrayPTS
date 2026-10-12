package mn.murray.pts.domain

/**
 * Task domain model. A single [TaskItem] shape backs both the assigned-tasks
 * kanban and the other-tasks board; the `kind` discriminator on the DB row
 * (see db/tables/Tasks.kt) tells them apart.
 */

val TASK_STATUSES = listOf("Not started", "In progress", "On hold", "Blocked", "Completed")

@kotlinx.serialization.Serializable
data class TaskItem(
    val title: String,
    val project: String,
    val due: String,
    val priority: String,
    val assignee: String = "",
    val status: String = "Not started",
    val id: Int = 0,
    val bucket: String = "Management",
    val started: String = "",
)

/**
 * Kanban column for the other-tasks board. The legacy store kept buckets as a plain
 * `List<String>`; v2 gives them an id + explicit position so tasks can reference a
 * bucket by FK and the board order is stable.
 */
data class Bucket(
    val id: Int = 0,
    val name: String,
    val position: Int = 0,
)
