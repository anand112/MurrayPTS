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
import kotlinx.html.script
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.unsafe

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

fun navItems(employeeNumber: String): List<NavItem> = listOf(
    NavItem("projects", "/app/projects", "Projects", ICONS["folder"]!!, DemoData.projects.size),
    NavItem("assigned-tasks", "/app/assigned-tasks", "Assigned tasks", ICONS["check"]!!, DemoData.assignedTasks.size),
    NavItem("calendar", "/app/calendar", "Calendar", ICONS["calendar"]!!, null),
    NavItem("team", "/app/team", "Team", ICONS["users"]!!, DemoData.team.size),
    NavItem("forecasts", "/app/forecasts", "Forecasts", ICONS["message"]!!, DemoData.forecasts.size),
    NavItem("lessons", "/app/lessons", "Lesson learnt", ICONS["star"]!!, DemoData.lessons.size),
    NavItem("other-tasks", "/app/other-tasks", "Other tasks", ICONS["user"]!!, DemoData.otherTasks.size),
    NavItem("todo", "/app/todo", "To-do", ICONS["list"]!!, DemoData.todos.count { it.owner == employeeNumber }),
    NavItem("access-control", "/app/access-control", "Access control", ICONS["shield"]!!, null),
    NavItem("settings", "/app/settings", "Settings", ICONS["gear"]!!, null),
)

// Front-end assets for the app shell are served as external static files (Stage 7):
//   - /static/css/base.css + /static/css/app.css  — linked in appPage's <head>
//   - /static/js/theme.js                         — emitted by themeScript() in Views.kt
//   - /static/js/app.js                           — delegated [data-autosubmit] / [data-confirm]
//     behaviours, replacing the inline onchange/onclick attributes the legacy views emitted.
// Per-page scripts are linked by the pages that need them (kanban.js, calendar-modal.js,
// team.js, todo.js, settings-accounts.js, access-control.js).
// The former inline PAGE_CSS/APP_CSS/THEME_JS/LOGO_SVG Kotlin constants were removed.
// Nav icons remain inline SVG (data, not executable — CSP-safe) via unsafe { +item.icon }.

fun HTML.appPage(session: UserSession, section: String, title: String, block: DIV.(UserSession) -> Unit) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title("MURRAY PTS — $title")
        unsafe {
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
                    navItems(session.employeeNumber).forEach { item ->
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
                div("page-head") { h1 { +title } }
                div("content") { block(session) }
            }
        }
        themeScript()
        // Delegated handlers for [data-autosubmit] / [data-confirm]; must load on every
        // authenticated page because the sidebar pin forms and nav use them too.
        script(src = "/static/js/app.js") {}
    }
}

private fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
