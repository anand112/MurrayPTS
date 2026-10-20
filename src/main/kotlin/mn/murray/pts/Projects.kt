package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.SELECT
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.col
import kotlinx.html.colGroup
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import mn.murray.pts.service.ProjectDraft
import mn.murray.pts.service.ProjectField
import mn.murray.pts.service.ProjectService
import mn.murray.pts.service.TaskService

/**
 * Server-side state of the create-project modal. A non-null [form] on a freshly rendered
 * page means a POST was rejected: the modal opens with [values] still in the inputs and
 * each message in [errors] printed under its own field.
 */
data class ProjectForm(
    val values: ProjectDraft = ProjectDraft(),
    val errors: Map<ProjectField, String> = emptyMap(),
    val suggestions: Map<String, String> = emptyMap(),
) {
    val isOpen: Boolean = errors.isNotEmpty()
    fun error(field: ProjectField): String? = errors[field]
}

/**
 * One of the two directions a sort picker beside the search bar can be set to.
 *
 * `""` means the picker is not choosing a key at all — that is what lets both be blank and the board
 * fall back to the end-date order it has always used. Plain strings rather than an enum because the
 * value arrives in a query parameter and goes straight back out into `<option value>`s.
 */
object BoardSort {
    const val NONE = ""
    const val ASC = "asc"
    const val DESC = "desc"

    /** Anything that is not exactly [ASC] or [DESC] counts as "not chosen", so a hand-typed URL cannot invent a key. */
    fun of(raw: String?): String = if (raw == ASC || raw == DESC) raw else NONE
}

/**
 * What the board's search bar and its two sort pickers currently say.
 *
 * The search is scoped to the tab the reader is on — the category tab stays the scope and this narrows
 * inside it — and it matches the number, description, client, manager and category label, word by word:
 * every space-separated word has to appear somewhere in one of those, so "haul road" finds the project
 * but "haul missing" finds nothing. Sorting composes the two keys in the fixed order status then manager,
 * with the end date and then the project number as tie-breaks, so setting both reads as "group by status,
 * and inside each status by manager".
 */
data class ProjectBoardFilter(
    val text: String = "",
    val statusSort: String = BoardSort.NONE,
    val managerSort: String = BoardSort.NONE,
) {
    val isActive: Boolean get() = text.isNotBlank() || statusSort != BoardSort.NONE || managerSort != BoardSort.NONE

    /** A blank search matches everything, so an untouched bar is not a filter. */
    fun matches(project: Project): Boolean {
        if (text.isBlank()) return true
        val haystack = listOf(project.code, project.name, project.client, project.manager, project.type)
            .joinToString(" ").lowercase()
        return text.lowercase().split(' ', '\t').filter { it.isNotBlank() }.all { haystack.contains(it) }
    }

    fun sorted(projects: List<Project>): List<Project> {
        val byDue = Comparator<Project> { a, b -> a.due.compareTo(b.due) }
        val byCode = Comparator<Project> { a, b -> a.code.compareTo(b.code) }
        val chosen = listOfNotNull(
            if (statusSort == BoardSort.NONE) null
            else Comparator<Project> { a, b -> statusRank(a.status).compareTo(statusRank(b.status)) }.oriented(statusSort),
            if (managerSort == BoardSort.NONE) null else managerComparator(managerSort),
        )
        // Nothing chosen: the board is what it always was, soonest end date first.
        if (chosen.isEmpty()) return projects.sortedWith(byDue.then(byCode))
        return projects.sortedWith(chosen.reduce { acc, c -> acc.then(c) }.then(byDue).then(byCode))
    }

    /**
     * Manager names in the chosen direction, with an unassigned project filed last **whichever** way the
     * names run. That has to be decided before the direction is applied, not after: an empty string sorts
     * ahead of every name, so simply reversing the comparator would put "nobody has taken this yet" at the
     * top of the panel — and a tie-break added afterwards would never be reached, because two different
     * names already compare unequal.
     */
    private fun managerComparator(order: String): Comparator<Project> = Comparator<Project> { a, b ->
        val unassigned = when {
            a.manager.isBlank() && b.manager.isBlank() -> 0
            a.manager.isBlank() -> 1
            b.manager.isBlank() -> -1
            else -> null
        }
        unassigned
            ?: if (order == BoardSort.DESC) b.manager.compareTo(a.manager) else a.manager.compareTo(b.manager)
    }

    private fun Comparator<Project>.oriented(order: String): Comparator<Project> =
        if (order == BoardSort.DESC) reversed() else this

    /** `/app/projects?type=Site+projects&q=haul` — the tab this filter belongs to, plus every knob that is set. */
    fun hrefFor(type: String?): String {
        // `add`, not kotlinx's unary `+`: this list is a query string, not a tag.
        val params = buildList {
            if (type != null) add("type=" + urlEncode(type))
            if (text.isNotBlank()) add("q=" + urlEncode(text))
            if (statusSort != BoardSort.NONE) add("sort_status=$statusSort")
            if (managerSort != BoardSort.NONE) add("sort_manager=$managerSort")
        }
        return "/app/projects" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }
}

