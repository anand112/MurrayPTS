package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.DIV
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.unsafe
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

fun DIV.calendarContent(session: UserSession) {
    val canManage = DemoData.userFor(session.employeeNumber).access in MANAGE_ROLES
    val today = LocalDate.now()
    val yearMonth = YearMonth.from(today)
    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayOfMonth = yearMonth.atDay(1)
    val firstDayOfWeek = firstDayOfMonth.dayOfWeek.value % 7 // 0 = Sunday, 1 = Monday, etc.

    div("card-panel") {
        h3 { +"Calendar - ${yearMonth.month.getDisplayName(TextStyle.FULL, java.util.Locale.ENGLISH)} ${yearMonth.year}" }
        p("task-meta") { +"View all assigned tasks with start dates and deadlines" }
    }

    div("card-panel") {
        table("calendar-grid") {
            thead {
                tr {
                    listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
                        th { +day }
                    }
                }
            }
            tbody {
                var dayCounter = 1
                var weekCounter = 0

                // Build a map of tasks that span dates with their position
                val taskPositions = mutableMapOf<Pair<Int, LocalDate>, Int>()
                val taskRowMap = mutableMapOf<Int, Int>()
                var maxRow = 0

                DemoData.assignedTasks.forEach { task ->
                    val startDate = if (task.started.isNotEmpty()) {
                        try { LocalDate.parse(task.started) } catch (e: Exception) { null }
                    } else null
                    val dueDate = if (task.due.isNotEmpty()) {
                        try { LocalDate.parse(task.due) } catch (e: Exception) { null }
                    } else null

                    if (startDate != null && dueDate != null && startDate <= dueDate) {
                        // Find first available row
                        var row = 0
                        var found = false
                        while (!found) {
                            var conflict = false
                            var checkDate = startDate!!
                            while (checkDate <= dueDate!! && !conflict) {
                                if (taskPositions[Pair(row, checkDate)] != null) {
                                    conflict = true
                                }
                                checkDate = checkDate.plusDays(1)
                            }
                            if (!conflict) {
                                found = true
                            } else {
                                row++
                            }
                        }
                        taskRowMap[task.id] = row
                        if (row > maxRow) maxRow = row

                        // Mark all dates in the span
                        var markDate = startDate!!
                        while (markDate <= dueDate!!) {
                            taskPositions[Pair(row, markDate)] = task.id
                            markDate = markDate.plusDays(1)
                        }
                    }
                }

                while (dayCounter <= daysInMonth) {
                    tr {
                        for (dayOfWeek in 0..6) {
                            val cellDay = if (weekCounter == 0 && dayOfWeek < firstDayOfWeek) {
                                null
                            } else if (dayCounter <= daysInMonth) {
                                dayCounter++
                            } else {
                                null
                            }

                            td {
                                attributes["class"] = "calendar-cell"
                                if (cellDay != null) {
                                    val date = yearMonth.atDay(cellDay)
                                    val isToday = date == today

                                    div("calendar-day ${if (isToday) "today" else ""}") {
                                        +cellDay.toString()
                                    }

                                    // Render task bars for this date
                                    for (row in 0..maxRow) {
                                        val taskId = taskPositions[Pair(row, date)]
                                        if (taskId != null) {
                                            val task = DemoData.assignedTasks.firstOrNull { it.id == taskId }
                                            if (task != null) {
                                                val startDate = if (task.started.isNotEmpty()) {
                                                    try { LocalDate.parse(task.started) } catch (e: Exception) { null }
                                                } else null
                                                val dueDate = if (task.due.isNotEmpty()) {
                                                    try { LocalDate.parse(task.due) } catch (e: Exception) { null }
                                                } else null

                                                val isStartDate = startDate != null && date == startDate
                                                val isDueDate = dueDate != null && date == dueDate
                                                val isFirstDay = isStartDate
                                                val isLastDay = isDueDate

                                                val assignee = DemoData.team.firstOrNull { it.employeeNumber == task.assignee }
                                                val taskColor = when (task.priority) {
                                                    "High" -> "#d93025"
                                                    "Medium" -> "#e0a800"
                                                    else -> "#1e8e3e"
                                                }

                                                div("calendar-task-bar") {
                                                    attributes["style"] = """
                                                        background-color: $taskColor;
                                                        ${if (isFirstDay) "border-top-left-radius: 6px; border-bottom-left-radius: 6px;" else ""}
                                                        ${if (isLastDay) "border-top-right-radius: 6px; border-bottom-right-radius: 6px;" else ""}
                                                    """.trimIndent()
                                                    attributes["data-task-id"] = "${task.id}"
                                                    attributes["data-task-title"] = task.title
                                                    attributes["data-task-project"] = task.project
                                                    attributes["data-task-assignee"] = (assignee?.name ?: "Unassigned")
                                                    attributes["data-task-started"] = task.started
                                                    attributes["data-task-due"] = task.due
                                                    attributes["data-task-priority"] = task.priority
                                                    attributes["data-task-status"] = task.status
                                                    attributes["onclick"] = "openTaskModal(this)"
                                                    
                                                    if (isFirstDay) {
                                                        span("calendar-task-link") {
                                                            +task.title.take(25)
                                                            if (task.title.length > 25) +"..."
                                                        }
                                                        if (assignee != null) {
                                                            span("calendar-bar-assignee") { 
                                                                attributes["title"] = assignee.name
                                                                +initials(assignee.name)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    weekCounter++
                }
            }
        }
    }

    // Task detail modal
    div("modal") {
        id = "taskModal"
        div("modal-content") {
            span("close") {
                attributes["onclick"] = "closeTaskModal()"
                +"×"
            }
            h3 { id = "modalTaskTitle"; +"Task Details" }
            div("modal-body") {
                div("modal-row") {
                    span("modal-label") { +"Project:" }
                    span("modal-value") { id = "modalProject" }
                }
                div("modal-row") {
                    span("modal-label") { +"Assignee:" }
                    span("modal-value") { id = "modalAssignee" }
                }
                div("modal-row") {
                    span("modal-label") { +"Started:" }
                    span("modal-value") { id = "modalStarted" }
                }
                div("modal-row") {
                    span("modal-label") { +"Due:" }
                    span("modal-value") { id = "modalDue" }
                }
                div("modal-row") {
                    span("modal-label") { +"Priority:" }
                    span("modal-value") { id = "modalPriority" }
                }
                div("modal-row") {
                    span("modal-label") { +"Status:" }
                    span("modal-value") { id = "modalStatus" }
                }
                div("modal-actions") {
                    a(href = "/app/assigned-tasks", classes = "modal-btn") { +"View in Assigned Tasks" }
                }
            }
        }
    }

    // Modal script
    unsafe {
        +("""
        <script>
        function openTaskModal(element) {
            document.getElementById('modalTaskTitle').textContent = element.getAttribute('data-task-title');
            document.getElementById('modalProject').textContent = element.getAttribute('data-task-project') || '—';
            document.getElementById('modalAssignee').textContent = element.getAttribute('data-task-assignee');
            document.getElementById('modalStarted').textContent = element.getAttribute('data-task-started') || '—';
            document.getElementById('modalDue').textContent = element.getAttribute('data-task-due') || '—';
            document.getElementById('modalPriority').textContent = element.getAttribute('data-task-priority');
            document.getElementById('modalStatus').textContent = element.getAttribute('data-task-status');
            document.getElementById('taskModal').style.display = 'block';
        }

        function closeTaskModal() {
            document.getElementById('taskModal').style.display = 'none';
        }

        window.onclick = function(event) {
            var modal = document.getElementById('taskModal');
            if (event.target == modal) {
                modal.style.display = 'none';
            }
        }
        </script>
        """)
    }

    div("card-panel") {
        h3 { +"All Assigned Tasks Summary" }
        val allTasks = DemoData.assignedTasks
        if (allTasks.isEmpty()) {
            p("task-meta") { +"No assigned tasks yet." }
        } else {
            table("grid") {
                thead {
                    tr {
                        th { +"Task" }
                        th { +"Project" }
                        th { +"Assignee" }
                        th { +"Started" }
                        th { +"Due" }
                        th { +"Status" }
                        th { +"Priority" }
                    }
                }
                tbody {
                    allTasks.forEach { task ->
                        tr {
                            td { span("pname") { +task.title } }
                            td { +task.project }
                            td {
                                val assignee = DemoData.team.firstOrNull { it.employeeNumber == task.assignee }
                                if (assignee != null) +assignee.name else span("assignee none") { +"Unassigned" }
                            }
                            td { +task.started.ifEmpty { "—" } }
                            td { +task.due.ifEmpty { "—" } }
                            td { span("chip ${chipClass(taskStatusChip(task.status))}") { +task.status } }
                            td { +task.priority }
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

private fun chipClass(status: String): String = when (status) {
    "Ongoing" -> "ontrack"
    "Blocked" -> "atrisk"
    "Completed" -> "done"
    "On hold" -> "onhold"
    else -> "planning"
}

private fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
