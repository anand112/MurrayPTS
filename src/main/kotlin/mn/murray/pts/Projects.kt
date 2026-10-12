package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

fun DIV.projectsContent(session: UserSession, typeFilter: String?) {
    val all = DemoData.projects
    div("filter-tabs") {
        a(classes = if (typeFilter == null) "ftab active" else "ftab") {
            href = "/app/projects"
            +"All "
            span("pill") { +"${all.size}" }
        }
        PROJECT_TYPES.forEach { type ->
            a(classes = if (typeFilter == type) "ftab active" else "ftab") {
                href = "/app/projects?type=" + java.net.URLEncoder.encode(type, "UTF-8")
                +"$type "
                span("pill") { +"${all.count { it.type == type }}" }
            }
        }
    }
    val groups: List<Pair<String, List<Project>>> = if (typeFilter == null) {
        PROJECT_TYPES.map { type -> type to all.filter { it.type == type }.sortedBy { it.due } }
    } else {
        listOf(typeFilter to all.filter { it.type == typeFilter }.sortedBy { it.due })
    }
    val visible = groups.filter { it.second.isNotEmpty() }
    if (visible.isEmpty()) {
        div("card-panel") {
            span("task-meta") { +"No projects of this type yet." }
        }
    } else {
        visible.forEach { (type, projects) ->
            div("card-panel") {
                div("group-head") {
                    span("chip ${typeChipClass(type)}") { +type }
                    span("pill") { +"${projects.size}" }
                }
                projectTable(projects, session)
            }
        }
    }
    div("card-panel") {
        h3 { +"Add project" }
        form(action = "/app/projects", method = FormMethod.post) {
            div("form-grid") {
                div("field") {
                    label { +"Project name" }
                    input(type = InputType.text, name = "name") {
                        placeholder = "e.g. Dalanzadgad Depot"
                        required = true
                    }
                }
                div("field") {
                    label { +"Type" }
                    select {
                        name = "type"
                        PROJECT_TYPES.forEach { option { +it } }
                    }
                }
                div("field") {
                    label { +"Status" }
                    select {
                        name = "status"
                        PROJECT_STATUSES.forEach { option { +it } }
                    }
                }
                div("field") {
                    label { +"Progress %" }
                    input(type = InputType.number, name = "progress") {
                        min = "0"
                        max = "100"
                        value = "0"
                    }
                }
                div("field") {
                    label { +"Manager" }
                    select {
                        name = "manager"
                        option { value = ""; +"— No manager —" }
                        DemoData.team.forEach { member ->
                            option { value = member.name; +"${member.employeeNumber} · ${member.name} (${member.position})" }
                        }
                    }
                }
                div("field") {
                    label { +"Due date" }
                    input(type = InputType.date, name = "due")
                }
            }
            p("task-meta") { +"The project ID is assigned automatically from the type (SIT-, MFG-, RMS-, LAB-)." }
            button(type = ButtonType.submit, classes = "submit") { +"Add project" }
        }
    }
}

fun DIV.projectTable(shown: List<Project>, session: UserSession) {
    table("grid") {
        thead {
            tr {
                th { +"ID" }
                th { +"Project" }
                th { +"Status" }
                th { +"Progress" }
                th { +"Manager" }
                th { +"Due" }
                th { +"" }
            }
        }
        tbody {
            shown.forEach { project ->
                tr {
                    td { a(href = "/app/projects/${project.id}", classes = "row-link") { span("code") { +project.code } } }
                    td { a(href = "/app/projects/${project.id}", classes = "row-link") { span("pname") { +project.name } } }
                    td { span("chip ${chipClass(project.status)}") { +project.status } }
                    td {
                        div("progress") {
                            div("bar") { attributes["style"] = "width:${project.progress}%" }
                        }
                        span("pct") { +"${project.progress}%" }
                    }
                    td { +project.manager }
                    td { +project.due }
                    td {
                        form(action = "/app/projects/${project.id}/pin", method = FormMethod.post) {
                            val pinned = pinnedIds(session.employeeNumber).contains(project.id)
                            button(type = ButtonType.submit, classes = if (pinned) "pin-btn on" else "pin-btn") {
                                +(if (pinned) "★ Pinned" else "☆ Pin")
                            }
                        }
                    }
                }
            }
        }
    }
}

