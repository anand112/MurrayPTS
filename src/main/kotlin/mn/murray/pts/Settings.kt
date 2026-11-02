package mn.murray.pts
import mn.murray.pts.domain.*

import io.ktor.http.Parameters
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FORM
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.SELECT
import kotlinx.html.TBODY
import kotlinx.html.TD
import kotlinx.html.TH
import kotlinx.html.TR
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
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.security.Authorization
import mn.murray.pts.service.BucketDefaultsService
import mn.murray.pts.service.DueDateAlertService
import mn.murray.pts.service.EmployeeColumn
import mn.murray.pts.service.EmployeeDraft
import mn.murray.pts.service.EmployeeField
import mn.murray.pts.service.EmployeeFilter
import mn.murray.pts.service.EmployeeResult
import mn.murray.pts.service.EmployeeRow
import mn.murray.pts.service.EmployeeService
import mn.murray.pts.service.EmployeeSheet
import mn.murray.pts.service.MailSwitch
import mn.murray.pts.service.ProjectTypeService

const val MIN_PASSWORD_LENGTH = 4

/** Outcome of an account write. Passed back to the page after the redirect to drive the flash banner. */
enum class PasswordStatus(val isError: Boolean, val text: String) {
    CHANGED(false, "Password updated successfully."),
    TOO_SHORT(true, "Password must be at least $MIN_PASSWORD_LENGTH characters."),
    EMPTY(true, "Please fill in all password fields."),
    USER_NOT_FOUND(true, "No user found with that employee number."),
    USER_DELETED(false, "User deleted."),
    USER_ADDED(false, "User added."),
    USER_UPDATED(false, "Account updated."),
    CANNOT_DELETE_SELF(true, "You cannot delete your own account."),
    CANNOT_DELETE_SUPER(true, "The Super Admin account cannot be deleted."),
    // Stage 110: the bar reads the target's *Manage roles & permissions* chip, so the sentence names the right
    // rather than the old "Admin" word — which is no longer anything the sheet prints.
    CANNOT_DELETE_ADMIN(true, "Only somebody who manages roles can delete that account."),
    USER_EXISTS(true, "User name is taken."),
    MAIL_SENT(false, "Test email sent — check the inbox the message was addressed to."),
    MAIL_LOGGED(true, "Nothing left this machine: mail is in log-only mode."),
    MAIL_FAILED(true, "The mail server refused the test message."),
    MAIL_NO_ADDRESS(true, "No email on file for this account — set one on the Team sheet first."),
    MAIL_ENABLED(false, "Outbound mail is now enabled — PTS can send."),
    MAIL_DISABLED(true, "Outbound mail is now switched off — PTS will not send until it is enabled again."),
    MAIL_REFUSED(true, "Only somebody who manages email settings can turn outbound mail on or off."),
    // The lane add carries no text of its own since Stage 98: the receipt from BucketDefaultsService.add is
    // already a whole sentence naming the bucket, the category and how many boards took the new column, and
    // "Default lane added." in front of it would say the same thing twice (the TYPE_* pattern below).
    LANE_ADDED(false, ""),
    LANE_REMOVED(false, "Default lane removed."),
    LANE_REFUSED(true, "That lane cannot be added."),
    // The three category answers carry no text of their own: ProjectTypeService's reason is already a whole
    // sentence naming the letter, the label and how many projects moved, and prefixing it with "Category
    // added." would say the same thing twice. A blank text means `message()` prints the detail alone.
    TYPE_ADDED(false, ""),
    TYPE_RENAMED(false, ""),
    TYPE_REMOVED(false, ""),
    TYPE_REFUSED(true, "That category cannot be saved.");

    fun message(detail: String? = null): String = when {
        text.isBlank() -> detail.orEmpty()
        detail.isNullOrBlank() -> text
        else -> "$text ($detail)"
    }

    companion object {
        fun fromCode(code: String?): PasswordStatus? =
            code?.let { wanted -> entries.firstOrNull { it.name == wanted } }
    }
}

/** Green/red one-shot result banner rendered at the top of a card. */
fun DIV.flashBanner(status: PasswordStatus?, detail: String? = null) {
    status?.let { current ->
        div(if (current.isError) "flash err" else "flash ok") { +current.message(detail) }
    }
}

/**
 * The Settings page (Stage 46): the company's Team sheet first, then the reader's own account.
 *
 * The picture this page is built to is a roster with a search box over it — one row per person, seven
 * columns, and the two things anybody ever wanted from the old workload table (what this account may
 * do, and how much work is on it) printed as columns rather than as a count you have to click open.
 * So the sheet replaced that table here, and the account card underneath it stayed exactly where it
 * was. Stage 99 took the password box off the page: a password is set from the Team sheet, and the app
 * no longer stops a reader whose password is still the number the system gave them.
 *
 * How it updates, in the same shape the lesson sheet uses:
 *
 *  * the search box and the two pickers are one GET form onto this route and the table under them is a
 *    live region (`app.js`), so typing or choosing fetches and swaps the sheet — the toolbar is never
 *    replaced and the caret stays where it was.
 *  * each of the seven column headers is a form of its own carrying the whole view plus the order it is
 *    *asking* for, and `data-live-target="#team-board"`. A header is not a button of the toolbar's form
 *    because that form already holds a hidden `sort`: two controls with one name in one data set is an
 *    address that states the order twice, and without JavaScript the browser keeps the first.
 *  * a row's pencil is a GET opener that swaps `#employeeModal`; its trash is a POST that swaps the
 *    sheet and the count line with it, behind a confirm.
 *  * the popup saves to a route that redirects on a write that landed and answers with the page itself
 *    on a refusal, so the reason arrives under the field that caused it with the words still typed.
 *
 * [sheet] is the rows this reader may see, [form] the popup in the state this answer needs, and [note]
 * the one-shot line under the heading that says an account was added or taken away.
 */
