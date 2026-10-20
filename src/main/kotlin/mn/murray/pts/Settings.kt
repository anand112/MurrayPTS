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

const val MIN_PASSWORD_LENGTH = 4

/** Outcome of a password change. Passed back to the page after the redirect to drive the flash banner. */
enum class PasswordStatus(val isError: Boolean, val text: String) {
    CHANGED(false, "Password updated successfully."),
    WRONG_CURRENT(true, "Current password is incorrect."),
    MISMATCH(true, "New password and confirmation do not match."),
    TOO_SHORT(true, "Password must be at least $MIN_PASSWORD_LENGTH characters."),
    EMPTY(true, "Please fill in all password fields."),
    USER_NOT_FOUND(true, "No user found with that employee number."),
    USER_DELETED(false, "User deleted."),
    USER_ADDED(false, "User added."),
    USER_UPDATED(false, "Account updated."),
    CANNOT_DELETE_SELF(true, "You cannot delete your own account."),
    CANNOT_DELETE_SUPER(true, "The Super Admin account cannot be deleted."),
    CANNOT_DELETE_ADMIN(true, "Managers cannot delete Admin accounts."),
    USER_EXISTS(true, "User name is taken."),
    MAIL_SENT(false, "Test email sent — check the inbox the message was addressed to."),
    MAIL_LOGGED(true, "Nothing left this machine: mail is in log-only mode."),
    MAIL_FAILED(true, "The mail server refused the test message."),
    MAIL_NO_ADDRESS(true, "No email on file for this account — set one on the Team sheet first."),
    MAIL_ENABLED(false, "Outbound mail is now enabled — PTS can send."),
    MAIL_DISABLED(true, "Outbound mail is now switched off — PTS will not send until it is enabled again."),
    MAIL_REFUSED(true, "Only a Super Admin can turn outbound mail on or off."),
    LANE_ADDED(false, "Default lane added."),
    LANE_REMOVED(false, "Default lane removed."),
    LANE_REFUSED(true, "That lane cannot be added.");