private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

/**
 * The `+ New project` opener. It lives in the page head (passed to appPage as its
 * `actions` slot) so it faces the "Projects" title from the other side of the row,
 * instead of the button bar it used to sit in above the board.
 *
 * Nothing in project-modal.js moves with it: the handler is delegated on `document`
 * and looks for [data-project-modal-open] wherever it appears.
 */
fun DIV.newProjectButton() {
    button(type = ButtonType.button, classes = "submit btn-sm new-project-btn") {
        attributes["data-project-modal-open"] = "true"
        +"+ New project"
    }
}

fun DIV.projectsContent(
    session: UserSession,
    typeFilter: String?,
    form: ProjectForm? = null,
    canManage: Boolean,
    filter: ProjectBoardFilter = ProjectBoardFilter(),
) {
    val all = DemoData.projects
    projectBoardToolbar(filter, typeFilter)
    // Everything under the toolbar is one **live region** (`app.js`): a refresh replaces this block in
    // place and rewrites the URL, while the toolbar — and the box the reader is typing in — is never
    // touched, so no keystroke can lose the focus, the caret or the scroll position to a page reload.
    // It has to be the *server* that renders it: the pills, the groups and the "Nothing here matches …"
    // message are the same code the whole page uses, which is why the render tests still describe the
    // board. Without JavaScript, or if a refresh ever fails, the same form does an ordinary GET load and
    // the reader lands on exactly this markup anyway.
    div("board-results") {
        id = "board-results"
        attributes["data-live-region"] = "true"
        // The pills count what the search would leave in each category, so a tab never advertises six
        // projects and then opens on two.
        div("filter-tabs") {
            a(classes = if (typeFilter == null) "ftab active" else "ftab") {
                href = filter.hrefFor(null)
                +"All "
                span("pill") { +"${all.count { filter.matches(it) }}" }
            }
            PROJECT_TYPES.forEach { type ->
                a(classes = if (typeFilter == type) "ftab active" else "ftab") {
                    href = filter.hrefFor(type)
                    +typeLabel(type)
                    +" "
                    span("pill") { +"${all.count { it.type == type && filter.matches(it) }}" }
                }
            }
        }
        val groups: List<Pair<String, List<Project>>> = if (typeFilter == null) {
            PROJECT_TYPES.map { type -> type to filter.sorted(all.filter { it.type == type && filter.matches(it) }) }
        } else {
            listOf(typeFilter to filter.sorted(all.filter { it.type == typeFilter && filter.matches(it) }))
        }
        val visible = groups.filter { it.second.isNotEmpty() }
        if (visible.isEmpty()) {
            div("card-panel") {
                if (filter.isActive) {
                    // The reader asked for something and the board has it not; say which of the two
                    // reasons this is, because "No projects of this type yet" would be a lie.
                    val wanted = filter.text.takeIf { it.isNotBlank() }?.let { " “$it”" }.orEmpty()
                    val where = typeFilter?.let { " in $it" }.orEmpty()
                    span("task-meta") { +"Nothing here matches$wanted$where." }
                    +" "
                    a(classes = "row-link", href = "/app/projects" + (typeFilter?.let { "?type=" + urlEncode(it) } ?: "")) {
                        span("pname") { +"Clear the search and the sort" }
                    }
                } else {
                    span("task-meta") { +"No projects of this type yet." }
                }
            }
        } else {
            // One read of every card, one map of every project's measured progress, shared by all the
            // panels below — the board is one page and its bars must agree with each other.
            val measured = TaskService.progressByProject()
            visible.forEach { (type, projects) ->
                div("card-panel") {
                    div("group-head") {
                        span("chip ${typeChipClass(type)}") { +type }
                        span("pill") { +"${projects.size}" }
                    }
                    projectTable(projects, session, measured)
                }
            }
        }
    }
    // Only management gets the popup at all; `POST /app/projects` re-checks the role, so a Viewer
    // who posts this form's action by hand is redirected rather than served a project.
    if (canManage) projectCreateModal(form ?: ProjectForm(suggestions = ProjectService.suggestedCodes()), filter)
}