fun DIV.settingsContent(
    session: UserSession,
    sheet: EmployeeSheet,
    filter: EmployeeFilter,
    form: EmployeeForm,
    canManage: Boolean,
    canAdmin: Boolean = false,
    /**
     * Stage 110: the page's panels are four different chips, so the four flags are carried apart. [canManage]
     * is *Manage employees* (the sheet), [canAdmin] *Manage employees* again for the popup's position and
     * password fields, [canSetRoles] *Manage roles & permissions* for its Role picker, [canTypes] *Manage
     * project types* for the two catalogue panels, and [canEmail] *Manage email settings* for the mail panel's
     * switch and its outbound test — the card's two status lines print for [canManage] too, so the people who
     * answer "why was I never emailed" can read it.
     * Their defaults keep the pre-110 call sites (and their tests) coherent: who could open the page before
     * could see all of it.
     */
    canSetRoles: Boolean = canAdmin,
    canTypes: Boolean = canManage,
    canEmail: Boolean = canAdmin,
    note: PasswordStatus? = null,
    mailStatus: PasswordStatus? = null,
    mailDetail: String? = null,
    bucketNote: PasswordStatus? = null,
    bucketDetail: String? = null,
    bucketDefaultsType: String = PROJECT_TYPES.first(),
    typeNote: PasswordStatus? = null,
    typeDetail: String? = null,
    typeBeingEdited: String = "",
) {
    // Stage 51: the Team sheet is the Settings *function* — the roster of accounts with its add/edit controls,
    // search and toolbar. It belongs to management alone, so a Viewer who reaches this page (the change-password
    // form below is every account's, and a first-login forced change lands here) sees none of the sheet. The write
    // routes refuse a non-manager again through mayAdd/mayEdit; this only stops printing it.
    if (canManage) {
        h3("set-section") { +"Team" }
        flashBanner(note)
        if (form.mayAdd) addEmployeeCard(filter)
        teamToolbar(filter, sheet, bucketDefaultsType)
        div("board-results") {
            id = "team-board"
            attributes["data-live-region"] = "true"
            if (sheet.isEmpty) teamEmpty(filter) else teamTable(sheet.rows, filter)
        }
    }

    accountCard(session)

    // The three panels below the account card are the settings *of the app*, not of the account: which
    // categories projects are filed under, which buckets a freshly created project starts with, and whether
    // outbound mail may leave this machine. Stage 110 split the old one `canManage` block into the two chips
    // they belong to — *Manage project types* for the catalogues, *Manage email settings* for the mail panel —
    // so a role that holds one of them sees that panel and only that panel. The order is the one the work flows
    // in: name the kinds of job, decide what each one's board looks like, then say whether anybody is told
    // about them by email.
    if (canTypes) {
        projectTypesPanel(typeNote, typeDetail, typeBeingEdited)
        bucketDefaultsPanel(bucketDefaultsType, bucketNote, bucketDetail, filter)
    }
    // The mail card is the one app panel whose *reading* is wider than its *writing*: whoever manages people
    // has to be able to see why a colleague was never emailed, so the delivery mode and their own address
    // print for them read-only, while the switch and the outbound test — the two presses that change what
    // leaves the machine — print only for a reader holding *Manage email settings*. Printing the card for the
    // second group alone would also leave a `MAIL_REFUSED` answer with no card to land on.
    if (canEmail || canManage) {
        emailPanel(session.employeeNumber, canMail = canEmail, mailStatus = mailStatus, mailDetail = mailDetail)
    }

    if (canManage || canTypes || canEmail) {
        employeeModal(form)
        script(src = "/static/js/employee-modal.js") {}
    }
}

/**
 * The card above the sheet that opens a new account.
 *
 * It is a GET form onto this page carrying `new=1`, and `app.js` answers it by fetching that URL and
 * swapping only `#employeeModal` — the same opener the lesson sheet's add button is. Management's
 * control, printed for management and re-checked by the route: [EmployeeService.save] refuses a create
 * from anybody a manager's account cannot make.
 */
private fun DIV.addEmployeeCard(filter: EmployeeFilter) {
    div("card-panel team-add") {
        p("panel-caption") {
            +("Add a new employee account. They sign in with their employee number, and until you set a " +
                "password on this sheet the number is the password.")
        }
        form(action = "/app/settings", method = FormMethod.get, classes = "opener-form") {
            attributes["data-live-target"] = "#employeeModal"
            input(type = InputType.hidden, name = "new") { value = "1" }
            viewCarriage(filter)
            button(type = ButtonType.submit, classes = "submit") { +"+ Add employee" }
        }
    }
}

/**
 * The search box, the two pickers and the count, in one GET form onto this route.
 *
 * The count is *inside* the form rather than parked beside it, because it is the one number on the page
 * that describes exactly what the three controls leave, and a companion (`data-live-extra`) is the only
 * way it can land with the sheet instead of after it. It sits at the far right by the stylesheet's
 * `margin-left: auto`, which is where the picture puts it.
 *
 * There is no Search button. The box is `[data-livefilter]`, so the send happens as the typing settles,
 * and Enter in the box submits this form the way it submits any other — with the pickers and the order
 * riding along, because they are its own fields.
 *
 * The hidden `sort` is the order the sheet is *already* read down: the pickers have no opinion about it,
 * and without it choosing a position would jump a Z→A list back to A→Z.
 */
private fun DIV.teamToolbar(filter: EmployeeFilter, sheet: EmployeeSheet, bucketType: String = PROJECT_TYPES.first()) {
    form(action = "/app/settings", method = FormMethod.get, classes = "board-toolbar") {
        id = "tsFilter"
        // The bucket card rides along: its category form carries this view's fields (viewCarriage), so a
        // sheet filtered here must hand the new copy its hidden q/position/role/sort or the next category
        // change would write them away — and the next add would land on a panel the address no longer names.
        attributes["data-live-extra"] = "#team-counts,#bucketDefaults"
        // The page carries two independent views, and its address carries both. Without this the team's
        // own filter would write a query with no `lanes`, so a reload after searching the roster would
        // put the bucket panel back on the first category — and the next add would land there (Stage 82 fix).
        input(type = InputType.hidden, name = "lanes") {
            // Named so the bucket picker can hand this copy the category the reader just chose: the two
            // views share one address, and a stale hidden field here would send the roster's next filter
            // back to the category the page opened on.
            id = "bucketCategoryKey"
            value = bucketType
        }
        div("board-search") {
            input(type = InputType.text, name = "q") {
                id = "teamSearch"
                value = filter.text
                placeholder = "Search name, email, employee #…"
                autoComplete = "off"
                attributes["data-livefilter"] = "true"
                // A live filter reloads the sheet, and a reloaded sheet has no caret; the box the reader
                // was typing in is the only thing worth taking focus back to.
                if (filter.text.isNotBlank()) autoFocus = true
            }
        }
        label("board-key") {
            +"Position"
            select {
                name = "position"
                attributes["data-autosubmit"] = "true"
                tsOption("", "All positions", filter.position)
                sheet.positions.forEach { tsOption(it, it, filter.position) }
            }
        }
        label("board-key") {
            +"Role"
            select {
                name = "role"
                attributes["data-autosubmit"] = "true"
                tsOption("", "All roles", filter.role)
                EmployeeService.roles().forEach { tsOption(it, it, filter.role) }
            }
        }
        // Clear answers the three controls and nothing else: the order is not one of them, and a reader
        // who searched their way to five rows still wants to read them in the order they chose.
        if (filter.isActive) {
            a(classes = "board-reset", href = filter.cleared().queryFor()) { +"Clear" }
        }
        if (filter.sortValue.isNotEmpty()) input(type = InputType.hidden, name = "sort") { value = filter.sortValue }
        span("board-count") {
            id = "team-counts"
            +"${sheet.shown} of ${sheet.total} shown"
        }
    }
}

