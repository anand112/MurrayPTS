package mn.murray.pts
import mn.murray.pts.domain.*
import mn.murray.pts.service.LessonBoard
import mn.murray.pts.service.LessonDraft
import mn.murray.pts.service.LessonField
import mn.murray.pts.service.LessonFilter
import mn.murray.pts.service.LessonResult
import mn.murray.pts.service.LessonService

import io.ktor.http.Parameters
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FORM
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.SELECT
import kotlinx.html.TBODY
import kotlinx.html.TD
import kotlinx.html.TH
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.col
import kotlinx.html.colGroup
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.optGroup
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The Lesson learnt sheet (V9): one row per lesson, read as the company's own list.
 *
 * The picture this page is built to is a sheet of who learned what, on which project, and whether
 * anybody has talked about it yet. Three of its columns are links to records that already exist —
 * the person, the project, the card — and one is the only free text on the row, which is why the
 * popup that writes a lesson asks for a project and a card rather than two more things to type.
 *
 * How the page updates, in the same shape the forecast sheet uses:
 *
 *  * the two pickers are one GET form onto this route and everything under them is a live region
 *    (`app.js`), so choosing a project fetches and swaps the sheet — the toolbar is never replaced.
 *  * the date column's header is a `<button name="sort">` **inside** the region that points back at
 *    that form with `form="llFilter"`, so the order flips in place and the pickers ride with it.
 *  * each row's three buttons are forms of their own. Ticking a lesson swaps only that row;
 *    archiving or deleting one swaps the whole sheet, because the row is changing list — and the
 *    "N active, M archived" line beside the pickers swaps with it, from the same response.
 *  * the popup opens by URL (`?new=1`, `?edit=<id>`) and is a block like the backlog's, so a reader
 *    with scripts switched off still gets the same form on a fresh page load.
 *
 * [form] is the popup as this page should draw it: closed on an ordinary read, up on an opener, and
 * up with the typed words and the field-keyed reasons when a save was refused.
 */
fun DIV.lessonsContent(
    board: LessonBoard,
    filter: LessonFilter,
    form: LessonForm,
    projects: List<Project>,
    tasks: List<LessonTaskChoice>,
    employees: List<Member>,
) {
    lessonToolbar(filter, projects, employees, board)
    div("board-results") {
        id = "ll-board"
        attributes["data-live-region"] = "true"
        if (board.isEmpty) {
            lessonEmpty(filter)
        } else {
            if (board.active.isNotEmpty()) lessonTable(board.active, filter)
            lessonArchived(board, filter)
        }
    }
    lessonModal(form, projects, tasks, filter)
    script(src = "/static/js/lesson-modal.js") {}
}

/**
 * The two pickers, the count and the add control, on one line above the sheet.
 *
 * Two forms, not one: the pickers are a GET form onto this page (so a choice is an address that can
 * be bookmarked and reloaded), while `+ Add lesson` is a block form of its own that opens the popup.
 * A form cannot be nested inside a form — the browser would drop the inner one and the add button
 * would turn into a page load — so they are siblings and the toolbar's flex layout is what puts them
 * on the same line the picture shows.
 *
 * The count line is outside the live region because it is the toolbar's, not the sheet's, and it is
 * named as a companion (`data-live-extra`) of both kinds of send: the pickers change it as much as a
 * row being archived does, and a "3 active" beside a board that filtered down to five rows is the
 * half-updated page this mechanism exists to prevent.
 */
