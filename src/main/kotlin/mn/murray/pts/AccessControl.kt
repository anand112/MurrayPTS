package mn.murray.pts
import mn.murray.pts.domain.*
import mn.murray.pts.service.EmployeeService
import mn.murray.pts.service.RoleService

import io.ktor.http.Parameters
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.TR
import kotlinx.html.button
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

/**
 * One row of the access table: full team roster first, then accounts without a roster entry.
 *
 * [department] and [onRoster] travel with the row because a department is a property of the *roster*, not
 * of a login (Stage 118): a person with no account still belongs to one, and an account with no roster row
 * has nothing to file.
 */
private data class UserRow(
    val employeeNumber: String,
    val name: String,
    val teamPosition: String,
    val department: String,
    val onRoster: Boolean,
    val account: AccessRow?,
)

private fun userRows(): List<UserRow> {
    val teamEmps = DemoData.team.map { it.employeeNumber }.toSet()
    return DemoData.team.map { m ->
        UserRow(
            employeeNumber = m.employeeNumber,
            name = m.name,
            teamPosition = m.position,
            department = m.department,
            onRoster = true,
            account = DemoData.access.firstOrNull { it.employeeNumber == m.employeeNumber },
        )
    } + DemoData.access.filter { it.employeeNumber !in teamEmps }.map { a ->
        UserRow(
            employeeNumber = a.employeeNumber,
            name = a.name,
            teamPosition = a.position,
            department = "",
            onRoster = false,
            account = a,
        )
    }
}

/** Results of the Add-user form shown on the panel itself rather than the page-top banner. */
private val ADD_FORM_STATUSES = setOf(PasswordStatus.USER_ADDED, PasswordStatus.USER_EXISTS, PasswordStatus.TOO_SHORT)

/**
 * The roles table as the address bar states it (Stage 110).
 *
 * Two openers, each a GET form onto Access control itself — the picture's **+ New role** is `?newrole=1`
 * and a row's **Edit** is `?roleedit=<id>` — so the popup is server-drawn state and a reader with no
 * JavaScript gets the same form a fresh load has. A reader without *Manage roles & permissions* is handed
 * the ordinary page: the ids are only honoured for the account that could save what they open.
 *
 * [nameError] is a refusal's sentence, which arrives with the page rather than a redirect — `app.js` swaps
 * `#rolesPanel` and `#roleModal` out of that response, and a redirect's target would arrive with the form
 * shut and the reason nowhere.
 */
data class RoleForm(
    val editId: Int = 0,
    val name: String = "",
    val checked: Set<Permission> = emptySet(),
    val open: Boolean = false,
    val nameError: String? = null,
) {
    val isEdit: Boolean get() = editId > 0
    val isOpen: Boolean get() = open || nameError != null
}

/** The popup as the address asks for it; a closed form for a reader who may not write roles. */
fun roleFormOf(query: Parameters, canRoles: Boolean): RoleForm {
    if (!canRoles) return RoleForm()
    val editId = query["roleedit"]?.trim()?.toIntOrNull() ?: 0
    if (editId > 0) {
        val role = RoleService.byId(editId) ?: return RoleForm()
        return RoleForm(editId = role.id, name = role.name, checked = role.permissions, open = true)
    }
    if (query["newrole"] == "1") return RoleForm(open = true)
    return RoleForm()
}

/** The popup as its own form states it, for the page a refusal answers with. */
fun roleFormOfForm(form: Parameters, editing: AccessRole?): RoleForm = RoleForm(
    editId = editing?.id ?: 0,
    name = form["name"].orEmpty().trim(),
    checked = Permission.ofAll(form.getAll("perm").orEmpty()),
    open = true,
)

