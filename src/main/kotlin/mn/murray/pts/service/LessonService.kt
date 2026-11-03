package mn.murray.pts.service

import mn.murray.pts.domain.LESSON_TEXT_MAX
import mn.murray.pts.domain.LessonEntry
import mn.murray.pts.domain.LessonTaskChoice
import mn.murray.pts.repository.LessonRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The rules behind the Lesson learnt sheet (V9): what a row is, who may write it, and what the
 * sheet does with a row that has been discussed or put away.
 *
 * Three of them are worth stating before the code, because each one is a place where the picture
 * the page paints and the database underneath could quietly disagree:
 *
 *  * **The employee column is not a field.** A row's person is whoever logged it, taken from the
 *    session on the way in — see [save]. The sheet is a record of who learned something, and a
 *    picker next to it would let a lesson be attributed to whoever was handy.
 *  * **A card pins its lesson to its project.** [LessonDraft] carries both, and they may not
 *    name two different projects: a row that read "M-01250 / a card on S-00005" would be a
 *    statement the sheet cannot check. The refusal says which of the two has to move.
 *  * **A lesson is read, discussed or deleted.** The archive a row could once be put away in is
 *    retired (Stage 116); its `archived` column stays so rows put away before then are still data,
 *    but nothing prints them and nothing writes the flag any more.
 *
 * Rights are decided here and re-checked at every route, exactly as [ProjectService] does: a
 * button the page did not print is not a permission the server granted.
 */
object LessonService {

    /** Whether this reader may write this row: management any, an ordinary account only their own. */
    fun mayWrite(entry: LessonEntry, employeeNumber: String, canManage: Boolean): Boolean =
        canManage || (entry.isOwned && entry.employeeNumber == employeeNumber)

    /**
     * The sheet: every lesson that is not put away, cut to the two pickers and ordered by the date
     * column.
     *
     * [LessonEntry.mayEdit] is stamped on each row here, so the view asks a row rather than
     * re-deriving the rule — and so a row an ordinary account wrote prints its buttons beside their
     * own name and nowhere else.
     */
    fun board(
        filter: LessonFilter = LessonFilter(),
        employeeNumber: String = "",
        canManage: Boolean = false,
    ): LessonBoard {
        val rows = LessonRepository.all().map {
            it.copy(mayEdit = mayWrite(it, employeeNumber, canManage))
        }.filter { filter.matches(it) && !it.archived }
        return LessonBoard(active = rows.sortedWith(filter.order))
    }

    /** What the sidebar pill counts, with no filter and no archive list in the way. */
    fun activeCount(): Int = LessonRepository.countActive()

    /**
     * One row by id, for the route behind `?edit=<id>`.
     *
     * An accessor rather than a repository call from [mn.murray.pts.Application]: a route that asked the
     * database directly would have to decide the rights question twice — once here, once there — and the
     * popup's read-only half is exactly the kind of thing that gets drawn from the wrong one.
     */
    fun find(id: Int): LessonEntry? = LessonRepository.find(id)

    /** The cards the popup's Card picker lists, grouped by the project each is filed under. */
    fun taskChoices(): List<LessonTaskChoice> = LessonRepository.taskChoices()

