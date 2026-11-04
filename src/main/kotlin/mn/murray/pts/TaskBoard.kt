package mn.murray.pts
import mn.murray.pts.domain.*

import io.ktor.http.Parameters
import kotlinx.html.FORM
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.a
import kotlinx.html.form
import kotlinx.html.FlowContent
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.textArea
import mn.murray.pts.service.TaskDraft
import mn.murray.pts.service.TaskField
import mn.murray.pts.service.TaskSaveResult
import mn.murray.pts.service.TaskService

/**
 * Server-side state of the task popup, shared by the Assigned tasks board and a project page.
 *
 * The markup always renders, hidden; [isOpen] is the two cases the server sends it up open — a rejected
 * POST, where [values] is what was typed and each message in [errors] prints under its own field, and a
 * page asked for with `?edit=<card>` or `?new=1`, which is how the popup arrives already filled when
 * JavaScript is off or the reader bookmarked the URL. [taskId] is 0 while creating and the card's id
 * while editing.
 *
 * [canEditAll] is the popup's copy of the split in [mn.murray.pts.service.TaskService.updateAssigned]:
 * management fills every field, while the person the card is assigned to fills the three that book
 * their own time on it — start, due, priority — and reads the rest greyed. [task] is the card
 * being edited, and it is what the popup's steps and notes sections are printed from.
 *
 * **Since Stage 86 the popup also opens for readers who may write nothing on the card.** The card's board is
 * shared (a project's is the whole team's, Stage 56), and the user asked that a non-manager be able to click one
 * and *see* it, not change it — so the popup has a third state beside management's and the assignee's:
 * [viewOnly], where every field is greyed, the plan is drawn stopped, the log has no box under it and there is no
 * save button. The rights below are what tell the three apart; the service refuses the stranger's write whatever
 * this says.
 *
 * [canCreate] is a second, narrower right and it applies to a **create** only: the account a project
 * names as its manager is not management, so [canEditAll] is false for them, yet the card they are about
 * to write needs a title, an assignee and a column. It never widens an edit — an existing card keeps
 * Stage 23's split whatever this says — and [mn.murray.pts.service.TaskService.create] is what makes it
 * real, pinning that card to the project the page named. A backlog create is the one form that hands it to
 * **every** account (see [mn.murray.pts.service.TaskService.createBacklog]), and [selfPinned] is what that
 * looks like on the page: the card is the writer's own and it has not started.
 */
data class TaskForm(
    val taskId: Int = 0,
    val values: TaskDraft = TaskDraft(),
    val errors: Map<TaskField, String> = emptyMap(),
    val open: Boolean = false,
    val canEditAll: Boolean = true,
    val canCreate: Boolean = false,
    val task: TaskItem? = null,
    /**
     * The lanes of the project this popup was opened from (V7), empty for the general board and for the Other
     * tasks board. On a project page they are that project's own list — since Stage 100 the buckets Settings
     * listed for its category when it was created, plus whatever its manager has added since — so the picker
     * can offer them in the board's order; on any other page the picker falls back to
     * [DEFAULT_PROJECT_BUCKETS], the legacy trio those boards were filed under.
     */
    val lanes: List<ProjectBucket> = emptyList(),
    /**
     * Every project's own columns, keyed by project **name** — the popup's only list for a create that stands on
     * no particular project. Since **Stage 111** the general board's `+ New task` asks which project the card is
     * for inside the popup, so the Sub-category picker cannot be one fixed list: it is the chosen project's, and
     * the reader changes the project without leaving the form. Filled by [mn.murray.pts.service.TaskService.lanesByProjectName]
     * on that one form and left empty everywhere else, where [laneChoices] already knows the board — a project
     * page from its own [lanes], an edit from the row it was loaded from.
     */
    val lanesByProject: Map<String, List<String>> = emptyMap(),
    /**
     * The Other tasks board's columns (Stage 42), so a **backlog** popup can fill its Column picker. Since
     * Stage 48 management's edit popup carries them for a project card too, because the "where it lives"
     * picker can send that card onto that board and the column has to be chosen in the same form — so their
     * presence no longer says which board this is. [backlog] does.
     */
    val backlogColumns: List<Bucket> = emptyList(),
    /**
     * Whether this popup is a **create for the Other tasks board** — the one thing [isBacklog] cannot read off a
     * row, because there is no row yet. [backlogFormOf] sets it; an assigned create and every edit leave it alone.
     */
    val backlog: Boolean = false,
    /**
     * Whose board this popup is being drawn on. It decides one thing — [mayTick] — and nothing else, so an
     * empty value leaves a card's own assignee with a plan they may read but not tick, rather than letting
     * the popup guess who is looking. Set from the session by the route that loaded the card, never from a
     * form field: a reader cannot make somebody else's card tickable by posting a number.
     */
    val viewerEmpNo: String = "",
) {
    val isOpen: Boolean = open || errors.isNotEmpty()
    val isEdit: Boolean = taskId != 0

    /** Whether this popup's fields are writable: management always, a create by a project's manager too. */
    val mayWriteCard: Boolean = canEditAll || (!isEdit && canCreate)

    /**
     * Whether the reader is the person this card is assigned to.
     *
     * [viewerEmpNo] is set from the session, never from a field, so this is a fact about the reader rather than
     * something a post can claim. It is the one non-management right over a card: the work itself — the reader's
     * own day on it, its steps and its log.
     */
    val ownsCard: Boolean = task != null && task.assignedTo(viewerEmpNo)

    /**
     * Whether the three fields that describe the reader's own day on a card — **start date, due date, priority** —
     * may be written. Management fills them, so does a project's manager on a create; on an edit they belong to
     * management and to the card's own assignee
     * ([mn.murray.pts.service.TaskService.updateAssigned]'s narrower half).
     *
     * **Stage 86** is why this exists apart from [mayWriteCard]: since then anybody may open any card on a board
     * they can see, so a popup can be on screen for a reader who may write *nothing* in it. That reader is
     * [viewOnly], the fields above are greyed and there is no save button; the assignee's popup is the one the
     * widen did not touch.
     */
    val mayBookTime: Boolean = mayWriteCard || ownsCard

    /**
     * A card on screen that is neither management's nor the reader's own: every field is greyed and the popup
     * offers no save — the plan and the log are there to be read. It is the state the user asked for in Stage 86
     * — *a non-manager can click a card and see it, not change it* — and the answer to "is this popup a form or a
     * view", which is the one thing the modal branches on below.
     */
    val viewOnly: Boolean = isEdit && !mayBookTime

    /**
     * Whether this is a card of the Other tasks board — a backlog card belongs to a column of that board and
     * to no project. An edit knows from the row it was loaded from; a create has no row yet and knows from
     * [backlog], which is what its opener was standing on.
     */
    val isBacklog: Boolean = if (isEdit) task?.isBacklog == true else backlog

    /**
     * Whether this popup may be asked **which board the card lives on** (Stage 48).
     *
     * One control, two directions: management can hand a project's card to the Other tasks board and take one
     * back, which is the only way a card that is already written can change world — the boards' own `+ Add
     * task` lines create on the page they sit on, and there is nothing to move in a card that does not exist
     * yet. The assignee of a card is not offered it: the title above it is management's for the same reason,
     * that a card's place in the work is decided by the team, not by the person it happens to be on.
     */
    val mayRefile: Boolean = isEdit && canEditAll

    /**
     * Which board this form is answering as, which is what its pickers are drawn from.
     *
     * A popup that offers the choice answers with the field it prints, and one that does not answers with the
     * row — so a form that never shows a "where it lives" picker still files its card on the board the card is
     * already on, and a hand-built POST naming the other one is refused by the service rather than obeyed here.
     */
    val boardAnswer: String
        get() = values.board
            ?: if (isBacklog) BOARD_OTHER else BOARD_PROJECT

    /** Whether this form's Column picker is the one to draw, and its Project and Lane pickers are not. */
    val showsBacklog: Boolean get() = boardAnswer == BOARD_OTHER

    /**
     * The sub-categories this form's Lane picker lists (Stage 48).
     *
     * On a project page, that project's own lanes, in the order its board prints them: since Stage 100 whatever
     * Settings listed for its category when it was created, then whatever its manager added. Anywhere else, the
     * legacy [DEFAULT_PROJECT_BUCKETS] trio — the names those boards were filed under, which is why the general
     * board's popup can ask the question at all without knowing a project's lane ids. Posted by name for the
     * same reason, and resolved against the project the card lands on, so a board that has retitled or removed
     * one of those names simply stops accepting it (Stage 75).
     */
    val laneChoices: List<String> get() =
        (if (lanes.isNotEmpty()) lanes.map { it.name } else DEFAULT_PROJECT_BUCKETS).distinct()

    /**
     * Whether the Column picker writes. **Management's alone since Stage 49**, on both boards.
     *
     * It used to be one field wider than [mayWriteCard] on purpose: the person a backlog card belongs to could
     * move it between columns here, on the reasoning that this was the same right
     * [mn.murray.pts.service.TaskService.moveToLane] gave them by dragging on a project board and that the
     * backlog simply had nothing to drag. The user retired the whole idea — *management moves the cards* — so
     * which column a card sits in is now decided by the same accounts that decide which lane, which is exactly
     * [mayWriteCard]'s line moved up to the board-level fields. [mn.murray.pts.service.TaskService.updateAssigned]
     * keeps the two in step: a non-manager's save reads the row's column back, whatever this says.
     *
     * [mayRefile] counts too, and it is the half of the form the reader cannot see yet: a popup that ships both
     * boards leaves this one hidden until the "where it lives" picker is moved to *Other tasks*, and a disabled
     * select posts nothing — so a greyed field here would leave management able to send a card to the backlog
     * but never able to say **which column** it lands in, which is the one thing a create there has to answer.
     */
    val maySetColumn: Boolean = (showsBacklog || mayRefile) && canEditAll

    /**
     * A new backlog card whose column is decided by the place it was opened from rather than by the picker —
     * the Column field's own [selfPinned]. Every account may write a card onto the Other tasks board
     * ([mn.murray.pts.service.TaskService.createBacklog]), and each column's `+ Add task` line names the column
     * it sits under, so the writer has already answered this field by choosing where to stand. Making them
     * choose it a second time in a popup they may not write is what Stage 49 took out; the line at the field
     * says which column the card lands under instead.
     *
     * A pinned select posts nothing, which is why the markup carries the answer as a hidden field as well:
     * [mn.murray.pts.service.TaskService.createBacklog] still requires a card to name its column.
     */
    val columnPinned: Boolean = isBacklog && !isEdit && !canEditAll

    /**
     * A new backlog card written by an account that is not management: it lands **as the writer's own** and
     * **Not started** — [mn.murray.pts.service.TaskService.createBacklog] rebuilds the draft that way before
     * validating it. Like [projectPinned] on a project's create, that is a fact the pickers have to show
     * rather than a choice the reader can be wrong in.
     */
    val selfPinned: Boolean = isBacklog && !isEdit && !canEditAll && canCreate

    /**
     * Whether this popup may offer a step's tick box — [mn.murray.pts.service.TaskService.toggleChecklistItem]'s
     * own rule read as presentation: management works any card, and the person a card is assigned to works
     * their own. An unassigned backlog card belongs to nobody, so only management is offered the box on it.
     *
     * Taking a step off is **not** in here: that stays [canEditAll] ([mayAddStep] says which half of the plan
     * writing moved). A reader who may only read the card — [viewOnly] — still sees the plan, drawn stopped
     * ([stepRowLocked]), because a plan is part of what the card is.
     */
    val mayTick: Boolean = canEditAll || ownsCard

    /**
     * Whether this popup may offer a *New step…* line — [mn.murray.pts.service.TaskService.addChecklistItem]'s
     * rule read as presentation. It is [mayTick]'s line: management plans any card, and **since Stage 89** the
     * person the card is assigned to plans their own. Taking a step off stays [canEditAll]'s, so an owner's row
     * carries no × beside the box they may work — knowing the work lets them add to the plan, but striking a
     * line off it is management's decision about the plan.
     */
    val mayAddStep: Boolean = canEditAll || ownsCard

    /**
     * Whether this popup may carry the *Add note* box — [mn.murray.pts.service.TaskService.addNote]'s rule, which
     * is [mayTick]'s: management writes on any card, and so does the person doing the work. Taking an entry off
     * stays [canEditAll]'s. Everybody else reads the log without a box under it (Stage 86).
     */
    val mayNote: Boolean = mayTick

    /** A new card whose project is decided by the page it was opened from rather than by the picker. */
    val projectPinned: Boolean = !canEditAll && !isEdit && canCreate

    fun error(field: TaskField): String? = errors[field]
}

/**
 * The `+ New task` opener, rendered in the page head opposite the title exactly like
 * `+ New project`.
 *
 * It is a GET form rather than a button with a script behind it: `?new=1` on this page's own route means
 * "the same page with a blank popup up", which [app.js] fetches and swaps into `#taskModal` so the board
 * never restarts, and which the browser loads on its own when scripts are off. Nothing here has to
 * remember what a blank form looks like — the server renders it, with this page's project already
 * selected when the opener sits on a project page.
 */
fun DIV.newTaskButton(fixedProject: Project? = null) {
    form(
        action = fixedProject?.let { "/app/projects/${it.id}" } ?: "/app/assigned-tasks",
        method = FormMethod.get,
        classes = "opener-form",
    ) {
        attributes["data-live-target"] = "#taskModal"
        input(type = InputType.hidden, name = "new") { value = "1" }
        button(type = ButtonType.submit, classes = "submit btn-sm new-task-btn") { +"+ New task" }
    }
}

/**
 * The `+ Add task` line at the foot of one lane column (V7 board).
 *
 * It is [newTaskButton]'s own mechanism with one extra field: a GET form to this project's page carrying
 * `new=1` and `into=<lane id>`, so the server renders the blank popup with that lane already picked and
 * [app.js] swaps it into `#taskModal` over the board the reader is looking at. Scripts off, the same click
 * loads the same page with the same popup up — which is why this is a form and not an `href`.
 *
 * `into` rather than `lane` because `?lane=` on this same URL already means something else: the code a
 * management request to `POST /app/projects/{id}/buckets` was refused with (Stage 30/31, and since Stage 35
 * only reachable that way — no control on this page posts to it). Two meanings on one key would make a
 * refusal and an aimed create indistinguishable.
 *
 * Drawn per column for exactly the reader the page head's opener is drawn for — see
 * [mn.murray.pts.service.TaskService.mayAddTasks] — and not on **Unsorted**, which is where cards land when
 * nobody placed them rather than a place one is placed.
 */
