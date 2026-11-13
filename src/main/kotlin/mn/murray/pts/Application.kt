package mn.murray.pts
import mn.murray.pts.domain.*
import mn.murray.pts.config.AppConfig
import mn.murray.pts.db.DatabaseFactory
import mn.murray.pts.db.migration.DemoSeeder
import mn.murray.pts.db.migration.JsonImporter
import mn.murray.pts.db.migration.UserEmailBackfill
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
import mn.murray.pts.service.ProjectTypeResult
import mn.murray.pts.service.ProjectTypeService
import mn.murray.pts.service.RoleService
import mn.murray.pts.service.TaskService
import mn.murray.pts.service.TodoService
import mn.murray.pts.service.UserService
import mn.murray.pts.repository.SessionRepository
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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.path
import io.ktor.server.request.httpMethod
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
 * The one question every route under this file asks (Stage 110): does this account's role hold this chip?
 *
 * It is a shorthand, not a second rule — [Authorization.canDo] is the rule, and this only saves every guard
 * from repeating `session.employeeNumber`. A route asks for the chip its control is about, never for
 * [Authorization.canManage]: that convenience flag is for drawing a page as an editor's, and a custom role
 * holding one chip must get exactly that one power.
 */
private fun UserSession.may(permission: Permission): Boolean =
    Authorization.canDo(employeeNumber, permission)

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
 * The `must_change_password` flag is still recorded on an account whose password the system
 * assigned (an import, a bootstrap admin, an account added without one), but since Stage 99
 * nothing enforces it: the Settings password box is gone, and a flagged reader reaches the
 * app exactly like any other.
 */
class UserSession(val employeeNumber: String)

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
 * [TaskService.openableBy], which since Stage 86 hands a project card to any reader — it opens read-only for
 * somebody who may not write it — and still answers a backlog card only to management or the person it is
 * assigned to, so a hand-typed id for a card off that reader's scoped board comes back as an ordinary board
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
    allowSelfBacklogCreate: Boolean = false,
): TaskForm? {
    val query = call.request.queryParameters
    val isNew = query["new"] != null
    // A project page's popup gets that project's lanes (V7) so its Sub-category picker can offer them in the
    // order the board prints them, extras and all. The general board passes none, and the popup then lists the
    // three every project opens with (Stage 75) — the names no board is created without, which is why it can ask
    // the question at all without knowing a project's lane ids. A board that has renamed one of the three refuses
    // that name when the card is saved, because the name is looked up on the project the card lands on.
    val lanes = if (pageProjectId > 0) TaskService.lanesOf(pageProjectId) else emptyList()
    if (isNew && pageProjectId == 0) {
        // `?column=<id>` is the bucket a backlog `+ Add task` line was pressed under, checked against the
        // board's own columns rather than trusted: a hand-typed id is not a place a card can be filed, and an
        // unaimed `?new=1` stays the assigned create this page's head has always posted.
        // **Stage 90** draws that line on the Assigned page for every reader ([allowSelfBacklogCreate]):
        // a non-manager's card is pinned to them as *Not started* by
        // [mn.murray.pts.service.TaskService.createBacklog], so the form is theirs to fill in and nothing
        // outside the title, dates, priority and bucket is theirs to choose. The shared Other-tasks page keeps
        // its Stage 51 shape, so its own `?new=1` still opens nothing for a non-manager.
        val columns = TaskService.bucketRows()
        val into = query["column"]?.trim()?.toIntOrNull()?.takeIf { id -> columns.any { it.id == id } }
        if (into != null && (canManage || allowSelfBacklogCreate)) {
            return backlogFormOf(canManage, session.employeeNumber, columns, into = into)
        }
    }
    if (isNew && TaskService.mayAddTasks(pageProjectId, session.employeeNumber, canManage)) {
        // `?into=<lane id>` is the lane a `+ Add task` line was pressed under. It is checked against this
        // page's own lanes rather than trusted, so a hand-typed id — another project's lane, a removed one,
        // a word — opens the ordinary blank form instead of selecting a column that is not on this board. The
        // form is filled with the lane's **name**, which is what the picker and the post both work in
        // (Stage 48); the id is only ever the board's way of saying which column was pressed.
        val into = query["into"]?.trim()?.toIntOrNull()?.let { id -> lanes.firstOrNull { it.id == id } }
        // A create that stands on no project (the Assigned page's head, and the Other-tasks page's own `?new=1`)
        // picks its project inside the popup, so the popup is handed every board's columns at once — Stage 111.
        // A project page's create already has its own board in [lanes] and needs none of that list.
        return newFormOf(canManage, into = into?.name)
            .copy(lanes = lanes, lanesByProject = if (pageProjectId == 0) TaskService.lanesByProjectName() else emptyMap())
    }
    val id = query["edit"]?.trim()?.toIntOrNull() ?: return null
    return TaskService.openableBy(id, session.employeeNumber, canManage)?.let { task ->
        // Which picker this popup has is the row's answer, not the URL's: a backlog card is filed in a column
        // of that board and needs its list to fill the select with, a project card in a lane of its own.
        // Management gets both lists whatever the row says, because their popup can move a card from one board
        // to the other (Stage 48) and the field it lands on has to be chosen before the form is saved.
        val columns = if (task.isBacklog || canManage) TaskService.bucketRows() else emptyList()
        editFormOf(task, canManage, session.employeeNumber, backlogColumns = columns).copy(lanes = lanes)
    }
}

/**
 * The backlog column popup this page was asked to arrive open.
 *
 * Two keys, one for each thing a reader can press on that board: `?newbucket=1` for the dashed box that adds a
 * column, `?bucket=<id>` for the `⋮` that renames or empties one. Since Stage 48 the create key is honoured for
 * every signed-in account, because the box that sends it is drawn for them; the second stays management's, and
 * a non-manager's copy of the popup prints the add half whatever the query claims — [otherTasksContent] checks
 * the role before it draws a rename form, so nothing typed here can ask for one.
 *
 * *(**Stage 97** retired a third key this KDoc used to document — `?allbuckets=1`, the wide view the head's
 * toggle carried, which printed the columns that hold nothing. Both the flag and the view are gone; the columns a
 * reader wants to see are named one at a time by the board's Bucket picker, and that filter — `?show=<id>`, read at
 * the GET route below — is the state that travels through the forms on this page now.)*
 */
private fun openingBucketForm(call: ApplicationCall, canManage: Boolean): BucketForm {
    val query = call.request.queryParameters
    return BucketForm(
        open = query["newbucket"] == "1",
        bucketId = if (canManage) query["bucket"]?.trim()?.toIntOrNull() ?: 0 else 0,
    )
}

/**
 * Where one of a project page's bucket writes sends the reader back, and what it says there (Stage 74).
 *
 * Since **Stage 81** this is also how a card put away with its column is answered, because the page has one place
 * for a sentence about this board's shape and its work. (**Stage 106** retired the Archive panel and its
 * `?archive=1` key: nothing reads it, nothing echoes it, and a stale bookmark answers the ordinary page.)
 *
 * All three — the dashed box's add, a column's rename, a column's removal — answer the same way the Stage 73
 * picker already did: the outcome in `?buckets=`, the sentence in `?bucketnote=`. The pair of keys that used to
 * tell the page **which half of it asked** is down to one now that **Stage 79** has taken the picker panel off the
 * page: every write comes from the board, so the sentence prints over the columns the reader changed. [onBoard] is
 * the form's own `src` field carried into the
 * URL, and [reopen] is the popup to put back up: a **refusal** hands the reader their own words in the field that
 * was refused, a **success** closes the form and lets the redrawn board be the answer.
 *
 * The URL built with [onBoard] false and [reopen] null is still exactly the string Stage 73 built, so an old
 * bookmark resolves the same way — the page simply has no panel left to print it in, and puts it over the board.
 */
private fun laneRedirect(
    projectId: Int,
    ok: Boolean,
    note: String,
    onBoard: Boolean,
    reopen: LaneForm? = null,
    forType: Boolean = false,
): String {
    val keys = mutableListOf(
        "buckets=${if (ok) "ok" else "err"}",
        "bucketnote=" + java.net.URLEncoder.encode(note, "UTF-8"),
    )
    if (onBoard) keys += "bucketsrc=board"
    // Stage 77: a refused save that was asked with the *also offer it to new projects* tick on says so, so the
    // form that comes back still shows the reader's own answer. A save that landed needs no such key — the
    // sentence it prints already told them whether the category widened.
    if (!ok && forType) keys += "widen=1"
    reopen?.let {
        if (it.open) keys += "laneadd=1"
        if (it.laneId != 0) keys += "laneedit=${it.laneId}"
        if (it.typed.isNotEmpty()) keys += "lanetyped=" + java.net.URLEncoder.encode(it.typed, "UTF-8")
        // Stage 80: the swatch the reader had chosen, kept for the same reason their word in the name field is
        // kept — a refusal about the name should not also quietly re-pick the colour they were happy with.
        it.colourSlot?.let { slot -> keys += "lanecolour=$slot" }
    }
    return "/app/projects/$projectId?" + keys.joinToString("&")
}

/**
 * What a removal the board asked for answers with when it did not remove (Stage 74).
 *
 * [TaskService.removeLane] answers with a [mn.murray.pts.service.LaneRemoval] — the refusal, or how many cards
 * went to the Archive with the column — and the board's `⋯` is the only control that turns either half into
 * words. The older bare-`?lane=` round trip has no control pointing at it any more and keeps its code-only answer. Since Stage 75 [LaneError.LAST_LANE] is the one a real click does reach — every column
 * carries its `⋯`, including the three a board opened with, so a project that has been emptied down to one lane
 * can be asked to remove it — and the rest are what a stale page or a hand-built POST reaches, since a board
 * prints no menu for a reader who may not write it and no menu for a column that is already gone.
 */
private fun laneRemovalNote(error: LaneError): String = when (error) {
    LaneError.LAST_LANE -> "A board keeps at least one bucket, so this one cannot go."
    else -> "That bucket is no longer on this board."
}

/**
 * The project page's bucket popup, read out of its URL (Stage 74).
 *
 * [LaneForm] is the page's own state, so a plain click and a bookmarked link open the same form — the dashed box
 * is `?laneadd=1` and a column's `⋯` is `?laneedit=<id>`, each a GET form [mn.murray.pts.laneKanban] draws. Three
 * keys belong to a refusal rather than to an opener, and a refusal is why the page also needs [note] and
 * [refused] here: the sentence a write answers with arrives in the URL, and where it prints depends on whether the
 * form is still up. **A name refused inside the open popup is printed at the field, not twice** — so [note] goes
 * into [LaneForm.nameError] and [noteOnBoard] comes back false — while a write that landed, or one refused for
 * some other reason, keeps the sentence over the board it changed. Since **Stage 79** the board is the page's only
 * bucket control, so every bucket write's answer is the board's: the `?bucketsrc=board` mark its forms carry is
 * what distinguishes a refusal that belongs at a field from one that does not, and a URL without the mark — an
 * old bookmark, or the picker's own redirect from before the panel went — is printed over the board too rather
 * than swallowed with the panel that used to hold it.
 *
 * Every key is honoured only for a reader who may make the write ([mayWriteLanes]): a Viewer who bookmarks
 * `?laneedit=7` is handed the page, not a form whose every button the routes refuse, which is the same reason
 * [openingBucketForm] asks for the role before it reads `?bucket=`.
 */
