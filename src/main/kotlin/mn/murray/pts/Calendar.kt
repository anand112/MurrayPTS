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
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import mn.murray.pts.service.TaskService
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

/**
 * The reader's own month plan — for whichever month the address asks for.
 *
 * Two decisions are made here and nowhere else on the page:
 *
 *  * **Whose cards, and which of them.** The plan draws one bar per card, so it follows the rule the Assigned
 *    tasks board settled on in Stage 53 and the project page confirmed in Stage 56: *the calendar is the reader's
 *    own month, for every role.* Management runs the team from the project boards and the Team sheet, where a
 *    colleague's card is a click away and actionable; a plan of the month is a personal document, and bars that
 *    are not yours are noise in it. `canManage = false` is passed deliberately rather than the filter being
 *    written here — [TaskService] stays the single place the visibility rule lives.
 *    Since **Stage 66** the month is read from *both* boards the reader's work lives on, exactly as the Assigned
 *    tasks page has since Stage 55: [TaskService.assignedFor]'s project cards plus
 *    [TaskService.assignedOtherFor]'s backlog cards. A card that belongs to no project is still work that is on
 *    somebody and still has start and due dates, so before this it could be dated, real and invisible — the one
 *    gap between "what is on me" and what the grid was willing to show. Both lists are personal slices, so
 *    widening them cannot reach outside the reader.
 *  * **How they tell each other apart.** A month of bars all coloured by their own priority reads as three buckets
 *    of noise: two projects at the same priority are indistinguishable. So since Stage 66 the **hue is the
 *    group** — a project's cards share one hue and a backlog card takes the hue of the bucket it sits in — and
 *    priority moved to the small dot inside the bar, where it still reads without owning the colour. The key
 *    under the strip spells both out whenever the month holds more than one group.
 *  * **Which month.** [month] is the `YYYY-MM` the address named, resolved by [monthKeyOf] at the route, so the
 *    plan can be bookmarked, shared and reloaded onto a month that is not the current one. A word that is not a
 *    month falls back to this month instead of dead-ending, exactly as the forecast sheet's does.
 *
 * The list under the grid deliberately does **not** follow the month: it is the reader's whole workload, and
 * cutting it to the month above would silently drop every card that carries no dates at all — real work that can
 * never be a bar. The grid answers "what is happening this month"; the list answers "what is mine".
 */
