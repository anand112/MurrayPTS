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
import kotlinx.html.FlowContent
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

/**
 * The house glyph box: strokes in the theme's own ink, so an icon needs no font and no second colour. [iconFilled]
 * is its other half (Stage 129) — the same box drawn in solid ink instead of strokes. Both take an optional class
 * because `app.css` sometimes has to choose between the two drawings of one control; every icon that only ever has
 * one drawing leaves it empty, and its markup is then byte-identical to what it was before.
 */
private fun icon(path: String, classes: String = ""): String {
    val box = if (classes.isEmpty()) "" else " class=\"$classes\""
    return """<svg$box viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">$path</svg>"""
}

/**
 * The solid drawing (Stage 129): the glyph's ink fills the shape instead of running around it. A caller gives its
 * `d` the `fill-rule="evenodd"` attribute, which is what lets a subpath read as a hole — a chevron or a divider
 * cut out of the block, showing whatever the button is standing on, rather than painted over it.
 */
private fun iconFilled(path: String, classes: String = ""): String {
    val box = if (classes.isEmpty()) "" else " class=\"$classes\""
    return """<svg$box viewBox="0 0 24 24" fill="currentColor" stroke="none">$path</svg>"""
}

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
    // The three the bottom tab bar needs and the sidebar never did (Stage 134). "home" is not "folder": the
    // bar's first tab is the page `/app` already redirects to, and a phone reader reads a roof, not a drawer
    // of files. "menu" is three bars, the same drawing the hamburger wears, because it is the same disclosure.
    "home" to icon("""<path d="M4 11 12 4l8 7"/><path d="M6.5 10.2V19h11v-8.8"/>"""),
    "search" to icon("""<circle cx="11" cy="11" r="6"/><path d="m20 20-4.6-4.6"/>"""),
    "menu" to icon("""<line x1="4" y1="7" x2="20" y2="7"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="17" x2="20" y2="17"/>"""),
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
 * (Stage 110). The picture has no "view the backlog" chip, so each of these is gated on the chip of the
 * control inside it: the backlog board is edited by *Edit any task* or reshaped by *Manage buckets*;
 * Access control is the two people chips.
 *
 * Settings is deliberately **not** here since Stage 123: it is every account's page, because the one
 * thing every account owns — *Your account* and its change-password form — lives on it. The panels inside
 * it stay gated on their own chips, so an Employee sees the one section and nothing else.
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
//   - /static/js/dialog.js                        — shared popup focus trap, close path, scroll lock, exit motion
//   - /static/js/forms.js                         — inline browser checks, first-error focus, one-press sends, "Saved."
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
                // Named so the page head's hamburger can say what it opens (`aria-controls`) and so the
                // drawer's own script has one element to look for (Stage 119).
                id = "appSidebar"
                a(href = "/app") {
                    img(src = "/static/assets/mms-logo.png", alt = "MURRAY Mining Services Mongolia") {
                        attributes["class"] = "logo-img side-logo"
                    }
                }
                div("side-caption") {
                    +"PROJECT TRACKING SYSTEM "
                    span("accent") { +"(PTS)" }
                }
                navCloseButton()
                nav {
                    attributes["aria-label"] = "Main"
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
                                // **Stage 127: the ID comes first, and it is the same one the Projects sheet's ID
                                // column prints** — `projects.code`, the number a reader actually quotes. The name
                                // stays the only link so the row has one stop, and the × still unpins for this
                                // account alone.
                                span("code") { +project.code }
                                a(href = "/app/projects/${project.id}") { +project.name }
                                form(action = "/app/projects/${project.id}/pin", method = FormMethod.post) {
                                    button(type = ButtonType.submit, classes = "pin-btn on") {
                                        attributes["aria-label"] = "Unpin ${project.name}"
                                        +"×"
                                    }
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
                    // The drawer's opener and the title share this wrapper so the page's actions and the bell
                    // stay at the far right as they always did — a third child of .page-head would otherwise
                    // pull the heading to the middle of the row (Stage 119).
                    div("page-title") {
                        navToggle()
                        h1 {
                            +title
                            subtitle?.let { span("page-sub") { +it } }
                        }
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
            // The page under an open drawer, so a press outside the menu closes it (Stage 119). It is
            // markup on every page and only ever shown by the breakpoint's own rule.
            div("nav-backdrop") {
                id = "navBackdrop"
                attributes["aria-hidden"] = "true"
            }
            // The desktop reopen tab (Stage 130), and the reason it is not inside the sidebar: the button that
            // hides the column lives in that column, so once it is hidden there is nothing left in it to press.
            // Fixed to the left edge, and shown by `app.css` only while a script has hidden the panel.
            sidebarTab()
            // The phone's own row of tabs (Stage 134), a sibling of the drawer's backdrop for the same reason
            // the reopen tab is: it belongs to the page, not to the panel it can open. `app.css` hides it above
            // 900px, where the sidebar is already on screen and a second copy of it would be a second answer to
            // a question nobody asked.
            mobileTabBar(session, section)
        }
        themeScript()
        // Delegated handlers for [data-autosubmit] / [data-confirm]; must load on every
        // authenticated page because the sidebar pin forms and nav use them too.
        script(src = "/static/js/app.js") {}
        script(src = "/static/js/dialog.js") {}
        script(src = "/static/js/forms.js") {}
    }
}

