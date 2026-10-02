package mn.murray.pts

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
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.unsafe

private fun icon(path: String): String =
    """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">$path</svg>"""

val ICONS = mapOf(
    "folder" to icon("""<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>"""),
    "check" to icon("""<rect x="4" y="4" width="16" height="16" rx="2"/><path d="m9 12 2.5 2.5L16 10"/>"""),
    "users" to icon("""<circle cx="9" cy="8" r="3.2"/><path d="M3.5 19c.6-3.2 2.8-5 5.5-5s4.9 1.8 5.5 5"/><circle cx="17" cy="9" r="2.6"/><path d="M15.5 14.4c2.3.2 4.2 1.7 4.9 4.6"/>"""),
    "message" to icon("""<path d="M4 5h16v10H9l-5 4z"/>"""),
    "star" to icon("""<path d="m12 4 2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4L3.2 9.7l5.4-.8z"/>"""),
    "user" to icon("""<circle cx="12" cy="8" r="3.4"/><path d="M5 19.5c.8-3.6 3.4-5.5 7-5.5s6.2 1.9 7 5.5"/>"""),
    "calendar" to icon("""<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M8 3v4M16 3v4M4 10h16"/><path d="m9.5 15 2 2 3.5-3.5"/>"""),
    "shield" to icon("""<path d="M12 3 5 6v6c0 4.4 3 7.6 7 9 4-1.4 7-4.6 7-9V6z"/>"""),
    "gear" to icon("""<circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v3M12 18.2v3M2.8 12h3M18.2 12h3M5.5 5.5l2.1 2.1M16.4 16.4l2.1 2.1M18.5 5.5l-2.1 2.1M7.6 16.4l-2.1 2.1"/>"""),
)

data class NavItem(val id: String, val path: String, val label: String, val icon: String, val count: Int?)

fun navItems(): List<NavItem> = listOf(
    NavItem("projects", "/app/projects", "Projects", ICONS["folder"]!!, DemoData.projects.size),
    NavItem("assigned-tasks", "/app/assigned-tasks", "Assigned tasks", ICONS["check"]!!, DemoData.assignedTasks.size),
    NavItem("team", "/app/team", "Team", ICONS["users"]!!, DemoData.team.size),
    NavItem("forecasts", "/app/forecasts", "Forecasts", ICONS["message"]!!, DemoData.forecasts.size),
    NavItem("lessons", "/app/lessons", "Lesson learnt", ICONS["star"]!!, DemoData.lessons.size),
    NavItem("other-tasks", "/app/other-tasks", "Other tasks", ICONS["user"]!!, DemoData.otherTasks.size),
    NavItem("todo", "/app/todo", "To-do", ICONS["calendar"]!!, DemoData.todos.size),
    NavItem("access-control", "/app/access-control", "Access control", ICONS["shield"]!!, null),
    NavItem("settings", "/app/settings", "Settings", ICONS["gear"]!!, null),
)

