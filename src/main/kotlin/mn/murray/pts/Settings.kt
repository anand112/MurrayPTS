package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.TBODY
import kotlinx.html.TD
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.unsafe

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
    CANNOT_DELETE_SELF(true, "You cannot delete your own account."),
    CANNOT_DELETE_SUPER(true, "The Super Admin account cannot be deleted."),
    CANNOT_DELETE_ADMIN(true, "Managers cannot delete Admin accounts."),
    USER_EXISTS(true, "User name is taken.");

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

fun DIV.settingsContent(session: UserSession, passwordStatus: PasswordStatus? = null) {
    val user = DemoData.userFor(session.employeeNumber)
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

    div("card-panel") {
        h3 { +"Change Password" }
        flashBanner(passwordStatus)
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

    div("card-panel") {
        h3 { +"All user accounts" }
        p("task-meta") {
            +"Every account with its contact details and current workload. Click a count to see all of that person's projects or tasks. Emails can be edited in place — administrators for any account, everyone for their own."
        }
        accountsTable(canAdmin = user.access == "Admin", currentEmployeeNumber = session.employeeNumber)
        accountsScript()
    }
}

/** Workload overview: one row per account — click a count to expand the underlying lists. */
private fun DIV.accountsTable(canAdmin: Boolean, currentEmployeeNumber: String) {
    table("grid") {
        thead {
            tr {
                th { +"Account" }
                th { +"Email" }
                th { +"Department" }
                th { +"Position" }
                th { +"Role" }
                th { +"Projects" }
                th { +"Tasks" }
            }
        }
        tbody {
            DemoData.access.forEach { row ->
                val member = DemoData.team.firstOrNull { it.employeeNumber == row.employeeNumber }
                val userProjects = DemoData.projects.filter { it.manager == row.name }
                val userTasks = DemoData.assignedTasks.filter { it.assignee == row.employeeNumber }
                tr {
                    td {
                        div("assignee") {
                            div("assignee-name") { +row.name }
                            div("assignee-id") { +row.employeeNumber }
                        }
                    }
                    td {
                        if (canAdmin || row.employeeNumber == currentEmployeeNumber) {
                            form(action = "/app/settings/email", method = FormMethod.post, classes = "cell-edit") {
                                input(type = InputType.hidden, name = "employeeNumber") { value = row.employeeNumber }
                                input(type = InputType.email, name = "email") {
                                    value = row.displayEmail()
                                    attributes["onchange"] = "this.form.requestSubmit()"
                                    attributes["title"] = "Edit email and press Enter or click away to save"
                                }
                            }
                        } else {
                            span("acct-email") { +row.displayEmail() }
                        }
                    }
                    td { +(member?.department?.takeIf { it.isNotBlank() } ?: "—") }
                    td { +row.position.ifBlank { "—" } }
                    td {
                        span("chip " + when (row.access) {
                            "Admin" -> "atrisk"
                            "Manager" -> "planning"
                            else -> "done"
                        }) { +row.access }
                    }
                    td("num") { countCell(row.employeeNumber, "projects", userProjects.size, "projects") }
                    td("num") { countCell(row.employeeNumber, "tasks", userTasks.size, "tasks") }
                }
                acctDetailRow(row.employeeNumber, userProjects, userTasks)
            }
        }
    }
}

/** Zero counts stay plain text; non-zero ones become buttons that open the matching detail pane. */
private fun TD.countCell(emp: String, kind: String, count: Int, label: String) {
    if (count == 0) {
        span("count-zero") { +"0" }
    } else {
        button(type = ButtonType.button, classes = "count-btn") {
            attributes["data-emp"] = emp
            attributes["data-kind"] = kind
            attributes["title"] = "Show $label"
            +"$count"
        }
    }
}

/** Hidden sub-row with two panes: the account's projects and its tasks. */
private fun TBODY.acctDetailRow(emp: String, projects: List<Project>, tasks: List<TaskItem>) {
    tr("acct-detail") {
        attributes["data-emp"] = emp
        attributes["hidden"] = "hidden"
        td {
            colSpan = "7"
            div("acct-pane") {
                attributes["data-pane"] = "projects"
                attributes["hidden"] = "hidden"
                div("acct-pane-head") { +"Projects (${projects.size})" }
                if (projects.isEmpty()) {
                    div("mpop-empty") { +"No projects." }
                } else {
                    projects.sortedBy { it.due }.forEach { p ->
                        div("mpop-row") {
                            div("mpop-main") {
                                div("mpop-title") {
                                    a(href = "/app/projects/${p.id}") { span("code") { +p.code } }
                                    +p.name
                                }
                                div("mpop-meta") {
                                    span("mdot") { attributes["style"] = "background:${taskColor(p.status)}" }
                                    +p.status
                                    span { +"due ${formatDue(p.due)}" }
                                    span("mprog") {
                                        span("mprog-bar") { attributes["style"] = "width:${p.progress}%" }
                                    }
                                    span { +"${p.progress}%" }
                                }
                            }
                        }
                    }
                }
            }
            div("acct-pane") {
                attributes["data-pane"] = "tasks"
                attributes["hidden"] = "hidden"
                div("acct-pane-head") { +"Tasks (${tasks.size})" }
                if (tasks.isEmpty()) {
                    div("mpop-empty") { +"No tasks." }
                } else {
                    tasks.sortedBy { it.due }.forEach { t ->
                        div("mpop-row") {
                            div("mpop-main") {
                                div("mpop-title") {
                                    if (t.id > 0) span("tcode m") { +taskCode(t.id) }
                                    +t.title
                                }
                                div("mpop-meta") {
                                    span("mdot") { attributes["style"] = "background:${taskColor(t.status)}" }
                                    +t.status
                                    if (t.project.isNotBlank()) span { +projectContext(t.project) }
                                    if (t.due.isNotBlank()) span { +"due ${formatDue(t.due)}" }
                                    span("prio ${prioClass(t.priority)}") { +t.priority }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Toggles the workload detail sub-rows when a count button is clicked. */
private fun DIV.accountsScript() {
    unsafe {
        +("""<script>
(function () {
  document.querySelectorAll('.count-btn').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var emp = btn.getAttribute('data-emp');
      var kind = btn.getAttribute('data-kind');
      var sub = document.querySelector('tr.acct-detail[data-emp="' + emp + '"]');
      if (!sub) return;
      var pane = sub.querySelector('.acct-pane[data-pane="' + kind + '"]');
      var wasOpen = !sub.hidden && !!pane && !pane.hidden;
      var row = btn.closest('tr');
      if (row) {
        row.querySelectorAll('.count-btn').forEach(function (b) { b.classList.remove('open'); });
      }
      sub.querySelectorAll('.acct-pane').forEach(function (p) { p.hidden = true; });
      if (wasOpen) {
        sub.hidden = true;
      } else {
        sub.hidden = false;
        if (pane) pane.hidden = false;
        btn.classList.add('open');
      }
    });
  });
})();
</script>""")
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatDue(iso: String): String {
    val parts = iso.split("-")
    if (parts.size != 3) return iso
    val m = parts[1].toIntOrNull() ?: return iso
    val d = parts[2].toIntOrNull() ?: return iso
    return "%02d %s %s".format(d, MONTHS[m - 1], parts[0])
}

private fun taskCode(id: Int): String = "T-${id.toString().padStart(5, '0')}"

private fun prioClass(priority: String): String = when (priority) {
    "High" -> "p-high"
    "Medium" -> "p-med"
    else -> "p-low"
}

private fun taskColor(status: String): String = when (status) {
    "In progress" -> "#e0a800"
    "On hold" -> "#e07000"
    "Blocked" -> "#d93025"
    "Completed", "Ongoing" -> "#1e8e3e"
    else -> "#8a9199"
}

private fun projectContext(project: String): String {
    if (project.isBlank()) return ""
    val p = DemoData.projects.firstOrNull { it.name == project } ?: return project
    return "${p.code} · ${p.name}"
}