/**
 * The search bar with the two sort pickers beside it, in one GET form onto the board's own URL.
 *
 * `type` rides along as a hidden field so sorting a panel cannot drop the reader out of the category they
 * were reading, and the pickers carry `data-autosubmit` (app.js) while the text box carries
 * `data-livefilter` — the same submit, debounced, so typing settles instead of firing per keystroke. That
 * submit is normally **not** a navigation: app.js intercepts it, fetches this URL and swaps
 * [projectsContent]'s live region, so the toolbar and the half-typed word stay exactly where they are. The
 * form is still a real GET form, which is what makes all of it safe to remove: with JavaScript off, with
 * the fetch failing, or with the session having lapsed, the browser performs the plain page load this
 * markup was written for. Every link on the board comes back through [ProjectBoardFilter.hrefFor], so the
 * knobs keep showing what is actually filtered.
 */
private fun DIV.projectBoardToolbar(filter: ProjectBoardFilter, typeFilter: String?) {
    form(action = "/app/projects", method = FormMethod.get, classes = "board-toolbar") {
        if (typeFilter != null) input(type = InputType.hidden, name = "type") { value = typeFilter }
        div("board-search") {
            input(type = InputType.text, name = "q") {
                id = "projectSearch"
                value = filter.text
                placeholder = "Search projects — number, description, client, manager"
                autoComplete = "off"
                attributes["data-livefilter"] = "true"
                // A live filter reloads the page, and a reloaded page has no caret; the box the reader
                // was typing in is the only thing worth taking focus back to.
                if (filter.text.isNotBlank()) autoFocus = true
            }
            // Inside the search wrapper rather than at the end of the toolbar, after the pickers: the
            // button belongs to the box it submits, and parked at the far right it read as a control for
            // whatever picker happened to be next to it.
            button(type = ButtonType.submit, classes = "submit btn-sm") { +"Search" }
        }
        label("board-key") {
            +"Status"
            select {
                name = "sort_status"
                attributes["data-autosubmit"] = "true"
                sortOption(BoardSort.NONE, "—", filter.statusSort)
                sortOption(BoardSort.ASC, "Not started first", filter.statusSort)
                sortOption(BoardSort.DESC, "Completed first", filter.statusSort)
            }
        }
        label("board-key") {
            +"Manager"
            select {
                name = "sort_manager"
                attributes["data-autosubmit"] = "true"
                sortOption(BoardSort.NONE, "—", filter.managerSort)
                sortOption(BoardSort.ASC, "A → Z", filter.managerSort)
                sortOption(BoardSort.DESC, "Z → A", filter.managerSort)
            }
        }
        if (filter.isActive) {
            a(classes = "board-reset", href = "/app/projects" + (typeFilter?.let { "?type=" + urlEncode(it) } ?: "")) {
                +"Clear"
            }
        }
    }
}

private fun SELECT.sortOption(value: String, label: String, chosen: String) {
    option {
        this.value = value
        selected = value == chosen
        +label
    }
}

/**
 * The create-project popup. It is a same-page modal rather than a separate route: the
 * board, the filter tabs and the sidebar all stay put, and a rejected submit re-renders
 * this page with the modal open instead of losing what was typed.
 *
 * Behaviour lives in /static/js/project-modal.js (CSP blocks inline script): the opener
 * is the [data-project-modal-open] button from [newProjectButton], which the projects
 * route renders in the page head, close markers are [data-modal-close],
 * and each category option carries `data-suggest` so switching category can offer the
 * next free number for it. `style-src-attr 'unsafe-inline'` is why the open state can be
 * a plain `style="display:block"`.
 */
