package mn.murray.pts
import mn.murray.pts.domain.*
import mn.murray.pts.config.AppConfig
import mn.murray.pts.db.DatabaseFactory
import mn.murray.pts.db.migration.JsonImporter
import mn.murray.pts.service.AuthService
import mn.murray.pts.web.plugin.SecurityHeaders

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveParameters
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import io.ktor.util.date.GMTDate
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.html.DIV
import kotlinx.html.stream.createHTML
import kotlinx.html.tr

const val SESSION_COOKIE = "PTS_SID"

val MANAGE_ROLES = setOf("Admin", "Manager")

/** True when the request came from the page's fetch() handler, which expects fragments instead of redirects. */
private fun ApplicationCall.isFetch(): Boolean = request.headers["X-Requested-With"] == "fetch"

/**
 * Best-effort client IP for rate limiting. Honours the first `X-Forwarded-For` hop when
 * the app sits behind a reverse proxy, otherwise falls back to the socket peer.
 */
private fun ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteHost

class UserSession(val employeeNumber: String)

object SessionStore {
    val sessions = ConcurrentHashMap<String, UserSession>()
}

private val log = LoggerFactory.getLogger("mn.murray.pts.Application")

/**
 * Prepare persistence for the v2 SQLite/Exposed stack.
 *
 * Additive by design: it creates and migrates the database and performs the one-shot
 * legacy JSON import, but the running app still reads/writes through the legacy
 * [DemoData]/JsonStore path until Stage 6 rewires the routes. That keeps the build green
 * and the app usable at every step of the restructure.
 */
private fun bootstrapDatabase() {
    AppConfig.validate()
    log.info("PTS v2 starting\n{}", AppConfig.summary())
    DatabaseFactory.init(AppConfig.dbPath)
    val legacy = File(AppConfig.legacyJsonPath)
    val summary = JsonImporter.importFromFile(legacy)
    if (summary.imported) {
        log.info(
            "Database bootstrapped from legacy JSON: {} users, {} projects, {} tasks.",
            summary.users, summary.projects, summary.tasks
        )
    } else {
        log.info("Database bootstrap: import skipped ({})", summary.reason)
    }
    // Guarantee an admin account exists even on a fresh DB with no legacy JSON.
    AuthService.ensureSuperAdmin()
}

