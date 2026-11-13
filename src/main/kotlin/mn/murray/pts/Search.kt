package mn.murray.pts

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.p
import kotlinx.html.span
import mn.murray.pts.domain.Permission
import mn.murray.pts.security.Authorization
import mn.murray.pts.service.LessonService

/**
 * The search page (Stage 134): one box over the sheets a reader is most likely to be hunting, behind the bottom
 * bar's *Search* tab.
 *
 * It is a page of its own rather than a box in the page head, which was the reader's choice when the checklist's
 * bottom bar named a *Search* tab: a tab has to land somewhere, and a popup opened from a tab has nothing to be
 * the back button's answer to. The form is a plain GET, so the words a reader typed are in the address, a shared
 * link opens the same results, and a browser with no JavaScript gets the same page the script would have asked
 * for — the one difference being that nothing focuses the box for them.
 *
 * **Nothing here is a new way into a row.** Every result is a link to the page the row already lives on, and the
 * one group that is not everybody's — the shared Other-tasks board — is offered only to the roles whose sidebar
 * carries that link, so the page never advertises the whole backlog to a reader whose own Other-tasks page is
 * their slice of it (Stage 51). That is [SEARCH_PERMISSIONS]' whole job, and it is the same map the sidebar reads
 * ([NAV_PERMISSIONS]) rather than a second opinion about who may see what. The page itself is *not* a permission:
 * it answers every signed-in reader, which is why the gate is about whose board these rows belong to and not about
 * a door that would refuse.
 */

/** One result: the words printed, the sentence under them, and the page the row lives on. */
data class SearchRow(val title: String, val detail: String, val href: String)

/** One sheet's results, in the order the page prints them. [id] is the group's own name, and the tests read it. */
data class SearchGroup(val id: String, val label: String, val rows: List<SearchRow>)

/**
 * The shortest word the page will look for. Two letters, because one is the letter *a* and a sheet of everything
 * containing "a" is not an answer to anything — and because a phone keyboard offers its results as they are typed,
 * where the cost of a too-eager search is a screen of noise after every keystroke.
 */
const val SEARCH_MIN: Int = 2

/** The longest title the page prints before cutting it, so one long lesson cannot become a row of its own. */
private const val TITLE_MAX: Int = 90

/**
 * The groups that are one role's board rather than everybody's, and the chips that earn them. Read from
 * [NAV_PERMISSIONS] by section id at [searchGroups], so this map is a list of *which* groups are gated and the
 * sidebar stays the only place the chips themselves are named.
 *
 * The chips gate the *group*, not the page: `/app/other-tasks` answers every signed-in reader, an ordinary account
 * on their own slice (Stage 51). These rows are the shared board's cards, so they follow the sidebar link.
 */
private val SEARCH_PERMISSIONS: Map<String, Set<Permission>> = mapOf(
    "other-tasks" to (NAV_PERMISSIONS["other-tasks"] ?: emptySet()),
)

/** Whether one of [fields] carries [needle], which is already lowercased and trimmed by the caller. */
private fun hit(needle: String, vararg fields: String?): Boolean =
    fields.any { it != null && it.lowercase().contains(needle) }

/** The blank parts dropped, so a row never prints `S-01 · · Manager:` for a project nobody manages. */
private fun joined(vararg parts: String?): String =
    parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" · ")

/** [title] cut to [TITLE_MAX] with an ellipsis, on the one boundary that matters: a word is never cut in half. */
private fun cut(title: String): String {
    val text = title.trim()
    if (text.length <= TITLE_MAX) return text
    val head = text.take(TITLE_MAX)
    val lastSpace = head.lastIndexOf(' ')
    return (if (lastSpace > TITLE_MAX / 2) head.take(lastSpace) else head).trimEnd() + "…"
}

/**
 * Every group with at least one result, in the order the page prints them: projects, project cards, Other-task
 * cards, lessons, the reader's own to-do, people.
 *
 * [employeeNumber] does two jobs and both are the reader's own rights, never a filter somebody else chose: it
 * decides which groups are offered at all, and it is the only reason the to-do group can exist, because a to-do
 * item belongs to one account and this page is not the place a second person's list becomes everybody's business.
 */
fun searchGroups(query: String, employeeNumber: String): List<SearchGroup> {
    val needle = query.trim().lowercase()
    if (needle.length < SEARCH_MIN) return emptyList()
    val permissions = Authorization.permissionsOf(employeeNumber)
    return listOf(
        projectRows(needle),
        taskRows(needle),
        otherTaskRows(needle),
        lessonRows(needle),
        todoRows(needle, employeeNumber),
        peopleRows(needle),
    ).filter { group ->
        val needed = SEARCH_PERMISSIONS[group.id] ?: return@filter true
        needed.any { it in permissions }
    }.filter { it.rows.isNotEmpty() }
}

private fun projectRows(needle: String): SearchGroup = SearchGroup(
    id = "projects",
    label = "Projects",
    rows = DemoData.projects
        .filter { hit(needle, it.code, it.name, it.manager, it.client, it.status) }
        .map { project ->
            SearchRow(
                title = cut(project.name),
                detail = joined(project.code, project.status, project.client, "Manager: ${project.manager}"),
                href = "/app/projects/${project.id}",
            )
        },
)

/**
 * The cards that belong to a project. Each one links to its own popup on the project's page — `?edit=<id>` is the
 * key that page has always read — and a card whose project has gone links to the assigned board instead, because
 * a row with nowhere to be opened is still a row worth reporting.
 */