private fun SELECT.tsOption(value: String, label: String, chosen: String) {
    option {
        this.value = value
        selected = value == chosen
        +label
    }
}

/**
 * The pickers and the order, carried into a GET form that is not the toolbar's.
 *
 * Same reason the lesson sheet carries its four view keys: an opener that dropped them would answer on a
 * different sheet than the one the reader is standing on — the popup would close onto the whole roster,
 * or a header click would forget the search that made this list five rows long.
 */
private fun FORM.viewCarriage(filter: EmployeeFilter) {
    if (filter.text.isNotEmpty()) input(type = InputType.hidden, name = "q") { value = filter.text }
    if (filter.position.isNotEmpty()) input(type = InputType.hidden, name = "position") { value = filter.position }
    if (filter.role.isNotEmpty()) input(type = InputType.hidden, name = "role") { value = filter.role }
    if (filter.sortValue.isNotEmpty()) input(type = InputType.hidden, name = "sort") { value = filter.sortValue }
}

/**
 * The same view for a form that *writes*, under `board_*`.
 *
 * The prefix is not decoration: the popup's own fields are named `position` and `role`, and the delete
 * form is on a sheet that may be filtered by exactly those two words. Un-prefixed, a popup opened while
 * the sheet is filtered to Administrators would arrive stating `role` twice — once as the account's new
 * role and once as the filter — and the answer would be a sheet about the wrong thing.
 */
private fun FORM.boardCarriage(filter: EmployeeFilter) {
    if (filter.text.isNotEmpty()) input(type = InputType.hidden, name = "board_q") { value = filter.text }
    if (filter.position.isNotEmpty()) input(type = InputType.hidden, name = "board_position") { value = filter.position }
    if (filter.role.isNotEmpty()) input(type = InputType.hidden, name = "board_role") { value = filter.role }
    if (filter.sortValue.isNotEmpty()) input(type = InputType.hidden, name = "board_sort") { value = filter.sortValue }
}

/** The sheet: the seven columns the picture reads it down, plus the two buttons on each row. */
private fun DIV.teamTable(rows: List<EmployeeRow>, filter: EmployeeFilter) {
    sortCarriage(filter)
    table("grid team-sheet") {
        colGroup { T_COLUMNS.forEach { col(classes = it) } }
        thead {
            tr {
                EmployeeColumn.entries.forEachIndexed { index, column ->
                    th(classes = T_COLUMNS[index]) { columnHeader(column, filter) }
                }
                th(classes = T_COLUMNS.last()) { }
            }
        }
        tbody { rows.forEach { teamRow(it, filter) } }
    }
}

/**
 * The one form the seven headers press, holding the three controls they all answer.
 *
 * It is *not* the toolbar's form. The toolbar already carries a hidden `sort` so its pickers keep the
 * order, and a header button of that same form would state the order twice — with JavaScript the pressed
 * submitter overwrites the hidden one, but without it the browser sends both and the sheet would reload
 * in the order it was already in. Here there is no hidden `sort` to collide with: the value the header
 * asks for is the only thing its button puts on the send.
 *
 * A `<form>` cannot live inside a `<th>` — the content model forbids it, and the browser would lift it
 * out of the table — so it sits above the table and the seven buttons belong to it by id. It is inside
 * the live region rather than outside it because its own fields are the search and the two pickers as
 * this response renders them.
 */
private fun DIV.sortCarriage(filter: EmployeeFilter) {
    form(action = "/app/settings", method = FormMethod.get, classes = "col-sort-form") {
        id = "tsSort"
        attributes["data-live-target"] = "#team-board"
        if (filter.text.isNotEmpty()) input(type = InputType.hidden, name = "q") { value = filter.text }
        if (filter.position.isNotEmpty()) input(type = InputType.hidden, name = "position") { value = filter.position }
        if (filter.role.isNotEmpty()) input(type = InputType.hidden, name = "role") { value = filter.role }
    }
}

/**
 * One column header, which is also the control that reverses it.
 *
 * Its value is the order being *asked for*, not the one on screen — the same absolute-value rule the
 * lesson sheet's date header follows, so a reload cannot flip the sheet back again. A column that is not
 * the current one is asked for head-first; the one that is gets turned over.
 */
private fun TH.columnHeader(column: EmployeeColumn, filter: EmployeeFilter) {
    val asked = filter.sortValueFor(column)
    button(type = ButtonType.submit, classes = "col-sort") {
        attributes["form"] = "tsSort"
        name = "sort"
        value = asked
        attributes["title"] = "Sort by ${column.label.lowercase()} — ${if (asked.endsWith(":desc")) "lowest first" else "highest first"}"
        +column.label.uppercase()
        span("col-sort-glyph") { +filter.glyphFor(column) }
    }
}

private fun TBODY.teamRow(row: EmployeeRow, filter: EmployeeFilter) {
    tr("ts-row") {
        id = "ts-row-${row.employeeNumber}"
        td("t-name") {
            // The avatar's colour and its two letters come from the same pair every other sheet in the
            // app reads, so one person is one colour and one abbreviation wherever they are listed.
            span("ts-person") {
                span("avatar sm ${avatarTint(row.employeeNumber)}") { +initials(row.name) }
                span("ts-person-name") { +row.name.ifEmpty { "—" } }
                // The picture's own marker for the row you are reading about yourself, and the only word
                // on the sheet that is about the reader rather than the person in the row.
                if (row.isSelf) span("chip ontrack ts-you") { +"YOU" }
            }
        }
        td("t-emp") { span("code") { +row.employeeNumber } }
        td("t-position") { +row.position }
        td("t-department") { if (row.department.isBlank()) +"—" else +row.department }
        td("t-role") {
            if (row.role.isEmpty()) {
                span("ts-none") { +"—" }
            } else {
                span("chip ${roleChip(row.role)}") { +row.role }
            }
        }
        td("t-email") { span("ts-email") { +row.email } }
        td("t-tasks") { +(row.openTasks?.toString() ?: "—") }
        td("t-actions") { rowActions(row, filter) }
    }
}

/**
 * The app's own colour for a role, the same one the Access control page prints.
 *
 * Since Stage 110 the name beside a person is one of the catalogue's rows rather than one of three rank words,
 * so any role that is not one of the three seeded ones takes the last of these — colour is decoration, and what
 * a role may do is written in its chips on Access control, not in the tint of its chip here.
 */