private fun DIV.lessonToolbar(filter: LessonFilter, projects: List<Project>, employees: List<Member>, board: LessonBoard) {
    div("board-toolbar ll-toolbar") {
        form(action = "/app/lessons", method = FormMethod.get, classes = "ll-pickers") {
            id = "llFilter"
            attributes["data-live-extra"] = "#ll-counts"
            label("board-key") {
                attributes["aria-label"] = "Project"
                select {
                    name = "project"
                    attributes["data-autosubmit"] = "true"
                    filterOption("0", "All projects", "${filter.project}")
                    projects.forEach { filterOption("${it.id}", "${it.code} · ${it.name}", "${filter.project}") }
                }
            }
            label("board-key") {
                attributes["aria-label"] = "Employee"
                select {
                    name = "employee"
                    attributes["data-autosubmit"] = "true"
                    filterOption("", "All employees", filter.employee)
                    employees.forEach { filterOption(it.employeeNumber, it.name, filter.employee) }
                }
            }
            // Clearing the two pickers is the reader's whole question, so the address it loads keeps the
            // order they set and the archive they had open: a control that widened the list by also
            // jumping it back to newest-first and folding the half they are reading would be two answers
            // to one press.
            if (filter.isActive) {
                a(classes = "board-reset", href = filter.copy(project = 0, employee = "").queryFor()) { +"Clear" }
            }
            // Only the two view keys the form does not already hold as fields ride along. The pickers
            // answer for `project` and `employee` themselves, and a duplicate name in a GET form is a
            // address that states the same choice twice — `?project=3&project=5`. The order and the open
            // half of the sheet have no control of their own in here, so they would otherwise be lost by
            // the first picker change: a sheet sorted oldest-first and showing its archive must still be
            // that sheet after the reader picks a project, not the default view with a row missing.
            if (filter.oldestFirst) input(type = InputType.hidden, name = "sort") { value = "old" }
            if (filter.showArchived) input(type = InputType.hidden, name = "archived") { value = "1" }
        }
        div("ll-tail") {
            span("ll-counts") {
                id = "ll-counts"
                +"${board.activeCount} active, ${board.archivedCount} archived"
            }
            addLessonForm(filter)
        }
    }
}

private fun SELECT.filterOption(value: String, label: String, chosen: String) {
    option {
        this.value = value
        selected = value == chosen
        +label
    }
}

/**
 * `+ Add lesson`: the opener for the create popup.
 *
 * It is a GET form onto this page carrying `new=1`, and `app.js` answers it by fetching that URL and
 * swapping only `#lessonModal` — [mn.murray.pts.service.LessonService.save]'s author rule means every
 * signed-in account may write their own lesson, so this is drawn for everybody and the route re-checks.
 */
private fun DIV.addLessonForm(filter: LessonFilter) {
    form(action = "/app/lessons", method = FormMethod.get, classes = "opener-form") {
        attributes["data-live-target"] = "#lessonModal"
        input(type = InputType.hidden, name = "new") { value = "1" }
        viewCarriage(filter)
        button(type = ButtonType.submit, classes = "ghost-btn btn-sm") { +"+ Add lesson" }
    }
}

/**
 * The pickers, the order and the open half of the sheet, carried into a GET form that is not the
 * toolbar's.
 *
 * A row write or an opener that dropped them would answer on a different sheet than the one the
 * reader is standing on: the row they just touched could move out from under them, or the popup
 * could close onto the whole list. These are plain keys because a GET form's fields *are* this
 * page's address — the toolbar's own pickers post under the same names.
 */
private fun FORM.viewCarriage(filter: LessonFilter) {
    if (filter.project != 0) input(type = InputType.hidden, name = "project") { value = "${filter.project}" }
    if (filter.employee.isNotEmpty()) input(type = InputType.hidden, name = "employee") { value = filter.employee }
    if (filter.oldestFirst) input(type = InputType.hidden, name = "sort") { value = "old" }
    if (filter.showArchived) input(type = InputType.hidden, name = "archived") { value = "1" }
}

/**
 * The same view for a form that writes: the four keys under `board_*`, as the forecast sheet carries
 * its toolbar.
 *
 * The prefix is not decoration here. A row's own form posts an `archived` state and a lesson's form
 * posts the `project` it is about — the same two names the sheet uses for which rows to show and
 * which project to show them under. Un-prefixed, a restore pressed while the archived list is open
 * would arrive stating the state twice, and the popup would answer with the row filed back in the
 * archive it was being brought out of.
 */