fun main() {
    bootstrapDatabase()
    val port = AppConfig.port
    val host = AppConfig.host
    val server = embeddedServer(Netty, port = port, host = host) {
        install(SecurityHeaders)
        routing {
            staticResources("/static", "static")
            get("/") {
                if (call.currentSession() != null) {
                    call.respondRedirect("/app")
                } else {
                    call.respondLogin(error = null)
                }
            }
            post("/login") {
                val ip = call.clientIp()
                val limiter = AuthService.rateLimiter
                if (limiter.isBlocked(ip)) {
                    val secs = limiter.retryAfterSeconds(ip)
                    return@post call.respondLogin(error = "Too many failed attempts. Try again in ${secs}s.")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val password = form["password"].orEmpty()
                val user = DemoData.access.firstOrNull { it.employeeNumber == employeeNumber }
                if (employeeNumber.isNotEmpty() && user != null && password == user.effectivePassword()) {
                    limiter.reset(ip)
                    val token = UUID.randomUUID().toString()
                    SessionStore.sessions[token] = UserSession(employeeNumber)
                    call.response.cookies.append(Cookie(SESSION_COOKIE, token, httpOnly = true, path = "/"))
                    call.respondRedirect("/app")
                } else {
                    limiter.recordFailure(ip)
                    call.respondLogin(error = "Invalid employee number or password.")
                }
            }
            get("/logout") {
                call.request.cookies[SESSION_COOKIE]?.let { SessionStore.sessions.remove(it) }
                call.response.cookies.append(Cookie(SESSION_COOKIE, "", expires = GMTDate.START, path = "/"))
                call.respondRedirect("/")
            }
            get("/app") { call.respondRedirect("/app/projects") }

            get("/app/projects") {
                val session = call.currentSession() ?: return@get call.respondRedirect("/")
                val type = call.request.queryParameters["type"]?.takeIf { it in PROJECT_TYPES }
                call.respondHtml { appPage(session, "projects", "Projects") { projectsContent(it, type) } }
            }
            get("/app/projects/{id}") {
                val session = call.currentSession() ?: return@get call.respondRedirect("/")
                val project = call.parameters["id"]?.toIntOrNull()?.let { id -> DemoData.projects.firstOrNull { it.id == id } }
                if (project == null) return@get call.respondRedirect("/app/projects")
                val canManage = DemoData.userFor(session.employeeNumber).access in MANAGE_ROLES
                call.respondHtml { appPage(session, "projects", project.name) { projectDetailContent(it, project, canManage) } }
            }
            get("/app/assigned-tasks") {
                val session = call.currentSession() ?: return@get call.respondRedirect("/")
                val canManage = DemoData.userFor(session.employeeNumber).access in MANAGE_ROLES
                call.respondHtml { appPage(session, "assigned-tasks", "Assigned tasks") { assignedTasksContent(session, canManage) } }
            }
            sectionRoute("/app/calendar", "calendar", "Calendar") { calendarContent(it) }
            sectionRoute("/app/team", "team", "Team") {
                teamContent(canManage = DemoData.userFor(it.employeeNumber).access in MANAGE_ROLES)
            }
            sectionRoute("/app/forecasts", "forecasts", "Forecasts") { forecastsContent() }
            sectionRoute("/app/lessons", "lessons", "Lesson learnt") { lessonsContent() }
            sectionRoute("/app/other-tasks", "other-tasks", "Other tasks") { tasksContent(DemoData.otherTasks, "Unassigned backlog") }
            sectionRoute("/app/todo", "todo", "To-do") { todoContent(it) }
            get("/app/access-control") {
                val session = call.currentSession() ?: return@get call.respondRedirect("/")
                val access = DemoData.userFor(session.employeeNumber).access
                val canAdmin = access == "Admin"
                val canManage = access in MANAGE_ROLES
                val status = PasswordStatus.fromCode(call.request.queryParameters["pw"])
                val detail = call.request.queryParameters["who"]
                call.respondHtml { appPage(session, "access-control", "Access control") { accessContent(canAdmin, canManage, session.employeeNumber, status, detail) } }
            }
            get("/app/settings") {
                val session = call.currentSession() ?: return@get call.respondRedirect("/")
                val status = PasswordStatus.fromCode(call.request.queryParameters["pw"])
                call.respondHtml { appPage(session, "settings", "Settings") { settingsContent(it, status) } }
            }

            post("/app/settings/password") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val form = call.receiveParameters()
                val currentPassword = form["currentPassword"].orEmpty()
                val newPassword = form["newPassword"].orEmpty()
                val confirmPassword = form["confirmPassword"].orEmpty()
                val user = DemoData.userFor(session.employeeNumber)

                val status = when {
                    currentPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty() -> PasswordStatus.EMPTY
                    currentPassword != user.effectivePassword() -> PasswordStatus.WRONG_CURRENT
                    newPassword != confirmPassword -> PasswordStatus.MISMATCH
                    newPassword.length < MIN_PASSWORD_LENGTH -> PasswordStatus.TOO_SHORT
                    else -> {
                        JsonStore.update { data ->
                            data.copy(
                                access = data.access.map { row ->
                                    if (row.employeeNumber != session.employeeNumber) return@map row
                                    row.copy(password = newPassword)
                                },
                            )
                        }
                        PasswordStatus.CHANGED
                    }
                }
                call.respondRedirect("/app/settings?pw=${status.name}")
            }

            post("/app/settings/email") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val isAdmin = DemoData.userFor(session.employeeNumber).access == "Admin"
                if (employeeNumber.isNotEmpty() && (isAdmin || employeeNumber == session.employeeNumber)) {
                    JsonStore.update { data ->
                        val target = data.access.firstOrNull { it.employeeNumber == employeeNumber }
                            ?: return@update data
                        val email = form["email"]?.trim()?.lowercase().orEmpty()
                            .ifBlank { autoEmail(target.name) }
                        data.copy(
                            access = data.access.map { row ->
                                if (row.employeeNumber == employeeNumber) row.copy(email = email) else row
                            },
                        )
                    }
                }
                call.respondRedirect("/app/settings")
            }

            post("/app/projects") {
                if (call.currentSession() == null) return@post call.respondRedirect("/")
                val form = call.receiveParameters()
                val name = form["name"]?.trim().orEmpty()
                val type = form["type"]?.trim()?.takeIf { it in PROJECT_TYPES } ?: "Site projects"
                if (name.isNotEmpty()) {
                    JsonStore.update { data ->
                        val nextId = (data.projects.maxOfOrNull { it.id } ?: 0) + 1
                        data.copy(
                            projects = data.projects + Project(
                                id = nextId,
                                code = nextProjectCode(data.projects, type),
                                name = name,
                                status = form["status"]?.trim()?.takeIf { it in PROJECT_STATUSES } ?: "Not started",
                                progress = form["progress"]?.toIntOrNull()?.coerceIn(0, 100) ?: 0,
                                manager = form["manager"]?.trim().orEmpty(),
                                due = form["due"]?.trim().orEmpty(),
                                type = type,
                            ),
                        )
                    }
                }
                call.respondRedirect("/app/projects")
            }
            post("/app/projects/{id}/manager") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access !in MANAGE_ROLES) {
                    return@post call.respondRedirect("/app/projects")
                }
                val id = call.parameters["id"]?.toIntOrNull()
                val manager = call.receiveParameters()["manager"]?.trim().orEmpty()
                val valid = manager.isEmpty() || DemoData.team.any { it.name == manager }
                if (id != null && valid && DemoData.projects.any { it.id == id }) {
                    JsonStore.update { data ->
                        data.copy(projects = data.projects.map { if (it.id == id) it.copy(manager = manager) else it })
                    }
                }
                call.respondRedirect(if (id == null) "/app/projects" else "/app/projects/$id")
            }
            post("/app/projects/{id}/assignees/add") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access !in MANAGE_ROLES) {
                    return@post call.respondRedirect("/app/projects")
                }
                val id = call.parameters["id"]?.toIntOrNull()
                val emp = call.receiveParameters()["assignee"]?.trim().orEmpty()
                if (id != null && emp.isNotEmpty() && DemoData.team.any { it.employeeNumber == emp } && DemoData.projects.any { it.id == id }) {
                    JsonStore.update { data ->
                        data.copy(projects = data.projects.map {
                            if (it.id == id && emp !in it.assignees) it.copy(assignees = it.assignees + emp) else it
                        })
                    }
                }
                call.respondRedirect(if (id == null) "/app/projects" else "/app/projects/$id")
            }
            post("/app/projects/{id}/assignees/remove") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access !in MANAGE_ROLES) {
                    return@post call.respondRedirect("/app/projects")
                }
                val id = call.parameters["id"]?.toIntOrNull()
                val emp = call.receiveParameters()["assignee"]?.trim().orEmpty()
                if (id != null && emp.isNotEmpty()) {
                    JsonStore.update { data ->
                        data.copy(projects = data.projects.map {
                            if (it.id == id) it.copy(assignees = it.assignees.filter { a -> a != emp }) else it
                        })
                    }
                }
                call.respondRedirect(if (id == null) "/app/projects" else "/app/projects/$id")
            }
            post("/app/assigned-tasks") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access !in MANAGE_ROLES) {
                    return@post call.respondRedirect("/app/assigned-tasks")
                }
                val form = call.receiveParameters()
                val title = form["title"]?.trim().orEmpty()
                val assignee = form["assignee"]?.trim().orEmpty()
                val project = form["project"]?.trim().orEmpty()
                val validAssignee = DemoData.team.any { it.employeeNumber == assignee }
                if (title.isNotEmpty() && validAssignee) {
                    JsonStore.update { data ->
                        val nextId = (data.assignedTasks.maxOfOrNull { it.id } ?: 0) + 1
                        data.copy(
                            assignedTasks = data.assignedTasks + TaskItem(
                                title = title,
                                project = project,
                                due = form["due"]?.trim().orEmpty(),
                                priority = form["priority"]?.trim()?.takeIf { it in listOf("High", "Medium", "Low") } ?: "Medium",
                                assignee = assignee,
                                status = "Not started",
                                id = nextId,
                                started = form["started"]?.trim().orEmpty(),
                            ),
                        )
                    }
                }
                call.respondRedirect("/app/assigned-tasks")
            }
            post("/app/team/assign") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access !in MANAGE_ROLES) {
                    return@post call.respondRedirect("/app/team")
                }
                val form = call.receiveParameters()
                val title = form["title"]?.trim().orEmpty()
                val assignee = form["assignee"]?.trim().orEmpty()
                val project = form["project"]?.trim().orEmpty()
                val validAssignee = DemoData.team.any { it.employeeNumber == assignee }
                val validProject = project.isEmpty() || DemoData.projects.any { it.name == project }
                if (title.isNotEmpty() && validAssignee && validProject) {
                    JsonStore.update { data ->
                        val nextId = (data.assignedTasks.maxOfOrNull { it.id } ?: 0) + 1
                        data.copy(
                            assignedTasks = data.assignedTasks + TaskItem(
                                title = title,
                                project = project,
                                due = form["due"]?.trim().orEmpty(),
                                priority = form["priority"]?.trim()?.takeIf { it in listOf("High", "Medium", "Low") } ?: "Medium",
                                assignee = assignee,
                                status = "Not started",
                                id = nextId,
                                started = form["started"]?.trim().orEmpty(),
                            ),
                        )
                    }
                }
                call.respondRedirect("/app/team")
            }
            post("/app/tasks/status") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val form = call.receiveParameters()
                val id = form["id"]?.toIntOrNull()
                val status = form["status"]?.trim()?.takeIf { it in TASK_STATUSES }
                val canManage = DemoData.userFor(session.employeeNumber).access in MANAGE_ROLES
                val task = id?.let { tid -> DemoData.assignedTasks.firstOrNull { it.id == tid } }
                if (task != null && status != null && (canManage || task.assignee == session.employeeNumber)) {
                    JsonStore.update { data ->
                        data.copy(assignedTasks = data.assignedTasks.map { if (it.id == task.id) it.copy(status = status) else it })
                    }
                }
                val back = call.request.headers[HttpHeaders.Referrer] ?: "/app/assigned-tasks"
                call.respondRedirect(back)
            }
            post("/app/other-tasks") {
                if (call.currentSession() == null) return@post call.respondRedirect("/")
                val form = call.receiveParameters()
                val title = form["title"]?.trim().orEmpty()
                val bucket = form["bucket"]?.trim().orEmpty()
                if (title.isNotEmpty() && bucket.isNotEmpty()) {
                    JsonStore.update { data ->
                        val nextId = (data.otherTasks.maxOfOrNull { it.id } ?: 0) + 1
                        data.copy(
                            otherTasks = data.otherTasks + TaskItem(
                                title = title,
                                project = "",
                                due = form["due"]?.trim().orEmpty(),
                                priority = form["priority"]?.trim()?.takeIf { it in listOf("High", "Medium", "Low") } ?: "Medium",
                                status = "Not started",
                                id = nextId,
                                bucket = bucket,
                                started = form["started"]?.trim().orEmpty(),
                            ),
                        )
                    }
                }
                call.respondRedirect("/app/assigned-tasks")
            }
            post("/app/other-buckets") {
                if (call.currentSession() == null) return@post call.respondRedirect("/")
                val name = call.receiveParameters()["name"]?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    JsonStore.update { data ->
                        if (data.buckets.contains(name)) data else data.copy(buckets = data.buckets + name)
                    }
                }
                call.respondRedirect("/app/assigned-tasks")
            }
            post("/app/access-control") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val caller = DemoData.userFor(session.employeeNumber)
                if (caller.access !in MANAGE_ROLES) {
                    return@post call.respondRedirect("/app/access-control")
                }
                // Only admins may set positions, access levels and passwords; managers add basic users.
                val canSetRoles = caller.access == "Admin"
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val name = form["name"]?.trim().orEmpty()
                val email = form["email"]?.trim()?.lowercase().orEmpty()
                val newPassword = if (canSetRoles) form["password"]?.trim().orEmpty() else ""
                if (canSetRoles && newPassword.isNotEmpty() && newPassword.length < MIN_PASSWORD_LENGTH) {
                    return@post call.respondRedirect("/app/access-control?pw=${PasswordStatus.TOO_SHORT.name}&who=$employeeNumber")
                }
                val status = when {
                    employeeNumber.isEmpty() || name.isEmpty() -> PasswordStatus.EMPTY
                    DemoData.access.any { it.employeeNumber == employeeNumber } -> PasswordStatus.USER_EXISTS
                    else -> {
                        // Position defaults to the member's team-roster position, "Team Member" otherwise.
                        val rosterPosition = DemoData.team.firstOrNull { it.employeeNumber == employeeNumber }?.position
                        val requestedPosition = if (canSetRoles) form["position"]?.trim()?.takeIf { it.isNotEmpty() } else null
                        val requestedAccess = if (canSetRoles) form["access"]?.trim()?.takeIf { it in ACCESS_LEVELS } else null
                        JsonStore.update { data ->
                            val row = AccessRow(
                                employeeNumber = employeeNumber,
                                name = name,
                                position = requestedPosition ?: rosterPosition ?: "Team Member",
                                access = requestedAccess ?: "Viewer",
                                password = newPassword,
                                email = email.ifBlank { autoEmail(name) },
                            )
                            // Put the new account to use straight away: roster it on the team
                            // unless the employee number is already listed there.
                            val nextTeam = if (data.team.any { it.employeeNumber == employeeNumber }) {
                                data.team
                            } else {
                                data.team + Member(employeeNumber = employeeNumber, name = name, department = "General", position = row.position)
                            }
                            data.copy(access = data.access + row, team = nextTeam)
                        }
                        PasswordStatus.USER_ADDED
                    }
                }
                call.respondRedirect("/app/access-control?pw=${status.name}&who=$employeeNumber")
            }
            post("/app/access-control/delete") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val caller = DemoData.userFor(session.employeeNumber)
                if (caller.access !in MANAGE_ROLES) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val target = DemoData.access.firstOrNull { it.employeeNumber == employeeNumber }
                val onTeam = DemoData.team.any { it.employeeNumber == employeeNumber }
                val status = when {
                    employeeNumber.isEmpty() -> PasswordStatus.EMPTY
                    target == null && !onTeam -> PasswordStatus.USER_NOT_FOUND
                    employeeNumber == "EMP0000" -> PasswordStatus.CANNOT_DELETE_SUPER
                    employeeNumber == session.employeeNumber -> PasswordStatus.CANNOT_DELETE_SELF
                    caller.access == "Manager" && target?.access == "Admin" -> PasswordStatus.CANNOT_DELETE_ADMIN
                    else -> {
                        JsonStore.update { data ->
                            data.copy(
                                access = data.access.filterNot { it.employeeNumber == employeeNumber },
                                team = data.team.filterNot { it.employeeNumber == employeeNumber },
                            )
                        }
                        PasswordStatus.USER_DELETED
                    }
                }
                call.respondRedirect("/app/access-control?pw=${status.name}&who=$employeeNumber")
            }
            post("/app/access-control/password") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access != "Admin") {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val newPassword = form["password"].orEmpty()
                val status = when {
                    employeeNumber.isEmpty() || newPassword.isEmpty() -> PasswordStatus.EMPTY
                    DemoData.access.none { it.employeeNumber == employeeNumber } -> PasswordStatus.USER_NOT_FOUND
                    newPassword.length < MIN_PASSWORD_LENGTH -> PasswordStatus.TOO_SHORT
                    else -> {
                        JsonStore.update { data ->
                            data.copy(
                                access = data.access.map { row ->
                                    if (row.employeeNumber != employeeNumber) row else row.copy(password = newPassword)
                                },
                            )
                        }
                        PasswordStatus.CHANGED
                    }
                }
                call.respondRedirect("/app/access-control?pw=${status.name}&who=$employeeNumber")
            }
            post("/app/access-control/update") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access != "Admin") {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val newPosition = form["position"]?.trim().orEmpty()
                val newAccess = form["access"]?.trim().orEmpty()
                if (employeeNumber.isNotEmpty()) {
                    JsonStore.update { data ->
                        data.copy(
                            access = data.access.map { row ->
                                if (row.employeeNumber != employeeNumber) return@map row
                                row.copy(
                                    position = newPosition.ifEmpty { row.position },
                                    access = newAccess.takeIf { it in ACCESS_LEVELS } ?: row.access,
                                )
                            },
                        )
                    }
                }
                call.respondRedirect("/app/access-control")
            }

            post("/app/todo/lists") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val form = call.receiveParameters()
                val name = form["name"]?.trim().orEmpty()
                val due = form["due"]?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    JsonStore.update { data ->
                        val nextId = (data.todoLists.maxOfOrNull { it.id } ?: 0) + 1
                        data.copy(todoLists = data.todoLists + TodoList(nextId, name, owner = session.employeeNumber, due = due))
                    }
                }
                call.respondRedirect("/app/todo")
            }

            post("/app/todo/lists/{id}/delete") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val emp = session.employeeNumber
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data ->
                        if (data.todoLists.none { it.id == id && it.owner == emp }) return@update data
                        data.copy(
                            todoLists = data.todoLists.filterNot { it.id == id },
                            todos = data.todos.filterNot { it.list == id && it.owner == emp },
                        )
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/lists/{id}/toggle") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val emp = session.employeeNumber
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data ->
                        data.copy(todoLists = data.todoLists.map { if (it.id == id && it.owner == emp) it.copy(done = !it.done) else it })
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/lists/{id}/move") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val emp = session.employeeNumber
                val dir = call.receiveParameters()["dir"].orEmpty()
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data ->
                        val mine = data.todoLists.filter { it.owner == emp }
                        val from = mine.indexOfFirst { it.id == id }
                        val to = if (dir == "up") from - 1 else from + 1
                        if (from < 0 || to !in mine.indices) return@update data
                        val reordered = mine.toMutableList()
                        reordered[from] = mine[to]
                        reordered[to] = mine[from]
                        data.copy(todoLists = reordered + data.todoLists.filterNot { it.owner == emp })
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/lists/reorder") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val emp = session.employeeNumber
                val ids = call.receiveParameters()["order"].orEmpty()
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                if (ids.isNotEmpty()) {
                    JsonStore.update { data ->
                        val byId = data.todoLists.associateBy { it.id }
                        val requested = ids.distinct().mapNotNull { byId[it] }.filter { it.owner == emp }
                        if (requested.isEmpty()) return@update data
                        val movedIds = requested.map { it.id }.toSet()
                        data.copy(
                            todoLists = requested +
                                data.todoLists.filter { it.owner == emp && it.id !in movedIds } +
                                data.todoLists.filterNot { it.owner == emp },
                        )
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val emp = session.employeeNumber
                val form = call.receiveParameters()
                val title = form["title"]?.trim().orEmpty()
                val requested = form["list"]?.toIntOrNull() ?: 1
                val due = form["due"]?.trim().orEmpty()
                if (title.isNotEmpty()) {
                    JsonStore.update { data ->
                        val mine = data.todoLists.filter { it.owner == emp }
                        val listId = mine.firstOrNull { it.id == requested }?.id ?: mine.firstOrNull()?.id
                        if (listId == null) return@update data
                        val nextId = (data.todos.maxOfOrNull { it.id } ?: 0) + 1
                        data.copy(todos = data.todos + TodoItem(id = nextId, title = title, done = false, list = listId, owner = emp, due = due))
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/lists/{id}/due") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val emp = session.employeeNumber
                val due = call.receiveParameters()["due"]?.trim().orEmpty()
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data ->
                        data.copy(todoLists = data.todoLists.map { if (it.id == id && it.owner == emp) it.copy(due = due) else it })
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/{id}/due") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val emp = session.employeeNumber
                val due = call.receiveParameters()["due"]?.trim().orEmpty()
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data ->
                        data.copy(todos = data.todos.map { if (it.id == id && it.owner == emp) it.copy(due = due) else it })
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/{id}/toggle") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val emp = session.employeeNumber
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data ->
                        data.copy(todos = data.todos.map { if (it.id == id && it.owner == emp) it.copy(done = !it.done) else it })
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/{id}/delete") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val emp = session.employeeNumber
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data -> data.copy(todos = data.todos.filterNot { it.id == id && it.owner == emp }) }
                }
                call.respondRedirect("/app/todo")
            }

            post("/app/positions") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access != "Admin") {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val name = form["name"]?.trim().orEmpty()
                val permissionLevel = form["permissionLevel"]?.trim()?.takeIf { it in PERMISSION_LEVELS } ?: "Viewer"
                val description = form["description"]?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    var created: Position? = null
                    JsonStore.update { data ->
                        val pos = Position(
                            id = (data.positions.maxOfOrNull { it.id } ?: 0) + 1,
                            name = name,
                            permissionLevel = permissionLevel,
                            description = description,
                        )
                        created = pos
                        data.copy(positions = data.positions + pos)
                    }
                    if (call.isFetch()) {
                        val html = createHTML().tr { positionRow(created!!, canAdmin = true) }
                        call.respondText(html, io.ktor.http.ContentType.Text.Html)
                        return@post
                    }
                }
                call.respondRedirect("/app/access-control")
            }

            post("/app/positions/update") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access != "Admin") {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val id = form["id"]?.toIntOrNull()
                val newName = form["name"]?.trim().orEmpty()
                val newPermissionLevel = form["permissionLevel"]?.trim().orEmpty()
                val newDescription = form["description"]?.trim().orEmpty()
                if (id != null) {
                    JsonStore.update { data ->
                        data.copy(
                            positions = data.positions.map { pos ->
                                if (pos.id != id) return@map pos
                                pos.copy(
                                    name = newName.ifEmpty { pos.name },
                                    permissionLevel = newPermissionLevel.takeIf { it in PERMISSION_LEVELS } ?: pos.permissionLevel,
                                    description = newDescription,
                                )
                            },
                        )
                    }
                    if (call.isFetch()) {
                        val row = JsonStore.data().positions.firstOrNull { it.id == id }
                        if (row == null) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respondText(createHTML().tr { positionRow(row, canAdmin = true) }, io.ktor.http.ContentType.Text.Html)
                        }
                        return@post
                    }
                }
                call.respondRedirect("/app/access-control")
            }

            post("/app/positions/delete") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access != "Admin") {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val id = form["id"]?.toIntOrNull()
                if (id != null) {
                    JsonStore.update { data ->
                        data.copy(positions = data.positions.filterNot { it.id == id })
                    }
                    if (call.isFetch()) {
                        call.respond(HttpStatusCode.NoContent)
                        return@post
                    }
                }
                call.respondRedirect("/app/access-control")
            }

            post("/app/projects/{id}/pin") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                val projectId = call.parameters["id"]?.toIntOrNull()
                if (projectId != null) {
                    val emp = session.employeeNumber
                    JsonStore.update { data ->
                        if (data.projects.none { it.id == projectId }) return@update data
                        val current = data.pins[emp].orEmpty()
                        val next = if (projectId in current) current - projectId else current + projectId
                        data.copy(pins = data.pins + (emp to next))
                    }
                }
                val back = call.request.headers[HttpHeaders.Referrer] ?: "/app/projects"
                call.respondRedirect(back)
            }
        }
    }
    println("Murray PTS is running on port $port (bound to $host).")
    println("  On this PC:    http://127.0.0.1:$port")
    if (!host.contains("127.0.0.1") && host != "localhost") {
        lanAddresses().forEach { ip -> println("  Other devices: http://$ip:$port") }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down: closing database pool")
        DatabaseFactory.close()
    })
    server.start(wait = true)
}

private fun lanAddresses(): List<String> =
    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif -> java.util.Collections.list(nif.inetAddresses).asSequence() }
        .filterIsInstance<java.net.Inet4Address>()
        .map { it.hostAddress }
        .filter { it != "127.0.0.1" }
        .toList()

private fun io.ktor.server.routing.Route.sectionRoute(
    path: String,
    section: String,
    title: String,
    content: DIV.(UserSession) -> Unit,
) {
    get(path) {
        val session = call.currentSession() ?: return@get call.respondRedirect("/")
        call.respondHtml { appPage(session, section, title, content) }
    }
}

private suspend fun ApplicationCall.respondLogin(error: String?) =
    respondHtml(if (error == null) HttpStatusCode.OK else HttpStatusCode.Unauthorized) {
        loginPage(error)
    }

fun ApplicationCall.currentSession(): UserSession? =
    request.cookies[SESSION_COOKIE]?.let { SessionStore.sessions[it] }
