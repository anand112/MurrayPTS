package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.TBODY
import kotlinx.html.TD
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.id
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
import kotlinx.html.unsafe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun DIV.teamContent(canManage: Boolean) {
    val projects = DemoData.projects
    div("card-panel") {
        p("panel-caption") {
            +"Tip: click a member for details, or open the Tasks count to see who is doing what."
            if (canManage) +" Use + next to it to assign them a task."
        }
        table("grid team-table") {
            thead {
                tr {
                    th { +"ID" }
                    th { +"Name" }
                    th { +"Department" }
                    th { +"Position" }
                    th(classes = "num") { +"Tasks" }
                    th(classes = "num") { +"Ongoing" }
                    th(classes = "num") { +"On hold" }
                    th(classes = "num") { +"Not started" }
                    th(classes = "num") { +"Blocked" }
                    th(classes = "num") { +"Completed" }
                    th(classes = "num") { +"Total" }
                    th(classes = "num") { attributes["aria-label"] = "Details"; +"" }
                }
            }
            tbody {
                DemoData.team.forEach { member ->
                    val mine = projects.filter { it.manager == member.name }
                    fun count(status: String) = mine.count { it.status == status }
                    val tasks = memberTaskRows(member.employeeNumber)
                    val open = tasks.count { it.first.status != "Completed" }
                    tr {
                        attributes["data-emp"] = member.employeeNumber
                        td { span("code") { +member.employeeNumber } }
                        td {
                            div("team-name") {
                                span("avatar sm") { +initials(member.name) }
                                div("assignee-meta") {
                                    span("pname") { +member.name }
                                    div("assignee-id") { +memberEmail(member) }
                                }
                            }
                        }
                        td { +member.department }
                        td { +member.position }
                        td("num") {
                            button(type = ButtonType.button, classes = if (open == 0) "tasks-toggle zero" else "tasks-toggle") {
                                attributes["data-emp"] = member.employeeNumber
                                attributes["title"] = "Show ${member.name}'s tasks"
                                +"$open"
                            }
                            if (canManage) {
                                button(type = ButtonType.button, classes = "quick-add") {
                                    attributes["data-emp"] = member.employeeNumber
                                    attributes["title"] = "Assign a task to ${member.name}"
                                    +"+"
                                }
                            }
                        }
                        td("num") { statusCount("ontrack", count("Ongoing")) }
                        td("num") { statusCount("onhold", count("On hold")) }
                        td("num") { statusCount("planning", count("Not started")) }
                        td("num") { statusCount("atrisk", count("Blocked")) }
                        td("num") { statusCount("done", count("Completed")) }
                        td("num") { span("count-total") { +"${mine.size}" } }
                        td("num") { span("mhint") { +"›" } }
                    }
                    memberTasksRow(member, tasks)
                }
            }
        }
    }

    if (canManage) {
        div("card-panel") {
            id = "assignPanel"
            h3 { +"Assign a task to a team member" }
            form(action = "/app/team/assign", method = FormMethod.post) {
                div("form-grid") {
                    div("field") {
                        label { +"Team member" }
                        select {
                            id = "assignMember"
                            name = "assignee"
                            required = true
                            DemoData.team.forEach { member ->
                                option { value = member.employeeNumber; +"${member.employeeNumber} · ${member.name} (${member.position})" }
                            }
                        }
                    }
                    div("field") {
                        label { +"Task" }
                        input(type = InputType.text, name = "title") {
                            id = "assignTitle"
                            placeholder = "e.g. Prepare weekly progress report"
                            required = true
                        }
                    }
                    div("field") {
                        label { +"Project (optional)" }
                        select {
                            name = "project"
                            option { value = ""; +"— No project —" }
                            DemoData.projects.forEach { project ->
                                option { value = project.name; +"${project.code} · ${project.name}" }
                            }
                        }
                    }
                    div("field") {
                        label { +"Start date" }
                        input(type = InputType.date, name = "started")
                    }
                    div("field") {
                        label { +"Due date" }
                        input(type = InputType.date, name = "due")
                    }
                    div("field") {
                        label { +"Priority" }
                        select {
                            name = "priority"
                            listOf("High", "Medium", "Low").forEach { option { +it } }
                        }
                    }
                }
                button(type = ButtonType.submit, classes = "submit") { +"Assign task" }
            }
        }
    }
    memberDetailScript()
}

private fun TD.statusCount(kind: String, n: Int) {
    span(if (n == 0) "count zero" else "count $kind") { +"$n" }
}

private fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")

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

/** All of a member's tasks (project tasks plus personal ones), open first then by due date. */
private fun memberTaskRows(emp: String): List<Pair<TaskItem, Boolean>> =
    (DemoData.assignedTasks.filter { it.assignee == emp }.map { it to false } +
        DemoData.otherTasks.filter { it.assignee == emp }.map { it to true })
        .sortedWith(
            compareBy(
                { (t, _) -> t.status == "Completed" },
                { (t, _) -> t.due.isEmpty() },
                { (t, _) -> t.due },
            )
        )