private fun FORM.boardCarriage(filter: LessonFilter) {
    if (filter.project != 0) input(type = InputType.hidden, name = "board_project") { value = "${filter.project}" }
    if (filter.employee.isNotEmpty()) input(type = InputType.hidden, name = "board_employee") { value = filter.employee }
    if (filter.oldestFirst) input(type = InputType.hidden, name = "board_sort") { value = "old" }
    if (filter.showArchived) input(type = InputType.hidden, name = "board_archived") { value = "1" }
}

/** The active list: the seven columns the sheet is read by. */
private fun DIV.lessonTable(rows: List<LessonEntry>, filter: LessonFilter) {
    table("grid lessons-table") {
        colGroup { LL_COLUMNS.forEach { col(classes = it) } }
        thead {
            tr {
                th(classes = "l-date") { sortButton(filter) }
                th(classes = "l-employee") { +"Employee" }
                th(classes = "l-project") { +"Project #" }
                th(classes = "l-task") { +"Task" }
                th(classes = "l-text") { +"Lesson learnt" }
                th(classes = "l-status") { +"Status" }
                th(classes = "l-actions") { }
            }
        }
        tbody { rows.forEach { lessonRow(it, filter) } }
    }
}

/**
 * The date column's header, which is also the control that reverses it.
 *
 * Its value is the order being *asked for*, not the one on screen — the same absolute-value rule the
 * forecast sheet's month controls follow, so a reload cannot flip the sheet back again.
 */
private fun TH.sortButton(filter: LessonFilter) {
    button(type = ButtonType.submit, classes = "ll-sort") {
        attributes["form"] = "llFilter"
        name = "sort"
        value = filter.sortKey
        attributes["title"] = if (filter.oldestFirst) "Show the newest lesson first" else "Show the oldest lesson first"
        +"Date"
        span("ll-sort-glyph") { +if (filter.oldestFirst) "▲" else "▼" }
    }
}

private fun TBODY.lessonRow(entry: LessonEntry, filter: LessonFilter) {
    tr("ll-row") {
        id = "ll-row-${entry.id}"
        td("l-date") { +dayLabel(entry.loggedOn) }
        td("l-employee") { lessonEmployee(entry) }
        td("l-project") {
            if (entry.projectId != 0) {
                a(href = "/app/projects/${entry.projectId}", classes = "row-link") {
                    span("code") { +entry.projectCode }
                }
            } else {
                span("ll-none") { +"—" }
            }
        }
        td("l-task") {
            if (entry.taskId != 0) {
                span("ll-task") { +entry.taskTitle }
            } else {
                span("ll-none") { +"—" }
            }
        }
        td("l-text") { lessonText(entry, filter) }
        td("l-status") {
            // The dot is drawn by the stylesheet from these two class names, and they are named apart
            // from the app's status chips (`.chip.done` is a *card* state, in blue) because "discussed"
            // is a fact about a lesson and not about a project.
            span("chip ${if (entry.discussed) "ll-discussed" else "ll-undiscussed"}") { +entry.status }
        }
        td("l-actions") { lessonActions(entry, filter) }
    }
}

/** The person who wrote it down: the same avatar the cards use, coloured per account. */
private fun TD.lessonEmployee(entry: LessonEntry) {
    if (entry.employeeName.isEmpty()) {
        span("ll-none") { +"—" }
        return
    }
    span("ll-person") {
        span("avatar sm ${avatarTint(entry.employeeNumber)}") { +initials(entry.employeeName) }
        span("ll-person-name") { +entry.employeeName }
    }
}

/**
 * The lesson itself, behind the click that opens it.
 *
 * The cell truncates because the sheet is read down its rows, and the full text is one click away
 * in the popup — which is also how the author edits it. A reader who may not write this row gets the
 * same popup with its fields disabled, so the click is never a dead end and never a false promise.
 */
private fun TD.lessonText(entry: LessonEntry, filter: LessonFilter) {
    form(action = "/app/lessons", method = FormMethod.get, classes = "opener-form ll-text-form") {
        attributes["data-live-target"] = "#lessonModal"
        input(type = InputType.hidden, name = "edit") { value = "${entry.id}" }
        viewCarriage(filter)
        button(type = ButtonType.submit, classes = "ll-open") {
            attributes["title"] = entry.text
            +entry.text
        }
    }
}