fun DIV.projectDetailContent(session: UserSession, project: Project, canManage: Boolean) {
    div("detail-head") {
        a(href = "/app/projects", classes = "back-link") { +"← Back to projects" }
    }
    div("card-panel") {
        div("group-head") {
            span("chip ${typeChipClass(project.type)}") { +project.type }
            span("code") { +project.code }
            span("chip ${chipClass(project.status)}") { +project.status }
            form(action = "/app/projects/${project.id}/pin", method = FormMethod.post) {
                val pinned = pinnedIds(session.employeeNumber).contains(project.id)
                button(type = ButtonType.submit, classes = if (pinned) "pin-btn on" else "pin-btn") {
                    +(if (pinned) "★ Pinned" else "☆ Pin")
                }
            }
        }
        h3("detail-title") { +project.name }
        div("detail-grid") {
            detailItem("Project ID", project.code)
            detailItem("Manager", project.manager.ifEmpty { "—" })
            detailItem("Due date", project.due.ifEmpty { "—" })
            detailItem("Status", project.status)
            detailItem("Type", project.type)
            div("detail-item") {
                span("detail-key") { +"Progress" }
                div("progress") {
                    div("bar") { attributes["style"] = "width:${project.progress}%" }
                }
                span("pct") { +"${project.progress}%" }
            }
        }
    }
    val manager = DemoData.team.firstOrNull { it.name == project.manager }
    div("card-panel") {
        h3 { +"Project manager" }
        if (manager != null) {
            div("todo-row") {
                span("avatar sm") { +initials(manager.name) }
                div("assignee") {
                    div("assignee-name") { +manager.name }
                    div("assignee-id") { +"${manager.employeeNumber} · ${manager.position.ifEmpty { manager.department }}" }
                }
            }
        } else if (project.manager.isNotBlank()) {
            p("task-meta") { +"${project.manager} is not on the team roster. Assign one of the accounts below." }
        } else {
            p("task-meta") { +"No manager assigned yet." }
        }
        if (canManage) {
            form(action = "/app/projects/${project.id}/manager", method = FormMethod.post) {
                div("manager-set") {
                    select {
                        name = "manager"
                        option { value = ""; +"— No manager —" }
                        DemoData.team.forEach { member ->
                            option {
                                value = member.name
                                selected = manager?.employeeNumber == member.employeeNumber
                                +"${member.employeeNumber} · ${member.name} (${member.position})"
                            }
                        }
                    }
                    button(type = ButtonType.submit, classes = "submit") { +"Set manager" }
                }
            }
        }
    }
    div("card-panel") {
        h3 { +"Project assignees" }
        if (project.assignees.isEmpty()) {
            p("task-meta") { +"No assignees yet. Add the team members who work on this project." }
        } else {
            project.assignees.forEach { emp ->
                val member = DemoData.team.firstOrNull { it.employeeNumber == emp }
                val name = member?.name ?: emp
                val sub = when {
                    member == null -> "$emp · not on the team roster"
                    member.position.isNotEmpty() -> "${member.employeeNumber} · ${member.position}"
                    else -> "${member.employeeNumber} · ${member.department}"
                }
                div("todo-row") {
                    span("avatar sm") { +initials(name) }
                    div("assignee") {
                        div("assignee-name") { +name }
                        div("assignee-id") { +sub }
                    }
                    if (canManage) {
                        form(action = "/app/projects/${project.id}/assignees/remove", method = FormMethod.post, classes = "todo-del-form") {
                            input(type = InputType.hidden, name = "assignee") { value = emp }
                            button(type = ButtonType.submit, classes = "todo-del") {
                                attributes["title"] = "Remove $name from this project"
                                +"×"
                            }
                        }
                    }
                }
            }
        }
        if (canManage) {
            form(action = "/app/projects/${project.id}/assignees/add", method = FormMethod.post) {
                div("manager-set") {
                    select {
                        name = "assignee"
                        option { value = ""; +"— Add team member —" }
                        DemoData.team.filter { it.employeeNumber !in project.assignees }.forEach { member ->
                            option { value = member.employeeNumber; +"${member.employeeNumber} · ${member.name} (${member.position})" }
                        }
                    }
                    button(type = ButtonType.submit, classes = "submit") { +"Add assignee" }
                }
            }
        }
    }
    val tasks = DemoData.assignedTasks.filter { it.project == project.name }
    div("card-panel") {
        h3 { +"Tasks on this project" }
        if (tasks.isEmpty()) {
            p("task-meta") { +"No tasks assigned to this project yet. Assign some from the Assigned tasks page." }
        } else {
            table("grid") {
                thead {
                    tr {
                        th { +"Code" }
                        th { +"Task" }
                        th { +"Assignee" }
                        th { +"Status" }
                        th { +"Priority" }
                        th { +"Due" }
                    }
                }
                tbody {
                    tasks.forEach { task ->
                        tr {
                            td { span("code") { +"T-${task.id.toString().padStart(5, '0')}" } }
                            td { span("pname") { +task.title } }
                            td {
                                val a = DemoData.team.firstOrNull { it.employeeNumber == task.assignee }
                                if (a != null) +a.name else span("assignee none") { +"Unassigned" }
                            }
                            td { span("chip ${chipClass(taskStatusChip(task.status))}") { +task.status } }
                            td { +task.priority }
                            td { +task.due }
                        }
                    }
                }
            }
        }
    }
}

private fun taskStatusChip(status: String): String = when (status) {
    "In progress" -> "Ongoing"
    "Blocked" -> "Blocked"
    "Completed" -> "Completed"
    "On hold" -> "On hold"
    else -> "Not started"
}

private fun DIV.detailItem(key: String, value: String) {
    div("detail-item") {
        span("detail-key") { +key }
        span("detail-val") { +value }
    }
}

private fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")

private fun chipClass(status: String): String = when (status) {
    "Ongoing" -> "ontrack"
    "Blocked" -> "atrisk"
    "Completed" -> "done"
    "On hold" -> "onhold"
    else -> "planning"
}

private fun typeChipClass(type: String): String = when (type) {
    "Manufacturing" -> "t-mfg"
    "Repair/ Maintenance/ Other Services" -> "t-rms"
    "Site projects" -> "t-sit"
    "Labour supply" -> "t-lab"
    else -> "planning"
}