private fun DIV.laneAddTaskForm(projectId: Int, lane: ProjectBucket) {
    form(action = "/app/projects/$projectId", method = FormMethod.get, classes = "kcol-add") {
        attributes["data-live-target"] = "#taskModal"
        input(type = InputType.hidden, name = "new") { value = "1" }
        input(type = InputType.hidden, name = "into") { value = "${lane.id}" }
        button(type = ButtonType.submit, classes = "kcol-add-btn") { +"+ Add task" }
    }
}

/**
 * Where an edit or a delete returns to: the project page it was opened from, the Other tasks board, or the
 * assigned board.
 * The value is server-rendered into a hidden field, never taken from user input, so it cannot
 * be used to send someone off-site.
 *
 * [edit] keeps the popup up on the way back: a step added or a note written from inside it is a change
 * to that card, and the reader is looking at the card's own form when they press the button. With no id
 * — or after a save, which closes the popup — this is the plain page.
 *
 * *(**Stage 40** added a fourth thing here — `showAllBuckets`, which carried the backlog's wide view across
 * the same trip so a card saved from it did not land the reader on the narrow board. **Stage 97** deleted it
 * with the wide view: the board's Bucket picker is the only view control left, and its filter already rides
 * this token in `show`.)*
 */
fun taskRedirect(back: String, projectId: Int, edit: Int = 0): String {
    val page = when {
        back == BACK_PROJECT && projectId > 0 -> "/app/projects/$projectId"
        isOtherBack(back) -> "/app/other-tasks"
        else -> "/app/assigned-tasks"
    }
    // The Assigned page's two filters ride the same token `back` does, so opening a card, saving it, adding a
    // step, writing a note or answering a refusal all land the reader on the board they were reading rather
    // than on the unfiltered one: its Other-tasks bucket (Stage 88) and, since Stage 107, the project the
    // projects board above it was narrowed to. Only that page has those filters; a project page and the shared
    // backlog are never sent a `bucket` or a `project`.
    val assigned = back == BACK_ASSIGNED || back.startsWith("$BACK_ASSIGNED:")
    val bucket = if (assigned) bucketOfBack(back) else 0
    val project = if (assigned) projectOfBack(back) else 0
    // Since Stage 93 the shared backlog's own Bucket picker rides the same token in `show` — the key its filter
    // form posts, because `?bucket=` on that URL already names a column's popup.
    val picked = bucketOfOtherBack(back)
    val query = listOfNotNull(
        if (edit > 0) "edit=$edit" else null,
        if (bucket > 0) "bucket=$bucket" else null,
        if (project > 0) "project=$project" else null,
        if (picked > 0) "show=$picked" else null,
    ).joinToString("&")
    return if (query.isEmpty()) page else "$page?$query"
}

const val BACK_ASSIGNED = "assigned"
const val BACK_PROJECT = "project"

/**
 * The `back` token for the Assigned page, carrying which Other-tasks bucket its reader was filtering by
 * (**Stage 88**): plain `assigned` when they are reading every bucket, `assigned:<bucket id>` when one is
 * chosen.
 *
 * The filter is a page view, not a stored setting — the URL is the state — so it has to survive every trip a
 * card makes out of that page: the popup opener, the save, a step tick, a note, a delete and a refusal all post
 * `back` and are answered through [taskRedirect], which reads the id back out of the token. Anything that
 * cannot be read as a bucket id is the unfiltered page, never an error.
 *
 * Since **Stage 107** the projects board above the Other-task board has a picker of its own, so the same token
 * carries both filters — see [backForFilters]; this is that function with the project left whole, kept because
 * a bucket-only board is still the common shape and reads better as `assigned:3` than `assigned:3:0`.
 */
fun backForBucket(bucketId: Int): String = backForFilters(bucketId, 0)

/**
 * Both Assigned-page filters in one `back` token: plain `assigned` when neither is on, `assigned:<bucket>`
 * when only the Other-tasks bucket is chosen, and `assigned:<bucket>:<project>` when the projects board is
 * narrowed too — a `0` in either slot meaning *every* bucket or project (**Stage 107**).
 *
 * One token rather than two fields because every card action already carries exactly one `back` and the page
 * it answers on is built from it: [taskRedirect] reads both ids out and rebuilds the same URL, so a card
 * opened, saved, stepped, noted, deleted or refused from a doubly-filtered board comes back to that board.
 * The ids are written from the board's own data, never from a post, so a hand-built token can only name a
 * filter a URL could name anyway.
 */
fun backForFilters(bucketId: Int, projectId: Int): String = when {
    bucketId <= 0 && projectId <= 0 -> BACK_ASSIGNED
    projectId <= 0 -> "$BACK_ASSIGNED:$bucketId"
    else -> "$BACK_ASSIGNED:${bucketId.coerceAtLeast(0)}:$projectId"
}

/** The ids inside an [backForFilters] token, in order; empty for `assigned` and for anything unrecognised. */
private fun assignedParts(back: String): List<String> =
    if (back.startsWith("$BACK_ASSIGNED:")) back.removePrefix("$BACK_ASSIGNED:").split(':') else emptyList()

/** Which Other-tasks bucket a [backForFilters] token names; 0 for `assigned` and for anything unrecognised. */
fun bucketOfBack(back: String): Int = assignedParts(back).getOrNull(0)?.trim()?.toIntOrNull() ?: 0

/**
 * Which project a [backForFilters] token names (**Stage 107**); 0 for a token without that slot, for `assigned`
 * and for anything unrecognised — a bucket-only `assigned:3` is the whole projects board, never a guess.
 */
fun projectOfBack(back: String): Int = assignedParts(back).getOrNull(1)?.trim()?.toIntOrNull() ?: 0

/**
 * The `back` token for the **shared** Other-tasks board, carrying the bucket its own picker is showing
 * (**Stage 93**): plain `other` when every bucket is on the board, `other:<bucket id>` when one is chosen.
 *
 * It is [backForBucket]'s twin on the other page, and for the same reason: the filter is the URL's, not a stored
 * setting, so a card opened, saved, stepped, noted, deleted or refused from a narrowed board has to come back to
 * that board. [bucketOfOtherBack] reads the id back out; anything that is not a number is the full board, never
 * an error. The Assigned page keeps its own `assigned:<id>` token — the two pages may filter by a bucket at the
 * same time, and one token cannot name two boards.
 */
fun backForOtherBucket(bucketId: Int): String = if (bucketId > 0) "$BACK_OTHER:$bucketId" else BACK_OTHER

/** Which bucket a [backForOtherBucket] token names; 0 for plain `other` and for anything unrecognised. */
fun bucketOfOtherBack(back: String): Int =
    if (back.startsWith("$BACK_OTHER:")) back.removePrefix("$BACK_OTHER:").trim().toIntOrNull() ?: 0 else 0

/** Whether a `back` token names the shared Other-tasks board, filtered or not (Stage 93). */
fun isOtherBack(back: String): Boolean = back == BACK_OTHER || back.startsWith("$BACK_OTHER:")

/**
 * The Other tasks board (Stage 47). Its cards are the same shape as an assigned card's and are written, edited
 * and moved through the same routes, so what this key carries is not a different set of rights but the page to
 * hand the reader back to — and, on a backlog card, the column view to hand it back *in*.
 */
const val BACK_OTHER = "other"

/**
 * What the Project picker shows for the one card it cannot ask a choice of: a row that arrived before every
 * assigned card had to name a project, and still carries none.
 *
 * It is not the option that used to sit in this picker (Stage 48 took *— No project —* out, along with the
 * save that accepted it), and it is deliberately not selectable as an answer: it is what the field says when the
 * database has nothing to say, and the route that receives it refuses the write and tells the reader who can
 * choose a world for the card.
 */
const val NO_PROJECT_YET = "— No project yet —"

/**
 * The Project picker's own blank on the **general board's create** (Stage 111): the page names no project, so
 * the card has none until the reader says which one it is. Unlike [NO_PROJECT_YET] this is not a fact about a
 * row — it is a question, and it is the answer the route refuses a create with
 * (*"Choose which project this card is on."*) when the field is left as it came.
 */
const val CHOOSE_A_PROJECT = "— Choose a project —"

/**
 * Sub-category picker, no project chosen yet (Stage 111). The columns belong to a board, and this popup has not
 * named one, so the field says what it is waiting for instead of offering a list from a stranger's project.
 */
const val CHOOSE_A_PROJECT_FIRST = "— Choose a project first —"

/**
 * Sub-category picker for a chosen project whose board has no columns (Stage 111). Every board is created with
 * at least one (Stage 100), so this is a board somebody has emptied since — said plainly, rather than as an
 * empty drop-down that looks like a broken field.
 */
const val NO_COLUMN_ON_BOARD = "— This project has no column yet —"

/**
 * Sub-category picker for a refused create (Stage 111): the project above it does have columns, and the answer
 * this form carried is not one of them — the board was renamed, or the card arrived here from a picker that has
 * since changed. The field's own message says what to do; this says why the picker cannot show what was posted,
 * which is the one thing [NO_COLUMN_ON_BOARD] must not be made to mean when the board plainly has columns.
 */
const val NOT_A_COLUMN_OF_THIS_PROJECT = "— Not a column of this project —"

/**
 * One project's columns as a JSON array, for the create popup's `data-lanes` attribute — the list
 * `task-modal.js` refills the Sub-category picker from when the reader changes the project (Stage 111).
 *
 * The names are free text typed in Settings, so they are escaped for JSON rather than trusted: quote,
 * backslash, the control characters. `<` and `&` go out as unicode escapes too, which keeps the attribute free
 * of anything a page parser might read as markup or as an entity it has to resolve — a `&` that arrives as
 * `&amp;` in the parsed attribute is a broken JSON escape, and the reader would get an empty picker for a board
 * that has columns.
 */
fun laneOptionsJson(names: List<String>): String =
    names.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"" + laneNameJson(it) + "\"" }

/** `\\uXXXX` for a character that has no JSON escape of its own, in lower-case hex. */
private fun StringBuilder.appendJsonEscape(code: Int) {
    append(BACKSLASH).append('u').append(code.toString(16).padStart(4, '0'))
}

private const val BACKSLASH = '\\'

private fun laneNameJson(name: String): String = buildString {
    for (c in name) {
        when (c.code) {
            0x22 -> append(BACKSLASH).append('"')
            0x5C -> append(BACKSLASH).append(BACKSLASH)
            0x08 -> append(BACKSLASH).append('b')
            0x09 -> append(BACKSLASH).append('t')
            0x0A -> append(BACKSLASH).append('n')
            0x0C -> append(BACKSLASH).append('f')
            0x0D -> append(BACKSLASH).append('r')
            // `&` and `<` are the two that a page can change on the way to the script, so they never travel raw.
            0x26 -> appendJsonEscape(0x26)
            0x3C -> appendJsonEscape(0x3C)
            else -> if (c.code < 0x20) appendJsonEscape(c.code) else append(c)
        }
    }
}

/**
 * Which board the popup says the card lives on (Stage 48): [BOARD_PROJECT] or [BOARD_OTHER], or null when the
 * form carries no such field.
 *
 * The three answers matter separately, exactly like the lane field below. The picker is drawn on an edit by
 * management only, so a create, an owner's form and an old bookmark all post nothing, and nothing means "the
 * board the row is already on" — never a move to one of them. A value that is neither word is the same as
 * absent rather than a third kind of board.
 */
fun boardOf(form: Parameters): String? = form["board"]?.trim()?.takeIf { it == BOARD_PROJECT || it == BOARD_OTHER }

/** The popup form, read exactly as posted so a rejected one can be printed back verbatim. */
fun taskDraftOf(form: Parameters): TaskDraft = TaskDraft(
    title = form["title"].orEmpty().trim(),
    projectName = form["project"].orEmpty().trim(),
    assigneeEmpNo = form["assignee"].orEmpty().trim(),
    // The second person on a project card (Stage 122). A backlog form never prints this picker and posts
    // nothing, which is the blank the service reads as *one owner* on the board that has one by design.
    assignee2EmpNo = form["assignee2"].orEmpty().trim(),
    started = form["started"].orEmpty().trim(),
    due = form["due"].orEmpty().trim(),
    priority = form["priority"].orEmpty().trim().ifBlank { "Medium" },
    status = form["status"].orEmpty().trim().ifBlank { "Not started" },
    // Absent is not blank: the Lane picker is greyed for the one reader who may not refile a card, and a
    // disabled select posts nothing at all, so the card it updates keeps the lane it was in (V7). Since
    // Stage 48 the field is the lane's **name**, which is what lets the general board's popup offer the three
    // every board opens with without knowing this project's lane ids.
    projectLane = form["lane"]?.trim(),
    // The same for the backlog's column (Stage 42): only a backlog popup renders that picker, so a POST from
    // either project board carries no `column` at all and the card is not being filed anywhere.
    columnId = form["column"]?.trim()?.toIntOrNull(),
    // Which board the form was left on. Null on every form that draws no such picker — see [boardOf].
    board = boardOf(form),
)

/**
 * Which page a posted card action came from; anything unrecognised is the general board.
 *
 * The general board's answer is normalised through [backForFilters], so a token that carries one or both page
 * filters (`assigned:3`, Stage 88; `assigned:0:5` and `assigned:3:5`, Stage 107) survives the round trip while a
 * hand-built or unrecognised one lands on the unfiltered board rather than being echoed back. The shared
 * backlog's `other:<id>` (Stage 93) is normalised the same way through [backForOtherBucket].
 */
fun backOf(form: Parameters): String {
    val raw = form["back"].orEmpty().trim()
    return when {
        raw == BACK_PROJECT -> BACK_PROJECT
        raw == BACK_OTHER -> BACK_OTHER
        raw.startsWith("$BACK_OTHER:") -> backForOtherBucket(bucketOfOtherBack(raw))
        else -> backForFilters(bucketOfBack(raw), projectOfBack(raw))
    }
}

/** The project page to go back to, server-rendered into the form rather than typed by anyone. */
fun projectIdOf(form: Parameters): Int =
    form["projectId"].orEmpty().trim().toIntOrNull() ?: 0

/**
 * Which card a block-form inside the popup belongs to, so its redirect can bring the popup back up on
 * the same card. Server-rendered like `back` and `projectId`; a form that is not in the popup carries 0,
 * and the redirect lands on the plain page.
 */