private fun openingLaneForm(
    call: ApplicationCall,
    mayWriteLanes: Boolean,
    note: String?,
    refused: Boolean,
): LaneEntry {
    if (!mayWriteLanes) return LaneEntry(LaneForm(), false)
    val query = call.request.queryParameters
    val add = query["laneadd"] == "1"
    val laneId = query["laneedit"]?.trim()?.toIntOrNull() ?: 0
    val inPopup = refused && query["bucketsrc"] == "board" && (add || laneId != 0)
    return LaneEntry(
        form = LaneForm(
            typed = query["lanetyped"].orEmpty(),
            nameError = if (inPopup) note else null,
            open = add,
            laneId = laneId,
            forType = query["widen"] == "1",
            // Stage 80: the swatch the reader had chosen, or null for "no answer yet", which the popup reads as
            // the colour the column already wears.
            colourSlot = colourSlotOf(query["lanecolour"]),
        ),
        noteOnBoard = !inPopup,
    )
}

/** What [openingLaneForm] read: the popup's state and whether this page's bucket sentence belongs over the board.
 *  Stage 77's *also offer it to new projects of this category* tick rides inside the form, where it is drawn. */
private data class LaneEntry(val form: LaneForm, val noteOnBoard: Boolean)

/**
 * The page a bucket write answers itself with: the board redrawn and the popup in the state to show.
 *
 * A refusal comes back as this HTML rather than as a redirect, because `app.js` swaps `#otherBoard` and its
 * companion `#bucketModal` out of the response it is given and a redirect's target would arrive with the popup
 * closed and the reason for the refusal nowhere. A write that landed is a redirect (see [bucketsBack]), whose
 * followed page carries the new board and a closed popup — the same two blocks, moved by the same swap.
 *
 * The same renderer serves the plain page, the card popup's refusal and the column popup's, which is the whole
 * reason it is one function: the head's `+ Add bucket` has to be asked of the same read the board is drawn from,
 * or the control appears over a board that has nothing for it to do. *(Until **Stage 97** the head carried a second
 * control, the `Show all buckets` toggle, and it had to be asked the same question; it went with the wide view, and
 * the picker's filter is the only board state this page answers with.)*
 */
private suspend fun ApplicationCall.respondOtherTasks(
    session: UserSession,
    canManage: Boolean,
    state: TaskForm? = null,
    bucket: BucketForm = BucketForm(),
    showBucketId: Int = 0,
) = respondHtml {
    // The head's control and the board both read one answer: whether a bucket is chosen, which the picker's own
    // `?show=` names. A stale id is not chosen — it answers as *All buckets*, exactly as the board does.
    val filtered = TaskService.bucketRows().any { it.id == showBucketId }
    appPage(
        session, "other-tasks", "Other tasks", subtitle = OTHER_TASKS_SUBTITLE,
        actions = {
            if (canManage) addBucketButton(if (filtered) showBucketId else 0)
        },
    ) {
        otherTasksContent(session, canManage, state, bucket, showBucketId)
    }
}

/**
 * The page a bucket write that landed redirects to, on the view the reader was looking at.
 *
 * [showBucketId] is the board's bucket filter (Stage 93). A rename and a delete carry the column the reader was
 * reading, so the board comes back narrowed exactly as they left it. The create carries the column it **made**: a
 * new bucket holds nothing, and the board prints only the buckets that do, so unless the answer names the newcomer
 * it would vanish as the reader pressed the box. That is what the wide view used to be forced on for (**Stage 40**,
 * retired by **Stage 97**); pointing the filter at one column reaches the same end with one view control on the page
 * instead of two, and it leaves the reader somewhere they can see what they just added.
 */
private fun bucketsBack(showBucketId: Int = 0): String =
    if (showBucketId > 0) "/app/other-tasks?show=$showBucketId" else "/app/other-tasks"

/**
 * The Assigned tasks page, rendered for whoever asked — a plain read, or one of the column popup's answers
 * (**Stage 124**).
 *
 * The page was rendered inline in its GET route until Stage 124; the bucket popup's POST now has to answer with
 * the same page, because the panel's `+ New bucket` opens that popup *here* rather than on the Other tasks board.
 * One renderer is what keeps the two copies identical: [bucket] is the popup's state (blank and closed on a read,
 * open when `?newbucket=1` asked, filled and open when a name was refused), [canAddBuckets] says whether the
 * reader may name a column at all, and [otherBucketId] / [projectFilter] are the two view filters the page's own
 * pickers post — carried through a write so it answers on the board the reader was looking at.
 */
private suspend fun ApplicationCall.respondAssignedTasks(
    session: UserSession,
    canWriteTasks: Boolean,
    state: TaskForm? = null,
    bucket: BucketForm = BucketForm(),
    otherBucketId: Int = 0,
    projectFilter: Int = 0,
    canAddBuckets: Boolean = false,
) = respondHtml {
    appPage(session, "assigned-tasks", "Assigned tasks", actions = { if (canWriteTasks) newTaskButton() }) {
        assignedTasksContent(
            session,
            canWriteTasks,
            state,
            otherBucketId = otherBucketId,
            projectFilter = projectFilter,
            canAddBuckets = canAddBuckets,
            bucket = bucket,
        )
    }
}

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
    // Put the accounts already on the sheet under the same address rule new ones get (Stage 126). Runs once per
    // database — an `app_settings` marker records the rule version it wrote — and recomputes each address through
    // the very function the create paths call, so there is one rule, not a SQL copy of it that can drift.
    val emailsRewritten = UserEmailBackfill.applyOnce()
    if (emailsRewritten > 0) {
        log.info("Company emails: {} account address(es) rewritten as firstname.surname@murrays.mn.", emailsRewritten)
    }
    // Publish the category catalogue (V14) into the domain's PROJECT_TYPES / TYPE_PREFIX views. Every board
    // panel, forecast tab, create-form picker and `?type=` check reads that snapshot rather than the table, so
    // it has to be loaded before the first request — and the count goes in the boot log because a catalogue
    // that came up empty is the one thing on this page a manager would notice as missing categories, not as a
    // stack trace.
    val categories = ProjectTypeService.list()
    log.info("Project categories: {} loaded from the catalogue.", categories.size)
    // Non-fatal deployment advisories: surfaced every boot so a misconfigured VPS is visible
    // in the log rather than discovered by a user who cannot stay logged in.
    AppConfig.warnings().forEach { log.warn("Launch check: {}", it) }
}