private fun DIV.projectCreateModal(state: ProjectForm, filter: ProjectBoardFilter) {
    val v = state.values
    val selectedType = v.type.takeIf { it in PROJECT_TYPES } ?: PROJECT_TYPES.first()
    val suggested = state.suggestions[selectedType].orEmpty()
    val codeValue = v.code.ifBlank { suggested }
    div("modal") {
        id = "projectModal"
        if (state.isOpen) attributes["style"] = "display:block"
        div("modal-content modal-wide") {
            span("close") {
                attributes["data-modal-close"] = "true"
                +"×"
            }
            h3 { +"New project" }
            form(action = "/app/projects", method = FormMethod.post) {
                // A rejected create re-renders this page, and the board behind the popup should still be
                // the board the reader was looking at. These ride along so the route can rebuild the
                // filter; `board_` because the create form already owns `type`, `status` and `manager`.
                input(type = InputType.hidden, name = "board_q") { value = filter.text }
                input(type = InputType.hidden, name = "board_sort_status") { value = filter.statusSort }
                input(type = InputType.hidden, name = "board_sort_manager") { value = filter.managerSort }
                div("form-grid") {
                    div("field") {
                        label { +"Project type" }
                        select {
                            name = "type"
                            id = "projectType"
                            attributes["data-suggest-target"] = "projectCode"
                            PROJECT_TYPES.forEach { type ->
                                option {
                                    value = type
                                    selected = type == selectedType
                                    attributes["data-suggest"] = state.suggestions[type].orEmpty()
                                    attributes["data-letter"] = TYPE_PREFIX[type].orEmpty()
                                    +typeLabel(type)
                                }
                            }
                        }
                        state.error(ProjectField.TYPE)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Project number" }
                        input(type = InputType.text, name = "code") {
                            id = "projectCode"
                            value = codeValue
                            placeholder = "${TYPE_PREFIX[selectedType].orEmpty()}-01"
                            required = true
                            maxLength = "32"
                            if (v.code.isBlank() && suggested.isNotEmpty()) {
                                // Marks a value the script offered, so a category change may replace it.
                                attributes["data-autofilled"] = "true"
                            }
                        }
                        span("field-hint") {
                            id = "projectCodeHint"
                            +"${TYPE_PREFIX[selectedType].orEmpty()}-… — the number is yours to change."
                        }
                        state.error(ProjectField.CODE)?.let { p("field-err") { +it } }
                    }
                    div("field full") {
                        label { +"Description" }
                        input(type = InputType.text, name = "name") {
                            placeholder = "e.g. Dalanzadgad Depot construction"
                            required = true
                            value = v.name
                        }
                        state.error(ProjectField.NAME)?.let { p("field-err") { +it } }
                    }
                    div("field full") {
                        label { +"Client" }
                        input(type = InputType.text, name = "client") {
                            placeholder = "e.g. Oyu Tolgoi LLC"
                            value = v.client
                        }
                        state.error(ProjectField.CLIENT)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Start date" }
                        input(type = InputType.date, name = "start") { value = v.start }
                        state.error(ProjectField.START)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"End date" }
                        input(type = InputType.date, name = "end") { value = v.end }
                        span("field-hint") { +"Due-date alerts and the Calendar watch this date." }
                        state.error(ProjectField.END)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Status" }
                        select {
                            name = "status"
                            PROJECT_STATUSES.forEach { status ->
                                option {
                                    value = status
                                    selected = status == (v.status.ifBlank { ProjectService.DEFAULT_STATUS })
                                    +status
                                }
                            }
                        }
                        state.error(ProjectField.STATUS)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Revenue" }
                        input(type = InputType.number, name = "revenue") {
                            min = "0"
                            step = "1"
                            placeholder = "0"
                            value = v.revenue
                        }
                        state.error(ProjectField.REVENUE)?.let { p("field-err") { +it } }
                    }
                    div("field full") {
                        label { +"Manager" }
                        select {
                            name = "manager"
                            option {
                                value = ""
                                selected = v.managerName.isEmpty()
                                +"— No manager —"
                            }
                            optGroupPerMember(v.managerName, valueOf = { it.name })
                        }
                        state.error(ProjectField.MANAGER)?.let { p("field-err") { +it } }
                    }
                }
                p("task-meta") {
                    +"Assignees and progress are managed on the project page after it is created."
                }
                div("modal-actions") {
                    button(type = ButtonType.submit, classes = "submit") { +"Create project" }
                    button(type = ButtonType.button, classes = "ghost-btn") {
                        attributes["data-modal-close"] = "true"
                        +"Cancel"
                    }
                }
            }
        }
    }
    // Modal open/close + the category-driven number suggestion. External because CSP
    // allows only 'self' scripts.
    script(src = "/static/js/project-modal.js") {}
}

/**
 * One row per project, on the board's **shared ten-column grid**.
 *
 * The board renders a separate `<table>` per category panel, and the browser's auto layout sizes every
 * one of those tables from its own contents — so "Project" started at one x in the Maintenance group
 * and at another in the Site group, and the panels did not line up from one to the next. The `c-*`
 * classes plus `table-layout: fixed` (widths in app.css) are what make all of them agree, and they are
 * percentages summing to 100 so a narrow screen wraps instead of overflowing.
 *
 * [measured] is the page's one read of what every project's checklists add up to
 * ([mn.murray.pts.service.TaskService.progressByProject]); a project with nothing measurable on it keeps
 * the number its own row carries, which is the figure this column showed before checklists measured
 * anything. See [mn.murray.pts.service.TaskService.displayedProgress].
 */