internal fun roleChip(role: String): String = when (role) {
    "Owner", "Administrator" -> "atrisk"
    "Project Manager" -> "planning"
    else -> "done"
}

/**
 * The row's two buttons: the pencil that opens the account, and the bin that takes it off the sheet.
 *
 * A pencil is admin's, and a row with no account behind it is not a reason not to print one — it opens
 * the create popup filled with that person, which is what the roster row has been waiting for.
 *
 * The bin prints for everybody management, and for everybody else it prints greyed with the rule that
 * refuses it as its title. That is presentation of a right and not the right itself:
 * [EmployeeService.save] and [UserService.deleteUser] decide again on every one of these routes.
 */
private fun TD.rowActions(row: EmployeeRow, filter: EmployeeFilter) {
    val who = row.name.ifEmpty { row.employeeNumber }
    div("icon-btns") {
        if (row.mayEdit) {
            val openerHint = if (row.hasAccount) "Change $who" else "Create an account for $who"
            form(action = "/app/settings", method = FormMethod.get, classes = "opener-form ts-action") {
                attributes["data-live-target"] = "#employeeModal"
                input(type = InputType.hidden, name = "edit") { value = row.employeeNumber }
                viewCarriage(filter)
                button(type = ButtonType.submit, classes = "icon-btn ts-edit") {
                    attributes["title"] = openerHint
                    attributes["aria-label"] = openerHint
                    +"✎"
                }
            }
        }
        val blocked = row.deleteBlocked
        val deleteHint = blocked ?: "Delete $who"
        form(action = "/app/settings/employee/delete", method = FormMethod.post, classes = "ts-action") {
            attributes["data-live-target"] = "#team-board"
            attributes["data-live-extra"] = "#team-counts"
            input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
            boardCarriage(filter)
            button(type = ButtonType.submit, classes = "icon-btn") {
                attributes["title"] = deleteHint
                attributes["aria-label"] = deleteHint
                if (blocked == null) {
                    attributes["data-confirm"] =
                        "Delete the account ${row.employeeNumber}? Its sign-in and sessions go with it."
                } else {
                    // Disabled rather than absent: the column has to stay lined up, and the reason is the
                    // only answer to the question the greyed button is already asking.
                    disabled = true
                }
                +"🗑"
            }
        }
    }
}

/** The sheet with nothing on it, and the two different reasons there is nothing on it. */
private fun DIV.teamEmpty(filter: EmployeeFilter) {
    div("board-empty") {
        if (filter.isActive) {
            p("board-empty-line") { +"Nobody matches this search." }
            p("board-empty-sub") { +"The roster is what the Team page prints — this sheet lists it with the accounts filed against it." }
            a(classes = "row-link", href = "/app/settings") { +"Show everybody" }
        } else {
            p("board-empty-line") { +"Nobody is on the sheet yet." }
            p("board-empty-sub") {
                +(
                    "A row is a person: the Team page adds them, and the account they sign in with is " +
                        "filed against that row from here."
                    )
            }
        }
    }
}

/** The reader's own account — the four things the old page printed, in the same rows. */
private fun DIV.accountCard(session: UserSession) {
    val user = DemoData.userFor(session.employeeNumber)
    h3("set-section") { +"Your account" }
    div("card-panel") {
        div("set-row") {
            span("set-key") { +"Employee number" }
            span { +session.employeeNumber }
        }
        div("set-row") {
            span("set-key") { +"Display name" }
            span { +user.name }
        }
        div("set-row") {
            span("set-key") { +"Position" }
            span { +user.position }
        }
        div("set-row") {
            span("set-key") { +"Role" }
            span { +user.role.ifEmpty { "—" } }
        }
        p("task-meta") {
            +"Use the 🎨 button in the sidebar to cycle the accent colour and 🌙 for dark mode. Preferences are stored in your browser."
        }
    }
}

/**
 * The categories a project can be filed under — the V14 catalogue, and management's to edit.
 *
 * Each row is the letter that leads that category's project numbers (the S in S-01), the label, and how many
 * projects the company has under it right now. [ProjectTypeService] owns what may be written here, and its two
 * rules are what the panel is shaped around: **a label may be renamed** — the projects and the per-category
 * lanes that store it are carried across in the same transaction, so nothing is orphaned — and **a letter may
 * not**. It is the catalogue's key and the first token of numbers people already quote to each other, so the
 * pencil edits only the words and a retired category's letter is simply free again, never reassigned.
 *
 * The rename is an inline swap rather than a popup: one field, already holding the words being changed, and
 * the panel is its own live region (`#projectTypes`), so a save or a cancel replaces this card and the sheet
 * above it never moves. A refusal comes back as the card's own flash line, beside the reason.
 */
private fun DIV.projectTypesPanel(note: PasswordStatus?, detail: String?, beingEdited: String) {
    h3("set-section") { +"Project types" }
    div("card-panel") {
        id = "projectTypes"
        p("panel-caption") {
            +("These are the type codes available when creating a project, shown as the small badge in the " +
                "project list and used as the group heading on the forecasts sheet. Each one leads its project " +
                "numbers with its letter — the S in S-01 — so a letter is issued once and never reused: " +
                "renaming a category moves the projects filed under it, retiring one is refused while any remain.")
        }
        flashBanner(note, detail)
        // By letter, not by the catalogue's own order: the list below is a lookup of "which code is which
        // category", and a reader scanning it for the S knows the letter they are hunting, not the position
        // somebody added the row at. The create-project picker keeps the catalogue order.
        ProjectTypeService.list().sortedBy { it.code }.forEach { row ->
            if (row.name == beingEdited) typeRenameRow(row) else typeRow(row)
        }
        addTypeForm()
    }
}

/**
 * One category: badge, label, how many projects it holds, and the two writes that row may answer.
 *
 * The bin is printed even on a category that cannot be retired, because the refusal is the answer the reader
 * needs to read — "3 projects are filed under Site projects. Move them to another category first" — and a
 * control that is quietly absent teaches nothing about why the row is still there. [ProjectTypeService.remove]
 * decides again on the route, as always.
 */