    /**
     * Write a lesson — a new one when [LessonDraft.id] is 0, an existing one otherwise — and answer
     * with either nothing to say or the field each refusal belongs under.
     *
     * A new row's author is [employeeNumber] and nothing else, whatever the form claims. An edit
     * keeps the row's own author: rewriting a lesson must not move it to the name of whoever
     * happened to open the popup.
     */
    fun save(draft: LessonDraft, employeeNumber: String, canManage: Boolean): LessonResult {
        val existing = if (draft.id == 0) null else LessonRepository.find(draft.id)
        if (draft.id != 0 && existing == null) return LessonResult.refused(draft, LessonField.ROW to ROW_GONE)
        if (existing != null && !mayWrite(existing, employeeNumber, canManage)) {
            return LessonResult.refused(draft, LessonField.PERMISSION to NOT_YOURS)
        }
        val errors = LinkedHashMap<LessonField, String>()

        val text = draft.text.trim()
        if (text.isEmpty()) errors += LessonField.TEXT to BLANK_LESSON
        if (text.length > LESSON_TEXT_MAX) errors += LessonField.TEXT to "A lesson is $LESSON_TEXT_MAX characters or fewer."

        val day = draft.loggedOn.trim().ifEmpty { today() }
        if (!isIsoDate(day)) errors += LessonField.DATE to "Choose a date the sheet can sort by."

        val projectId = draft.projectId
        if (projectId != 0 && !LessonRepository.projectExists(projectId)) {
            errors += LessonField.PROJECT to "That project is no longer on the list."
        }
        val taskId = draft.taskId
        if (taskId != 0 && !LessonRepository.taskExists(taskId)) {
            errors += LessonField.TASK to "That card is no longer on a board."
        }
        // A card names its own project, so the two fields cannot disagree. Read off the database,
        // never off what the form chose to say about the card it is pointing at.
        if (errors.isEmpty() && taskId != 0) {
            val cardProject = LessonRepository.projectOfTask(taskId) ?: 0
            if (cardProject != projectId) {
                errors += LessonField.TASK to
                    "That card is ${if (cardProject == 0) "on no project" else "on another project"} — " +
                    "clear it here, or choose its project above."
            }
        }
        if (errors.isNotEmpty()) return LessonResult.refused(draft, errors)

        if (existing == null) {
            LessonRepository.create(
                text = text,
                // An account with no number cannot own a row, and one that is not on the roster would be a
                // foreign key to a member who does not exist. NULL is the state the sheet already reads as
                // "unowned, management's to tidy".
                employeeNumber = employeeNumber.ifEmpty { null },
                projectId = projectId.takeIf { it != 0 },
                taskId = taskId.takeIf { it != 0 },
                loggedOn = day,
            )
        } else {
            LessonRepository.update(
                id = existing.id,
                text = text,
                projectId = projectId.takeIf { it != 0 },
                taskId = taskId.takeIf { it != 0 },
                loggedOn = day,
            )
        }
        return LessonResult(values = draft)
    }

    /** The pill: has this been talked about yet? */
    fun setDiscussed(id: Int, discussed: Boolean, employeeNumber: String, canManage: Boolean): LessonWrite =
        write(id, employeeNumber, canManage) { LessonRepository.setDiscussed(id, discussed) }

    /** Take the row off the database. The one write on this page that has no way back. */
    fun remove(id: Int, employeeNumber: String, canManage: Boolean): LessonWrite =
        write(id, employeeNumber, canManage) { LessonRepository.remove(id) }

    /**
     * One guard for all three single-field writes: find the row, ask whether this reader may write
     * it, and only then run the change.
     *
     * The row is read back rather than trusted from the form because a page that has been open since
     * the last edit can be pointing at a lesson that has since been deleted, or at one whose author
     * has changed.
     */
    private fun write(
        id: Int,
        employeeNumber: String,
        canManage: Boolean,
        change: () -> Unit,
    ): LessonWrite {
        val entry = LessonRepository.find(id) ?: return LessonWrite.MISSING
        if (!mayWrite(entry, employeeNumber, canManage)) return LessonWrite.DENIED
        change()
        return LessonWrite.SAVED
    }

    private const val ROW_GONE = "That lesson is no longer on the sheet."
    private const val NOT_YOURS = "Only management can change someone else's lesson."
    private const val BLANK_LESSON = "Write what was learned — the sheet has nothing to show without it."

    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun today(): String = LocalDate.now().format(ISO_DATE)

