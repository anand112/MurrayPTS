package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.optGroup
import kotlinx.html.option
import kotlinx.html.script
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.unsafe
import mn.murray.pts.security.Authorization
import mn.murray.pts.service.LessonService
import mn.murray.pts.service.TaskService

private fun icon(path: String): String =
    """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">$path</svg>"""

val ICONS = mapOf(
    "folder" to icon("""<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>"""),
    "check" to icon("""<rect x="4" y="4" width="16" height="16" rx="2"/><path d="m9 12 2.5 2.5L16 10"/>"""),
    "users" to icon("""<circle cx="9" cy="8" r="3.2"/><path d="M3.5 19c.6-3.2 2.8-5 5.5-5s4.9 1.8 5.5 5"/><circle cx="17" cy="9" r="2.6"/><path d="M15.5 14.4c2.3.2 4.2 1.7 4.9 4.6"/>"""),
    "message" to icon("""<path d="M4 5h16v10H9l-5 4z"/>"""),
    "star" to icon("""<path d="m12 4 2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4L3.2 9.7l5.4-.8z"/>"""),
    "user" to icon("""<circle cx="12" cy="8" r="3.4"/><path d="M5 19.5c.8-3.6 3.4-5.5 7-5.5s6.2 1.9 7 5.5"/>"""),
    "calendar" to icon("""<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M8 3v4M16 3v4M4 10h16"/>"""),
    "shield" to icon("""<path d="M12 3 5 6v6c0 4.4 3 7.6 7 9 4-1.4 7-4.6 7-9V6z"/>"""),
    "gear" to icon("""<circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v3M12 18.2v3M2.8 12h3M18.2 12h3M5.5 5.5l2.1 2.1M16.4 16.4l2.1 2.1M18.5 5.5l-2.1 2.1M7.6 16.4l-2.1 2.1"/>"""),
    "list" to icon("""<line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/>"""),
)

data class NavItem(val id: String, val path: String, val label: String, val icon: String, val count: Int?)

/**
 * The number beside *Assigned tasks*: **every** card that page prints, finished ones included.
 *
 * Since **Stage 55** the Assigned tasks page prints two boards — the reader's own project cards and their own
 * Other-task cards — so the pill is the size of both lists together, and can never be built from cards the page
 * would not show. Each list is scoped by the same personal rule the page reads through
 * ([mn.murray.pts.service.TaskService.assignedFor] and
 * [mn.murray.pts.service.TaskService.assignedOtherFor]), so the count passes `canManage = false` regardless of
 * role: it is the reader's *own* cards, never the team's. (Management's wider read of the whole board lives on
 * the Team sheet, the project pages and the Calendar, not beside this link.)
 *
 * Since **Stage 54** it counts every card those lists hold, including the ones sitting in the Completed column —
 * so the number beside the link is exactly the count of cards the reader's boards print, with no card excluded.
 * This retires Stage 27's exception, which left Completed out so the figure would fall as work finished; the
 * reader asked for the opposite — the pill to answer *how many tasks are assigned to me*, finished ones being
 * tasks all the same. (What still distinguishes an open card is the Completed column itself and the boards' own
 * per-column counts, plus the Team sheet's OPEN TASKS figure, which is deliberately a count of *open* work only
 * and is untouched here.)
 */
private fun visibleTaskCount(employeeNumber: String): Int =
    TaskService.assignedFor(employeeNumber, canManage = false).size +
        TaskService.assignedOtherFor(employeeNumber).size

fun navItems(employeeNumber: String): List<NavItem> = listOf(
    NavItem("projects", "/app/projects", "Projects", ICONS["folder"]!!, DemoData.projects.size),
    NavItem("assigned-tasks", "/app/assigned-tasks", "Assigned tasks", ICONS["check"]!!, visibleTaskCount(employeeNumber)),
    NavItem("calendar", "/app/calendar", "Calendar", ICONS["calendar"]!!, null),
    NavItem("team", "/app/team", "Team", ICONS["users"]!!, DemoData.team.size),
    // The money sheet has one line per project per month, so the number beside it is the number of
    // projects on the sheet — the legacy hour-planning rows the pill used to count are still in the
    // `forecasts` table for the importer's sake, but the page does not read them (V8).
    NavItem("forecasts", "/app/forecasts", "Forecasts", ICONS["message"]!!, DemoData.projects.size),
    // The lesson pill counts the active sheet only: a lesson somebody archived has been read and put
    // away, so the number beside the page is the number of lessons still waiting to be talked about.
    NavItem("lessons", "/app/lessons", "Lesson learnt", ICONS["star"]!!, LessonService.activeCount()),
    NavItem("other-tasks", "/app/other-tasks", "Other tasks", ICONS["user"]!!, DemoData.otherTasks.size),
    NavItem("todo", "/app/todo", "To-do", ICONS["list"]!!, DemoData.todos.count { it.owner == employeeNumber }),
    NavItem("access-control", "/app/access-control", "Access control", ICONS["shield"]!!, null),
    NavItem("settings", "/app/settings", "Settings", ICONS["gear"]!!, null),
)

