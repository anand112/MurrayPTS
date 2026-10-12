package mn.murray.pts
import mn.murray.pts.domain.*
import mn.murray.pts.config.AppConfig
import mn.murray.pts.db.DatabaseFactory
import mn.murray.pts.db.migration.DemoSeeder
import mn.murray.pts.db.migration.JsonImporter
import mn.murray.pts.service.AuthService
import mn.murray.pts.service.AuthResult
import mn.murray.pts.service.DueDateAlertScheduler
import mn.murray.pts.service.DueDateAlertService
import mn.murray.pts.service.MailResult
import mn.murray.pts.service.ProjectService
import mn.murray.pts.service.TaskService
import mn.murray.pts.service.TodoService
import mn.murray.pts.service.UserService
import mn.murray.pts.security.Authorization
import mn.murray.pts.web.plugin.SecurityHeaders

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.path
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import kotlinx.html.DIV
import kotlinx.html.stream.createHTML
import kotlinx.html.tr

/**
 * Session cookie name, resolved once at boot from [AppConfig] (default `PTS_SID`).
 * It used to be a `const`, which meant `PTS_SESSION_COOKIE` silently did nothing.
 */
private val SESSION_COOKIE: String = AppConfig.sessionCookieName

/** True when the request came from the page's fetch() handler, which expects fragments instead of redirects. */
private fun ApplicationCall.isFetch(): Boolean = request.headers["X-Requested-With"] == "fetch"

/**
 * Best-effort client IP for rate limiting.
 *
 * `X-Forwarded-For` is only consulted when the operator declares a proxy (PTS_TRUST_PROXY=true).
 * Reading it unconditionally would let anyone on a directly exposed port send a fresh header per
 * attempt and never trip the limiter, so the header is treated as attacker-supplied by default.
 */
private fun ApplicationCall.clientIp(): String {
    val forwarded = if (AppConfig.trustProxy) {
        request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }
    } else null
    return forwarded ?: request.origin.remoteHost
}

/**
 * The signed-in user for one request.
 *
 * [mustChangePassword] is read with the session on every request so the route guard can
 * enforce "set a real password before you see the app" — the flag exists because imported
 * accounts whose password defaulted to their employee number, and a super admin bootstrapped
 * without `PTS_ADMIN_PASSWORD`, are not safe to leave working.
 */
class UserSession(val employeeNumber: String, val mustChangePassword: Boolean = false)

/**
 * Issue the session cookie for an authenticated [token]. Name stays `PTS_SID` (legacy
 * compatibility); httpOnly always, Secure when configured (production default), SameSite=Lax
 * to blunt CSRF while still allowing top-level form navigation.
 */
private fun ApplicationCall.setSessionCookie(token: String) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = token,
            path = "/",
            httpOnly = true,
            secure = AppConfig.secureCookies,
            extensions = mapOf("SameSite" to "Lax"),
        )
    )
}

private val log = LoggerFactory.getLogger("mn.murray.pts.Application")