private fun DIV.typeRow(row: ProjectType) {
    div("set-row type-row") {
        span("code type-letter") { +row.code }
        span("chip ${typeChipClass(row.name)}") { +row.name }
        span("type-count") { +projectCountByType(row.name) }
        div("icon-btns") {
            form(action = "/app/settings", method = FormMethod.get, classes = "opener-form ts-action") {
                attributes["data-live-target"] = "#projectTypes"
                input(type = InputType.hidden, name = "edittype") { value = row.name }
                button(type = ButtonType.submit, classes = "icon-btn ts-edit") {
                    attributes["title"] = "Rename ${row.name}"
                    attributes["aria-label"] = "Rename ${row.name}"
                    +"✎"
                }
            }
            form(action = "/app/settings/types/delete", method = FormMethod.post, classes = "ts-action") {
                attributes["data-live-target"] = "#projectTypes"
                input(type = InputType.hidden, name = "code") { value = row.code }
                button(type = ButtonType.submit, classes = "icon-btn") {
                    attributes["title"] = "Retire ${row.name}"
                    attributes["aria-label"] = "Retire ${row.name}"
                    attributes["data-confirm"] =
                        "Stop offering ${row.name} when a project is created? Projects already filed under it " +
                            "keep their category and have to be moved first."
                    +"🗑"
                }
            }
        }
    }
}

/**
 * The row the pencil opened: the label in a field, and nothing else editable.
 *
 * The letter stays a badge rather than becoming an input for the same reason [ProjectTypeService.rename]
 * cannot change it — every project number under this category begins with that character, and a field that
 * looked changeable would promise a renumbering the app will not do.
 */
private fun DIV.typeRenameRow(row: ProjectType) {
    div("set-row type-row editing") {
        span("code type-letter") { +row.code }
        form(action = "/app/settings/types/rename", method = FormMethod.post, classes = "type-rename") {
            attributes["data-live-target"] = "#projectTypes"
            input(type = InputType.hidden, name = "name") { value = row.name }
            input(type = InputType.text, name = "newname") {
                value = row.name
                autoComplete = "off"
                required = true
                attributes["maxlength"] = ProjectTypeService.NAME_MAX.toString()
                attributes["placeholder"] = "New name for ${row.code}"
            }
            button(type = ButtonType.submit, classes = "submit btn-sm") { +"Save" }
        }
        form(action = "/app/settings", method = FormMethod.get, classes = "opener-form ts-action") {
            attributes["data-live-target"] = "#projectTypes"
            button(type = ButtonType.submit, classes = "ghost-btn btn-sm") {
                attributes["title"] = "Keep ${row.name}"
                attributes["aria-label"] = "Keep ${row.name}"
                +"Cancel"
            }
        }
    }
}

/**
 * The add line under the list: a letter and a name, in that order, because the letter is the decision and
 * the name is the label. Both are bounded by what [ProjectTypeService.add] accepts — one character, A to Z —
 * and a collision with a letter already in use comes back as the panel's own refusal line.
 */
private fun DIV.addTypeForm() {
    form(action = "/app/settings/types/add", method = FormMethod.post, classes = "type-add") {
        attributes["data-live-target"] = "#projectTypes"
        input(type = InputType.text, name = "code") {
            autoComplete = "off"
            required = true
            attributes["maxlength"] = "1"
            attributes["placeholder"] = "Code, e.g. P"
            attributes["aria-label"] = "New category letter"
        }
        input(type = InputType.text, name = "name") {
            autoComplete = "off"
            required = true
            attributes["maxlength"] = ProjectTypeService.NAME_MAX.toString()
            attributes["placeholder"] = "Description, e.g. Parts supply"
            attributes["aria-label"] = "New category name"
        }
        button(type = ButtonType.submit, classes = "submit btn-sm") { +"+ Add type" }
    }
}

/**
 * How many projects the company actually has under a category, in the words the row's right edge answers.
 *
 * Printed because nine labels alone say nothing about the business: the row reading "no projects yet" is the
 * one a default lane in the panel below is not worth adding to, and the one with forty is the board worth
 * templating. A count of rows whose `type` matches exactly, so a legacy label V3 re-coded is not silently
 * folded into a modern one.
 */
private fun projectCountByType(type: String): String =
    when (val count = ProjectRepository.findByType(type).size) {
        0 -> "no projects yet"
        1 -> "1 project"
        else -> "$count projects"
    }

/**
 * Whether outbound mail can leave this machine, and the one button that proves it.
 *
 * The figure is [DueDateAlertService.currentMode]'s — the same string the boot log and the admin trigger
 * print — because the alert sweep, the notification mail and this test all go through the one SMTP route in
 * [mn.murray.pts.service.Mailer]; a panel that reported a different transport than the sweep uses would be
 * describing a machine that is not this one. Nothing here is editable: the host, the sender and the dry-run
 * flag live in `data/.env` (`PTS_SMTP_*`), and a password typed into a web form is a password written into
 * a database — the file is where the credential belongs.
 *
 * The test sends to **the reader's own address** and cannot be pointed at a colleague: one message, no ledger
 * row, so it cannot spend the day's alert budget and can be pressed again straight after fixing a credential.
 * The answer comes back as this card's own flash line, in place, with the server's reason beside it when the
 * send was refused or logged instead of delivered.
 *
 * The two status lines print for anybody who manages employees (a Project Manager reads the delivery mode and
 * their own address), but the switch and the test button print only for a reader holding *Manage email settings*
 * — the same chip [mn.murray.pts.Application]'s `/app/settings/mail-switch` and `/app/settings/email-test`
 * re-check, so the panel never offers a press the route would silently bounce.
 */