fun editOf(form: Parameters): Int = form["edit"].orEmpty().trim().toIntOrNull() ?: 0

/**
 * Which of a project board's columns are showing their completed cards (Stage 80).
 *
 * A column's `▸ Completed N` line is a GET form onto the project page, so the open set **is** the URL: one
 * repeated `showdone=<lane id>` for every column the reader has opened, which is how one button can fold one
 * column up without folding the others down. The whole set is resent with each press for the same reason the
 * bucket popup carries the board's other lanes as hidden fields — a form only knows what it posts. A value that
 * is not a number is dropped rather than guessed at, and no key at all means nothing is open, which is the
 * board's shape before anybody presses the line and what a bookmarked or hand-built link gets.
 */
fun openDoneLanesOf(params: Parameters): Set<Int> =
    params.getAll("showdone").orEmpty().mapNotNull { it.trim().toIntOrNull() }.toSet()

/**
 * The task board.
 *
 * Two ways to cut it, and one list of cards. On the **Assigned tasks** page [lanes] is empty, so the board is
 * the five status columns it has always been — that reader is asking "what is on me now" across every project,
 * and Not started → Completed is the answer. When the caller passes a project's lanes (V7: the three every
 * project is created with, plus whatever its manager adds), the same cards are grouped by which phase of
 * the project they belong to instead. A card has both at once — `status` and `project_bucket_id` are separate
 * columns — so no data moves when the board changes shape, and a lane board prints no status word anywhere: the
 * column a card sits in on that page says where the work is, not how far it has got.
 *
 * Which of the two a **project's** page asks for stopped being the card list's decision at **Stage 75**: the
 * page passes its buckets and gets buckets, populated or not. The user's word for the project page was *the
 * bucket structure is shown all in kanban*, and the two shapes do not disagree about anything — a card's stage
 * is on the card, and moves through its Done button and popup rather than through a column — so the columns can
 * answer the one question this page asks, which is what the work is filed under, all the time.
 *
 * A project with no lanes at all falls back to the status board, because a board with zero columns is worse
 * than a familiar one.
 *
 * [projectId] travels with a card action when the board belongs to one project, so saving an
 * edit or deleting a card returns to that project page instead of the general board.
 *
 * [canAdd] is the reader's right to write a card here — management on either board, and on a project page
 * also the account that project names as its manager. When the board this reader can see holds nothing and
 * they may write one, the empty board says so and carries the opener itself: the page head's `+ New task`
 * is the same form, and a five-column frame of "Nothing here." is not a thing anybody reads as an
 * invitation. [fixedProject] is what makes a new card land on this project.
 *
 * [canMoveCards] is the right to put one of this project's cards into a different lane of it, and since
 * Stage 49 it is the project page's one difference between management and everybody else: the cards printed
 * underneath are the same ones either way, and a plain account that has work on the project still sees it, in
 * the lane it is filed in. The caller passes the same value it passes for [canAdd], because the application
 * decided that writing a card onto a project and filing one inside it are the same kind of act — see
 * [mn.murray.pts.service.TaskService.mayAddTasks] for the one rule both come from. It is a separate parameter
 * rather than the same one because a drag moves what is already there and an add line does not, and the two
 * affordances could be widened apart later without either service rule changing.
 *
 * [drawOpener] is false only when this board is not the one that should carry the page's `#taskEditOpener`. The
 * opener is found by id, so a page that stacks two boards — the Assigned tasks page since **Stage 55**, which
 * prints the reader's own project cards and their own Other-task cards as two status boards under a single card
 * popup — draws it on exactly one of them. Every other caller leaves it true and gets the opener as before.
 *
 * Nothing on either board names or removes a lane (Stage 35) — with one exception, since **Stage 74** and for
 * every project board now that Stage 75 made the lane board the project board: a column the reader may retitle or
 * take off carries a `⋯`, and one more bucket is offered as a dashed box at the end of the run. Both hang on
 * [canManageLanes], which is the picker's own right passed through (see
 * [mn.murray.pts.service.TaskService.mayEditLanes]), and both are drawn whether or not the columns hold cards.
 * What the board *always* offers, populated or not, is where the work goes: the popup's Lane field and a drag are
 * the only ways a card moves between columns.
 */
fun DIV.taskKanban(
    tasks: List<TaskItem>,
    session: UserSession,
    canManage: Boolean,
    back: String,
    projectId: Int = 0,
    canAdd: Boolean = false,
    fixedProject: Project? = null,
    lanes: List<ProjectBucket> = emptyList(),
    canMoveCards: Boolean = false,
    drawOpener: Boolean = true,
    canManageLanes: Boolean = false,
    doneOpen: Set<Int> = emptySet(),
) {
    val laneBoard = lanes.isNotEmpty()
    if (laneBoard) {
        laneKanban(tasks, session, canManage, back, projectId, lanes, canAdd, canMoveCards, canManageLanes, doneOpen)
    } else statusKanban(tasks, session, canManage, back, projectId)
    // The empty-board invitation is drawn only where there is no board to invite anybody into. On a project that
    // has no cards yet, the bucket columns *are* the invitation — each one carries its own `+ Add task` line —
    // so a "No tasks on this project yet." panel and a `+ New task` button stacked under them would be a second
    // answer to the question the columns already answer (Stage 74).
    if (tasks.isEmpty() && canAdd && lanes.isEmpty()) {
        div("board-empty") {
            p("board-empty-line") {
                +(if (fixedProject == null) "Nothing has been assigned yet." else "No tasks on this project yet.")
            }
            p("board-empty-sub") {
                +(
                    if (fixedProject == null) "The board is the plan — write the first card and it appears under its column."
                    else "The board is the plan — write the first card and it appears under its lane."
                    )
            }
            newTaskButton(fixedProject)
        }
    }
    // One opener for the whole board. Which card was clicked is only known in the browser, so the card's
    // id has to be a field [task-modal.js] can fill in rather than an href written at render time. It
    // appears only when something on this board can be clicked: an empty column set has no card to open. Since
    // **Stage 86** every card on the board is a click target for every reader — the popup inside is what
    // narrows to a read-only view — so the test is the board holding a card, not the reader owning one.
    //
    // [drawOpener] is switched off by the caller when a second board on the same page already carries the
    // page's one opener — `task-modal.js` finds it by id, so two boards must not each emit a `#taskEditOpener`
    // (Stage 55's Assigned page, which prints a project board and the reader's own Other-tasks board under it).
    if (drawOpener && tasks.isNotEmpty()) {
        taskEditOpener(back, projectId)
    }
}

/** The Assigned tasks board: one column per [mn.murray.pts.domain.TASK_STATUSES], all five always drawn. */
private fun DIV.statusKanban(
    tasks: List<TaskItem>,
    session: UserSession,
    canManage: Boolean,
    back: String,
    projectId: Int,
) {
    div("kanban") {
        TASK_STATUSES.forEach { status ->
            val inCol = tasks.filter { it.status == status }
            div("kcol") {
                attributes["data-status"] = status
                div("kcol-head") {
                    span("dot ${statusDot(status)}") { }
                    span("kcol-title") { +status }
                    span("kcol-count") { +"${inCol.size}" }
                }
                if (inCol.isEmpty()) {
                    p("kcol-empty") { +"Nothing here." }
                }
                inCol.forEach { taskCard(it, session, canManage, laneBoard = false) }
            }
        }
    }
}

/**
 * A project's board: one column per lane (V7), in the order the project's lanes were written.
 *
 * A project's board is its lanes and nothing else (Stage 37): the picture the page is built to shows three
 * columns and no bucket beside them, so **Unsorted** is not drawn on an empty board. It comes back only while the
 * board actually holds a card that has no lane — one created from the general board, one moved here from another
 * project, one whose lane was taken off by a request that came from somewhere other than this page — because a
 * card that exists must be visible somewhere, and a column nobody can see is a card nobody can find. That is the
 * whole reason it is still drawn at all, and it is why nothing is lost by the column not being there the rest of
 * the time.
 *
 * Drawing it conditionally is safe now because the columns stopped being sized by how many of them there are
 * (see app.css: `.kanban-lanes` is a run of fixed 288px tracks, packed from the left the way the picture packs
 * them). A fourth column appearing used to take 134px off each of the other three and every card in them with
 * them; now it takes nothing, so the board's geometry does not depend on the count of columns at all. Each
 * column carries `data-lane` rather than `data-status`, which is how [kanban.js] knows a drop here refiles the
 * card instead of advancing it. **Unsorted carries `data-unfiled` as well and is not a drop target** (Stage 48):
 * a card on a project has to be filed in a sub-category, so the column that holds the ones that are not is a
 * place cards leave from, never one they arrive at.
 *
 * Every **lane** prints its "Nothing here." line, *after* its cards, and app.css hides it whenever one precedes
 * it (`.kcard + .kcol-empty`). A lane that has just received its first card therefore loses the message in the
 * same repaint as the card arriving, with nothing in the script to keep it in step — which is the whole reason
 * the line is not written conditionally here the way the status board still writes it. Unsorted
 * needs no line: it is only drawn while it has a card in it, and [kanban.js] takes the column away when the
 * last one leaves. Since **Stage 80** a lane whose work is partly finished also prints that part as its own block
 * just before the line, and app.css hides the line for that too (`.kcol-done + .kcol-empty`) — a column holding
 * only completed cards is not a column with nothing in it.
 *
 * A lane head prints its colour, its name and its count (Stage 74), and — for the reader who may re-shape this
 * board — a `⋯` that opens that one column's popup. The colour is the bucket's own dot, from the six the
 * stylesheet defines: since **Stage 80** a column wears the hue somebody picked for it on its own popup, and one
 * nobody picked still wears the shade of its position in the run — the same cycle the Settings catalogue prints
 * its bucket list with, so **QAQC** is the same shade on both pages until a manager colours one board
 * differently. There is
 * no box under these columns that names a lane on its own and no **×** on a head that takes one off: a new bucket
 * is asked for in the popup the dashed box at the end of the run opens, which is where a refusal has somewhere to
 * print its reason, and the picker under the board remains the one place the whole set is ticked. Where the work
 * goes is still the board's own offer: [canAdd] — the same reader whose page head carries `+ New task` — gets a
 * `+ Add task` line at the foot of every **lane**, which is that same opener aimed at the column
 * (`into=<lane id>`, see [laneAddTaskForm]). Unsorted is left without one on purpose: it is where unplaced cards
 * wait, not a place somebody sets out to put one.
 *
 * **Dragging is [canMoveCards]'s alone** (Stage 49). The cards printed here are the same cards either way —
 * a plain account whose work is on this project sees it in the lane it is filed in — and what management and
 * the project's own manager have over them is the right to move one from a lane to another lane of this same
 * project. The flag reaches [taskCard] as `mayDrag`, which is the only thing that puts `draggable="true"` on
 * a card, and that attribute is the only thing [kanban.js] binds a drag source to: a card this reader cannot
 * move does not look draggable, does not start a drag, and needs no script of its own to say so. The card still
 * **opens** for its owner — the popup, the tab stop and the Done button are a different right from the drag,
 * and Stage 49 took none of them away.
 */
private fun DIV.laneKanban(
    tasks: List<TaskItem>,
    session: UserSession,
    canManage: Boolean,
    back: String,
    projectId: Int,
    lanes: List<ProjectBucket>,
    canAdd: Boolean = false,
    canMoveCards: Boolean = false,
    canManageLanes: Boolean = false,
    doneOpen: Set<Int> = emptySet(),
) {
    val unfiled = tasks.filter { it.projectBucketId == 0 }
    val mayWriteLanes = canManageLanes && projectId > 0
    div("kanban kanban-lanes") {
        lanes.forEachIndexed { index, lane ->
            val inCol = tasks.filter { it.projectBucketId == lane.id }
            val finished = inCol.filter { it.status == STATUS_COMPLETED }
            div("kcol") {
                attributes["data-lane"] = "${lane.id}"
                div("kcol-head") {
                    span(bucketDotClass(index, lane.colourSlot)) { }
                    span("kcol-title") { +lane.name }
                    span("kcol-count") { +"${inCol.size}" }
                    // Every column of a board this reader may re-shape carries its menu, the three it opened with
                    // included: since Stage 75 those are ordinary lanes, and [TaskService.renameLane] and
                    // [removeLane] refuse them no more than they refuse a bucket somebody added — the only rule
                    // left is that a board keeps one lane and a lane keeps its cards until they are moved. A `⋯`
                    // whose every button answers *no* is the dead control Stage 51 made a rule about, and that is
                    // now a *right* question, not a name question: [mayWriteLanes] is the answer to it.
                    if (mayWriteLanes) laneMenuForm(projectId, lane.id)
                }
                inCol.filter { it.status != STATUS_COMPLETED }
                    .forEach { taskCard(it, session, canManage, laneBoard = true, mayDrag = canMoveCards) }
                // **Stage 80's collapse.** A column's finished cards stay on the column and stop taking the room:
                // the live ones print first, then a `▸ Completed N` line, then the finished ones inside a block
                // the page draws `hidden` unless this column is named by `?showdone=`. It is the board's own
                // question rather than a script's: the toggle is a GET form onto this page carrying every column
                // that is open *after* the click, `app.js` swaps `#projectBoard` out of the answer, and a reader
                // with no JavaScript gets the same opened-and-closed board from the same URL. The count in the
                // head is untouched (it has always counted every card in the column, and [kanban.js] recomputes
                // it the same way after a drag), and a column with nothing finished in it prints no line at all.
                if (finished.isNotEmpty()) {
                    laneDoneToggle(projectId, lanes, lane.id, finished.size, doneOpen)
                    div("kcol-done") {
                        if (lane.id !in doneOpen) attributes["hidden"] = "true"
                        finished.forEach { taskCard(it, session, canManage, laneBoard = true, mayDrag = canMoveCards) }
                    }
                }
                p("kcol-empty") { +"Nothing here." }
                if (canAdd && projectId > 0) laneAddTaskForm(projectId, lane)
            }
        }
        if (unfiled.isNotEmpty()) {
            div("kcol") {
                attributes["data-lane"] = "0"
                // Cards leave this column by drag and never arrive by it. Since Stage 48 a card on a project has
                // to be filed in one of its sub-categories, so dropping one here would be a move the service
                // refuses — and a board that let the card go in and then told the reader nothing would have
                // moved it for nothing. The flag is what [kanban.js] reads to keep the column out of the drop
                // targets, so the drag never looks like it worked.
                attributes["data-unfiled"] = "true"
                div("kcol-head") {
                    span("kcol-title") { +UNSORTED_LANES }
                    span("kcol-count") { +"${unfiled.size}" }
                }
                // The same mover as the lanes' own cards (Stage 49), and this is the column that needs it: a card
                // here has no lane, so a drag out is the only way the board can file it without a popup.
                unfiled.forEach { taskCard(it, session, canManage, laneBoard = true, mayDrag = canMoveCards) }
            }
        }
        // The dashed box that trails the run of columns, for the reader who may add one. It is a column's width
        // and a column's height without being a column — no `data-lane`, so [kanban.js] never offers it as a drop
        // target, and no cards could ever sit in it. What it opens is the popup, not a field: a name that is
        // blank, too long, already on this board or outside the category's catalogue needs a place to say so, and
        // the picture's own answer is an empty box that asks rather than a box that takes the typing.
        if (mayWriteLanes) laneAddBucketForm(projectId)
    }
}