/**
 * The row's three buttons, for the reader who may write it: the discussion state, the bin, and the
 * archive. An archived row gets the archive back out and the bin, and nothing else — a lesson that
 * has been put away is not also being discussed.
 *
 * Nobody else sees any of them. That is presentation of a right and not the right itself:
 * [LessonService] decides again on every one of these routes.
 */
private fun TD.lessonActions(entry: LessonEntry, filter: LessonFilter) {
    if (!entry.mayEdit) return
    div("icon-btns") {
        if (!entry.archived) {
            rowForm(
                id = entry.id,
                action = "/app/lessons/status",
                // A row that only changes state stays exactly where it is, so the send replaces that
                // one `<tr>` and the reader's place on the sheet is untouched.
                target = "#ll-row-${entry.id}",
                extra = null,
                filter = filter,
                glyph = if (entry.discussed) "↺" else "✓",
                hint = if (entry.discussed) "Mark it not discussed" else "Mark it discussed",
                fields = {
                    input(type = InputType.hidden, name = "discussed") { value = if (entry.discussed) "0" else "1" }
                },
            )
        }
        rowForm(
            id = entry.id,
            action = "/app/lessons/delete",
            target = "#ll-board",
            extra = "#ll-counts",
            filter = filter,
            glyph = "🗑",
            hint = "Delete this lesson",
            confirm = "Delete this lesson? It cannot be brought back.",
            fields = {},
        )
        rowForm(
            id = entry.id,
            action = "/app/lessons/archive",
            target = "#ll-board",
            extra = "#ll-counts",
            filter = filter,
            glyph = if (entry.archived) "↩" else "⏸",
            hint = if (entry.archived) "Restore this lesson" else "Archive this lesson",
            fields = {
                input(type = InputType.hidden, name = "archived") { value = if (entry.archived) "0" else "1" }
            },
        )
    }
}

/**
 * One of a row's writes: a form with a hidden id, the view it is answering, and one button.
 *
 * [target] is the block the response replaces — the row when nothing moves, the whole sheet when a
 * row arrives on it or leaves it — and [extra] the count line beside the pickers that a row leaving
 * the active list also changes.
 */
private fun DIV.rowForm(
    id: Int,
    action: String,
    target: String,
    extra: String?,
    filter: LessonFilter,
    glyph: String,
    hint: String,
    confirm: String? = null,
    fields: FORM.() -> Unit,
) {
    form(action = action, method = FormMethod.post, classes = "ll-action") {
        attributes["data-live-target"] = target
        if (extra != null) attributes["data-live-extra"] = extra
        input(type = InputType.hidden, name = "id") { value = "$id" }
        boardCarriage(filter)
        fields()
        button(type = ButtonType.submit, classes = "icon-btn") {
            attributes["title"] = hint
            attributes["aria-label"] = hint
            if (confirm != null) attributes["data-confirm"] = confirm
            +glyph
        }
    }
}

/**
 * The Archived heading and, when it is open, the rows underneath it.
 *
 * The heading is always printed, because the picture prints it with nothing in the list: it is where
 * archived lessons live, and a sheet that only admits the place exists once something has been put
 * in it reads as a sheet with a hidden half. What is *not* printed when the list is empty is a
 * second table of nothing — the toggle says so in words instead.
 *
 * The heading is a button of the toolbar's form rather than a form of its own (`form="llFilter"`, the
 * same arrangement the date column's control and the forecast sheet's month controls use). That is
 * what lets it live inside the live region it re-renders without nesting a form in a form, it puts
 * `?archived=1` in the address bar like any other view key, and the pickers ride along because they
 * are that form's own fields.
 */