private fun DIV.emailPanel(
    empNo: String,
    canMail: Boolean,
    mailStatus: PasswordStatus? = null,
    mailDetail: String? = null,
) {
    val mode = DueDateAlertService.currentMode()
    val address = DueDateAlertService.emailFor(empNo)
    val sending = MailSwitch.enabled()
    h3("set-section") { +"Email notifications" }
    div("card-panel") {
        id = "emailBoard"
        p("panel-caption") {
            +("Due-date alerts and assignment notices go out over the SMTP server configured in " +
                "data/.env (PTS_SMTP_*). Change those keys and restart — a password does not belong in a " +
                "web form. Independently, outbound mail has to be turned **on** here — by an account that " +
                "manages email settings — before the system is allowed to send anything at all; while it is " +
                "off every message stays in the log.")
        }
        flashBanner(mailStatus, mailDetail)
        div("set-row") {
            span("set-key") { +"Delivery mode" }
            span("email-mode") { +mode }
        }
        // Management that cannot move the switch still reads whether mail may leave the machine at all. A reader
        // holding the chip has that same state printed as the control below, rather than twice.
        if (!canMail) {
            div("set-row") {
                span("set-key") { +"Outbound mail" }
                span(if (sending) "email-switch on" else "email-switch off") {
                    +(if (sending) "Enabled — PTS can send" else "Switched off — PTS will not send")
                }
            }
        }
        div("set-row") {
            span("set-key") { +"Your address" }
            span { +address.ifBlank { "(no email on file for $empNo — set one on the Team sheet)" } }
        }
        // The master switch is the one control that decides whether the whole installation may email anyone,
        // so it prints as a form for a reader holding *Manage email settings* only — the same chip
        // `/app/settings/mail-switch` re-checks.
        // The box carries the state (ticked = sending, and the route reads anything but "true" as off, so a
        // hand-typed value cannot quietly arm the mailer) and *Save settings* is the no-JavaScript path to the
        // same write. Only a press that would turn sending on is confirmed: stopping mail asks nothing of anyone.
        if (canMail) {
            form(action = "/app/settings/mail-switch", method = FormMethod.post, classes = "mail-switch") {
                attributes["data-live-target"] = "#emailBoard"
                label("check-row") {
                    input(type = InputType.checkBox, name = "on") {
                        value = "true"
                        checked = sending
                    }
                    +"Enable email notifications"
                }
                button(type = ButtonType.submit, classes = "submit btn-sm") {
                    if (!sending) {
                        attributes["data-confirm"] =
                            "Allow PTS to send email to people? Due-date alerts and notices will start going " +
                                "out to the addresses on the Team sheet."
                    }
                    +"Save settings"
                }
            }
        }
        // The test sends to **the reader's own address** and cannot be pointed at a colleague: one message, no ledger
        // row, so it cannot spend the day's alert budget and can be pressed again straight after fixing a credential.
        // The answer comes back as this card's own flash line, in place, with the server's reason beside it when the
        // send was refused or logged instead of delivered.
        //
        // It prints only when there is an address to send to *and* the reader holds *Manage email settings* — the
        // test is the one
        // control on the page that opens a connection out of the machine, so it is gated exactly as the
        // `/app/alerts/test` endpoint it mirrors is. When the master switch is still off this button is honest about
        // it: the press would only come back "logged", so the line beside it already says what to do about that.
        if (address.isNotBlank() && canMail) {
            form(action = "/app/settings/email-test", method = FormMethod.post, classes = "opener-form") {
                attributes["data-live-target"] = "#emailBoard"
                button(type = ButtonType.submit, classes = "submit") {
                    attributes["data-confirm"] = "Send one test message to $address?"
                    +"Send test email to myself"
                }
            }
        }
    }
}

/**
 * The buckets a project of [type] opens with, and nothing else.
 *
 * Since **Stage 100** this table is the whole board. [mn.murray.pts.repository.ProjectService.create] seeds a
 * new project with exactly the rows listed here, in this order — the three phases Engineering, Procurement and
 * Production are not added by the code any more, so a category that wants them lists them like any other
 * bucket, under its own spelling. A category with no rows at all has no column to file a card in, and until
 * **Stage 104** that refused the create outright; since then the create popup may name that board's first column,
 * so an empty category makes its projects one at a time — the popup points here when it names nothing either.
 * Existing boards are untouched
 * by this — they keep the lanes their manager gave them; what changes is what the *next* project opens with.
 *
 * A row is added by naming it; since Stage 83 the app picks the colour, and the column it seeds wears that same
 * shade. Taking a row off stops new projects getting it — the boards that already carry that column keep it.
 * Since Stage 73 a bucket typed into a project's own page can be written back here too, so a bucket the company
 * starts using becomes one the category opens with — and since **Stage 77** only when the reader ticks the box
 * under the name field, which is drawn for management alone: left clear, a typed bucket is that project's
 * column and no other board ever hears about it.
 *
 * The category picker is a plain GET onto this page carrying `?lanes=`, so the panel answers for the category
 * chosen rather than always the first one, and the two write forms carry the same key hidden so their answers
 * land on the category being edited. The add line prints that key where it is used — *"Adds to Site projects
 * only"* — because a bucket named for one category is offered to that category's new projects and to nobody
 * else's, and the hidden field alone leaves the reader guessing which of the nine the word went to.
 *
 * Since **Stage 80** a dot is read from its row rather than counted off its place: a bucket somebody gave a
 * shade wears that shade here and on every board it later lands on, and one with no shade of its own wears the
 * colour of its position. **Stage 83 stopped asking**: creating a bucket is a name and a button, and the colour
 * the new row — and the column it later seeds — wears is the next shade in the run, picked by the app. The
 * choice is not gone, only moved to where it belongs: a bucket already on a project's board can be recoloured
 * from its own `⋯`, where the reader can see what they are changing.
 */
