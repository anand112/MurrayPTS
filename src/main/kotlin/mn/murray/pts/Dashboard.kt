package mn.murray.pts

import mn.murray.pts.domain.*

import kotlinx.html.DIV
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.span
import mn.murray.pts.domain.STATUS_COMPLETED
import mn.murray.pts.domain.TaskItem
import mn.murray.pts.service.ProjectService
import mn.murray.pts.service.TaskService
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

// The dashboard lives here; the other page renderers are split by feature:
// - Projects.kt: projectsContent(), projectTable()
// - AssignedTasks.kt: assignedTasksContent()
// - Team.kt: teamContent()
// - Forecasts.kt: forecastsContent()
// - Lessons.kt: lessonsContent()
// - OtherTasks.kt: otherTasksContent(), the backlog board's own columns, popup and toggle
// - Todo.kt: todoContent()
// - AccessControl.kt: accessContent()
// - Settings.kt: settingsContent()
// - DashboardCommon.kt: appPage(), navItems(), ICONS, themeScript()

// CSS and JavaScript have been extracted to resources:
// - src/main/resources/styles.css
// - src/main/resources/theme.js
// - src/main/resources/kanban.js
// - src/main/resources/logo.json

enum class TaskDueFilter(val key: String, val title: String, val emptyMessage: String) {
    OVERDUE("overdue", "Overdue", "Nothing overdue — your work is clear."),
    THIS_WEEK("week", "Due this week", "Nothing else is due this week.");

    companion object {
        fun from(value: String?): TaskDueFilter? =
            values().firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) }
    }
}

private data class DatedDashboardTask(val task: TaskItem, val due: LocalDate)

/** Apply one dashboard due-date window to the same personal tasks the Assigned page displays. */
fun tasksForDueFilter(
    tasks: List<TaskItem>,
    filter: TaskDueFilter?,
    today: LocalDate = LocalDate.now(),
): List<TaskItem> {
    if (filter == null) return tasks
    val weekEnd = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(6)
    return tasks.mapNotNull { task ->
        if (task.archived || task.status == STATUS_COMPLETED || task.due.isBlank()) return@mapNotNull null
        val due = try {
            LocalDate.parse(task.due)
        } catch (_: DateTimeParseException) {
            null
        } ?: return@mapNotNull null
        val matches = when (filter) {
            TaskDueFilter.OVERDUE -> due.isBefore(today)
            TaskDueFilter.THIS_WEEK -> !due.isBefore(today) && !due.isAfter(weekEnd)
        }
        if (matches) DatedDashboardTask(task, due) else null
    }.sortedWith(compareBy<DatedDashboardTask>({ it.due }, { it.task.title.lowercase() }))
        .map { it.task }
}

fun DIV.dashboardContent(session: UserSession, today: LocalDate = LocalDate.now()) {
    val tasks = TaskService.assignedFor(session.employeeNumber, canManage = false) +
        TaskService.assignedOtherFor(session.employeeNumber)
    val overdue = tasksForDueFilter(tasks, TaskDueFilter.OVERDUE, today)
    val thisWeek = tasksForDueFilter(tasks, TaskDueFilter.THIS_WEEK, today)

    div("dashboard-urgency") {
        urgencyCard(TaskDueFilter.OVERDUE, overdue)
        urgencyCard(TaskDueFilter.THIS_WEEK, thisWeek)
    }

    val projects = ProjectService.all().sortedBy { it.code }
    val measuredProgress = TaskService.progressByProject()
    div("card-panel dashboard-project-panel") {
        h2("dashboard-section-title") { +"Project progress" }
        if (projects.isEmpty()) {
            p("dashboard-empty") {
                +"No projects yet. "
                a(href = "/app/projects") { +"Open Projects" }
                +" to get started."
            }
        } else {
            div("dashboard-project-list") {
                projects.forEach { project ->
                    val progress = TaskService.displayedProgress(project, measuredProgress)
                    a(href = "/app/projects/${project.id}", classes = "dashboard-project") {
                        div("dashboard-project-heading") {
                            span("code") { +project.code }
                            span("dashboard-project-name") { +project.name }
                            span("dashboard-project-status") { +project.status }
                        }
                        div("dashboard-progress-row") {
                            div("progress dashboard-progress") {
                                attributes["role"] = "progressbar"
                                attributes["aria-label"] = "${project.code} ${project.name} progress"
                                attributes["aria-valuemin"] = "0"
                                attributes["aria-valuemax"] = "100"
                                attributes["aria-valuenow"] = "$progress"
                                div("bar") { attributes["style"] = "width: ${progress}%" }
                            }
                            span("pct") { +"$progress%" }
                        }
                    }
                }
            }
        }
    }
}

private fun DIV.urgencyCard(filter: TaskDueFilter, tasks: List<TaskItem>) {
    div("card-panel dashboard-urgency-card") {
        a(href = "/app/assigned-tasks?due=${filter.key}", classes = "dashboard-metric-link") {
            h2("dashboard-section-title") { +filter.title }
            span("dashboard-count") { +"${tasks.size}" }
            span("dashboard-action") { +"Open filtered task list" }
        }
        if (tasks.isEmpty()) {
            p("dashboard-empty") { +filter.emptyMessage }
        } else {
            div("dashboard-task-list") {
                tasks.take(5).forEach { task ->
                    a(
                        href = "/app/assigned-tasks?due=${filter.key}&edit=${task.id}",
                        classes = "dashboard-task",
                    ) {
                        span("dashboard-task-title") { +task.title }
                        span("dashboard-task-meta") {
                            val context = if (task.projectCode.isNotBlank()) task.projectCode else "Other tasks"
                            +"$context · Due ${task.due} · ${task.priority}"
                        }
                    }
                }
            }
        }
    }
}
