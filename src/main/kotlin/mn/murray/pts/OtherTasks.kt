package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.input
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span

fun DIV.taskRow(task: TaskItem, session: UserSession? = null, canManage: Boolean = false, showStatus: Boolean = false) {
    val assignee = DemoData.team.firstOrNull { it.employeeNumber == task.assignee }
    div("task-row") {
        div("task-main") {
            div("task-title") { +task.title }
            div("task-meta") {
                +(if (task.project.isEmpty()) "Due ${task.due}" else "${task.project} · due ${task.due}")
                if (task.started.isNotEmpty()) +" · started ${task.started}"
                +" · ${task.priority} priority"
            }
        }
        if (assignee != null) {
            span("assignee") {
                span("avatar sm") { +initials(assignee.name) }
                span("assignee-meta") {
                    span("assignee-name") { +assignee.name }
                    span("assignee-id") { +assignee.employeeNumber }
                }
            }
        } else {
            span("assignee none") { +"Unassigned" }
        }
        if (showStatus) {
            val editable = session != null && (canManage || task.assignee == session.employeeNumber)
            if (editable) {
                form(action = "/app/tasks/status", method = FormMethod.post, classes = "status-form") {
                    input(type = InputType.hidden, name = "id") { value = "${task.id}" }
                    select(classes = "status-select") {
                        name = "status"
                        attributes["data-autosubmit"] = ""
                        TASK_STATUSES.forEach { st ->
                            option {
                                value = st
                                if (st == task.status) selected = true
                                +st
                            }
                        }
                    }
                }
            } else {
                span("chip ${taskStatusChip(task.status)}") { +task.status }
            }
        }
        span("chip " + when (task.priority) {
            "High" -> "atrisk"
            "Medium" -> "planning"
            else -> "done"
        }) { +task.priority }
    }
}

fun DIV.tasksContent(items: List<TaskItem>, caption: String) {
    div("card-panel") {
        p("panel-caption") { +caption }
        if (items.isEmpty()) p("task-meta") { +"No tasks yet." }
        items.forEach { taskRow(it) }
    }
}

private fun taskStatusChip(status: String): String = when (status) {
    "In progress" -> "ontrack"
    "Blocked" -> "atrisk"
    "Completed" -> "done"
    "On hold" -> "onhold"
    else -> "planning"
}

private fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