/**
 * The `⋯` on one lane's head (Stage 74): the opener for that column's popup.
 *
 * [bucketMenuForm]'s exact mechanism on the project page — a GET form onto this page carrying `laneedit=<id>`,
 * which [app.js] answers by fetching that URL and swapping only `#projectLaneModal`, so the popup the server drew
 * already open arrives over the board the reader is standing on. Scripts off, the same click loads the same page
 * with the same popup up. The id travels as a field of the form rather than as an `href` written per column, and
 * `?laneedit=` is a key of its own because `?lane=` on this URL already carries the code a management lane
 * request was refused with, and `?into=` the lane a new card is aimed at.
 */
private fun DIV.laneMenuForm(projectId: Int, laneId: Int) {
    form(action = "/app/projects/$projectId", method = FormMethod.get, classes = "kcol-menu") {
        attributes["data-live-target"] = "#projectLaneModal"
        input(type = InputType.hidden, name = "laneedit") { value = "$laneId" }
        button(type = ButtonType.submit, classes = "kcol-menu-btn") {
            attributes["aria-label"] = "Manage this bucket"
            attributes["title"] = "Rename or remove this bucket"
            +"⋯"
        }
    }
}

/**
 * The dashed `+ Add bucket` box at the end of the board's run of columns (Stage 74).
 *
 * A GET form onto this page as `?laneadd=1`, opening the page's one bucket popup — the same shape the backlog's
 * head button gives, with one difference the picture dictates: on this page the columns *are* the board, so the
 * offer to add one sits at the end of their run rather than in the page head. It opens a form rather than being
 * one, because the name it asks for is refused by four rules (blank, too long, already on this board, outside the
 * category's catalogue unless the reader is management) and a box on the board has nowhere to print which of them
 * stopped it.
 */
private fun DIV.laneAddBucketForm(projectId: Int) {
    form(action = "/app/projects/$projectId", method = FormMethod.get, classes = "lane-add-box") {
        attributes["data-live-target"] = "#projectLaneModal"
        input(type = InputType.hidden, name = "laneadd") { value = "1" }
        button(type = ButtonType.submit, classes = "lane-add-box-btn") { +"+ Add bucket" }
    }
}

/**
 * The `▸/▾ Completed N` line under a column's live cards (Stage 80).
 *
 * The prototype the board was copied from keeps this open-or-closed in the browser's session, which is fine for a
 * demo and would be a second source of truth here: this page renders its columns from the request, and a card the
 * reader cannot see is a card the page has to know about when it draws the next one. So the answer is carried in
 * the URL as `?showdone=<lane id>`, repeated once per expanded column, and this control is a GET form that posts
 * **the set as it will be after the click** — this column in it when it is shut, out of it when it is open, every
 * other open column carried along. [app.js] fetches that URL and swaps `#projectBoard`, so the line the reader
 * pressed re-renders in the state they asked for and the rest of the board does not move; without scripts the
 * same form loads the same page and the same block comes back open.
 *
 * Only columns with a finished card in them reach here, so the count is never zero and a column nobody can expand
 * prints no line to try.
 */
private fun DIV.laneDoneToggle(projectId: Int, lanes: List<ProjectBucket>, laneId: Int, done: Int, open: Set<Int>) {
    val expanded = laneId in open
    val next = if (expanded) open - laneId else open + laneId
    form(action = "/app/projects/$projectId", method = FormMethod.get, classes = "kcol-done-toggle") {
        attributes["data-live-target"] = "#projectBoard"
        lanes.map { it.id }.filter { it in next }.forEach { id ->
            input(type = InputType.hidden, name = "showdone") { value = "$id" }
        }
        button(type = ButtonType.submit, classes = "kdone-btn") {
            attributes["title"] = if (expanded) {
                "Hide the $done completed ${if (done == 1) "card" else "cards"} in this column"
            } else {
                "Show the $done completed ${if (done == 1) "card" else "cards"} in this column"
            }
            +("${if (expanded) "▾" else "▸"} Completed $done")
        }
    }
}

/**
 * The six hues a bucket can be given, drawn as a row of swatches to choose one of (Stage 80).
 *
 * **A slot, never a colour**, and this is the whole of why the row is six boxes rather than a colour box: the
 * hues live in app.css ([mn.murray.pts.domain.BUCKET_DOT_COLOURS] is the count Kotlin agrees with it on), so
 * what a reader picks is *which of them* this column wears and a posted value can only ever be `0` to
 * [BUCKET_DOT_COLOURS]. Nothing here can paint a column a colour the theme cannot carry, and a value a hand-built
 * POST invents is read back as no answer at all by [mn.murray.pts.domain.colourSlotOf].
 *
 * Since **Stage 83** there is one caller: the rename half of a project board's bucket popup
 * ([mn.murray.pts.laneManageBody]), where a reader is changing a column already in front of them. Creation no
 * longer asks — neither the `+ Add bucket` half nor Settings' catalogue add line draws this row — because a new
 * bucket takes the shade of its place in the run and a reader who wants another one recolours the column after
 * it exists, from the same popup this row lives in.
 *
 * The radio is left **visible** rather than hidden behind its swatch, because the row is the one control on these
 * forms whose whole meaning is the picture: hiding the input and styling the label by `:checked` would need the
 * stylesheet to carry a state the server cannot see, and a reader tabbing through six identical-looking boxes
 * with no focus ring on any of them has no answer to which they are on. [current] is that answer — the slot the
 * lane already wears, or the slot a new column would wear by position — and the caller resolves it through
 * [mn.murray.pts.domain.effectiveColourSlot] so the row agrees with the dot on the column.
 */
fun FlowContent.bucketColourPicker(current: Int, fieldName: String = "colour") {
    div("colour-row") {
        span("colour-caption") { +"Colour" }
        BUCKET_COLOUR_NAMES.forEachIndexed { slot, hue ->
            label("colour-opt") {
                input(type = InputType.radio, name = fieldName) {
                    attributes["value"] = "$slot"
                    checked = slot == current
                    attributes["aria-label"] = hue
                }
                span(bucketDotClass(slot)) { }
            }
        }
    }
}

/**
 * The form behind a click on a card: a GET onto this page as `?edit=<card>`, which [app.js] fetches and
 * answers by swapping `#taskModal` — so the popup arrives filled from the database and the board
 * underneath does not move. Nothing here needs to be seen: no button, no label, hidden so it costs no
 * layout and no tab stop, and a card's id is written into its field by the click.
 *
 * It posts nothing. `edit=0` — the value it renders with, and what a board with no card on it leaves the
 * route to default — names no card, and [TaskService.openableBy] answers with the plain board.
 */
private fun DIV.taskEditOpener(back: String, projectId: Int) {
    form(action = taskRedirect(back, projectId), method = FormMethod.get, classes = "card-edit-opener") {
        id = "taskEditOpener"
        attributes["hidden"] = "true"
        attributes["data-live-target"] = "#taskModal"
        input(type = InputType.hidden, name = "edit") { value = "0" }
    }
}

/**
 * One task card. Two affordances, from two different rights:
 *
 * **Opens** — [data-task-card] carries the task id, which is all the opener needs to ask this page for that
 * card's editor — for **every reader**, since Stage 86. A card with no control inside it therefore does one
 * thing: opens. What it opens is as much as the reader may do with it: management and the card's own assignee
 * get the form they always had, and anybody else gets the same popup with every field greyed, the plan and the
 * log to read and no save (see [TaskForm.viewOnly]). The user asked for exactly this — *non-management can click
 * a card and see the task but not modify it* — and it is the read half of the project board being shared.
 *
 * **Drags** — `draggable="true"`, the only attribute [kanban.js] looks for when it picks a drag source — for
 * whoever may move the card between this board's columns. On the status board that is still the pair who write
 * it, because a card's owner walking their own work from Not started to Done is the thing that page is for.
 * On a project's lane board it is [mayDrag] and nothing else (Stage 49): the lanes are the project's own
 * structure, so filing a card inside them belongs to the accounts that may write onto the project — management
 * and that project's manager — and a plain colleague reads the same cards without moving them. The parameter is
 * nullable rather than defaulted to the mover rule because the caller knows which board it is: a board that
 * says nothing about `mayDrag` drags exactly as it always did.
 *
 * **Reading a card is not dragging it.** Before Stage 86 the two shared one flag (`opens`), because the only
 * reader who could click a card was one who could write it; the widen would have handed every colleague the
 * drag and the arrows as a side effect, which is a write. They are separate now: clicking is every reader's,
 * moving and walking stay `canManage || mine` or the board's own mover. (**Stage 103** took the arrows out, so
 * what is left of "walking" on a lane board is the holder's single ✓ Done / ↺ Reopen button.)
 *
 * Below those two affordances sits the card's one number — the percentage its own steps add up to — and
 * nothing else. The steps themselves are not on a card: a board is where work is read, and the tick boxes
 * are in the editor this card opens, beside the plan they belong to. See [modalSteps].
 *
 * Above them the card has two lines of its own, and which two depend on the board it sits on (**Stage 78**).
 * On a **project's lane board** the card is its title, then its priority/due/percentage line, then a foot line
 * carrying the stage it has reached — dot and word, the same idiom the priority beside it uses — at the left and
 * its holder's avatar at the right, and the whole card wears that stage's tint. There is no code chip: the
 * project's page is already one project's worth of cards, and the picture this board is built to has none. On a
 * **status board** the card keeps its identification chip on the top line and prints no stage word, because the
 * column it stands in *is* that word, and the avatar stays on the meta line where it has always been. Since
 * **Stage 88** that chip names the card's **project** by its code (`S-01`) rather than the card's own `T-00012`
 * number, because "which project is this work for" is the question a run of cards from every project at once
 * asks; an Other-task card — which has no project — names its **bucket** instead, so the reader can see where it
 * is filed without opening it. Only a card with neither (a legacy row) falls back to its task number.
 */