private fun DIV.bucketDefaultsPanel(
    type: String,
    note: PasswordStatus? = null,
    detail: String? = null,
    filter: EmployeeFilter = EmployeeFilter(),
) {
    val entries = BucketDefaultsService.bucketsOf(type)
    h3("set-section") { +"Project bucket settings" }
    div("card-panel") {
        id = "bucketDefaults"
        p("panel-caption") {
            +(
                "The buckets a $type project opens with — these and nothing else, in this order, apart from a " +
                    "column named while creating a project. " +
                    "Adding one puts an empty column on each of this category's projects now, and every project " +
                    "created afterwards opens with it; taking a row off stops new projects getting it, while the " +
                    "boards that already carry that column keep it. A project needs a column to file its first " +
                    "card in, so name one here for every project of this category, or name this project's first " +
                    "one while creating it. A project's own page is where a column is renamed or taken off. A bucket typed into " +
                    "a project's own picker joins this list only when management tick the box under its name; " +
                    "left clear, that bucket is one board's own. Each row's dot is the colour of its place in " +
                    "the run, and the column that bucket puts on a board opens wearing that same shade — the app " +
                    "picks it, so adding a bucket asks for a name and nothing else."
                )
        }
        form(action = "/app/settings", method = FormMethod.get, classes = "board-toolbar") {
            // The add line sits outside the live region but belongs to the same choice: its hidden `lanes`
            // and its *"Adds to … only"* label name the category. Without this companion the picker could
            // swap the rows and leave the form posting the category the reader had just left (Stage 82 fix).
            attributes["data-live-extra"] = "#bucketAdd,#bucketCategoryKey"
            // The other view's keys ride along too, so choosing a category does not write away the
            // roster's search, its two pickers or its order (Stage 82 fix; same carriage as the
            // employee card and the pencils, `viewCarriage`).
            viewCarriage(filter)
            label("board-key") {
                +"Category"
                select {
                    name = "lanes"
                    attributes["data-autosubmit"] = "true"
                    PROJECT_TYPES.forEach { tsOption(it, typeLabel(it), type) }
                }
            }
        }
        div("board-results") {
            // The Settings page carries two live regions (the Team roster above and this one), so this
            // region names itself: `app.js` matches the response's copy by id, and a category change
            // replaces this list rather than the first region on the page. Without the id the select
            // moved the roster, the hidden `lanes` field stayed on the old category, and the next add
            // wrote there (Stage 82 fix).
            id = "bucketDefaultsResults"
            attributes["data-live-region"] = "true"
            flashBanner(note, detail)
            // Stage 100: no fixed phase rows any more. This list is the whole board a new project opens with, so
            // every row is an ordinary catalogue row — a dot, a name, a bin — and the three phases appear here
            // only because a category lists them.
            if (entries.isEmpty()) {
                p("board-empty-line") {
                    // Stage 104 made this sentence true again: an empty category still has no column to file a
                    // card in, but its projects can be created one at a time — the create popup names the first
                    // column. The old wording said no project of this category could be created at all.
                    +("$type has no buckets yet — add one below, or name one while creating a project.")
                }
            } else {
                entries.forEachIndexed { index, entry ->
                    div("set-row lane-row") {
                        // The catalogue and a board both number from 0, so the catalogue's first row is that
                        // board's first column and QAQC wears the same shade on both pages (Stage 100; see
                        // [BUCKET_DOT_COLOURS]). Since Stage 80 a bucket with a colour of its own overrides
                        // that — the position only answers for one nobody picked a shade for.
                        span(bucketDotClass(index, entry.colourSlot)) { }
                        span("lane-name") { +entry.name }
                        form(action = "/app/settings/buckets/delete", method = FormMethod.post, classes = "ts-action") {
                            attributes["data-live-target"] = "#bucketDefaults"
                            input(type = InputType.hidden, name = "id") { value = entry.id.toString() }
                            input(type = InputType.hidden, name = "lanes") { value = type }
                            button(type = ButtonType.submit, classes = "icon-btn") {
                                attributes["data-confirm"] =
                                    "Stop adding ${entry.name} from new $type projects? Existing boards keep the lane."
                                attributes["aria-label"] = "Remove ${entry.name}"
                                +"🗑"
                            }
                        }
                    }
                }
            }
        }
        // One line, the way a lane is added on a live board: name it, press the button. Since Stage 83 the colour
        // is the app's answer rather than the reader's — the new row, and the future column seeded from it, wear
        // the next shade in the run. The category rides along hidden, so a lane typed while Fabrication is on
        // show lands on Fabrication and the
        // answer comes back to the same panel rather than to the catalogue's first category — and it is written
        // out at the box as well, because the scoping is the whole point of this panel and a hidden field is not
        // a reader being told: the word added here belongs to the category on show and to no other.
        form(action = "/app/settings/buckets/add", method = FormMethod.post, classes = "bucket-add") {
            id = "bucketAdd"
            attributes["data-live-target"] = "#bucketDefaults"
            span("bucket-add-for") { +"Adds to $type only" }
            input(type = InputType.text, name = "name") {
                attributes["placeholder"] = "New bucket name, e.g. QAQC"
                attributes["aria-label"] = "New default bucket for $type"
                autoComplete = "off"
                required = true
                attributes["maxlength"] = PROJECT_BUCKET_MAX.toString()
            }
            input(type = InputType.hidden, name = "lanes") { value = type }
            button(type = ButtonType.submit, classes = "submit btn-sm") { +"+ Add bucket" }
        }
    }
}

/**
 * The popup behind `+ Add employee` and a row's pencil.
 *
 * One node for both, for the reason the lesson sheet gives: the page can only swap a block that has one
 * id. The save names `#team-board` as its target and the count line and the popup as its companions, so
 * a write that landed comes back as a sheet with the row on it and a closed form, and one that was
 * refused comes back with the form still up, the words still in it and the reason under its field.
 *
 * [EmployeeForm.maySetRoles] is the admin half of the form. A manager's popup asks for a number, a name
 * and an address and nothing else, because a position, a role and a password are not theirs to give —
 * and printing three fields that the route would refuse is a promise the page has no right to make.
 */
private fun DIV.employeeModal(form: EmployeeForm) {
    div("modal") {
        id = "employeeModal"
        if (form.isOpen) attributes["style"] = "display:block"
        div("modal-content modal-wide") {
            span("close") {
                attributes["data-employee-modal-close"] = "true"
                +"×"
            }
            h3 { +if (form.isEdit) "Change account" else "Add employee" }
            p("modal-scope") {
                +(
                    if (form.isEdit) {
                        "Their name and employee number stay as the roster has them — projects and cards " +
                            "name people by those words."
                    } else if (form.maySetRoles) {
                        "They sign in with the number and the password you set here. Leave the password " +
                            "blank and the number is it; you can set a new one here at any time."
                    } else {
                        "They sign in with their employee number as the password, and start in the " +
                            "${form.defaultRole} role — the one the catalogue hands out by default."
                    }
                    )
            }
            form(action = "/app/settings/employee/save", method = FormMethod.post) {
                attributes["data-live-target"] = "#team-board"
                attributes["data-live-extra"] = "#team-counts,#employeeModal"
                if (form.isEdit) input(type = InputType.hidden, name = "edit") { value = form.edit }
                boardCarriage(form.filter)
                div("form-grid") {
                    div("field") {
                        label { +"Employee number" }
                        input(type = InputType.text, name = "employeeNumber") {
                            id = "employeeNumber"
                            value = form.employeeNumber
                            attributes["placeholder"] = "EMP0023"
                            autoComplete = "off"
                            // On an edit the number is the row's own, and the hidden `edit` above is what
                            // the route writes against — a field the reader cannot change, printed because
                            // they are looking at the account it names.
                            if (form.isEdit) attributes["readonly"] = "readonly"
                        }
                        form.error(EmployeeField.EMP_NO)?.let { p("field-err") { +it } }
                        form.error(EmployeeField.ROW)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Name" }
                        input(type = InputType.text, name = "name") {
                            id = "employeeName"
                            value = form.name
                            autoComplete = "off"
                            if (form.isEdit) attributes["readonly"] = "readonly"
                        }
                        form.error(EmployeeField.NAME)?.let { p("field-err") { +it } }
                    }
                    div("field") {
                        label { +"Email" }
                        input(type = InputType.email, name = "email") {
                            id = "employeeEmail"
                            value = form.email
                            attributes["placeholder"] = "Blank means the company address built from their name"
                            autoComplete = "off"
                        }
                        form.error(EmployeeField.EMAIL)?.let { p("field-err") { +it } }
                    }
                    if (form.maySetRoles) {
                        div("field") {
                            label { +"Role" }
                            select {
                                name = "role"
                                id = "employeeRole"
                                form.roles.forEach { tsOption(it, it, form.role.ifEmpty { form.defaultRole }) }
                            }
                            form.error(EmployeeField.ROLE)?.let { p("field-err") { +it } }
                        }
                    }
                    if (form.maySetProfile) {
                        div("field") {
                            label { +"Position" }
                            select {
                                name = "position"
                                id = "employeePosition"
                                tsOption("", "— Follow the roster —", form.position)
                                form.positions.forEach { tsOption(it, it, form.position) }
                            }
                            form.error(EmployeeField.POSITION)?.let { p("field-err") { +it } }
                        }
                        div("field") {
                            label { +"Password" }
                            input(type = InputType.password, name = "password") {
                                id = "employeePassword"
                                attributes["placeholder"] =
                                    if (form.isEdit) "Leave blank to keep the current password"
                                    else "Blank means their employee number"
                                attributes["autocomplete"] = "new-password"
                                attributes["minlength"] = MIN_PASSWORD_LENGTH.toString()
                            }
                            form.error(EmployeeField.PASSWORD)?.let { p("field-err") { +it } }
                        }
                    }
                    form.error(EmployeeField.PERMISSION)?.let { p("field-err full") { +it } }
                }
                div("modal-actions") {
                    button(type = ButtonType.submit, classes = "submit") {
                        +if (form.isEdit) "Save account" else "Add employee"
                    }
                    button(type = ButtonType.button, classes = "ghost-btn") {
                        attributes["data-employee-modal-close"] = "true"
                        +"Cancel"
                    }
                }
            }
        }
    }
}

