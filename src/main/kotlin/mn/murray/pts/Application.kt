package mn.murray.pts

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import io.ktor.util.date.GMTDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.html.DIV

const val SESSION_COOKIE = "PTS_SID"

val MANAGE_ROLES = setOf("Admin", "Manager")

class UserSession(val employeeNumber: String, val pinned: MutableSet<Int> = mutableSetOf())

object SessionStore {
    val sessions = ConcurrentHashMap<String, UserSession>()
}

fun main() {
    embeddedServer(Netty, port = 8080, host = "127.0.0.1") {
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
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val password = form["password"].orEmpty()
                val user = DemoData.access.firstOrNull { it.employeeNumber == employeeNumber }
                if (employeeNumber.isNotEmpty() && user != null && password == user.effectivePassword()) {
                    val token = UUID.randomUUID().toString()
                    SessionStore.sessions[token] = UserSession(employeeNumber)
                    call.response.cookies.append(Cookie(SESSION_COOKIE, token, httpOnly = true, path = "/"))
                    call.respondRedirect("/app")
                } else {
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
                call.respondHtml { appPage(session, "projects", project.name) { projectDetailContent(it, project) } }
            }
            get("/app/assigned-tasks") {
                val session = call.currentSession() ?: return@get call.respondRedirect("/")
                val canManage = DemoData.userFor(session.employeeNumber).access in MANAGE_ROLES
                call.respondHtml { appPage(session, "assigned-tasks", "Assigned tasks") { assignedTasksContent(session, canManage) } }
            }
            sectionRoute("/app/team", "team", "Team") { teamContent() }
            sectionRoute("/app/forecasts", "forecasts", "Forecasts") { forecastsContent() }
            sectionRoute("/app/lessons", "lessons", "Lesson learnt") { lessonsContent() }
            sectionRoute("/app/other-tasks", "other-tasks", "Other tasks") { tasksContent(DemoData.otherTasks, "Unassigned backlog") }
            sectionRoute("/app/todo", "todo", "To-do") { todoContent() }
            get("/app/access-control") {
                val session = call.currentSession() ?: return@get call.respondRedirect("/")
                val canAdmin = DemoData.userFor(session.employeeNumber).access == "Admin"
                call.respondHtml { appPage(session, "access-control", "Access control") { accessContent(canAdmin) } }
            }
            sectionRoute("/app/settings", "settings", "Settings") { settingsContent(it) }

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
                            ),
                        )
                    }
                }
                call.respondRedirect("/app/assigned-tasks")
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
                if (DemoData.userFor(session.employeeNumber).access != "Admin") {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val name = form["name"]?.trim().orEmpty()
                if (employeeNumber.isNotEmpty() && name.isNotEmpty()) {
                    JsonStore.update { data ->
                        val row = AccessRow(
                            employeeNumber = employeeNumber,
                            name = name,
                            position = form["position"]?.trim()?.takeIf { it.isNotEmpty() } ?: "Team Member",
                            access = form["access"]?.trim()?.takeIf { it in ACCESS_LEVELS } ?: "Viewer",
                            password = form["password"]?.trim().orEmpty(),
                        )
                        data.copy(access = data.access.filterNot { it.employeeNumber == employeeNumber } + row)
                    }
                }
                call.respondRedirect("/app/access-control")
            }
            post("/app/access-control/password") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                if (DemoData.userFor(session.employeeNumber).access != "Admin") {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val newPassword = form["password"].orEmpty()
                if (employeeNumber.isNotEmpty() && newPassword.isNotEmpty()) {
                    JsonStore.update { data ->
                        data.copy(
                            access = data.access.map { row ->
                                if (row.employeeNumber != employeeNumber) row else row.copy(password = newPassword)
                            },
                        )
                    }
                }
                call.respondRedirect("/app/access-control")
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
                if (call.currentSession() == null) return@post call.respondRedirect("/")
                val name = call.receiveParameters()["name"]?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    JsonStore.update { data ->
                        val nextId = (data.todoLists.maxOfOrNull { it.id } ?: 0) + 1
                        data.copy(todoLists = data.todoLists + TodoList(nextId, name))
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/lists/{id}/delete") {
                if (call.currentSession() == null) return@post call.respondRedirect("/")
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data ->
                        data.copy(
                            todoLists = data.todoLists.filterNot { it.id == id },
                            todos = data.todos.filterNot { it.list == id },
                        )
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo") {
                if (call.currentSession() == null) return@post call.respondRedirect("/")
                val form = call.receiveParameters()
                val title = form["title"]?.trim().orEmpty()
                val requested = form["list"]?.toIntOrNull() ?: 1
                if (title.isNotEmpty()) {
                    JsonStore.update { data ->
                        val listId = if (data.todoLists.any { it.id == requested }) requested else (data.todoLists.firstOrNull()?.id ?: 1)
                        val nextId = (data.todos.maxOfOrNull { it.id } ?: 0) + 1
                        data.copy(todos = data.todos + TodoItem(id = nextId, title = title, done = false, list = listId))
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/{id}/toggle") {
                if (call.currentSession() == null) return@post call.respondRedirect("/")
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data ->
                        data.copy(todos = data.todos.map { if (it.id == id) it.copy(done = !it.done) else it })
                    }
                }
                call.respondRedirect("/app/todo")
            }
            post("/app/todo/{id}/delete") {
                if (call.currentSession() == null) return@post call.respondRedirect("/")
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    JsonStore.update { data -> data.copy(todos = data.todos.filterNot { it.id == id }) }
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
                    JsonStore.update { data ->
                        val nextId = (data.positions.maxOfOrNull { it.id } ?: 0) + 1
                        data.copy(
                            positions = data.positions + Position(
                                id = nextId,
                                name = name,
                                permissionLevel = permissionLevel,
                                description = description,
                            ),
                        )
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
                }
                call.respondRedirect("/app/access-control")
            }

            post("/app/projects/{id}/pin") {
                val session = call.currentSession() ?: return@post call.respondRedirect("/")
                call.parameters["id"]?.toIntOrNull()?.let { id ->
                    if (!session.pinned.remove(id)) session.pinned.add(id)
                }
                val back = call.request.headers[HttpHeaders.Referrer] ?: "/app/projects"
                call.respondRedirect(back)
            }
        }
    }.start(wait = true)
}

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
