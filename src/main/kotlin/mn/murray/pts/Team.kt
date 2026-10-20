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
 * engineer finds the person to call. *Who is doing what* is management's view, so a reader without
 * that access gets the task count and the drill-down only on their own row, and the member popup
 * carries nobody's task list but theirs. The same [mn.murray.pts.service.TaskService.assignedFor]
 * rule as the board, applied per row.
 *
 * [assign] is the panel below the sheet in the state this answer needs: blank for an ordinary read, and
 * the typed words with the field-keyed reasons when a save was refused — the same contract the card popup
 * keeps, and the reason `/app/team/assign` answers a refusal with this page rather than with a redirect.
 */
fun DIV.teamContent(canManage: Boolean, viewerEmpNo: String, assign: AssignForm = AssignForm()) {
    val projects = DemoData.projects
    div("card-panel") {
        p("panel-caption") {
            if (canManage) {
                +"Tip: click a member for details, or open the Tasks count to see who is doing what."
                +" Use + next to it to assign them a task."
            } else {
                +"Open your own Tasks count to see what is assigned to you."
            }
        }
        table("grid team-table") {
            thead {
                tr {
                    th { +"ID" }
                    th { +"Name" }
                    th { +"Department" }
                    th { +"Position" }
                    th(classes = "num") { +"Tasks" }
                    th(classes = "num") { +"Ongoing" }
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
                        val mine = projects.filter { it.manager == member.name }
                        fun count(status: String) = mine.count { it.status == status }
                        // Whose work this row is allowed to show: management sees everybody's, and a
                        // normal account sees its own — the only row with a number to open.
                        val seesTasks = canManage || member.employeeNumber == viewerEmpNo
                        val tasks = if (seesTasks) memberTaskRows(member.employeeNumber) else emptyList()
                        val open = tasks.count { it.first.isOpen }
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
                            td { +member.department }
                            td { +member.position }
                            td("num") {
                                if (seesTasks) {
                                    button(type = ButtonType.button, classes = if (open == 0) "tasks-toggle zero" else "tasks-toggle") {
                                        attributes["data-emp"] = member.employeeNumber
                                        attributes["title"] = "Show ${member.name}'s tasks"
                                        +"$open"
                                    }
                                } else {
                                    // The column stays so the table lines up; only what is inside it
                                    // is withheld, and a dash says "not yours to open", not "no work".
                                    span("mtd-empty") { +"—" }
                                }
                                if (canManage) {
                                    button(type = ButtonType.button, classes = "quick-add") {
                                        attributes["data-emp"] = member.employeeNumber
                                        attributes["title"] = "Assign a task to ${member.name}"
                                        +"+"
                                    }
                                }
                            }
                            td("num") { statusCount("ontrack", count("Ongoing")) }
                            td("num") { statusCount("onhold", count("On hold")) }
                            td("num") { statusCount("planning", count("Not started")) }
                            td("num") { statusCount("atrisk", count("Blocked")) }
                            td("num") { statusCount("done", count("Completed")) }
                            td("num") { span("count-total") { +"${mine.size}" } }
                            td("num") { span("mhint") { +"›" } }
                        }
                        if (seesTasks) memberTasksRow(member, tasks)
                    }
                }
            }
        }
    }

    if (canManage) {
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
                        // The six sub-categories beside it are the same six on every project's board, which is
                        // what lets this page ask without knowing any project's lane ids.
                        label { +"Sub-category" }
                        select {
                            name = "lane"
                            DEFAULT_PROJECT_BUCKETS.forEach { lane ->
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
 * The label row above each department's people. It spans all twelve columns so the numeric columns
 * stay in line beneath it, and it states the headcount rather than leaving anyone to count rows —
 * the roster runs to fourteen departments, and a plain sort would make each group's size invisible.
 */
private fun TBODY.departmentRow(department: String, people: Int) {
    tr("dept-row") {
        td {
            colSpan = "12"
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
    "Completed", "Ongoing" -> "#1e8e3e"
    else -> "#8a9199"
}

private fun projectContext(project: String): String {
    if (project.isBlank()) return ""
    val p = DemoData.projects.firstOrNull { it.name == project } ?: return project
    return "${p.code} · ${p.name}"
}

/** All of a member's tasks (project tasks plus personal ones), open first then by due date. */
private fun memberTaskRows(emp: String): List<Pair<TaskItem, Boolean>> =
    (DemoData.assignedTasks.filter { it.assignee == emp }.map { it to false } +
        DemoData.otherTasks.filter { it.assignee == emp }.map { it to true })
        .sortedWith(
            compareBy(
                { (t, _) -> !t.isOpen },
                { (t, _) -> t.due.isEmpty() },
                { (t, _) -> t.due },
            )
        )

/** Hidden table sub-row listing everything a member is doing; toggled from the Tasks count. */
private fun TBODY.memberTasksRow(member: Member, tasks: List<Pair<TaskItem, Boolean>>) {
    tr("member-tasks") {
        attributes["data-emp"] = member.employeeNumber
        attributes["hidden"] = "hidden"
        td {
            colSpan = "12"
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
 * The payload is the page's one copy of "what is this person doing", and it is a normal account's
 * own entry that may carry it: for anybody else, [canManage] false and not the reader's own number
 * means the task lists come back empty and only the projects they *manage* are named. A colleague
 * is still findable by name, department, position and email — which is what the page is for.
 */
private fun buildMemberDetails(canManage: Boolean, viewerEmpNo: String): Map<String, MemberDetail> {
    val allProjects = DemoData.projects
    val projectByName = allProjects.associateBy { it.name }
    // The percentage a popup prints for a project is the Projects board's figure for the same project —
    // one measurement, from the checklists on its cards, with the row's own number only where no card
    // carries a plan. Three pages naming one project three different numbers would be read as three bugs.
    val measured = TaskService.progressByProject()
    return DemoData.team.associate { member ->
        val seesTasks = canManage || member.employeeNumber == viewerEmpNo
        val managed = allProjects.filter { it.manager == member.name }
        val managedIds = managed.map { it.id }.toSet()
        val assigned = if (seesTasks) DemoData.assignedTasks.filter { it.assignee == member.employeeNumber } else emptyList()
        val other = if (seesTasks) DemoData.otherTasks.filter { it.assignee == member.employeeNumber } else emptyList()
        val contributedNames = assigned.map { it.project }.toSet()
        val contributed = allProjects.filter { it.name in contributedNames && it.id !in managedIds }

        val projectInfos = (
            managed.map {
                MemberProjectInfo(it.code, it.name, it.status, TaskService.displayedProgress(it, measured), it.due, "Manager")
            } + contributed.map {
                MemberProjectInfo(it.code, it.name, it.status, TaskService.displayedProgress(it, measured), it.due, "Tasks")
            }
            ).sortedWith(compareBy({ it.role != "Manager" }, { it.name }))

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