private fun DIV.lessonArchived(board: LessonBoard, filter: LessonFilter) {
    div("ll-archived") {
        button(type = ButtonType.submit, classes = "ll-arch-toggle") {
            attributes["form"] = "llFilter"
            name = "archived"
            value = if (filter.showArchived) "0" else "1"
            attributes["aria-expanded"] = filter.showArchived.toString()
            attributes["title"] = if (filter.showArchived) "Hide the archived lessons" else "Show the archived lessons"
            span("ll-arch-caret") { +if (filter.showArchived) "▾" else "▸" }
            +"Archived"
            span("pill") { +"${board.archivedCount}" }
        }
        if (filter.showArchived) {
            if (board.archived.isEmpty()) {
                p("task-meta") { +"Nothing has been archived." }
            } else {
                lessonTable(board.archived, filter)
            }
        }
    }
}

/** The empty sheet, and the two different reasons there is nothing on it. */
private fun DIV.lessonEmpty(filter: LessonFilter) {
    div("board-empty") {
        if (filter.isActive) {
            p("board-empty-line") { +"No lesson matches this project and employee." }
            p("board-empty-sub") {
                +"A lesson is only listed once — here, under the person who wrote it down."
            }
            a(classes = "row-link", href = "/app/lessons") { +"Show every lesson" }
        } else {
            p("board-empty-line") { +"Nothing has been logged yet." }
            p("board-empty-sub") {
                +("A lesson is what one person learned on a job — the thing worth telling the next " +
                    "crew — and it goes on this sheet under their own name.")
            }
            addLessonForm(filter)
        }
    }
}

/**
 * What the popup holds: the lesson being written, the field each refusal belongs under, and whether
 * it is up at all.
 *
 * [id] is 0 for a new lesson. [readOnly] is the popup opened on somebody else's row: the same page,
 * its text printed plainly and its Save button gone, because the click that opened it promised the
 * lesson, not an edit. [projectCode], [projectName] and [taskLabel] are what that half names instead
 * of pickers — the writable half gets its lists from the routes' own [Project] and card rows.
 */
data class LessonForm(
    val id: Int = 0,
    val typed: String = "",
    val projectId: Int = 0,
    val projectCode: String = "",
    val projectName: String = "",
    val taskId: Int = 0,
    val taskLabel: String = "",
    val loggedOn: String = "",
    val employeeName: String = "",
    val errors: Map<LessonField, String> = emptyMap(),
    val open: Boolean = false,
    val readOnly: Boolean = false,
) {
    val isEdit: Boolean get() = id != 0
    val isOpen: Boolean get() = open || isEdit || errors.isNotEmpty()
    fun error(field: LessonField): String? = errors[field]
}

/**
 * The popup behind `+ Add lesson` and a click on a lesson's text.
 *
 * One node for both, for the reason the backlog's bucket popup gives: the page can only swap a block
 * that has one id. The save names `#ll-board` as its target and the popup as its companion, so a
 * write that landed comes back as a sheet with the new row on it and a closed form, and one that was
 * refused comes back with the form still up and the words still in it.
 */
private fun DIV.lessonModal(form: LessonForm, projects: List<Project>, tasks: List<LessonTaskChoice>, filter: LessonFilter) {
    div("modal") {
        id = "lessonModal"
        if (form.isOpen) attributes["style"] = "display:block"
        div("modal-content modal-wide") {
            span("close") {
                attributes["data-lesson-modal-close"] = "true"
                +"×"
            }
            h3 { +if (form.isEdit) "Lesson learnt" else "Add a lesson" }
            p("modal-scope") {
                +(
                    if (form.readOnly) {
                        "Logged by ${form.employeeName.ifEmpty { "a former account" }}. Only its author and " +
                            "management can change what a lesson says."
                    } else if (form.isEdit) {
                        "The lesson stays under the name it was logged with, whoever edits it."
                    } else {
                        "It goes on the sheet under your name, dated today unless you say otherwise, " +
                            "and anyone here can read it."
                    }
                    )
            }
            if (form.readOnly) lessonReadBody(form) else lessonWriteBody(form, projects, tasks, filter)
        }
    }
}