fun DIV.calendarContent(session: UserSession, month: String = currentMonthKey()) {
    val today = LocalDate.now()
    val yearMonth = parseMonth(month) ?: YearMonth.from(today)
    // The canonical key of the month actually being drawn. Every control hands the route *this*, never a word it
    // was handed, so a hand-typed `?month=2026-13` cannot leave a ‹ / › pointing at a month no page can read.
    val key = monthKey(yearMonth)
    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayOfMonth = yearMonth.atDay(1)
    val lastDayOfMonth = yearMonth.atDay(daysInMonth)
    val firstDayOfWeek = firstDayOfMonth.dayOfWeek.value % 7 // 0 = Sunday, 1 = Monday, etc.
    // Both boards the reader's work lives on, in the order the page has always drawn them: a project card first,
    // then the backlog. Neither list is cut to the month — [spanOf] and [overlaps] decide what is a bar here, and
    // the list under the grid prints the whole set.
    val cards = TaskService.assignedFor(session.employeeNumber, canManage = false) +
        TaskService.assignedOtherFor(session.employeeNumber)
    val datedHere = cards.count { it.overlaps(yearMonth) }
    // Assigned over the reader's *whole* workload rather than the month's slice, so a project keeps the same hue
    // whoever is looking at it in January and in July. See [hueByGroup].
    val hues = hueByGroup(cards)

    // Everything the month decides — its name, the controls that change it, the grid — is one live region, so
    // choosing a month swaps the plan in place instead of restarting the page. The strip has to ride *inside* it
    // for the same reason the forecast sheet's does: ‹ / › carry the resolved neighbour, which is only knowable at
    // render time, and a control left outside would keep stepping to the month it was first drawn for.
    div("board-results") {
        id = "calendarMonth"
        attributes["data-live-region"] = "true"

        div("card-panel") {
            h3 { +"Calendar - ${yearMonth.month.getDisplayName(TextStyle.FULL, java.util.Locale.ENGLISH)} ${yearMonth.year}" }
            p("task-meta") {
                +"Your assigned tasks, with start dates and deadlines"
            }
            // The two boards the cards now come from, and the two things a bar's look is telling the reader. Said
            // plainly here so a page of coloured bars is never a puzzle about what the colours mean.
            p("task-meta") {
                +("From your projects and from Other tasks. Each bar is coloured by the project or bucket it " +
                    "belongs to; the dot inside it is its priority.")
            }
        }

        calendarMonthStrip(key, datedHere)

        // Lives inside the month region on purpose: which groups are *here* is a fact about the month on screen,
        // so the key has to change with it or it would describe the month the reader left.
        calendarColourKey(cards.filter { it.overlaps(yearMonth) }, hues)

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

                    cards.forEach { task ->
                        val span = spanOf(task)

                        if (span != null) {
                            val (startDate, dueDate) = span
                            // Find first available row
                            var row = 0
                            var found = false
                            while (!found) {
                                var conflict = false
                                var checkDate = startDate
                                while (checkDate <= dueDate && !conflict) {
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
                            var markDate = startDate
                            while (markDate <= dueDate) {
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
                                                val task = cards.firstOrNull { it.id == taskId }
                                                val span = task?.let { spanOf(it) }
                                                if (task != null && span != null) {
                                                    // A bar that runs in from the month before, or out into the month
                                                    // after, is still this reader's card and still has to say what it is.
                                                    // So its label prints on the first day *visible here*, and the rounded
                                                    // end closes on the last: a card that spans six months is readable in
                                                    // every one of them, not only in the month it started in.
                                                    val isFirstDay = date == span.first || date == firstDayOfMonth
                                                    val isLastDay = date == span.second || date == lastDayOfMonth

                                                    // Every person on the card (Stage 122), so a shared project card
                                                    // prints both names and both initials the way the board does.
                                                    val assignees = task.assignees.mapNotNull { emp ->
                                                        DemoData.team.firstOrNull { it.employeeNumber == emp }
                                                    }
                                                    // The hue says *which work this bar belongs to*, and it is the same
                                                    // for every day of that card, so a project reads as one long object
                                                    // rather than as a string of unrelated cells. Priority is the dot.
                                                    val groupColor = hues[groupKey(task)] ?: CALENDAR_HUES.first()

                                                    div("calendar-task-bar") {
                                                        attributes["style"] = """
                                                            background-color: $groupColor;
                                                            ${if (isFirstDay) "border-top-left-radius: 6px; border-bottom-left-radius: 6px;" else ""}
                                                            ${if (isLastDay) "border-top-right-radius: 6px; border-bottom-right-radius: 6px;" else ""}
                                                        """.trimIndent()
                                                        attributes["data-task-id"] = "${task.id}"
                                                        attributes["data-task-title"] = task.title
                                                        attributes["data-task-project"] = modalProject(task)
                                                        attributes["data-task-assignee"] =
                                                            assignees.joinToString(", ") { it.name }.ifEmpty { "Unassigned" }
                                                        attributes["data-task-started"] = task.started
                                                        attributes["data-task-due"] = task.due
                                                        attributes["data-task-priority"] = task.priority
                                                        attributes["data-task-status"] = task.status
                                                        attributes["data-modal-open"] = "true"

                                                        if (isFirstDay) {
                                                            // One dot, on the labelled day only: repeated down a long
                                                            // bar it would read as decoration, and the day the bar
                                                            // names its card is the day the reader looks at.
                                                            span("cal-dot ${priorityDot(task.priority)}") {
                                                                attributes["title"] = "${task.priority} priority"
                                                            }
                                                            span("calendar-task-link") {
                                                                +task.title.take(25)
                                                                if (task.title.length > 25) +"..."
                                                            }
                                                            assignees.forEach { assignee ->
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

        // An empty month is still a month, so the grid stays drawn — but the page says plainly which of the two
        // reasons this is: nothing of yours is dated here, as opposed to nothing of yours existing at all.
        if (datedHere == 0) {
            p("cal-empty task-meta") {
                +"Nothing assigned to you is dated in ${monthLabel(key)}."
                if (key != currentMonthKey()) {
                    +" "
                    a(classes = "row-link", href = "/app/calendar") { +"Back to this month" }
                }
            }
        }
    }

    // Task detail modal
    div("modal") {
        id = "taskModal"
        div("modal-content") {
            span("close") {
                attributes["data-modal-close"] = "true"
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

    // Task-detail modal behaviour is an external script (Stage 7). It uses delegated
    // click listeners over [data-modal-open] / [data-modal-close] markers plus the
    // data-task-* attributes on each bar, so no global function or inline handler is needed.
    script(src = "/static/js/calendar-modal.js") {}

    div("card-panel") {
        // The list under the grid is the same personal slice the bars are drawn from, so its heading says the
        // same thing the month above it does — for a manager as plainly as for anybody else. It is not cut to the
        // month on screen: see the note on [calendarContent].
        h3 { +"Your assigned tasks" }
        val allTasks = cards
        if (allTasks.isEmpty()) {
            p("task-meta") { +"No assigned tasks yet." }
        } else {
            table("grid") {
                thead {
                    tr {
                        th { +"Task" }
                        th { +"Where it lives" }
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
                            // The same hue the bar wears, so a card that is in the list but not on the grid still
                            // reads as belonging to the group it does. Backlog work is tagged, because a bucket
                            // name on its own is the same words a project lane uses.
                            td {
                                span("cal-group") {
                                    span("cal-swatch") {
                                        attributes["style"] = "background-color: ${hues[groupKey(task)] ?: CALENDAR_HUES.first()}"
                                    }
                                    +groupLabel(task)
                                    if (task.isBacklog) span("cal-tag") { +"Other" }
                                }
                            }
                            td {
                                val names = task.assignees.mapNotNull { emp ->
                                    DemoData.team.firstOrNull { it.employeeNumber == emp }?.name
                                }
                                if (names.isNotEmpty()) +names.joinToString(", ")
                                else span("assignee none") { +"Unassigned" }
                            }
                            td { +task.started.ifEmpty { "—" } }
                            td { +task.due.ifEmpty { "—" } }
                            td { span("chip ${chipClass(task.status)}") { +task.status } }
                            td {
                                span("cal-group") {
                                    span("cal-dot ${priorityDot(task.priority)}") { attributes["aria-hidden"] = "true" }
                                    +task.priority
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The hues a month is drawn in, one per group rather than one per priority.
 *
 * Ten colours chosen to stay apart at bar height and to hold white label text: the palette is fixed rather than
 * generated so the same project cannot arrive in a different shade on a different build, and so the tenth entry
 * is as usable as the first. Order matters only in that neighbours should not look alike — a reader with three
 * projects sees blue, green and orange, not three blues.
 */
private val CALENDAR_HUES = listOf(
    "#2f6fdb", // blue
    "#1e8e3e", // green
    "#c8681f", // orange
    "#8e44ad", // purple
    "#0f8b8d", // teal
    "#c2185b", // magenta
    "#6d4c41", // brown
    "#4a5bc4", // indigo
    "#7c7a12", // olive
    "#4f6b78", // slate
)

/**
 * The hues a month is drawn in, one per group rather than one per priority.
 *
 * A bar's colour is the only thing on the grid that says *which piece of work this is* — a month of twelve cards
 * at the same priority used to be twelve identical bars. So the hue is keyed on the group ([groupKey]) and every
 * day of one card carries the same one, which is what makes a project read as one long object across a week.
 *
 * The palette is assigned by position over the reader's **whole** workload, sorted, and not over the month's
 * slice: changing the month must not repaint the same project in a different colour. Cards wrap around the
 * palette once it runs out, which is rare for one person's month and is still readable because the label, the
 * key and the list all name the group as well.
 *
 * This page's own list, and [CALENDAR_HUES] is the whole of it. A project board colours its *columns* by bucket
 * (Stage 74) from the stylesheet's `.bucket-dot` set, and the two are not the same question: the month groups by
 * which project a card belongs to, the board by which phase of it the card is.
 */
private fun hueByGroup(cards: List<TaskItem>): Map<String, String> =
    cards.map { groupKey(it) }.distinct().sorted()
        .mapIndexed { index, key -> key to CALENDAR_HUES[index % CALENDAR_HUES.size] }
        .toMap()

/**
 * Which group a card is coloured by: its project, or — for backlog work, which has no project by definition —
 * the column of the Other tasks board it sits in. The two keys share no prefix, so a project that happens to be
 * named like a bucket still keeps its own hue.
 */
private fun groupKey(task: TaskItem): String = when {
    task.isBacklog -> "other::" + task.bucket.trim().lowercase()
    else -> "project::" + task.project.trim().lowercase()
}

/** The group's name as the reader sees it, in the key and in the list. */
private fun groupLabel(task: TaskItem): String = when {
    task.isBacklog -> task.bucket.ifBlank { "Other tasks" }
    else -> task.project.ifBlank { "No project" }
}

/** What the popup's *Project:* line is told, which has to stay honest for a card that has no project. */
private fun modalProject(task: TaskItem): String = when {
    task.isBacklog -> "Other tasks · ${groupLabel(task)}"
    else -> task.project
}

/** The dot inside a bar. Priority keeps a voice on the page without owning its colour. */
private fun priorityDot(priority: String): String = when (priority) {
    "High" -> "cal-dot-high"
    "Medium" -> "cal-dot-medium"
    else -> "cal-dot-low"
}

/**
 * The legend under the month: which hue is which piece of work, and what the dots mean.
 *
 * Printed only when there is something to explain — an empty month has no bars to key, and a month holding one
 * single group gains nothing from a row that repeats its name. The priority line is the compact one, because the
 * dots are the new thing on the page and a reader who has to guess what they mean will not trust the hues.
 */
private fun DIV.calendarColourKey(onScreen: List<TaskItem>, hues: Map<String, String>) {
    if (onScreen.isEmpty()) return
    val groups = onScreen
        .map { Triple(groupKey(it), groupLabel(it), it.isBacklog) }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
    div("cal-key") {
        if (groups.size > 1) {
            groups.forEach { (key, label, backlog) ->
                // `cal-key-group` is the marker the tests read: the priority legend beside it is the same shape
                // with a different meaning, and a count of "how many groups is this month explaining" has to be
                // able to say.
                span("cal-key-item cal-key-group") {
                    span("cal-swatch") { attributes["style"] = "background-color: ${hues[key] ?: CALENDAR_HUES.first()}" }
                    +label
                    if (backlog) span("cal-tag") { +"Other" }
                }
            }
        }
        span("cal-key-prio") {
            +"Priority:"
            listOf("High" to "cal-dot-high", "Medium" to "cal-dot-medium", "Low" to "cal-dot-low").forEach { (word, cls) ->
                span("cal-key-item") {
                    span("cal-dot $cls") {}
                    +word
                }
            }
        }
    }
}

/**
 * The month the plan is reading, with the four ways to change it: step back, step on, go home, or name any month
 * at all.
 *
 * All four are controls of **one GET form onto this page**, so a browser without JavaScript simply navigates to
 * the month the pressed control names, while `app.js` fetches and swaps `#calendarMonth` when it can. The box
 * beside the steps is what makes the whole calendar reachable in one move rather than twelve clicks — the reader
 * asked to choose *all* the months, not to walk through them one at a time.
 *
 * Two names, deliberately: the step buttons answer `month`, the typed box answers `pick`. Pressing a button also
 * sends whatever the box happens to hold, so one question carrying two answers would leave the server guessing
 * which one the reader meant. The route believes `month` first — it is the control that was acted on — and reads
 * `pick` only when no step was taken. With JavaScript the question never arises: the pressed button's own value
 * is what goes on the address (see the markup contract at the top of `static/js/app.js`).
 */
private fun DIV.calendarMonthStrip(month: String, datedHere: Int) {
    form(action = "/app/calendar", method = FormMethod.get) {
        // The strip is a div *inside* the form rather than the form itself, because a form's own content context
        // is not a `DIV` one, and the step button below is drawn by a `DIV` helper the forecast sheet already
        // uses. The flex row is the div; the form is only the thing that carries it to the route.
        div("fc-strip cal-strip") {
            monthButton("‹", shiftMonth(month, -1), "Previous month")
            span("fc-month") { +monthLabel(month) }
            monthButton("›", shiftMonth(month, 1), "Next month")
            val today = currentMonthKey()
            if (month != today) {
                monthButton("This month", today, "Back to the current month")
            }
            span("task-meta") { +" · $datedHere ${if (datedHere == 1) "card" else "cards"} dated here" }
            label("cal-pick") {
                +"Jump to"
                input(type = InputType.month, name = "pick") {
                    // `yyyy-MM` is exactly the key the route reads, so the browser's own month control and the
                    // address bar can never disagree about which month is on screen.
                    value = month
                    autoComplete = "off"
                    attributes["data-autosubmit"] = "true"
                }
            }
            // Unnamed on purpose: a named submit here would answer the month question a second time beside a ‹ / ›.
            button(type = ButtonType.submit, classes = "submit btn-sm") { +"Go" }
        }
    }
}

private fun DIV.monthButton(glyph: String, target: String, hint: String) {
    button(type = ButtonType.submit, classes = "ghost-btn btn-sm fc-step") {
        // The value is an absolute `YYYY-MM`, never "one step back": reloading the address lands on the month it
        // names, and a relative control would step twice.
        name = "month"
        value = target
        attributes["title"] = hint
        +glyph
    }
}

/**
 * The two dates a card runs between, or null when it does not name a pair a bar could be drawn from.
 *
 * A card with a deadline and no start (or the reverse) is the reader's work, but it is not a span, so it stays
 * off the grid and is still counted in the list under it — the split the page has always had, stated once here
 * rather than worked out twice in two different places.
 */
private fun spanOf(task: TaskItem): Pair<LocalDate, LocalDate>? {
    val from = task.started.ifEmpty { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val to = task.due.ifEmpty { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return if (from != null && to != null && !from.isAfter(to)) from to to else null
}

/** True when any day of this card's span falls inside [month] — including a bar that runs in or out of it. */
private fun TaskItem.overlaps(month: YearMonth): Boolean {
    val (from, to) = spanOf(this) ?: return false
    return !from.isAfter(month.atEndOfMonth()) && !to.isBefore(month.atDay(1))
}

/**
 * The chip colour for a status. **Stage 96** removed the translation this sat behind: the calendar's list used to
 * print a card's own word and colour it through `taskStatusChip`, which mapped *In progress* onto the project word
 * *Ongoing*, because the two boards named the same stage differently. One vocabulary now, so one key list.
 */
private fun chipClass(status: String): String = when (status) {
    "In progress" -> "ontrack"
    "Blocked" -> "atrisk"
    "Completed" -> "done"
    "On hold" -> "onhold"
    else -> "planning"
}