/**
 * What the popup holds: the account being written, the field each refusal belongs under, and whether it
 * is up at all.
 *
 * [edit] is the number of the account being changed, and blank for a create — including for the create
 * opened from a roster row that has no account yet, which is filled with that person's name and number
 * but writes a new account rather than looking for one that is not there.
 *
 * [mayAdd] and [maySetRoles] are this reader's two rights, carried in so the page can print the controls
 * that match them: management's popup opens, and only an administrator's asks for a position, a role and
 * a password. [positions] and [filter] ride along for the same reason — the picker's choices and the sheet
 * the popup has to answer when it closes.
 */
data class EmployeeForm(
    val edit: String = "",
    val employeeNumber: String = "",
    val name: String = "",
    val email: String = "",
    val position: String = "",
    val role: String = "",
    val open: Boolean = false,
    val mayAdd: Boolean = false,
    /** *Manage roles & permissions*: the Role picker. */
    val maySetRoles: Boolean = false,
    /** *Manage employees*: the position picker and the password box. */
    val maySetProfile: Boolean = maySetRoles,
    val errors: Map<EmployeeField, String> = emptyMap(),
    val filter: EmployeeFilter = EmployeeFilter(),
    val positions: List<String> = emptyList(),
    /** The role catalogue, in its own order (V21), and the role a new account starts in. */
    val roles: List<String> = emptyList(),
    val defaultRole: String = "",
) {
    val isEdit: Boolean get() = edit.isNotBlank()
    val isOpen: Boolean get() = open || errors.isNotEmpty()
    fun error(field: EmployeeField): String? = errors[field]
}

/** The sheet as the address bar states it — what a GET onto the page is reading. */
fun employeeFilterOf(query: Parameters): EmployeeFilter =
    EmployeeFilter.of(TEAM_VIEW_KEYS.associateWith { query[it] })

/**
 * The same sheet as the popup's or a row's form states it, under `board_*`.
 *
 * [boardCarriage] writes them prefixed and this reads them back the same way, so the two cannot drift
 * and the plain [EmployeeFilter.of] answers both the address and the form.
 */
fun employeeFilterOfForm(form: Parameters): EmployeeFilter =
    EmployeeFilter.of(TEAM_VIEW_KEYS.associateWith { form["board_$it"] })

/** The popup as one of its forms states it: what was typed, and the account it is about. */
fun employeeDraftOf(form: Parameters): EmployeeDraft =
    EmployeeDraft.of(EmployeeDraft.keys().associateWith { form[it] })

private val TEAM_VIEW_KEYS = listOf("q", "position", "role", "sort")

/**
 * The popup as a query asks for it: `?new=1` to write one, `?edit=<employee number>` to change one.
 *
 * A number the sheet does not print, or an edit this reader may not make, answers with the ordinary page
 * and no popup — the same answer a hand-typed id gets on the lesson sheet. A roster row with no account
 * behind it opens as a *create* filled with that person: [EmployeeForm.edit] stays blank so the save
 * writes a new account rather than looking for one that is not there.
 */
fun employeeFormOf(
    query: Parameters,
    row: EmployeeRow?,
    account: User?,
    positionName: String,
    mayAdd: Boolean,
    mayEdit: Boolean,
    maySetRoles: Boolean = mayEdit,
): EmployeeForm {
    val roles = EmployeeService.roles()
    val fallback = EmployeeService.defaultRoleName()
    val wanted = query["edit"].orEmpty().trim().uppercase()
    if (wanted.isNotEmpty()) {
        if (row == null || !(mayEdit || maySetRoles)) return EmployeeForm()
        return EmployeeForm(
            edit = if (row.hasAccount) wanted else "",
            employeeNumber = wanted,
            name = row.name,
            email = account?.email.orEmpty(),
            position = positionName,
            role = account?.let { Authorization.roleOf(it).name }.orEmpty(),
            open = true,
            mayAdd = mayAdd,
            maySetRoles = maySetRoles,
            maySetProfile = mayEdit,
            filter = employeeFilterOf(query),
            positions = EmployeeService.positionNames(),
            roles = roles,
            defaultRole = fallback,
        )
    }
    return EmployeeForm(
        open = query["new"] == "1" && mayAdd,
        mayAdd = mayAdd,
        maySetRoles = maySetRoles,
        maySetProfile = mayEdit,
        role = fallback,
        filter = employeeFilterOf(query),
        positions = EmployeeService.positionNames(),
        roles = roles,
        defaultRole = fallback,
    )
}

/** The popup as a refusal states it: the words that were typed, and the reason each field was not taken. */
fun employeeFormOf(
    result: EmployeeResult,
    mayAdd: Boolean,
    maySetRoles: Boolean,
    filter: EmployeeFilter,
    maySetProfile: Boolean = maySetRoles,
): EmployeeForm {
    val draft = result.values
    return EmployeeForm(
        edit = draft.edit,
        employeeNumber = draft.employeeNumber,
        name = draft.name,
        email = draft.email,
        position = draft.position,
        role = draft.role,
        open = true,
        mayAdd = mayAdd,
        maySetRoles = maySetRoles,
        maySetProfile = maySetProfile,
        errors = result.errors,
        filter = filter,
        positions = EmployeeService.positionNames(),
        roles = EmployeeService.roles(),
        defaultRole = EmployeeService.defaultRoleName(),
    )
}

/** The eight columns of the sheet — the seven the picture reads, and the two buttons on the end. */
private val T_COLUMNS = listOf(
    "t-name", "t-emp", "t-position", "t-department", "t-role", "t-email", "t-tasks", "t-actions",
)
