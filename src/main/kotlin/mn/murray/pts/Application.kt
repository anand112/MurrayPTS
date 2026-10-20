package mn.murray.pts
import mn.murray.pts.domain.*
import mn.murray.pts.config.AppConfig
import mn.murray.pts.db.DatabaseFactory
import mn.murray.pts.db.migration.DemoSeeder
import mn.murray.pts.db.migration.JsonImporter
import mn.murray.pts.service.AuthService
import mn.murray.pts.service.AuthResult
import mn.murray.pts.service.BucketDefaultsService
import mn.murray.pts.service.BucketError
import mn.murray.pts.service.DueDateAlertScheduler
import mn.murray.pts.service.DueDateAlertService
import mn.murray.pts.service.EmployeeFilter
import mn.murray.pts.service.EmployeeService
import mn.murray.pts.service.ForecastDraft
import mn.murray.pts.service.ForecastFilter
import mn.murray.pts.service.ForecastResult
import mn.murray.pts.service.ForecastService
import mn.murray.pts.service.LaneError
import mn.murray.pts.service.LessonFilter
import mn.murray.pts.service.LessonService
import mn.murray.pts.service.MailResult
import mn.murray.pts.service.MailSwitch
import mn.murray.pts.service.NotificationService
import mn.murray.pts.service.ProjectDraft
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
 * The task popup a board page was asked to arrive open, or null for the plain page.
 *
 * Every opener is a GET form onto the page it sits on, so the popup's state lives in the URL and the
 * page itself is the renderer: [app.js] fetches the same route and swaps only `#taskModal`, which is why
 * the branches below are the *whole* of the in-place behaviour and no fetch code is needed in the
 * script. `?new=1` is honoured for whoever may write a card on this page — management anywhere, and on a
 * project page also the account that project names as its manager ([pageProjectId]) — because that is
 * exactly the set of accounts `/save` accepts a create from. It is what the page head's `+ New task` posts,
 * and what a lane's `+ Add task` line posts with `&into=<lane id>` beside it to open the form already in
 * that column. `?edit=<card>` goes through
 * [TaskService.editableBy], so a hand-typed id for somebody else's card comes back as an ordinary board
 * rather than as their task form.
 *
 * A backlog card is the one create that is not gated by [TaskService.mayAddTasks], because writing one is
 * every account's right (see [TaskService.createBacklog]). Its opener posts `?new=1&column=<id>` at the Other
 * tasks board, and that column is the only thing that tells this function which of the two creates the reader
 * asked for — a project page has no backlog line, and neither has the assigned board since Stage 47 took the
 * backlog onto its own page.
 */
private fun openingTaskForm(
    call: ApplicationCall,
    session: UserSession,
    canManage: Boolean,
    pageProjectId: Int = 0,
): TaskForm? {
    val query = call.request.queryParameters
    val isNew = query["new"] != null
    // A project page's popup gets that project's lanes (V7) so its Sub-category picker can offer them in the
    // order the board prints them, extras and all. The general board passes none, and the popup then lists the
    // six every project is created with (Stage 48) — the same names on every board, which is why it can ask the
    // question at all without knowing a project's lane ids.
    val lanes = if (pageProjectId > 0) TaskService.lanesOf(pageProjectId) else emptyList()
    if (isNew && pageProjectId == 0 && canManage) {
        // `?column=<id>` is the column a backlog `+ Add task` line was pressed under, checked against the
        // board's own columns rather than trusted: a hand-typed id is not a place a card can be filed, and
        // an unaimed `?new=1` stays the assigned create this page's head has always posted.
        // Stage 51: writing a *new* card onto the shared backlog is an Other-tasks function management keeps.
        // A Viewer reaches the board only to work their own card, so a hand-typed `?new=1&column=<id>` lands
        // them on their scoped board with the popup shut rather than on a create form their route will not save.
        val columns = TaskService.bucketRows()
        val into = query["column"]?.trim()?.toIntOrNull()?.takeIf { id -> columns.any { it.id == id } }
        if (into != null) return backlogFormOf(canManage, session.employeeNumber, columns, into = into)
    }
    if (isNew && TaskService.mayAddTasks(pageProjectId, session.employeeNumber, canManage)) {
        // `?into=<lane id>` is the lane a `+ Add task` line was pressed under. It is checked against this
        // page's own lanes rather than trusted, so a hand-typed id — another project's lane, a removed one,
        // a word — opens the ordinary blank form instead of selecting a column that is not on this board. The
        // form is filled with the lane's **name**, which is what the picker and the post both work in
        // (Stage 48); the id is only ever the board's way of saying which column was pressed.
        val into = query["into"]?.trim()?.toIntOrNull()?.let { id -> lanes.firstOrNull { it.id == id } }
        return newFormOf(canManage, into = into?.name).copy(lanes = lanes)
    }
    val id = query["edit"]?.trim()?.toIntOrNull() ?: return null
    return TaskService.editableBy(id, session.employeeNumber, canManage)?.let { task ->
        // Which picker this popup has is the row's answer, not the URL's: a backlog card is filed in a column
        // of that board and needs its list to fill the select with, a project card in a lane of its own.
        // Management gets both lists whatever the row says, because their popup can move a card from one board
        // to the other (Stage 48) and the field it lands on has to be chosen before the form is saved.
        val columns = if (task.isBacklog || canManage) TaskService.bucketRows() else emptyList()
        editFormOf(task, canManage, session.employeeNumber, backlogColumns = columns).copy(lanes = lanes)
    }
}

/**
 * The backlog column popup this page was asked to arrive open, and the board's own width while it is up.
 *
 * Two keys, one for each thing a reader can press on that board: `?newbucket=1` for the dashed box that adds a
 * column, `?bucket=<id>` for the `⋮` that renames or empties one. Since Stage 48 the create key is honoured for
 * every signed-in account, because the box that sends it is drawn for them; the second stays management's, and
 * a non-manager's copy of the popup prints the add half whatever the query claims — [otherTasksContent] checks
 * the role before it draws a rename form, so nothing typed here can ask for one.
 *
 * [showAll] is `?allbuckets=1`: the columns that hold nothing are printed. It travels through the bucket forms
 * as a field of their own rather than being re-read from the address bar, because a popup that redraws the
 * board has to redraw it the way the reader has it set — the columns that were hidden before a refusal must be
 * hidden after it, or the answer says something different from the question.
 */
private fun openingBucketForm(call: ApplicationCall, canManage: Boolean): BucketForm {
    val query = call.request.queryParameters
    return BucketForm(
        open = query["newbucket"] == "1",
        bucketId = if (canManage) query["bucket"]?.trim()?.toIntOrNull() ?: 0 else 0,
    )
}

/**
 * The page a bucket write answers itself with: the board redrawn and the popup in the state to show.
 *
 * A refusal comes back as this HTML rather than as a redirect, because `app.js` swaps `#otherBoard` and its
 * companion `#bucketModal` out of the response it is given and a redirect's target would arrive with the popup
 * closed and the reason for the refusal nowhere. A write that landed is a redirect (see [bucketsBack]), whose
 * followed page carries the new board and a closed popup — the same two blocks, moved by the same swap.
 *
 * The same renderer serves the plain page, the card popup's refusal and the column popup's, which is the whole
 * reason it is one function: the head's `Show all buckets` has to be asked of the same read the board is drawn
 * from, or the control appears over a board that has nothing for it to do.
 */