    fun message(detail: String? = null): String =
        if (detail.isNullOrBlank()) text else "$text ($detail)"

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
 * The Settings page (Stage 46): the company's Team sheet first, then the reader's own account, then
 * the password form.
 *
 * The picture this page is built to is a roster with a search box over it — one row per person, seven
 * columns, and the two things anybody ever wanted from the old workload table (what this account may
 * do, and how much work is on it) printed as columns rather than as a count you have to click open.
 * So the sheet replaced that table here, and the two sections underneath it stayed exactly where they
 * were: this page is still the one a `mustChangePassword` account is sent to, and the password form is
 * the only thing on it that clears that flag.
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
    note: PasswordStatus? = null,
    passwordStatus: PasswordStatus? = null,
    mailStatus: PasswordStatus? = null,
    mailDetail: String? = null,
    bucketNote: PasswordStatus? = null,
    bucketDetail: String? = null,
    bucketDefaultsType: String = PROJECT_TYPES.first(),
) {
    // Stage 51: the Team sheet is the Settings *function* — the roster of accounts with its add/edit controls,
    // search and toolbar. It belongs to management alone, so a Viewer who reaches this page (the change-password
    // form below is every account's, and a first-login forced change lands here) sees none of the sheet. The write
    // routes refuse a non-manager again through mayAdd/mayEdit; this only stops printing it.
    if (canManage) {
        h3("set-section") { +"Team" }
        flashBanner(note)
        if (form.mayAdd) addEmployeeCard(filter)
        teamToolbar(filter, sheet)
        div("board-results") {
            id = "team-board"
            attributes["data-live-region"] = "true"
            if (sheet.isEmpty) teamEmpty(filter) else teamTable(sheet.rows, filter)
        }
    }

    accountCard(session)

    h3("set-section") { +"Change Password" }
    div("card-panel") {
        if (session.mustChangePassword) {
            // requireSession() routes these accounts straight here and blocks every other page;
            // the flag is cleared by UserService.changeOwnPassword when this form succeeds.
            div("flash err") {
                +"Set a new password to unlock the rest of PTS — the current one is the default the system created for your account."
            }
        }
        flashBanner(passwordStatus)
        passwordForm()
    }

    // The three panels below the password form are the settings *of the app*, not of the account: which
    // categories projects are filed under, whether outbound mail can leave this machine, and which lanes a
    // freshly created project starts with beyond the six it always gets. All three are management's — a
    // Viewer's page stops at the password form — and each is a straight print of state the code already
    // holds: the category list comes from PROJECT_TYPES, the mail status from DueDateAlertService.currentMode,
    // and the per-type lanes from the V12 table ProjectService.create seeds a new board from.
    if (canManage) {
        projectTypesPanel()
        emailPanel(session.employeeNumber, canAdmin, mailStatus, mailDetail)
        bucketDefaultsPanel(bucketDefaultsType, bucketNote, bucketDetail)
    }

    if (canManage) {
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
                "password the number is the password and they have to change it on first sign-in.")
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
private fun DIV.teamToolbar(filter: EmployeeFilter, sheet: EmployeeSheet) {
    form(action = "/app/settings", method = FormMethod.get, classes = "board-toolbar") {
        id = "tsFilter"
        attributes["data-live-extra"] = "#team-counts"
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
                ACCESS_LEVELS.forEach { tsOption(it, it, filter.role) }
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

/** The app's own three-way colour for an access level, the same one the Access control page prints. */
private fun roleChip(role: String): String = when (role) {
    "Admin" -> "atrisk"
    "Manager" -> "planning"
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
            span("set-key") { +"Access level" }
            span { +user.access }
        }
        p("task-meta") {
            +"Use the 🎨 button in the sidebar to cycle the accent colour and 🌙 for dark mode. Preferences are stored in your browser."
        }
    }
}

/**
 * The password form, unchanged from the page this replaced.
 *
 * It stays a plain POST with a redirect and a flash line rather than a popup write, because it is the
 * one form on the page a `mustChangePassword` account has to be able to use with nothing else working —
 * no live region, no companion swap, no modal script between the reader and the app unlocking itself.
 */
private fun DIV.passwordForm() {
    form(action = "/app/settings/password", method = FormMethod.post) {
        div("field") {
            label { +"Current password" }
            input(type = InputType.password, name = "currentPassword") {
                required = true
                attributes["autocomplete"] = "current-password"
            }
        }
        div("field") {
            label { +"New password" }
            input(type = InputType.password, name = "newPassword") {
                required = true
                attributes["minlength"] = MIN_PASSWORD_LENGTH.toString()
                attributes["autocomplete"] = "new-password"
            }
        }
        div("field") {
            label { +"Confirm new password" }
            input(type = InputType.password, name = "confirmPassword") {
                required = true
                attributes["minlength"] = MIN_PASSWORD_LENGTH.toString()
                attributes["autocomplete"] = "new-password"
            }
        }
        button(type = ButtonType.submit, classes = "submit") { +"Change Password" }
    }
}

/**
 * The nine categories, read-only.
 *
 * [PROJECT_TYPES] is the list the create-project popup's picker is built from and [TYPE_PREFIX] the letter
 * each category's numbers lead with (`S-01` for Site projects), so this panel is a print of the app's own
 * catalog rather than a second list that could drift from it. It is deliberately **not** editable: a
 * category is not a free label — it keys project codes through [codeMatchesType], the chip colours through
 * [typeChipClass], the Forecasts sheet's panels and this page's per-type lane defaults — and renaming one
 * on a whim would leave every project already coded under the old letter misfiled. Management reads the
 * catalog here; changing it is a code decision, made where the letter, the colour and the codes agree.
 */
private fun DIV.projectTypesPanel() {
    h3("set-section") { +"Project types" }
    div("card-panel") {
        p("panel-caption") {
            +("The categories a project is filed under. Each one leads its project numbers with its letter " +
                "— the S in S-01 — and these nine are fixed: adding or renaming one is a change to how " +
                "codes, forecasts and lanes are keyed, not a setting to type in.")
        }
        PROJECT_TYPES.forEach { type ->
            div("set-row type-row") {
                span("code type-letter") { +typeLetter(type) }
                span("chip ${typeChipClass(type)}") { +type }
                span("type-count") { +projectCountByType(type) }
            }
        }
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
 * The two status lines print for management (a Manager reads the delivery mode and their own address), but the
 * test button prints for an **admin** only — the same right [mn.murray.pts.Application]'s `/app/settings/email-test`
 * re-checks, so the panel never offers a press the route would silently bounce.
 */
private fun DIV.emailPanel(empNo: String, canAdmin: Boolean, mailStatus: PasswordStatus? = null, mailDetail: String? = null) {
    val mode = DueDateAlertService.currentMode()
    val address = DueDateAlertService.emailFor(empNo)
    val sending = MailSwitch.enabled()
    h3("set-section") { +"Email notifications" }
    div("card-panel") {
        id = "emailBoard"
        p("panel-caption") {
            +("Due-date alerts and assignment notices go out over the SMTP server configured in " +
                "data/.env (PTS_SMTP_*). Change those keys and restart — a password does not belong in a " +
                "web form. Independently, a Super Admin must turn outbound mail **on** below before the " +
                "system is allowed to send anything at all; while it is off every message stays in the log.")
        }
        flashBanner(mailStatus, mailDetail)
        div("set-row") {
            span("set-key") { +"Delivery mode" }
            span("email-mode") { +mode }
        }
        div("set-row") {
            span("set-key") { +"Outbound mail" }
            span(if (sending) "email-switch on" else "email-switch off") {
                +(if (sending) "Enabled — PTS can send" else "Switched off — PTS will not send")
            }
        }
        div("set-row") {
            span("set-key") { +"Your address" }
            span { +address.ifBlank { "(no email on file for $empNo — set one on the Team sheet)" } }
        }
        // The master switch is the one control that decides whether the whole installation may email anyone,
        // so it prints as a form for a Super Admin only — the same right `/app/settings/mail-switch` re-checks.
        // The button offers the *opposite* of the current state, and turning it on warns what it commits to.
        if (canAdmin) {
            form(action = "/app/settings/mail-switch", method = FormMethod.post, classes = "opener-form") {
                attributes["data-live-target"] = "#emailBoard"
                input(type = InputType.hidden, name = "on") { value = if (sending) "false" else "true" }
                button(type = ButtonType.submit, classes = if (sending) "ghost-btn btn-sm" else "submit") {
                    if (sending) {
                        attributes["data-confirm"] = "Stop PTS from sending any email? Alerts and notices will go back to the log only."
                        +"Switch sending off"
                    } else {
                        attributes["data-confirm"] = "Allow PTS to send email to people? Due-date alerts and notices will start going out to the addresses on the Team sheet."
                        +"Enable sending"
                    }
                }
            }
        }
        // The test sends to **the reader's own address** and cannot be pointed at a colleague: one message, no ledger
        // row, so it cannot spend the day's alert budget and can be pressed again straight after fixing a credential.
        // The answer comes back as this card's own flash line, in place, with the server's reason beside it when the
        // send was refused or logged instead of delivered.
        //
        // It prints only when there is an address to send to *and* the reader is an admin — the test is the one
        // control on the page that opens a connection out of the machine, so it is gated exactly as the
        // `/app/alerts/test` endpoint it mirrors is. When the master switch is still off this button is honest about
        // it: the press would only come back "logged", so the line beside it already says what to do about that.
        if (address.isNotBlank() && canAdmin) {
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
 * The lanes a freshly created project of [type] gets **on top of** the six every board carries.
 *
 * The six ([DEFAULT_PROJECT_BUCKETS]) print as the permanent floor: a card is filed in one of them or it is
 * not on a project, so no setting can take one away and this panel does not offer one. What is edited here
 * are the *extra* defaults per category — the lane a Fabrication job always has and a Labor supply contract
 * never does — stored in the V12 table and appended to a new project's board by ProjectService.create, right
 * after [mn.murray.pts.repository.ProjectBucketRepository.seedDefaults]. Existing boards keep whatever lanes
 * their manager gave them: changing a default changes the next project, not the last twenty.
 *
 * The category picker is a plain GET onto this page carrying `?lanes=`, so the panel answers for the category
 * chosen rather than always the first one, and the two write forms carry the same key hidden so their answers
 * land on the category being edited.
 */
private fun DIV.bucketDefaultsPanel(type: String, note: PasswordStatus? = null, detail: String? = null) {
    val extras = BucketDefaultsService.extrasOf(type)
    h3("set-section") { +"Bucket defaults" }
    div("card-panel") {
        id = "bucketDefaults"
        p("panel-caption") {
            +(
                "Every project board starts with the six standard sub-categories below, and a card filed " +
                    "under one of the six is what the general boards read. The names here are the extra lanes " +
                    "each category adds when a project is created — existing projects keep their own lanes."
                )
        }
        div("set-row") {
            span("set-key") { +"Always on every board" }
            span("lane-floor") {
                DEFAULT_PROJECT_BUCKETS.forEach { span("chip lane-chip") { +it } }
            }
        }
        form(action = "/app/settings", method = FormMethod.get, classes = "board-toolbar") {
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
            attributes["data-live-region"] = "true"
            if (extras.isEmpty()) {
                p("board-empty-line") { +"$type adds no lanes of its own — its projects start with just the six." }
            } else {
                extras.forEach { entry ->
                    div("set-row") {
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
        form(action = "/app/settings/buckets/add", method = FormMethod.post) {
            attributes["data-live-target"] = "#bucketDefaults"
            div("form-grid") {
                div("field") {
                    label { +"New extra lane for $type" }
                    input(type = InputType.text, name = "name") {
                        attributes["placeholder"] = "Like: Commissioning"
                        autoComplete = "off"
                        attributes["maxlength"] = PROJECT_BUCKET_MAX.toString()
                    }
                }
            }
            input(type = InputType.hidden, name = "lanes") { value = type }
            div { flashBanner(note, detail) }
            button(type = ButtonType.submit, classes = "submit btn-sm") { +"+ Add default lane" }
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
                            "blank and the number is it, until they change it."
                    } else {
                        "They sign in with their employee number as the password and change it on first " +
                            "sign-in, as a Viewer."
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
                            label { +"Role" }
                            select {
                                name = "role"
                                id = "employeeRole"
                                ACCESS_LEVELS.forEach { tsOption(it, it, form.role.ifEmpty { "Viewer" }) }
                            }
                            form.error(EmployeeField.ROLE)?.let { p("field-err") { +it } }
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
    val maySetRoles: Boolean = false,
    val errors: Map<EmployeeField, String> = emptyMap(),
    val filter: EmployeeFilter = EmployeeFilter(),
    val positions: List<String> = emptyList(),
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
): EmployeeForm {
    val wanted = query["edit"].orEmpty().trim().uppercase()
    if (wanted.isNotEmpty()) {
        if (row == null || !mayEdit) return EmployeeForm()
        return EmployeeForm(
            edit = if (row.hasAccount) wanted else "",
            employeeNumber = wanted,
            name = row.name,
            email = account?.email.orEmpty(),
            position = positionName,
            role = account?.access.orEmpty(),
            open = true,
            mayAdd = mayAdd,
            maySetRoles = mayEdit,
            filter = employeeFilterOf(query),
            positions = EmployeeService.positionNames(),
        )
    }
    return EmployeeForm(
        open = query["new"] == "1" && mayAdd,
        mayAdd = mayAdd,
        maySetRoles = mayEdit,
        role = "Viewer",
        filter = employeeFilterOf(query),
        positions = EmployeeService.positionNames(),
    )
}

/** The popup as a refusal states it: the words that were typed, and the reason each field was not taken. */
fun employeeFormOf(result: EmployeeResult, mayAdd: Boolean, maySetRoles: Boolean, filter: EmployeeFilter): EmployeeForm {
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
        errors = result.errors,
        filter = filter,
        positions = EmployeeService.positionNames(),
    )
}

/** The eight columns of the sheet — the seven the picture reads, and the two buttons on the end. */
private val T_COLUMNS = listOf(
    "t-name", "t-emp", "t-position", "t-department", "t-role", "t-email", "t-tasks", "t-actions",
)