private fun taskRows(needle: String): SearchGroup = SearchGroup(
    id = "tasks",
    label = "Tasks",
    rows = DemoData.assignedTasks
        .filter { !it.archived && hit(needle, it.title, it.project, it.assignee, it.status, it.projectBucket) }
        .map { task ->
            SearchRow(
                title = cut(task.title),
                detail = joined(task.project, task.status, task.assignee, task.due),
                href = if (task.projectId > 0) "/app/projects/${task.projectId}?edit=${task.id}" else "/app/assigned-tasks",
            )
        },
)

/** The shared Other-tasks board's cards. The group is [SEARCH_PERMISSIONS]-gated, because the board is one role's. */
private fun otherTaskRows(needle: String): SearchGroup = SearchGroup(
    id = "other-tasks",
    label = "Other tasks",
    rows = DemoData.otherTasks
        .filter { !it.archived && hit(needle, it.title, it.bucket, it.assignee, it.status) }
        .map { task ->
            SearchRow(
                title = cut(task.title),
                detail = joined(task.bucket, task.status, task.assignee, task.due),
                href = "/app/other-tasks?edit=${task.id}",
            )
        },
)

/**
 * The lesson sheet's active rows, read through [LessonService.board] rather than the repository: that call is the
 * sheet's own reading, archive left out, and a search that went around it would be a second place to keep the
 * "what is a lesson everybody can see" rule true.
 */
private fun lessonRows(needle: String): SearchGroup = SearchGroup(
    id = "lessons",
    label = "Lesson learnt",
    rows = LessonService.board().active
        .filter { hit(needle, it.text, it.projectName, it.projectCode, it.employeeName, it.taskTitle) }
        .map { entry ->
            SearchRow(
                title = cut(entry.text),
                detail = joined(entry.projectCode, entry.projectName, entry.employeeName, entry.loggedOn),
                href = "/app/lessons?edit=${entry.id}",
            )
        },
)

/** The reader's own to-do items, and nobody else's: `owner` is the same word the To-do page counts the pill by. */
private fun todoRows(needle: String, employeeNumber: String): SearchGroup {
    val lists = DemoData.todoLists.associateBy { it.id }
    return SearchGroup(
        id = "todo",
        label = "To-do",
        rows = DemoData.todos
            .filter { it.owner == employeeNumber && hit(needle, it.title, lists[it.list]?.name) }
            .map { item ->
                SearchRow(
                    title = cut(item.title),
                    detail = joined(if (item.done) "Done" else "Open", lists[item.list]?.name, item.due),
                    href = "/app/todo",
                )
            },
    )
}

/**
 * People, on the roster's own order and through [departmentLabel], so a result reads the same department the Team
 * sheet files that person under rather than the blank the roster carries for somebody added without one.
 */
private fun peopleRows(needle: String): SearchGroup = SearchGroup(
    id = "people",
    label = "People",
    rows = teamByDepartment()
        .filter { hit(needle, it.name, it.position, it.department, it.employeeNumber) }
        .map { member ->
            SearchRow(
                title = member.name,
                detail = joined(member.position, departmentLabel(member), member.employeeNumber),
                href = "/app/team",
            )
        },
)

/**
 * The page: the box, then one sentence saying what was looked for and what came of it, then a panel per group.
 *
 * The sentence is printed in all three of its states — nothing typed yet, a word with no answer, and a word with
 * one — because a search page that stays silent on an empty result is indistinguishable from a search page that
 * broke. The panels reuse the house markup (`.card-panel`, `.panel-caption`, `.row-link`) rather than a table,
 * because a list of links wraps at any width and a sheet of them would need Stage 120's horizontal scroll on the
 * one page whose whole job is being readable on a phone.
 */
fun DIV.searchContent(session: UserSession, query: String) {
    div("board-results") {
        form(action = "/app/search", method = FormMethod.get, classes = "board-toolbar search-toolbar") {
            div("board-search") {
                input(type = InputType.text, name = "q") {
                    id = "appSearch"
                    value = query
                    placeholder = "Search projects, tasks, lessons, to-do and people"
                    autoComplete = "off"
                    // The reader has just arrived from a tab press with the box empty, or from a submit with
                    // their word in it; only the second is worth taking focus back to.
                    if (query.isNotBlank()) autoFocus = true
                }
                button(type = ButtonType.submit, classes = "submit btn-sm") { +"Search" }
            }
        }

        val trimmed = query.trim()
        val groups = searchGroups(trimmed, session.employeeNumber)
        val found = groups.sumOf { it.rows.size }

        p("panel-caption search-said") {
            +when {
                // Under the floor, which includes the empty box: the page has not looked, so it says what it needs
                // rather than "nothing matches". One letter used to fall through to the no-answer sentence below,
                // which told the reader their word had been searched for and found wanting when it never was.
                trimmed.length < SEARCH_MIN ->
                    "Type two letters or more. One box looks across projects, tasks, lessons, your own to-do " +
                        "and people, and every result opens the page that row lives on."
                found == 0 -> "Nothing matches \"$trimmed\"."
                else -> "\"$trimmed\" — $found ${if (found == 1) "result" else "results"} in " +
                    "${groups.size} ${if (groups.size == 1) "place" else "places"}."
            }
        }

        groups.forEach { group ->
            div("card-panel search-group") {
                attributes["data-search-group"] = group.id
                p("panel-caption") { +"${group.label} · ${group.rows.size}" }
                div("search-list") {
                    group.rows.forEach { row ->
                        a(classes = "row-link search-row") {
                            href = row.href
                            span("search-title") { +row.title }
                            if (row.detail.isNotEmpty()) span("search-detail") { +row.detail }
                        }
                    }
                }
            }
        }
    }
}