/** One editable row of the Manage-positions table; reused for in-place (fetch) add/update responses. */
internal fun TR.positionRow(pos: Position, canEdit: Boolean) {
    td {
        if (canEdit) {
            form(action = "/app/positions/update", method = FormMethod.post, classes = "cell-edit") {
                input(type = InputType.hidden, name = "id") { value = pos.id.toString() }
                input(type = InputType.text, name = "name") {
                    value = pos.name
                    attributes["data-autosubmit"] = ""
                    attributes["title"] = "Edit position name and press Enter or click away to save"
                }
            }
        } else {
            +pos.name
        }
    }
    td {
        if (canEdit) {
            form(action = "/app/positions/update", method = FormMethod.post, classes = "cell-edit") {
                input(type = InputType.hidden, name = "id") { value = pos.id.toString() }
                input(type = InputType.text, name = "description") {
                    value = pos.description
                    attributes["data-autosubmit"] = ""
                    attributes["title"] = "Edit description and press Enter or click away to save"
                }
            }
        } else {
            +pos.description
        }
    }
    if (canEdit) {
        td {
            form(action = "/app/positions/delete", method = FormMethod.post, classes = "cell-edit") {
                input(type = InputType.hidden, name = "id") { value = pos.id.toString() }
                button(type = ButtonType.submit, classes = "delete-btn") {
                    attributes["data-confirm"] = "Are you sure you want to delete this position?"
                    +"Delete"
                }
            }
        }
    }
}

/**
 * The Access control page (Stage 110).
 *
 * The picture's **Roles & permissions** table comes first: one row per role, its chips, and how many
 * accounts hold it. It is drawn for [canRoles] (the **Edit** buttons and **+ New role** follow that flag) and read
 * by everybody who may be on this page at all — a reader holding only *Manage employees* still needs to
 * see which role a person is filed under, even though the select beside it is not theirs.
 *
 * The People sheet beneath it keeps its Stage 51 shape. Its **Access** column became **Role**, because the
 * role is what decides access now; the position beside it is a job title and nothing more. [canPeople] is
 * *Manage employees* — the position, delete and add-user controls — while the role written in a row is
 * **Manage roles & permissions** and posts to its own route. The **Password** column, its per-row setter and
 * the add-user password box follow [canSetPassword] instead (Stage 123): `EMP0000` alone.
 *
 * **Department** (Stage 118) is the roster's own column, printed here between Position and Role. [canDepartments]
 * is the Owner/Administrator pair (a role holding every chip): they get a picker per row that posts to its own
 * route and swaps only that cell; everybody else reads the word. A person without a login still has a
 * department to file, so the picker does not follow [canPeople]; an account with no roster row has nothing to
 * write and keeps the dash.
 */