fun DIV.projectTable(shown: List<Project>, session: UserSession, measured: Map<String, Int>) {
    table("grid projects-table") {
        // The widths live here rather than on the cells, because `table-layout: fixed` reads the first
        // row it finds and one colgroup per table is the only way to give every category panel the
        // same grid. Order matters: these are the ten columns the header prints below. (`colGroup` is
        // the kotlinx.html name; the element it emits is `<colgroup>`.)
        colGroup {
            col(classes = "c-code")
            col(classes = "c-name")
            col(classes = "c-client")
            col(classes = "c-status")
            col(classes = "c-progress")
            col(classes = "c-manager")
            col(classes = "c-start")
            col(classes = "c-due")
            col(classes = "c-rev")
            col(classes = "c-pin")
        }
        thead {
            tr {
                th(classes = "c-code") { +"ID" }
                th(classes = "c-name") { +"Project" }
                th(classes = "c-client") { +"Client" }
                th(classes = "c-status") { +"Status" }
                th(classes = "c-progress") { +"Progress" }
                th(classes = "c-manager") { +"Manager" }
                th(classes = "c-start") { +"Start date" }
                th(classes = "c-due") { +"End date" }
                th(classes = "c-rev") { +"Revenue" }
                th(classes = "c-pin") { +"" }
            }
        }
        tbody {
            shown.forEach { project ->
                val pct = TaskService.displayedProgress(project, measured)
                tr {
                    td("c-code") { a(href = "/app/projects/${project.id}", classes = "row-link") { span("code") { +project.code } } }
                    td("c-name") { a(href = "/app/projects/${project.id}", classes = "row-link") { span("pname") { +project.name } } }
                    td("c-client") { +project.client.ifEmpty { "—" } }
                    td("c-status") { span("chip ${chipClass(project.status)}") { +project.status } }
                    td("c-progress") {
                        div("progress") {
                            div("bar") { attributes["style"] = "width:$pct%" }
                        }
                        span("pct") { +"$pct%" }
                    }
                    td("c-manager") { +project.manager }
                    td("c-start") { +project.start.ifEmpty { "—" } }
                    td("c-due") { +project.due.ifEmpty { "—" } }
                    td("c-rev") { span("money") { +formatRevenue(project.revenue) } }
                    td("c-pin") {
                        form(action = "/app/projects/${project.id}/pin", method = FormMethod.post) {
                            val pinned = pinnedIds(session.employeeNumber).contains(project.id)
                            button(type = ButtonType.submit, classes = if (pinned) "pin-btn on" else "pin-btn") {
                                +(if (pinned) "★ Pinned" else "☆ Pin")
                            }
                        }
                    }
                }
            }
        }
    }
}