private fun DIV.taskCard(
    task: TaskItem,
    session: UserSession,
    canManage: Boolean,
    laneBoard: Boolean,
    mayDrag: Boolean? = null,
) {
    // Either person on the card is its holder (Stage 122): the two names share the plan, the log, the day and
    // the one ✓ Done button exactly as they share the card.
    val mine = task.assignedTo(session.employeeNumber)
    // The move affordances — the drag on a status board and the ✓ Done button under a lane board's card — belong
    // to the pair that writes the card: management and its holder. Since Stage 86 opening is everybody's, so the
    // window this flag no longer gates is the *reading* of it; see [TaskForm.viewOnly] for what a stranger's
    // popup is.
    val canMove = canManage || mine
    val draggable = mayDrag ?: canMove
    // Every card wears its stage's tint, on every board (**Stage 78**). A status board has done this since the
    // slug went on the node, and its five columns made the cost obvious: a column of five cards all in progress
    // paints the same amber five times. On a lane board that is no longer a repetition of the heading — the
    // heading names a *phase*, and the tint is the one thing on the card that says where the work itself has
    // got to, read from across the room before any word is. The word is printed too, on the foot line below.
    // Since Stage 95 the backlog board's cards are in this set as well — the two boards cut cards by the same five
    // statuses and a stage that is amber here and slate there reads as two different facts.
    div("kcard ${statusSlug(task.status)}") {
        attributes["data-id"] = "${task.id}"
        if (draggable) attributes["draggable"] = "true"
        // The id is all the opener needs to ask this page for the card's editor. The tab stop and the label are
        // so a reader who is not using a mouse reaches the same popup on the card they are standing on — a
        // click target that cannot be focused is only half an affordance.
        attributes["data-task-card"] = "${task.id}"
        attributes["tabindex"] = "0"
        attributes["aria-label"] = "Open ${task.title}"
        // The card's identification chip, on its own top line, on a **status board** only (Stage 78). That page
        // is a run of cards from every project at once, so one fact has to tell the cards apart — since Stage 88
        // that fact is the **project's code** (`S-01`), because the reader's question there is which world this
        // work belongs to, not which row it is. Since **Stage 104 that code is also a way in**: the chip is the
        // card's own link to `/app/projects/<id>`, the same page the code names, so a reader working down a long
        // column no longer has to open the card, read the project line in it and go find the board. The link is
        // a plain `<a>` and nothing more — `task-modal.js` already steps aside from a click inside an `<a>` on a
        // card (the rule that keeps a card's own buttons from reopening it), so the popup stays shut and the drag
        // source is untouched, and a reader with no scripts follows the href like anybody. The card itself still
        // opens on a click anywhere else.
        //
        // A card of no project (an Other task) has no project code and wears its **bucket** instead, which is the
        // same fact for that half of the board: where the work is filed — and it stays a label, because a bucket
        // is a column of a board that has no page of its own. A legacy row with neither keeps the task number it
        // has always printed, also unlinked. A project's lane board is one project's work and its picture starts
        // each card at its title, so no chip is printed there — the project is the page the reader is on.
        if (!laneBoard) {
            div("kcard-top") {
                when {
                    task.projectCode.isNotBlank() && task.projectId > 0 ->
                        // `classes =` named, not `a("tcode")`: kotlinx's one-argument `a` is the *href* overload,
                        // and the chip would come out wearing no class at all.
                        a(classes = "tcode") {
                            href = "/app/projects/${task.projectId}"
                            attributes["title"] = "Open project ${task.projectCode}"
                            attributes["aria-label"] = "Open project ${task.projectCode} on the Projects board"
                            +task.projectCode
                        }
                    task.projectCode.isNotBlank() -> span("tcode") { +task.projectCode }
                    task.isBacklog && task.bucket.isNotBlank() -> span("tcode bucket-chip") { +task.bucket }
                    else -> span("tcode") { +taskCode(task.id) }
                }
            }
        }
        div("ktitle") { +task.title }
        // The card's live region: its meta line, swapped on its own. A step ticked in the popup moves the
        // percentage printed here, and one response carrying both blocks is what keeps the number from
        // sitting on the value from before the click.
        //
        // It stops short of the whole `.kcard` on purpose: that node is the drag source `kanban.js` binds
        // `dragstart` to, and replacing it would leave the card undraggable until the next reload. The
        // move buttons sit outside for the same reason — they carry their own bound listeners.
        // One avatar per person on the card — two on a shared project card (Stage 122) — each keeping the
        // hover label the one holder has always had.
        val holders = task.assignees.mapNotNull { emp -> DemoData.team.firstOrNull { it.employeeNumber == emp } }
        div("kbody") {
            id = cardBodyRegion(task.id)
            div("kmeta") {
                span("prio ${prioClass(task.priority)}") { +task.priority }
                span("due") { +"📅 ${formatDue(task.due)}" }
                // The card's own percentage, from its own checklists — and nothing at all on a card that
                // has no plan, which is unmeasured rather than 0% done. Anyone who can see the card sees
                // this, including management, who read a board for exactly this number.
                task.progressPercent?.let { span("kpct") { +"$it%" } }
                // On a status board the holder rides on this line, where they have always stood. On a lane
                // board they drop to the foot line below, beside the stage word the picture pairs them with.
                if (!laneBoard) holders.forEach { holder ->
                    span("avatar xs") { attributes["title"] = holder.name; +initials(holder.name) }
                }
            }
        }
        // A lane board's foot line: the stage the card has reached, as a dot and a word — the same idiom the
        // priority above it is drawn with — and the holder's avatar at the far right. The stage word is printed
        // here and nowhere else on this page because the columns name a **phase**, not a stage (Stage 75), so
        // the board would otherwise say nothing at all about how far its cards have got. A status board prints
        // no such line: its column head already *is* the word, and repeating it under every card in the column
        // is two places for one fact.
        if (laneBoard) {
            div("kmeta kfoot") {
                span("kstat ${statusSlug(task.status)}") { +task.status }
                holders.forEach { holder ->
                    span("avatar xs") { attributes["title"] = holder.name; +initials(holder.name) }
                }
            }
        }
        // **Stage 103 retired the prev/next arrows.** On a status board a column is changed by dragging the card
        // into another column — which both status boards have taken since Stage 58, and which Stage 94 made work
        // through a live-region swap — so the two buttons underneath the card were a second way to the same write
        // that nobody reached for while the board answered the mouse. They are gone from Assigned tasks and from
        // the shared Other tasks board together: one card, one answer, not one board keeping a control the other
        // retired.
        //
        // What remains is the **lane** board's single button, and it is a different act rather than the same one
        // with fewer keys: a project's columns name a *phase*, not a stage, so there is no Completed column to
        // drag into. The reader who cannot set Status in the popup gets the one move that still matters — done, or
        // not done yet. `kanban.js` posts the `.kmove-btn` to `/app/tasks/status` exactly as it always did, so
        // this costs no script and no route.
        //
        // This hangs on the **holder** (`mine`), not on the drag flag, and deliberately so (Stage 49): finishing
        // your own card is the one board move an owner keeps, because a status is a fact about the work rather
        // than a place on somebody's board.
        //
        // It used to hang on `opens`, which then meant *may write this card*. Since Stage 86 every card opens for
        // everybody, so that flag would have put a Done button on every colleague's card — the one thing this
        // widen must not do. The condition is the holder, spelled out.
        if (mine && !canManage && laneBoard) {
            div("kmove") {
                button(type = ButtonType.button, classes = if (task.isOpen) "kmove-btn next" else "kmove-btn") {
                    attributes["data-id"] = "${task.id}"
                    attributes["data-status"] = if (task.isOpen) "Completed" else "Not started"
                    attributes["title"] = if (task.isOpen) "Mark this task done" else "Reopen this task"
                    +(if (task.isOpen) "✓ Done" else "↺ Reopen")
                }
            }
        }
        // No corner mark: there is nothing else the card opens. The whole body is the target, and
        // app.css gives a clickable card its hover and focus rings instead.
    }
}

/**
 * The id a step write swaps on the card behind the popup: the card's meta line, whose percentage is counted
 * from the very steps being ticked. The card carries no plan of its own since Stage 41, so this is one line
 * rather than a section — but it still has to move in the same response, or the number goes stale beside a
 * box that has already changed. Named apart from [taskCard]'s `.kcard` because that node is [kanban.js]'s
 * drag source, and replacing it mid-drag would drop the card.
 *
 * Both boards use it: a backlog card prints the same line, so a tick in its popup repaints the percentage on
 * the column it is standing in (Stage 42).
 */
fun cardBodyRegion(taskId: Int) = "task-body-$taskId"

/**
 * The project-level number a step write also moves: the project's own progress bar. It lives only on a
 * project page, and app.js skips a companion it cannot find in the response, so the same form is correct
 * on the general board. The roll-up strip that used to sit beside it is gone (Stage 28), which is why this
 * list is one selector short of the two it used to be.
 */
private const val PROJECT_COMPANIONS = "#project-progress"

/**
 * The five hidden fields every checklist form carries. The card id and the step id say what to
 * change; `back` and `projectId` say where to return to, and both are server-rendered from the page
 * the card was drawn on — never typed — so an edit opened from a project page goes back there.
 * [edit] is the card's own id in every popup form, which is what brings the popup back up on the same
 * card after a step is ticked, added or taken off.
 */
private fun kotlinx.html.FORM.checklistFields(
    taskId: Int,
    itemId: Int,
    back: String,
    projectId: Int,
    edit: Int = 0,
) {
    input(type = InputType.hidden, name = "taskId") { value = "$taskId" }
    input(type = InputType.hidden, name = "itemId") { value = "$itemId" }
    input(type = InputType.hidden, name = "back") { value = back }
    input(type = InputType.hidden, name = "projectId") {
        value = "${if (back == BACK_PROJECT) projectId else 0}"
    }
    input(type = InputType.hidden, name = "edit") { value = "$edit" }
}

/** The hidden fields a note form carries: which card, which note, and which page to return to. */
private fun kotlinx.html.FORM.noteFields(
    taskId: Int,
    noteId: Int,
    back: String,
    projectId: Int,
    edit: Int,
) {
    input(type = InputType.hidden, name = "taskId") { value = "$taskId" }
    input(type = InputType.hidden, name = "noteId") { value = "$noteId" }
    input(type = InputType.hidden, name = "back") { value = back }
    input(type = InputType.hidden, name = "projectId") {
        value = "${if (back == BACK_PROJECT) projectId else 0}"
    }
    input(type = InputType.hidden, name = "edit") { value = "$edit" }
}

/**
 * The create/edit popup. One form serves both: a hidden `taskId` of 0 creates, anything else
 * updates that card, and the action is always `/app/assigned-tasks/save`.
 *
 * It is server-rendered in both directions, which is the whole reason the opener is a GET form rather
 * than a script: `?new=1` comes back as a blank form, `?edit=<card>` as that card filled in from the
 * database, and a rejected submit as the page with what was typed still in the inputs and each message
 * under its own field. [app.js] swaps only `#taskModal` for whichever of those the URL asked for, so
 * the board behind it never moves.
 *
 * Under the fields sit the card's two sections, [modalSteps] and [modalNotes], and they are separate
 * forms on purpose — a form cannot live inside the save form. Each writes one row and comes back as the
 * same popup open on the same card, so adding a step does not close the form somebody is still filling.
 */
