package mn.murray.pts
import mn.murray.pts.domain.*
import mn.murray.pts.service.TaskDraft
import mn.murray.pts.service.TaskField
import mn.murray.pts.service.TaskService

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.TBODY
import kotlinx.html.TD
import kotlinx.html.TR
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.unsafe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The assign panel's answer, kept so a refused one can be printed back.
 *
 * The card popup has the same shape for the same reason ([TaskForm]): a redirect after a refusal would come
 * back to a blank panel, which tells the reader nothing about the words they just typed and leaves the reason
 * nowhere to print. So `/app/team/assign` hands back this page with [values] as they were posted and [errors]
 * keyed by field, and each field prints its own reason under itself.
 *
 * [values] is the plain, freshly-typed half of that: every field of a panel nobody has submitted yet answers
 * with its own default, and the picker below the *Where it lives* half is the one thing a default cannot
 * answer for — a card with no column named is a card management has not filed yet.
 */
data class AssignForm(
    val values: TaskDraft = TaskDraft(),
    val errors: Map<TaskField, String> = emptyMap(),
) {
    /**
     * Whether this panel is being filled as an **Other task**, which decides which of its two halves is up.
     *
     * It reads the answer the form itself carries, not a board the reader is standing on: there is no board
     * behind this panel, and the picker's default is the project half, so a form that posted nothing at the
     * field — an old bookmark, a hand-built POST — is the panel as it has always been rather than a third kind
     * of card. `null` and `"project"` are the same answer here, and neither is `BOARD_OTHER`.
     */
    val onBacklog: Boolean get() = values.board == BOARD_OTHER

    fun error(field: TaskField): String? = errors[field]
}

/** The Column picker's placeholder (Stage 50): the one answer this panel may print and must refuse. */
private const val CHOOSE_COLUMN = "— Choose a column —"

/**
 * The Team page.
 *
 * The roster — name, department, position, email — belongs to everybody: it is how a new site
 * engineer finds the person to call. Stage 68 extends that to how much work is *on* them: the
 * **Projects** figure, the **Tasks** count, the five status columns and **Total** print on every row for
 * every reader, because "is this person already carrying a lot?" is a question a colleague is entitled to ask
 * before they hand something over. What stays management's view is *which* work it is: the card-by-card
 * drill-down under a row and the task list inside the member popup come only on the reader's own row unless
 * they manage the sheets. So a figure the reader may not open below is printed as a plain number rather than
 * as the button that would open it — the count is shared, the list underneath it is not. The same
 * [mn.murray.pts.service.TaskService.assignedFor] rule as the board, applied per row.
 *
 * Every figure on a row is counted from the member's **cards**, from both boards: the cards filed on a project
 * and the ones that sit outside every project on *Other tasks* ([mn.murray.pts.service.TaskService.assignedOtherFor]).
 * A card with no project is still work that is on somebody, and a sheet that counted one kind and not the other
 * would understate the person doing it. The number on the **Tasks** button, the five status columns and **Total**
 * all come from the one list the row's own drill-down prints, so the figures cannot disagree with each other or
 * with what the reader sees when they open it.
 *
 * **Projects** is the one column that is not a card count, and it is the one set worth naming exactly: the
 * projects the member is *on*, which is the ones they manage **plus** the ones holding at least one of their
 * cards ([memberProjects]) — which is also what the member popup lists as that person's projects, for every
 * reader, so the figure and the list under it are the same set counted once. A backlog card belongs to a column
 * rather than a project, so it moves **Total** and not **Projects**, and the two figures are allowed to differ.
 *
 * The sheet is wrapped in `#teamSheet` for one reason: **the figures on it are only ever true for the moment the
 * page was drawn** (Stage 69). A card dragged on a board writes its new status to SQLite without revisiting this
 * page — sitting in the tab they came from, or waiting under the Back button — so it has no reason to ask
 * again, so it goes on printing the counts from before the move. `team.js` therefore re-pulls
 * this very URL and swaps this one panel when the page is restored from the browser's back/forward cache or
 * comes back on screen, which is what makes the numbers right without the reader pressing F5. The panel is the
 * whole of what changes, so nothing else on the page moves under them.
 *
 * The script covers a page that is *already open*. The other half is a page the browser never even asked the
 * server for: with no `Cache-Control` of its own, this HTML was fair game for Chrome's own cache, so pressing
 * Back handed the reader the sheet as it was drawn before the drag — a stale answer with no request in front of
 * it to intercept. [mn.murray.pts.web.plugin.isServerDrawnPage] is why every page here now goes out as
 * `Cache-Control: no-store`, so a revisit reads the database again and only a tab that never navigated at all
 * needs the re-count above.
 *
 * [assign] is the panel below the sheet in the state this answer needs: blank for an ordinary read, and
 * the typed words with the field-keyed reasons when a save was refused — the same contract the card popup
 * keeps, and the reason `/app/team/assign` answers a refusal with this page rather than with a redirect.
 *
 * **Stage 110 split the one flag into the two chips the page actually offers.** [canManage] is
 * *View teammates' tasks* — it draws the caption's "open the Tasks count" sentence, the drill-down under a row
 * and the popup's card list. [canAssign] is *Assign tasks to others*, and it is the only thing that draws the
 * `+` beside a name and the panel underneath the sheet, because writing a card onto somebody else's board is a
 * different permission from reading it. The default keeps the pre-110 shape — whoever could see the work could
 * put work on it — so an older call site, and every test written against this page, still reads the page it read.
 */