fun DIV.projectDetailContent(
    session: UserSession,
    project: Project,
    canManage: Boolean,
    state: TaskForm? = null,
    edit: ProjectForm? = null,
) {
    // **Stage 60 — the head, and who edits from it.** Management lands on a project page to run it, so the top
    // of the page is now the project's own settings: a breadcrumb that says which project this is, and to its
    // right the two whole-project actions — *Pin for me* and *Delete project*. Beneath that the project's fields
    // are editable in place (see the form below). A Viewer sees none of that and gets the page's old head back:
    // a plain "← Back to projects" and a read-only card, because reading a project is not the same as being able
    // to change it, and hiding the form here is presentation — `/app/projects/{id}` (POST) and `/delete` refuse a
    // non-manager again, so a hand-built request cannot reach the writes this markup only offers to management.
    if (canManage) {
        div("detail-head crumb-row") {
            div("crumb") {
                a(href = "/app/projects", classes = "crumb-link") { +"Projects" }
                span("crumb-sep") { +"/" }
                span("crumb-code") { +project.code }
            }
            div("head-actions") {
                form(action = "/app/projects/${project.id}/pin", method = FormMethod.post, classes = "opener-form") {
                    val pinned = pinnedIds(session.employeeNumber).contains(project.id)
                    button(type = ButtonType.submit, classes = if (pinned) "pin-btn on" else "pin-btn") {
                        +(if (pinned) "★ Pinned" else "☆ Pin for me")
                    }
                }
                // The small icon button the screenshot puts between the pin and the delete. It is a copy-link
                // action, not a form: it has no write of its own, so it is a plain `button` (never a submit) that
                // app.js answers through a delegated `[data-copy-link]` click handler — the CSP forbids an inline
                // onclick, and there is no server route to post to. It copies this page's own URL to the clipboard
                // so a manager can hand a colleague a link to the project without leaving the page.
                button(type = ButtonType.button, classes = "icon-btn head-copy") {
                    attributes["data-copy-link"] = ""
                    attributes["aria-label"] = "Copy link to this project"
                    attributes["title"] = "Copy link to this project"
                    +"📋"
                }
                form(action = "/app/projects/${project.id}/delete", method = FormMethod.post, classes = "opener-form") {
                    button(type = ButtonType.submit, classes = "ghost-btn btn-sm danger") {
                        attributes["data-confirm"] =
                            "Delete ${project.code}? Every task filed under this project goes with it. This cannot be undone."
                        +"Delete project"
                    }
                }
            }
        }
    } else {
        div("detail-head") {
            a(href = "/app/projects", classes = "back-link") { +"← Back to projects" }
        }
    }
    // This project's own board. It is cut by **status** — the same five columns, Not started → Completed, that
    // the general Assigned tasks page prints — because standing on one project the useful question is how far
    // the work has got, not which of the project's six sub-categories a card sits in. So the word "In progress"
    // is back on this page as a column head. A card still carries its sub-category — the project's six, the
    // popup's picker and the service's compulsory filing are all unchanged from Stage 48 — it is simply no
    // longer what the columns are made of, and no control here adds a lane or takes one off (Stage 35 stands).
    // The status board always draws all five of its columns, empty ones included, so a project with no work
    // reads as a plan rather than as a blank page.
    //
    // **Stage 56:** this board shows the **whole** project to **every** reader. A project is a shared thing, and
    // the question standing on one page is "what tasks does this project have, and who is doing each" — which is
    // not answerable from one person's slice of it. So the list is no longer narrowed to a non-manager's own
    // cards the way the general Assigned tasks page is (Stage 53): every card filed under this project is drawn,
    // and each card already carries its holder's avatar, so "who is doing" reads straight off the board.
    //
    // This is **the LIST, not the RIGHTS**. What a reader may *do* is unchanged and still comes from the flags
    // handed down, not from this list: [mayAdd] is the only write right here (management anywhere, this project's
    // own manager on its page), and a card is openable / draggable only for `canManage || its assignee` (see
    // [taskCard]). A plain Viewer who opens a project therefore **reads** the whole board — every colleague's
    // card and holder — but cannot edit, move or add a card that is not their own; their own cards here still
    // open and walk for them. The per-role read the routes enforce again on every write; markup is not a
    // permission. (The personal scoping of Stage 53/61 is deliberately kept everywhere else — the general
    // Assigned tasks page, the Calendar and the Team sheet's OPEN TASKS drill-down still show a non-manager only
    // their own cards — this page being the one shared, project-wide board.)
    val mayAdd = TaskService.mayAddTasks(project.id, session.employeeNumber, canManage)
    val tasks = TaskService.assigned().filter { it.project == project.name }
    // The bar in the detail card measures the *project*, so it counts every card on it, not the slice one
    // reader owns: the same figure the Projects board prints for this row, from the one rule in
    // [mn.murray.pts.service.TaskService.displayedProgress]. Since **Stage 56** the board under it is drawn from
    // [tasks] — which is now the whole project for every reader — so bar and board agree on that same set here
    // for the first time. There are no other roll-up numbers on
    // this page on purpose: the board already prints each column's count in its own head, so a strip above
    // it could only repeat them or disagree with them.
    val progress = TaskService.displayedProgress(project, TaskService.progressByProject())
    if (canManage) {
        // The header strip is a form onto `/app/projects/{id}`, and every field on it is editable — the same
        // inputs the create modal asks for, laid out in the single row the screenshot shows rather than a modal
        // nobody opens twice. A refused save comes back here with [edit] carrying what was typed and each message
        // under its own input; a good save redirects, so this is what a fresh page reads. Two things are NOT on
        // the form: the **project number** (fixed, shown in the breadcrumb above — changing the category does not
        // re-code a live project) and **progress** (not typed any more; it is what the cards' checklists add up
        // to, so it prints as read-only here and the bar under it is the figure the board produces).
        val v = edit?.values ?: ProjectDraft(
            type = project.type,
            name = project.name,
            client = project.client,
            start = project.start,
            end = project.due,
            status = project.status,
            managerName = project.manager,
            revenue = money(project.revenue),
            invoiced = money(project.invoiced),
        )
        div("card-panel edit-panel") {
            form(action = "/app/projects/${project.id}", method = FormMethod.post) {
                div("edit-strip") {
                    editField("Description") {
                        input(type = InputType.text, name = "name") { value = v.name; required = true; maxLength = "255" }
                        edit?.error(ProjectField.NAME)?.let { p("field-err") { +it } }
                    }
                    editField("Client") {
                        input(type = InputType.text, name = "client") { value = v.client; maxLength = "255" }
                        edit?.error(ProjectField.CLIENT)?.let { p("field-err") { +it } }
                    }
                    editField("Type", "wide-ef") {
                        select {
                            name = "type"
                            PROJECT_TYPES.forEach { t ->
                                option { value = t; selected = t == v.type; +typeLabel(t) }
                            }
                        }
                        edit?.error(ProjectField.TYPE)?.let { p("field-err") { +it } }
                    }
                    editField("Start") {
                        input(type = InputType.date, name = "start") { value = v.start }
                        edit?.error(ProjectField.START)?.let { p("field-err") { +it } }
                    }
                    editField("Due") {
                        input(type = InputType.date, name = "end") { value = v.end }
                        edit?.error(ProjectField.END)?.let { p("field-err") { +it } }
                    }
                    editField("Status") {
                        select {
                            name = "status"
                            PROJECT_STATUSES.forEach { s ->
                                option { value = s; selected = s == v.status; +s }
                            }
                        }
                        edit?.error(ProjectField.STATUS)?.let { p("field-err") { +it } }
                    }
                    editField("Project manager", "wide-ef") {
                        select {
                            name = "manager"
                            option { value = ""; selected = v.managerName.isEmpty(); +"— No manager —" }
                            optGroupPerMember(v.managerName, valueOf = { it.name })
                        }
                        edit?.error(ProjectField.MANAGER)?.let { p("field-err") { +it } }
                    }
                    editField("Revenue", "money-ef") {
                        // A text field, not `number`: the screenshot shows the amount comma-grouped ("5,000,000"),
                        // and a numeric input refuses to display a grouped value. The save is safe because
                        // [mn.murray.pts.service.ProjectService]'s revenue parsing strips spaces and commas before
                        // it reads the number, so the grouped text round-trips.
                        input(type = InputType.text, name = "revenue") { value = v.revenue; maxLength = "32" }
                        edit?.error(ProjectField.REVENUE)?.let { p("field-err") { +it } }
                    }
                    editField("Invoiced", "money-ef") {
                        input(type = InputType.text, name = "invoiced") { value = v.invoiced; maxLength = "32" }
                        edit?.error(ProjectField.INVOICED)?.let { p("field-err") { +it } }
                    }
                    // Progress is a read-only cell, not an input: it is derived from the board below and there is
                    // no box for a person to type it into. The screenshot shows it as a bare percentage, so there
                    // is no bar here — just the number, in the same flat style as the other values. The same
                    // `project-progress` id the live-swap contract uses still names this cell, so ticking a step
                    // swaps the figure in place, and the `.pct` span the swap tests read is kept.
                    div("ef progress-ef") {
                        id = "project-progress"
                        span("ef-label") { +"Progress" }
                        span("pct") {
                            attributes["title"] = "The average of what each card on this project has reached"
                            +"$progress%"
                        }
                    }
                }
                div("edit-actions") {
                    button(type = ButtonType.submit, classes = "submit") { +"Save changes" }
                }
            }
        }
    } else {
        div("card-panel") {
            div("group-head") {
                span("chip ${typeChipClass(project.type)}") { +project.type }
                span("code") { +project.code }
                span("chip ${chipClass(project.status)}") { +project.status }
                form(action = "/app/projects/${project.id}/pin", method = FormMethod.post) {
                    val pinned = pinnedIds(session.employeeNumber).contains(project.id)
                    button(type = ButtonType.submit, classes = if (pinned) "pin-btn on" else "pin-btn") {
                        +(if (pinned) "★ Pinned" else "☆ Pin")
                    }
                }
            }
            h3("detail-title") { +project.name }
            div("detail-grid") {
                detailItem("Project number", project.code)
                detailItem("Category", typeLabel(project.type))
                detailItem("Client", project.client.ifEmpty { "—" })
                detailItem("Manager", project.manager.ifEmpty { "—" })
                detailItem("Start date", project.start.ifEmpty { "—" })
                detailItem("End date", project.due.ifEmpty { "—" })
                detailItem("Status", project.status)
                // Revenue and Progress travel as one unit through the run of fields. They are ordinary
                // caption-and-value cells like the seven above — same line, same shape — but wrapped together so a
                // card with room for the amount and not for the bar never leaves the bar stranded on a line by
                // itself. This is NOT the full-width row an earlier attempt gave them; it is a single flex item in
                // the same wrapping row the other fields flow through.
                div("detail-pair") {
                    div("detail-item") {
                        span("detail-key") { +"Revenue" }
                        span("detail-val") { +formatRevenue(project.revenue) }
                    }
                    div("detail-item") {
                        // The id is what a step form's `data-live-extra` names in app.js: ticking a box on a card
                        // below swaps this cell from the same response, so the bar cannot sit on last week's
                        // number while the checkbox that moved it is already ticked.
                        id = "project-progress"
                        span("detail-key") { +"Progress" }
                        div("progress") {
                            div("bar") { attributes["style"] = "width:$progress%" }
                        }
                        span("pct") {
                            // The bar weighs cards and the cards underneath count their own ticked steps, so the two
                            // figures legitimately differ; saying which one is which is cheaper here than in a manual.
                            attributes["title"] = "The average of what each card on this project has reached"
                            +"$progress%"
                        }
                    }
                }
            }
        }
    }
    div("other-head") {
        span("other-title") { +"Tasks on this project" }
        val sub = when {
            tasks.isNotEmpty() -> "— ${tasks.size} assigned"
            mayAdd -> "— nothing assigned yet, create the first one"
            else -> "— nothing assigned to this project yet"
        }
        span("other-sub") { +sub }
    }
    // The board's columns, and who may write a card into them. Passing no `lanes` is what makes this a
    // **status** board — [taskKanban] falls to its five status columns when there are no lanes to cut by, the
    // same shape the general Assigned tasks page already draws. `kanban.js` treats a `.kcol[data-status]`
    // column as a status write to `/app/tasks/status`, and the page head's `+ New task` is the opener, so
    // nothing here drags a card into a lane: on this board a card's owner walks it along their own work exactly
    // as on the general board. [mayAdd] is the right to write a card here — management anywhere, this project's
    // manager on its page — and it is what the empty board's invitation hangs on. `lanes` still fills the
    // popup's Sub-category picker below, so a new card is still filed in a sub-category the board no longer
    // shows (Stage 48's compulsory filing is untouched).
    val lanes = TaskService.lanesOf(project.id)
    taskKanban(
        tasks, session, canManage, BACK_PROJECT, project.id,
        canAdd = mayAdd,
        fixedProject = project,
    )
    // There is no "Project manager" panel on this page any more, and no picker to set one: the manager is
    // chosen when the project is created and printed in the detail card above, and a second way to write the
    // same field from the bottom of the page was a thing to disagree with it. `/app/projects/{id}/manager`
    // still exists for that field's own sake; nothing on this page posts to it.
    //
    // The same popup as the general board, and for the same readers who may act: management open any card here,
    // an assignee their own. Since Stage 56 the **board** shows every card, but a card only opens for somebody who
    // may edit it, so the popup hangs on who can *write*, not on the now-wider list: it is drawn for management, for
    // a project's own manager (who may write the first card here even on an empty board), for a page arriving with
    // the popup already up (`state`), and for a reader who owns a card on it — the exact condition that draws the
    // opener inside [taskKanban]. A Viewer who can only read other people's cards therefore gets neither opener nor
    // popup, not a dead form. `project` is what pre-selects it in a new task, and what an edit posts back
    // as `projectId` so the page it returns to is this one. It carries this project's lanes so the placeholder is the
    // same form the URL version comes back as.
    if (canManage || mayAdd || state != null || tasks.any { it.assignee == session.employeeNumber }) {
        taskFormModal(
            state ?: TaskForm(canEditAll = canManage, canCreate = mayAdd, lanes = lanes),
            BACK_PROJECT,
            project,
        )
    }
    // Dragging a card between these columns changes its status, and the owner's arrows do the same; both are a
    // status write to `/app/tasks/status`, in [kanban.js]. There is nothing to drag into a lane here — the
    // sub-category a card belongs to is set in the popup, not by the column it sits in.
    script(src = "/static/js/kanban.js") {}
}