/**
 * Prepare persistence for the v2 SQLite/Exposed stack.
 *
 * Creates and migrates the database, performs the one-shot legacy JSON import, optionally
 * seeds the demo dataset when `PTS_SEED_DEMO=true`, and guarantees a super-admin account
 * exists. As of Stage 6 every route reads and writes through the service/repository layers
 * backed by SQLite; the legacy JSON file is only ever consulted once, by [JsonImporter],
 * and is then archived.
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
        // Opt-in only: an unconfigured production database stays empty rather than filling with demo rows.
        DemoSeeder.seedIfEnabled()
    }
    // Guarantee an admin account exists even on a fresh DB with no legacy JSON.
    AuthService.ensureSuperAdmin()
    // Non-fatal deployment advisories: surfaced every boot so a misconfigured VPS is visible
    // in the log rather than discovered by a user who cannot stay logged in.
    AppConfig.warnings().forEach { log.warn("Launch check: {}", it) }
}

fun main() {
    bootstrapDatabase()
    val port = AppConfig.port
    val host = AppConfig.host
    val server = embeddedServer(Netty, port = port, host = host) {
        install(SecurityHeaders)
        // Only meaningful (and only safe) behind a proxy we trust — see AppConfig.trustProxy.
        // Lets Ktor read X-Forwarded-For/-Host/-Proto so redirects, the client IP used for
        // rate limiting, and `call.request.origin` stay correct behind nginx/Traefik.
        if (AppConfig.trustProxy) install(XForwardedHeaders)
        if (AppConfig.logRequests) {
            install(CallLogging) {
                disableDefaultColors() // the log file is read by grep, not by a terminal
                disableForStaticContent() // CSS/JS/favicon noise would bury the route audit trail
            }
        }
        routing {
            staticResources("/static", "static")
            // Stage 7 moved the logo under /static/assets/; keep the legacy path resolving.
            get("/static/mms-logo.png") {
                call.respondRedirect("/static/assets/mms-logo.png", permanent = true)
            }
            get("/") {
                if (call.currentSession() != null) {
                    call.respondRedirect("/app")
                } else {
                    call.respondLogin(error = null)
                }
            }
            post("/login") {
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val password = form["password"].orEmpty()
                val ua = call.request.headers[HttpHeaders.UserAgent].orEmpty()
                when (val result = AuthService.authenticate(employeeNumber, password, call.clientIp(), ua)) {
                    is AuthResult.Success -> {
                        call.setSessionCookie(result.token)
                        call.respondRedirect("/app")
                    }
                    is AuthResult.Throttled ->
                        call.respondLogin(error = "Too many failed attempts. Try again in ${result.retryAfterSeconds}s.")
                    is AuthResult.Failure ->
                        call.respondLogin(error = result.message)
                }
            }
            get("/logout") {
                AuthService.logout(call.request.cookies[SESSION_COOKIE])
                call.response.cookies.append(
                    Cookie(SESSION_COOKIE, "", expires = GMTDate.START, path = "/", httpOnly = true)
                )
                call.respondRedirect("/")
            }
            get("/app") { call.respondRedirect("/app/projects") }

            get("/app/projects") {
                val session = call.requireSession() ?: return@get
                val type = call.request.queryParameters["type"]?.takeIf { it in PROJECT_TYPES }
                call.respondHtml { appPage(session, "projects", "Projects") { projectsContent(it, type) } }
            }
            get("/app/projects/{id}") {
                val session = call.requireSession() ?: return@get
                val project = call.parameters["id"]?.toIntOrNull()?.let { id -> DemoData.projects.firstOrNull { it.id == id } }
                if (project == null) return@get call.respondRedirect("/app/projects")
                val canManage = Authorization.canManage(session.employeeNumber)
                call.respondHtml { appPage(session, "projects", project.name) { projectDetailContent(it, project, canManage) } }
            }
            get("/app/assigned-tasks") {
                val session = call.requireSession() ?: return@get
                val canManage = Authorization.canManage(session.employeeNumber)
                call.respondHtml { appPage(session, "assigned-tasks", "Assigned tasks") { assignedTasksContent(session, canManage) } }
            }
            sectionRoute("/app/calendar", "calendar", "Calendar") { calendarContent(it) }
            sectionRoute("/app/team", "team", "Team") {
                teamContent(canManage = Authorization.canManage(it.employeeNumber))
            }
            sectionRoute("/app/forecasts", "forecasts", "Forecasts") { forecastsContent() }
            sectionRoute("/app/lessons", "lessons", "Lesson learnt") { lessonsContent() }
            sectionRoute("/app/other-tasks", "other-tasks", "Other tasks") { tasksContent(DemoData.otherTasks, "Unassigned backlog") }
            sectionRoute("/app/todo", "todo", "To-do") { todoContent(it) }
            get("/app/access-control") {
                val session = call.requireSession() ?: return@get
                val canAdmin = Authorization.canAdmin(session.employeeNumber)
                val canManage = Authorization.canManage(session.employeeNumber)
                val status = PasswordStatus.fromCode(call.request.queryParameters["pw"])
                val detail = call.request.queryParameters["who"]
                call.respondHtml { appPage(session, "access-control", "Access control") { accessContent(canAdmin, canManage, session.employeeNumber, status, detail) } }
            }
            get("/app/settings") {
                val session = call.requireSession() ?: return@get
                val status = PasswordStatus.fromCode(call.request.queryParameters["pw"])
                call.respondHtml { appPage(session, "settings", "Settings") { settingsContent(it, status) } }
            }

            post("/app/settings/password") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val status = UserService.changeOwnPassword(
                    employeeNumber = session.employeeNumber,
                    current = form["currentPassword"].orEmpty(),
                    new = form["newPassword"].orEmpty(),
                    confirm = form["confirmPassword"].orEmpty(),
                )
                if (status == PasswordStatus.CHANGED) {
                    // changeOwnPassword revoked all sessions; re-issue one so the user stays signed in.
                    val ua = call.request.headers[HttpHeaders.UserAgent].orEmpty()
                    call.setSessionCookie(AuthService.reissue(session.employeeNumber, call.clientIp(), ua))
                }
                call.respondRedirect("/app/settings?pw=${status.name}")
            }

            post("/app/settings/email") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val canAdmin = Authorization.canAdmin(session.employeeNumber)
                if (employeeNumber.isNotEmpty() && (canAdmin || employeeNumber == session.employeeNumber)) {
                    UserService.updateEmail(employeeNumber, form["email"].orEmpty())
                }
                call.respondRedirect("/app/settings")
            }

            post("/app/projects") {
                if (call.requireSession() == null) return@post
                val form = call.receiveParameters()
                ProjectService.create(
                    name = form["name"]?.trim().orEmpty(),
                    type = form["type"]?.trim().orEmpty(),
                    status = form["status"]?.trim().orEmpty(),
                    progress = form["progress"]?.toIntOrNull() ?: 0,
                    managerName = form["manager"]?.trim().orEmpty(),
                    due = form["due"]?.trim().orEmpty(),
                )
                call.respondRedirect("/app/projects")
            }
            post("/app/projects/{id}/manager") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/projects")
                }
                val id = call.parameters["id"]?.toIntOrNull()
                val manager = call.receiveParameters()["manager"]?.trim().orEmpty()
                if (id != null) ProjectService.setManager(id, manager)
                call.respondRedirect(if (id == null) "/app/projects" else "/app/projects/$id")
            }
            post("/app/projects/{id}/assignees/add") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/projects")
                }
                val id = call.parameters["id"]?.toIntOrNull()
                val emp = call.receiveParameters()["assignee"]?.trim().orEmpty()
                if (id != null) ProjectService.addAssignee(id, emp)
                call.respondRedirect(if (id == null) "/app/projects" else "/app/projects/$id")
            }
            post("/app/projects/{id}/assignees/remove") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/projects")
                }
                val id = call.parameters["id"]?.toIntOrNull()
                val emp = call.receiveParameters()["assignee"]?.trim().orEmpty()
                if (id != null) ProjectService.removeAssignee(id, emp)
                call.respondRedirect(if (id == null) "/app/projects" else "/app/projects/$id")
            }
            post("/app/assigned-tasks") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/assigned-tasks")
                }
                val form = call.receiveParameters()
                TaskService.createAssigned(
                    title = form["title"]?.trim().orEmpty(),
                    projectName = form["project"]?.trim().orEmpty(),
                    due = form["due"]?.trim().orEmpty(),
                    priority = form["priority"]?.trim().orEmpty(),
                    assigneeEmpNo = form["assignee"]?.trim().orEmpty(),
                    started = form["started"]?.trim().orEmpty(),
                )
                call.respondRedirect("/app/assigned-tasks")
            }
            post("/app/team/assign") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/team")
                }
                val form = call.receiveParameters()
                TaskService.createAssigned(
                    title = form["title"]?.trim().orEmpty(),
                    projectName = form["project"]?.trim().orEmpty(),
                    due = form["due"]?.trim().orEmpty(),
                    priority = form["priority"]?.trim().orEmpty(),
                    assigneeEmpNo = form["assignee"]?.trim().orEmpty(),
                    started = form["started"]?.trim().orEmpty(),
                    requireValidProject = true,
                )
                call.respondRedirect("/app/team")
            }
            post("/app/tasks/status") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val id = form["id"]?.toIntOrNull()
                val status = form["status"]?.trim().orEmpty()
                val canManage = Authorization.canManage(session.employeeNumber)
                if (id != null) {
                    TaskService.updateStatus(id, status, session.employeeNumber, canManage)
                }
                val back = call.request.headers[HttpHeaders.Referrer] ?: "/app/assigned-tasks"
                call.respondRedirect(back)
            }
            post("/app/other-tasks") {
                if (call.requireSession() == null) return@post
                val form = call.receiveParameters()
                TaskService.createOther(
                    title = form["title"]?.trim().orEmpty(),
                    bucketName = form["bucket"]?.trim().orEmpty(),
                    due = form["due"]?.trim().orEmpty(),
                    priority = form["priority"]?.trim().orEmpty(),
                    started = form["started"]?.trim().orEmpty(),
                )
                call.respondRedirect("/app/assigned-tasks")
            }
            post("/app/other-buckets") {
                if (call.requireSession() == null) return@post
                TaskService.addBucket(call.receiveParameters()["name"]?.trim().orEmpty())
                call.respondRedirect("/app/assigned-tasks")
            }
            post("/app/access-control") {
                val session = call.requireSession() ?: return@post
                val callerEmp = session.employeeNumber
                if (!Authorization.canManage(callerEmp)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                // Only admins may set positions, access levels and passwords; managers add basic users.
                val canSetRoles = Authorization.canAdmin(callerEmp)
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val name = form["name"]?.trim().orEmpty()
                val email = form["email"].orEmpty()
                val newPassword = if (canSetRoles) form["password"]?.trim().orEmpty() else ""
                if (canSetRoles && newPassword.isNotEmpty() && newPassword.length < MIN_PASSWORD_LENGTH) {
                    return@post call.respondRedirect("/app/access-control?pw=${PasswordStatus.TOO_SHORT.name}&who=$employeeNumber")
                }
                val status = UserService.addUser(
                    employeeNumber = employeeNumber,
                    name = name,
                    email = email,
                    requestedPosition = if (canSetRoles) form["position"]?.trim() else null,
                    requestedAccess = if (canSetRoles) form["access"]?.trim() else null,
                    newPassword = newPassword,
                    canSetRoles = canSetRoles,
                )
                call.respondRedirect("/app/access-control?pw=${status.name}&who=$employeeNumber")
            }
            post("/app/access-control/delete") {
                val session = call.requireSession() ?: return@post
                val callerEmp = session.employeeNumber
                if (!Authorization.canManage(callerEmp)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val status = UserService.deleteUser(employeeNumber, callerEmp, Authorization.effectiveRole(callerEmp))
                call.respondRedirect("/app/access-control?pw=${status.name}&who=$employeeNumber")
            }
            post("/app/access-control/password") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canAdmin(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val status = UserService.setPasswordByAdmin(employeeNumber, form["password"].orEmpty())
                call.respondRedirect("/app/access-control?pw=${status.name}&who=$employeeNumber")
            }
            post("/app/access-control/update") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canAdmin(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                UserService.updateUserAccess(
                    employeeNumber = employeeNumber,
                    positionName = form["position"]?.trim().orEmpty(),
                    access = form["access"]?.trim().orEmpty(),
                )
                call.respondRedirect("/app/access-control")
            }

            post("/app/todo/lists") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                TodoService.createList(
                    empNo = session.employeeNumber,
                    name = form["name"]?.trim().orEmpty(),
                    due = form["due"]?.trim().orEmpty(),
                )
                call.respondRedirect("/app/todo")
            }

            post("/app/todo/lists/{id}/delete") {
                val session = call.requireSession() ?: return@post
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    TodoService.deleteList(session.employeeNumber, id)
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/lists/{id}/toggle") {
                val session = call.requireSession() ?: return@post
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    TodoService.toggleList(session.employeeNumber, id)
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/lists/{id}/move") {
                val session = call.requireSession() ?: return@post
                val dir = call.receiveParameters()["dir"].orEmpty()
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    TodoService.moveList(session.employeeNumber, id, up = dir == "up")
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/lists/reorder") {
                val session = call.requireSession() ?: return@post
                val ids = call.receiveParameters()["order"].orEmpty()
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                TodoService.reorderLists(session.employeeNumber, ids)
                call.respondRedirect("/app/todo")
            }
            post("/app/todo") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                TodoService.createItem(
                    empNo = session.employeeNumber,
                    title = form["title"]?.trim().orEmpty(),
                    requestedListId = form["list"]?.toIntOrNull() ?: 1,
                    due = form["due"]?.trim().orEmpty(),
                )
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/lists/{id}/due") {
                val session = call.requireSession() ?: return@post
                val due = call.receiveParameters()["due"]?.trim().orEmpty()
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    TodoService.setListDue(session.employeeNumber, id, due)
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/{id}/due") {
                val session = call.requireSession() ?: return@post
                val due = call.receiveParameters()["due"]?.trim().orEmpty()
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    TodoService.setItemDue(session.employeeNumber, id, due)
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/{id}/toggle") {
                val session = call.requireSession() ?: return@post
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    TodoService.toggleItem(session.employeeNumber, id)
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/{id}/delete") {
                val session = call.requireSession() ?: return@post
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    TodoService.deleteItem(session.employeeNumber, id)
                }
                call.respondRedirect("/app/todo")
            }

            post("/app/positions") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canAdmin(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val name = form["name"]?.trim().orEmpty()
                val permissionLevel = form["permissionLevel"]?.trim()?.takeIf { it in PERMISSION_LEVELS } ?: "Viewer"
                val description = form["description"]?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val created = UserService.addPosition(name, permissionLevel, description)
                    if (call.isFetch() && created != null) {
                        val html = createHTML().tr { positionRow(created, canAdmin = true) }
                        call.respondText(html, io.ktor.http.ContentType.Text.Html)
                        return@post
                    }
                }
                call.respondRedirect("/app/access-control")
            }

            post("/app/positions/update") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canAdmin(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val id = form["id"]?.toIntOrNull()
                val newName = form["name"]?.trim().orEmpty()
                val newPermissionLevel = form["permissionLevel"]?.trim().orEmpty()
                val newDescription = form["description"]?.trim().orEmpty()
                if (id != null) {
                    val row = UserService.updatePosition(id, newName, newPermissionLevel, newDescription)
                    if (call.isFetch()) {
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
                val session = call.requireSession() ?: return@post
                if (!Authorization.canAdmin(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val id = call.receiveParameters()["id"]?.toIntOrNull()
                if (id != null) {
                    UserService.deletePosition(id)
                    if (call.isFetch()) {
                        call.respond(HttpStatusCode.NoContent)
                        return@post
                    }
                }
                call.respondRedirect("/app/access-control")
            }

            post("/app/projects/{id}/pin") {
                val session = call.requireSession() ?: return@post
                val projectId = call.parameters["id"]?.toIntOrNull()
                if (projectId != null) {
                    ProjectService.togglePin(session.employeeNumber, projectId)
                }
                val back = call.request.headers[HttpHeaders.Referrer] ?: "/app/projects"
                call.respondRedirect(back)
            }

            // --- due-date alerts: operator triggers -----------------------------------
            // Plain text, not HTML, on purpose: these exist for the launch checklist and for
            // `curl -b cookie.txt -X POST .../app/alerts/run`, so the sweep's own report is what
            // you read. Users never see them; only Admins pass the guard.
            post("/app/alerts/run") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canAdmin(session.employeeNumber)) {
                    return@post call.respondText(
                        "Admin role required to run the due-date alert sweep.",
                        io.ktor.http.ContentType.Text.Plain,
                        HttpStatusCode.Forbidden,
                    )
                }
                // Blocking SMTP/SQLite work, so it runs off the Netty event loop.
                val summary = withContext(Dispatchers.IO) { DueDateAlertService.runSweep() }
                call.respondText(
                    summary.renderDetail(),
                    io.ktor.http.ContentType.Text.Plain,
                )
            }
            get("/app/alerts/recent") {
                val session = call.requireSession() ?: return@get
                if (!Authorization.canAdmin(session.employeeNumber)) {
                    return@get call.respondText(
                        "Admin role required to read the alert ledger.",
                        io.ktor.http.ContentType.Text.Plain,
                        HttpStatusCode.Forbidden,
                    )
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val rows = DueDateAlertService.recent(limit)
                val text = buildString {
                    appendLine("mode = ${DueDateAlertService.currentMode()}")
                    DueDateAlertService.lastSummary()?.let { appendLine("last sweep: ${it.render()}") }
                    appendLine("ledger (newest first, ${rows.size} row(s))")
                    rows.forEach {
                        appendLine(
                            "#${it.id} ${it.alertDate} ${it.kind} ${it.itemId} -> ${it.recipientEmpNo} " +
                                "due=${it.due} ${if (it.delivered) "delivered" else "NOT delivered"} " +
                                "[${it.channel}] ${it.subject}" +
                                if (it.error.isBlank()) "" else " :: ${it.error}"
                        )
                    }
                }
                call.respondText(text, io.ktor.http.ContentType.Text.Plain)
            }

            // One-message delivery test: proves the SMTP wiring end to end without spending the
            // day's alert budget on five people. Writes no ledger row (see DueDateAlertService),
            // so it can be run again immediately after fixing a credential.
            post("/app/alerts/test") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canAdmin(session.employeeNumber)) {
                    return@post call.respondText(
                        "Admin role required to send a mail delivery test.",
                        io.ktor.http.ContentType.Text.Plain,
                        HttpStatusCode.Forbidden,
                    )
                }
                // Defaults to the caller's own address: a mistyped curl should never mail a colleague.
                val to = call.request.queryParameters["to"]?.trim().orEmpty()
                    .ifBlank { DueDateAlertService.emailFor(session.employeeNumber) }
                if (to.isBlank()) {
                    return@post call.respondText(
                        "No recipient: ?to= was empty and account ${session.employeeNumber} has no " +
                            "email on file in Access control.",
                        io.ktor.http.ContentType.Text.Plain,
                        HttpStatusCode.BadRequest,
                    )
                }
                val result = withContext(Dispatchers.IO) { DueDateAlertService.runDeliveryTest(to) }
                val report = when (result) {
                    is MailResult.Sent ->
                        "DELIVERED — the SMTP server accepted the test for $to (id ${result.messageId}).\n" +
                            "Check that inbox; the body names the host and sender that produced it."
                    is MailResult.LoggedOnly ->
                        "NOT SENT — ${result.reason}. Nothing left this machine, so this tells you the " +
                            "config is still in log-only mode (PTS_SMTP_HOST/PTS_SMTP_DRY_RUN).\n" +
                            "mode = ${DueDateAlertService.currentMode()}"
                    is MailResult.Failed ->
                        "FAILED — the server rejected the send to $to.\n" +
                            "reason: ${result.error}\n" +
                            "mode = ${DueDateAlertService.currentMode()}\n" +
                            "For smtp.office365.com this is usually the mailbox password, or Authenticated " +
                            "SMTP being disabled on it (README → Sending through Microsoft 365)."
                }
                call.respondText(report, io.ktor.http.ContentType.Text.Plain)
            }
        }
    }
    println("Murray PTS is running on port $port (bound to $host).")
    println("  On this PC:    http://127.0.0.1:$port")
    if (!host.contains("127.0.0.1") && host != "localhost") {
        lanAddresses().forEach { ip -> println("  Other devices: http://$ip:$port") }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down: stopping the alert scheduler, then closing database pool")
        DueDateAlertScheduler.stop()
        DatabaseFactory.close()
    })
    // Single-node by design (see DueDateAlertScheduler): this process owns the daily sweep, and the
    // ledger dedupes so a restart or a manual run cannot mail anyone twice on the same day.
    DueDateAlertScheduler.start()
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
        val session = call.requireSession() ?: return@get
        call.respondHtml { appPage(session, section, title, content) }
    }
}

private suspend fun ApplicationCall.respondLogin(error: String?) =
    respondHtml(if (error == null) HttpStatusCode.OK else HttpStatusCode.Unauthorized) {
        loginPage(error)
    }

fun ApplicationCall.currentSession(): UserSession? =
    request.cookies[SESSION_COOKIE]
        ?.let { AuthService.resolve(it) }
        ?.let { UserSession(it.employeeNumber, AuthService.mustChangePassword(it.employeeNumber)) }

/**
 * The guard every authenticated route calls, in place of [currentSession].
 *
 * It answers the call itself and returns null so the handler can `?: return@get`:
 *  - no session            -> back to the login page;
 *  - must change password  -> to Settings, where the password form is the only thing that
 *    clears the flag. Settings and its password POST are allowlisted, so the user can always
 *    complete the change; /logout stays reachable because it is not gated through here at all.
 *
 * The redirect target is a page, not an action: a `mustChangePassword` user who bookmarks
 * /app/team simply lands on Settings. That is deliberate — hiding the app behind one form is
 * safer than letting a default-password account read and write real data.
 */
private suspend fun ApplicationCall.requireSession(): UserSession? {
    val session = currentSession() ?: run {
        respondRedirect("/")
        return null
    }
    if (session.mustChangePassword && request.path() !in MUST_CHANGE_ALLOWLIST) {
        respondRedirect(SETTINGS_PATH)
        return null
    }
    return session
}

private const val SETTINGS_PATH = "/app/settings"

/** Paths a `mustChangePassword` account may still use: the Settings page and its password form. */
private val MUST_CHANGE_ALLOWLIST = setOf(SETTINGS_PATH, "$SETTINGS_PATH/password")