/**
 * The sections whose link is a permission rather than everyone's page, and the chips that earn it
 * (Stage 110). The picture has no "view the backlog" or "view settings" chip, so each of these is gated on
 * the chip of the control inside it: the backlog board is edited by *Edit any task* or reshaped by *Manage
 * buckets*; Access control is the two people chips; Settings holds the three panels those chips stand for.
 *
 * They are kept here rather than dropped from [navItems] because that list does double duty — the notification
 * bell resolves a stored item's section back to a path through it (see [mn.murray.pts.notificationBell]). Take
 * the items out of [navItems] and a Viewer's backlog-card notification silently falls through to /app/projects.
 * The routes check the permission again; a hidden link is presentation, never the right itself.
 */
val NAV_PERMISSIONS: Map<String, Set<Permission>> = mapOf(
    "forecasts" to setOf(Permission.VIEW_FORECASTS),
    "other-tasks" to setOf(Permission.EDIT_ANY_TASK, Permission.MANAGE_BUCKETS),
    "access-control" to setOf(Permission.MANAGE_EMPLOYEES, Permission.MANAGE_ROLES),
    "settings" to Authorization.SETTINGS,
)

/** [navItems] as one account's sidebar should actually print it: a gated link only for a role holding its chip. */
fun navItemsFor(employeeNumber: String): List<NavItem> {
    val permissions = Authorization.permissionsOf(employeeNumber)
    return navItems(employeeNumber).filter { item ->
        val needed = NAV_PERMISSIONS[item.id] ?: return@filter true
        needed.any { it in permissions }
    }
}

/**
 * The department a member is presented under. The roster carries blanks for anyone added without
 * one, and filing them under [UNASSIGNED_DEPARTMENT] keeps them on the page instead of at the end
 * of the sort where nobody scrolls.
 */
const val UNASSIGNED_DEPARTMENT = "Unassigned"

fun departmentLabel(member: Member): String = member.department.trim().ifBlank { UNASSIGNED_DEPARTMENT }

/**
 * The team in the order every page presents it: department A→Z, then name A→Z. The roster itself is
 * stored in hire order, which reads as shuffled once twelve departments are in play, so the Team
 * table, its assign form and every manager/assignee picker all sort through here rather than each
 * picking their own order.
 */
fun teamByDepartment(): List<Member> =
    DemoData.team.sortedWith(compareBy({ departmentLabel(it) }, { it.name.trim() }))

/**
 * [teamByDepartment] cut into one run per department, in the same order. `groupBy` preserves
 * insertion order, so sorting first and grouping second is what makes the headings appear
 * alphabetically without a second sort of the keys.
 */
fun teamGroupedByDepartment(): List<Pair<String, List<Member>>> =
    teamByDepartment().groupBy { departmentLabel(it) }.toList()

/**
 * The member choices in a `<select>`, as one `<optgroup>` per department. Every picker on the app
 * goes through this so the Team page and its dropdowns agree on the order.
 *
 * [valueOf] differs per form, not per taste: the Team assign form and the task popup post an
 * employee number, while both project manager forms post a name, which is what their routes look
 * up. [selectedValue] is whatever the form currently holds, so a rejected submit comes back with
 * the same person picked.
 */
fun kotlinx.html.SELECT.optGroupPerMember(
    selectedValue: String,
    valueOf: (Member) -> String = { it.employeeNumber },
) {
    teamGroupedByDepartment().forEach { (department, members) ->
        optGroup {
            attributes["label"] = department
            members.forEach { member ->
                option {
                    value = valueOf(member)
                    selected = valueOf(member) == selectedValue
                    +"${member.employeeNumber} · ${member.name} (${member.position})"
                }
            }
        }
    }
}

// Front-end assets for the app shell are served as external static files (Stage 7):
//   - /static/css/base.css + /static/css/app.css  — linked in appPage's <head>
//   - /static/js/theme.js                         — emitted by themeScript() in Views.kt
//   - /static/js/app.js                           — delegated [data-autosubmit] / [data-confirm]
//     behaviours, replacing the inline onchange/onclick attributes the legacy views emitted.
// Per-page scripts are linked by the pages that need them (kanban.js, calendar-modal.js,
// team.js, todo.js, settings-accounts.js, access-control.js). notifications.js is linked by
// the bell itself, so it travels with the markup it drives and every page gets it for free.
// The former inline PAGE_CSS/APP_CSS/THEME_JS/LOGO_SVG Kotlin constants were removed.
// Nav icons remain inline SVG (data, not executable — CSP-safe) via unsafe { +item.icon }.