fun DIV.teamContent(
    canManage: Boolean,
    viewerEmpNo: String,
    assign: AssignForm = AssignForm(),
    canAssign: Boolean = canManage,
) {
    div("card-panel") {
        // The hook the re-count swaps. It holds the caption and the sheet and nothing else — not the member
        // popup's data island, which the script refreshes by itself, and not the assign panel below, where the
        // reader may be mid-sentence in a task title.
        id = "teamSheet"
        p("panel-caption") {
            if (canManage) {
                +"Tip: click a member for details, or open the Tasks count to see who is doing what."
                +" Use + next to it to assign them a task."
            } else {
                +"Every row shows how many projects and how much work that person is carrying."
                +" Click a member for the projects they are on, and open your own Tasks count to see what is assigned to you."
            }
        }
        table("grid team-table") {
            thead {
                tr {
                    th { +"ID" }
                    th { +"Name" }
                    th { +"Department" }
                    th { +"Position" }
                    th(classes = "num") { +"Projects" }
                    th(classes = "num") { +"Tasks" }
                    th(classes = "num") { +"In progress" }
                    th(classes = "num") { +"On hold" }
                    th(classes = "num") { +"Not started" }
                    th(classes = "num") { +"Blocked" }
                    th(classes = "num") { +"Completed" }
                    th(classes = "num") { +"Total" }
                    th(classes = "num") { attributes["aria-label"] = "Details"; +"" }
                }
            }
            tbody {
                teamGroupedByDepartment().forEach { (department, members) ->
                    departmentRow(department, members.size)
                    members.forEach { member ->
                        // Whose *cards* this row may list, as opposed to count: every figure below prints for
                        // every reader (Stage 68), and this flag decides only whether the drill-down that names
                        // each card is attached under the row.
                        val seesDetail = canManage || member.employeeNumber == viewerEmpNo
                        // Every card the member holds, project cards and backlog cards together — the same list
                        // the drill-down under this row prints. It is fetched once and every figure on the row is
                        // counted off it, so the button, the five columns and the total cannot disagree.
                        val tasks = memberTaskRows(member.employeeNumber)
                        val cards = tasks.map { it.first }
                        val open = cards.count { it.isOpen }
                        // The projects figure is a set of projects, not a count of cards, and it is the same call
                        // the popup makes below — so the number on the sheet is the list behind it, for anybody
                        // who opens it and for anybody who does not.
                        val projects = memberProjects(member.name, cards)
                        fun count(status: String) = cards.count { it.status == status }
                        tr {
                            attributes["data-emp"] = member.employeeNumber
                            td { span("code") { +member.employeeNumber } }
                            td {
                                div("team-name") {
                                    span("avatar sm") { +initials(member.name) }
                                    div("assignee-meta") {
                                        span("pname") { +member.name }
                                        div("assignee-id") { +memberEmail(member) }
                                    }
                                }
                            }
                            td {
                                if (member.department.isNotBlank()) span("chip dept-chip") { +member.department } else +member.department
                            }
                            td { +member.position }
                            td("num") { span("proj-count") { +"${projects.size}" } }
                            td("num") {
                                if (seesDetail) {
                                    button(type = ButtonType.button, classes = if (open == 0) "tasks-toggle zero" else "tasks-toggle") {
                                        attributes["data-emp"] = member.employeeNumber
                                        attributes["title"] = "Show ${member.name}'s tasks"
                                        +"$open"
                                    }
                                } else {
                                    // The same figure, without the control. There is no card list under this row
                                    // for this reader to open, and a button that opens nothing is read as the
                                    // page being broken rather than as the list being somebody else's.
                                    span(if (open == 0) "tasks-static zero" else "tasks-static") { +"$open" }
                                }
                                if (canAssign) {
                                    button(type = ButtonType.button, classes = "quick-add") {
                                        attributes["data-emp"] = member.employeeNumber
                                        attributes["title"] = "Assign a task to ${member.name}"
                                        +"+"
                                    }
                                }
                            }
                            counted("ontrack", count("In progress"))
                            counted("onhold", count("On hold"))
                            counted("planning", count("Not started"))
                            counted("atrisk", count("Blocked"))
                            counted("done", count("Completed"))
                            td("num") { span("count-total") { +"${cards.size}" } }
                            td("num") { span("mhint") { +"›" } }
                        }
                        if (seesDetail) memberTasksRow(member, tasks)
                    }
                }
            }
        }
    }

    if (canAssign) {
        val columns = TaskService.bucketRows()
        val v = assign.values
        div("card-panel") {
            id = "assignPanel"
            h3 { +"Assign a task to a team member" }
            form(action = "/app/team/assign", method = FormMethod.post) {
                div("form-grid") {
                    div("field") {
                        label { +"Team member" }
                        select {
                            id = "assignMember"
                            name = "assignee"
                            required = true
                            optGroupPerMember(selectedValue = v.assigneeEmpNo)
                        }
                        assign.error(TaskField.ASSIGNEE)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Task" }
                        input(type = InputType.text, name = "title") {
                            id = "assignTitle"
                            placeholder = "e.g. Prepare weekly progress report"
                            required = true
                            value = v.title
                        }
                        assign.error(TaskField.TITLE)?.let { p("field-err") { +it } }
                    }
                    div("field full") {
                        // Which of the two boards the card this panel writes belongs to (Stage 50). The same
                        // field, the same two answers and the same markers as the card popup's *where it lives*
                        // picker, so the one script that swaps the halves of a popup answers this panel too —
                        // and a task with no project is an Other task because the reader said so, not because
                        // they found a Project box to leave blank.
                        label { +"Where it lives" }
                        select {
                            name = "board"
                            id = "assignBoard"
                            attributes["data-board-toggle"] = "true"
                            option {
                                value = BOARD_PROJECT
                                selected = !assign.onBacklog
                                +"On a project"
                            }
                            option {
                                value = BOARD_OTHER
                                selected = assign.onBacklog
                                +"Other tasks"
                            }
                        }
                        span("field-hint") {
                            +(
                                "A task on no project is an Other task: it prints on the Other tasks board, " +
                                    "under the column chosen below."
                                )
                        }
                    }
                    div("field") {
                        attributes["data-board-half"] = "project"
                        if (assign.onBacklog) attributes["hidden"] = "hidden"
                        // Still no blank answer since Stage 48 — a card that names a project has to name one that
                        // exists — but the `required` the picker carried went with the half it belongs to (Stage
                        // 50): a *hidden* box is still validated by the browser, and this one is hidden whenever
                        // the reader is filling the Other tasks half. The rule is not the attribute's to keep
                        // anyway; [mn.murray.pts.service.TaskService.create] refuses a card that names no
                        // project, which is what answers a hand-built POST and a form with scripts off alike.
                        label { +"Project" }
                        select {
                            name = "project"
                            DemoData.projects.forEach { project ->
                                option {
                                    value = project.name
                                    selected = project.name == v.projectName
                                    +"${project.code} · ${project.name}"
                                }
                            }
                        }
                        assign.error(TaskField.PROJECT)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        attributes["data-board-half"] = "project"
                        if (assign.onBacklog) attributes["hidden"] = "hidden"
                        // Since Stage 100 a board's lanes are whatever Settings listed for its category, so this
                        // panel no longer has a universal trio to offer: it shows the chosen project's own lanes,
                        // in its board's order. Switching the Project box leaves the list as drawn — the page does
                        // not reload — and a name the new project does not carry is refused when the card is filed,
                        // because the name is resolved against the lanes that project actually has (Stage 75).
                        label { +"Sub-category" }
                        select {
                            name = "lane"
                            laneNamesFor(v.projectName).forEach { lane ->
                                option {
                                    value = lane
                                    selected = lane == v.projectLane
                                    +lane
                                }
                            }
                        }
                        assign.error(TaskField.LANE)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        attributes["data-board-half"] = "other"
                        if (!assign.onBacklog) attributes["hidden"] = "hidden"
                        label { +"Column" }
                        select {
                            name = "column"
                            id = "assignColumn"
                            // The one field of this half that cannot be left answered. A column is where the
                            // Other tasks board keeps a card, and a row with no `bucket_id` is on no column of
                            // it at all — work that has gone missing rather than work that has been filed — so
                            // management is asked to pick one and
                            // [mn.murray.pts.service.TaskService.createBacklog] refuses the card that does not.
                            // The blank below is there to be refused, not to be kept.
                            option {
                                value = ""
                                selected = v.columnId == null
                                +CHOOSE_COLUMN
                            }
                            columns.forEach { column ->
                                option {
                                    value = "${column.id}"
                                    selected = column.id == v.columnId
                                    +column.name
                                }
                            }
                        }
                        if (columns.isEmpty()) {
                            // Nothing to choose, and a line saying so, so an empty picker reads as a board that
                            // has no columns rather than as a box that lost its options.
                            span("field-hint") {
                                +"The Other tasks board has no columns yet, so there is nowhere to file it."
                            }
                        }
                        assign.error(TaskField.COLUMN)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Start date" }
                        input(type = InputType.date, name = "started") { value = v.started }
                        assign.error(TaskField.START)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Due date" }
                        input(type = InputType.date, name = "due") { value = v.due }
                        assign.error(TaskField.DUE)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Priority" }
                        select {
                            name = "priority"
                            TASK_PRIORITIES.forEach { priority ->
                                option {
                                    value = priority
                                    selected = priority == v.priority
                                    +priority
                                }
                            }
                        }
                        assign.error(TaskField.PRIORITY)?.let { p("field-err") { +it } }
                    }
                }
                button(type = ButtonType.submit, classes = "submit") { +"Assign task" }
            }
        }
        // The popup's own script, for one field of this panel: the *Where it lives* picker above swaps the two
        // halves of the form in place, exactly as it does inside a card's editor. It is the same contract —
        // `select[data-board-toggle]` over `div[data-board-half]`, hidden rather than styled, and the marker
        // attributes are what the server already set for the half this answer is on — so the panel gets the
        // behaviour without a second copy of it to keep in step. Everything else the script does is inert here:
        // this page prints no card to click and no `#taskModal` to close.
        script(src = "/static/js/task-modal.js") {}
    }
    memberDetailScript(canManage, viewerEmpNo)
}