private suspend fun ApplicationCall.respondOtherTasks(
    session: UserSession,
    canManage: Boolean,
    state: TaskForm? = null,
    bucket: BucketForm = BucketForm(),
    showAll: Boolean = false,
) = respondHtml {
    appPage(
        session, "other-tasks", "Other tasks", subtitle = OTHER_TASKS_SUBTITLE,
        actions = {
            if (canManage) addBucketButton(showAll)
            if (canManage && otherToggleApplies(showAll)) allBucketsToggle(showAll)
        },
    ) {
        otherTasksContent(session, canManage, state, bucket, showAll)
    }
}

/**
 * The page a bucket write that landed redirects to, on the view the reader was looking at.
 *
 * [forceAll] is for the one write whose own result would otherwise hide it: a new column has no cards in it,
 * and a board that prints only the columns holding work would swallow the thing the reader just added. So the
 * create answers with the full board up — where the toggle is on the reader's sight to bring it back down.
 */
private fun bucketsBack(showAll: Boolean, forceAll: Boolean = false): String =
    if (showAll || forceAll) "/app/other-tasks?allbuckets=1" else "/app/other-tasks"

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
                val canManage = Authorization.canManage(session.employeeNumber)
                val query = call.request.queryParameters
                val type = query["type"]?.takeIf { it in PROJECT_TYPES }
                // The search bar and the two sort pickers are a GET form onto this same route, so the
                // board is a function of the URL: a filtered, sorted view can be reloaded, bookmarked
                // and handed to someone as a link. Anything the reader types that is not a known sort
                // value is treated as "not chosen" by BoardSort.of rather than guessed at.
                val filter = ProjectBoardFilter(
                    text = query["q"].orEmpty().trim(),
                    statusSort = BoardSort.of(query["sort_status"]),
                    managerSort = BoardSort.of(query["sort_manager"]),
                )
                call.respondHtml {
                    appPage(session, "projects", "Projects",
                        actions = { if (canManage) newProjectButton() }) {
                        projectsContent(it, type, canManage = canManage, filter = filter)
                    }
                }
            }
            get("/app/projects/{id}") {
                val session = call.requireSession() ?: return@get
                val project = call.parameters["id"]?.toIntOrNull()?.let { id -> DemoData.projects.firstOrNull { it.id == id } }
                if (project == null) return@get call.respondRedirect("/app/projects")
                val canManage = Authorization.canManage(session.employeeNumber)
                // The project page carries its own task board, so its opener is New task, not
                // New project, and it pre-selects this project in the popup. Its manager may write
                // cards here too, which is the one thing that widens the opener below management.
                val mayAdd = TaskService.mayAddTasks(project.id, session.employeeNumber, canManage)
                val state = openingTaskForm(call, session, canManage, project.id)
                // Nothing here reads `?lane=` any more (Stage 35). The two lane routes still answer a
                // request with the code they refused it on — `POST /app/projects/{id}/buckets` is a
                // management-only endpoint with no control pointing at it, so that query is the whole of
                // what its outcome says, and it belongs to the URL rather than to this renderer.
                call.respondHtml {
                    appPage(session, "projects", project.name, actions = { if (mayAdd) newTaskButton(project) }) {
                        projectDetailContent(it, project, canManage, state)
                    }
                }
            }
            /**
             * The assigned board: every card that carries an owner, cut into the five status columns.
             *
             * `?new=1` and `?edit=<card>` are the two openers of the card popup, both posted by markup on this
             * page itself — the head's `+ New task` and a card's own click. Nothing else lives here since
             * Stage 47: the backlog of work with no project is a page of its own, so no view key, no column
             * popup and no second board belongs to this URL any more.
             */
            get("/app/assigned-tasks") {
                val session = call.requireSession() ?: return@get
                val canManage = Authorization.canManage(session.employeeNumber)
                val state = openingTaskForm(call, session, canManage)
                call.respondHtml {
                    appPage(session, "assigned-tasks", "Assigned tasks", actions = { if (canManage) newTaskButton() }) {
                        assignedTasksContent(session, canManage, state)
                    }
                }
            }
            sectionRoute("/app/calendar", "calendar", "Calendar") { calendarContent(it) }
            /**
             * The Team sheet, and the panel management assigns a card from.
             *
             * [AssignForm] is blank for a read like this one; the only route that answers with a filled copy is
             * `/app/team/assign`, when it refuses one.
             */
            get("/app/team") {
                val session = call.requireSession() ?: return@get
                call.respondTeam(session, AssignForm())
            }
            /**
             * The forecast sheet for one month: one line per project, and the money the company plans
             * and has actually raised against it.
             *
             * The address names both halves of what the page shows — `month` picks the ledger line, and
             * `q` / `type` / `status` pick the projects the sheet is cut down to. `type` is the open tab,
             * and like the Projects board's it is one of [PROJECT_TYPES] or no tab at all: a name the sheet
             * has no panel for reads as All rather than as an empty page. An unrecognised month is
             * not an error page: [ForecastService.monthOr] falls back to the current one, so a bookmark
             * left on a renamed month still opens a sheet rather than a dead end.
             */
            get("/app/forecasts") {
                val session = call.requireSession() ?: return@get
                val month = ForecastService.monthOr(call.request.queryParameters["month"])
                val filter = forecastFilterOf(call.request.queryParameters)
                call.respondForecasts(session, month, filter, refused = null)
            }
            /**
             * Write one cell-set of one line: this project's forecast, invoice, invoice month and stated
             * status for the month the sheet is reading.
             *
             * Two things the markup cannot be trusted for, checked here: the right (a Viewer is served
             * plain text instead of inputs, and hiding markup is not a check — see [ProjectService]), and
             * which line is being written, which the form's hidden `month` answers and its invoice-month
             * cell deliberately does not.
             *
             * A saved line redirects back to the same sheet, so a browser without JavaScript lands on the
             * grid it just changed rather than on an empty address; a refused one is answered with the page
             * itself, because the row has to come back with the rejected characters still in its box.
             */
            post("/app/forecasts/save") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val month = ForecastService.monthOr(form["month"])
                val filter = forecastFilterOfForm(form)
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect(filter.queryFor(month))
                }
                val result = ForecastService.save(forecastDraftOf(form), month, canManage = true)
                if (result.saved) return@post call.respondRedirect(filter.queryFor(month))
                call.respondForecasts(session, month, filter, refused = result)
            }
            /**
             * The Lesson learnt sheet: one row per lesson, and the archived list beneath it.
             *
             * Four keys say which sheet the address is, and two more say which popup to have open on it.
             * `project` and `employee` are the picture's pickers, `sort=old` reverses the date column and
             * `archived=1` opens the list underneath the table — all four travel back through every form on
             * the page, so a row write answers the view the reader is looking at rather than the whole sheet.
             * `new=1` and `edit=<id>` are the two openers: the add button and a click on a lesson's text,
             * each a GET onto this route, which is how a reader with no JavaScript gets the same popup as a
             * fresh page load instead of a dead button.
             */
            get("/app/lessons") {
                val session = call.requireSession() ?: return@get
                val canManage = Authorization.canManage(session.employeeNumber)
                val query = call.request.queryParameters
                // The row `?edit=` names is read from the database, never from the form, and the popup's
                // rights come from that row: a hand-typed id gets a closed popup on the ordinary sheet, and
                // somebody else's lesson opens for reading with its fields printed plainly instead of typed.
                val entry = lessonEntryOf(query["edit"])
                val mayWrite = entry != null && LessonService.mayWrite(entry, session.employeeNumber, canManage)
                call.respondLessons(
                    session,
                    lessonFilterOf(query),
                    lessonFormOf(query, entry, mayWrite),
                )
            }
            /**
             * Write a lesson: a new one when the form carries no id, an existing row when it does.
             *
             * The author is the session and nothing else, which is why there is no Employee field on this
             * form — see [LessonService.save]. A saved row redirects back onto the sheet the form was posted
             * from, so a browser without JavaScript lands on the list it just changed; a refused one is
             * answered with the page itself, because `app.js` swaps the popup out of that response and the
             * reason has to arrive under the field that caused it with the words still typed in it.
             */
            post("/app/lessons/save") {
                val session = call.requireSession() ?: return@post
                val canManage = Authorization.canManage(session.employeeNumber)
                val form = call.receiveParameters()
                val filter = lessonFilterOfForm(form)
                val result = LessonService.save(lessonDraftOf(form), session.employeeNumber, canManage)
                if (result.saved) return@post call.respondRedirect(filter.queryFor())
                call.respondLessons(session, filter, lessonFormOf(result))
            }
            /**
             * Flip one lesson's discussed pill, in place.
             *
             * This is the only write on the sheet that leaves the row where it is, and the markup answers it
             * by swapping that one `<tr>` — which is why the response is the whole page rather than a
             * redirect: the row it replaces has to be drawn by the same code that drew the sheet.
             */
            post("/app/lessons/status") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                LessonService.setDiscussed(
                    id = form["id"]?.trim()?.toIntOrNull() ?: 0,
                    discussed = form["discussed"]?.trim() == "1",
                    employeeNumber = session.employeeNumber,
                    canManage = Authorization.canManage(session.employeeNumber),
                )
                // A row that has gone, or that has changed hands since the page was drawn, has no field on
                // this sheet to print either answer under — the re-read list is the whole of the reply, and
                // it shows the row gone or its buttons gone. Same pair of branches the backlog's column
                // routes send back for a denied rename.
                call.respondRedirect(lessonFilterOfForm(form).queryFor())
            }
            /** Move one lesson between the sheet and the Archived list beneath it. */
            post("/app/lessons/archive") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                LessonService.setArchived(
                    id = form["id"]?.trim()?.toIntOrNull() ?: 0,
                    archived = form["archived"]?.trim() == "1",
                    employeeNumber = session.employeeNumber,
                    canManage = Authorization.canManage(session.employeeNumber),
                )
                // Kept open on purpose: a reader archiving from the archived half of the page (`↩`) would
                // otherwise be bounced to a sheet with the list they are working through folded shut.
                call.respondRedirect(lessonFilterOfForm(form).queryFor())
            }
            /** Take one lesson off the database. The one write here with no way back, hence the confirm. */
            post("/app/lessons/delete") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                LessonService.remove(
                    id = form["id"]?.trim()?.toIntOrNull() ?: 0,
                    employeeNumber = session.employeeNumber,
                    canManage = Authorization.canManage(session.employeeNumber),
                )
                call.respondRedirect(lessonFilterOfForm(form).queryFor())
            }
            /**
             * The backlog board: work with no project, cut into the columns an account with rights names.
             *
             * Three keys open something on it, each a GET onto this route so a reader with no JavaScript gets
             * the same popup as a fresh load rather than a dead button. `?newbucket=1` and `?bucket=<id>` are
             * the dashed box and a column's `⋮`, both read by [openingBucketForm]; `?new=1&column=<id>` and
             * `?edit=<card>` are the card popup's, read by [openingTaskForm] — the first of those is a
             * backlog `+ Add task` line, which is why it aims at this page rather than the assigned one.
             * `?allbuckets=1` is the head's own toggle: the columns holding nothing are printed too.
             */
            get("/app/other-tasks") {
                val session = call.requireSession() ?: return@get
                val canManage = Authorization.canManage(session.employeeNumber)
                val state = openingTaskForm(call, session, canManage)
                val bucket = openingBucketForm(call, canManage)
                val showAll = call.request.queryParameters["allbuckets"] == "1"
                call.respondOtherTasks(session, canManage, state, bucket, showAll)
            }
            sectionRoute("/app/todo", "todo", "To-do") { todoContent(it) }
            get("/app/access-control") {
                val session = call.requireSession() ?: return@get
                val canAdmin = Authorization.canAdmin(session.employeeNumber)
                val canManage = Authorization.canManage(session.employeeNumber)
                // Stage 51: this page is entirely a management function — it is where accounts, roles and other
                // people's password status are read and set — so a Viewer who reaches it by URL or a stale
                // bookmark is sent back to the app rather than shown a read-only copy of the roster's keys.
                if (!canManage) return@get call.respondRedirect("/app")
                val status = PasswordStatus.fromCode(call.request.queryParameters["pw"])
                val detail = call.request.queryParameters["who"]
                call.respondHtml { appPage(session, "access-control", "Access control") { accessContent(canAdmin, canManage, session.employeeNumber, status, detail) } }
            }
            /**
             * The Settings page: the Team sheet, the reader's own account, and the password form.
             *
             * `q`, `position`, `role` and `sort` are the sheet's own view — the search box, the two
             * pickers and whichever column header was last pressed. `new=1` and `edit=<employee number>`
             * are the two openers, each a GET onto this route so a reader with no JavaScript gets the
             * same popup as a fresh page load rather than a dead button. `pw` and `note` are the one-shot
             * lines: the first belongs to the password form, the second to the sheet an account was just
             * added to or taken off.
             *
             * The account behind `?edit=` is read from the database and never from a form, and the popup's
             * rights come from the reader rather than the row: a number this account may not change answers
             * with the ordinary sheet and no form at all.
             */
            get("/app/settings") {
                val session = call.requireSession() ?: return@get
                val query = call.request.queryParameters
                val canManage = Authorization.canManage(session.employeeNumber)
                val canAdmin = Authorization.canAdmin(session.employeeNumber)
                val row = if (canAdmin) EmployeeService.row(query["edit"].orEmpty(), session.employeeNumber, canManage, canAdmin) else null
                val account = row?.let { EmployeeService.account(it.employeeNumber) }
                call.respondSettings(
                    session,
                    employeeFilterOf(query),
                    employeeFormOf(
                        query = query,
                        row = row,
                        account = account,
                        positionName = account?.let { EmployeeService.positionOf(it) }.orEmpty(),
                        mayAdd = canManage,
                        mayEdit = canAdmin,
                    ),
                    note = PasswordStatus.fromCode(query["note"]),
                    passwordStatus = PasswordStatus.fromCode(query["pw"]),
                    mailStatus = PasswordStatus.fromCode(query["mail"]),
                    mailDetail = query["mailerr"]?.trim()?.takeIf { it.isNotEmpty() },
                    bucketNote = PasswordStatus.fromCode(query["lane"]),
                    bucketDetail = query["lanedetail"]?.trim()?.takeIf { it.isNotEmpty() },
                    bucketDefaultsType = BucketDefaultsService.typeFor(query["lanes"]),
                )
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

            /**
             * Write one account: a new one when the popup carries no `edit`, an existing one when it does.
             *
             * One route for both, because [EmployeeService.save] is one set of rules for both — and the
             * split that matters is not create-versus-edit, it is that every field is checked before any
             * of them is applied. A write that landed redirects back onto the sheet the form was posted
             * from, so a browser without JavaScript lands on the list it just changed; a refused one is
             * answered with the page itself, because `app.js` swaps the popup out of that response and the
             * reason has to arrive under the field that caused it, with the words still typed in it.
             */
            post("/app/settings/employee/save") {
                val session = call.requireSession() ?: return@post
                val canManage = Authorization.canManage(session.employeeNumber)
                val canAdmin = Authorization.canAdmin(session.employeeNumber)
                val form = call.receiveParameters()
                val filter = employeeFilterOfForm(form)
                val result = EmployeeService.save(
                    draft = employeeDraftOf(form),
                    actor = session.employeeNumber,
                    canManage = canManage,
                    canAdmin = canAdmin,
                )
                if (result.saved) {
                    val note = if (result.isEdit) PasswordStatus.USER_UPDATED else PasswordStatus.USER_ADDED
                    return@post call.respondRedirect(filter.queryFor(extra = listOf("note=${note.name}")))
                }
                call.respondSettings(
                    session,
                    filter,
                    employeeFormOf(result, mayAdd = canManage, maySetRoles = canAdmin, filter = filter),
                )
            }

            /**
             * Take one account off the sheet. The one write here with no way back, hence the confirm.
             *
             * Every answer is a redirect back onto the sheet, because every answer is the same page with
             * one row different — and `app.js` follows the redirect, so the swap that lands is the re-read
             * list either way. The rule is [UserService.deleteUser]'s, in its own order, and the note it
             * comes back with is its own words: a delete that was refused says why under the heading,
             * because the row the reader aimed at is still in front of them.
             */
            post("/app/settings/employee/delete") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val filter = employeeFilterOfForm(form)
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect(filter.queryFor())
                }
                val status = UserService.deleteUser(
                    employeeNumber = employeeNumber,
                    callerEmpNo = session.employeeNumber,
                    callerRole = Authorization.effectiveRole(session.employeeNumber),
                )
                call.respondRedirect(filter.queryFor(extra = listOf("note=${status.name}")))
            }

            /**
             * Prove the SMTP wiring end to end, from the panel that reports it.
             *
             * The same one-message test `/app/alerts/test` runs — [DueDateAlertService.runDeliveryTest] over
             * the app's only outbound route, [mn.murray.pts.service.Mailer] — with two differences that belong
             * to a settings page: the recipient is always **the caller's own address** and cannot be pointed at
             * a colleague, and the answer is the page itself with a one-line flash rather than a plain-text
             * report, so pressing the button does not leave the panel the reader is looking at.
             *
             * No ledger row is written, so it can be pressed again straight after fixing a credential in
             * `data/.env` without spending the day's alert budget. Admin-only, exactly as the alerts endpoint
             * is: this is the one control on the page that makes an outbound connection to another machine.
             */
            post("/app/settings/email-test") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canAdmin(session.employeeNumber)) {
                    return@post call.respondRedirect(SETTINGS_PATH)
                }
                val to = DueDateAlertService.emailFor(session.employeeNumber)
                if (to.isBlank()) {
                    return@post call.respondRedirect("$SETTINGS_PATH?mail=${PasswordStatus.MAIL_NO_ADDRESS.name}")
                }
                val result = withContext(Dispatchers.IO) { DueDateAlertService.runDeliveryTest(to) }
                val (status, detail) = when (result) {
                    is MailResult.Sent -> PasswordStatus.MAIL_SENT to null
                    is MailResult.LoggedOnly -> PasswordStatus.MAIL_LOGGED to result.reason
                    is MailResult.Failed -> PasswordStatus.MAIL_FAILED to result.error.take(240)
                }
                val query = "mail=${status.name}" +
                    (detail?.let { "&mailerr=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
                call.respondRedirect("$SETTINGS_PATH?$query")
            }

            /**
             * Turn the app's master outbound-mail switch on or off (Stage 62).
             *
             * This is the in-app gate that decides whether the installation may email anyone at all, layered on
             * top of the operator's `data/.env` keys: even with a working host and dry-run off, nothing leaves
             * until [MailSwitch.enabled] is true. The right to flip it belongs to a Super Admin alone — it is
             * re-checked both here and inside [MailSwitch.setEnabled] (the "layout, not rights" convention), so
             * the panel never offers a button this route would bounce. The `on` field carries the *target* state
             * the button asked for; anything that is not exactly "true" is treated as off, so a hand-typed value
             * cannot quietly enable sending. The answer is the page again with a one-line flash, in place.
             */
            post("/app/settings/mail-switch") {
                val session = call.requireSession() ?: return@post
                val canAdmin = Authorization.canAdmin(session.employeeNumber)
                val target = call.receiveParameters()["on"]?.trim() == "true"
                if (!MailSwitch.setEnabled(on = target, canAdmin = canAdmin)) {
                    return@post call.respondRedirect("$SETTINGS_PATH?mail=${PasswordStatus.MAIL_REFUSED.name}")
                }
                val status = if (target) PasswordStatus.MAIL_ENABLED else PasswordStatus.MAIL_DISABLED
                call.respondRedirect("$SETTINGS_PATH?mail=${status.name}")
            }

            /**
             * Add one extra default lane to a project category (the V12 catalogue Settings' panel edits).
             *
             * Management-only at the route and again inside [BucketDefaultsService.add], whose reasons come
             * back as the panel's own flash line through the `?lane=` code. A refusal writes nothing. The
             * `?lanes=` key rides back so the answer is the same category's panel the reader was editing,
             * whichever of the nine it was — see `get("/app/settings")` for how that key is read.
             */
            post("/app/settings/buckets/add") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val category = BucketDefaultsService.typeFor(form["lanes"])
                val result = BucketDefaultsService.add(
                    type = category,
                    rawName = form["name"].orEmpty(),
                    canManage = Authorization.canManage(session.employeeNumber),
                )
                val code = if (result.ok) PasswordStatus.LANE_ADDED else PasswordStatus.LANE_REFUSED
                val reason = if (result.ok) "" else "&lanedetail=${java.net.URLEncoder.encode(result.message, "UTF-8")}"
                call.respondRedirect(
                    "$SETTINGS_PATH?lane=$code$reason&lanes=${java.net.URLEncoder.encode(category, "UTF-8")}",
                )
            }

            /** Take one default off the catalogue. Live boards are untouched — see [BucketDefaultsService.remove]. */
            post("/app/settings/buckets/delete") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val category = BucketDefaultsService.typeFor(form["lanes"])
                val id = form["id"]?.trim()?.toIntOrNull() ?: -1
                val ok = BucketDefaultsService.remove(id, Authorization.canManage(session.employeeNumber))
                val code = if (ok) PasswordStatus.LANE_REMOVED else PasswordStatus.LANE_REFUSED
                call.respondRedirect(
                    "$SETTINGS_PATH?lane=$code&lanes=${java.net.URLEncoder.encode(category, "UTF-8")}",
                )
            }

            post("/app/projects") {
                val session = call.requireSession() ?: return@post
                // Creating a project is management's call. The opener and the popup are hidden from
                // a Viewer too, but hiding markup is not a check — this route now answers a
                // hand-built POST exactly the way /app/projects/{id}/manager already does.
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/projects")
                }
                val fields = call.receiveParameters()
                val result = ProjectService.create(
                    ProjectDraft(
                        type = fields["type"].orEmpty(),
                        code = fields["code"].orEmpty(),
                        name = fields["name"].orEmpty(),
                        client = fields["client"].orEmpty(),
                        start = fields["start"].orEmpty(),
                        end = fields["end"].orEmpty(),
                        status = fields["status"].orEmpty(),
                        managerName = fields["manager"].orEmpty(),
                        revenue = fields["revenue"].orEmpty(),
                    ),
                    actorEmpNo = session.employeeNumber,
                )
                val created = result.projectId
                if (created != null) return@post call.respondRedirect("/app/projects/$created")
                // Rejected: re-render the board with the popup open, what was typed back in
                // the inputs, and each message printed under the field it belongs to.
                val type = fields["type"]?.trim()?.takeIf { it in PROJECT_TYPES }
                // The popup carries the board's knobs as `board_*` hidden fields (see
                // projectCreateModal), so a rejected create returns to the filtered, sorted board the
                // reader was working on instead of the whole catalogue.
                val filter = ProjectBoardFilter(
                    text = fields["board_q"].orEmpty().trim(),
                    statusSort = BoardSort.of(fields["board_sort_status"]),
                    managerSort = BoardSort.of(fields["board_sort_manager"]),
                )
                val state = ProjectForm(result.values, result.errors, ProjectService.suggestedCodes())
                call.respondHtml {
                    appPage(session, "projects", "Projects", actions = { newProjectButton() }) {
                        projectsContent(it, type, state, canManage = true, filter = filter)
                    }
                }
            }
            post("/app/projects/{id}/manager") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/projects")
                }
                val id = call.parameters["id"]?.toIntOrNull()
                val manager = call.receiveParameters()["manager"]?.trim().orEmpty()
                // The actor travels so the service can stay quiet when a manager takes the project
                // for themselves — telling someone they just assigned themselves is noise.
                if (id != null) ProjectService.setManager(id, manager, session.employeeNumber)
                call.respondRedirect(if (id == null) "/app/projects" else "/app/projects/$id")
            }
            /**
             * Save an edit from the project page's header strip (Stage 60). Management-only, exactly like the
             * create and the manager routes: the strip itself is drawn only for management, and this route
             * refuses a Viewer's hand-built POST the same way `/app/projects` (create) does.
             *
             * [ProjectService.update] re-checks every field rule and answers with the field each message belongs
             * under; on refusal the page comes back with what was typed still in the inputs and each complaint
             * beneath its own control — the same inline-validation contract the create modal uses, never a
             * page-top banner. On success it is a plain redirect, so a reload of the URL cannot re-submit the
             * save. The project number and progress are not fields here (see the view), so they are not read.
             */
            post("/app/projects/{id}") {
                val session = call.requireSession() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/app/projects")
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/projects/$id")
                }
                val fields = call.receiveParameters()
                val result = ProjectService.update(
                    id,
                    ProjectDraft(
                        type = fields["type"].orEmpty(),
                        name = fields["name"].orEmpty(),
                        client = fields["client"].orEmpty(),
                        start = fields["start"].orEmpty(),
                        end = fields["end"].orEmpty(),
                        status = fields["status"].orEmpty(),
                        managerName = fields["manager"].orEmpty(),
                        revenue = fields["revenue"].orEmpty(),
                        invoiced = fields["invoiced"].orEmpty(),
                    ),
                    actorEmpNo = session.employeeNumber,
                )
                if (result.ok) return@post call.respondRedirect("/app/projects/$id")
                val project = DemoData.projects.firstOrNull { it.id == id }
                    ?: return@post call.respondRedirect("/app/projects")
                val mayAdd = TaskService.mayAddTasks(project.id, session.employeeNumber, true)
                val state = openingTaskForm(call, session, true, project.id)
                call.respondHtml {
                    appPage(session, "projects", project.name, actions = { if (mayAdd) newTaskButton(project) }) {
                        projectDetailContent(
                            it, project, canManage = true, state = state,
                            edit = ProjectForm(result.values, result.errors),
                        )
                    }
                }
            }
            /**
             * Take a project and its cards off the database (Stage 60). The one whole-project write here with no
             * way back, so the button carries a `data-confirm` and this route is management's alone — a Viewer
             * hand-building the POST is bounced to the list without a word, and [ProjectService.delete] refuses
             * again on the same flag. Success goes back to `/app/projects`, because the page it deletes is gone.
             */
            post("/app/projects/{id}/delete") {
                val session = call.requireSession() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/app/projects")
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/projects")
                }
                ProjectService.delete(id, canManage = true)
                call.respondRedirect("/app/projects")
            }
            /**
             * One more lane on this project's board (V7).
             *
             * The name is the whole request, and every rule about it — blank, too long, already taken, whose
             * board this is — is [TaskService.addLane]'s. What is left here is where the reader goes after the
             * answer: a board that took the new lane shows it, and a refusal comes back to this same page with
             * its code in `?lane=`. Since Stage 35 nothing on a project page posts here and nothing on it reads
             * that code either — the box under the columns was the only control and the only message — so this
             * route is management's, reachable only by a request built by hand, and its answer is visible only
             * as the board being one column longer or not. A page that has no business holding this lane at
             * all — the caller may not name lanes, or the project is gone — is not a page to come back to, so
             * it returns to the project list instead.
             */
            post("/app/projects/{id}/buckets") {
                val session = call.requireSession() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/app/projects")
                val result = TaskService.addLane(
                    id,
                    call.receiveParameters()["name"].orEmpty(),
                    session.employeeNumber,
                    Authorization.canManage(session.employeeNumber),
                )
                val error = result.error
                if (error == LaneError.DENIED || error == LaneError.NO_PROJECT) {
                    return@post call.respondRedirect("/app/projects")
                }
                call.respondRedirect(
                    if (error == null) "/app/projects/$id" else "/app/projects/$id?lane=${error.name.lowercase()}"
                )
            }
            /**
             * The task popup endpoint. `taskId` 0 creates a card, any other value edits that one,
             * so the popup always posts here and only fills its own fields.
             *
             * Which of the fields a caller may write is decided in [TaskService.updateAssigned],
             * not here: management writes all of them, the assignee their own dates and priority — and since
             * Stage 49 nothing else, so **which column or lane a card sits in is management's answer on both
             * boards**. A create stays management's except on the backlog, where every account may write one,
             * and there the card lands in the column its `+ Add task` line was pressed under. The route's job
             * is the round trip — redirect when the card took the change, re-render the popup open when it did
             * not.
             *
             * A create names its board by which picker the form carries: `column` is the backlog's and nothing
             * else draws it, so a POST with one is handed to [TaskService.createBacklog] rather than to
             * [TaskService.create]. That is presentation, never the right — both services check the role again.
             * It is also why a *pinned* backlog column still has to be **posted**: the greyed select carries the
             * answer, so a hidden field does (see [TaskForm.columnPinned]), or a plain account's create would
             * be read as an assigned one.
             */
            post("/app/assigned-tasks/save") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val back = backOf(form)
                val projectId = projectIdOf(form)
                val canManage = Authorization.canManage(session.employeeNumber)
                val mayAdd = TaskService.mayAddTasks(projectId, session.employeeNumber, canManage)
                val taskId = form["taskId"]?.trim()?.toIntOrNull() ?: 0
                val draft = taskDraftOf(form)
                val backlogCreate = taskId == 0 && draft.columnId != null
                // Stage 51: the shared-backlog create is management's function. The board draws no `+ Add task`
                // line for a Viewer and their popup opener is shut (see [openingTaskForm]), so a POST carrying a
                // column from a non-manager has no page it belongs to. It is discarded back to their own scoped
                // board rather than saved — the markup is not the right, and this is the route checking it.
                if (backlogCreate && !canManage) {
                    return@post call.respondOtherTasks(session, canManage, showAll = showAllOf(form))
                }
                // No role gate here any more, deliberately: an assignee may now save their own dates and
                // priority, and [TaskService] is the place that knows which of the two callers is allowed
                // to write which field. A create is the one write with a right of its own — management from
                // any page, a project's manager from its page, and every account on the backlog — and each
                // service checks it against the same rule this page prints, not by what the markup showed.
                val result = when {
                    taskId > 0 -> TaskService.updateAssigned(taskId, draft, session.employeeNumber, canManage)
                    backlogCreate -> TaskService.createBacklog(draft, session.employeeNumber, canManage)
                    else -> TaskService.create(draft, session.employeeNumber, canManage, projectId)
                }
                // A card on the backlog's wide view answers on that same view: `allbuckets` is the popup's own
                // hidden field, carried through [taskRedirect] so saving a card cannot hide the columns the
                // reader was looking at, and read back below for the page that re-prints a refusal.
                val showAll = showAllOf(form)
                if (result.taskId != null) return@post call.respondRedirect(taskRedirect(back, projectId, showAllBuckets = showAll))
                // Rejected: the popup comes back open on the page it was posted from, with what
                // was typed still in the inputs and each message under its own field. The card is read
                // again so its steps and log ride along — a rejected due date must not close the list
                // somebody was writing next to it.
                val refused = if (taskId > 0) TaskService.editableBy(taskId, session.employeeNumber, canManage) else null
                val state = taskFormOf(
                    taskId = taskId,
                    result = result,
                    task = refused,
                    canEditAll = canManage,
                    // A refused backlog create keeps the right that drew it: the reader may write this card, and
                    // the two fields the route pinned for them come back greyed rather than blank (Stage 42).
                    canCreate = if (backlogCreate) true else mayAdd,
                    // The lanes this popup was posted from, so a rejected save comes back with the same lane
                    // picker it was filled with — and the card's chosen lane still selected in it (V7). Any
                    // other page's popup lists the six standard names from its own state instead.
                    lanes = if (back == BACK_PROJECT && projectId > 0) TaskService.lanesOf(projectId) else emptyList(),
                    // Likewise its columns: a refused backlog write comes back with the board's own picker, so
                    // the reader can see what they chose and change it without closing the form. Management gets
                    // them for a project card too, because their popup can send it onto that board and the
                    // column has to be pickable in the refusal (Stage 48).
                    backlogColumns = if (backlogCreate || refused?.isBacklog == true || canManage) {
                        TaskService.bucketRows()
                    } else {
                        emptyList()
                    },
                    backlog = backlogCreate,
                    // And the same tick boxes: a refused due date must not quietly turn the assignee's own
                    // steps into a read-only list because the re-render forgot who is looking at it.
                    viewerEmpNo = session.employeeNumber,
                )
                if (back == BACK_PROJECT) {
                    val project = DemoData.projects.firstOrNull { it.id == projectId }
                    if (project != null) {
                        return@post call.respondHtml {
                            appPage(session, "projects", project.name, actions = { if (mayAdd) newTaskButton(project) }) {
                                projectDetailContent(it, project, canManage, state)
                            }
                        }
                    }
                }
                // The page a refusal returns to is the page the form was opened on, which `back` now says out
                // loud rather than leaving to be inferred: a backlog card has been written from the Other tasks
                // board since Stage 47, and answering it on the assigned board would close the popup it came
                // from and redraw a board that never held the card. Both pages draw the same `#taskModal`, so
                // `app.js` swaps the refusal either way — what moves is the board behind it.
                if (back == BACK_OTHER) {
                    return@post call.respondOtherTasks(session, canManage, state = state, showAll = showAll)
                }
                call.respondHtml {
                    appPage(session, "assigned-tasks", "Assigned tasks", actions = { newTaskButton() }) {
                        assignedTasksContent(session, canManage, state)
                    }
                }
            }
            /**
             * Deletes the card named by the form's hidden `taskId`. The card menu on the board is
             * one shared form, so the id arrives as a field rather than as part of the path.
             *
             * The Delete option renders for managers only; this is that promise, kept server-side.
             */
            post("/app/assigned-tasks/delete") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val id = form["taskId"]?.trim()?.toIntOrNull()
                if (id != null && Authorization.canManage(session.employeeNumber)) {
                    TaskService.deleteAssigned(id, session.employeeNumber, true)
                }
                call.respondRedirect(taskRedirect(backOf(form), projectIdOf(form)))
            }
            /**
             * The bell's only link: mark the row read on the way through to whatever it points at.
             *
             * Two guards make an inbox item safe to render as a plain anchor. The id is matched
             * against the session's own employee number, so a hand-typed id marks nothing and leads
             * nowhere; and [NotificationService.safeLink] answers for `/app/` targets only, so the
             * stored link cannot be turned into a way out of the application.
             */
            get("/app/notifications/{id}/open") {
                val session = call.requireSession() ?: return@get
                val id = call.parameters["id"]?.trim()?.toIntOrNull()
                    ?: return@get call.respondRedirect("/app/projects")
                NotificationService.markRead(session.employeeNumber, id)
                call.respondRedirect(NotificationService.safeLink(session.employeeNumber, id) ?: "/app/projects")
            }
            /**
             * Clears the badge and returns to the page the panel was open on.
             *
             * The destination is a hidden field the bell renders itself — not the referrer, which a
             * proxy or a bookmark can make anything — and it is re-checked for the `/app/` prefix
             * here rather than trusted for being server-rendered.
             */
            post("/app/notifications/read-all") {
                val session = call.requireSession() ?: return@post
                val back = call.receiveParameters()["back"].orEmpty().trim()
                    .takeIf { it.startsWith("/app/") } ?: "/app/projects"
                NotificationService.markAllRead(session.employeeNumber)
                call.respondRedirect(back)
            }
            /**
             * Assign a card from the Team panel — to either of the two boards that panel can now name (Stage 50).
             *
             * The *Where it lives* answer picks the service rule, and nothing else does: *Other tasks* is a card
             * with no project, so it goes through [TaskService.createBacklog] — the same write a column's
             * `+ Add task` line makes on that board, which files the card under the column the form names and
             * asks nothing of the Project and Sub-category boxes the reader never saw. Any other answer, and a
             * form that posted no such field, is the card this route has always written: [TaskService.create],
             * project and lane both compulsory. The guard above is what lets the service be handed
             * `canManage = true`, and that is the difference between this backlog card and the one a normal
             * account writes on that board: [TaskService.createBacklog] pins a non-management card to its writer
             * and starts it *Not started*, whereas the person picked from this panel's roster is who the card
             * belongs to, whichever board it lands on.
             *
             * A refusal is answered with the Team page itself, at 200, carrying the typed words and the reason
             * under the field that caused it. The panel is not a popup, so a redirect would arrive with it empty
             * and the reader would have to type the whole card again to learn that its column was never chosen.
             */
            post("/app/team/assign") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.canManage(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/team")
                }
                val form = call.receiveParameters()
                val draft = taskDraftOf(form)
                val result = if (boardOf(form) == BOARD_OTHER) {
                    TaskService.createBacklog(draft, session.employeeNumber, canManage = true)
                } else {
                    TaskService.create(draft, session.employeeNumber, canManage = true)
                }
                if (result.errors.isNotEmpty()) {
                    return@post call.respondTeam(session, AssignForm(result.values, result.errors))
                }
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
            /**
             * Take one lane off this project's board (V7, Stage 31) — the route the **×** on a column head used
             * to post to, and since Stage 35 the only way a lane comes off, because that × is gone.
             *
             * The twin of the route above, and by design: one field (`bucket`, the lane's own id), the whole
             * rule inside [TaskService.removeLane], and the same `?lane=` back to the board the reader is
             * standing on — a code nothing on that board any longer turns into a sentence, its box having gone
             * before this control did. The project
             * comes from the URL and the lane from the form, and the service is what checks the two belong to
             * each other — which is why a POST naming another project's lane removes nothing.
             */
            post("/app/projects/{id}/buckets/delete") {
                val session = call.requireSession() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/app/projects")
                val lane = call.receiveParameters()["bucket"]?.trim()?.toIntOrNull() ?: 0
                val error = TaskService.removeLane(
                    id,
                    lane,
                    session.employeeNumber,
                    Authorization.canManage(session.employeeNumber),
                )
                if (error == LaneError.DENIED || error == LaneError.NO_PROJECT) {
                    return@post call.respondRedirect("/app/projects")
                }
                call.respondRedirect(
                    if (error == null) "/app/projects/$id" else "/app/projects/$id?lane=${error.name.lowercase()}"
                )
            }
            /**
             * Drag a card onto one of its project's lanes (V7). `bucket` is the lane's id, and since Stage 48
             * **0 is refused**: the Unsorted column holds the cards that arrived before every card on a project
             * had to be filed in a sub-category, so the board does not offer it as a destination and a request
             * that aims at it changes nothing.
             *
             * The board's own answer to a drop, and deliberately the twin of [TaskService.updateStatus]
             * rather than a third way to save a card: one field, one rule. **The rule is not updateStatus's any
             * more, though** (Stage 49): a lane belongs to a project, so the mover is the account that may write
             * onto that project — management, or the project's own manager — and not the person the card happens
             * to be assigned to. That is decided inside [TaskService.moveToLane] from the card's row, so this
             * route hands it the session and the role and asks nothing of the form. `kanban.js` sends this and
             * moves the card node itself, so the page does not restart under the reader — the redirect below is
             * for the request that arrives any other way.
             */
            post("/app/tasks/bucket") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val id = form["id"]?.trim()?.toIntOrNull()
                val bucket = form["bucket"]?.trim()?.toIntOrNull()
                val canManage = Authorization.canManage(session.employeeNumber)
                if (id != null && bucket != null) {
                    TaskService.moveToLane(id, bucket, session.employeeNumber, canManage)
                }
                val back = call.request.headers[HttpHeaders.Referrer] ?: "/app/assigned-tasks"
                call.respondRedirect(back)
            }
            /**
             * The three checklist moves on a card: put a step on it, tick one, take one off.
             *
             * Each is one small POST that writes one row and answers with a redirect back to the page
             * the card is on, which is what lets [app.js] fetch it and swap only that card's checklist
             * block — ticking a box cannot restart the board, and the add box keeps its focus so a list
             * can be written one line at a time. With JavaScript switched off these are ordinary forms:
             * the same POST, the same redirect, the page loaded fresh.
             *
             * *Whether* a caller may do any of it is decided in [TaskService] rather than here, so the
             * rule reads the same from the board, from a project page, or from a hand-built request:
             * management writes the list, and management or the assignee works down it.
             *
             * A form inside the editor popup carries `edit=<card>`, so its redirect lands on the page with
             * the same popup open on the same card — otherwise the block [app.js] asked for would not be in
             * the response and the send would fall back to a full page load.
             */
            post("/app/tasks/checklist/add") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val taskId = form["taskId"]?.trim()?.toIntOrNull() ?: 0
                TaskService.addChecklistItem(
                    taskId,
                    label = form["label"].orEmpty(),
                    callerEmpNo = session.employeeNumber,
                    canManage = Authorization.canManage(session.employeeNumber),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form), showAllBuckets = showAllOf(form)),
                )
            }
            post("/app/tasks/checklist/toggle") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val itemId = form["itemId"]?.trim()?.toIntOrNull() ?: 0
                TaskService.toggleChecklistItem(
                    itemId,
                    callerEmpNo = session.employeeNumber,
                    canManage = Authorization.canManage(session.employeeNumber),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form), showAllBuckets = showAllOf(form)),
                )
            }
            post("/app/tasks/checklist/remove") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val itemId = form["itemId"]?.trim()?.toIntOrNull() ?: 0
                TaskService.removeChecklistItem(
                    itemId,
                    callerEmpNo = session.employeeNumber,
                    canManage = Authorization.canManage(session.employeeNumber),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form), showAllBuckets = showAllOf(form)),
                )
            }
            /**
             * The two note moves: append one, and management taking one off again.
             *
             * The same shape as a checklist write — one row, one redirect, and only the
             * `#task-notes-<card>` block swapped in place — because a note is written while the card's form
             * is open, and closing it to say "saved" would interrupt the thing the reader was doing. Adding
             * is open to the assignee as well as management ([TaskService.addNote]); removing is
             * management's alone, so the log cannot be quietly rewritten by whoever wrote the line.
             */
            post("/app/tasks/notes/add") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val taskId = form["taskId"]?.trim()?.toIntOrNull() ?: 0
                TaskService.addNote(
                    taskId,
                    body = form["body"].orEmpty(),
                    callerEmpNo = session.employeeNumber,
                    canManage = Authorization.canManage(session.employeeNumber),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form), showAllBuckets = showAllOf(form)),
                )
            }
            post("/app/tasks/notes/remove") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val noteId = form["noteId"]?.trim()?.toIntOrNull() ?: 0
                TaskService.removeNote(
                    noteId,
                    callerEmpNo = session.employeeNumber,
                    canManage = Authorization.canManage(session.employeeNumber),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form), showAllBuckets = showAllOf(form)),
                )
            }
            // There is no `POST /app/other-tasks` any more (Stage 42). The backlog's `+ Add task` line used to
            // be a title box that wrote a bare card straight into the column under it; it is now the same
            // `?new=1&column=<id>` popup every other create uses, and that posts `/app/assigned-tasks/save`,
            // which dispatches to [TaskService.createBacklog] when the form carries a column. One card route for
            // one card shape, so the two boards cannot drift into two different sets of rules again.
            post("/app/other-buckets") {
                val session = call.requireSession() ?: return@post
                val canManage = Authorization.canManage(session.employeeNumber)
                // Stage 51: naming a backlog column is an Other-tasks function, and the board is now management's,
                // so the route turns a Viewer's add back to the page (which they cannot open) rather than growing
                // a column. The rename/delete routes below have always checked the role; this closes the last
                // write Stage 48 had left open to every signed-in account.
                if (!canManage) return@post call.respondRedirect("/app/other-tasks")
                val form = call.receiveParameters()
                val name = form["name"]?.trim().orEmpty()
                val showAll = form["allbuckets"]?.trim() == "1"
                // [TaskService.addBucket] answers with the rule that stopped the name, so the two a reader can
                // reach by typing — nothing at all, and a name the board already carries — are told apart in
                // the popup rather than landing on the same silent board.
                val error = TaskService.addBucket(name, session.employeeNumber, canManage)
                if (error != null) {
                    // Refused: the page comes back with the popup up, the word still in the field and the
                    // reason printed under it. `app.js` swaps both blocks from this one response, so the
                    // board keeps the columns it has and only the popup moves — no reload, nothing retyped.
                    // A create has no column to name wrongly, so of the answers [TaskService.addBucket] can
                    // return, every one is a word about the field the reader is looking at.
                    return@post call.respondOtherTasks(
                        session, canManage, bucket = BucketForm(typed = name, nameError = bucketMessage(error)),
                        showAll = showAll,
                    )
                }
                // Landed, or aimed at a column this page cannot show a popup for. A new bucket is drawn with
                // the whole board up: it holds nothing, and the default view prints only the columns that do,
                // so the column the reader just added would otherwise vanish as they pressed the box.
                call.respondRedirect(bucketsBack(showAll, forceAll = true))
            }
            /**
             * Rename one backlog column. `app.js` swaps the board and this popup from whichever answer comes
             * back, so a rename that landed arrives as a new heading over the same cards and a name that was
             * refused arrives as the popup it came from, word still typed, reason under the field.
             */
            post("/app/other-buckets/{id}/rename") {
                val session = call.requireSession() ?: return@post
                val canManage = Authorization.canManage(session.employeeNumber)
                val bucketId = call.parameters["id"]?.toIntOrNull() ?: 0
                val form = call.receiveParameters()
                val name = form["name"]?.trim().orEmpty()
                val showAll = form["allbuckets"]?.trim() == "1"
                val error = TaskService.renameBucket(bucketId, name, session.employeeNumber, canManage)
                // The two answers with nothing to say them: this reader may not be renaming a column at all,
                // or the column they aimed at is not on the board. Neither has a field on this page to print
                // under, so the board is the whole of the reply — the same pair of branches the lane routes
                // send back to the project list for.
                if (error == BucketError.DENIED || error == BucketError.NO_BUCKET) {
                    return@post call.respondRedirect("/app/other-tasks")
                }
                if (error != null) {
                    return@post call.respondOtherTasks(
                        session,
                        canManage,
                        bucket = BucketForm(typed = name, nameError = bucketMessage(error), bucketId = bucketId),
                        showAll = showAll,
                    )
                }
                call.respondRedirect(bucketsBack(showAll))
            }
            /**
             * Take one backlog column off the board, handing its cards to another first.
             *
             * [TaskService.deleteBucket] is the rule and this is the round trip: a column whose cards have
             * nowhere agreed to go is refused with the choice still made, because the reader is looking at that
             * select and it is the field the answer belongs under. A denied request or a column that has gone
             * comes back as the ordinary board — there is no popup of theirs to print a reason in.
             */
            post("/app/other-buckets/{id}/delete") {
                val session = call.requireSession() ?: return@post
                val canManage = Authorization.canManage(session.employeeNumber)
                val bucketId = call.parameters["id"]?.toIntOrNull() ?: 0
                val form = call.receiveParameters()
                val showAll = form["allbuckets"]?.trim() == "1"
                val moveTo = form["moveTo"]?.trim()?.toIntOrNull()
                val error = TaskService.deleteBucket(bucketId, moveTo, session.employeeNumber, canManage)
                if (error == BucketError.DENIED || error == BucketError.NO_BUCKET) {
                    return@post call.respondRedirect("/app/other-tasks")
                }
                if (error != null) {
                    return@post call.respondOtherTasks(
                        session,
                        canManage,
                        bucket = BucketForm(deleteError = bucketMessage(error), bucketId = bucketId, moveId = moveTo ?: 0),
                        showAll = showAll,
                    )
                }
                call.respondRedirect(bucketsBack(showAll))
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

/**
 * The forecast sheet, on both routes that answer with it.
 *
 * [refused] is the row a save rejected and nothing else: the sheet is re-read from the ledger, so
 * every other line shows the stored figures while that one shows what was typed and says why the
 * page would not take it.
 */
private suspend fun ApplicationCall.respondForecasts(
    session: UserSession,
    month: String,
    filter: ForecastFilter,
    refused: ForecastResult?,
) = respondHtml {
    appPage(session, "forecasts", "Forecasts") {
        forecastsContent(
            month = month,
            board = ForecastService.board(month, filter),
            filter = filter,
            canManage = Authorization.canManage(session.employeeNumber),
            refused = refused,
        )
    }
}

/**
 * The lesson sheet, on every route that answers with it.
 *
 * [form] is the popup in the state this particular answer needs: closed for a redirect's page, up with
 * its fields for an opener, and up with the typed words and the field-keyed reasons when a save was
 * refused — which is the only reason a write route here responds with HTML at all, since `app.js`
 * swaps `#ll-board`, `#ll-counts` and `#lessonModal` out of the response it is given and a redirect's
 * target would arrive with the form shut and the refusal nowhere.
 *
 * The board is re-read for the reader who is asking, so each row carries its own answer to "may this
 * account write it?" and the sheet a Viewer gets has no buttons on it anywhere.
 */
private suspend fun ApplicationCall.respondLessons(
    session: UserSession,
    filter: LessonFilter,
    form: LessonForm,
) = respondHtml {
    val canManage = Authorization.canManage(session.employeeNumber)
    appPage(session, "lessons", "Lesson learnt") {
        lessonsContent(
            board = LessonService.board(filter, session.employeeNumber, canManage),
            filter = filter,
            form = form,
            projects = DemoData.projects,
            tasks = LessonService.taskChoices(),
            employees = DemoData.team,
        )
    }
}

/**
 * The Settings page, on every route that answers with it.
 *
 * [form] is the popup in the state this particular answer needs: closed for an ordinary read, up with
 * its fields for an opener, and up with the typed words and the field-keyed reasons when a save was
 * refused — which is the only reason a write route here responds with HTML at all, since `app.js` swaps
 * `#team-board`, `#team-counts` and `#employeeModal` out of the response it is given and a redirect's
 * target would arrive with the form shut and the refusal nowhere.
 *
 * The sheet is read for the account that is asking, so each row carries its own answer to "may this
 * reader change it, and may they delete it", and the counts column a Viewer cannot see is a dash rather
 * than a zero.
 */
private suspend fun ApplicationCall.respondSettings(
    session: UserSession,
    filter: EmployeeFilter,
    form: EmployeeForm,
    note: PasswordStatus? = null,
    passwordStatus: PasswordStatus? = null,
    mailStatus: PasswordStatus? = null,
    mailDetail: String? = null,
    bucketNote: PasswordStatus? = null,
    bucketDetail: String? = null,
    bucketDefaultsType: String = PROJECT_TYPES.first(),
) = respondHtml {
    val canManage = Authorization.canManage(session.employeeNumber)
    val canAdmin = Authorization.canAdmin(session.employeeNumber)
    appPage(session, "settings", "Settings") {
        settingsContent(
            session = session,
            sheet = EmployeeService.sheet(filter, session.employeeNumber, canManage, canAdmin),
            filter = filter,
            form = form,
            canManage = canManage,
            canAdmin = canAdmin,
            note = note,
            passwordStatus = passwordStatus,
            mailStatus = mailStatus,
            mailDetail = mailDetail,
            bucketNote = bucketNote,
            bucketDetail = bucketDetail,
            bucketDefaultsType = bucketDefaultsType,
        )
    }
}

/**
 * The Team page, on every route that answers with it.
 *
 * [assign] is the panel's own half of that, and it is the reason this exists as a helper rather than as a
 * `sectionRoute`: a read hands it a blank form, and `/app/team/assign` hands it the words the reader typed and
 * the field-keyed reason it refused them — the only write route in the app that answers with this page at all,
 * because the panel is on the page rather than in a popup `app.js` could swap.
 *
 * The sheet is read for the account that is asking, so the row a normal account may open for details is their
 * own, and the panel below it is drawn for management alone.
 */
private suspend fun ApplicationCall.respondTeam(session: UserSession, assign: AssignForm) = respondHtml {
    appPage(session, "team", "Team") {
        teamContent(
            canManage = Authorization.canManage(session.employeeNumber),
            viewerEmpNo = session.employeeNumber,
            assign = assign,
        )
    }
}

private fun io.ktor.server.routing.Route.sectionRoute(
    path: String,
    section: String,
    title: String,
    content: DIV.(UserSession) -> Unit,
) {
    get(path) {
        val session = call.requireSession() ?: return@get
        call.respondHtml { appPage(session, section, title, block = content) }
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