/** The full text, for a reader who may not write it. No form, so nothing can be posted from here. */
private fun DIV.lessonReadBody(form: LessonForm) {
    div("form-grid") {
        div("field full") {
            label { +"Lesson learnt" }
            p("ll-plain") { +form.typed.ifEmpty { "Nothing was written down." } }
        }
        div("field") {
            label { +"Employee" }
            p("ll-plain") { +form.employeeName.ifEmpty { "—" } }
        }
        div("field") {
            label { +"Date" }
            p("ll-plain") { +dayLabel(form.loggedOn) }
        }
        div("field") {
            label { +"Project" }
            p("ll-plain") { +form.projectLabel }
        }
        div("field") {
            label { +"Card" }
            p("ll-plain") { +form.taskLabel.ifEmpty { "—" } }
        }
    }
    div("modal-actions") {
        button(type = ButtonType.button, classes = "ghost-btn") {
            attributes["data-lesson-modal-close"] = "true"
            +"Close"
        }
    }
}

/** The four fields a lesson has: what, on which project, from which card, and when. */
private fun DIV.lessonWriteBody(
    form: LessonForm,
    projects: List<Project>,
    tasks: List<LessonTaskChoice>,
    filter: LessonFilter,
) {
    form(action = "/app/lessons/save", method = FormMethod.post) {
        attributes["data-live-target"] = "#ll-board"
        attributes["data-live-extra"] = "#ll-counts,#lessonModal"
        if (form.isEdit) input(type = InputType.hidden, name = "id") { value = "${form.id}" }
        boardCarriage(filter)
        div("form-grid") {
            div("field") {
                label { +"Project" }
                select {
                    name = "project"
                    id = "lessonProject"
                    option {
                        value = ""
                        selected = form.projectId == 0
                        +"— No project —"
                    }
                    projects.forEach {
                        option {
                            value = "${it.id}"
                            selected = it.id == form.projectId
                            +"${it.code} · ${it.name}"
                        }
                    }
                }
                form.error(LessonField.PROJECT)?.let { p("field-err") { +it } }
            }
            div("field") {
                label { +"Date" }
                // A plain field, not a `[data-autosubmit]` one: choosing a date is half of writing a
                // lesson, and a page that posted the moment it was picked would refuse on the still
                // empty text box underneath it.
                input(type = InputType.date, name = "on") {
                    id = "lessonDate"
                    value = form.loggedOn
                }
                form.error(LessonField.DATE)?.let { p("field-err") { +it } }
            }
            div("field full") {
                label { +"Card (optional)" }
                select {
                    name = "task"
                    id = "lessonTask"
                    option {
                        value = ""
                        selected = form.taskId == 0
                        +"— No card —"
                    }
                    // Grouped by project rather than filtered by the picker above: a popup that had to
                    // be re-rendered to fill its own second field would be a page reload wearing a mask.
                    tasks.groupBy { it.projectId }.values.forEach { group ->
                        optGroup {
                            // The cards under no project at all: the backlog's own, and a card whose project
                            // has since gone. `No project` is the same words the picker above uses for none.
                            label = group.first().projectCode.ifEmpty { "No project" }
                            group.forEach { choice ->
                                option {
                                    value = "${choice.id}"
                                    selected = choice.id == form.taskId
                                    +choice.title
                                }
                            }
                        }
                    }
                }
                form.error(LessonField.TASK)?.let { p("field-err") { +it } }
            }
            div("field full") {
                label { +"What was learned" }
                textArea {
                    name = "text"
                    id = "lessonText"
                    attributes["maxlength"] = "$LESSON_TEXT_MAX"
                    attributes["rows"] = "4"
                    +form.typed
                }
                form.error(LessonField.TEXT)?.let { p("field-err") { +it } }
                form.error(LessonField.ROW)?.let { p("field-err") { +it } }
                form.error(LessonField.PERMISSION)?.let { p("field-err") { +it } }
            }
        }
        div("modal-actions") {
            button(type = ButtonType.submit, classes = "submit") {
                +if (form.isEdit) "Save lesson" else "Add lesson"
            }
            button(type = ButtonType.button, classes = "ghost-btn") {
                attributes["data-lesson-modal-close"] = "true"
                +"Cancel"
            }
        }
    }
}