private fun TD.statusCount(kind: String, n: Int) {
    span(if (n == 0) "count zero" else "count $kind") { +"$n" }
}

/**
 * The lanes the assign panel's Sub-category picker offers for the project named in its Project box (Stage 100).
 *
 * Before Stage 100 the answer was a constant: every board was created with the same three phases, so a page that
 * knew nothing about any project's lane ids could still offer them by name. Now a board carries whatever Settings
 * listed for its category, so the list has to be read from the board — the first project's when the box is still
 * unanswered, which is the same row the select has marked selected. A project with no lanes answers with nothing
 * rather than with a guess: the card could not be filed in any of them.
 */
private fun laneNamesFor(projectName: String): List<String> {
    val projects = DemoData.projects
    val project = projects.firstOrNull { it.name == projectName } ?: projects.firstOrNull() ?: return emptyList()
    return mn.murray.pts.repository.ProjectBucketRepository.ofProject(project.id).map { it.name }
}

/**
 * One counted column of a member's row: the figure off their cards.
 *
 * There is no rights flag here, which is the whole of Stage 68: every reader gets every row's counts, so the
 * flag the row carries decides only whether the card list under it is printed, never the number above it. The
 * dash this column used to be able to print is gone, and with it the ambiguity between "this person holds
 * nothing in that state" and "this row is not yours to read".
 */