fun DIV.taskFormModal(
    state: TaskForm,
    back: String,
    fixedProject: Project? = null,
) {
    val v = state.values
    val projectNames = DemoData.projects.map { it.name }
    // On a project page the task always belongs to that project, and since Stage 48 every assigned card names
    // one: a **create** therefore opens on the first project rather than on a blank the route would only refuse
    // again. The one form allowed to answer with nothing is an edit of a row that predates the rule and still
    // carries no project — it prints that as a placeholder, and saving it stays refused until management chooses
    // a world for the card.
    val wanted = v.projectName.ifBlank { fixedProject?.name.orEmpty() }
    // **Stage 111:** the general board's `+ New task` is the one create that picks its project *inside* the
    // popup, so it opens on no project at all, and its Sub-category list is that project's own columns rather
    // than the legacy trio [TaskForm.laneChoices] falls back to. Every other form here already names the board
    // the card belongs to — a project page through [fixedProject], an edit through the row it was loaded from —
    // and those keep behaving exactly as they did.
    val choosesProject = !state.isEdit && !state.showsBacklog && fixedProject == null &&
        state.mayWriteCard && state.lanesByProject.isNotEmpty()
    val selectedProject = when {
        choosesProject -> wanted.takeIf { it in projectNames }.orEmpty()
        wanted.isNotEmpty() && wanted in projectNames -> wanted
        wanted.isNotEmpty() -> projectNames.firstOrNull().orEmpty()
        state.isEdit -> ""
        else -> projectNames.firstOrNull().orEmpty()
    }
    val carriedProjectId = DemoData.projects.firstOrNull { it.name == selectedProject }?.id ?: 0
    // Where a step or a note sent from inside this popup returns to. On a project page that is the
    // page itself, so a card moved off it still comes back to the board it was opened from.
    val pageProjectId = if (back == BACK_PROJECT) fixedProject?.id ?: carriedProjectId else 0
    // The fields a non-manager reads but cannot write. The service takes them from the stored row
    // rather than from this form, so greying them is presentation, never the check. A create by the
    // account a project names as its manager is the one case that writes a full card without being
    // management, so it follows [TaskForm.mayWriteCard] rather than `canEditAll`.
    val readOnly = !state.mayWriteCard
    val pinned = state.projectPinned && fixedProject != null
    // Which column box the backlog's popup opens on. Like the lane below it, an edit reads the row and a
    // create takes the column the `+ Add task` line was pressed under; unlike the lane, there is no *unfiled*
    // answer to fall back to, because the Other tasks board has no Unsorted column.
    val currentColumn = v.columnId ?: state.backlogColumns.firstOrNull()?.id ?: 0
    // The column the picker opens on, by name: what a pinned picker prints, and what the create's hint says.
    // Null when the board has no columns, which is the case the hint below answers instead.
    val landingColumn = state.backlogColumns.firstOrNull { it.id == currentColumn }
    // Where a lane or a column picker has nothing to offer: an edit leaves the field out of the post (so the
    // card keeps the place it is in) and the popup says why there is no choice to make.
    val noColumns = state.showsBacklog && state.backlogColumns.isEmpty()
    // Which columns this picker offers at all. On the general board's create that is the project chosen above it,
    // read out of the popup's own per-project list — and with no project chosen yet, nothing. Everywhere else it
    // is [TaskForm.laneChoices]: this page's board, or the row's, exactly as it has always been.
    val laneOptions = if (choosesProject) state.lanesByProject[selectedProject] ?: emptyList() else state.laneChoices
    // Which sub-category the Lane picker opens on. An edit reads what the form posted and, behind that, the lane
    // the row is in; a create reads the lane its `+ Add task` line was pressed under and, behind that, the first
    // one on the board — because **Unsorted is where the cards that arrived before this rule rest, not a place
    // to send one** (Stage 48), so a new card is never aimed at it and a row with no lane opens on a placeholder
    // that has to be answered before anything else on the form saves.
    val currentLane: String? = when {
        // On the general board's create the picker answers to the project chosen above it: what the form posted
        // if that project really has that column, otherwise the first column on that board — the same default a
        // project page's create has. With no project chosen there is no column to open on, which is the state the
        // placeholder below speaks for.
        choosesProject -> (v.projectLane?.trim()?.ifBlank { null } ?: laneOptions.firstOrNull())
            ?.takeIf { it in laneOptions }
        v.projectLane != null -> v.projectLane.trim().ifBlank { null }
        else -> state.task?.projectBucket?.trim()?.ifBlank { null }
            ?: if (state.isEdit) null else state.laneChoices.firstOrNull()
    }
    // A lane this picker does not list is still the card's lane: a project's extra sub-categories are offered on
    // that project's board only, so the same card opened from the general board would otherwise show a choice it
    // never made. It prints after the three, the way it prints after them on the board.
    val laneNames = laneOptions + listOfNotNull(currentLane?.takeIf { it !in laneOptions })
    // Which half of the form the card is filed under. A popup that offers the choice ships **both** and leaves
    // one of them hidden, so the reader can switch and see the fields they are switching to without the page
    // moving under them — and because the service reads [TaskDraft.board] to decide which half it takes, a
    // reader with JavaScript off gets the same answer from a form that shows both. A popup nobody may refile in
    // prints one half and nothing else, so there is no field in it for the other world to be posted at.
    val showColumn = state.showsBacklog
    val showProject = !state.showsBacklog
    val printColumn = showColumn || state.mayRefile
    val printProject = showProject || state.mayRefile
    // **Stage 87: a card that is already on a project does not print its Project and Sub-category pickers on
    // an edit.** Where the card sits is a fact of the row, and the one control that changes it is the drag on
    // the board behind this popup — so the two drop-downs only repeated the page, and the popup now opens on
    // the fields the reader came for. The project still rides along as a hidden field below: the save form has
    // to name the world it writes to, and a field that never printed must not be read as "the first project".
    //
    // The pair stays wherever it is still an answer to give: on a **create**, which has no row to read one
    // off; on a **backlog** card, whose "where it lives" picker only needs it at the moment the reader moves
    // the card onto a project (it is drawn in that half, under the same `hidden` the picker switches); and on
    // a row that arrived before every card named a project, which is the one card a picker can fix.
    val projectIsTheRows: Boolean =
        state.isEdit && !state.isBacklog && !state.task?.project.isNullOrBlank()
    // A refusal about either field prints under the field, so the fields come back with it: a page that
    // said nothing, or said it over a picker that was not there, would be the silent refusal this app does
    // not have. The only way a project card's save is refused here is a hand-built or stale form — the
    // popup posts the row's own project — and the pickers are then exactly what fixes it.
    val projectRefused: Boolean =
        state.error(TaskField.PROJECT) != null || state.error(TaskField.LANE) != null
    val printProjectFields = printProject && (!projectIsTheRows || projectRefused)
    div("modal") {
        id = "taskModal"
        if (state.isOpen) attributes["style"] = "display:block"
        div("modal-content modal-wide") {
            span("close") {
                attributes["data-modal-close"] = "true"
                +"×"
            }
            // The heading names what the popup is, and since Stage 86 that is not always an editor: a reader
            // who may write nothing in it is looking at the **task**, so it says so rather than offering them
            // an edit they are not being given.
            h3 {
                when {
                    !state.isEdit -> +"New task"
                    state.viewOnly -> +"Task ${taskCode(state.taskId)}"
                    else -> +"Edit task ${taskCode(state.taskId)}"
                }
            }
            if (state.viewOnly) {
                // Said before the fields, for the same reason the owner's line is: greyed inputs with no
                // sentence beside them read as a page that is broken. It also says what *is* theirs here —
                // the plan and the log, to read — so the popup does not look like an empty refusal.
                p("modal-scope") {
                    +(
                        "This task belongs to ${memberNames(v.assigneeEmpNo, v.assignee2EmpNo)}. You can read " +
                            "its plan and its log; its fields belong to management and to the people it is " +
                            "assigned to."
                        )
                }
            } else if (readOnly) {
                p("modal-scope") {
                    +if (state.isBacklog) {
                        // The column is management's here as well since Stage 49, so this reads the same as an
                        // assigned card's except for the field that names its board: an owner sets their own day
                        // on a card, not where the card sits. See [TaskForm.maySetColumn].
                        "The dates and the priority are yours to set. The title, the column, the people it is " +
                            "assigned to and the status belong to management."
                    } else {
                        // Stage 87 took the project and the sub-category off this popup, so the line names only
                        // what is still printed above it.
                        "The dates and the priority are yours to set. The title, the people it is assigned " +
                            "to and the status belong to management."
                    }
                }
            }
            if (state.selfPinned) {
                // Said before the pickers rather than after a refusal: the two fields this card cannot carry
                // are greyed, and a greyed field with no line beside it reads as a page that is broken. The
                // column is greyed too (Stage 49) and this line says which one it is, so a writer who wants a
                // different column is told to have written from under it.
                p("modal-scope") {
                    +("A card written here is yours and starts as Not started, under the column its + Add task " +
                        "line was pressed. Management can hand it to somebody else, move it along, or move it " +
                        "to another column, afterwards.")
                }
            }
            form(action = "/app/assigned-tasks/save", method = FormMethod.post) {
                input(type = InputType.hidden, name = "taskId") { value = "${state.taskId}" }
                input(type = InputType.hidden, name = "back") { value = back }
                input(type = InputType.hidden, name = "projectId") { value = "$carriedProjectId" }
                // *(**Stage 40** had a third field here — `allbuckets`, so a save from the backlog's wide view
                // did not answer by hiding the columns the reader had opened. **Stage 97** took the wide view
                // out, and the field with it; the save's board is named by `back` alone.)*
                // The card's project, carried where the picker no longer prints (Stage 87): the save still has
                // to name the world it writes to, and [mn.murray.pts.service.TaskService.updateAssigned] reads
                // the posted name as the card's own answer. Server-rendered from the row, never typed. A
                // refusal draws the picker instead, which is where its message belongs.
                if (projectIsTheRows && !projectRefused) {
                    input(type = InputType.hidden, name = "project") { value = selectedProject }
                }
                div("form-grid") {
                    div("field full") {
                        label { +"Task" }
                        input(type = InputType.text, name = "title") {
                            id = "taskTitle"
                            placeholder = "e.g. Review blast vibration report"
                            required = true
                            disabled = readOnly
                            value = v.title
                        }
                        state.error(TaskField.TITLE)?.let { p("field-err") { +it } }
                    }
                    if (state.mayRefile) {
                        // Which world the card lives in (Stage 48), and the only field on this form that decides
                        // where the *next* two are. It is management's, like the title above it: a card's place
                        // in the work is the team's decision, and the person it happens to be on changes their
                        // own day, not the plan. Everything else on the card rides along — its steps, its log,
                        // its status, its dates and who owns it — because the crossing moves the card, not the
                        // work.
                        div("field full") {
                            label { +"Where it lives" }
                            select {
                                name = "board"
                                id = "taskBoard"
                                attributes["data-board-toggle"] = "true"
                                option {
                                    value = BOARD_PROJECT
                                    selected = !state.showsBacklog
                                    +"On a project"
                                }
                                option {
                                    value = BOARD_OTHER
                                    selected = state.showsBacklog
                                    +"Other tasks"
                                }
                            }
                            span("field-hint") {
                                +(
                                    "Moving a card between them keeps its steps, its log, its status and the " +
                                        "person it belongs to. Only the board it prints on changes."
                                    )
                            }
                        }
                    }
                    if (printColumn) {
                        // A backlog card belongs to a column of the Other tasks board and to **no project** —
                        // that is the one thing that tells the two kinds apart — so this slot holds where on
                        // that board it is filed, exactly where a project card's holds which project it is on.
                        // The id is the field's value, not the name: a column renamed while this popup was open
                        // is the same column, and one that has gone is not a place a card can be put.
                        div("field full") {
                            if (!showColumn) attributes["hidden"] = "hidden"
                            // Which half of the form this is, so [task-modal.js] can bring it up when the reader
                            // changes their mind about the board and shut the other. A marker rather than a class
                            // because nothing in the stylesheet cares.
                            if (state.mayRefile) attributes["data-board-half"] = "other"
                            label { +"Column" }
                            select {
                                name = "column"
                                id = "taskColumn"
                                disabled = !state.maySetColumn || noColumns
                                if (state.columnPinned && !noColumns) {
                                    // A create whose column the reader already chose by standing under the
                                    // column's `+ Add task` line: the picker prints that one answer and nothing
                                    // else, the way a pinned Assign-to prints the writer rather than the roster.
                                    landingColumn?.let { onlyOption("${it.id}", it.name) }
                                } else {
                                    state.backlogColumns.forEach { column ->
                                        option {
                                            value = "${column.id}"
                                            selected = column.id == currentColumn
                                            +column.name
                                        }
                                    }
                                }
                            }
                            // A greyed select posts nothing at all, and unlike an edit — where the absence *is*
                            // the answer, because the service reads the row's column back — a create has to name
                            // one or be refused. So the pinned column rides along as a hidden field, printed by
                            // the server from the same read the picker shows.
                            if (state.columnPinned && !noColumns) {
                                input(type = InputType.hidden, name = "column") { value = "$currentColumn" }
                            }
                            if (noColumns) {
                                // Nothing to choose, and a line saying so, so an empty picker reads as a board
                                // that has no columns rather than as a form that lost its options.
                                span("field-hint") {
                                    +"The Other tasks board has no columns yet, so there is nowhere to file it."
                                }
                            } else if (!state.maySetColumn) {
                                // Greyed for its reader, and said: the whole column list is still printed on an
                                // edit, so this is the field that looks like everybody's and is management's.
                                span("field-hint") {
                                    if (state.isEdit) +"Management moves a card between the columns."
                                    else landingColumn?.let {
                                        +"New cards land under ${it.name}; management moves one from there."
                                    }
                                }
                            } else if (!state.isEdit) {
                                landingColumn?.let { span("field-hint") { +"A new card lands under ${it.name}." } }
                            }
                            state.error(TaskField.COLUMN)?.let { p("field-err") { +it } }
                        }
                    }
                    if (printProjectFields) {
                        div("field full") {
                            if (!showProject) attributes["hidden"] = "hidden"
                            if (state.mayRefile) attributes["data-board-half"] = "project"
                            label { +"Project" }
                            select {
                                name = "project"
                                id = "taskProject"
                                disabled = readOnly || pinned
                                // The marker `task-modal.js` fills the Sub-category picker from. It is on the
                                // picker that can change its answer in the popup, which is this one and only on
                                // the general board's create; a `fixedProject` page's create has one board and an
                                // edit has a row, so neither needs a list that moves.
                                if (choosesProject) attributes["data-lane-fill"] = "true"
                                if (readOnly || pinned) {
                                    // Either a card whose project belongs to management, or a new card whose
                                    // project is the page it was opened from. One option either way, so the
                                    // picker cannot show a list this account may not choose from.
                                    onlyOption(selectedProject, selectedProject.ifBlank { NO_PROJECT_YET })
                                } else {
                                    // There is no *no project* choice any more (Stage 48): a card is on a project
                                    // or it is on the Other tasks board, and a form that cannot say which has
                                    // picked has to be told. The one blank this picker can show is a row that
                                    // arrived before the rule, and it prints as what it is rather than as an
                                    // answer somebody could choose. **Since Stage 111** a create opened on the
                                    // general board starts on the same blank, and says what to do about it:
                                    // choose a project, and its columns come up below.
                                    if (selectedProject.isEmpty()) {
                                        option {
                                            value = ""
                                            selected = true
                                            +if (choosesProject) CHOOSE_A_PROJECT else NO_PROJECT_YET
                                        }
                                    }
                                    projectNames.forEach { name ->
                                        option {
                                            value = name
                                            selected = name == selectedProject
                                            // The board's own columns, in board order, riding on the option that
                                            // names that board: `task-modal.js` reads this and refills the
                                            // Sub-category picker when the project changes, so the two pickers
                                            // never disagree and the page never moves.
                                            if (choosesProject) {
                                                attributes["data-lanes"] =
                                                    laneOptionsJson(state.lanesByProject[name] ?: emptyList())
                                            }
                                            +name
                                        }
                                    }
                                }
                            }
                            // Words about making a card, so they belong to a create form this reader is
                            // filling; an edit popup says which project the card is on in its own field.
                            if (!state.isEdit && (state.canEditAll || pinned)) {
                                if (choosesProject) {
                                    // Said before the picker is used, because on this page nothing has decided the
                                    // card's project yet — the page is the reader's own board, not a project.
                                    span("field-hint") {
                                        +"This card lands on whichever project you pick, under one of its columns."
                                    }
                                } else {
                                    fixedProject?.let {
                                        span("field-hint") {
                                            if (pinned) +"New tasks land on ${it.name} — this project is yours to write for."
                                            else +"New tasks land on ${it.name}; pick another project to move one off this page."
                                        }
                                    }
                                }
                            }
                            state.error(TaskField.PROJECT)?.let { p("field-err") { +it } }
                        }
                    }
                    div("field full") {
                        label { +"Assign to" }
                        select {
                            name = "assignee"
                            id = "taskAssignee"
                            // Two reasons this picker is shut: [readOnly] (an owner editing a card whose
                            // assignee is management's to set), and [TaskForm.selfPinned] (a backlog create
                            // whose writer is its assignee by rule, not by choice). Both print the one person
                            // the card says rather than the roster, so a greyed field is not a hidden answer.
                            disabled = readOnly || state.selfPinned
                            if (readOnly || state.selfPinned) {
                                onlyOption(v.assigneeEmpNo, memberLabel(v.assigneeEmpNo))
                            } else {
                                optGroupPerMember(v.assigneeEmpNo)
                            }
                        }
                        state.error(TaskField.ASSIGNEE)?.let { p("field-err") { +it } }
                    }
                    // The **second** person on a project card (Stage 122), the user's *assign to 2 people*.
                    // Optional, and printed on a project popup only: a card of the Other tasks board is one
                    // person's work by design, and the service drops a second name on a crossing to that board.
                    // It is management's field like the one above it, so an owner's popup prints the person
                    // the card carries rather than the roster.
                    if (!state.isBacklog) {
                        div("field full") {
                            label { +"Also assign to" }
                            select {
                                name = "assignee2"
                                id = "taskAssignee2"
                                disabled = readOnly || state.selfPinned
                                if (readOnly || state.selfPinned) {
                                    onlyOption(
                                        v.assignee2EmpNo,
                                        if (v.assignee2EmpNo.isBlank()) "Nobody else" else memberLabel(v.assignee2EmpNo),
                                    )
                                } else {
                                    option {
                                        value = ""
                                        selected = v.assignee2EmpNo.isBlank()
                                        +"— Nobody else —"
                                    }
                                    optGroupPerMember(v.assignee2EmpNo)
                                }
                            }
                            span("field-hint") {
                                +"Optional. Both people share the card's plan, its log and its dates."
                            }
                            state.error(TaskField.ASSIGNEE2)?.let { p("field-err") { +it } }
                        }
                    }
                    div("field") {
                        label { +"Start date" }
                        input(type = InputType.date, name = "started") {
                            id = "taskStarted"
                            // The three fields that describe the reader's own day (Stage 23) are management's,
                            // a create-writer's, and the assignee's — not a stranger's, since Stage 86 put
                            // everybody else in this popup to read it. See [TaskForm.mayBookTime].
                            disabled = !state.mayBookTime
                            value = v.started
                        }
                        state.error(TaskField.START)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Due date" }
                        input(type = InputType.date, name = "due") {
                            id = "taskDue"
                            disabled = !state.mayBookTime
                            value = v.due
                        }
                        state.error(TaskField.DUE)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Priority" }
                        select {
                            name = "priority"
                            id = "taskPriority"
                            disabled = !state.mayBookTime
                            TASK_PRIORITIES.forEach { choice ->
                                option {
                                    value = choice
                                    selected = choice == (v.priority.ifBlank { "Medium" })
                                    +choice
                                }
                            }
                        }
                        state.error(TaskField.PRIORITY)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Status" }
                        select {
                            name = "status"
                            id = "taskStatus"
                            // A backlog card written by an ordinary account starts as Not started and that is
                            // the service's rule, not a suggestion — see
                            // [mn.murray.pts.service.TaskService.createBacklog].
                            disabled = readOnly || state.selfPinned
                            TASK_STATUSES.forEach { choice ->
                                option {
                                    value = choice
                                    selected = choice == (v.status.ifBlank { "Not started" })
                                    +choice
                                }
                            }
                        }
                        state.error(TaskField.STATUS)?.let { p("field-err") { +it } }
                    }
                    // The sub-category the card sits in on its project's board (V7), drawn wherever the Project
                    // picker is (see [printProjectFields]): a **create** has to answer it because every project's
                    // board opens with the same three, and a card cannot be left on no lane of it; a **backlog**
                    // card answers it at the moment its "where it lives" picker moves it onto a project, which is
                    // the same crossing that needs the project above. An **edit** of a card already on a project
                    // no longer prints it (Stage 87): the column is a fact of the row, and the board behind the
                    // popup files a card by dragging it. A board that has renamed one of them stops accepting
                    // that name, because the name is resolved against the lanes this card's project actually
                    // has (Stage 75). There is no *Unsorted* choice to make — the column is where the cards that
                    // arrived before this rule rest, and a row that came from there opens on the placeholder
                    // below until somebody files it. Like title, project and status it is a field that moves a
                    // card about the board, so a plain account reads it greyed where it prints and the service
                    // takes the stored value instead: see [mn.murray.pts.service.TaskService.updateAssigned].
                    if (printProjectFields) {
                        div("field") {
                            if (!showProject) attributes["hidden"] = "hidden"
                            if (state.mayRefile) attributes["data-board-half"] = "project"
                            label { +"Sub-category" }
                            select {
                                name = "lane"
                                id = "taskLane"
                                // Greyed while there is no column to offer: no board chosen, or a board that has
                                // none. `task-modal.js` lifts this the moment a project with columns is picked,
                                // in place.
                                disabled = readOnly || (choosesProject && laneOptions.isEmpty())
                                // The two sentences this field can be in while the project above is being chosen,
                                // handed to `task-modal.js` as the strings it puts in the option it rebuilds — the
                                // copy stays server-rendered, so the page and the script cannot drift.
                                if (choosesProject) {
                                    attributes["data-ask-project"] = CHOOSE_A_PROJECT_FIRST
                                    attributes["data-no-columns"] = NO_COLUMN_ON_BOARD
                                }
                                if (currentLane == null) {
                                    // Not a choice so much as the card's own state, and the one value the route
                                    // answers with a refusal for unless the row has no lane to keep. On the
                                    // general board's create the two states above the field are said in their own
                                    // words: no project chosen, or a board that has no column to file under.
                                    option {
                                        value = ""
                                        selected = true
                                        +when {
                                            choosesProject && selectedProject.isEmpty() -> CHOOSE_A_PROJECT_FIRST
                                            choosesProject && laneOptions.isEmpty() -> NO_COLUMN_ON_BOARD
                                            choosesProject -> NOT_A_COLUMN_OF_THIS_PROJECT
                                            else -> "— $UNSORTED_LANES —"
                                        }
                                    }
                                }
                                laneNames.forEach { lane ->
                                    option {
                                        value = lane
                                        selected = lane == currentLane
                                        +lane
                                    }
                                }
                            }
                            // A project page can promise this sentence because the page fixed the board; the
                            // general board's create is still being asked which board it is, and a line about the
                            // column it "lands under" would be a guess the picker contradicts.
                            if (!state.isEdit && !choosesProject) {
                                currentLane?.let { span("field-hint") { +"A new card lands under $it." } }
                            }
                            state.error(TaskField.LANE)?.let { p("field-err") { +it } }
                        }
                    }
                }
                state.error(TaskField.PERMISSION)?.let { p("field-err") { +it } }
                // **Stage 86:** a reader who may write nothing on this card is given no save button, and the
                // action that closes the popup says *Close* rather than *Cancel* — there is nothing to cancel.
                // The form is still a form, with every input in it disabled: pressing Enter somewhere posts
                // nothing, and a hand-built POST is refused by the service whatever this page offers.
                div("modal-actions") {
                    if (!state.viewOnly) {
                        button(type = ButtonType.submit, classes = "submit") {
                            if (state.isEdit) +"Save task" else +"Create task"
                        }
                    }
                    button(type = ButtonType.button, classes = "ghost-btn") {
                        attributes["data-modal-close"] = "true"
                        +if (state.viewOnly) "Close" else "Cancel"
                    }
                }
            }
            // The plan and its boxes both live here now. Management gets the section because they write it;
            // the person a card belongs to gets it because the tick boxes they used to work down on the card
            // are in here — a card that no longer carries steps is only a quiet card if somewhere still
            // lets its owner tick them. **Any other reader gets it too since Stage 86**, drawn stopped: a plan
            // is part of what the card *is*, so the popup a stranger opens without it would be a popup that
            // hides the work. [TaskForm.mayTick] is what decides between the two readings (see [modalSteps]).
            if (state.isEdit) modalSteps(state, back, pageProjectId)
            // A note is somebody reporting on the work, which both the reporter and management may do; the log
            // is everybody's to read, and [modalNotes] is where the box is drawn on [TaskForm.mayNote].
            if (state.isEdit) modalNotes(state, back, pageProjectId)
        }
    }
    // Open/close, and moving the card menu onto the clicked card. External because CSP allows only
    // 'self' scripts.
    script(src = "/static/js/task-modal.js") {}
}

