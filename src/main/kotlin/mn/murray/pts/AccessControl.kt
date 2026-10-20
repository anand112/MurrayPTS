package mn.murray.pts
import mn.murray.pts.domain.*

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
import kotlinx.html.unsafe

/** One row of the access table: full team roster first, then accounts without a roster entry. */
private data class UserRow(val employeeNumber: String, val name: String, val teamPosition: String, val account: AccessRow?)

private fun userRows(): List<UserRow> {
    val teamEmps = DemoData.team.map { it.employeeNumber }.toSet()
    return DemoData.team.map { m ->
        UserRow(m.employeeNumber, m.name, m.position, DemoData.access.firstOrNull { it.employeeNumber == m.employeeNumber })
    } + DemoData.access.filter { it.employeeNumber !in teamEmps }.map { a ->
        UserRow(a.employeeNumber, a.name, a.position, a)
    }
}

/** Results of the Add-user form shown on the panel itself rather than the page-top banner. */
private val ADD_FORM_STATUSES = setOf(PasswordStatus.USER_ADDED, PasswordStatus.USER_EXISTS, PasswordStatus.TOO_SHORT)

/** One editable row of the Manage-positions table; reused for in-place (fetch) add/update responses. */
internal fun TR.positionRow(pos: Position, canAdmin: Boolean) {
    td {
        if (canAdmin) {
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
        if (canAdmin) {
            form(action = "/app/positions/update", method = FormMethod.post, classes = "cell-edit") {
                input(type = InputType.hidden, name = "id") { value = pos.id.toString() }
                select {
                    name = "permissionLevel"
                    attributes["data-autosubmit"] = ""
                    PERMISSION_LEVELS.forEach { level ->
                        option {
                            selected = level == pos.permissionLevel
                            +level
                        }
                    }
                }
            }
        } else {
            span("chip " + when (pos.permissionLevel) {
                "Admin" -> "atrisk"
                "Manager" -> "planning"
                else -> "done"
            }) { +pos.permissionLevel }
        }
    }
    td {
        if (canAdmin) {
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
    if (canAdmin) {
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

fun DIV.accessContent(canAdmin: Boolean, canManage: Boolean, currentEmployeeNumber: String, passwordStatus: PasswordStatus? = null, statusDetail: String? = null) {
    flashBanner(passwordStatus?.takeIf { it !in ADD_FORM_STATUSES }, statusDetail)
    div("card-panel") {
        table("grid") {
            thead {
                tr {
                    th { +"Employee" }
                    th { +"Name" }
                    th { +"Position" }
                    th { +"Access" }
                    if (canAdmin) th { +"Password" }
                    if (canManage) th { +"Delete" }
                }
            }
            tbody {
                userRows().forEach { row ->
                    val account = row.account
                    tr {
                        td { span("code") { +row.employeeNumber } }
                        td { span("pname") { +row.name } }
                        td {
                            if (canAdmin && account != null) {
                                form(action = "/app/access-control/update", method = FormMethod.post, classes = "cell-edit") {
                                    input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                    select {
                                        name = "position"
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
                            if (canAdmin && account != null) {
                                form(action = "/app/access-control/update", method = FormMethod.post, classes = "cell-edit") {
                                    input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                    select {
                                        name = "access"
                                        attributes["data-autosubmit"] = ""
                                        ACCESS_LEVELS.forEach { level ->
                                            option {
                                                selected = level == account.access
                                                +level
                                            }
                                        }
                                    }
                                }
                            } else if (account != null) {
                                span("chip " + when (account.access) {
                                    "Admin" -> "atrisk"
                                    "Manager" -> "planning"
                                    else -> "done"
                                }) { +account.access }
                            } else {
                                span("assignee none") { +"No account" }
                            }
                        }
                        if (canAdmin) {
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
                        if (canManage) {
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
        if (!canAdmin) p("panel-caption") {
            +(if (canManage) "Managers can add and delete users, including team members without an account; only administrators can change positions, access levels and passwords."
              else "Only administrators and managers can manage user accounts.")
        }
    }
    if (canManage) {
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
                            placeholder = "Auto: firstname.lastname@murray.mn"
                        }
                    }
                    if (canAdmin) {
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

                        div("field") {
                            label { +"Access level" }
                            select {
                                name = "access"
                                ACCESS_LEVELS.forEach { option { +it } }
                            }
                        }
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
                    if (canAdmin) {
                        +"Position defaults to the member's team-roster position, access to Viewer. If no password is given, it defaults to the employee number. A blank email is generated as firstname.lastname@murray.mn."
                    } else {
                        +"New users start with Viewer access and their employee number as password. Only administrators can set positions, access levels and passwords afterwards. A blank email is generated as firstname.lastname@murray.mn."
                    }
                }
                button(type = ButtonType.submit, classes = "submit") { +"Add user" }
            }
        }
    }
    if (canAdmin) {
        div("card-panel") {
            h3 { +"Manage positions" }
            table("grid") {
                thead {
                    tr {
                        th { +"Position" }
                        th { +"Permission" }
                        th { +"Description" }
                        if (canAdmin) th { +"Actions" }
                    }
                }
                tbody {
                    id = "positions-tbody"
                    DemoData.positions.forEach { pos ->
                        tr {
                            positionRow(pos, canAdmin)
                        }
                    }
                }
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
                        label { +"Permission level" }
                        select {
                            name = "permissionLevel"
                            PERMISSION_LEVELS.forEach { option { +it } }
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
        script(src = "/static/js/access-control.js") {}
    }
}