private fun TR.counted(kind: String, n: Int) {
    td("num") { statusCount(kind, n) }
}

/**
 * The projects a member is *on*: the ones their name is set as the manager of, plus the ones holding at least
 * one of their cards.
 *
 * Two worlds, because a project can be a person's in either of them: the manager who has not written a card for
 * it yet is the one carrying it, and the engineer whose cards sit in four projects is carrying those. The row's
 * **Projects** figure and the popup's list of projects both come from this one call, which is what keeps a
 * number on the sheet from naming a different set than the list underneath it. A backlog card belongs to a
 * column rather than to a project, so it moves **Total** without moving this — the two figures are meant to be
 * able to differ, and a row of `3 projects, 9 cards` is a normal thing to read.
 */
private fun memberProjects(name: String, cards: List<TaskItem>): List<Project> {
    val onCards = cards.map { it.project }.filter { it.isNotBlank() }.toSet()
    return DemoData.projects.filter { it.manager == name || it.name in onCards }
}

/**
 * The label row above each department's people. It spans all thirteen columns so the numeric columns
 * stay in line beneath it, and it states the headcount rather than leaving anyone to count rows —
 * the roster runs to fourteen departments, and a plain sort would make each group's size invisible.
 */
private fun TBODY.departmentRow(department: String, people: Int) {
    tr("dept-row") {
        td {
            colSpan = "13"
            div("dept-cell") {
                span("dept-name") { +department }
                span("dept-count") { +"$people ${if (people == 1) "person" else "people"}" }
            }
        }
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatDue(iso: String): String {
    val parts = iso.split("-")
    if (parts.size != 3) return iso
    val m = parts[1].toIntOrNull() ?: return iso
    val d = parts[2].toIntOrNull() ?: return iso
    return "%02d %s %s".format(d, MONTHS[m - 1], parts[0])
}

private fun taskCode(id: Int): String = "T-${id.toString().padStart(5, '0')}"

private fun prioClass(priority: String): String = when (priority) {
    "High" -> "p-high"
    "Medium" -> "p-med"
    else -> "p-low"
}

private fun taskColor(status: String): String = when (status) {
    "In progress" -> "#e0a800"
    "On hold" -> "#e07000"
    "Blocked" -> "#d93025"
    "Completed" -> "#1e8e3e"
    else -> "#8a9199"
}

private fun projectContext(project: String): String {
    if (project.isBlank()) return ""
    val p = DemoData.projects.firstOrNull { it.name == project } ?: return project
    return "${p.code} · ${p.name}"
}

/** All of a member's tasks (project tasks plus personal ones), open first then by due date. */
private fun memberTaskRows(emp: String): List<Pair<TaskItem, Boolean>> =
    // A card with two people appears under each of them (Stage 122).
    (DemoData.assignedTasks.filter { it.assignedTo(emp) }.map { it to false } +
        DemoData.otherTasks.filter { it.assignedTo(emp) }.map { it to true })
        .sortedWith(
            compareBy(
                { (t, _) -> !t.isOpen },
                { (t, _) -> t.due.isEmpty() },
                { (t, _) -> t.due },
            )
        )

/**
 * Hidden table sub-row listing everything a member is doing, card by card; toggled from the Tasks count.
 *
 * It is printed only under a row this reader may open — management's, or their own — which is the half of the
 * page Stage 68 left gated. Above it the figures are on every row for everybody; this is the part that names
 * *what* the work is, and a colleague's task titles are not the roster's business.
 */
private fun TBODY.memberTasksRow(member: Member, tasks: List<Pair<TaskItem, Boolean>>) {
    tr("member-tasks") {
        attributes["data-emp"] = member.employeeNumber
        attributes["hidden"] = "hidden"
        td {
            colSpan = "13"
            if (tasks.isEmpty()) {
                span("mtd-empty") { +"No tasks assigned yet." }
            } else {
                tasks.forEach { (task, isOther) ->
                    div("mpop-row") {
                        div("mpop-main") {
                            div("mpop-title") {
                                if (!isOther && task.id > 0) span("tcode m") { +taskCode(task.id) }
                                if (isOther) span("other-tag") { +"other" }
                                +task.title
                            }
                            div("mpop-meta") {
                                span("mdot") { attributes["style"] = "background:${taskColor(task.status)}" }
                                +task.status
                                if (isOther) {
                                    if (task.bucket.isNotBlank()) span { +task.bucket }
                                } else if (task.project.isNotBlank()) {
                                    span { +projectContext(task.project) }
                                }
                                if (task.due.isNotBlank()) span { +"due ${formatDue(task.due)}" }
                                span("prio ${prioClass(task.priority)}") { +task.priority }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Member detail popup data ------------------------------------------------

@Serializable
private data class MemberProjectInfo(
    val code: String,
    val name: String,
    val status: String,
    val progress: Int,
    val due: String,
    val role: String,
)

@Serializable
private data class MemberTaskInfo(
    val id: Int,
    val title: String,
    val context: String,
    val status: String,
    val due: String,
    val priority: String,
    val other: Boolean = false,
)

@Serializable
private data class MemberDetail(
    val name: String,
    val position: String,
    val department: String,
    val email: String = "",
    val projects: List<MemberProjectInfo> = emptyList(),
    val tasks: List<MemberTaskInfo> = emptyList(),
)

private val detailJson = Json { encodeDefaults = true }

/**
 * One entry per team member: projects they manage or contribute tasks to, plus their tasks.
 *
 * The two halves of the payload are read under two different rights, and Stage 68 is what split them. The
 * **projects** are everybody's view of a colleague, because that set is what the sheet's **Projects** figure
 * now prints for every row — a number on the page that its own popup contradicts would be read as the page
 * being wrong, so the list is the same [memberProjects] call, whatever the reader may see of the cards that
 * feed it. The **tasks** stay the old rule: their own entry, and everybody's for management. A colleague
 * is still findable by name, department, position and email — which is what the page is for.
 */
private fun buildMemberDetails(canManage: Boolean, viewerEmpNo: String): Map<String, MemberDetail> {
    val projectByName = DemoData.projects.associateBy { it.name }
    // The percentage a popup prints for a project is the Projects board's figure for the same project —
    // one measurement, from the checklists on its cards, with the row's own number only where no card
    // carries a plan. Three pages naming one project three different numbers would be read as three bugs.
    val measured = TaskService.progressByProject()
    return DemoData.team.associate { member ->
        val seesTasks = canManage || member.employeeNumber == viewerEmpNo
        val assigned = if (seesTasks) DemoData.assignedTasks.filter { it.assignedTo(member.employeeNumber) } else emptyList()
        val other = if (seesTasks) DemoData.otherTasks.filter { it.assignedTo(member.employeeNumber) } else emptyList()
        // The projects they are on, for every reader and from the one helper the row uses. Only the *names*
        // travel here; the cards behind them are still the gated half, which is why this reads the member's
        // own project cards off the repository rather than reusing the gated [assigned] above.
        val theirCards = DemoData.assignedTasks.filter { it.assignedTo(member.employeeNumber) }
        val projectInfos = memberProjects(member.name, theirCards)
            .map {
                val role = if (it.manager == member.name) "Manager" else "Tasks"
                MemberProjectInfo(it.code, it.name, it.status, TaskService.displayedProgress(it, measured), it.due, role)
            }
            .sortedWith(compareBy({ it.role != "Manager" }, { it.name }))

        val taskInfos = (assigned.map { it to false } + other.map { it to true })
            .sortedWith(compareBy({ (t, _) -> t.due.isEmpty() }, { (t, _) -> t.due }))
            .map { (t, isOther) ->
                val context = if (isOther) t.bucket.ifEmpty { "Other" }
                else projectByName[t.project]?.let { "${it.code} · ${it.name}" } ?: t.project
                MemberTaskInfo(t.id, t.title, context, t.status, t.due, t.priority, isOther)
            }

        member.employeeNumber to MemberDetail(member.name, member.position, member.department, memberEmail(member), projectInfos, taskInfos)
    }
}

/** JSON for the popup, with "<" escaped so it is safe inside a script tag. */
private fun memberDetailJson(canManage: Boolean, viewerEmpNo: String): String =
    detailJson.encodeToString(buildMemberDetails(canManage, viewerEmpNo)).replace("<", "\\u003c")

/**
 * Team page member popup. The per-member payload stays a server-rendered JSON island —
 * a <script type="application/json"> tag is data, not executable, so a strict CSP allows
 * it inline — while the behaviour moved to /static/js/team.js (Stage 7), which reads the
 * island by id and wires the popup, per-row task toggles and quick-add shortcuts.
 */
private fun DIV.memberDetailScript(canManage: Boolean, viewerEmpNo: String) {
    unsafe {
        +("""<script type="application/json" id="memberDetailData">""" +
            memberDetailJson(canManage, viewerEmpNo) + "</script>")
    }
    script(src = "/static/js/team.js") {}
}