/**
 * The steps of the card this popup is open on — the plan, the boxes, and the two rights that differ.
 *
 * A tick is working the plan, so it is offered to management and to the person the card belongs to
 * ([TaskForm.mayTick], which is [mn.murray.pts.service.TaskService.toggleChecklistItem]'s rule read back as
 * presentation). **Since Stage 89** the *New step…* line follows the same line ([TaskForm.mayAddStep]): the
 * owner of the work adds to its plan, exactly as management does. Taking a step off stays management's alone,
 * so the × is drawn on [TaskForm.canEditAll] and an owner sees a list with boxes and an add line but no ×.
 * Nobody is offered a control the route would refuse.
 *
 * **Since Stage 86 there is a third reader here: somebody who may write nothing at all.** The section is drawn
 * for them too (the plan is part of what the card is, and a popup that opened without it would hide the work),
 * in its stopped reading — every row a [stepRowLocked] square, no ×, no *New step…* line. So there are two
 * reasons a row is drawn stopped and they read differently: a *Completed* card whose plan is settled, and a
 * card that belongs to somebody else. Both keep the same `.chk-row`, the same square and the same struck label.
 *
 * Every one of these forms re-renders **this** block (`data-live-target`) rather than the page, and names its
 * companions (`data-live-extra`): the card out on the board, whose percentage the tick just moved, and the
 * project's own bar. One response carrying all three is what keeps a number on the screen from holding the
 * value from before the click. Each also posts `edit` with the card's id, so the popup that answers is open
 * on the same card — ticking a step has never closed an editor, and it is not starting now. See [app.js].
 */
private fun DIV.modalSteps(
    state: TaskForm,
    back: String,
    projectId: Int,
) {
    val task = state.task ?: return
    val region = "task-steps-${task.id}"
    val companions = "$PROJECT_COMPANIONS,#${cardBodyRegion(task.id)}"
    // Which of the two stopped readings a row gets, or null when it is a box the reader may work. The status
    // is asked first because it is the service's own order: `toggleChecklistItem` refuses a finished card
    // before it looks at who is asking, so a colleague reading somebody's completed card hears the archive
    // reason it would give management rather than a permission it never reached.
    val lockedReason = when {
        !task.isOpen -> "This card is Completed, so its steps are settled — move the card back to change them"
        !state.mayTick -> "This card belongs to somebody else, so its plan is yours to read and not to work"
        else -> null
    }
    div("modal-checklist") {
        id = region
        div("chk-head") {
            span("chk-key") { +"Checklist" }
            span("chk-count") {
                +("${task.checklistDone} of ${task.checklist.size} done" +
                    (task.progressPercent?.let { " · $it%" } ?: ""))
            }
        }
        if (!task.hasChecklist) p("section-empty") { +"No steps on this card yet." }
        // The card's own measurement, drawn with the one progress bar the app has (`.progress`/`.bar`, the
        // same pair a Projects-board row and a project page's Progress cell use) so this is the same object
        // at the same size, not a second style of bar. It sits inside this section's swapped block, so the
        // tick that moves the number fills the track in the very same response. And it stays off a card
        // nobody has planned: an empty plan is not a plan at zero, which is the rule the card's chip obeys.
        task.progressPercent?.let { percent ->
            div("kprog") {
                attributes["title"] = "What this card's own steps add up to"
                div("progress") {
                    div("bar") { attributes["style"] = "width:$percent%" }
                }
            }
        }
        // The second reading of the same list, for two reasons since Stage 86 (see [lockedReason]): a card in
        // *Completed*, whose plan the service ticked and whose boxes it refuses, and a card that belongs to
        // somebody else. The rows keep the same `.chk-row`, the same square and the same struck label, only
        // stopped and explained — a plan is still worth reading when it is not yours to work. The way to
        // change a finished card's steps is to move the card back out of *Completed*, which puts each step
        // where it stood.
        task.checklist.forEach { item ->
            div("chk-row") {
                if (lockedReason != null) {
                    stepRowLocked(item, lockedReason)
                } else {
                    stepTickForm(item, task, region, companions, state, back, projectId)
                }
                stepRemoveForm(item, task, region, companions, state, back, projectId)
            }
        }
        if (state.mayAddStep) stepAddForm(task, region, companions, back, projectId)
    }
}

/**
 * One step an open card's owner or management may tick: the box itself, as a form that flips what the row
 * already says, so nothing here has to know which way it is going. The whole row is a `label`, so clicking
 * the word works as well as clicking the square, and `data-autosubmit` ([app.js]) posts it the moment it
 * changes — see [modalSteps] for the regions this repaints.
 */
private fun kotlinx.html.DIV.stepTickForm(
    item: ChecklistItem,
    task: TaskItem,
    region: String,
    companions: String,
    state: TaskForm,
    back: String,
    projectId: Int,
) {
    form(action = "/app/tasks/checklist/toggle", method = FormMethod.post, classes = "chk-toggle") {
        attributes["data-live-target"] = "#$region"
        attributes["data-live-extra"] = companions
        checklistFields(task.id, item.id, back, projectId, state.taskId)
        label("chk-box") {
            attributes["for"] = "chk-${item.id}"
            input(type = InputType.checkBox, name = "done") {
                id = "chk-${item.id}"
                checked = item.done
                attributes["data-autosubmit"] = ""
            }
            span(if (item.done) "chk-label done" else "chk-label") { +item.label }
        }
    }
}

/**
 * One step of a card this reader may not work, drawn stopped rather than clickable — the same object as
 * [stepTickForm]'s row with the form taken out from under it, and [reason] on its `title`. It says what the row
 * holds rather than assuming the tick: [mn.murray.pts.service.TaskService] ticks a card's whole plan on the way
 * into *Completed*, while a step written onto a finished card lands unticked like any other (Stage 101) — so a
 * settled plan may carry one row that is not struck through, and this square shows it the way it stands.
 *
 * [reason] is the sentence that says why — a settled plan on a *Completed* card, or (Stage 86) a card that
 * belongs to somebody else. The square is the same square either way, because what the reader is looking at is
 * a plan, not a permission.
 */
private fun kotlinx.html.DIV.stepRowLocked(item: ChecklistItem, reason: String) {
    div("chk-box locked") {
        attributes["title"] = reason
        input(type = InputType.checkBox) {
            id = "chk-${item.id}"
            checked = item.done
            disabled = true
        }
        span(if (item.done) "chk-label done" else "chk-label") { +item.label }
    }
}

/**
 * The × on one step, for the reader who writes plans. Drawn as its own form beside the row it takes off,
 * and silent for anybody else — `removeChecklistItem` refuses them, so a control would only be a refusal
 * waiting to happen.
 */
private fun kotlinx.html.DIV.stepRemoveForm(
    item: ChecklistItem,
    task: TaskItem,
    region: String,
    companions: String,
    state: TaskForm,
    back: String,
    projectId: Int,
) {
    if (!state.canEditAll) return
    form(action = "/app/tasks/checklist/remove", method = FormMethod.post, classes = "step-remove") {
        attributes["data-live-target"] = "#$region"
        attributes["data-live-extra"] = companions
        checklistFields(task.id, item.id, back, projectId, state.taskId)
        button(type = ButtonType.submit, classes = "x-btn") {
            attributes["data-confirm"] = "Remove this step from the card?"
            attributes["aria-label"] = "Remove step: ${item.label}"
            +"×"
        }
    }
}

/** The *New step…* line: one field, appended to this card's plan in the order it was typed. Drawn for
 * management and, since Stage 89, for the person the card is assigned to — [TaskForm.mayAddStep] is that
 * rule and it is the same one the route checks.
 *
 * There is deliberately no button — a form whose only writable field is this one submits on Enter by the
 * browser's own implicit-submission rule, and a second way to add a step would be a second thing to aim at
 * the same route. The note box underneath keeps its button because a textarea takes Enter for a new line. */
private fun kotlinx.html.DIV.stepAddForm(
    task: TaskItem,
    region: String,
    companions: String,
    back: String,
    projectId: Int,
) {
    form(action = "/app/tasks/checklist/add", method = FormMethod.post, classes = "section-add") {
        attributes["data-live-target"] = "#$region"
        attributes["data-live-extra"] = companions
        input(type = InputType.hidden, name = "taskId") { value = "${task.id}" }
        input(type = InputType.hidden, name = "back") { value = back }
        input(type = InputType.hidden, name = "projectId") { value = "$projectId" }
        input(type = InputType.hidden, name = "edit") { value = "${task.id}" }
        input(type = InputType.text, name = "label") {
            id = "stepAdd-${task.id}"
            placeholder = "New step…"
            maxLength = "${TaskService.CHECKLIST_LABEL_MAX}"
            required = true
        }
    }
}

/**
 * The log under a card: what the work amounts to, in the words of whoever wrote it, newest first.
 *
 * Notes are appended and never edited — a log that can be rewritten in place is not a log — so this
 * section holds a list and one box, and one note cannot overtype another. The name on each entry is the
 * point of the shape: [TaskNote.author] stores the employee number and is read back here as a person,
 * falling back to the number if the account has since left the roster. Only management can take an entry
 * off, which is why the × is rendered on [TaskForm.canEditAll] and the box is not.
 *
 * **The box and management's Delete task share one foot row (Stage 72).** Writing a note and taking the
 * whole card off are the two things a person does at the end of this panel, and the popup is the only place
 * either happens, so the delete lives here rather than back out on the board where a click opens a card. It
 * sits at the far right of the *Add note* line — the destructive action kept away from the button that adds
 * to the log, and reachable from the card it destroys.
 *
 * **Stage 86:** the log is readable by everybody the card opens for, so the foot row is drawn only for a
 * reader who may write in it — the box on [TaskForm.mayNote] and the delete on [TaskForm.canEditAll]. A
 * stranger's popup is a list of entries and nothing under it; an empty foot row over a card that belongs to
 * somebody else would be the shape of a box they are not being given.
 */
