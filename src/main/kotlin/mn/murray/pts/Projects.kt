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
import mn.murray.pts.service.BucketDefaultsService
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
 *
 * Since **Stage 104** the popup asks one bucket question — *what else should this board have* — through
 * `#projectBucketPresets` (the category's own columns, read-only, one row per category with `.on` showing the
 * chosen one), `#newProjectBucketList` (the chips, each a hidden `new_bucket`) and `#newProjectBucketName` with
 * its `[data-bucket-add]` button. The script keeps the preset rows in step with the category picker and turns a
 * typed name into a chip; the form itself needs none of it to post what is in the box.
 */
private fun DIV.projectCreateModal(state: ProjectForm, filter: ProjectBoardFilter) {
    val v = state.values
    val selectedType = v.type.takeIf { it in PROJECT_TYPES } ?: PROJECT_TYPES.first()
    val suggested = state.suggestions[selectedType].orEmpty()
    val letter = TYPE_PREFIX[selectedType].orEmpty()
    // Read once for the whole popup: every category's columns, shown one category at a time under the Buckets
    // label so the reader knows what the board starts with before they add to it (Stage 104).
    val catalogue = BucketDefaultsService.catalogueByType()
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
                        // Stage 92: the box opens **with the category's letter and dash already in it**, so the
                        // reader types only the number. The script keeps that lead in step with the category
                        // picker (S-07 -> G-07), and a rejected submit brings the whole code back as typed.
                        input(type = InputType.text, name = "code") {
                            id = "projectCode"
                            value = v.code.ifBlank { if (letter.isEmpty()) "" else "$letter-" }
                            placeholder = suggested.ifEmpty { "$letter-01" }
                            attributes["data-prefix"] = "$letter-"
                            required = true
                            maxLength = "32"
                        }
                        span("field-hint") {
                            id = "projectCodeHint"
                            +"$letter-… — type the number after the dash; the letter follows the category."
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
                    // Stage 92 took the bucket picker out of this popup, Stage 100 made the category's Settings
                    // list the whole board, and **Stage 104 puts an Add bucket line back — as an addition, never
                    // as a choice.** Nothing here selects from the rows below or unticks them: the board opens
                    // with everything the category lists, and what management types here is appended after it.
                    // There is deliberately no `also_type` tick on this form — Stage 77's box asks whether one
                    // column named on a live board should reshape the category, and asking it mid-create, before
                    // the project exists to belong to, would be a box whose "on" means "and every future project
                    // of this kind". Settings stays the only place the catalogue grows.
                    div("field full bucket-pick") {
                        label { +"Buckets" }
                        // One row per category, only the chosen one shown. The list is server-drawn for the
                        // category the popup opens on, so a reader with no scripts sees the true answer to "what
                        // does this board start with" for the category on their screen; `[data-preset-for]` is
                        // what [project-modal.js] switches between when they change it. A category that lists
                        // nothing says so here, which is the same fact the create now refuses on — and the one
                        // this line lets them fix without leaving the popup.
                        div("bucket-preset-rows") {
                            attributes["id"] = "projectBucketPresets"
                            PROJECT_TYPES.forEach { option ->
                                val rows = catalogue[option].orEmpty()
                                div(if (option == selectedType) "bucket-preset-row on" else "bucket-preset-row") {
                                    attributes["data-preset-for"] = option
                                    if (rows.isEmpty()) {
                                        span("bucket-preset none") { +"— none listed in Settings yet —" }
                                    } else {
                                        rows.forEach { row -> span("bucket-preset") { +row.name } }
                                    }
                                }
                            }
                        }
                        // What the reader has added, worn as chips. Each one carries its own hidden `new_bucket`
                        // because a chip is a *value*, not a control: the box below is cleared when it becomes
                        // one, so nothing else would post it — and a disabled box posts nothing at all, which is
                        // the Stage 73 lesson this form follows to the letter.
                        div("bucket-pending") {
                            attributes["id"] = "newProjectBucketList"
                            state.values.newBuckets.forEach { name -> newBucketChip(name) }
                        }
                        div("bucket-add-line") {
                            input(type = InputType.text, name = "new_bucket") {
                                id = "newProjectBucketName"
                                placeholder = "e.g. QAQC"
                                attributes["maxlength"] = "$PROJECT_BUCKET_MAX"
                                attributes["autocomplete"] = "off"
                            }
                            // The same words the board's own box and its popup button print, so the ask is
                            // recognisable on the one page that has no board yet to add to.
                            button(type = ButtonType.button, classes = "ghost-btn btn-sm") {
                                attributes["data-bucket-add"] = "true"
                                +"+ Add bucket"
                            }
                        }
                        // What the box says when a name does not become a chip — a name the category already
                        // offers, or one already on this board twice. It is NOT a `.field-err` node even though
                        // it wears the same red: an always-printed error line would be the first one a refused
                        // create tries to focus, and this one is empty until the script has a reason to fill it.
                        p("bucket-add-said") {
                            id = "newProjectBucketSaid"
                        }
                        p("field-hint") {
                            +(
                                "The board opens with the buckets above, then any you add here. One added here is " +
                                    "this project's own column and changes nothing in Settings."
                                )
                        }
                        state.error(ProjectField.BUCKETS)?.let { p("field-err") { +it } }
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
 * One bucket the reader added to the create form, worn as a chip that carries its own hidden `new_bucket`
 * (Stage 104).
 *
 * The hidden field is the chip, not a decoration beside it: [project-modal.js] clears the text box when a name
 * becomes a chip, so the value would otherwise exist only as ink on the page — and a control that is drawn but
 * disabled posts nothing at all, which is the Stage 73 rule this popup's category picker already follows with its
 * hidden `bucket` twins. Every one of these fields shares the name `new_bucket` with the still-open text box, so
 * the route reads the posted list whole and the order the chips stand in is the order the board gets.
 *
 * The × is a button the script answers, never a form: this popup is one `<form>` already, and a nested one is
 * what made Stage 72's two-alike-buttons problem. A reader without scripts can still create with the name in the
 * box — the chips they cannot remove are ones they cannot add either.
 */
private fun DIV.newBucketChip(name: String) {
    span("bucket-pending-chip") {
        attributes["data-bucket-pending"] = name
        input(type = InputType.hidden, name = "new_bucket") { value = name }
        +name
        button(type = ButtonType.button, classes = "bucket-pending-drop") {
            attributes["data-bucket-drop"] = "true"
            attributes["aria-label"] = "Remove $name from this board"
            +"×"
        }
    }
}

/**
 * The project page's bucket popup (Stage 74) — the one form behind a column's `⋯` and behind the dashed
 * `+ Add bucket` box that trails the run of columns.
 *
 * [typed] is the word the reader had in the field, kept for the copy that comes back after a refusal, and
 * [nameError] is that refusal's sentence, printed under the field it belongs to. [open] is the box's opener and
 * [laneId] a column's, and [isOpen] is the page's test for drawing the popup visibly — the same arrangement the
 * backlog's [mn.murray.pts.BucketForm] keeps, for the same reason: whether this form is up is decided by the page
 * the server rendered, so a reader with no JavaScript gets the identical popup from a plain click, and a refusal
 * is simply the same page again with its reason in it.
 */
data class LaneForm(
    val typed: String = "",
    val nameError: String? = null,
    val open: Boolean = false,
    val laneId: Int = 0,
    /** Stage 77: how the reader left the *also offer it to new projects of this category* tick. */
    val forType: Boolean = false,
    /**
     * Stage 80: the colour swatch the reader had chosen on a column's `⋯`, kept for the copy that comes back
     * after a refusal. Since **Stage 83** only the manage half draws a swatch row — creating a bucket asks for
     * a name alone — so this is null on the add half. Null is *no answer yet* — the popup then shows the shade
     * the column wears now.
     */
    val colourSlot: Int? = null,
) {
    /** Whether this popup is about a column already on the board rather than one the reader is naming. */
    val isManage: Boolean get() = laneId != 0

    /** Up because the reader asked for it, or up because a write from inside it was refused. */
    val isOpen: Boolean get() = open || laneId != 0 || nameError != null
}

/**
 * The popup behind a project board's column `⋯` and its `+ Add bucket` box (Stage 74).
 *
 * One node for both openers, exactly as the backlog's `#bucketModal` is one node for its head button and its
 * column menus: the page can only swap a block that has one id, and the two asks are the two halves of the same
 * question — what the board's columns are called, and how many of them there are. Since **Stage 79** it is also
 * the *only* place on a project page where its buckets are written at all: the tick-list panel that used to sit
 * under the header is gone, and a column is added, retitled and removed from the column itself.
 *
 * `#projectLaneModal` is the block the openers swap in, and `#projectBoard` is the block the popup's own forms
 * swap, with the popup named in their `data-live-extra` alongside the card popup's Sub-category picker. That
 * pairing is what makes the exchange need no script of its own to open or close it:
 * the response the server renders decides whether the form is up. A write that landed comes back as the board with
 * its columns retitled or one longer, the popup closed, and its sentence on the board; a write that was refused
 * comes back with the board untouched, this popup open, and the reason under the field it came from.
 *
 * [mayWriteLanes] is the same right the `⋯` and the dashed box hang on
 * ([mn.murray.pts.service.TaskService.mayEditLanes]) — management, or the account this project names as its
 * manager — so a Viewer who bookmarks `?laneedit=7` is handed the page rather than a form whose every button the
 * route refuses.
 * The script tag is inside this block so a popup swapped in by an opener still gets its close hook.
 */
fun DIV.projectLaneModal(project: Project, state: LaneForm, mayWriteLanes: Boolean, canManage: Boolean) {
    // The board's lanes read once, in board order, because a colour needs the order to be understood (Stage 80):
    // a column with no hue of its own wears the shade of its place in the run, so the swatch row a popup opens
    // with has to answer from the same position the column head painted — [TaskService.lanesOf] is the one list
    // both of them read, and the index is looked up here rather than re-derived inside each half.
    val boardLanes = if (mayWriteLanes) TaskService.lanesOf(project.id) else emptyList()
    val laneIndex = boardLanes.indexOfFirst { it.id == state.laneId }
    val lane = if (laneIndex >= 0) boardLanes[laneIndex] else null
    div("modal") {
        id = "projectLaneModal"
        if (mayWriteLanes && state.isOpen) attributes["style"] = "display:block"
        div("modal-content") {
            span("close") {
                attributes["data-lane-modal-close"] = "true"
                +"×"
            }
            val current = lane
            if (current != null) {
                laneManageBody(
                    project, current, state, TaskService.cardCounts(project.id)[current.id] ?: 0, laneIndex,
                )
            } else laneAddBody(project, state, canManage)
        }
    }
    script(src = "/static/js/lane-modal.js") {}
}

/**
 * The create half: one name, and the board's other lanes carried along with it. The colour is not asked for
 * since **Stage 83** — a new column wears the shade of the position it takes at the end of the run, and a
 * manager who wants a different one opens the column's `⋯` afterwards, where the same six swatches are drawn
 * beside the column they change.
 *
 * The form posts to the page's one bucket-save route with **every lane the board holds** as hidden `bucket`
 * fields plus the new name in `new_bucket`, which is what [mn.murray.pts.service.TaskService.saveLanes] reads as
 * the whole set. Those hidden fields are not decoration: the save's contract is *these are the buckets this
 * board is to have*, so an add that posted only its own name would have read as a request to drop every other
 * lane on the board — and since Stage 75 that would include the three the project opened with, because a lane
 * the form fails to state is now genuinely a lane being unticked rather than a lane the service protects. In
 * particular an add whose hidden set skipped **Engineering** would remove Engineering by accident. Inheriting
 * that route is also what gives the box the create popup's rules for free — the name limits, the duplicate check, and
 * since Stage 77 the same reach: the new column is this project's, and only management is asked whether the
 * category should adopt it.
 */
private fun DIV.laneAddBody(project: Project, state: LaneForm, canManage: Boolean) {
    h3 { +"Add a bucket" }
    p("modal-scope") {
        +(
            "A bucket is a column of this project's board — a phase of its work a card can be filed in. " +
                "It belongs to this project; ${project.type} is only told about it if management tick the box below."
            )
    }
    form(action = "/app/projects/${project.id}/buckets/save", method = FormMethod.post) {
        attributes["data-live-target"] = "#projectBoard"
        attributes["data-live-extra"] = "#taskLane,#projectLaneModal"
        TaskService.lanesOf(project.id).forEach { lane ->
            input(type = InputType.hidden, name = "bucket") { value = lane.name }
        }
        div("field") {
            label { +"Bucket" }
            input(type = InputType.text, name = "new_bucket") {
                id = "laneName"
                placeholder = "e.g. QAQC"
                required = true
                attributes["maxlength"] = "$PROJECT_BUCKET_MAX"
                value = state.typed
            }
            state.nameError?.let { p("field-err") { +it } }
        }
        // Stage 77: the column is this project's by default, and sharing it with the category is a separate,
        // deliberate act — management's alone, which is why a project's own manager sees no box here at all
        // rather than one that would be refused. The tick carries its own state back on a refusal, because a
        // reader who asked for the wider write and was stopped by the name rules should not have to ask twice.
        if (canManage) {
            label("check-row bucket-widen") {
                input(type = InputType.checkBox, name = "also_type") {
                    checked = state.forType
                }
                +"Also make it a default bucket for ${project.type}"
            }
            // The same caption the create popup writes, and the same class: `.modal-scope` pulls itself 10px up
            // to sit under a popup's heading, which on this line lands it on top of the bold tick label it
            // belongs below. `bucket-widen-why` is the one that knows it follows a row of its own.
            p("bucket-widen-why") {
                +(
                    "Off, this column is the project's own. On, it joins the defaults for ${project.type}, so " +
                        "the next project of that category is offered it too."
                    )
            }
        }
        // The save's answer is a sentence, and since Stage 73 it arrives in the URL the redirect is followed to.
        // This form is on the board, so it says so and the route puts the same word back into the redirect: it is
        // what tells [mn.murray.pts.Application]'s project route to print the sentence over the columns the reader
        // just changed rather than swallow it. Since Stage 79 every bucket write on this page comes from here or
        // from a column's `⋯`, so every one of them carries this mark.
        input(type = InputType.hidden, name = "src") { value = "board" }
        div("modal-actions") {
            button(type = ButtonType.submit, classes = "submit") { +"Add bucket" }
            button(type = ButtonType.button, classes = "ghost-btn") {
                attributes["data-lane-modal-close"] = "true"
                +"Cancel"
            }
        }
    }
}

/**
 * The manage half: this column's name, its colour, and the button that takes it off the board.
 *
 * Renaming is the safe half — a card belongs to its bucket by id, so a new heading is a new heading over the same
 * work, and nothing else moves. Since **Stage 80** it travels with a colour for the same reason and with the same
 * lack of consequence: the hue is painted on the column head and on nothing else, so recolouring a lane cannot
 * orphan a card, misfile a step, or change what any other board shows. It is offered on **every** column since Stage 75, the three a project opened with
 * included: their names used to be what the general board's popup filed a card by, which made retitling one a way
 * to orphan its cards, and that popup files a card by *this project's* lanes now, so a heading is this board's own
 * business. The category's catalogue keeps its name either way — a rename touches one row of one board.
 *
 * Removing is offered as well, and **since Stage 81 the count changes only the words, never the button.** A column
 * that holds work comes off with its work in it, and the work goes to this project's Archive — the panel the
 * header's 🗄 opens, where each card can be restored or deleted for good. The older promise was the other way
 * round: Stage 73 refused to untick an occupied bucket and Stage 74 carried that refusal onto the column, so the
 * button sat disabled over the line *"Move them to another bucket first"*. The prototype this page now follows
 * never asks the reader to move anything; it tells them what will happen — *"3 task(s) will be moved to
 * Archive"* — and lets the button press. [held] is therefore a statement about the future rather than a gate,
 * which is also what makes [mn.murray.pts.service.TaskService.removeLane]'s own count trustworthy: the route
 * re-reads it at the moment it deletes, so a page left open while somebody filed a card into this column still
 * removes the column, and the card it now holds goes to Archive with it rather than being refused a move it
 * cannot see the reason for.
 *
 * The one rule still worth a dead button is the board's shape: a column that is a board's last stays standing
 * (Stage 75's [mn.murray.pts.service.TaskService.LaneError.LAST_LANE]), because a project page without columns
 * is not a project page. That is the only case `disabled` covers now.
 */
private fun DIV.laneManageBody(
    project: Project,
    lane: ProjectBucket,
    state: LaneForm,
    held: Int,
    boardIndex: Int,
) {
    h3 { +"Bucket" }
    p("modal-scope") {
        +(
            "Rename this column, or take it off the board. A card belongs to its bucket by identity, so a new " +
                "name is only a new heading — and the ${project.type} catalogue keeps the name this one was " +
                "offered under."
            )
    }
    form(action = "/app/projects/${project.id}/buckets/rename", method = FormMethod.post) {
        attributes["data-live-target"] = "#projectBoard"
        attributes["data-live-extra"] = "#taskLane,#projectLaneModal"
        input(type = InputType.hidden, name = "lane") { value = "${lane.id}" }
        // The same mark the add box carries. Both of this popup's routes are the page's bucket writes now
        // (Stage 79 took the tick-list that used to share them off the page), and it is this field that tells them
        // the reader is standing on the columns, so the sentence about their write prints over the column they
        // changed rather than being left for a form nobody opened.
        input(type = InputType.hidden, name = "src") { value = "board" }
        div("field") {
            label { +"Name" }
            input(type = InputType.text, name = "name") {
                id = "laneName"
                required = true
                attributes["maxlength"] = "$PROJECT_BUCKET_MAX"
                // The typed word after a refusal, the stored name before one: a reader whose name was cut short
                // or doubled should see what they posted, not what the database still holds.
                value = if (state.nameError != null) state.typed else lane.name
            }
            state.nameError?.let { p("field-err") { +it } }
        }
        // Stage 80: the same six swatches the create half offers, opened on the shade this column is wearing —
        // its own when it has one, the one its place in the run gives it when it does not
        // ([mn.murray.pts.domain.effectiveColourSlot]), which is what the head's dot is painted with. A post that
        // changes only this field is a legitimate one: [mn.murray.pts.service.TaskService.renameLane] writes the
        // colour on its own, so a manager who recolours a column and leaves its name alone is not told nothing
        // changed.
        bucketColourPicker(state.colourSlot ?: effectiveColourSlot(boardIndex, lane.colourSlot))
        div("modal-actions") {
            button(type = ButtonType.submit, classes = "submit") { +"Rename bucket" }
            button(type = ButtonType.button, classes = "ghost-btn") {
                attributes["data-lane-modal-close"] = "true"
                +"Cancel"
            }
        }
    }
    // The one rule that still stops a removal: a board keeps a column. [held] no longer belongs to that question
    // (Stage 81) — it decides only what the button's confirm says, and the service archives the cards it names.
    // [mn.murray.pts.service.TaskService.removeLane] re-reads both when it writes, so a page drawn against an
    // empty column still removes it and archives whatever arrived since.
    val lastLane = TaskService.lanesOf(project.id).size < 2
    p("modal-scope") {
        +(
            when {
                // The refusal first, because it is the only one that is not going to happen: a last column with
                // cards in it would otherwise be told where its work is going by a button that cannot be pressed.
                lastLane -> "A board keeps at least one bucket, so this one cannot go. Add another column " +
                    "before removing this one."
                held > 0 -> "Removing this column takes it off the board, and its $held " +
                    "${if (held == 1) "card goes" else "cards go"} to Archive — restore " +
                    "${if (held == 1) "it" else "them"} from the 🗄 panel in the header."
                lastLane -> "A board keeps at least one bucket, so this one cannot go. Add another column " +
                    "before removing this one."
                else -> "Removing this column takes it off the board. Nothing is filed in it, so no card moves."
            }
            )
    }
    form(action = "/app/projects/${project.id}/buckets/delete", method = FormMethod.post) {
        attributes["data-live-target"] = "#projectBoard"
        attributes["data-live-extra"] = "#taskLane,#projectLaneModal"
        input(type = InputType.hidden, name = "bucket") { value = "${lane.id}" }
        input(type = InputType.hidden, name = "src") { value = "board" }
        div("modal-actions") {
            button(type = ButtonType.submit, classes = "submit danger-btn") {
                disabled = lastLane
                // The prototype's confirm, in the same words: the count is the consequence, and saying it before
                // the press is what makes an archive that swallows a week of work readable as what was asked for.
                if (held > 0) {
                    attributes["data-confirm"] =
                        "Remove \"${lane.name}\"? Its $held ${if (held == 1) "card" else "cards"} " +
                            "will be moved to Archive."
                }
                +"Remove bucket"
            }
            button(type = ButtonType.button, classes = "ghost-btn") {
                attributes["data-lane-modal-close"] = "true"
                +"Cancel"
            }
        }
    }
}

/**
 * The header's 🗄 — the opener for this project's Archive panel (Stage 81).
 *
 * A GET form onto this page carrying `?archive=1`, the same shape the column `⋯` uses for its popup and the
 * `▸ Completed N` line uses for its fold: whether the panel is up is a fact about the URL, so `app.js` fetches
 * the page and swaps `#projectArchive` alone, a bookmark opens the panel on its own, and pressing the button
 * while it is already up posts the key off and shuts it. [open] is that key as the page read it, which is why
 * this form's only field is conditional.
 *
 * It is drawn on [mayWriteLanes] — management or this project's own manager, the one right every archive write
 * checks — and in **both** of this page's headers, because the two of them are the same reader seen from two
 * roles: management edits the strip above, a project's manager who is not management reads it. A plain Viewer
 * gets no button, and a Viewer who hand-types `?archive=1` gets a page with the panel drawn closed, which is the
 * Stage 74 rule that a control nobody may use is not drawn at all.
 */
private fun DIV.archiveOpener(projectId: Int, open: Boolean, mayWriteLanes: Boolean) {
    if (!mayWriteLanes) return
    form(action = "/app/projects/$projectId", method = FormMethod.get, classes = "opener-form") {
        attributes["data-live-target"] = "#projectArchive"
        if (!open) input(type = InputType.hidden, name = "archive") { value = "1" }
        button(type = ButtonType.submit, classes = if (open) "icon-btn archive-btn on" else "icon-btn archive-btn") {
            attributes["aria-label"] = if (open) "Hide the archived tasks" else "Show the archived tasks"
            attributes["title"] = if (open) "Hide archived tasks" else "Archived tasks"
            +"🗄"
        }
    }
}

/**
 * The project's Archive: every card its board put away, each one restorable (V18, Stage 81).
 *
 * Drawn beside the board **always closed, and open only when the page's URL says so** — [mn.murray.pts.projectLaneModal]'s
 * arrangement for the same three reasons: the openers swap one block by one id, the reader with no JavaScript gets
 * the identical panel from a plain click, and a write from inside it answers with the panel still up because the
 * redirect that comes back carries `?archive=1` again. That last part is what makes the two buttons below feel
 * like a panel rather than a form that throws you out of the page every press.
 *
 * **A card in here is not on any board**, and that is the whole of the design: the row was never copied or
 * deleted, so its plan, its log and its lesson learnt are exactly what they were the moment it left, and
 * [mn.murray.pts.service.TaskService.restoreCard] needs only to clear the flag and name a lane to put it back.
 * The lane it came from is remembered as text and as an id — the text always reads, the id resolves only while
 * that column is still on this project, which is the interesting case a reader meets after removing a bucket with
 * cards in it, and where it no longer does the card lands in the board's first column rather than in Unsorted.
 *
 * The panel is where an **archived** card is destroyed for good. The live card's own popup carries management's
 * **Delete task** again since **Stage 84** (Stage 81 had put an **Archive task** button there), so this is one of two
 * permanent deletes rather than the only one: a card can be destroyed from its popup while it is on the board, or
 * from here after its bucket took it off. Archiving itself is now reached by removing the bucket, not by a button on
 * the card.
 */
fun DIV.projectArchivePanel(project: Project, open: Boolean) {
    val cards = TaskService.archived(project.id)
    div("modal") {
        id = "projectArchive"
        if (open) attributes["style"] = "display:block"
        div("modal-content archive-panel") {
            div("archive-head") {
                h3("archive-title") { +"Archived tasks (${cards.size})" }
                // The close is a plain GET form onto the page with no key on it, not a script's hide: the panel's
                // state is the URL's, so this works with JavaScript off and with it on it is one more swap of the
                // same block.
                form(action = "/app/projects/${project.id}", method = FormMethod.get, classes = "opener-form") {
                    attributes["data-live-target"] = "#projectArchive"
                    button(type = ButtonType.submit, classes = "icon-btn") {
                        attributes["aria-label"] = "Close archived tasks"
                        +"✕"
                    }
                }
            }
            if (cards.isEmpty()) {
                p("archive-empty") { +"No archived tasks." }
            }
            cards.forEach { card ->
                div("archive-item") {
                    div("at-title") { +card.title }
                    div("at-meta") {
                        // The prototype's line: which project this was, and which column it left. The day it left is
                        // stored (`archived_on`, V18) and the rows are printed newest-first, so the order carries it
                        // without every row needing to spell it out.
                        +(project.code + " · was in \"" + card.archivedLane.ifEmpty { "no bucket" } + "\"")
                    }
                    div("at-actions") {
                        form(action = "/app/projects/${project.id}/archive/restore", method = FormMethod.post) {
                            attributes["data-live-target"] = "#projectArchive"
                            attributes["data-live-extra"] = "#projectBoard"
                            input(type = InputType.hidden, name = "taskId") { value = "${card.id}" }
                            button(type = ButtonType.submit, classes = "submit btn-sm") { +"Restore" }
                        }
                        form(action = "/app/projects/${project.id}/archive/delete", method = FormMethod.post) {
                            attributes["data-live-target"] = "#projectArchive"
                            attributes["data-live-extra"] = "#projectBoard"
                            input(type = InputType.hidden, name = "taskId") { value = "${card.id}" }
                            button(type = ButtonType.submit, classes = "ghost-btn btn-sm danger") {
                                attributes["data-confirm"] =
                                    "Permanently delete ${card.title}? Its steps and its notes go with it. " +
                                        "This cannot be undone."
                                +"Delete permanently"
                            }
                        }
                    }
                }
            }
        }
    }
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
    bucketNote: String? = null,
    bucketNoteIsError: Boolean = false,
    noteOnBoard: Boolean = false,
    lane: LaneForm = LaneForm(),
    /**
     * Stage 80: which of this board's columns are showing their completed cards, read off the URL's repeated
     * `?showdone=<lane id>` keys. Everything else on the page folds that work away. A bucket write answers
     * without the key — the redirect the save builds names the columns, not the reader's open groups — so the
     * board comes back folded after a lane is added, renamed or removed, which is the state a re-shaped board
     * should be read in anyway.
     */
    doneOpen: Set<Int> = emptySet(),
    /**
     * Stage 81: whether this page arrives with its **Archived tasks** panel up, off `?archive=1`. The route reads
     * the key only for a reader who may write the board's lanes, and the panel is drawn closed beside the board
     * for everybody else, exactly as the bucket popup has been since Stage 74 — the page the server rendered is
     * what says whether it is open, so a plain click and a bookmarked link give the same panel.
     */
    archiveOpen: Boolean = false,
) {
    // **Stage 81: the board's own right, read once, at the top.** What it gates is now three things rather than
    // two — the columns' `⋯` and the dashed `+ Add bucket` box, and the header's 🗄 that opens the Archive — and
    // the header is drawn before anything else on this page, so the flag cannot wait for the board's block below.
    // [mn.murray.pts.service.TaskService.mayEditLanes] is management or the account this project names as its
    // manager, which is the one line every destructive write here agrees on: reshaping the columns, putting a
    // card away, bringing it back, and agreeing it is gone.
    val mayWriteLanes = TaskService.mayEditLanes(project.id, session.employeeNumber, canManage)
    // **Stage 60 — the head, and who edits from it.** Management lands on a project page to run it, so the top
    // of the page is now the project's own settings: a breadcrumb that says which project this is, and to its
    // right the whole-project actions — *Pin for me*, the 🗄 that opens this board's Archive (Stage 81), and
    // *Delete project*. Beneath that the project's fields
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
                archiveOpener(project.id, archiveOpen, mayWriteLanes)
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
    // This project's own board. It is cut by **buckets** — one column for each of this project's sub-categories,
    // the three it opened with included — because the user's word for this page was *the bucket structure is
    // shown all in kanban* (Stage 75). A card still carries its status: it prints on the card, its Done button
    // and popup write it, and the five status columns are what the reader's own Assigned tasks page cuts the same
    // cards by. The stage a card has reached is a fact about that card; the column it sits in here is a fact
    // about the project, and this page is where the project's shape is edited — from its columns' `⋯`, the dashed
    // box at the end of the run, and the picker above the board (Stage 73, Stage 74).
    // The bucket board prints a column for every lane even when it holds nothing, so a project with no work still
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
    // own manager on its page), and a card is draggable only for `canManage || its assignee` on a status board or
    // the board's own mover on a lane one (see [taskCard]). A plain Viewer who opens a project therefore **reads**
    // the whole board, and since **Stage 86** can open any card on it to read the plan and the log — the popup
    // they get is read-only ([TaskForm.viewOnly]) — while their own cards here still open as an editor and walk
    // for them. The per-role read the routes enforce again on every write; markup is not a permission. (The
    // personal scoping of Stage 53/61 is deliberately kept everywhere else — the general Assigned tasks page, the
    // Calendar and the Team sheet's OPEN TASKS drill-down still show a non-manager only their own cards — this
    // page being the one shared, project-wide board.)
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
                // The same Archive door the management strip prints. A project's own manager who is not
                // management reads this header rather than editing it, and they are still the account that owns
                // this board's columns — so they get the panel, and a plain Viewer gets nothing, because
                // [archiveOpener] checks [mayWriteLanes] and not [canManage].
                archiveOpener(project.id, archiveOpen, mayWriteLanes)
            }
            h3("detail-title") { +project.name }
            // **Stage 71: one line, for every reader.** This run used to print `CAPTION value` pairs side by
            // side, which asks about 1,600px of a panel that measures about 1,100 in a normal window, so Revenue
            // and Progress fell onto a line of their own in any ordinary browser, and the `detail-pair` wrapper
            // that tried to keep them company only made the second line look designed. Putting a caption *over* its value halves
            // the width each field needs, which is exactly how the management header above fits its ten cells in
            // one row, so this is now that same object read-only: the `.edit-strip` run and the `.ef` /
            // `.ef-label` cells, with a plain value where management has an input. Nothing was dropped,
            // shortened or reordered to get there — the same nine fields, in the same order, from the same
            // columns, and the bar is still the app's one `.progress`.
            div("edit-strip detail-strip") {
                detailCell("Project number", project.code, "num-ef")
                detailCell("Category", typeLabel(project.type), "cat-ef")
                detailCell("Client", project.client.ifEmpty { "—" }, "text-ef")
                detailCell("Manager", project.manager.ifEmpty { "—" }, "wide-ef")
                detailCell("Start date", project.start.ifEmpty { "—" }, "date-ef")
                detailCell("End date", project.due.ifEmpty { "—" }, "date-ef")
                detailCell("Status", project.status, "text-ef")
                detailCell("Revenue", formatRevenue(project.revenue), "money-ef")
                // The id is what a step form's `data-live-extra` names in app.js: ticking a box on a card below
                // swaps this cell from the same response, so the bar cannot sit on last week's number while the
                // checkbox that moved it is already ticked.
                div("ef progress-ef") {
                    id = "project-progress"
                    span("ef-label") { +"Progress" }
                    div("detail-line") {
                        div("progress") {
                            div("bar") { attributes["style"] = "width:$progress%" }
                        }
                        span("pct") {
                            // The bar weighs cards and the cards underneath count their own ticked steps, so the
                            // two figures legitimately differ; saying which one is which is cheaper here than in
                            // a manual.
                            attributes["title"] = "The average of what each card on this project has reached"
                            +"$progress%"
                        }
                    }
                }
            }
        }
    }
    // This page's buckets are written on the board itself: the dashed `+ Add bucket` box at the end of the run and
    // the `⋯` on every column. Stage 73 put a tick-list of the same buckets under the header as well, and
    // **Stage 79 took that panel off** — a reader standing in front of the columns has no use for a second list of
    // the names they can already see, and a column that holds work says so where it holds it, in its own popup.
    //
    // The one right covering all of this page's bucket writes — a column's `⋯`, the dashed box, and since
    // **Stage 81** the Archive behind the header's 🗄 — is [mn.murray.pts.service.TaskService.mayEditLanes], read
    // at the top of this function as [mayWriteLanes] because the header that opens the Archive is drawn first.
    // The project's buckets, read once and used twice below: as this board's columns, and as the card popup's
    // Sub-category picker. They are also half of the gate the bucket popup hangs on, [laneModalDrawn].
    val lanes = TaskService.lanesOf(project.id)
    // **The board is its buckets, always** (Stage 75), so the popup that reshapes them is drawn beside it always
    // — not only while the project has no cards, which is what Stage 74's gate said. The one condition that
    // matters is whether this reader is offered the openers at all: [mayWriteLanes], plus a non-empty column set,
    // because a board with nothing on it has no column to point a rename at.
    val laneModalDrawn = mayWriteLanes && lanes.isNotEmpty()
    div("other-head") {
        span("other-title") { +"Tasks on this project" }
        val sub = when {
            tasks.isNotEmpty() -> "— ${tasks.size} assigned"
            mayAdd -> "— nothing assigned yet, create the first one"
            else -> "— nothing assigned to this project yet"
        }
        span("other-sub") { +sub }
    }
    // The board's columns, and who may write a card into them. This page has one board shape (Stage 75): **the
    // project's buckets, cut by [laneKanban] whether or not it holds cards**. [taskKanban]'s five status columns
    // are not a fallback here any more — a card's stage is printed on the card and moved by its own Done button
    // and popup, and the columns underneath the header are where the work is *filed*, which is the question a
    // project page asks and the Assigned tasks page does not. [mayAdd] is the right to write a card here —
    // management anywhere, this project's manager on its page — and it is what every column's `+ Add task` line
    // hangs on. [lanes] is also what fills the card popup's Sub-category picker below, so the board and the
    // picker are always the same set of names (Stage 48's compulsory filing is untouched).
    // `#projectBoard` is the block the bucket popup's forms swap (Stage 74), which is why the sentence a save
    // answers with prints inside it: the columns and the words about them have to arrive in one repaint, or a board
    // that grew a column while the note still said *two buckets* is the half-updated page this mechanism exists to
    // avoid. Since **Stage 79** this is where every bucket write's answer prints — the popup's own name field still
    // carries a refused name, and nothing else on the page is a bucket form.
    div("project-board") {
        id = "projectBoard"
        if (noteOnBoard) {
            bucketNote?.let { p("bucket-said " + if (bucketNoteIsError) "err" else "ok") { +it } }
        }
        // **One board, read as buckets** (Stage 75). What a project amounts to is the shape of its work, not the
        // stage each card has reached, and the user asked for the bucket structure on the kanban rather than
        // beside it: one column per bucket, each with its own `+ Add task` line, and the columns a reader renames
        // or adds are the columns the next card is filed in. `status` and `project_bucket_id` remain separate
        // columns of the same row, so nothing moved here — the same list is simply read one way instead of two,
        // and the five status columns are exactly what the reader's own Assigned tasks page still cuts them by.
        // [taskKanban] never reaches its `board-empty` invitation on this page: handed a column set it draws the
        // columns, and the columns with their `+ Add task` lines *are* the invitation.
        taskKanban(
            tasks, session, canManage, BACK_PROJECT, project.id,
            canAdd = mayAdd,
            fixedProject = project,
            lanes = lanes,
            // Stage 49's mover, unchanged: management or this project's own manager, which is the same reader the
            // `⋯` is drawn for and the same rule [TaskService.moveToLane] checks server-side. A drag between
            // these columns files the card in the bucket it lands on, so the right to drag is the right to shape
            // this board's contents, and nobody else's cards or nobody else's projects come within it.
            canMoveCards = mayWriteLanes,
            canManageLanes = mayWriteLanes,
            // Stage 80: which columns are showing their finished cards, off the URL's repeated `showdone` keys.
            // A bucket write answers without the key, so a column the reader had opened comes back folded — the
            // board they get after a rename is the board as it is now, not as it was mid-read.
            doneOpen = doneOpen,
        )
    }
    // There is no "Project manager" panel on this page any more, and no picker to set one: the manager is
    // chosen when the project is created and printed in the detail card above, and a second way to write the
    // same field from the bottom of the page was a thing to disagree with it. `/app/projects/{id}/manager`
    // still exists for that field's own sake; nothing on this page posts to it.
    //
    // The same popup as the general board. Until **Stage 86** it hung on who could *write* a card — management,
    // this project's manager, a page arriving with the popup already up, and a reader who owned one of the cards
    // — because opening a card was editing it. Since Stage 86 every card on the board is a click target for
    // every reader, so the popup is drawn whenever the board holds a card: a colleague opening somebody else's
    // card gets the same form with every field greyed and no save (see [TaskForm.viewOnly]), while management
    // and the card's assignee
    // get the editor they always had. `project` is what pre-selects it in a new task, and what an edit posts back
    // as `projectId` so the page it returns to is this one. It carries this project's lanes so the placeholder is the
    // same form the URL version comes back as.
    if (canManage || mayAdd || state != null || tasks.isNotEmpty()) {
        taskFormModal(
            state ?: TaskForm(canEditAll = canManage, canCreate = mayAdd, lanes = lanes),
            BACK_PROJECT,
            project,
        )
    }
    // The bucket popup the board's `⋯` and its dashed `+ Add bucket` box open (Stage 74). The same arrangement the
    // backlog's `#bucketModal` keeps, for the same reason: **whether this form is up is decided by the page the
    // server rendered**, not by a click — an opener is a GET form carrying `?laneedit=<id>` or `?laneadd=1`, and a
    // write that lands comes back with it shut.
    //
    // It is drawn on one condition — [laneModalDrawn], computed above where the board's rights were read — and
    // that condition is Stage 51's: **only beside a board that prints
    // the openers**. Since Stage 75 that board is this page's board always, cards or no cards, so the openers the
    // form answers are the `⋯` on every column and the dashed box at the end of the run. A page cannot carry a
    // popup nobody can open, still less one whose fields a hand-built POST would be the only way to reach, and a
    // Viewer gets neither the buttons nor the form: both hang on [mayWriteLanes], management or this project's own
    // manager. Since **Stage 79** it is also the page's only bucket control — the tick-list panel that used to sit
    // above the board is gone, so adding is this form's dashed box, and a rename or a removal is the `⋯` on the
    // column itself.
    if (laneModalDrawn) {
        projectLaneModal(project, lane, mayWriteLanes, canManage)
    }
    // The Archive this board's cards are put away in (Stage 81), drawn for exactly the readers the header's 🗄 is
    // drawn for. Like the bucket popup it is carried closed, so its openers and its own forms have one block to
    // swap and a refusal is just the same page again with its sentence in it.
    if (mayWriteLanes) {
        projectArchivePanel(project, archiveOpen)
        // Outside the panel rather than inside it, unlike the bucket popup's own script: this block is swapped by
        // its two forms, and a script tag in there would be thrown away with the panel it belongs to.
        script(src = "/static/js/archive-modal.js") {}
    }
    // Dragging a card between these columns files it in the bucket it lands on, and that is [kanban.js]'s lane
    // half: a `.kcol[data-lane]` drop posts to `/app/tasks/bucket` and moves the card node in place, with no
    // reload under the reader's hands. Only [mayWriteLanes]'s cards carry `draggable`, so the board offers the
    // drag to exactly the people whose hand-built POST the service would accept. A card's **status** is a
    // different write from a different control — the Done button and the popup's Status field, both of which
    // reload the page the way a status board's drag does.
    script(src = "/static/js/kanban.js") {}
}

// This page prints no roll-up of its own board, on purpose. What a project amounts to is readable from the
// counts in the lane heads, from the progress bar in the detail card, and from the holder named on
// each card — a strip repeating those numbers was a third place to keep in step and the first place to
// disagree. (Stage 28 removed the tiles: Cards, Open, Done, Overdue, Steps and End date.)

/**
 * One read-only cell of a project's detail strip (Stage 71) — the shape [editField] prints for management, with
 * the control replaced by the value itself. [extra] carries the width class, because a category name and a date
 * do not need the same share of a row. The whole value travels as the cell's `title` as well as its text: a name
 * too long for its share is shortened on the page and complete on hover, which is the same bargain an input
 * makes when its text scrolls, and the alternative — letting it wrap — is the second line this strip exists to
 * remove.
 */
private fun DIV.detailCell(caption: String, value: String, extra: String) {
    div(("ef" + if (extra.isEmpty()) "" else " $extra").trim()) {
        span("ef-label") { +caption }
        span("detail-val") {
            attributes["title"] = value
            +value
        }
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
    "In progress" -> "ontrack"
    "Blocked" -> "atrisk"
    "Completed" -> "done"
    "On hold" -> "onhold"
    else -> "planning"
}