    private fun isIsoDate(value: String): Boolean = try {
        LocalDate.parse(value, ISO_DATE)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Which rows the sheet is cut down to, and in which order.
 *
 * The two pickers are the picture's own — a project and an employee — and the date column's glyph
 * is the third: newest first is how a sheet of lessons is read, and the flip is there for whoever
 * wants the oldest at the top. An undated carry-over sits at the end of **both**, because a row
 * with no date has no place in a date order and hiding it altogether would be worse.
 */
data class LessonFilter(
    val project: Int = 0,
    val employee: String = "",
    val oldestFirst: Boolean = false,
) {
    val isActive: Boolean get() = project != 0 || employee.isNotEmpty()

    fun matches(entry: LessonEntry): Boolean {
        if (project != 0 && entry.projectId != project) return false
        if (employee.isNotEmpty() && entry.employeeNumber != employee) return false
        return true
    }

    /** The order the sheet prints. */
    val order: Comparator<LessonEntry>
        get() = if (oldestFirst) {
            compareBy<LessonEntry> { it.loggedOn.isEmpty() }.thenBy { it.loggedOn }.thenBy { it.id }
        } else {
            compareByDescending<LessonEntry> { it.loggedOn }.thenByDescending { it.id }
        }

    /** The key the date column's control carries next: the order this one is *asking* for. */
    val sortKey: String get() = if (oldestFirst) "new" else "old"

    /**
     * This view as an address.
     *
     * [path] is the route that answers: the sheet's own address for a read, and one of the write
     * routes' redirect targets. A row write that lost the pickers would land the reader on the
     * whole sheet and move the row they just wrote somewhere else on it.
     */
    fun queryFor(path: String = "/app/lessons"): String = buildString {
        append(path)
        val keys = mutableListOf<String>()
        if (project != 0) keys += "project=$project"
        if (employee.isNotEmpty()) keys += "employee=${java.net.URLEncoder.encode(employee, "UTF-8")}"
        if (oldestFirst) keys += "sort=old"
        if (keys.isNotEmpty()) append('?').append(keys.joinToString("&"))
    }

    companion object {
        /** The sheet as a query states it. An unknown `sort` is simply the default order. */
        fun of(query: Map<String, String?>): LessonFilter = LessonFilter(
            project = query["project"]?.trim()?.toIntOrNull() ?: 0,
            employee = query["employee"].orEmpty().trim(),
            oldestFirst = query["sort"]?.trim() == "old",
        )
    }
}

/** The list the page prints, in the order it prints it. */
data class LessonBoard(val active: List<LessonEntry>) {
    val activeCount: Int get() = active.size
    val isEmpty: Boolean get() = active.isEmpty()
}

/** The raw popup, kept as the form sent it so a refusal can hand the words back. */
data class LessonDraft(
    val id: Int = 0,
    val text: String = "",
    val projectId: Int = 0,
    val taskId: Int = 0,
    val loggedOn: String = "",
) {
    companion object {
        fun of(form: Map<String, String?>): LessonDraft = LessonDraft(
            id = form["id"]?.trim()?.toIntOrNull() ?: 0,
            text = form["text"].orEmpty(),
            projectId = form["project"]?.trim()?.toIntOrNull() ?: 0,
            taskId = form["task"]?.trim()?.toIntOrNull() ?: 0,
            loggedOn = form["on"].orEmpty().trim(),
        )
    }
}

/** Which control a refusal belongs under. The message prints there, not at the top of the page. */
enum class LessonField { TEXT, PROJECT, TASK, DATE, ROW, PERMISSION }

/** `saved` on success; field-keyed `errors` and the typed values when the popup has to stay up. */
data class LessonResult(
    val saved: Boolean = true,
    val errors: Map<LessonField, String> = emptyMap(),
    val values: LessonDraft,
) {
    fun error(field: LessonField): String? = errors[field]

    companion object {
        fun refused(values: LessonDraft, vararg errors: Pair<LessonField, String>) =
            LessonResult(false, errors.toMap(), values)

        fun refused(values: LessonDraft, errors: Map<LessonField, String>) =
            LessonResult(false, errors, values)
    }
}

/** What a write to one existing row did: landed, or why it did not. */
enum class LessonWrite { SAVED, MISSING, DENIED }