private fun DIV.modalNotes(state: TaskForm, back: String, projectId: Int) {
    val task = state.task ?: return
    val region = "task-notes-${task.id}"
    div("modal-notes") {
        id = region
        div("chk-head") {
            span("chk-key") { +"Notes" }
            span("chk-count") {
                +"${task.notes.size} ${if (task.notes.size == 1) "entry" else "entries"}"
            }
        }
        if (!task.hasNotes) p("section-empty") { +"Nothing has been written on this card yet." }
        task.notes.forEach { note ->
            div("note-row") {
                div("note-body") { +note.body }
                div("note-by") { +"${noteAuthor(note.author)} · ${formatDue(note.created)}" }
                if (state.canEditAll) {
                    form(action = "/app/tasks/notes/remove", method = FormMethod.post, classes = "note-remove") {
                        attributes["data-live-target"] = "#$region"
                        noteFields(task.id, note.id, back, projectId, task.id)
                        button(type = ButtonType.submit, classes = "x-btn") {
                            attributes["data-confirm"] = "Delete this note? A log entry removed here is gone for everybody."
                            attributes["aria-label"] = "Delete this note"
                            +"×"
                        }
                    }
                }
            }
        }
        // A reader who may write neither a note nor the card's delete gets no foot row at all (and
        // [mayNote] is implied by [canEditAll], so this is the one test the row needs).
        if (!state.mayNote) return@div
        div("note-foot") {
            if (state.mayNote) {
                form(action = "/app/tasks/notes/add", method = FormMethod.post, classes = "section-add note-add") {
                    attributes["data-live-target"] = "#$region"
                    input(type = InputType.hidden, name = "taskId") { value = "${task.id}" }
                    input(type = InputType.hidden, name = "back") { value = back }
                    input(type = InputType.hidden, name = "projectId") { value = "$projectId" }
                    input(type = InputType.hidden, name = "edit") { value = "${task.id}" }
                    textArea(rows = "3") {
                        // Written as attributes because kotlinx.html's textArea takes rows/cols as parameters
                        // and leaves the rest of the group to the attribute map; `name` is what the route reads.
                        attributes["name"] = "body"
                        id = "noteAdd-${task.id}"
                        attributes["placeholder"] = "What changed, what is holding it up…"
                        attributes["maxlength"] = "${TaskService.NOTE_MAX}"
                        attributes["required"] = "required"
                    }
                    button(type = ButtonType.submit, classes = "submit btn-sm") { +"Add note" }
                }
            }
            // A form cannot live inside the note form, so the two are siblings and the row lays them out.
            // **Stage 84 put the permanent delete back on a project card**: the popup's foot offers management's
            // **Delete task** in either world, exactly as it did before Stage 81's archive button replaced it.
            // Archiving is still how a card enters this project's Archive, but its trigger is removing the bucket
            // the card stands in (Stage 81), not a button on the card — so a live card's one destructive answer is
            // the delete. Since **Stage 106** no page shows the archived rows; the routes that restore or destroy
            // one are still there.
            if (state.canEditAll) taskDeleteForm(task, back, projectId)
        }
    }
}

/**
 * Management's **Delete task** — the card, its plan and its log, gone.
 *
 * The route has been here since Stage 17 (`POST /app/assigned-tasks/delete`, [mn.murray.pts.service.TaskService.deleteAssigned])
 * and has always refused anybody but management; what it never had was a control pointing at it, because
 * Stage 35 took the card menu out and the popup was never the menu. This is that control, and it is drawn on
 * [TaskForm.canEditAll] — which is management's flag and the same line the route checks, so nobody is ever
 * offered a button that would do nothing. A project's own manager is not management (see [TaskForm]), and
 * neither is the person a card belongs to.
 *
 * It posts no `edit`, unlike every other form in this panel: those come back as the popup still open on the
 * card, and there is no card to be open on here. `back` and `projectId` are server-rendered so the board the
 * reader was looking at is the board they land on. *(**Stage 40** sent `allbuckets` beside them for the same
 * reason; **Stage 97** retired the wide view, and a narrowed backlog rides `back` as `other:<id>` instead.)
 * The confirm names the card, because the one thing a delete cannot be is a misclick.
 *
 * **Since Stage 84 this is every card's button again.** Stage 81 had replaced it on a project card with the
 * **Archive task** button (`/app/tasks/archive`); Stage 84 took that button off the popup at the user's request and
 * put the delete back in its place, in both worlds. The page still archives — removing a bucket takes its cards off
 * the board with it (Stage 81) — and since **Stage 106** no page shows archived cards, though the routes that
 * restore or destroy one remain; what a live project card offers is the one destructive answer it offered before
 * Stage 81. The right is unchanged and still management's alone: a project's own manager is not management, and
 * neither is the card's assignee.
 */
private fun kotlinx.html.DIV.taskDeleteForm(task: TaskItem, back: String, projectId: Int) {
    form(action = "/app/assigned-tasks/delete", method = FormMethod.post, classes = "opener-form task-del") {
        input(type = InputType.hidden, name = "taskId") { value = "${task.id}" }
        input(type = InputType.hidden, name = "back") { value = back }
        input(type = InputType.hidden, name = "projectId") { value = "$projectId" }
        button(type = ButtonType.submit, classes = "ghost-btn btn-sm danger") {
            attributes["data-confirm"] =
                "Delete ${task.title}? Its steps and its notes go with it. This cannot be undone."
            +"Delete task"
        }
    }
}

/** The name behind a note, or its employee number once the account is no longer on the roster. */
private fun noteAuthor(employeeNumber: String): String = memberName(employeeNumber)

/**
 * The person behind an employee number, or the number once the account is off the roster — a name for a
 * sentence, where [memberLabel] is a line for a picker.
 */
private fun memberName(employeeNumber: String): String =
    DemoData.team.firstOrNull { it.employeeNumber == employeeNumber }?.name ?: employeeNumber

/**
 * Two employee numbers as the sentence that names them: *Bat-Erdene* for one owner, *Bat-Erdene and
 * Sarnai* for a card with a second person (Stage 122). Blanks drop out, so the same call speaks for
 * every card.
 */
private fun memberNames(vararg employeeNumbers: String): String =
    employeeNumbers.filter { it.isNotBlank() }.map(::memberName).joinToString(" and ")

/** The roster line a picker shows for one person, in the same shape [optGroupPerMember] uses. */
private fun memberLabel(employeeNumber: String): String {
    val member = DemoData.team.firstOrNull { it.employeeNumber == employeeNumber }
        ?: return employeeNumber.ifBlank { "Nobody yet" }
    return "${member.employeeNumber} · ${member.name} (${member.position})"
}

/**
 * The single option a greyed picker carries. A field nobody may change does not need the list of
 * everything it could have been: its owner is told what the card says, and the rest of the roster and
 * the other projects stay on management's form. That matters for the hidden placeholder a normal
 * account's page ships so `?edit=` has a node to swap into — it is a landing point, not a directory.
 */
private fun kotlinx.html.SELECT.onlyOption(optionValue: String, optionLabel: String) {
    option {
        value = optionValue
        selected = true
        +optionLabel
    }
}

/**
 * The popup state a rejected [result] has to be re-rendered from. [task] is the card being edited, so
 * its steps and notes survive the round trip, [canEditAll] is the role the popup is drawn for, and
 * [viewerEmpNo] is the account looking at it — which is what keeps a refused save from turning the
 * assignee's own tick boxes into a stranger's read-only list.
 *
 * [backlogColumns] is the Other tasks board's column list, and a refused write to a backlog card has to come
 * back with it or its own popup loses the picker the reader was just using (Stage 42). Since Stage 48
 * management's copy arrives even for a project card, because their popup can send that card onto that board and
 * the column has to be chosen in the same form; which popup *is* a backlog's is [backlog]'s answer, not the
 * list's. [lanes] and the columns are both filled on a management popup and one on anybody else's.
 *
 * [lanesByProject] is the refused general-board create's own answer (Stage 111): the reader picked a project
 * inside this popup, so the re-render needs every board's column list again or the Sub-category picker they are
 * being refused on falls back to a list that has nothing to do with the project they chose.
 */
fun taskFormOf(
    taskId: Int,
    result: TaskSaveResult,
    task: TaskItem? = null,
    canEditAll: Boolean = true,
    canCreate: Boolean = false,
    lanes: List<ProjectBucket> = emptyList(),
    viewerEmpNo: String = "",
    backlogColumns: List<Bucket> = emptyList(),
    backlog: Boolean = false,
    lanesByProject: Map<String, List<String>> = emptyMap(),
): TaskForm =
    TaskForm(
        taskId = taskId,
        values = result.values,
        errors = result.errors,
        canEditAll = canEditAll,
        canCreate = canCreate,
        task = task,
        lanes = lanes,
        lanesByProject = lanesByProject,
        viewerEmpNo = viewerEmpNo,
        backlogColumns = backlogColumns,
        backlog = backlog,
    )

/**
 * The popup state an `?edit=` page is rendered from: the card as the database holds it, open, with the
 * caller's rights. [canEditAll] is management's, [viewerEmpNo] the caller's own number, and everything
 * else follows from the row.
 *
 * [backlogColumns] is passed for a card of the Other tasks board, because that is the popup that has a Column
 * picker to fill, and for management's copy of any card, because their "where it lives" picker can put one on
 * that board and the column has to be chosen in the same form. The row decides which picker opens visible.
 */
fun editFormOf(
    task: TaskItem,
    canEditAll: Boolean,
    viewerEmpNo: String = "",
    backlogColumns: List<Bucket> = emptyList(),
): TaskForm = TaskForm(
    taskId = task.id,
    values = TaskDraft(
        title = task.title,
        projectName = task.project,
        assigneeEmpNo = task.assignee,
        assignee2EmpNo = task.assignee2,
        started = task.started,
        due = task.due,
        priority = task.priority,
        status = task.status,
        // Which board the card lives on, so the "where it lives" picker opens on the answer the row already
        // gives rather than on the first option (Stage 48). It is a rendering default only: the service reads
        // the posted field, and takes the row's own value for a role that may not move a card between worlds.
        board = if (task.isBacklog) BOARD_OTHER else BOARD_PROJECT,
        // The sub-category the card is filed under, by name, so the picker opens where the card actually sits.
        // Blank is null here: a row with no lane is a row the picker has to say so about, not one to select.
        projectLane = task.projectBucket.trim().ifBlank { null },
        // The same for a backlog card's column (Stage 42), and there 0 is not a column but the absence of
        // one — a row that has never been filed. Null, so the picker falls back to the first column rather
        // than selecting an id no column owns.
        columnId = task.bucketId.takeIf { it != 0 },
    ),
    open = true,
    canEditAll = canEditAll,
    task = task,
    viewerEmpNo = viewerEmpNo,
    backlogColumns = backlogColumns,
)

/**
 * The popup state `?new=1` asks for: this page's blank create form, up open.
 *
 * [canManage] is the whole answer for a blank card. Management's form keeps every field and the full
 * project picker, because they may place a card anywhere. Everybody else who reaches this form is the
 * account a project names as its manager — see [mn.murray.pts.service.TaskService.mayAddTasks] — so they
 * get [TaskForm.canCreate] and, with it, [TaskForm.projectPinned]: the project they write for is the page
 * they came from, not a picker they can be wrong in. Nobody else is handed an open blank form at all.
 *
 * [into] is the lane a `+ Add task` line was pressed under, as its **name**: the route resolves the id the line
 * posts against this project's lanes and hands the word here, which is what the draft carries and the picker
 * selects (Stage 48). With it absent a create takes the board's first sub-category, as it always did — there is
 * no unfiled answer for a new card to land on.
 */
fun newFormOf(canManage: Boolean = true, into: String? = null): TaskForm =
    TaskForm(
        open = true,
        canEditAll = canManage,
        canCreate = !canManage,
        values = if (into == null) TaskDraft() else TaskDraft(projectLane = into),
    )

/**
 * The popup state a backlog `+ Add task` line asks for (Stage 42) — the same blank create form, aimed at a
 * column of the Other tasks board instead of at a project.
 *
 * Every signed-in account is handed it, which is the one place the Other tasks board is wider than an
 * assigned one: [mn.murray.pts.service.TaskService.createBacklog] lets an ordinary account write a card, and
 * pins it to them as Not started. [viewerEmpNo] is what the greyed Assign-to picker shows as the answer, and
 * the pre-filled status is what the greyed Status picker shows; neither is read from the post, so both exist
 * purely so a field the reader cannot touch still tells the truth.
 *
 * [into] is the column whose line was pressed, checked against [columns] by the route before it gets here.
 * With none it opens on the first column, because unlike a lane board the backlog has no Unsorted column for
 * an unplaced card to land in.
 */
fun backlogFormOf(
    canManage: Boolean,
    viewerEmpNo: String,
    columns: List<Bucket>,
    into: Int? = null,
): TaskForm = TaskForm(
    open = true,
    canEditAll = canManage,
    canCreate = true,
    backlog = true,
    backlogColumns = columns,
    viewerEmpNo = viewerEmpNo,
    values = TaskDraft(
        assigneeEmpNo = if (canManage) "" else viewerEmpNo,
        status = if (canManage) "" else "Not started",
        columnId = into ?: columns.firstOrNull()?.id,
        // The picker this form prints, so `boardAnswer` and the fields that follow it read as the backlog's
        // without the row that would otherwise say so.
        board = BOARD_OTHER,
    ),
)

// --- Card formatting helpers -------------------------------------------------------------
/* File-private, like the same little formatters in Team.kt and Settings.kt: every view file
   owns its own copy, so adding a shared one here could not resolve against those. */

private fun taskCode(id: Int): String = "T-${id.toString().padStart(5, '0')}"

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatDue(iso: String): String {
    val p = iso.split("-")
    if (p.size != 3) return iso
    val m = p[1].toIntOrNull() ?: return iso
    val d = p[2].toIntOrNull() ?: return iso
    return "%02d %s %s".format(d, MONTHS[m - 1], p[0])
}

/** The column dot's color. internal because the assigned-task list dots each card with its own status. */
internal fun statusDot(status: String): String = when (status) {
    "In progress" -> "d-amber"
    "On hold" -> "d-orange"
    "Blocked" -> "d-red"
    "Completed" -> "d-green"
    else -> "d-gray"
}

/**
 * The card's tint for a status. internal because the backlog board paints its cards with it too (Stage 95),
 * exactly as [statusDot] is shared with the dots that name the same five hues: one mapping, so a stage cannot
 * be amber on one board and grey on another.
 */
internal fun statusSlug(status: String): String = when (status) {
    "In progress" -> "s-inprogress"
    "On hold" -> "s-onhold"
    "Blocked" -> "s-blocked"
    "Completed" -> "s-completed"
    else -> "s-notstarted"
}

private fun prioClass(priority: String): String = when (priority) {
    "High" -> "p-high"
    "Medium" -> "p-med"
    else -> "p-low"
}
