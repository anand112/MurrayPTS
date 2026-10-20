package mn.murray.pts.domain

/**
 * The row the Lesson learnt sheet reads (V9).
 *
 * It is one row of `lessons` with its three foreign keys already resolved to the words the sheet
 * prints — the person's name, the project's number, the card's title — so the view never has to
 * look a lesson up in another list, and the service never has to promise that a project id it
 * handed out is still on the page.
 *
 * The blanks are meaningful and they are not the same statement: [employeeNumber] is empty for a
 * row V9 carried over from the old notes, which nobody owns and only management can tidy, while
 * [projectId] and [taskId] are 0 for a lesson that was never about a project or a card at all.
 * [loggedOn] is empty for the same carry-overs, and the sheet sorts them to the end rather than
 * inventing a date for them.
 */
data class LessonEntry(
    val id: Int,
    /** What was learned, as one cell of text. */
    val text: String,
    val employeeNumber: String = "",
    val employeeName: String = "",
    val projectId: Int = 0,
    val projectCode: String = "",
    val projectName: String = "",
    val taskId: Int = 0,
    val taskTitle: String = "",
    /** 'YYYY-MM-DD', or '' when the row arrived undated. */
    val loggedOn: String = "",
    val discussed: Boolean = false,
    val archived: Boolean = false,
    /** Whether the reader looking at this row may write it: its author, or management. */
    val mayEdit: Boolean = false,
) {
    val status: String get() = if (discussed) DISCUSSED else NOT_DISCUSSED

    /** Whether anybody owns it. An unowned row is management's to delete, never a peer's. */
    val isOwned: Boolean get() = employeeNumber.isNotEmpty()
}

/** The two states the sheet's pill prints. */
const val NOT_DISCUSSED: String = "Not discussed"
const val DISCUSSED: String = "Discussed"

/** One card the popup's task picker can name, with the project it belongs to. */
data class LessonTaskChoice(
    val id: Int,
    val title: String,
    /** 0 for a backlog card, which belongs to no project. */
    val projectId: Int = 0,
    val projectCode: String = "",
)

/** The most the page will hold in one cell. Long enough for a paragraph, short enough to read. */
const val LESSON_TEXT_MAX: Int = 500

/**
 * The one text a lesson row carries, from the V1 pair that used to be the whole record.
 *
 * V9's backfill runs this same join in SQL, and the importer and the demo seeder run it here, so
 * a note that only ever had a title keeps just its title rather than a dangling separator.
 */
fun lessonTextOf(title: String, summary: String): String =
    listOf(title.trim(), summary.trim()).filter { it.isNotEmpty() }.joinToString(" — ")