/**
 * The hamburger in the page head (Stage 119): below 900px the sidebar becomes a drawer and this is the
 * only control that opens it, so it is printed on every page and hidden above the breakpoint by `app.css`
 * rather than left out of the markup — the shell is drawn by one function for every page, and a button
 * that came and went with the window would have to be redrawn to appear.
 *
 * It is a real `<button>` with `aria-controls`/`aria-expanded` because it is a disclosure, not a link;
 * `app.js` flips the state and the label, and the three bars are spans so no icon font or inline SVG is
 * needed for a control that is three lines (data, not executable, either way).
 */
private fun DIV.navToggle() {
    button(type = ButtonType.button, classes = "nav-toggle") {
        id = "navToggle"
        attributes["aria-controls"] = "appSidebar"
        attributes["aria-expanded"] = "false"
        attributes["aria-label"] = "Open navigation"
        repeat(3) { span("nav-bar") { } }
    }
}

/** One tab of [mobileTabBar]: the section it lights up for, the page it opens, and the word under its glyph. */
data class MobileTab(val id: String, val path: String, val label: String, val icon: String)

/**
 * The phone's bottom bar (Stage 134), in the order the design checklist names them: *Home, My tasks, To-do,
 * Search, Menu*. Four are links and the fifth is [mobileTabBar]'s button, so the list holds only the four.
 *
 * Three of the four are the sidebar's own rows under the names a thumb uses: *Home* is `/app/projects`, which
 * is where `/app` has always redirected, so the bar does not invent a landing page; *My tasks* is the sidebar's
 * *Assigned tasks*, because on a phone the word "assigned" is the system's and the word "my" is the reader's;
 * and *To-do* is the same page under the same name. *Search* is the one destination the sidebar does not have —
 * Stage 134's new `/app/search`, a page of its own rather than a box in the head, because a bar tab has to land
 * somewhere and a popup opened from a tab has nothing to be the back button's answer to.
 */
val MOBILE_TABS: List<MobileTab> = listOf(
    MobileTab("projects", "/app/projects", "Home", ICONS["home"]!!),
    MobileTab("assigned-tasks", "/app/assigned-tasks", "My tasks", ICONS["check"]!!),
    MobileTab("todo", "/app/todo", "To-do", ICONS["list"]!!),
    MobileTab("search", "/app/search", "Search", ICONS["search"]!!),
)

/**
 * [MOBILE_TABS] as one account's bar should print it, through the same gate the sidebar reads: a tab whose
 * section is a permission is offered only to a role holding one of its chips, so the bar can never hand a
 * reader a door the sidebar has already taken off the wall. The four tabs today are everybody's pages, which
 * makes this filter a no-op — and the reason it is here anyway is the fifth tab anybody adds later.
 */
fun mobileTabsFor(employeeNumber: String): List<MobileTab> {
    val permissions = Authorization.permissionsOf(employeeNumber)
    return MOBILE_TABS.filter { tab ->
        val needed = NAV_PERMISSIONS[tab.id] ?: return@filter true
        needed.any { it in permissions }
    }
}

/**
 * The bottom tab bar itself: printed on every authenticated page and hidden above 900px by `app.css`, exactly
 * like [navToggle] and for the same reason — the shell is drawn by one function, so a control that came and went
 * with the window's width would have to be redrawn to appear, and a reader who turns their phone sideways would
 * watch the bar vanish from under their thumb.
 *
 * The active tab is named by the page's own `section`, the same word the sidebar's `.nav-item active` is chosen
 * by, so the two can never disagree about where the reader is. *Menu* is a `<button>` with `aria-controls` and
 * `aria-expanded` rather than a link: it has no destination, it opens the drawer Stage 119 built, and `app.js`
 * keeps its state in step with the hamburger's because the two are one disclosure seen from either end of the
 * screen. `padding-bottom: env(safe-area-inset-bottom)` in the stylesheet is what keeps the row off the gesture
 * bar of a phone that has one.
 */