fun HTML.appPage(session: UserSession, section: String, title: String, block: DIV.(UserSession) -> Unit) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title("MURRAY PTS — $title")
        unsafe { +("<style>$PAGE_CSS$APP_CSS</style>") }
    }
    body("app-body") {
        div("app") {
            aside("sidebar") {
                img(src = "/static/mms-logo.png", alt = "MURRAY Mining Services Mongolia") {
                    attributes["class"] = "logo-img side-logo"
                }
                div("side-caption") {
                    +"PROJECT TRACKING SYSTEM "
                    span("accent") { +"(PTS)" }
                }
                nav {
                    navItems().forEach { item ->
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
                    val pinned = session.pinned.mapNotNull { pid -> DemoData.projects.firstOrNull { it.id == pid } }
                    if (pinned.isEmpty()) {
                        p("empty") {
                            +"No pinned projects yet. Pin a project from its board, or from the list on the Projects page, to see it here."
                        }
                    } else {
                        pinned.forEach { project ->
                            div("pinned-row") {
                                a(href = "/app/projects") { +project.name }
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
    }
}

private fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")

// App CSS is kept inline for the current Ktor HTML serving approach
// External version is available in src/main/resources/styles.css for future static file serving

const val APP_CSS = """
:root { --main-bg: #f4f6f8; --accent: #5a8a12; --accent-strong: #33500b; --accent-soft: #e9f4d8; }
html[data-theme="dark"] { --main-bg: #0e141c; --accent-soft: #24341a; }
html[data-accent="blue"] { --accent: #2f6fb2; --accent-strong: #1a4f8a; --accent-soft: #e3eefb; }
html[data-accent="blue"][data-theme="dark"] { --accent-soft: #16283c; }
html[data-accent="amber"] { --accent: #c07c10; --accent-strong: #8a5a06; --accent-soft: #fbeed3; }
html[data-accent="amber"][data-theme="dark"] { --accent-soft: #33260f; }
body.app-body { display: block; padding: 0; background: var(--main-bg); }
.app { display: flex; min-height: 100vh; }
.sidebar {
    width: 264px; flex: 0 0 264px;
    background: var(--card-bg); border-right: 1px solid var(--input-border);
    display: flex; flex-direction: column;
    padding: 20px 14px 16px;
    position: sticky; top: 0; height: 100vh; overflow-y: auto;
}
.sidebar .logo { width: 170px; margin: 0 auto 4px; }
.sidebar .logo-img.side-logo { width: 168px; max-width: 100%; height: auto; display: block; margin: 0 0 6px; }
.side-caption { text-align: center; font-size: 10.5px; font-weight: 600; letter-spacing: .14em; color: var(--muted); margin: 4px 0 18px; }
.side-caption .accent { color: var(--accent); font-weight: 800; }
.sidebar nav { display: flex; flex-direction: column; gap: 2px; }
.nav-item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 8px; color: var(--title); text-decoration: none; font-size: 14px; font-weight: 500; }
.nav-item svg { width: 18px; height: 18px; flex: 0 0 18px; color: var(--muted); }
.nav-item:hover { background: var(--input-bg); }
.nav-item.active { background: var(--accent-soft); color: var(--accent-strong); font-weight: 700; }
.nav-item.active svg { color: var(--accent-strong); }
.nav-item .label { flex: 1; }
.pill { background: var(--input-bg); border-radius: 10px; padding: 2px 8px; font-size: 11px; font-weight: 600; color: var(--muted); }
.nav-item.active .pill { background: var(--card-bg); color: var(--accent-strong); }
.pinned { margin: 18px 4px 0; }
.pinned h2 { font-size: 10.5px; font-weight: 700; letter-spacing: .1em; text-transform: uppercase; color: var(--muted); margin-bottom: 10px; }
.pinned .empty { font-size: 12px; line-height: 1.6; color: var(--note); }
.pinned-row { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.pinned-row a { flex: 1; font-size: 13px; color: var(--title); text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pinned-row a:hover { color: var(--accent-strong); text-decoration: underline; }
.side-foot { margin-top: auto; padding-top: 14px; border-top: 1px solid var(--input-border); }
.user-row { display: flex; align-items: center; gap: 10px; }
.avatar { width: 36px; height: 36px; flex: 0 0 36px; border-radius: 50%; background: var(--accent-strong); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700; }
.avatar.lg { width: 44px; height: 44px; flex-basis: 44px; font-size: 14px; margin: 0 auto 8px; }
.user-meta { flex: 1; min-width: 0; }
.user-name { font-size: 13px; font-weight: 600; color: var(--title); }
.user-role { font-size: 11px; color: var(--muted); }
.icon-btns { display: flex; gap: 6px; }
.icon-btn { width: 30px; height: 30px; border: none; border-radius: 8px; background: var(--input-bg); cursor: pointer; font-size: 14px; line-height: 1; }
a.logout { display: block; margin-top: 12px; padding: 9px; border-radius: 8px; background: var(--input-bg); color: var(--title); font-size: 13.5px; font-weight: 600; text-align: center; text-decoration: none; }
a.logout:hover { background: var(--input-border); }
.main { flex: 1; min-width: 0; padding: 28px 32px; }
.page-head { margin-bottom: 18px; }
.page-head h1 { font-size: 22px; font-weight: 800; color: var(--title); }
.card-panel { background: var(--card-bg); border: 1px solid var(--input-border); border-radius: 12px; padding: 18px 20px; margin-bottom: 16px; }
.panel-caption { font-size: 12.5px; color: var(--muted); margin-bottom: 12px; }
table.grid { width: 100%; border-collapse: collapse; font-size: 13.5px; }
table.grid th { text-align: left; font-size: 10.5px; font-weight: 700; letter-spacing: .07em; text-transform: uppercase; color: var(--muted); padding: 6px 10px 10px; border-bottom: 1px solid var(--input-border); }
table.grid td { padding: 10px; border-bottom: 1px solid var(--input-border); color: var(--title); vertical-align: middle; }
table.grid tr:last-child td { border-bottom: none; }
.code { font-family: Consolas, monospace; font-size: 12px; color: var(--muted); font-weight: 600; }
.pname { font-weight: 600; }
.chip { display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 11.5px; font-weight: 600; }
.chip.ontrack { background: #e6f4d7; color: #3f620e; }
.chip.atrisk { background: #fdecea; color: #b3261e; }
.chip.done { background: #e3f1fd; color: #1a5fb4; }
.chip.planning { background: #f3e8fd; color: #6f3fa0; }
.chip.onhold { background: #fbeed3; color: #8a5a06; }
.progress { display: inline-block; width: 110px; height: 8px; border-radius: 4px; background: var(--input-bg); overflow: hidden; vertical-align: middle; }
.progress .bar { height: 100%; background: var(--accent); }
.pct { margin-left: 8px; font-size: 12px; color: var(--muted); }
.pin-btn { border: 1px solid var(--input-border); background: var(--input-bg); color: var(--muted); border-radius: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer; white-space: nowrap; }
.pin-btn.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent-strong); font-weight: 600; }
.task-row { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--input-border); }
.task-row:last-child { border-bottom: none; }
.task-main { flex: 1; min-width: 0; }
.task-title { font-size: 14px; font-weight: 600; color: var(--title); }
.task-meta { font-size: 12px; color: var(--muted); margin-top: 2px; }
.team-name { display: flex; align-items: center; gap: 10px; }
.avatar.sm { width: 28px; height: 28px; flex-basis: 28px; font-size: 11px; }
.assignee { display: inline-flex; align-items: center; gap: 8px; margin-right: 12px; }
.assignee-meta { display: flex; flex-direction: column; line-height: 1.2; }
.assignee-name { font-size: 12.5px; font-weight: 600; color: var(--title); }
.assignee-id { font-size: 11px; color: var(--muted); }
.assignee.none { font-size: 12px; color: var(--muted); font-style: italic; margin-right: 12px; }
.status-form { display: inline-flex; margin-right: 10px; }
.status-select { border: 1px solid var(--input-border); background: var(--input-bg); color: var(--title); border-radius: 8px; padding: 4px 8px; font-size: 12px; font-weight: 600; cursor: pointer; }
form.cell-edit { margin: 0; }
form.cell-edit input[type="text"] { width: 100%; border: 1px solid var(--input-border); background: var(--input-bg); color: var(--title); border-radius: 6px; padding: 4px 8px; font-size: 12.5px; }
form.cell-edit select { border: 1px solid var(--input-border); background: var(--input-bg); color: var(--title); border-radius: 6px; padding: 4px 8px; font-size: 12px; font-weight: 600; cursor: pointer; }
.kanban { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; align-items: start; }
.kanban-other { grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); }
.kcol { background: rgba(0, 0, 0, .035); border-radius: 12px; padding: 12px; min-height: 190px; }
html[data-theme="dark"] .kcol { background: rgba(255, 255, 255, .05); }
.kcol.drag-over { outline: 2px dashed var(--accent); }
.kcol-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.kcol-title { font-size: 13px; font-weight: 700; color: var(--title); }
.kcol-count { margin-left: auto; font-size: 12px; color: var(--muted); font-weight: 600; }
.dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; flex: 0 0 9px; }
.dot.d-gray { background: #8a9199; }
.dot.d-amber { background: #e0a800; }
.dot.d-orange { background: #e07000; }
.dot.d-red { background: #d93025; }
.dot.d-green { background: #1e8e3e; }
.kcard { background: var(--card-bg); border-radius: 8px; border-left: 3px solid #8a9199; padding: 10px 12px; margin-top: 10px; box-shadow: 0 1px 2px rgba(0, 0, 0, .08); }
.kcard[draggable="true"] { cursor: grab; }
.kcard.s-notstarted { border-left-color: #8a9199; }
.kcard.s-inprogress { border-left-color: #e0a800; background: #fdf6dd; }
.kcard.s-onhold { border-left-color: #e07000; background: #fdeede; }
.kcard.s-blocked { border-left-color: #d93025; background: #fdecea; }
.kcard.s-completed { border-left-color: #1e8e3e; background: #e6f4ea; }
html[data-theme="dark"] .kcard.s-inprogress { background: #332b12; }
html[data-theme="dark"] .kcard.s-onhold { background: #33220f; }
html[data-theme="dark"] .kcard.s-blocked { background: #331512; }
html[data-theme="dark"] .kcard.s-completed { background: #12291a; }
.kcard.s-other { border-left-color: #5f6a76; }
.kmove { display: flex; gap: 6px; margin-top: 9px; }
.kmove-btn { flex: 1; cursor: pointer; font-size: 10.5px; font-weight: 600; padding: 4px 6px; border-radius: 6px; border: 1px solid var(--input-border); background: var(--card-bg); color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; transition: background .12s, color .12s, border-color .12s; }
.kmove-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); background: rgba(0, 0, 0, .04); }
html[data-theme="dark"] .kmove-btn:hover:not(:disabled) { background: rgba(255, 255, 255, .08); }
.kmove-btn.next { color: var(--accent); border-color: var(--accent); }
.kmove-btn:disabled { opacity: .5; cursor: default; }
.tcode { display: inline-block; background: #e6f4d7; color: #3f620e; border-radius: 6px; font-size: 10.5px; font-weight: 700; padding: 2px 7px; margin-bottom: 6px; }
html[data-theme="dark"] .tcode { background: #24341a; color: #b7e08a; }
.ktitle { font-size: 13px; font-weight: 600; color: var(--title); line-height: 1.35; }
.kmeta { display: flex; align-items: center; gap: 10px; margin-top: 7px; font-size: 11.5px; color: var(--muted); }
.kmeta.kfoot { justify-content: space-between; }
.prio { font-weight: 600; }
.prio::before { content: "● "; font-size: 9px; }
.prio.p-high { color: #d93025; }
.prio.p-med { color: #e07000; }
.prio.p-low { color: #8a9199; }
.ostatus { display: inline-flex; align-items: center; gap: 6px; color: var(--muted); font-weight: 600; }
.avatar.xs { width: 22px; height: 22px; flex-basis: 22px; font-size: 9px; margin-left: auto; }
.addtask input[type="text"], .addbucket input[type="text"] { width: 100%; border: none; background: transparent; color: var(--muted); font-size: 12.5px; padding: 8px 4px; }
.addtask input[type="text"]:focus, .addbucket input[type="text"]:focus { outline: none; color: var(--title); }
.addbucket { border: 1.5px dashed var(--input-border); background: transparent; }
.other-head { display: flex; align-items: baseline; gap: 8px; margin: 18px 0 10px; }
.other-title { font-size: 14px; font-weight: 700; color: var(--title); }
.other-sub { font-size: 12px; color: var(--muted); }
.team-table th.num, .team-table td.num { text-align: center; width: 1%; white-space: nowrap; }
.count { display: inline-block; min-width: 24px; padding: 2px 8px; border-radius: 10px; font-size: 12px; font-weight: 700; }
.count.zero { background: var(--input-bg); color: var(--muted); opacity: .55; font-weight: 600; }
.count.ontrack { background: #e6f4d7; color: #3f620e; }
.count.atrisk { background: #fdecea; color: #b3261e; }
.count.done { background: #e3f1fd; color: #1a5fb4; }
.count.planning { background: #f3e8fd; color: #6f3fa0; }
.count.onhold { background: #fbeed3; color: #8a5a06; }
.count-total { font-size: 13px; font-weight: 800; color: var(--title); }
.fc-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
.fc-card { margin-bottom: 0; }
.fc-period { font-size: 11px; font-weight: 700; letter-spacing: .08em; color: var(--accent-strong); text-transform: uppercase; }
.fc-card h3, .card-panel h3 { font-size: 15px; font-weight: 700; color: var(--title); margin: 4px 0 6px; }
.fc-hours { font-size: 22px; font-weight: 800; color: var(--title); margin-bottom: 6px; }
.lesson-text { font-size: 13.5px; color: var(--muted); line-height: 1.6; margin-top: 6px; }
.todo-row { display: flex; align-items: center; gap: 10px; padding: 9px 0; font-size: 14px; color: var(--title); border-bottom: 1px solid var(--input-border); }
.todo-row:last-child { border-bottom: none; }
.todo-box { width: 18px; height: 18px; flex: 0 0 18px; border: 1.5px solid var(--input-border); border-radius: 5px; display: flex; align-items: center; justify-content: center; font-size: 12px; color: #fff; }
.todo-box.done { background: var(--accent); border-color: var(--accent); }
.todo-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.todo-head h3 { margin: 0; }
.todo-count { font-size: 12px; color: var(--muted); font-weight: 600; }
.todo-del-form { margin: 0 0 0 auto; }
.todo-del { cursor: pointer; border: none; background: transparent; color: var(--muted); font-size: 16px; line-height: 1; padding: 2px 7px; border-radius: 6px; }
.todo-del:hover { color: #d93025; background: rgba(217, 48, 37, .12); }
.todo-toggle { margin: 0; }
.todo-toggle button { cursor: pointer; border: none; background: transparent; padding: 0; }
.todo-title { flex: 1; }
.todo-title.done { text-decoration: line-through; color: var(--muted); }
.todo-add { display: flex; gap: 8px; margin-top: 12px; }
.todo-add input[type="text"] { flex: 1; border: 1px solid var(--input-border); background: var(--input-bg); color: var(--title); border-radius: 8px; padding: 7px 10px; font-size: 13px; }
.set-row { display: flex; justify-content: space-between; padding: 9px 0; border-bottom: 1px solid var(--input-border); font-size: 13.5px; color: var(--title); }
.set-row:last-of-type { border-bottom: none; }
.set-key { color: var(--muted); }
select {
    width: 100%;
    padding: 11px 12px;
    font-size: 14px;
    font-family: inherit;
    color: var(--input-text);
    background: var(--input-bg);
    border: 1px solid var(--input-border);
    border-radius: 8px;
    outline: none;
}
.form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 12px; margin-bottom: 14px; }
.form-grid .field { margin-bottom: 0; }
.filter-tabs { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.ftab {
    display: inline-flex; align-items: center; gap: 6px;
    padding: 6px 12px; border-radius: 16px;
    background: var(--input-bg); color: var(--muted);
    font-size: 12.5px; font-weight: 600; text-decoration: none;
}
.ftab:hover { color: var(--title); }
.ftab.active { background: var(--accent-soft); color: var(--accent-strong); }
.ftab .pill { background: rgba(0, 0, 0, .06); }
html[data-theme="dark"] .ftab .pill { background: rgba(255, 255, 255, .08); }
.group-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.group-head .chip { font-size: 13px; font-weight: 700; padding: 4px 12px; }
.group-head .pill { background: rgba(0, 0, 0, .06); }
html[data-theme="dark"] .group-head .pill { background: rgba(255, 255, 255, .08); }
.chip.t-mfg { background: #e3f1fd; color: #1a5fb4; }
.chip.t-rms { background: #fbeed3; color: #8a5a06; }
.chip.t-sit { background: #e6f4d7; color: #3f620e; }
.chip.t-lab { background: #f3e8fd; color: #6f3fa0; }
.row-link { color: var(--title); text-decoration: none; }
.row-link:hover { color: var(--accent-strong); text-decoration: underline; }
.detail-head { margin-bottom: 12px; }
.back-link { color: var(--muted); text-decoration: none; font-size: 13px; font-weight: 600; }
.back-link:hover { color: var(--accent-strong); }
.detail-title { margin: 6px 0 14px; font-size: 22px; color: var(--title); }
.detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 14px; }
.detail-item { display: flex; flex-direction: column; gap: 5px; }
.detail-key { font-size: 11.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--muted); font-weight: 700; }
.detail-val { font-size: 14px; color: var(--title); font-weight: 600; }
.assignee.none { color: var(--muted); font-style: italic; }
.pw-edit { display: flex; gap: 6px; align-items: center; }
.pw-edit input[type="password"] { width: 120px; border: 1px solid var(--input-border); background: var(--input-bg); color: var(--title); border-radius: 6px; padding: 5px 8px; font-size: 12.5px; }
"""

// Theme JavaScript is kept inline for the current Ktor HTML serving approach
// External version is available in src/main/resources/theme.js for future static file serving

const val THEME_JS = """
(function () {
    var root = document.documentElement;
    var theme = localStorage.getItem('pts-theme');
    if (theme) root.setAttribute('data-theme', theme);
    var accent = localStorage.getItem('pts-accent');
    if (accent) root.setAttribute('data-accent', accent);
    var themeBtn = document.getElementById('themeToggle');
    function paintTheme() {
        if (themeBtn) themeBtn.textContent = root.getAttribute('data-theme') === 'dark' ? '☀️' : '🌙';
    }
    if (themeBtn) themeBtn.addEventListener('click', function () {
        var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
        root.setAttribute('data-theme', next);
        localStorage.setItem('pts-theme', next);
        paintTheme();
    });
    paintTheme();
    var accentBtn = document.getElementById('accentToggle');
    var order = ['green', 'blue', 'amber'];
    if (accentBtn) accentBtn.addEventListener('click', function () {
        var current = root.getAttribute('data-accent') || 'green';
        var next = order[(order.indexOf(current) + 1) % order.length];
        root.setAttribute('data-accent', next);
        localStorage.setItem('pts-accent', next);
    });
});
"""

// Logo SVG is kept inline for the current Ktor HTML serving approach
// External version is available in src/main/resources/logo.json for future static file serving

const val LOGO_SVG = """
<svg class="logo" viewBox="0 0 260 130" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Murray Mining Services Mongolia">
  <defs>
    <linearGradient id="murrayBanner" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#8dc63f"/>
      <stop offset="1" stop-color="#3e7c17"/>
    </linearGradient>
  </defs>
  <g stroke="#7ab648" stroke-width="1.4">
    <line x1="62" y1="65" x2="62" y2="8"/>
    <line x1="62" y1="65" x2="62" y2="122"/>
    <line x1="62" y1="65" x2="10" y2="65"/>
    <line x1="62" y1="65" x2="112" y2="65"/>
    <line x1="62" y1="65" x2="26" y2="29"/>
    <line x1="62" y1="65" x2="98" y2="101"/>
    <line x1="62" y1="65" x2="98" y2="29"/>
    <line x1="62" y1="65" x2="26" y2="101"/>
  </g>
  <path d="M84 10 A 62 62 0 0 0 84 120" fill="none" stroke="#2f6b12" stroke-width="3.5"/>
  <path d="M100 22 A 46 46 0 0 0 100 108" fill="none" stroke="#8dc63f" stroke-width="2"/>
  <g stroke="#1d4d0b" stroke-width="1.6" fill="none">
    <circle cx="62" cy="14" r="8"/>
    <line x1="52" y1="14" x2="72" y2="14"/>
    <line x1="62" y1="4" x2="62" y2="24"/>
    <circle cx="62" cy="116" r="8"/>
    <line x1="52" y1="116" x2="72" y2="116"/>
    <line x1="62" y1="106" x2="62" y2="126"/>
    <circle cx="100" cy="40" r="5"/>
    <circle cx="100" cy="90" r="5"/>
  </g>
  <circle cx="86" cy="26" r="6" fill="#1d4d0b"/>
  <circle cx="86" cy="104" r="6" fill="#1d4d0b"/>
  <rect x="88" y="50" width="150" height="28" fill="url(#murrayBanner)"/>
  <text x="163" y="70" text-anchor="middle" font-family="Arial, sans-serif" font-size="18" font-weight="bold" letter-spacing="7" fill="#ffffff">MURRAY</text>
  <text x="176" y="94" text-anchor="middle" font-family="Arial, sans-serif" font-size="12" font-weight="bold" letter-spacing="1" fill="#111111">MINING SERVICES</text>
  <text x="176" y="108" text-anchor="middle" font-family="Arial, sans-serif" font-size="11" letter-spacing="2" fill="#333333">MONGOLIA</text>
</svg>
"""
