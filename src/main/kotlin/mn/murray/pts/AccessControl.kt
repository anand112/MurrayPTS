package mn.murray.pts

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

fun DIV.accessContent(canAdmin: Boolean) {
    div("card-panel") {
        table("grid") {
            thead {
                tr {
                    th { +"Employee" }
                    th { +"Name" }
                    th { +"Position" }
                    th { +"Access" }
                    if (canAdmin) th { +"Password" }
                }
            }
            tbody {
                DemoData.access.forEach { row ->
                    tr {
                        td { span("code") { +row.employeeNumber } }
                        td { span("pname") { +row.name } }
                        td {
                            if (canAdmin) {
                                form(action = "/app/access-control/update", method = FormMethod.post, classes = "cell-edit") {
                                    input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                    select {
                                        name = "position"
                                        attributes["onchange"] = "this.form.submit()"
                                        DemoData.positions.forEach { pos ->
                                            option {
                                                selected = pos.name == row.position
                                                +pos.name
                                            }
                                        }
                                    }
                                }
                            } else {
                                +row.position
                            }
                        }
                        td {
                            if (canAdmin) {
                                form(action = "/app/access-control/update", method = FormMethod.post, classes = "cell-edit") {
                                    input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                    select {
                                        name = "access"
                                        attributes["onchange"] = "this.form.submit()"
                                        ACCESS_LEVELS.forEach { level ->
                                            option {
                                                selected = level == row.access
                                                +level
                                            }
                                        }
                                    }
                                }
                            } else {
                                span("chip " + when (row.access) {
                                    "Admin" -> "atrisk"
                                    "Manager" -> "planning"
                                    else -> "done"
                                }) { +row.access }
                            }
                        }
                        if (canAdmin) {
                            td {
                                form(action = "/app/access-control/password", method = FormMethod.post, classes = "cell-edit pw-edit") {
                                    input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                    input(type = InputType.password, name = "password") {
                                        attributes["placeholder"] = if (row.password.isEmpty()) "set password" else "••••••"
                                        attributes["title"] = "Type a new password and press Enter to save"
                                        required = true
                                    }
                                    button(type = ButtonType.submit, classes = "pin-btn") { +"Set" }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!canAdmin) p("panel-caption") { +"Only administrators can change positions and access levels." }
    }
    if (canAdmin) {
        div("card-panel") {
            h3 { +"Add user" }
            form(action = "/app/access-control", method = FormMethod.post) {
                div("form-grid") {
                    div("field") {
                        label { +"Employee number" }
                        input(type = InputType.text, name = "employeeNumber") {
                            placeholder = "EMP0023"
                            required = true
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
                        label { +"Position" }
                        select {
                            name = "position"
                            DemoData.positions.forEach { pos ->
                                option {
                                    selected = pos.name == "Team Member"
                                    +pos.name
                                }
                            }
                        }
                    }


                    div("field") {
                        label { +"Addition" }
                        select {
                            name = "addition"
                            DemoData.positions.forEach { pos ->
                                option {
                                    selected = pos.name == "Team tasks"
                                    +pos.name
                                }
                            }
                        }
                    }

                    div("field") {
                        label { +"Applicatoin" }
                        select {
                            name = "Applicatoin"
                            DemoData.positions.forEach { pos ->
                                option {
                                    selected = pos.name == "To-do tasksr"
                                    +pos.name
                                }
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
                        input(type = InputType.text, name = "password") {
                            placeholder = "Defaults to employee number"
                        }
                    }
                }
                p("panel-caption") { +"If no password is given, the employee's password defaults to their employee number." }
                button(type = ButtonType.submit, classes = "submit") { +"Add user" }
            }
        }
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
                    DemoData.positions.forEach { pos ->
                        tr {
                            td {
                                if (canAdmin) {
                                    form(action = "/app/positions/update", method = FormMethod.post, classes = "cell-edit") {
                                        input(type = InputType.hidden, name = "id") { value = pos.id.toString() }
                                        input(type = InputType.text, name = "name") {
                                            value = pos.name
                                            attributes["onchange"] = "this.form.submit()"
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
                                            attributes["onchange"] = "this.form.submit()"
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
                                            attributes["onchange"] = "this.form.submit()"
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
                                            attributes["onclick"] = "return confirm('Are you sure you want to delete this position?')"
                                            +"Delete"
                                        }
                                    }
                                }
                            }
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
    }
}