/**
 * The authenticated shell: sidebar, a page head, then the page body.
 *
 * [actions] is an optional slot rendered in the page head to the right of the title —
 * used by the Projects page for its `+ New project` opener. Pages that leave it out
 * render the heading alone, exactly as before.
 *
 * [subtitle] is the short grey line beside the title, for the pages whose name needs one: *Other tasks*
 * carries "tasks outside of projects", which is the sentence its own board used to repeat under a second
 * heading. One head per page is the reason it is here rather than in the page body — a control that changes
 * the whole page belongs opposite the page's name, not beneath it.
 */
fun HTML.appPage(
    session: UserSession,
    section: String,
    title: String,
    subtitle: String? = null,
    actions: (DIV.() -> Unit)? = null,
    block: DIV.(UserSession) -> Unit,
) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        // The tab is one name on every page (Stage 115): the page's own name is the on-screen heading, not the tab.
        title("MPTS")
        unsafe {
            +("""<link rel="icon" type="image/png" href="/static/assets/favicon.png">""")
            +("""<link rel="stylesheet" href="/static/css/base.css">""")
            +("""<link rel="stylesheet" href="/static/css/app.css">""")
        }
    }
    body("app-body") {
        div("app") {
            aside("sidebar") {
                a(href = "/app") {
                    img(src = "/static/assets/mms-logo.png", alt = "MURRAY Mining Services Mongolia") {
                        attributes["class"] = "logo-img side-logo"
                    }
                }
                div("side-caption") {
                    +"PROJECT TRACKING SYSTEM "
                    span("accent") { +"(PTS)" }
                }
                nav {
                    navItemsFor(session.employeeNumber).forEach { item ->
                        a(classes = if (item.id == section) "nav-item active" else "nav-item") {
                            href = item.path
                            unsafe { +item.icon }
                            span("label") { +item.label }
                            item.count?.let { span("pill") { +"$it" } }
                        }
                    }
                }
                div("pinned") {
                    h2 { +"Pinned projects" }
                    val pinned = pinnedIds(session.employeeNumber).mapNotNull { pid -> DemoData.projects.firstOrNull { it.id == pid } }
                    if (pinned.isEmpty()) {
                        p("empty") {
                            +"No pinned projects yet. Pin a project from its board, or from the list on the Projects page, to see it here."
                        }
                    } else {
                        pinned.forEach { project ->
                            div("pinned-row") {
                                a(href = "/app/projects/${project.id}") { +project.name }
                                form(action = "/app/projects/${project.id}/pin", method = FormMethod.post) {
                                    button(type = ButtonType.submit, classes = "pin-btn on") { +"×" }
                                }
                            }
                        }
                    }
                }
                div("side-foot") {
                    val user = DemoData.userFor(session.employeeNumber)
                    div("user-row") {
                        div("avatar") { +initials(user.name) }
                        div("user-meta") {
                            div("user-name") { +user.name }
                            div("user-role") { +user.position }
                        }
                        div("icon-btns") {
                            button(type = ButtonType.button, classes = "icon-btn") {
                                id = "accentToggle"
                                attributes["aria-label"] = "Cycle accent color"
                                +"🎨"
                            }
                            button(type = ButtonType.button, classes = "icon-btn") {
                                id = "themeToggle"
                                attributes["aria-label"] = "Toggle color theme"
                                +"🌙"
                            }
                        }
                    }
                    a(href = "/logout", classes = "logout") { +"Log out" }
                }
            }
            main("main") {
                div("page-head") {
                    h1 {
                        +title
                        subtitle?.let { span("page-sub") { +it } }
                    }
                    div("page-right") {
                        actions?.let { render -> div("page-actions") { render() } }
                        // The bell sits at the far right of every authenticated page, after the page's
                        // own action buttons, so it never competes with them for position.
                        notificationBell(session, section)
                    }
                }
                div("content") { block(session) }
            }
        }
        themeScript()
        // Delegated handlers for [data-autosubmit] / [data-confirm]; must load on every
        // authenticated page because the sidebar pin forms and nav use them too.
        script(src = "/static/js/app.js") {}
    }
}

/**
 * The two letters an avatar prints for a name: the first letter of the first two words, which is what
 * the roster's own names reduce to ("Anar Purev-Osor" → `AP`).
 */
fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")

/**
 * The avatar's colour, from the account rather than from the row: the same person is the same colour on
 * every sheet, which is what makes a column of names readable at a glance.
 *
 * The six tints are the ones [mn.murray.pts.appPage] ships in `app.css` as `.ll-a1`…`.ll-a6` — named
 * where they first appeared, and a palette every sheet now draws from rather than a second set of
 * colour rules per page.
 */
fun avatarTint(employeeNumber: String): String =
    AVATAR_TINTS[employeeNumber.sumOf { it.code } % AVATAR_TINTS.size]

private val AVATAR_TINTS = listOf("ll-a1", "ll-a2", "ll-a3", "ll-a4", "ll-a5", "ll-a6")