/** Hidden table sub-row listing everything a member is doing; toggled from the Tasks count. */
private fun TBODY.memberTasksRow(member: Member, tasks: List<Pair<TaskItem, Boolean>>) {
    tr("member-tasks") {
        attributes["data-emp"] = member.employeeNumber
        attributes["hidden"] = "hidden"
        td {
            colSpan = "12"
            if (tasks.isEmpty()) {
                span("mtd-empty") { +"No tasks assigned yet." }
            } else {
                tasks.forEach { (task, isOther) ->
                    div("mpop-row") {
                        div("mpop-main") {
                            div("mpop-title") {
                                if (!isOther && task.id > 0) span("tcode m") { +taskCode(task.id) }
                                if (isOther) span("other-tag") { +"other" }
                                +task.title
                            }
                            div("mpop-meta") {
                                span("mdot") { attributes["style"] = "background:${taskColor(task.status)}" }
                                +task.status
                                if (isOther) {
                                    if (task.bucket.isNotBlank()) span { +task.bucket }
                                } else if (task.project.isNotBlank()) {
                                    span { +projectContext(task.project) }
                                }
                                if (task.due.isNotBlank()) span { +"due ${formatDue(task.due)}" }
                                span("prio ${prioClass(task.priority)}") { +task.priority }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Member detail popup data ------------------------------------------------

@Serializable
private data class MemberProjectInfo(
    val code: String,
    val name: String,
    val status: String,
    val progress: Int,
    val due: String,
    val role: String,
)

@Serializable
private data class MemberTaskInfo(
    val id: Int,
    val title: String,
    val context: String,
    val status: String,
    val due: String,
    val priority: String,
    val other: Boolean = false,
)

@Serializable
private data class MemberDetail(
    val name: String,
    val position: String,
    val department: String,
    val email: String = "",
    val projects: List<MemberProjectInfo> = emptyList(),
    val tasks: List<MemberTaskInfo> = emptyList(),
)

private val detailJson = Json { encodeDefaults = true }

/** One entry per team member: projects they manage or contribute tasks to, plus their tasks. */
private fun buildMemberDetails(): Map<String, MemberDetail> {
    val allProjects = DemoData.projects
    val projectByName = allProjects.associateBy { it.name }
    return DemoData.team.associate { member ->
        val managed = allProjects.filter { it.manager == member.name }
        val managedIds = managed.map { it.id }.toSet()
        val assigned = DemoData.assignedTasks.filter { it.assignee == member.employeeNumber }
        val other = DemoData.otherTasks.filter { it.assignee == member.employeeNumber }
        val contributedNames = assigned.map { it.project }.toSet()
        val contributed = allProjects.filter { it.name in contributedNames && it.id !in managedIds }

        val projectInfos = (
            managed.map {
                MemberProjectInfo(it.code, it.name, it.status, it.progress, it.due, "Manager")
            } + contributed.map {
                MemberProjectInfo(it.code, it.name, it.status, it.progress, it.due, "Tasks")
            }
            ).sortedWith(compareBy({ it.role != "Manager" }, { it.name }))

        val taskInfos = (assigned.map { it to false } + other.map { it to true })
            .sortedWith(compareBy({ (t, _) -> t.due.isEmpty() }, { (t, _) -> t.due }))
            .map { (t, isOther) ->
                val context = if (isOther) t.bucket.ifEmpty { "Other" }
                else projectByName[t.project]?.let { "${it.code} · ${it.name}" } ?: t.project
                MemberTaskInfo(t.id, t.title, context, t.status, t.due, t.priority, isOther)
            }

        member.employeeNumber to MemberDetail(member.name, member.position, member.department, memberEmail(member), projectInfos, taskInfos)
    }
}

/** JSON for the popup, with "<" escaped so it is safe inside a script tag. */
private fun memberDetailJson(): String =
    detailJson.encodeToString(buildMemberDetails()).replace("<", "\\u003c")

private fun DIV.memberDetailScript() {
    unsafe {
        +("""<script type="application/json" id="memberDetailData">""" + memberDetailJson() + "</script>")
        +(
            """<script>
(function () {
  var data = {};
  var tag = document.getElementById('memberDetailData');
  try { data = JSON.parse(tag.textContent || '{}'); } catch (e) { data = {}; }
  var pop = document.createElement('div');
  pop.className = 'mpop';
  pop.id = 'memberPop';
  pop.hidden = true;
  document.body.appendChild(pop);
  var COLORS = { 'Ongoing': '#1e8e3e', 'In progress': '#e0a800', 'On hold': '#e07000',
                 'Not started': '#8a9199', 'Blocked': '#d93025', 'Completed': '#1e8e3e' };
  var MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  function fmtDate(iso) {
    var p = (iso || '').split('-');
    if (p.length !== 3) return iso || '';
    var m = parseInt(p[1], 10);
    return p[2] + ' ' + (MONTHS[m - 1] || p[1]) + ' ' + p[0];
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function initials(name) {
    return (name || '').split(' ').map(function (w) { return w.charAt(0); }).slice(0, 2).join('');
  }
  function render(emp) {
    var d = data[emp];
    if (!d) return null;
    var h = '';
    h += '<div class="mpop-head"><div class="avatar sm">' + esc(initials(d.name)) + '</div>' +
         '<div class="mpop-id"><div class="mpop-name">' + esc(d.name) + '</div>' +
         '<div class="mpop-sub">' + esc(d.position) + (d.department ? ' · ' + esc(d.department) : '') +
         ' · ' + esc(emp) + (d.email ? ' · ' + esc(d.email) : '') + '</div></div>' +
         '<button type="button" class="mpop-x" aria-label="Close">×</button></div>';
    h += '<div class="mpop-body">';
    h += '<div class="mpop-sechead">Projects (' + d.projects.length + ')</div>';
    if (!d.projects.length) h += '<div class="mpop-empty">No projects yet.</div>';
    d.projects.forEach(function (p) {
      var c = COLORS[p.status] || '#8a9199';
      h += '<div class="mpop-row"><div class="mpop-main">' +
           '<div class="mpop-title"><span class="code">' + esc(p.code) + '</span> ' + esc(p.name) + '</div>' +
           '<div class="mpop-meta"><span class="mdot" style="background:' + c + '"></span>' + esc(p.status) +
           ' · due ' + esc(fmtDate(p.due)) +
           '<span class="mprog"><span class="mprog-bar" style="width:' + (p.progress || 0) + '%"></span></span>' +
           (p.progress || 0) + '%</div></div>' +
           '<span class="mrole' + (p.role === 'Manager' ? ' mgr' : '') + '">' + esc(p.role) + '</span></div>';
    });
    h += '<div class="mpop-sechead">Tasks (' + d.tasks.length + ')</div>';
    if (!d.tasks.length) h += '<div class="mpop-empty">No tasks assigned.</div>';
    d.tasks.forEach(function (t) {
      var c = COLORS[t.status] || '#8a9199';
      var code = t.id > 0 ? '<span class="tcode m">T-' + ('00000' + t.id).slice(-5) + '</span> ' : '';
      h += '<div class="mpop-row"><div class="mpop-main">' +
           '<div class="mpop-title">' + code + esc(t.title) + '</div>' +
           '<div class="mpop-meta"><span class="mdot" style="background:' + c + '"></span>' + esc(t.status) +
           (t.context ? ' · ' + esc(t.context) : '') +
           (t.other ? ' · other' : '') +
           (t.due ? ' · due ' + esc(fmtDate(t.due)) : '') +
           ' · ' + esc(t.priority) + '</div></div></div>';
    });
    h += '</div>';
    return h;
  }
  var current = null;
  function close() { pop.hidden = true; current = null; }
  function place(row) {
    var r = row.getBoundingClientRect();
    var sx = window.pageXOffset, sy = window.pageYOffset;
    var pw = pop.offsetWidth, ph = pop.offsetHeight;
    var left = r.right + sx + 10;
    if (left + pw > sx + window.innerWidth - 12) left = r.left + sx - pw - 10;
    if (left < sx + 12) left = sx + 12;
    var top = r.top + sy + 6;
    if (top + ph > sy + window.innerHeight - 12) top = sy + window.innerHeight - ph - 12;
    if (top < sy + 12) top = sy + 12;
    pop.style.left = left + 'px';
    pop.style.top = top + 'px';
  }
  document.querySelectorAll('.team-table tbody tr[data-emp]').forEach(function (row) {
    row.addEventListener('click', function () {
      var emp = row.getAttribute('data-emp');
      if (current === emp && !pop.hidden) { close(); return; }
      var html = render(emp);
      if (!html) return;
      pop.innerHTML = html;
      pop.hidden = false;
      current = emp;
      place(row);
    });
  });
  document.querySelectorAll('.tasks-toggle').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault(); e.stopPropagation();
      var emp = btn.getAttribute('data-emp');
      var sub = document.querySelector('tr.member-tasks[data-emp="' + emp + '"]');
      if (!sub) return;
      sub.hidden = !sub.hidden;
      btn.classList.toggle('open', !sub.hidden);
    });
  });
  document.querySelectorAll('.quick-add').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault(); e.stopPropagation();
      var emp = btn.getAttribute('data-emp');
      var sel = document.getElementById('assignMember');
      var title = document.getElementById('assignTitle');
      if (!sel) return;
      sel.value = emp;
      var panel = document.getElementById('assignPanel');
      if (panel) panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
      setTimeout(function () { if (title) title.focus(); }, 300);
    });
  });
  pop.addEventListener('click', function (e) {
    if (e.target.closest('.mpop-x')) close();
  });
  document.addEventListener('click', function (e) {
    if (pop.hidden) return;
    if (pop.contains(e.target)) return;
    if (e.target.closest && e.target.closest('.team-table tbody tr[data-emp]')) return;
    close();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') close();
  });
})();
</script>"""
        )
    }
}
