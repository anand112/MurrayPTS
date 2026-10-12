package mn.murray.pts
import mn.murray.pts.domain.*

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

fun DIV.teamContent(canManage: Boolean) {
    val projects = DemoData.projects
    div("card-panel") {
        p("panel-caption") {
            +"Tip: click a member for details, or open the Tasks count to see who is doing what."
            if (canManage) +" Use + next to it to assign them a task."
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
                DemoData.team.forEach { member ->
                    val mine = projects.filter { it.manager == member.name }
                    fun count(status: String) = mine.count { it.status == status }
                    val tasks = memberTaskRows(member.employeeNumber)
                    val open = tasks.count { it.first.status != "Completed" }
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
                            button(type = ButtonType.button, classes = if (open == 0) "tasks-toggle zero" else "tasks-toggle") {
                                attributes["data-emp"] = member.employeeNumber
                                attributes["title"] = "Show ${member.name}'s tasks"
                                +"$open"
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
                    memberTasksRow(member, tasks)
                }
            }
        }
    }

    if (canManage) {
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
                            DemoData.team.forEach { member ->
                                option { value = member.employeeNumber; +"${member.employeeNumber} · ${member.name} (${member.position})" }
                            }
                        }
                    }
                    div("field") {
                        label { +"Task" }
                        input(type = InputType.text, name = "title") {
                            id = "assignTitle"
                            placeholder = "e.g. Prepare weekly progress report"
                            required = true
                        }
                    }
                    div("field") {
                        label { +"Project (optional)" }
                        select {
                            name = "project"
                            option { value = ""; +"— No project —" }
                            DemoData.projects.forEach { project ->
                                option { value = project.name; +"${project.code} · ${project.name}" }
                            }
                        }
                    }
                    div("field") {
                        label { +"Start date" }
                        input(type = InputType.date, name = "started")
                    }
                    div("field") {
                        label { +"Due date" }
                        input(type = InputType.date, name = "due")
                    }
                    div("field") {
                        label { +"Priority" }
                        select {
                            name = "priority"
                            listOf("High", "Medium", "Low").forEach { option { +it } }
                        }
                    }
                }
                button(type = ButtonType.submit, classes = "submit") { +"Assign task" }
            }
        }
    }
    memberDetailScript()
}

private fun TD.statusCount(kind: String, n: Int) {
    span(if (n == 0) "count zero" else "count $kind") { +"$n" }
}

private fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")

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
                { (t, _) -> t.status == "Completed" },
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

/** One entry per team member: projects they manage or contribute tasks to, plus their tasks. */
private fun buildMemberDetails(): Map<String, MemberDetail> {
    val allProjects = DemoData.projects
    val projectByName = allProjects.associateBy { it.name }
    return DemoData.team.associate { member ->
        val managed = allProjects.filter { it.manager == member.name }
        val managedIds = managed.map { it.id }.toSet()
        val assigned = DemoData.assignedTasks.filter { it.assignee == member.employeeNumber }
        val other = DemoData.otherTasks.filter { it.assignee == member.employeeNumber }
        val contributedNames = assigned.map { it.project }.toSet()
        val contributed = allProjects.filter { it.name in contributedNames && it.id !in managedIds }

        val projectInfos = (
            managed.map {
                MemberProjectInfo(it.code, it.name, it.status, it.progress, it.due, "Manager")
            } + contributed.map {
                MemberProjectInfo(it.code, it.name, it.status, it.progress, it.due, "Tasks")
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
private fun memberDetailJson(): String =
    detailJson.encodeToString(buildMemberDetails()).replace("<", "\\u003c")

/**
 * Team page member popup. The per-member payload stays a server-rendered JSON island —
 * a <script type="application/json"> tag is data, not executable, so a strict CSP allows
 * it inline — while the behaviour moved to /static/js/team.js (Stage 7), which reads the
 * island by id and wires the popup, per-row task toggles and quick-add shortcuts.
 */
private fun DIV.memberDetailScript() {
    unsafe {
        +("""<script type="application/json" id="memberDetailData">""" + memberDetailJson() + "</script>")
    }
    script(src = "/static/js/team.js") {}
}