/** The sheet as the address bar states it — what a GET onto the page is reading. */
fun lessonFilterOf(query: Parameters): LessonFilter =
    LessonFilter.of(LESSON_VIEW_KEYS.associateWith { query[it] })

/**
 * The same sheet as a row's or the popup's form states it.
 *
 * The four keys arrive under `board_*` because the posting form's own fields are the lesson's: an
 * un-prefixed `archived` would be the state the row is being moved to and `project` the project the
 * lesson belongs to. [boardCarriage] writes them prefixed, so reading them back is only a question of
 * taking the prefix off — which is why this hands the plain map to the same [LessonFilter.of] a GET
 * uses and cannot drift from it.
 */
fun lessonFilterOfForm(form: Parameters): LessonFilter = LessonFilter.of(
    LESSON_VIEW_KEYS.associateWith { form["board_$it"] }
)

/** The popup as one of its forms states it: what was typed, and the row it is about. */
fun lessonDraftOf(form: Parameters): LessonDraft = LessonDraft.of(
    LESSON_FORM_KEYS.associateWith { form[it] }
)

private val LESSON_VIEW_KEYS = listOf("project", "employee", "sort", "archived")

private val LESSON_FORM_KEYS = listOf("id", "text", "project", "task", "on")

/**
 * The row `?edit=<id>` asks for, or null when the id is blank, unknown or a word.
 *
 * A hand-typed id is not a lesson: the route answers with the sheet and no popup rather than a page
 * of empty fields, because the reader who typed it is looking at the row they meant to open.
 */
fun lessonEntryOf(id: String?): LessonEntry? =
    id?.trim()?.toIntOrNull()?.let { LessonService.find(it) }

/** `M-01250 · Erdenet Crusher Modernisation`, the way the popup's read-only half names a project. */
private val LessonForm.projectLabel: String
    get() = listOf(projectCode, projectName).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "—" }

private fun dayLabel(iso: String): String {
    if (iso.length != 10) return if (iso.isEmpty()) "—" else iso
    val day = try {
        LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (e: Exception) {
        return iso
    }
    return DAY_FORMAT.format(day)
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * The avatar's colour and its two letters come from [mn.murray.pts.avatarTint] and
 * [mn.murray.pts.initials] — the same pair every other sheet in the app reads, so one person is one
 * colour and one abbreviation wherever they are listed.
 */

/** The seven columns of the sheet, in the order the header above prints them. */
private val LL_COLUMNS = listOf(
    "l-date", "l-employee", "l-project", "l-task", "l-text", "l-status", "l-actions",
)

/** The popup as a query asks for it: `?new=1` to write one, `?edit=<id>` to read or change one. */
fun lessonFormOf(query: Parameters, entry: LessonEntry?, mayEdit: Boolean): LessonForm {
    if (entry != null) {
        return LessonForm(
            id = entry.id,
            typed = entry.text,
            projectId = entry.projectId,
            projectCode = entry.projectCode,
            projectName = entry.projectName,
            taskId = entry.taskId,
            taskLabel = entry.taskTitle,
            loggedOn = entry.loggedOn,
            employeeName = entry.employeeName,
            open = true,
            readOnly = !mayEdit,
        )
    }
    return LessonForm(open = query["new"] == "1", loggedOn = todayKey())
}

/**
 * The popup a refused save comes back in: the typed words, the reasons, still open.
 *
 * Always the writable half. Only a reader with the right to write the row can reach a save at all,
 * and a refusal has to land under the field that caused it — which the read-only half does not draw.
 */
fun lessonFormOf(result: LessonResult): LessonForm = LessonForm(
    id = result.values.id,
    typed = result.values.text,
    projectId = result.values.projectId,
    taskId = result.values.taskId,
    loggedOn = result.values.loggedOn,
    errors = result.errors,
)

private fun todayKey(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