fun main() {
    bootstrapDatabase()
    val port = AppConfig.port
    val host = AppConfig.host
    val server = embeddedServer(Netty, port = port, host = host) {
        install(StatusPages) {
        exception<Throwable> { call, cause -> log.error("Unhandled error on ${call.request.httpMethod.value} ${call.request.path()}", cause)
            call.respondText("Something went wrong. Please go back and try again.", status = HttpStatusCode.InternalServerError)
    }
        status(HttpStatusCode.NotFound) { call, _ -> call.respondText("Page not found.", status = HttpStatusCode.NotFound)
    }
}
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
                // Stage 110: the board is everybody's to read; the opener and its popup are *Create projects*.
                val canCreate = session.may(Permission.CREATE_PROJECTS)
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
                        actions = { if (canCreate) newProjectButton() }) {
                        projectsContent(it, type, canManage = canCreate, filter = filter)
                    }
                }
            }
            get("/app/projects/{id}") {
                val session = call.requireSession() ?: return@get
                val project = call.parameters["id"]?.toIntOrNull()?.let { id -> DemoData.projects.firstOrNull { it.id == id } }
                if (project == null) return@get call.respondRedirect("/app/projects")
                // Stage 110 splits the page's old single "management" flag into the four chips its controls
                // stand for: the header strip and pin are *Edit project details*, the delete button is
                // *Delete projects*, writing cards is *Edit any task*, and reshaping the columns is
                // *Manage buckets* (the project's own named manager still widens both board rights inside
                // [TaskService.mayEditLanes] / [TaskService.mayAddTasks]).
                val canEdit = session.may(Permission.EDIT_PROJECT_DETAILS)
                val canWriteTasks = session.may(Permission.EDIT_ANY_TASK)
                val mayAdd = TaskService.mayAddTasks(project.id, session.employeeNumber, canWriteTasks)
                val state = openingTaskForm(call, session, canWriteTasks, project.id)
                // `?lane=` is read by nothing here (Stage 35). The two lane routes still answer a request with
                // the code they refused it on — `POST /app/projects/{id}/buckets` is a management-only endpoint
                // with no control pointing at it, so that query is the whole of what its outcome says, and it
                // belongs to the URL rather than to this renderer.
                //
                // `?buckets=` is a bucket write's outcome (Stage 73) and this page does read it: `ok` or `err`
                // names which, and `bucketnote` carries the sentence to print over the board. Both are
                // only honoured as a pair — a bare `bucketnote` on a URL somebody typed is a stranger's words on
                // a page about a different save — and the openers that write are drawn for the reader who may use
                // them, so a Viewer who bookmarks the link gets no form, only the sentence the board carries.
                val savedCode = call.request.queryParameters["buckets"]
                val bucketNote = if (savedCode == "ok" || savedCode == "err") {
                    call.request.queryParameters["bucketnote"]?.trim()?.takeIf { it.isNotEmpty() }
                } else null
                // Since **Stage 74** the same three keys can also belong to the board, because the dashed
                // `+ Add bucket` box and a column's `⋯` post to the very same routes the page's bucket picker used
                // to. Since **Stage 79** they are the page's only bucket forms at all — the picker panel under the
                // header is gone.
                // `?bucketsrc=board` says the write came from one of them, and `?laneadd=` / `?laneedit=<id>` /
                // `?lanetyped=` are the popup's own openers — all four are read together, since where the sentence
                // prints depends on whether the form that earned it is still up. The role is asked here, not only
                // inside the renderer, so a Viewer's bookmarked `?laneedit=7` opens nothing at all.
                val mayWriteLanes = TaskService.mayEditLanes(
                    project.id, session.employeeNumber, session.may(Permission.MANAGE_BUCKETS),
                )
                val laneEntry = openingLaneForm(call, mayWriteLanes, bucketNote, savedCode == "err")
                // Stage 80: which columns are showing their completed cards is the URL's `showdone` keys, repeated
                // once per open column. Nothing on the page tracks it in a script, so a reload, a bookmark and
                // the back button all come back to the same board the reader was looking at.
                val doneOpen = openDoneLanesOf(call.request.queryParameters)
                call.respondHtml {
                    appPage(session, "projects", project.name, actions = { if (mayAdd) newTaskButton(project) }) {
                        projectDetailContent(
                            it, project, canEdit, state,
                            bucketNote = bucketNote, bucketNoteIsError = savedCode == "err",
                            noteOnBoard = laneEntry.noteOnBoard, lane = laneEntry.form,
                            doneOpen = doneOpen,
                            canDelete = session.may(Permission.DELETE_PROJECTS),
                            canEditTasks = canWriteTasks,
                            canManageBuckets = session.may(Permission.MANAGE_BUCKETS),
                            canWidenBuckets = session.may(Permission.MANAGE_PROJECT_TYPES),
                        )
                    }
                }
            }
            /**
             * The assigned board: every card that carries an owner, cut into the five status columns.
             *
             * `?new=1` and `?edit=<card>` are the two openers of the card popup, both posted by markup on this
             * page itself — the head's `+ New task` and a card's own click. Stage 47 moved the shared backlog
             * board to its own page; Stage 55 added the reader's personal Other-task board below this one, and
             * Stage 124's empty-panel `+ New bucket` opens the column-create popup here via `?newbucket=1`.
             *
             * Two view keys narrow the page without leaving it, because the URL is the state and a bookmark or a
             * back button restores the same view. `?bucket=<id>` (Stage 88) cuts the reader's own Other-tasks
             * board down to one bucket; `?project=<id>` (Stage 107) cuts the projects board above it down to one
             * project. Absent, mistyped or pointing at a row that no longer exists all answer the *whole* board,
             * so an ageing bookmark opens the full page rather than an empty one.
             */
            get("/app/assigned-tasks") {
                val session = call.requireSession() ?: return@get
                // Stage 110: the board is the reader's own cards; *Edit any task* is what draws it as an
                // editor's board and what the card routes below ask for.
                val canWriteTasks = session.may(Permission.EDIT_ANY_TASK)
                // Stage 90: this page's Other-tasks section carries its own `+ Add task` line for every reader,
                // so this is the one route that opens a backlog create for a non-manager.
                val state = openingTaskForm(call, session, canWriteTasks, allowSelfBacklogCreate = true)
                // Which Other-tasks bucket the board below is narrowed to (Stage 88), and which project the
                // projects board above it is narrowed to (Stage 107). A value that is not a number is no filter,
                // which is the same answer a deleted row gets in [assignedTasksContent].
                val bucket = call.request.queryParameters["bucket"]?.trim()?.toIntOrNull() ?: 0
                val project = call.request.queryParameters["project"]?.trim()?.toIntOrNull() ?: 0
                // **Stage 124:** the empty projects board's `+ New bucket` is a GET opener onto this page. A reader
                // who may name a column asks for that popup open; anybody else's `?newbucket=1` is an ordinary page,
                // exactly as a hand-typed `?new=1` is when the reader may not write a card.
                val canBuckets = session.may(Permission.MANAGE_BUCKETS)
                val bucketState = if (canBuckets && call.request.queryParameters["newbucket"] == "1") {
                    BucketForm(open = true)
                } else {
                    BucketForm()
                }
                call.respondAssignedTasks(
                    session, canWriteTasks, state, bucketState,
                    otherBucketId = bucket, projectFilter = project, canAddBuckets = canBuckets,
                )
            }
            /**
             * The reader's own month plan, for whichever month the address names.
             *
             * Two controls answer that question on the page — the ‹ / › steps (and `This month`), which send
             * `month`, and the box that names any month at all, which sends `pick` — and both are keys of the same
             * shape the forecast sheet reads. A step is the control that was *acted on*, so it is believed first;
             * the box is read only when no step was taken. A word that is not a month is not an error page:
             * [monthKeyOf] answers this month, so a mistyped or ageing bookmark still opens a plan.
             */
            get("/app/calendar") {
                val session = call.requireSession() ?: return@get
                val params = call.request.queryParameters
                call.respondCalendar(session, monthKeyOf(params["month"], params["pick"]))
            }
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
                // Stage 110: the money sheet is *View Forecasts tab*. A role without it has no link to this
                // page and no business reading it by hand either, so the address answers the projects board.
                if (!session.may(Permission.VIEW_FORECASTS)) return@get call.respondRedirect("/app/projects")
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
                if (!session.may(Permission.EDIT_FORECAST_AMOUNTS)) {
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
                // Stage 110: writing a lesson is *Edit any task*, the chip that already means "may write
                // shared work"; reading the sheet stays everybody's.
                val canManage = session.may(Permission.EDIT_ANY_TASK)
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
                val canManage = session.may(Permission.EDIT_ANY_TASK)
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
                    canManage = session.may(Permission.EDIT_ANY_TASK),
                )
                // A row that has gone, or that has changed hands since the page was drawn, has no field on
                // this sheet to print either answer under — the re-read list is the whole of the reply, and
                // it shows the row gone or its buttons gone. Same pair of branches the backlog's column
                // routes send back for a denied rename.
                call.respondRedirect(lessonFilterOfForm(form).queryFor())
            }
            /** Take one lesson off the database. The one write here with no way back, hence the confirm. */
            post("/app/lessons/delete") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                LessonService.remove(
                    id = form["id"]?.trim()?.toIntOrNull() ?: 0,
                    employeeNumber = session.employeeNumber,
                    canManage = session.may(Permission.EDIT_ANY_TASK),
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
             * `?show=<id>` is the board's Bucket picker (Stage 93): one column's group and nothing else. It is
             * `show` rather than `bucket` because `bucket` already names a column's popup on this route.
             *
             * *(Until **Stage 97** a fifth key was read here — `?allbuckets=1`, the head's toggle's wide view. The
             * picker covers both of that control's readings, so the toggle, the key and the view went together; a
             * stale bookmark carrying it now simply gets the ordinary board.)*
             */
            get("/app/other-tasks") {
                val session = call.requireSession() ?: return@get
                // Stage 110: the shared backlog board is drawn as an editor's for a reader who may edit its
                // cards (*Edit any task*) or reshape its columns (*Manage buckets*) — the same pair the
                // sidebar link is gated on. Everybody else keeps Stage 51's one-bucket slice of their own work.
                val canWriteTasks = session.may(Permission.EDIT_ANY_TASK)
                val canBuckets = session.may(Permission.MANAGE_BUCKETS)
                val canManage = canWriteTasks || canBuckets
                val state = openingTaskForm(call, session, canWriteTasks)
                val bucket = openingBucketForm(call, canBuckets)
                val showBucketId = call.request.queryParameters["show"]?.trim()?.toIntOrNull() ?: 0
                call.respondOtherTasks(session, canManage, state, bucket, showBucketId)
            }
            sectionRoute("/app/todo", "todo", "To-do") { todoContent(it) }
            /**
             * The search page (Stage 134), behind the bottom bar's *Search* tab: one GET box over projects,
             * cards, lessons, the reader's own to-do and people.
             *
             * It is not a [sectionRoute] for the reason the calendar and the forecast sheet are not — that helper
             * hands its view a `DIV.(UserSession)` and never sees the query, and here the query *is* the page. The
             * words are trimmed and handed on as they arrived: matching, the two-letter floor and the decision
             * about which groups this role is offered at all are [searchGroups]' job, so the route stays the one
             * place that reads the address and the renderer stays testable without one.
             */
            get("/app/search") {
                val session = call.requireSession() ?: return@get
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                call.respondHtml { appPage(session, "search", "Search") { searchContent(it, query) } }
            }
            get("/app/access-control") {
                val session = call.requireSession() ?: return@get
                // Stage 110: the page is two functions now — the roles table (*Manage roles & permissions*)
                // and the People sheet (*Manage employees*) — so either chip opens it, and an account with
                // neither is sent back to the app rather than shown a read-only copy of other people's keys.
                val canPeople = session.may(Permission.MANAGE_EMPLOYEES)
                val canRoles = session.may(Permission.MANAGE_ROLES)
                if (!canPeople && !canRoles) return@get call.respondRedirect("/app")
                val query = call.request.queryParameters
                call.respondAccess(
                    session = session,
                    roleForm = roleFormOf(query, canRoles),
                    status = PasswordStatus.fromCode(query["pw"]),
                    detail = query["who"],
                )
            }
            /**
             * The Settings page: the Team sheet and the reader's own account.
             *
             * `q`, `position`, `role` and `sort` are the sheet's own view — the search box, the two
             * pickers and whichever column header was last pressed. `new=1` and `edit=<employee number>`
             * are the two openers, each a GET onto this route so a reader with no JavaScript gets the
             * same popup as a fresh page load rather than a dead button. `note` is the one-shot line
             * under the Team heading for an account that was just added to or taken off.
             *
             * The account behind `?edit=` is read from the database and never from a form, and the popup's
             * rights come from the reader rather than the row: a number this account may not change answers
             * with the ordinary sheet and no form at all.
             */
            get("/app/settings") {
                val session = call.requireSession() ?: return@get
                val query = call.request.queryParameters
                // Stage 123: Settings is every account's page. The panels are chips, so a reader holding none
                // of them — a Project Manager, an Employee — sees exactly one section: *Your account*, with
                // its change-password form. Nobody is redirected away from their own account any more.
                val canPeople = session.may(Permission.MANAGE_EMPLOYEES)
                val canRoles = session.may(Permission.MANAGE_ROLES)
                val canTypes = session.may(Permission.MANAGE_PROJECT_TYPES)
                val canEmail = session.may(Permission.MANAGE_EMAIL_SETTINGS)
                // Stage 123: another person's password is `EMP0000`'s alone, whatever else a role may hold.
                val isSuperAdmin = session.employeeNumber == UserService.SUPER_ADMIN
                val row = if (canPeople) EmployeeService.row(query["edit"].orEmpty(), session.employeeNumber, canPeople, canRoles) else null
                val account = row?.let { EmployeeService.account(it.employeeNumber) }
                // Stage 117: the department picker is the Owner/Administrator pair's, so the form is told
                // whether to draw it from the reader's role rather than from the row's.
                val canDepartments = Authorization.isOwnerOrAdministrator(session.employeeNumber)
                call.respondSettings(
                    session,
                    employeeFilterOf(query),
                    employeeFormOf(
                        query = query,
                        row = row,
                        account = account,
                        positionName = account?.let { EmployeeService.positionOf(it) }.orEmpty(),
                        mayAdd = canPeople,
                        mayEdit = canPeople || canRoles,
                        maySetRoles = canRoles,
                        maySetDepartment = canDepartments,
                        maySetPassword = isSuperAdmin,
                    ),
                    note = PasswordStatus.fromCode(query["note"]),
                    accountStatus = PasswordStatus.fromCode(query["pw"]),
                    mailStatus = PasswordStatus.fromCode(query["mail"]),
                    mailDetail = query["mailerr"]?.trim()?.takeIf { it.isNotEmpty() },
                    bucketNote = PasswordStatus.fromCode(query["lane"]),
                    bucketDetail = query["lanedetail"]?.trim()?.takeIf { it.isNotEmpty() },
                    bucketDefaultsType = BucketDefaultsService.typeFor(query["lanes"]),
                    typeNote = PasswordStatus.fromCode(query["cat"]),
                    typeDetail = query["caterr"]?.trim()?.takeIf { it.isNotEmpty() },
                    typeBeingEdited = query["edittype"]?.trim()?.takeIf { it.isNotEmpty() }.orEmpty(),
                )
            }

            /**
             * The reader changes their *own* password (Stage 123).
             *
             * Every account may call this — it is the one write on Settings that is not a chip's. The service
             * proves the current password and checks the two new words agree before it touches anything; on
             * success every session of that account is revoked (a stolen cookie must not survive the change)
             * and this browser is handed a fresh one, so the reader stays signed in where they are. A refusal
             * comes back as `?pw=<status>` onto the page, and the form prints it under the field it belongs to.
             */
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
                    // ChangePassword revoked every session, this one included: issue the browser its own
                    // again so the reader sees the answer on the page rather than the login screen.
                    val token = SessionRepository.create(
                        employeeNumber = session.employeeNumber,
                        ttlHours = AppConfig.sessionTtlHours,
                        userAgent = call.request.headers[HttpHeaders.UserAgent].orEmpty(),
                        ip = call.clientIp(),
                    )
                    call.setSessionCookie(token)
                }
                call.respondRedirect("$SETTINGS_PATH?pw=${status.name}")
            }

            /**
             * Add a project category (the V14 catalogue Settings' *Project types* panel edits).
             *
             * Management-only here and again inside [ProjectTypeService.add]. The letter is what makes a
             * category more than a label — every project number filed under it starts with that letter — so a
             * collision with a letter already in use is refused rather than quietly renumbered. A refusal
             * writes nothing and comes back as the panel's own flash line.
             */
            post("/app/settings/types/add") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val result = ProjectTypeService.add(
                    rawCode = form["code"].orEmpty(),
                    rawName = form["name"].orEmpty(),
                    canManage = session.may(Permission.MANAGE_PROJECT_TYPES),
                )
                call.respondTypeAnswer(result, PasswordStatus.TYPE_ADDED)
            }

            /**
             * Rename a category, carrying the label across the projects and lane defaults that store it.
             *
             * The letter is not part of this write and cannot be: it is the catalogue's key and the first
             * token of numbers already issued, so `S-04` written under the old name still reads `S-04` after
             * the rename. The answer says how many projects moved with the label.
             */
            post("/app/settings/types/rename") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val result = ProjectTypeService.rename(
                    oldName = form["name"].orEmpty(),
                    rawName = form["newname"].orEmpty(),
                    canManage = session.may(Permission.MANAGE_PROJECT_TYPES),
                )
                call.respondTypeAnswer(result, PasswordStatus.TYPE_RENAMED)
            }

            /**
             * Retire a category — refused while any project is still filed under it, which is the whole
             * difference between a label nobody is using and forty orphaned rows. See [ProjectTypeService.remove].
             */
            post("/app/settings/types/delete") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val result = ProjectTypeService.remove(
                    code = form["code"].orEmpty(),
                    canManage = session.may(Permission.MANAGE_PROJECT_TYPES),
                )
                call.respondTypeAnswer(result, PasswordStatus.TYPE_REMOVED)
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
                val canPeople = session.may(Permission.MANAGE_EMPLOYEES)
                val canRoles = session.may(Permission.MANAGE_ROLES)
                // Stage 117: the owner/administrator pair alone writes a department; the service re-checks it.
                val canDepartments = Authorization.isOwnerOrAdministrator(session.employeeNumber)
                // Stage 123: another person's password is the super admin's alone; the service re-checks it.
                val canSetPassword = session.employeeNumber == UserService.SUPER_ADMIN
                val form = call.receiveParameters()
                val filter = employeeFilterOfForm(form)
                val result = EmployeeService.save(
                    draft = employeeDraftOf(form),
                    actor = session.employeeNumber,
                    canManage = canPeople,
                    canAdmin = canPeople,
                    canSetRoles = canRoles,
                    canSetDepartment = canDepartments,
                    canSetPassword = canSetPassword,
                )
                if (result.saved) {
                    val note = if (result.isEdit) PasswordStatus.USER_UPDATED else PasswordStatus.USER_ADDED
                    return@post call.respondRedirect(filter.queryFor(extra = listOf("note=${note.name}")))
                }
                call.respondSettings(
                    session,
                    filter,
                    employeeFormOf(
                        result, mayAdd = canPeople, maySetRoles = canRoles, filter = filter,
                        maySetProfile = canPeople, maySetDepartment = canDepartments,
                        maySetPassword = canSetPassword,
                    ),
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
                if (!session.may(Permission.MANAGE_EMPLOYEES)) {
                    return@post call.respondRedirect(filter.queryFor())
                }
                val status = UserService.deleteUser(
                    employeeNumber = employeeNumber,
                    callerEmpNo = session.employeeNumber,
                    callerCanAdmin = session.may(Permission.MANAGE_ROLES),
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
                if (!session.may(Permission.MANAGE_EMAIL_SETTINGS)) {
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
                val canMail = session.may(Permission.MANAGE_EMAIL_SETTINGS)
                val target = call.receiveParameters()["on"]?.trim() == "true"
                if (!MailSwitch.setEnabled(on = target, canAdmin = canMail)) {
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
             *
             * Since **Stage 83** the form sends a name and the category, and nothing else: the colour is the
             * app's answer, so there is no swatch to carry back on a refusal — only the name the reader typed,
             * which the browser keeps in the field it came from.
             */
            post("/app/settings/buckets/add") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val category = BucketDefaultsService.typeFor(form["lanes"])
                val result = BucketDefaultsService.add(
                    type = category,
                    rawName = form["name"].orEmpty(),
                    canManage = session.may(Permission.MANAGE_PROJECT_TYPES),
                )
                val code = if (result.ok) PasswordStatus.LANE_ADDED else PasswordStatus.LANE_REFUSED
                // Stage 98: the receipt rides the success redirect too. [BucketDefaultsService.add] now says how
                // many boards took the new column, and that number is the only evidence a reader standing on
                // Settings has that the add reached the projects — dropping it left the panel claiming a catalogue
                // row while the boards behind it were the thing the reader was asking about.
                val reason = "&lanedetail=${java.net.URLEncoder.encode(result.message, "UTF-8")}"
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
                val ok = BucketDefaultsService.remove(id, session.may(Permission.MANAGE_PROJECT_TYPES))
                val code = if (ok) PasswordStatus.LANE_REMOVED else PasswordStatus.LANE_REFUSED
                call.respondRedirect(
                    "$SETTINGS_PATH?lane=$code&lanes=${java.net.URLEncoder.encode(category, "UTF-8")}",
                )
            }

            post("/app/projects") {
                val session = call.requireSession() ?: return@post
                // Creating a project is *Create projects* (Stage 110). The opener and the popup are hidden
                // without it too, but hiding markup is not a check — this route answers a hand-built POST
                // exactly the way /app/projects/{id}/manager already does.
                if (!session.may(Permission.CREATE_PROJECTS)) {
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
                        // **Stage 109: the picker under Revenue says which money this is.** A create that posted
                        // no field at all still lands in tugrik — [mn.murray.pts.service.ProjectDraft] defaults to
                        // it — and the service refuses a code it cannot name, so nothing here second-guesses.
                        currency = fields["currency"].orEmpty(),
                        // **Stage 104 put a bucket line back on this form, and this route reads it.** Every
                        // `new_bucket` the popup posts arrives — the hidden twin behind each chip and the name
                        // still sitting in the box alike, since a reader who typed one and pressed Create
                        // instead of Add asked for that column as surely as one who pressed Add. Blank values are
                        // dropped by the service, order is the form's, and nothing here touches the category's
                        // catalogue: there is no `also_type` on this popup, so a hand-built POST naming one is
                        // read by nobody. Stage 92's "this route reads no bucket field" is superseded; Stage 98's
                        // board-from-the-category rule is not — the catalogue rows still come first.
                        newBuckets = fields.getAll("new_bucket").orEmpty(),
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
                if (!session.may(Permission.EDIT_PROJECT_DETAILS)) {
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
                if (!session.may(Permission.EDIT_PROJECT_DETAILS)) {
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
                // The refusal hands the page back with the strip still open; every other control is drawn
                // for the chips this reader actually holds, not for the one that let them edit the strip.
                val canWriteTasks = session.may(Permission.EDIT_ANY_TASK)
                val mayAdd = TaskService.mayAddTasks(project.id, session.employeeNumber, canWriteTasks)
                val state = openingTaskForm(call, session, canWriteTasks, project.id)
                call.respondHtml {
                    appPage(session, "projects", project.name, actions = { if (mayAdd) newTaskButton(project) }) {
                        projectDetailContent(
                            it, project, canEdit = true, state = state,
                            edit = ProjectForm(result.values, result.errors),
                            canDelete = session.may(Permission.DELETE_PROJECTS),
                            canEditTasks = canWriteTasks,
                            canManageBuckets = session.may(Permission.MANAGE_BUCKETS),
                            canWidenBuckets = session.may(Permission.MANAGE_PROJECT_TYPES),
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
                if (!session.may(Permission.DELETE_PROJECTS)) {
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
                    session.may(Permission.MANAGE_BUCKETS),
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
             * Save the whole set of one project's buckets, in one write (Stage 73).
             *
             * The form posts the bucket **names** it carries plus the one name typed into its add box, and
             * every rule about that set — whose board this is, a name that is not a bucket, a bucket that still
             * holds cards, a name only management may add to the category — is
             * [mn.murray.pts.service.TaskService.saveLanes]'s. What is left here is the round trip: the redirect
             * names the outcome in `?buckets=` and carries the sentence in `?bucketnote=`, which
             * [projectDetailContent] prints over `#projectBoard` — the block the form's `data-live-target` asks
             * app.js to swap, so the columns the reader just changed and the words that say what happened to them
             * arrive together and nothing else on the page moves. A refused save changed no board, so what comes
             * back is the board as it stands plus the reason for the refusal.
             *
             * Since **Stage 79** the page's one caller is the dashed `+ Add bucket` box, which posts the board's
             * whole set as hidden fields plus its own new name; the tick-list panel that used to post the same
             * route from under the header is gone, so what a reader adds here never silently unticks anything.
             *
             * Two refusals are not a page to come back to: [LaneError.DENIED] means the reader may not run this
             * board at all — the board draws no `⋯` and no add box for them, so only a hand-built POST reaches here — and
             * [LaneError.NO_PROJECT] means the project is gone. Both go to the project list, the same answer the
             * two older lane routes give.
             */
            post("/app/projects/{id}/buckets/save") {
                val session = call.requireSession() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/app/projects")
                val fields = call.receiveParameters()
                val result = TaskService.saveLanes(
                    id,
                    fields.getAll("bucket").orEmpty(),
                    fields["new_bucket"].orEmpty(),
                    session.employeeNumber,
                    session.may(Permission.MANAGE_BUCKETS),
                    // Stage 77: does a bucket this form names also join the category's default buckets? The tick
                    // is the reader's answer and its absence is the question answered "no" — a new column stays
                    // this project's own. Nothing here defaults it on: a field a browser did not send because the
                    // box was clear means the box is clear.
                    alsoForType = fields["also_type"].orEmpty().isNotEmpty(),
                    // Stage 110: the tick writes the category's catalogue, so it answers to that chip, not to
                    // the one that merely runs this board.
                    canManageTypes = session.may(Permission.MANAGE_PROJECT_TYPES),
                )
                val error = result.error
                if (error == LaneError.DENIED || error == LaneError.NO_PROJECT) {
                    return@post call.respondRedirect("/app/projects")
                }
                // One route, and since **Stage 79** one form on this page calls it: the dashed box, which posts the
                // whole set. `src` is the box's own mark and is what [laneRedirect] uses to put the sentence over
                // the columns it just changed. A refusal from the box also hands the
                // reader back the word they typed, in the popup that is still up. A request with no `src` — a
                // bookmark from before the picker panel went — builds the identical URL it always did, and the
                // page prints its sentence over the board now that the panel is not there to hold it.
                val typed = fields["new_bucket"].orEmpty().trim()
                val fromBoard = fields["src"] == "board"
                call.respondRedirect(
                    laneRedirect(
                        id,
                        result.ok,
                        result.message ?: if (result.ok) "Saved." else "Nothing changed.",
                        fromBoard,
                        reopen = if (fromBoard && !result.ok) {
                            LaneForm(open = true, typed = typed)
                        } else null,
                        forType = fields["also_type"].orEmpty().isNotEmpty(),
                    )
                )
            }
            /**
             * Retitle one column of this project's board (Stage 74) — the route a column's `⋯` posts to.
             *
             * The lane is named by **id** and the new heading by `name`, and every rule about the pair — whose board
             * this is, whether this lane is even on it, and a name blank, too long or already taken — is
             * [mn.murray.pts.service.TaskService.renameLane]'s. Since **Stage 75** no name is protected: the three a
             * board opens with are retitled like any other column, which is why nothing here asks whether the lane
             * is one of them. What is left here is the round trip, and it is the same one the box above uses: the outcome and its sentence in
             * the URL, the popup put back up only when the write was refused, since a rename that landed is its own
             * answer in the column head the reader is looking at.
             *
             * [LaneError.DENIED] and [LaneError.NO_PROJECT] are not a page to come back to — the board draws no `⋯`
             * for a reader who may not write it, so only a hand-built POST reaches them — and [LaneError.NO_LANE] is
             * the stale-request case: the column the popup was opened from is gone, so the form that names it is
             * closed rather than reopened over a board that no longer has it, and its sentence is printed on the
             * board itself.
             */
            post("/app/projects/{id}/buckets/rename") {
                val session = call.requireSession() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/app/projects")
                val fields = call.receiveParameters()
                val lane = fields["lane"]?.trim()?.toIntOrNull() ?: 0
                val name = fields["name"].orEmpty().trim()
                val slot = colourSlotOf(fields["colour"])
                val result = TaskService.renameLane(
                    id,
                    lane,
                    name,
                    session.employeeNumber,
                    session.may(Permission.MANAGE_BUCKETS),
                    // Stage 80: the same swatch row the add half draws. A rename that only resubmits the name
                    // sends the colour the column already wears, so this field can pin a shade without changing
                    // a heading — and [mn.murray.pts.service.TaskService.renameLane] writes it even when the
                    // name is unchanged, which is what makes a colour-only post a legitimate one.
                    colourSlot = slot,
                )
                val error = result.error
                if (error == LaneError.DENIED || error == LaneError.NO_PROJECT) {
                    return@post call.respondRedirect("/app/projects")
                }
                val gone = error == LaneError.NO_LANE
                call.respondRedirect(
                    laneRedirect(
                        id,
                        result.ok,
                        result.message
                            ?: if (result.ok) "$name is that column's name now." else "Nothing changed.",
                        fields["src"] == "board",
                        reopen = if (!result.ok && !gone) {
                            LaneForm(laneId = lane, typed = name, colourSlot = slot)
                        } else null,
                    )
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
                // Stage 110: the one flag this route needs is *Edit any task* — it is the chip that says a reader
                // may write a card, and the services below turn it into "management anywhere" or "this project's
                // own manager here", exactly as the old rank did.
                val canManage = session.may(Permission.EDIT_ANY_TASK)
                // The shared backlog page is drawn as an editor's for either chip — writing a card or naming a
                // column — which is the same pair the `GET /app/other-tasks` route reads. A refusal answered on that
                // page has to draw the openers the reader holds, not only the one that let them press Save.
                val canBoard = canManage || session.may(Permission.MANAGE_BUCKETS)
                val mayAdd = TaskService.mayAddTasks(projectId, session.employeeNumber, canManage)
                val taskId = form["taskId"]?.trim()?.toIntOrNull() ?: 0
                val draft = taskDraftOf(form)
                val backlogCreate = taskId == 0 && draft.columnId != null
                // Stage 51 had narrowed a backlog create to management on every board. Since **Stage 90** the
                // reader's own Other-tasks line is back on the Assigned page, because [TaskService.createBacklog]
                // has always pinned an ordinary account's card to themselves and *Not started* before it is
                // validated. So the flag, not the role, decides which create this is; the pinning is the service's,
                // and a hand-built POST that names a colleague still writes the caller's own card.
                // The shared Other-tasks page keeps its Stage 51 shape — no line, no create — so a column-carrying
                // POST claiming that page is still discarded back to the reader's scoped board rather than saved.
                if (backlogCreate && !canManage && isOtherBack(back)) {
                    return@post call.respondOtherTasks(
                        session, canBoard, showBucketId = bucketOfOtherBack(back),
                    )
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
                // The board a saved card answers on is named by `back` alone — on the backlog that token carries the
                // bucket filter as `other:<id>` (Stage 93), so a save comes back on the narrowed board the popup was
                // opened from. *(Stage 40 used to pass a second piece of view state through [taskRedirect] — the
                // popup's hidden `allbuckets` field, so a save could not hide the columns the reader had opened up.
                // **Stage 97** retired the wide view, and the read-back below that re-printed a refusal with it.)*
                if (result.taskId != null) return@post call.respondRedirect(taskRedirect(back, projectId))
                // Rejected: the popup comes back open on the page it was posted from, with what
                // was typed still in the inputs and each message under its own field. The card is read
                // again so its steps and log ride along — a rejected due date must not close the list
                // somebody was writing next to it.
                val refused = if (taskId > 0) TaskService.openableBy(taskId, session.employeeNumber, canManage) else null
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
                    // other page's popup lists the three phase names from its own state instead.
                    lanes = if (back == BACK_PROJECT && projectId > 0) TaskService.lanesOf(projectId) else emptyList(),
                    // ... and a create that was refused on the *general* board keeps the per-project column list
                    // it was opened with (Stage 111). Without it the Sub-category picker the reader was just
                    // refused on would fall back to a list taken from no board at all, and the project they
                    // named would have nothing to say about where the card lands.
                    lanesByProject = if (taskId == 0 && !backlogCreate && projectId <= 0) {
                        TaskService.lanesByProjectName()
                    } else {
                        emptyMap()
                    },
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
                                // A refused card write comes back on the project page with the board drawn for the
                                // chips this reader holds — the one that let them post this card (*Edit any task*)
                                // plus the three that decide what else on the page is live.
                                projectDetailContent(
                                    it, project, session.may(Permission.EDIT_PROJECT_DETAILS), state,
                                    canDelete = session.may(Permission.DELETE_PROJECTS),
                                    canEditTasks = canManage,
                                    canManageBuckets = session.may(Permission.MANAGE_BUCKETS),
                                    canWidenBuckets = session.may(Permission.MANAGE_PROJECT_TYPES),
                                )
                            }
                        }
                    }
                }
                // The page a refusal returns to is the page the form was opened on, which `back` now says out
                // loud rather than leaving to be inferred: a backlog card has been written from the Other tasks
                // board since Stage 47, and answering it on the assigned board would close the popup it came
                // from and redraw a board that never held the card. Both pages draw the same `#taskModal`, so
                // `app.js` swaps the refusal either way — what moves is the board behind it.
                if (isOtherBack(back)) {
                    return@post call.respondOtherTasks(
                        session, canBoard, state = state, showBucketId = bucketOfOtherBack(back),
                    )
                }
                call.respondAssignedTasks(
                    session, canManage, state,
                    // The filters this page was reading ride `back` (Stage 88 for the Other-tasks bucket,
                    // Stage 107 for the project), so a refused save comes back on the same narrowed page the
                    // popup was opened from.
                    otherBucketId = bucketOfBack(back),
                    projectFilter = projectOfBack(back),
                    // The same page also carries the column popup since Stage 124, so a refusal keeps the panel's
                    // `+ New bucket` offer — one renderer, so a page arriving from a save is the page arriving
                    // from a read.
                    canAddBuckets = session.may(Permission.MANAGE_BUCKETS),
                )
            }
            /**
             * Deletes the card named by the form's hidden `taskId`, its steps and its notes going with it.
             *
             * The **Delete task** button beside a card's note box is what points here since Stage 72; before that
             * this was a management-only endpoint with no control behind it, left over from the card menu
             * Stage 35 took out. It stays the only door: [TaskService.deleteAssigned] refuses a caller who is
             * not management whatever the markup offered, and the popup prints the button on the same flag.
             *
             * Success and a refusal both land on the board the form names, and there is no popup to bring back
             * up — the card it would open on is the thing that has just gone. The board is named by `back` alone,
             * and on the backlog that token carries the bucket filter (Stage 93).
             */
            post("/app/assigned-tasks/delete") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val id = form["taskId"]?.trim()?.toIntOrNull()
                if (id != null && session.may(Permission.EDIT_ANY_TASK)) {
                    TaskService.deleteAssigned(id, session.employeeNumber, true)
                }
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form)),
                )
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
                // Stage 110: the panel's one write is *Assign tasks to others* — the chip that says a reader may
                // put work on somebody else's board. The Team page draws the panel on the same chip, so the guard
                // and the markup answer the same question.
                if (!session.may(Permission.ASSIGN_TASKS)) {
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
                val canManage = session.may(Permission.EDIT_ANY_TASK)
                if (id != null) {
                    TaskService.updateStatus(id, status, session.employeeNumber, canManage)
                }
                val back = call.request.headers[HttpHeaders.Referrer] ?: "/app/assigned-tasks"
                call.respondRedirect(back)
            }
            /**
             * Take one lane off this project's board (V7, Stage 31) — the route the **×** on a column head used
             * to post to, which Stage 35 took off the board and **Stage 74** put back as the `Remove bucket`
             * button in a column's `⋯` popup, on the one board that prints those columns: a project with no cards.
             *
             * The twin of the route above, and by design: one field (`bucket`, the lane's own id), the whole
             * rule inside [TaskService.removeLane], and the outcome back to the board the reader is standing on.
             * The project comes from the URL and the lane from the form, and the service is what checks the two
             * belong to each other — which is why a POST naming another project's lane removes nothing. Without the
             * popup's `src` mark this is still Stage 31's round trip, `?lane=` carrying a code nothing on the page
             * any longer turns into a sentence; with it, the answer is the board's own.
             *
             * **Since Stage 81 the answer also names the work.** A column that holds cards is removed all the same,
             * and what it held goes to this project's Archive rather than being refused a move it has no way to
             * make — the count the popup prints is the consequence, not a gate — so the sentence has to say where
             * the cards are as well as that the column is gone.
             */
            post("/app/projects/{id}/buckets/delete") {
                val session = call.requireSession() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/app/projects")
                val fields = call.receiveParameters()
                val lane = fields["bucket"]?.trim()?.toIntOrNull() ?: 0
                val canManage = session.may(Permission.MANAGE_BUCKETS)
                val fromBoard = fields["src"] == "board"
                // **Stage 81: an occupied column comes off, and its work goes to Archive.** Until now this route
                // re-read the count and refused the press when a card had arrived since the page was drawn
                // (Stage 74's stale-page guard, kept through Stage 75 and the retirement of `LaneError.HAS_CARDS`),
                // because the schema's `ON DELETE SET NULL` would have left those cards filed under nothing. The
                // service now archives them itself, in the same transaction order that makes the snapshot stick
                // ([mn.murray.pts.service.TaskService.removeLane] writes the rows before it deletes the lane), so
                // there is nothing left for the route to protect the reader from and no count worth re-asking.
                val removal = TaskService.removeLane(
                    id,
                    lane,
                    session.employeeNumber,
                    canManage,
                )
                if (removal.error == LaneError.DENIED || removal.error == LaneError.NO_PROJECT) {
                    return@post call.respondRedirect("/app/projects")
                }
                // Since **Stage 74** the popup on a column's `⋯` posts here again, and it says so with the same
                // `src` field the add box carries. From there the answer is the board's: one column shorter, or the
                // reason printed over the columns with the popup shut — shut, because a removal has no field of its
                // own to put a sentence under, and this popup's fields are about the column's *name*. Without `src`
                // the older round trip stands exactly as it was, code in `?lane=` and nothing on the page reading it.
                if (fromBoard) {
                    val note = when {
                        !removal.ok -> laneRemovalNote(removal.error ?: LaneError.NO_LANE)
                        removal.archived > 0 -> {
                            "That bucket is off the board, and its ${removal.archived} " +
                                "${if (removal.archived == 1) "card" else "cards"} left the board with it."
                        }
                        else -> "That bucket is off the board."
                    }
                    call.respondRedirect(laneRedirect(id, removal.ok, note, true))
                } else {
                    // The older round trip: a bare id, no sentence, and the refusal's code in `?lane=` for the
                    // bookmark that still expects Stage 31's shape.
                    val error = removal.error
                    call.respondRedirect(
                        if (error == null) "/app/projects/$id" else "/app/projects/$id?lane=${error.name.lowercase()}"
                    )
                }
            }
            /**
             * Put one archived card back on its project's board (Stage 81).
             *
             * The whole of the promise the flag design was chosen for: the row was never moved or copied, so its
             * steps, its log and its lesson learnt are what they were the moment it left, and this write clears
             * the flag and names a lane. Which lane is decided inside
             * [mn.murray.pts.service.TaskService.restoreCard] — the column the card remembers while that column is
             * still on this project, otherwise the board's first — and the answer comes back in the sentence,
             * because a reader who restores a card from a bucket that no longer exists should be told where it
             * actually landed.
             *
             * **No page prints a control for this since Stage 106.** It was the Archive panel's first button, and
             * that panel went with its 🗄 opener at the user's request. The route is kept rather than torn out, the
             * way Stage 84 kept `/app/tasks/archive` after its button: it is the card-level half of a mechanism
             * whose rows are still in the database, and a hand-built POST still works for the board's own writers
             * ([mn.murray.pts.service.TaskService.restoreCard] checks that right again).
             */
            post("/app/projects/{id}/archive/restore") {
                val session = call.requireSession() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/app/projects")
                val taskId = call.receiveParameters()["taskId"]?.trim()?.toIntOrNull()
                val canManage = session.may(Permission.MANAGE_BUCKETS)
                val lane = taskId?.let {
                    TaskService.restoreCard(it, id, session.employeeNumber, canManage)
                }
                call.respondRedirect(
                    laneRedirect(
                        id,
                        lane != null,
                        if (lane != null) "That card is back on the board, in $lane."
                        else "That card is not in this project's archive.",
                        onBoard = true,
                    )
                )
            }
            /**
             * Delete an archived card for good (Stage 81) — the last permanent delete a project card has.
             *
             * The panel's second button until **Stage 106** retired the panel; the route stays for the same
             * reason the restore does. A card that left the board with its bucket can still be destroyed for good
             * by a hand-built POST, one deliberate step later. (Stage 84 put the live card popup's **Delete task**
             * back, so this is no longer the *only* permanent delete on a project — it is the one for cards already
             * archived.) [mn.murray.pts.service.TaskService.deleteArchived] refuses a card that
             * is not archived, so a hand-built POST naming a live card deletes nothing, and the plan and the log go
             * with the row by the same `ON DELETE CASCADE` the older delete has always relied on.
             */
            post("/app/projects/{id}/archive/delete") {
                val session = call.requireSession() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/app/projects")
                val taskId = call.receiveParameters()["taskId"]?.trim()?.toIntOrNull()
                val canManage = session.may(Permission.MANAGE_BUCKETS)
                val gone = taskId != null && TaskService.deleteArchived(
                    taskId, id, session.employeeNumber, canManage,
                )
                call.respondRedirect(
                    laneRedirect(
                        id,
                        gone,
                        if (gone) "That card is gone, with its steps and its notes."
                        else "That card is not in this project's archive.",
                        onBoard = true,
                    )
                )
            }
            /**
             * Take one card off its project's board and into that project's Archive (Stage 81).
             *
             * **No page prints a control for this since Stage 84.** Stage 81 put an **Archive task** button on a
             * project card's popup in place of **Delete task**; Stage 84 removed that button at the user's request
             * and restored the delete, so the route is left with no caller. The Archive itself is still written —
             * removing a bucket archives the cards standing in it (`TaskService.archiveLane`, Stage 81) — which is
             * why the route and its service are kept rather than torn out: they are the card-level half of a
             * mechanism that is now driven by the bucket. Since **Stage 106** no page shows the archived rows
             * either, but the routes that read them back are kept the same way. The right is the board's
             * ([mn.murray.pts.service.TaskService.mayEditLanes]: management or this project's manager).
             *
             * Like the delete, this posts no `edit`: there is no card to be open on afterwards. The redirect is the
             * project page with its sentence over the board, and the note says the card left it.
             */
            post("/app/tasks/archive") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val id = form["taskId"]?.trim()?.toIntOrNull()
                val projectId = projectIdOf(form)
                val canManage = session.may(Permission.MANAGE_BUCKETS)
                val archived = id != null && TaskService.archiveCard(id, session.employeeNumber, canManage)
                call.respondRedirect(
                    if (archived && projectId > 0) {
                        laneRedirect(
                            projectId, true,
                            "That card left the board and is archived.",
                            onBoard = true,
                        )
                    } else {
                        taskRedirect(backOf(form), projectId)
                    }
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
                val canManage = session.may(Permission.MANAGE_BUCKETS)
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
             * management and the card's own assignee write the list and work down it (the assignee's
             * right to add a step is Stage 89's), and taking one off stays management's.
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
                    canManage = session.may(Permission.EDIT_ANY_TASK),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form)),
                )
            }
            post("/app/tasks/checklist/toggle") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val itemId = form["itemId"]?.trim()?.toIntOrNull() ?: 0
                TaskService.toggleChecklistItem(
                    itemId,
                    callerEmpNo = session.employeeNumber,
                    canManage = session.may(Permission.EDIT_ANY_TASK),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form)),
                )
            }
            post("/app/tasks/checklist/remove") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val itemId = form["itemId"]?.trim()?.toIntOrNull() ?: 0
                TaskService.removeChecklistItem(
                    itemId,
                    callerEmpNo = session.employeeNumber,
                    canManage = session.may(Permission.EDIT_ANY_TASK),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form)),
                )
            }
            /**
             * The two note moves: append one, and taking one off again.
             *
             * The same shape as a checklist write — one row, one redirect, and only the
             * `#task-notes-<card>` block swapped in place — because a note is written while the card's form
             * is open, and closing it to say "saved" would interrupt the thing the reader was doing. Adding
             * is open to the assignee as well as an account with *Edit any task* ([TaskService.addNote]); removing
             * needs that chip alone, so the log cannot be quietly rewritten by whoever wrote the line.
             */
            post("/app/tasks/notes/add") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val taskId = form["taskId"]?.trim()?.toIntOrNull() ?: 0
                TaskService.addNote(
                    taskId,
                    body = form["body"].orEmpty(),
                    callerEmpNo = session.employeeNumber,
                    canManage = session.may(Permission.EDIT_ANY_TASK),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form)),
                )
            }
            post("/app/tasks/notes/remove") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                val noteId = form["noteId"]?.trim()?.toIntOrNull() ?: 0
                TaskService.removeNote(
                    noteId,
                    callerEmpNo = session.employeeNumber,
                    canManage = session.may(Permission.EDIT_ANY_TASK),
                )
                call.respondRedirect(
                    taskRedirect(backOf(form), projectIdOf(form), editOf(form)),
                )
            }
            // There is no `POST /app/other-tasks` any more (Stage 42). The backlog's `+ Add task` line used to
            // be a title box that wrote a bare card straight into the column under it; it is now the same
            // `?new=1&column=<id>` popup every other create uses, and that posts `/app/assigned-tasks/save`,
            // which dispatches to [TaskService.createBacklog] when the form carries a column. One card route for
            // one card shape, so the two boards cannot drift into two different sets of rules again.
            post("/app/other-buckets") {
                val session = call.requireSession() ?: return@post
                // Stage 110: naming a column of the shared board is *Manage buckets*; the page itself is drawn as
                // an editor's for either that or *Edit any task*, so a refusal comes back with the openers the
                // reader actually holds.
                val canBuckets = session.may(Permission.MANAGE_BUCKETS)
                val canManage = canBuckets || session.may(Permission.EDIT_ANY_TASK)
                val form = call.receiveParameters()
                // **Stage 124:** the Assigned page's empty panel opens this popup too, and names the page the
                // answer belongs on. `back` is one of two fixed words — anything else is the Other tasks board —
                // so it can neither send a reader off-site nor pick a route this file does not own.
                val fromAssigned = form["back"]?.trim() == "assigned-tasks"
                val canWriteTasks = session.may(Permission.EDIT_ANY_TASK)
                val otherBucketId = form["bucket"]?.trim()?.toIntOrNull() ?: 0
                val projectFilter = form["project"]?.trim()?.toIntOrNull() ?: 0
                // Stage 51: naming a backlog column is an Other-tasks function, and the board is now management's,
                // so the route turns a Viewer's add back to the page (which they cannot open) rather than growing
                // a column. The rename/delete routes below have always checked the role; this closes the last
                // write Stage 48 had left open to every signed-in account.
                if (!canBuckets) {
                    return@post call.respondRedirect(
                        if (fromAssigned) "/app/assigned-tasks" else "/app/other-tasks",
                    )
                }
                val name = form["name"]?.trim().orEmpty()
                val showBucketId = form["show"]?.trim()?.toIntOrNull() ?: 0
                // [TaskService.addBucket] answers with the rule that stopped the name, so the two a reader can
                // reach by typing — nothing at all, and a name the board already carries — are told apart in
                // the popup rather than landing on the same silent board.
                val error = TaskService.addBucket(name, session.employeeNumber, canBuckets)
                if (error != null) {
                    // Refused: the page comes back with the popup up, the word still in the field and the
                    // reason printed under it. `app.js` swaps both blocks from this one response, so the
                    // board keeps the columns it has and only the popup moves — no reload, nothing retyped.
                    // A create has no column to name wrongly, so of the answers [TaskService.addBucket] can
                    // return, every one is a word about the field the reader is looking at.
                    if (fromAssigned) {
                        return@post call.respondAssignedTasks(
                            session, canWriteTasks,
                            bucket = BucketForm(typed = name, nameError = bucketMessage(error)),
                            otherBucketId = otherBucketId, projectFilter = projectFilter, canAddBuckets = canBuckets,
                        )
                    }
                    return@post call.respondOtherTasks(
                        session, canManage, bucket = BucketForm(typed = name, nameError = bucketMessage(error)),
                        showBucketId = showBucketId,
                    )
                }
                // Landed. The answer names the column that was just made, because a new bucket holds nothing and
                // the board prints only the buckets that do: unfiltered, the thing the reader added would vanish as
                // they pressed the box. **Stage 40** forced the wide view for exactly this moment; since **Stage 97**
                // the create lands on the newcomer's own filter instead — the same end, one view control, and the
                // picker already lists every column, so *All buckets* is one choice away.
                // [TaskService.addBucket] returns the rule that stopped the name and no id, so the row is read back
                // by it: names are unique case-insensitively, so this is the one column just inserted.
                val createdId = TaskService.bucketRows()
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: 0
                // A create from the Assigned page answers with that page, its Other-tasks block redrawn with the
                // new column in the picker and the filter on it — the same "see what you just added" the Other
                // tasks board gets, without taking the reader off the page (**Stage 124**).
                if (fromAssigned) {
                    return@post call.respondAssignedTasks(
                        session, canWriteTasks,
                        otherBucketId = createdId, projectFilter = projectFilter, canAddBuckets = canBuckets,
                    )
                }
                call.respondRedirect(bucketsBack(createdId))
            }
            /**
             * Rename one backlog column. `app.js` swaps the board and this popup from whichever answer comes
             * back, so a rename that landed arrives as a new heading over the same cards and a name that was
             * refused arrives as the popup it came from, word still typed, reason under the field.
             */
            post("/app/other-buckets/{id}/rename") {
                val session = call.requireSession() ?: return@post
                val canManage = session.may(Permission.MANAGE_BUCKETS)
                val bucketId = call.parameters["id"]?.toIntOrNull() ?: 0
                val form = call.receiveParameters()
                val name = form["name"]?.trim().orEmpty()
                val showBucketId = form["show"]?.trim()?.toIntOrNull() ?: 0
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
                        showBucketId = showBucketId,
                    )
                }
                call.respondRedirect(bucketsBack(showBucketId))
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
                val canManage = session.may(Permission.MANAGE_BUCKETS)
                val bucketId = call.parameters["id"]?.toIntOrNull() ?: 0
                val form = call.receiveParameters()
                val showBucketId = form["show"]?.trim()?.toIntOrNull() ?: 0
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
                        showBucketId = showBucketId,
                    )
                }
                // The board the reader was on is gone with the column, so a filter naming it is dropped rather than
                // left pointing at an id no bucket has — same answer as a stale bookmark, but said on the way out.
                call.respondRedirect(bucketsBack(if (showBucketId == bucketId) 0 else showBucketId))
            }
            /**
             * Add an account (Stage 51, re-chipped for **Stage 110**, password narrowed in **Stage 123**).
             *
             * Two chips decide what this form may write. *Manage employees* is the door — without it nothing here
             * is reachable and the POST turns back to the page — and *Manage roles & permissions* is the **Role**
             * picker, so an account that only manages people adds a colleague under the catalogue's default role
             * rather than choosing which set of powers they get. The first password is the super admin's alone:
             * anybody else's posted word is ignored and the account starts on its employee number.
             */
            post("/app/access-control") {
                val session = call.requireSession() ?: return@post
                val callerEmp = session.employeeNumber
                if (!session.may(Permission.MANAGE_EMPLOYEES)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val canSetRoles = session.may(Permission.MANAGE_ROLES)
                val canSetPassword = callerEmp == UserService.SUPER_ADMIN
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val name = form["name"]?.trim().orEmpty()
                val email = form["email"].orEmpty()
                val newPassword = if (canSetPassword) form["password"]?.trim().orEmpty() else ""
                if (newPassword.isNotEmpty() && newPassword.length < MIN_PASSWORD_LENGTH) {
                    return@post call.respondRedirect("/app/access-control?pw=${PasswordStatus.TOO_SHORT.name}&who=$employeeNumber")
                }
                val status = UserService.addUser(
                    employeeNumber = employeeNumber,
                    name = name,
                    email = email,
                    requestedPosition = form["position"]?.trim(),
                    requestedRole = if (canSetRoles) form["role"]?.trim() else null,
                    newPassword = newPassword,
                    canSetRoles = canSetRoles,
                    canSetProfile = true,
                    canSetPassword = canSetPassword,
                )
                call.respondRedirect("/app/access-control?pw=${status.name}&who=$employeeNumber")
            }
            post("/app/access-control/delete") {
                val session = call.requireSession() ?: return@post
                val callerEmp = session.employeeNumber
                if (!session.may(Permission.MANAGE_EMPLOYEES)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                // Stage 110: only *Manage roles & permissions* may take away an account that itself holds it, so
                // a manager who manages people cannot delete the role that governs them.
                val status = UserService.deleteUser(
                    employeeNumber, callerEmp, callerCanAdmin = session.may(Permission.MANAGE_ROLES),
                )
                call.respondRedirect("/app/access-control?pw=${status.name}&who=$employeeNumber")
            }
            post("/app/access-control/password") {
                val session = call.requireSession() ?: return@post
                // Stage 123: another person's password is `EMP0000`'s alone — not a chip, so the Owner and
                // Administrator roles lose this control even though they hold every chip.
                if (session.employeeNumber != UserService.SUPER_ADMIN) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty()
                val status = UserService.setPasswordByAdmin(employeeNumber, form["password"].orEmpty())
                call.respondRedirect("/app/access-control?pw=${status.name}&who=$employeeNumber")
            }
            /**
             * Re-title one account — the Position select on a People row.
             *
             * Since **Stage 110** this writes the job title and nothing else. The old form carried an *Access*
             * picker whose answer was a rank word; ranks are gone, and the role a person signs in under is the
             * *Manage roles & permissions* chip's field, on the route below. [UserService.updateUserProfile] takes
             * the empty string as "leave that half alone", which is why nothing here reads a `role` field.
             */
            post("/app/access-control/update") {
                val session = call.requireSession() ?: return@post
                if (!session.may(Permission.MANAGE_EMPLOYEES)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                UserService.updateUserProfile(
                    employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty(),
                    positionName = form["position"]?.trim().orEmpty(),
                    roleName = "",
                )
                call.respondRedirect("/app/access-control")
            }
            /**
             * Move one account to another role — the Role select on a People row (Stage 110).
             *
             * Its own route rather than a second field on the one above, because the two answers belong to two
             * chips: renaming somebody's job title is *Manage employees*, changing what they may do is *Manage
             * roles & permissions*. The name is looked up in the catalogue and an unknown one writes nothing, which
             * is also what stops a hand-built POST inventing a role.
             *
             * A redirect is the whole answer, and the form names `#role-cell-<emp>` as the block it re-renders with
             * `#rolesPanel` as its companion: the picker shows the new role and the EMPLOYEES column moves, in the
             * same response, without the page restarting under the reader.
             */
            post("/app/access-control/role") {
                val session = call.requireSession() ?: return@post
                if (!session.may(Permission.MANAGE_ROLES)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                UserService.updateUserProfile(
                    employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty(),
                    positionName = "",
                    roleName = form["role"]?.trim().orEmpty(),
                )
                call.respondRedirect("/app/access-control")
            }
            /**
             * File one person under a department — the Department select on a People row (Stage 118).
             *
             * The narrowest right on this page, and the same one the Settings popup's Department box asks for:
             * a role holding **every** chip (*Owner* or *Administrator*). The word must be one of the nine and
             * the person must already be on the roster — [EmployeeService.setDepartment] answers false otherwise
             * and writes nothing, so a hand-built POST can neither invent a department nor clear one. A save is
             * the ordinary redirect; the form names `#department-cell-<emp>` as the one block to re-render, so
             * `app.js` swaps that cell and leaves the rest of the sheet where it was.
             */
            post("/app/access-control/department") {
                val session = call.requireSession() ?: return@post
                if (!Authorization.isOwnerOrAdministrator(session.employeeNumber)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                EmployeeService.setDepartment(
                    employeeNumber = form["employeeNumber"]?.trim()?.uppercase().orEmpty(),
                    department = form["department"]?.trim().orEmpty(),
                )
                call.respondRedirect("/app/access-control")
            }
            /**
             * Save one role — its name and the set of chips it holds (Stage 110).
             *
             * The whole page's rights hang on the rows this writes, so it is *Manage roles & permissions*'s alone
             * and there is no self-service half: nothing else can add, rename or widen a role.
             *
             * Three refusals come back as the page itself at 200 rather than a redirect, because a redirect's
             * target is drawn with the popup shut and the reason would have nowhere to stand: a blank name, one
             * the catalogue already carries, and an id that is not a role are all complaints about the single
             * field the reader is looking at, and [RoleService.save] is what decides between them. A save that
             * landed is the ordinary POST answer — redirect, and `app.js` follows it while swapping `#rolesPanel`
             * (the new row or the retitled one) and `#roleModal` (shut, because the target page draws it shut)
             * from the same response.
             */
            post("/app/roles/save") {
                val session = call.requireSession() ?: return@post
                val form = call.receiveParameters()
                if (!session.may(Permission.MANAGE_ROLES)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val editId = form["id"]?.trim()?.toIntOrNull() ?: 0
                val result = RoleService.save(
                    roleId = editId,
                    name = form["name"].orEmpty(),
                    permissions = Permission.ofAll(form.getAll("perm").orEmpty()),
                )
                if (!result.saved) {
                    return@post call.respondAccess(
                        session,
                        roleForm = roleFormOfForm(form, RoleService.byId(editId)).copy(nameError = result.error),
                    )
                }
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
                // Stage 110: the position catalogue is the People sheet's, so *Manage employees* is its chip.
                if (!session.may(Permission.MANAGE_EMPLOYEES)) {
                    return@post call.respondRedirect("/app/access-control")
                }
                val form = call.receiveParameters()
                val name = form["name"]?.trim().orEmpty()
                // Stage 110: no field on the page asks for a permission level any more — a position is a job title
                // and its rank grants nothing — so a new one is filed with the lowest legacy word, which is only
                // ever read back as a label.
                val permissionLevel = form["permissionLevel"]?.trim()?.takeIf { it in PERMISSION_LEVELS } ?: "Viewer"
                val description = form["description"]?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val created = UserService.addPosition(name, permissionLevel, description)
                    if (call.isFetch() && created != null) {
                        val html = createHTML().tr { positionRow(created, canEdit = true) }
                        call.respondText(html, io.ktor.http.ContentType.Text.Html)
                        return@post
                    }
                }
                call.respondRedirect("/app/access-control")
            }

            post("/app/positions/update") {
                val session = call.requireSession() ?: return@post
                if (!session.may(Permission.MANAGE_EMPLOYEES)) {
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
                            call.respondText(createHTML().tr { positionRow(row, canEdit = true) }, io.ktor.http.ContentType.Text.Html)
                        }
                        return@post
                    }
                }
                call.respondRedirect("/app/access-control")
            }

            post("/app/positions/delete") {
                val session = call.requireSession() ?: return@post
                if (!session.may(Permission.MANAGE_EMPLOYEES)) {
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
                // **Stage 127 — a pin is a preference, not a project edit.** This write used to ride on *Edit
                // project details* (Stage 110), which left an account without that chip — an *Employee*, or one whose
                // role holds only *Delete projects* — looking at a **☆ Pin** button that answered nothing. The row it
                // writes is keyed to `session.employeeNumber`, and [mn.murray.pts.repository.PinRepository.toggle]
                // touches that one pair and no other, so the pin was never anybody's but the reader's own and the
                // gate protected nothing. Every signed-in account may now pin for itself — and only for itself, which
                // is why there is still no employee-number field here to forge.
                if (projectId != null) {
                    ProjectService.togglePin(session.employeeNumber, projectId)
                }
                val back = call.request.headers[HttpHeaders.Referrer] ?: "/app/projects"
                call.respondRedirect(back)
            }

            // --- due-date alerts: operator triggers -----------------------------------
            // Plain text, not HTML, on purpose: these exist for the launch checklist and for
            // `curl -b cookie.txt -X POST .../app/alerts/run`, so the sweep's own report is what
            // you read. Users never see them; *Manage email settings* is what passes the guard.
            post("/app/alerts/run") {
                val session = call.requireSession() ?: return@post
                if (!session.may(Permission.MANAGE_EMAIL_SETTINGS)) {
                    return@post call.respondText(
                        "Manage email settings is required to run the due-date alert sweep.",
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
                if (!session.may(Permission.MANAGE_EMAIL_SETTINGS)) {
                    return@get call.respondText(
                        "Manage email settings is required to read the alert ledger.",
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
                if (!session.may(Permission.MANAGE_EMAIL_SETTINGS)) {
                    return@post call.respondText(
                        "Manage email settings is required to send a mail delivery test.",
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
            canManage = session.may(Permission.EDIT_FORECAST_AMOUNTS),
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
    val canManage = session.may(Permission.EDIT_ANY_TASK)
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
/**
 * Answer a *Project types* write with the Settings page and the panel's own one-line flash.
 *
 * The service's sentence is the whole answer — it names the letter, the label and, for a rename, how many
 * projects moved with the label — so the three success codes print it alone and a refusal prints it under
 * "That category cannot be saved." See [PasswordStatus.message].
 */
private suspend fun ApplicationCall.respondTypeAnswer(result: ProjectTypeResult, whenOk: PasswordStatus) {
    val status = if (result.ok) whenOk else PasswordStatus.TYPE_REFUSED
    val detail = "&caterr=${java.net.URLEncoder.encode(result.message, "UTF-8")}"
    respondRedirect("$SETTINGS_PATH?cat=${status.name}$detail")
}

private suspend fun ApplicationCall.respondSettings(
    session: UserSession,
    filter: EmployeeFilter,
    form: EmployeeForm,
    note: PasswordStatus? = null,
    /** The change-password form's own answer (Stage 123), carried on `?pw=`. */
    accountStatus: PasswordStatus? = null,
    mailStatus: PasswordStatus? = null,
    mailDetail: String? = null,
    bucketNote: PasswordStatus? = null,
    bucketDetail: String? = null,
    bucketDefaultsType: String = PROJECT_TYPES.first(),
    typeNote: PasswordStatus? = null,
    typeDetail: String? = null,
    typeBeingEdited: String = "",
    /**
     * Stage 110: the four panels are four chips, and each one is read off the caller's role here rather than
     * passed down from the route, so an answer to a refused write cannot accidentally draw a panel wider than
     * the chips the reader who was refused holds. The defaults are what the routes ask anyway; a refusal that
     * knows it holds one of them says so at the call.
     */
    canPeople: Boolean = session.may(Permission.MANAGE_EMPLOYEES),
    canRoles: Boolean = session.may(Permission.MANAGE_ROLES),
    canTypes: Boolean = session.may(Permission.MANAGE_PROJECT_TYPES),
    canEmail: Boolean = session.may(Permission.MANAGE_EMAIL_SETTINGS),
) = respondHtml {
    appPage(session, "settings", "Settings") {
        settingsContent(
            session = session,
            sheet = EmployeeService.sheet(filter, session.employeeNumber, canPeople, canPeople),
            filter = filter,
            form = form,
            canManage = canPeople,
            canAdmin = canPeople,
            canSetRoles = canRoles,
            canTypes = canTypes,
            canEmail = canEmail,
            note = note,
            accountStatus = accountStatus,
            mailStatus = mailStatus,
            mailDetail = mailDetail,
            bucketNote = bucketNote,
            bucketDetail = bucketDetail,
            bucketDefaultsType = bucketDefaultsType,
            typeNote = typeNote,
            typeDetail = typeDetail,
            typeBeingEdited = typeBeingEdited,
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
        // Stage 110: the sheet and the panel are separate chips — reading a colleague's board is
        // *View teammates' tasks*, putting a card on it is *Assign tasks to others*.
        teamContent(
            canManage = session.may(Permission.VIEW_TEAMMATES_TASKS),
            canAssign = session.may(Permission.ASSIGN_TASKS),
            viewerEmpNo = session.employeeNumber,
            assign = assign,
        )
    }
}

/**
 * The Access control page, on every route that answers with it (Stage 110).
 *
 * It is a helper for the same reason [respondLessons] is: `/app/roles/save` answers a refused name with this
 * page at 200, carrying the popup open with the word still typed and the reason under the field, and a
 * redirect's target would arrive with the form shut. Both flags are read off the caller's own role here rather
 * than passed in, because every route that can reach this page has already asked the same two questions and a
 * refusal must not be able to hand a reader a form wider than their own chips.
 */
private suspend fun ApplicationCall.respondAccess(
    session: UserSession,
    roleForm: RoleForm = RoleForm(),
    status: PasswordStatus? = null,
    detail: String? = null,
) = respondHtml {
    appPage(session, "access-control", "Access control") {
        accessContent(
            canPeople = session.may(Permission.MANAGE_EMPLOYEES),
            canRoles = session.may(Permission.MANAGE_ROLES),
            canDepartments = Authorization.isOwnerOrAdministrator(session.employeeNumber),
            currentEmployeeNumber = session.employeeNumber,
            roleForm = roleForm,
            passwordStatus = status,
            statusDetail = detail,
            // Stage 123: the Password column is the super admin's, read here rather than passed in, so a
            // refusal answering with this page cannot draw a control wider than the one the caller holds.
            canSetPassword = session.employeeNumber == UserService.SUPER_ADMIN,
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

/**
 * The month plan, on every route that answers with it.
 *
 * [month] is the `YYYY-MM` the address asked for, already resolved at the route; it is a renderer argument
 * rather than something the view reads off the request so that [calendarContent] can be drawn, tested and
 * swapped on a month without a browser being involved at all. `sectionRoute` cannot carry this page any more
 * for the same reason the forecast sheet left it behind: the lambda it hands the view is a `DIV.(UserSession)`
 * one, and it never sees the query the reader arrived with.
 */
private suspend fun ApplicationCall.respondCalendar(session: UserSession, month: String) = respondHtml {
    appPage(session, "calendar", "Calendar") { calendarContent(session, month) }
}

private suspend fun ApplicationCall.respondLogin(error: String?) =
    respondHtml(if (error == null) HttpStatusCode.OK else HttpStatusCode.Unauthorized) {
        loginPage(error)
    }

fun ApplicationCall.currentSession(): UserSession? =
    request.cookies[SESSION_COOKIE]
        ?.let { AuthService.resolve(it) }
        ?.let { UserSession(it.employeeNumber) }

/**
 * The guard every authenticated route calls, in place of [currentSession].
 *
 * It answers the call itself and returns null so the handler can `?: return@get`:
 *  - no session -> back to the login page.
 *
 * Stage 99 retired the second arm it used to have ("must change password -> to Settings, where
 * the password form is the only thing that clears the flag"). The box and the flag's enforcement
 * went together: a password is now set from the Team sheet, and a reader whose password is still
 * the number the system gave them reaches every page like anybody else.
 */
private suspend fun ApplicationCall.requireSession(): UserSession? {
    val session = currentSession() ?: run {
        respondRedirect("/")
        return null
    }
    return session
}

private const val SETTINGS_PATH = "/app/settings"