// This page prints no roll-up of its own board, on purpose. What a project amounts to is readable from the
// counts in the lane heads, from the progress bar in the detail card, and from the holder named on
// each card — a strip repeating those numbers was a third place to keep in step and the first place to
// disagree. (Stage 28 removed the tiles: Cards, Open, Done, Overdue, Steps and End date.)

private fun DIV.detailItem(key: String, value: String) {
    div("detail-item") {
        span("detail-key") { +key }
        span("detail-val") { +value }
    }
}

/**
 * One labelled cell of the management header strip (Stage 60): an uppercase caption over the control the caller
 * puts in [block], and — when a save was refused — the message for this field printed right under it. The caption
 * is upper-cased by CSS (`text-transform`), so the source stays readable and the screenshot's all-caps labels
 * are a styling choice, not a second string.
 */
private fun DIV.editField(caption: String, extra: String = "", block: kotlinx.html.FlowContent.() -> Unit) {
    div(("ef" + if (extra.isEmpty()) "" else " $extra").trim()) {
        span("ef-label") { +caption }
        block()
    }
}

/** Revenue has no currency column by design; the board prints the bare grouped amount. */
private fun formatRevenue(value: Double): String =
    if (value <= 0.0) "—" else java.text.DecimalFormat("#,##0").format(value)

/**
 * A stored amount rendered for the header strip's text `<input>`: the whole value comma-grouped exactly as the
 * screenshot shows it ("5,000,000"), and a plain `0` for zero rather than a blank box. The inverse of
 * [mn.murray.pts.service.ProjectService]'s revenue parsing, which strips the commas back off on the way in.
 */
private fun money(value: Double): String = java.text.DecimalFormat("#,##0").format(if (value <= 0.0) 0.0 else value)

private fun chipClass(status: String): String = when (status) {
    "Ongoing" -> "ontrack"
    "Blocked" -> "atrisk"
    "Completed" -> "done"
    "On hold" -> "onhold"
    else -> "planning"
}
