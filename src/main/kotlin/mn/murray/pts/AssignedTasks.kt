package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
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
import kotlinx.html.script

fun DIV.assignedTasksContent(session: UserSession, canManage: Boolean) {
    val tasks = DemoData.assignedTasks
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
                inCol.forEach { assignedCard(it, session, canManage) }
            }
        }
    }

    div("other-head") {
        span("other-title") { +"Other tasks" }
        span("other-sub") { +"— outside of projects" }
    }
    div("kanban kanban-other") {
        DemoData.buckets.forEach { bucket ->
            val inBucket = DemoData.otherTasks.filter { it.bucket == bucket }
            div("kcol") {
                div("kcol-head") {
                    span("dot d-gray") { }
                    span("kcol-title") { +bucket }
                    span("kcol-count") { +"${inBucket.size}" }
                }
                inBucket.forEach { otherCard(it) }
                form(action = "/app/other-tasks", method = FormMethod.post, classes = "addtask") {
                    input(type = InputType.hidden, name = "bucket") { value = bucket }
                    input(type = InputType.text, name = "title") {
                        placeholder = "+ Add task"
                        required = true
                    }
                }
            }
        }
        div("kcol addbucket") {
            form(action = "/app/other-buckets", method = FormMethod.post) {
                input(type = InputType.text, name = "name") {
                    placeholder = "+ Add bucket"
                    required = true
                }
            }
        }
    }

    if (canManage) {
        div("card-panel") {
            h3 { +"Assign a task" }
            form(action = "/app/assigned-tasks", method = FormMethod.post) {
                div("form-grid") {
                    div("field") {
                        label { +"Project" }
                        select {
                            name = "project"
                            required = true
                            DemoData.projects.forEach { project ->
                                option { value = project.name; +"${project.code} · ${project.name}" }
                            }
                        }
                    }
                    div("field") {
                        label { +"Task" }
                        input(type = InputType.text, name = "title") {
                            placeholder = "e.g. Review blast vibration report"
                            required = true
                        }
                    }
                    div("field") {
                        label { +"Assign to" }
                        select {
                            name = "assignee"
                            required = true
                            DemoData.team.forEach { member ->
                                option { value = member.employeeNumber; +"${member.employeeNumber} · ${member.name} (${member.position})" }
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
                p("task-meta") { +"Pick a project, break it into a task, then drag the card between columns to change its status." }
                button(type = ButtonType.submit, classes = "submit") { +"Assign task" }
            }
        }
    }
    // Board drag-and-drop + move buttons live in an external script (Stage 7): the markup
    // contract is .kcol[data-status], .kcard[draggable][data-id] and .kmove-btn[data-*].
    script(src = "/static/js/kanban.js") {}
}

private fun DIV.assignedCard(task: TaskItem, session: UserSession, canManage: Boolean) {
    val editable = canManage || task.assignee == session.employeeNumber
    div("kcard ${statusSlug(task.status)}") {
        attributes["data-id"] = "${task.id}"
        if (editable) attributes["draggable"] = "true"
        span("tcode") { +taskCode(task.id) }
        div("ktitle") { +task.title }
        div("kmeta") {
            span("prio ${prioClass(task.priority)}") { +task.priority }
            span("due") { +"📅 ${formatDue(task.due)}" }
            val a = DemoData.team.firstOrNull { it.employeeNumber == task.assignee }
            if (a != null) span("avatar xs") { attributes["title"] = a.name; +initials(a.name) }
        }
        if (editable) {
            val idx = TASK_STATUSES.indexOf(task.status).coerceAtLeast(0)
            val prev = TASK_STATUSES.getOrNull(idx - 1)
            val next = TASK_STATUSES.getOrNull(idx + 1)
            div("kmove") {
                if (prev != null) {
                    button(type = ButtonType.button, classes = "kmove-btn") {
                        attributes["data-id"] = "${task.id}"
                        attributes["data-status"] = prev
                        attributes["title"] = "Move to $prev"
                        +"← $prev"
                    }
                }
                if (next != null) {
                    button(type = ButtonType.button, classes = "kmove-btn next") {
                        attributes["data-id"] = "${task.id}"
                        attributes["data-status"] = next
                        attributes["title"] = "Move to $next"
                        +"$next →"
                    }
                }
            }
        }
    }
}

private fun DIV.otherCard(task: TaskItem) {
    div("kcard s-other") {
        div("ktitle") { +task.title }
        div("kmeta") {
            span("prio ${prioClass(task.priority)}") { +task.priority }
            span("due") { +"📅 ${formatDue(task.due)}" }
        }
        div("kmeta kfoot") {
            span("ostatus") {
                span("dot ${statusDot(task.status)}") { }
                +" ${task.status}"
            }
            val a = DemoData.team.firstOrNull { it.employeeNumber == task.assignee }
            if (a != null) span("avatar xs") { attributes["title"] = a.name; +initials(a.name) }
        }
    }
}

private fun taskCode(id: Int): String = "T-${id.toString().padStart(5, '0')}"

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatDue(iso: String): String {
    val p = iso.split("-")
    if (p.size != 3) return iso
    val m = p[1].toIntOrNull() ?: return iso
    val d = p[2].toIntOrNull() ?: return iso
    return "%02d %s %s".format(d, MONTHS[m - 1], p[0])
}

private fun statusDot(status: String): String = when (status) {
    "In progress" -> "d-amber"
    "On hold" -> "d-orange"
    "Blocked" -> "d-red"
    "Completed" -> "d-green"
    else -> "d-gray"
}

private fun statusSlug(status: String): String = when (status) {
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

private fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