private fun FlowContent.mobileTabBar(session: UserSession, section: String) {
    nav("mtabbar") {
        id = "mobileTabs"
        attributes["aria-label"] = "Quick navigation"
        mobileTabsFor(session.employeeNumber).forEach { tab ->
            val active = tab.id == section
            a(classes = if (active) "mtab active" else "mtab") {
                href = tab.path
                if (active) attributes["aria-current"] = "page"
                unsafe { +tab.icon }
                span("mtab-label") { +tab.label }
            }
        }
        button(type = ButtonType.button, classes = "mtab mtab-menu") {
            id = "mtabMenu"
            attributes["aria-controls"] = "appSidebar"
            attributes["aria-expanded"] = "false"
            attributes["aria-label"] = "Open menu"
            unsafe { +ICONS["menu"]!! }
            span("mtab-label") { +"Menu" }
        }
    }
}

/**
 * The drawer's own close button (Stage 128): a phone reader who opened the menu gets to shut it from where
 * their thumb already is, instead of hunting for the hamburger behind the panel they just slid over.
 *
 * It is printed inside `#appSidebar`, after the logo and caption and before the nav links, for two reasons.
 * `app.js` opens the drawer by focusing `navDrawer.querySelector('a, button')`, and the logo link still has
 * to be that first answer — a reader sent to a close button on opening is being told to leave before they
 * have read anything. And it lives in the panel rather than beside the hamburger because the panel is what it
 * acts on: **since Stage 130 the same button answers at the other width too**, where it sits in
 * the sidebar's own top-right corner and a press hides the column so the page takes the whole row. The
 * stylesheet keeps the two meanings apart without a second breakpoint — a default `display: none` for a reader
 * with no script to hide anything with, the desktop rule before the `@media` block, and the drawer's own rules
 * inside it, where they outrank it.
 *
 * Like [navToggle] it is a real `<button>` with `aria-controls`, not a link — it is a disclosure, and it
 * carries no destination. It does not repeat `aria-expanded`: the state belongs to the control that is always
 * there to be asked — the hamburger on a phone page, the reopen tab on a desktop one. The `aria-label` printed
 * here is the drawer's meaning, because that is the only meaning markup can know: `app.js` calls it *Hide
 * sidebar* once it can see the window is wide.
 *
 * It prints **two** drawings of one glyph (Stage 129), and only one of them is ever visible: `app.css` gives
 * the light theme the outlined panel, where a stroke reads crisply against a pale card, and the dark theme the
 * solid one, where weight is what survives a dark background. The button's own box, name and behaviour are the
 * same in either case — the theme chooses the ink, not the control.
 */
private fun FlowContent.navCloseButton() {
    button(type = ButtonType.button, classes = "nav-close") {
        id = "navClose"
        attributes["aria-controls"] = "appSidebar"
        attributes["aria-label"] = "Close navigation"
        unsafe {
            +icon(
                """<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18"/><path d="m16 15-3-3 3-3"/>""",
                classes = "glyph-outline",
            )
            +iconFilled(
                """<path fill-rule="evenodd" d="M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M9 3h1.6v18H9z M15.5 7.5 11 12l4.5 4.5h2L13 12l4.5-4.5z"/>""",
                classes = "glyph-filled",
            )
        }
    }
}

/**
 * The desktop reopen tab (Stage 130): the slim control on the page's left edge that brings the sidebar back
 * after the corner button in [navCloseButton] has hidden it.
 *
 * It is printed as a sibling of the sidebar rather than inside it for the one reason that matters — the panel it
 * opens is the thing that would have to hold it while being absent. `app.css` gives it to the page only under
 * `html.js body.sidebar-hidden`, the state `app.js` reads from `localStorage` before the first paint, so a
 * reader who hid the column is not shown a reopen control they cannot act on, and a reader with no script at all
 * is never shown one. The same rule is inside the drawer's own breakpoint: below 900px the sidebar is a drawer
 * that the hamburger opens, whatever this browser remembered from a wider window.
 *
 * Unlike [navToggle], whose `aria-expanded` starts false because the drawer starts shut, this one is the desktop
 * opener for a panel that is *in the page* until asked to leave, so it starts true and `app.js` flips it with the
 * state. It carries one drawing, in strokes, like every other icon in the shell: Stage 129's two-face treatment
 * belongs to the button the reader asked about, and the chevron here points the way the panel comes from.
 */
private fun DIV.sidebarTab() {
    button(type = ButtonType.button, classes = "sidebar-tab") {
        id = "sidebarTab"
        attributes["aria-controls"] = "appSidebar"
        attributes["aria-expanded"] = "true"
        attributes["aria-label"] = "Show sidebar"
        unsafe {
            +icon("""<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18"/><path d="m13 9 3 3-3 3"/>""")
        }
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