fun DIV.accessContent(
    canPeople: Boolean,
    canRoles: Boolean,
    canDepartments: Boolean,
    currentEmployeeNumber: String,
    roleForm: RoleForm = RoleForm(),
    passwordStatus: PasswordStatus? = null,
    statusDetail: String? = null,
    /**
     * The super admin alone (Stage 123): the Password column, its per-row setter, and the Add-user password
     * box. The Owner and Administrator roles hold every chip but are not that account, so holding *Manage
     * employees* no longer reaches another person's password.
     */
    canSetPassword: Boolean = false,
) {
    val roles = RoleService.catalogue()
    val counts = RoleService.counts()
    flashBanner(passwordStatus?.takeIf { it !in ADD_FORM_STATUSES }, statusDetail)
    rolesPanel(roles, counts, canRoles)

    div("card-panel") {
        h3("set-section") { +"People" }
        table("grid") {
            thead {
                tr {
                    th { +"Employee" }
                    th { +"Name" }
                    th { +"Position" }
                    th { +"Department" }
                    th { +"Role" }
                    if (canSetPassword) th { +"Password" }
                    if (canPeople) th { +"Delete" }
                }
            }
            tbody {
                userRows().forEach { row ->
                    val account = row.account
                    tr {
                        td { span("code") { +row.employeeNumber } }
                        td { span("pname") { +row.name } }
                        td {
                            id = "position-cell-${row.employeeNumber}"
                            if (canPeople && account != null) {
                                form(action = "/app/access-control/update", method = FormMethod.post, classes = "cell-edit") {
                                    attributes["data-live-target"] = "#position-cell-${row.employeeNumber}"
                                    input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                    select {
                                        name = "position"
                                        id = "position-pick-${row.employeeNumber}"
                                        attributes["data-autosubmit"] = ""
                                        DemoData.positions.forEach { pos ->
                                            option {
                                                selected = pos.name == account.position
                                                +pos.name
                                            }
                                        }
                                    }
                                }
                            } else {
                                +(account?.position ?: row.teamPosition)
                            }
                        }
                        td {
                            id = "department-cell-${row.employeeNumber}"
                            if (canDepartments && row.onRoster) {
                                // Only this cell moves on a save, so the rest of the sheet — and the page under
                                // the reader — stays exactly where it was.
                                form(
                                    action = "/app/access-control/department",
                                    method = FormMethod.post,
                                    classes = "cell-edit",
                                ) {
                                    attributes["data-live-target"] = "#department-cell-${row.employeeNumber}"
                                    input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                    select {
                                        name = "department"
                                        id = "department-pick-${row.employeeNumber}"
                                        attributes["data-autosubmit"] = ""
                                        // An old free-text word is shown as itself, unselectable: choosing one of the
                                        // nine replaces it, and pressing nothing else on the row cannot erase it.
                                        if (row.department !in EmployeeService.DEPARTMENTS) {
                                            option {
                                                value = ""
                                                selected = true
                                                attributes["disabled"] = ""
                                                +if (row.department.isBlank()) "— No department —" else "— ${row.department} —"
                                            }
                                        }
                                        EmployeeService.DEPARTMENTS.forEach { department ->
                                            option {
                                                selected = department == row.department
                                                +department
                                            }
                                        }
                                    }
                                }
                            } else if (row.department.isBlank()) {
                                +"—"
                            } else {
                                span("chip dept-chip") { +row.department }
                            }
                        }
                        td {
                            id = "role-cell-${row.employeeNumber}"
                            if (canRoles && account != null) {
                                // The two blocks a re-filed account changes: its own cell, and the EMPLOYEES
                                // column of the table above — one reader leaves a count, another joins it.
                                form(action = "/app/access-control/role", method = FormMethod.post, classes = "cell-edit") {
                                    attributes["data-live-target"] = "#role-cell-${row.employeeNumber}"
                                    attributes["data-live-extra"] = "#rolesPanel"
                                    input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                    select {
                                        name = "role"
                                        id = "role-pick-${row.employeeNumber}"
                                        attributes["data-autosubmit"] = ""
                                        roles.forEach { role ->
                                            option {
                                                selected = role.name == account.role
                                                +role.name
                                            }
                                        }
                                    }
                                }
                            } else if (account != null) {
                                span("chip ${roleChip(account.role)}") { +account.role }
                            } else {
                                span("assignee none") { +"No account" }
                            }
                        }
                        if (canSetPassword) {
                            td {
                                if (account == null) {
                                    +"—"
                                } else {
                                    form(action = "/app/access-control/password", method = FormMethod.post, classes = "cell-edit pw-edit") {
                                        input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                        input(type = InputType.password, name = "password") {
                                            attributes["placeholder"] = if (account.password.isEmpty()) "set password" else "••••••"
                                            attributes["title"] = "Type a new password and press Enter to save"
                                            required = true
                                        }
                                        button(type = ButtonType.submit, classes = "pin-btn") { +"Set" }
                                    }
                                }
                            }
                        }
                        if (canPeople) {
                            td {
                                if (row.employeeNumber == "EMP0000" || row.employeeNumber == currentEmployeeNumber) {
                                    +"—"
                                } else {
                                    form(action = "/app/access-control/delete", method = FormMethod.post, classes = "cell-edit") {
                                        input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                        button(type = ButtonType.submit, classes = "delete-btn") {
                                            attributes["data-confirm"] = "Delete ${row.name} (${row.employeeNumber})? They will also be removed from the team. This cannot be undone."
                                            +"Delete"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!canPeople) p("panel-caption") {
            +("Manage employees is what adds and removes accounts. The role beside each name is set by whoever " +
                "manages roles & permissions, the department by an Owner or Administrator, and a password only " +
                "by the super admin.")
        } else if (!canSetPassword) p("panel-caption") {
            +("A new account signs in with its employee number as the password, and only the super admin can " +
                "set another one. The role beside each name is set by whoever manages roles & permissions, and " +
                "the department by an Owner or Administrator.")
        }
    }
    if (canPeople) {
        div("card-panel") {
            h3 { +"Add user" }
            if (passwordStatus == PasswordStatus.USER_ADDED || passwordStatus == PasswordStatus.TOO_SHORT) {
                flashBanner(passwordStatus, statusDetail)
            }
            form(action = "/app/access-control", method = FormMethod.post) {
                div("form-grid") {
                    div("field") {
                        label { +"Employee number" }
                        input(type = InputType.text, name = "employeeNumber") {
                            placeholder = "EMP0023"
                            required = true
                        }
                        if (passwordStatus == PasswordStatus.USER_EXISTS) {
                            p("field-err") { +"User name is taken." }
                        }
                    }
                    div("field") {
                        label { +"Full name" }
                        input(type = InputType.text, name = "name") {
                            placeholder = "First Last"
                            required = true
                        }
                    }
                    div("field") {
                        label { +"Email" }
                        input(type = InputType.email, name = "email") {
                            placeholder = "Auto: firstname.surname@murrays.mn"
                        }
                    }
                    div("field") {
                        label { +"Position" }
                        select {
                            name = "position"
                            option {
                                value = ""
                                selected = true
                                +"Automatic (team roster)"
                            }
                            DemoData.positions.forEach { pos ->
                                option { +pos.name }
                            }
                        }
                    }
                    if (canRoles) {
                        div("field") {
                            label { +"Role" }
                            select {
                                name = "role"
                                roles.forEach { role ->
                                    option {
                                        selected = role.isDefault
                                        +role.name
                                    }
                                }
                            }
                        }
                    }
                    if (canSetPassword) {
                        div("field") {
                            label { +"Password" }
                            input(type = InputType.password, name = "password") {
                                placeholder = "Defaults to employee number"
                                attributes["minlength"] = MIN_PASSWORD_LENGTH.toString()
                                attributes["autocomplete"] = "new-password"
                            }
                        }
                    }
                }
                p("panel-caption") {
                    +(
                        "Position defaults to the member's team-roster position and the role to the catalogue's " +
                            "default. " +
                            (if (canSetPassword) "If no password is given it defaults to the employee number. "
                            else "The account starts with its employee number as the password — only the super admin sets another. ") +
                            "A blank email is generated as firstname.surname@murrays.mn."
                        )
                }
                button(type = ButtonType.submit, classes = "submit") { +"Add user" }
            }
        }
    }
    if (canPeople) {
        div("card-panel") {
            h3 { +"Manage positions" }
            table("grid") {
                thead {
                    tr {
                        th { +"Position" }
                        th { +"Description" }
                        th { +"Actions" }
                    }
                }
                tbody {
                    id = "positions-tbody"
                    DemoData.positions.forEach { pos ->
                        tr {
                            positionRow(pos, canEdit = true)
                        }
                    }
                }
            }
            p("panel-caption") {
                +"A position is the job title printed beside a name. What an account may do comes from its role above."
            }
            h3 { +"Add new position" }
            form(action = "/app/positions", method = FormMethod.post) {
                div("form-grid") {
                    div("field") {
                        label { +"Position name" }
                        input(type = InputType.text, name = "name") {
                            placeholder = "e.g. Senior Engineer"
                            required = true
                        }
                    }
                    div("field") {
                        label { +"Description" }
                        input(type = InputType.text, name = "description") {
                            placeholder = "Brief description of responsibilities"
                        }
                    }
                }
                button(type = ButtonType.submit, classes = "submit") { +"Add position" }
            }
        }
        // Positions are managed in place: the external script intercepts add/update/delete
        // submits and swaps the table row via fetch, avoiding a full page reload.
    }
    if (canRoles) roleModal(roleForm)
    script(src = "/static/js/access-control.js") {}
}

/**
 * The picture's **Roles & permissions** table, the whole of it inside `#rolesPanel` so a save can swap it
 * in place. **+ New role** is a GET opener onto this page (`?newrole=1`) and each row's **Edit** is
 * `?roleedit=<id>`; `app.js` answers either by fetching the URL and replacing `#roleModal`, which is the same
 * arrangement the Settings team sheet's popup uses.
 */
private fun DIV.rolesPanel(
    roles: List<AccessRole>,
    counts: Map<Int, Int>,
    canRoles: Boolean,
) {
    div("card-panel") {
        id = "rolesPanel"
        div("panel-head") {
            h3("set-section") { +"Roles & permissions" }
            if (canRoles) {
                form(action = "/app/access-control", method = FormMethod.get, classes = "opener-form") {
                    attributes["data-live-target"] = "#roleModal"
                    input(type = InputType.hidden, name = "newrole") { value = "1" }
                    button(type = ButtonType.submit, classes = "submit btn-sm") { +"+ New role" }
                }
            }
        }
        table("grid roles-grid") {
            thead {
                tr {
                    th { +"Role" }
                    th { +"Permissions" }
                    th(classes = "role-count-head") { +"Employees" }
                    if (canRoles) th { }
                }
            }
            tbody {
                // Every row keeps its **Edit** even while the popup is up: a save that was refused hands this
                // block back as it is drawn for an open form, and a panel whose openers had gone would leave the
                // reader with one way out of that popup and no way back into it. Opening a second role swaps the
                // popup whole, so the hidden id and the ticks move with it.
                roles.forEach { role ->
                    tr { roleRow(role, counts[role.id] ?: 0, canRoles) }
                }
            }
        }
        p("panel-caption") {
            +(
                "A role is a named set of the permissions above. Every account signs in under one role, and " +
                    "that role is the whole of what it may do — a position is only the job title beside its name."
                )
        }
    }
}

private fun TR.roleRow(role: AccessRole, count: Int, canEdit: Boolean) {
    td {
        span("role-name") { +role.name }
        if (role.isDefault) span("role-default") { +" (default)" }
    }
    td {
        if (role.permissions.isEmpty()) {
            span("task-meta") { +"No permissions" }
        } else {
            div("perm-chips") {
                role.permissions.sortedBy { it.ordinal }.forEach { permission ->
                    span("chip perm") { +permission.label }
                }
            }
        }
    }
    td("role-count") { +count.toString() }
    if (canEdit) {
        td {
            form(action = "/app/access-control", method = FormMethod.get, classes = "opener-form") {
                attributes["data-live-target"] = "#roleModal"
                input(type = InputType.hidden, name = "roleedit") { value = role.id.toString() }
                button(type = ButtonType.submit, classes = "ghost-btn btn-sm") {
                    attributes["title"] = "Change ${role.name}"
                    attributes["aria-label"] = "Change ${role.name}"
                    +"Edit"
                }
            }
        }
    }
}

/**
 * The role popup: its name and the thirteen chips, as tick boxes in the picture's order.
 *
 * A refusal comes back with the form up and its sentence under the name box, because that is the only
 * field a role write can be refused on — the chips are a set and any subset of them is a valid role.
 */
private fun DIV.roleModal(form: RoleForm) {
    div("modal") {
        id = "roleModal"
        if (form.isOpen) attributes["style"] = "display:block"
        div("modal-content modal-wide") {
            span("close") {
                attributes["data-role-modal-close"] = "true"
                +"×"
            }
            h3 { +if (form.isEdit) "Change role" else "New role" }
            p("modal-scope") {
                +(
                    if (form.isEdit) {
                        "Everybody holding this role picks up the new set at once — the change is not copied to " +
                            "their accounts, it is read from the role when they sign in."
                    } else {
                        "A new role starts with no permissions. Tick what it may do; accounts can be filed " +
                            "under it on the People sheet once it is saved."
                    }
                    )
            }
            form(action = "/app/roles/save", method = FormMethod.post) {
                attributes["data-live-target"] = "#rolesPanel"
                attributes["data-live-extra"] = "#roleModal"
                if (form.isEdit) input(type = InputType.hidden, name = "id") { value = form.editId.toString() }
                div("field") {
                    label { +"Role name" }
                    input(type = InputType.text, name = "name") {
                        id = "roleName"
                        value = form.name
                        attributes["placeholder"] = "e.g. Site Supervisor"
                        autoComplete = "off"
                        required = true
                    }
                    form.nameError?.let { p("field-err") { +it } }
                }
                div("perm-picks") {
                    Permission.entries.forEach { permission ->
                        label("perm-pick") {
                            input(type = InputType.checkBox, name = "perm") {
                                value = permission.key
                                checked = permission in form.checked
                            }
                            +permission.label
                        }
                    }
                }
                div("modal-actions") {
                    button(type = ButtonType.submit, classes = "submit") {
                        +if (form.isEdit) "Save role" else "Add role"
                    }
                    button(type = ButtonType.button, classes = "ghost-btn") {
                        attributes["data-role-modal-close"] = "true"
                        +"Cancel"
                    }
                }
            }
        }
    }
}
