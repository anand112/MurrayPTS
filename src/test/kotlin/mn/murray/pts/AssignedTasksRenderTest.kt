package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.Parameters
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML

/**
 * What the Assigned tasks page renders: the reader's own projects board, then their own Other tasks under it.
 *
 * The board itself — its five columns, its cards, its popup — is pinned by [TaskBoardRenderTest]. What is pinned
 * here is the half of the split that only this page can show. Stage 47 took the shared backlog *board* — every
 * column, the `⋮`, the dashed *Add bucket* box, the view toggle — off this URL and onto its own page, and each of
 * those is still asserted **absent** here: this page is not that board. But Stage 55 brought back the personal
 * half of it: the reader's **own** Other-task cards now ride along below the projects board, cut by status like
 * the board above, sharing the page's one popup and one editor opener. So what is pinned here is the distinction —
 * the shared board's management chrome is gone, the reader's own backlog cards are present, another person's
 * backlog cards are not, and a reader with none is told so plainly.
 *
 * The promises that moved with the shared board are in [OtherTasksRenderTest].
 */
class AssignedTasksRenderTest {

    private lateinit var ds: HikariDataSource
    private val manager = UserSession("EMP0001")
    private val engineer = UserSession("EMP0002")

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "assigned.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"))
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    private fun page(canManage: Boolean, state: TaskForm? = null, bucketId: Int = 0, projectId: Int = 0): String = render {
        assignedTasksContent(if (canManage) manager else engineer, canManage, state, bucketId, projectId)
    }

    /** A card of this board: assigned work, sitting in [status] and owned by [assignee]. */
    private fun assigned(
        title: String,
        assignee: String? = "EMP0002",
        status: String = "Not started",
        projectId: Int? = null,
    ): Int = TaskRepository.insert(
        kind = "ASSIGNED",
        title = title,
        projectId = projectId,
        due = "2026-11-12",
        priority = "Medium",
        assigneeEmpNo = assignee,
        status = status,
        bucketId = null,
        started = "2026-11-01",
    )

    /** A backlog card, filed in a column — work outside a project, owned by [assignee]. */
    private fun backlog(title: String, bucketId: Int, assignee: String = "EMP0002"): Int = TaskRepository.insert(
        kind = "OTHER",
        title = title,
        projectId = null,
        due = "2026-11-12",
        priority = "Medium",
        assigneeEmpNo = assignee,
        status = "Not started",
        bucketId = bucketId,
        started = "2026-11-01",
    )

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    private fun elementWithId(html: String, id: String): String? =
        Regex("<(\\w+)[^>]*id=\"${Regex.escape(id)}\"[^>]*>").find(html)?.value

    private fun formWithAction(html: String, action: String): String {
        val start = Regex("<form[^>]*action=\"${Regex.escape(action)}\"").find(html)?.range?.first ?: return ""
        val end = html.indexOf("</form>", start)
        return html.substring(start, if (end < 0) html.length else end + "</form>".length)
    }

    /** The form carrying [cls], from its `<form` to its `</form>` — the bucket picker's own shape. */
    private fun formWithClass(html: String, cls: String): String {
        val at = html.indexOf(cls)
        if (at < 0) return ""
        val start = html.lastIndexOf("<form", at)
        val end = html.indexOf("</form>", at)
        return if (start < 0 || end < 0) "" else html.substring(start, end + "</form>".length)
    }

    /**
     * Every card's top-line chip, in page order: a project's code or a backlog card's bucket (Stage 88). Since
     * **Stage 104** a project's chip is an `<a>` onto that project's page while a bucket chip stays a label, so
     * this reads the word in either shape and leaves the markup to the tests that care about it.
     */
    private fun chips(html: String): List<String> =
        Regex("<div class=\"kcard-top\"><(?:a|span)[^>]*class=\"tcode(?: bucket-chip)?\"[^>]*>([^<]*)</(?:a|span)>")
            .findAll(html).map { it.groupValues[1] }.toList()

    private fun optionFor(form: String, label: String): String =
        Regex("<option[^>]*>${Regex.escape(label)}</option>").find(form)?.value.orEmpty()

    @Test
    fun `the page draws exactly one board, cut by status`() {
        // The card belongs to the reader whose page this renders (the manager, EMP0001): since Stage 53 the
        // board is personal for every role, so a card is only here when it is the viewer's own.
        assigned("Grade the haul road", assignee = "EMP0001")
        val html = page(canManage = true)
        // Five columns and nothing else: a status board always prints all of them, empty ones included.
        assertEquals(TASK_STATUSES.size, occurrences(html, "kcol-head"), "the board is not five status columns")
        TASK_STATUSES.forEach { assertTrue(html.contains(">$it<"), "the status column $it is missing") }
        assertEquals(TASK_STATUSES.size, occurrences(html, "data-status=\""), "a column cannot be a drag target")
        assertTrue(html.contains("Grade the haul road"), "the assigned card is not on the board")
        assertTrue(html.contains("/static/js/kanban.js"), "the board's own script is not loaded")
        // One card popup, and one editor opener — the board's, not the backlog's per-card forms.
        assertEquals(1, occurrences(html, "id=\"taskModal\""), "the page draws two editors")
        assertEquals(0, occurrences(html, "kcard-open"), "the backlog's click-opened cards are back")
    }

    @Test
    fun `the board is the viewer's own cards for every role, management included`() {
        // Stage 53: "it does not matter whether the user or management clicks — show only the person's own
        // tasks." Two cards, one held by the manager EMP0001 and one by the engineer EMP0002; the manager's page
        // prints their own and not the colleague's, exactly as an engineer's page has always printed their own.
        assigned("Grade the haul road", assignee = "EMP0001")
        assigned("Service the crusher", assignee = "EMP0002")

        val asManager = page(canManage = true)
        assertTrue(asManager.contains("Grade the haul road"), "the manager's own card is missing from their board")
        assertFalse(asManager.contains("Service the crusher"), "the manager was shown a colleague's card")

        // The same rule already held for a non-manager, and it is stated on the page for both of them now.
        val asEngineer = page(canManage = false)
        assertTrue(asEngineer.contains("Service the crusher"), "the engineer's own card is missing")
        assertFalse(asEngineer.contains("Grade the haul road"), "a Viewer was shown management's card")

        // The scope line is the honest read for every reader, so it is no longer conditional on the role.
        assertEquals(1, occurrences(asManager, "board-scope"), "management's personal board said nothing about it")
        assertEquals(1, occurrences(asEngineer, "board-scope"), "the scope line went off a Viewer's page")
    }

    @Test
    fun `the shared backlog board is not drawn here in any of its forms`() {
        val site = BucketRepository.insert("Site works")
        // A backlog card belonging to the *engineer*, while this page renders for the manager — so it is neither
        // the reader's own card nor the shared board. Neither belongs on a manager's personal page.
        val cardId = backlog("Re-line the crusher", site)
        assigned("Grade the haul road", assignee = "EMP0001")
        val html = page(canManage = true)
        // Not merely hidden behind a flag: none of the shared board's markup is emitted on this URL at all.
        assertNull(elementWithId(html, "otherBoard"), "the backlog board is still on this page")
        assertNull(elementWithId(html, "bucketModal"), "the column popup is still on this page")
        assertFalse(html.contains("d-bucket"), "a backlog column head was drawn")
        assertFalse(html.contains("addbucket"), "the dashed add-bucket box was drawn")
        assertFalse(html.contains("+ Add bucket"), "a bucket could be named from here")
        assertFalse(html.contains("kcol-menu"), "a column menu was drawn")
        assertFalse(html.contains("⋮"), "the column menu's glyph was drawn")
        assertFalse(html.contains("/app/other-buckets"), "this page can post a bucket")
        assertFalse(html.contains("buckets-toggle"), "the head's view control was drawn here")
        assertFalse(html.contains("allbuckets"), "this page carries the backlog's view")
        // …and a backlog card that is not the reader's own does not ride along on their page.
        assertFalse(html.contains("Re-line the crusher"), "another person's backlog card was drawn here")
        assertFalse(html.contains("data-id=\"$cardId\""), "another person's backlog card is draggable here")
        assertTrue(html.contains("Grade the haul road"), "the assigned card went with the backlog")
        // The manager holds no Other-task cards of their own, so the personal section says so rather than drawing a
        // status board of empty columns.
        assertTrue(html.contains("You don"), "a manager with no own backlog was not told so")
    }

    @Test
    fun `the reader's own other tasks ride along below the projects board`() {
        val site = BucketRepository.insert("Site works")
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        assigned("Grade the haul road", assignee = "EMP0002")
        // The engineer's own page: their project card on the upper board, their backlog card on the lower one.
        val html = page(canManage = false)
        assertTrue(html.contains("assigned-other-head"), "the Other tasks section is missing its heading")
        assertTrue(html.contains("Re-line the crusher"), "the reader's own backlog card did not reach their page")
        assertTrue(html.contains("Grade the haul road"), "the project card went missing")
        assertFalse(html.contains("You don"), "a reader who has other tasks was told they had none")
        // The lower board is a status board like the upper one, not the shared backlog's columns — so no bucket
        // chrome. Since Stage 88 the bucket name *does* appear twice on this page — as the card's own chip and as
        // an option in the picker above the board — but never as a column heading, which is the shape this page
        // would take if the shared backlog had returned.
        assertFalse(html.contains("kcol-title\">Site works"), "a backlog column name was printed as a heading")
        assertTrue(html.contains("tcode bucket-chip\">Site works"), "the card does not name the bucket it is in")
        assertFalse(html.contains("d-bucket"), "a backlog column head was drawn")
        // Both boards cut by the same five statuses, so ten columns and one popup shared between them.
        assertEquals(2 * TASK_STATUSES.size, occurrences(html, "kcol-head"), "both boards are not status boards")
    }

    @Test
    fun `a reader with no other tasks is told so, plainly`() {
        assigned("Grade the haul road", assignee = "EMP0002")
        val html = page(canManage = false)
        assertTrue(html.contains("You don"), "the empty Other-tasks line is missing")
        // One projects board, no second board underneath: only the five upper status columns are drawn.
        assertEquals(TASK_STATUSES.size, occurrences(html, "kcol-head"), "an empty Other-tasks section drew a board")
    }

    // --- Stage 90: the empty board is the reader's way in -----------------------------------------

    @Test
    fun `a reader with no other tasks still gets the picker and a line to add their own`() {
        val site = BucketRepository.insert("Site works")
        val html = page(canManage = false)
        // The picker stays because it is also the chooser for the create line, and the line opens the blank
        // popup on the bucket it shows — an empty board with neither would leave a reader nowhere to start.
        assertTrue(formWithClass(html, "other-bucket-filter").isNotEmpty(),
            "an empty Other-tasks section hid the bucket picker")
        val add = formWithClass(html, "kcol-add")
        assertTrue(add.isNotEmpty(), "an empty Other-tasks section offered no way to write the first card")
        assertTrue(add.contains("action=\"/app/assigned-tasks\""), "the create line aims elsewhere: $add")
        assertTrue(add.contains("name=\"new\" value=\"1\""), "the line does not open the blank popup")
        assertTrue(add.contains("name=\"column\" value=\"$site\""),
            "the line does not name the bucket the card belongs in: $add")
        assertTrue(add.contains("data-live-target=\"#taskModal\""), "the line would reload the page")
        assertTrue(html.contains("You don"), "the empty line went missing")
    }

    @Test
    fun `the add line follows the bucket the picker shows, and brings the save back to it`() {
        val hr = BucketRepository.insert("HR")
        val site = BucketRepository.insert("Site works")
        val add = formWithClass(page(canManage = false, bucketId = site), "kcol-add")
        assertTrue(add.isNotEmpty(), "the narrowed board lost its create line")
        assertTrue(add.contains("name=\"column\" value=\"$site\""), "the line filed the card in another bucket: $add")
        assertTrue(add.contains("name=\"bucket\" value=\"$site\""),
            "a save from the narrowed board would answer on the unfiltered one: $add")
        assertFalse(add.contains("name=\"bucket\" value=\"$hr\""), "the line carried a bucket nobody is looking at")
    }

    @Test
    fun `the add line opens a create form pinned to the reader`() {
        val site = BucketRepository.insert("Site works")
        val html = page(
            canManage = false,
            state = backlogFormOf(false, "EMP0002", BucketRepository.findAll(), into = site),
            bucketId = site,
        )
        val form = formWithAction(html, "/app/assigned-tasks/save")
        assertTrue(form.isNotEmpty(), "the create popup has no save form")
        assertTrue(form.contains("<input type=\"hidden\" name=\"back\" value=\"assigned:$site\">"),
            "a save from the narrowed board would answer on the unfiltered one")
        // The writer and the starting status are facts, not choices: the service rebuilds the draft that way.
        val assignee = Regex("<select[^>]*name=\"assignee\".*?</select>", RegexOption.DOT_MATCHES_ALL)
            .find(form)?.value.orEmpty()
        assertTrue(assignee.contains("value=\"EMP0002\""), "the create form did not name the reader as its writer")
        assertFalse(assignee.contains("value=\"EMP0001\""), "the create form offered the roster to a non-manager")
        val status = Regex("<select[^>]*name=\"status\".*?</select>", RegexOption.DOT_MATCHES_ALL)
            .find(form)?.value.orEmpty()
        assertTrue(status.contains("disabled"), "the starting status was a choice for a non-manager")
        assertTrue(Regex("<option[^>]*value=\"Not started\"[^>]*selected").containsMatchIn(status),
            "the create form did not print *Not started* as the answer: $status")
        assertTrue(form.contains("name=\"column\""), "the backlog create has no column to land in")
        assertTrue(form.contains("Create task"), "the popup's own button still says Save")
    }

    @Test
    fun `the two boards share one popup and one editor opener`() {
        val site = BucketRepository.insert("Site works")
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        assigned("Grade the haul road", assignee = "EMP0002")
        val html = page(canManage = false)
        // task-modal.js finds the opener by id, so a page with two boards must still emit exactly one of each.
        assertEquals(1, occurrences(html, "id=\"taskEditOpener\""), "two boards drew two openers")
        assertEquals(1, occurrences(html, "id=\"taskModal\""), "two popups on one page")
        val opener = elementWithId(html, "taskEditOpener").orEmpty()
        assertTrue(opener.contains("action=\"/app/assigned-tasks\""), "the shared opener aims elsewhere: $opener")
    }

    @Test
    fun `management's own backlog surfaces here but a colleague's never does`() {
        val site = BucketRepository.insert("Site works")
        // Stage 55 keeps the page personal for every role: a manager sees their own Other-task card, and the
        // engineer's card stays off their page entirely.
        backlog("Sign the delivery note", site, assignee = "EMP0001")
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        val asManager = page(canManage = true)
        assertTrue(asManager.contains("Sign the delivery note"), "the manager's own backlog card was missing")
        assertFalse(asManager.contains("Re-line the crusher"), "a colleague's backlog card leaked onto management's page")
    }


    @Test
    fun `nothing on this page asks for the other page's view`() {
        // *(Until **Stage 97** the backlog had a wide view of its own, carried by a hidden `allbuckets` field on
        // every popup form. This page never set it — there was no board here for a save to widen — and now no page
        // does: the flag, the view and the button that set it are all gone.)*
        val html = page(canManage = true, state = editFormOf(
            TaskRepository.findById(assigned("Grade the haul road"))!!, true, "EMP0001",
        ))
        val form = formWithAction(html, "/app/assigned-tasks/save")
        assertTrue(form.isNotEmpty(), "the popup has no save form")
        assertFalse(form.contains("allbuckets"), "a card saved here answers on the backlog's wide board")
    }

    @Test
    fun `the editor this page opens comes back to this page`() {
        // A card *on this reader's board*: since Stage 86 the opener follows the board holding a card rather than
        // the reader holding a right, so a management page with nothing on it has nothing to open (pinned below).
        assigned("Grade the haul road", assignee = "EMP0001")
        val html = page(canManage = true)
        val opener = elementWithId(html, "taskEditOpener").orEmpty()
        assertTrue(opener.contains("action=\"/app/assigned-tasks\""), "the board's opener aims elsewhere: $opener")
        assertTrue(opener.contains("method=\"get\""), "the opener is not a GET onto this page: $opener")
        assertTrue(opener.contains("data-live-target=\"#taskModal\""), "the opener would reload the page: $opener")
        val form = formWithAction(html, "/app/assigned-tasks/save")
        assertTrue(form.contains("<input type=\"hidden\" name=\"back\" value=\"assigned\">"),
            "a refused save would answer on another board")
        assertEquals(0, occurrences(html, "action=\"/app/other-tasks\""), "a control on this page opens the backlog")
    }

    @Test
    fun `a board with no card on it carries no opener`() {
        // Stage 86: the opener exists to fill `?edit=` from a card's click, so a board with no card has nothing
        // for it to ask. The page's popup still travels — the head's `+ New task` and a refused save both land in
        // it — it is only the clicked-card form that is absent.
        val html = page(canManage = true)
        assertEquals(0, occurrences(html, "id=\"taskEditOpener\""), "an empty board shipped an opener for no card")
        assertEquals(1, occurrences(html, "id=\"taskModal\""), "the page lost the popup its head opens")
    }

    // --- Stage 88: the card's chip, and the Other-tasks bucket filter ------------------------------

    /** A project row for a card to belong to, with the code its chip should wear. */
    private fun projectRow(code: String = "S-01", name: String = "Haul road widening"): Int =
        ProjectRepository.insert(
            code = code, name = name, status = "In progress", progress = 0,
            managerEmpNo = "EMP0001", due = "2027-03-30", type = "Site projects",
        )

    @Test
    fun `a project card wears its project code, not its own task number`() {
        // The user's ask: "on assigned tasks there are project task cards showing task id's should be showing
        // project id". The cross-project board's chip is now the project's **code** — the same string the
        // projects board prints — so a run of cards says which world each belongs to.
        val projectId = projectRow()
        assigned("Spread base course", assignee = "EMP0002", projectId = projectId)
        val html = page(canManage = false)
        assertEquals(listOf("S-01"), chips(html), "the card did not wear its project's code")
        assertFalse(html.contains("T-0000"), "the card still printed its own task number")
    }

    @Test
    fun `a project card wears that code as the way to the project page`() {
        // The user's ask: "make user able to go to project page by clicking project code from task card". The chip
        // is a plain `<a>` in the same tab — no script involved, and `task-modal.js` bails on a click inside an
        // `<a>`, so following the code does not open this card's popup over it.
        val projectId = projectRow()
        assigned("Spread base course", assignee = "EMP0002", projectId = projectId)
        val html = page(canManage = false)
        val chip = Regex("<div class=\"kcard-top\"><a[^>]*>[^<]*</a>").find(html)?.value.orEmpty()
        assertTrue(chip.isNotEmpty(), "the project chip is not a link")
        assertTrue(chip.contains("class=\"tcode\""), "the link is not the app's code chip: $chip")
        assertTrue(chip.contains("href=\"/app/projects/$projectId\""), "the link goes to no project: $chip")
        assertTrue(chip.endsWith(">S-01</a>"), "the link lost the code it names: $chip")
        assertTrue(chip.contains("Open project S-01"), "the link does not say where pressing it goes")
        // The card keeps its own opener beside it: the chip is an added way out, not a replacement for the click.
        assertEquals(1, occurrences(html, "id=\"taskEditOpener\""), "the card lost its click-to-open form")
        // The bucket chip is a column of the very board the card sits on, so it links nowhere — and a card whose
        // project row is gone has no page to offer either. Both stay labels.
        val hr = BucketRepository.insert("HR")
        backlog("Sign the contract", hr, assignee = "EMP0002")
        val both = page(canManage = false)
        assertTrue(
            both.contains("<span class=\"tcode bucket-chip\">HR</span>"),
            "a bucket chip grew a link it cannot answer",
        )
        assertEquals(listOf("S-01", "HR"), chips(both), "the two boards' chips no longer read the same way")
    }

    @Test
    fun `an other-task card wears the bucket it is filed in`() {
        // The same slot on the other half of the page: an Other card has no project code, so it names its
        // **bucket** — which is how a reader sees where a card is filed without opening it.
        val hr = BucketRepository.insert("HR")
        backlog("Sign the contract", hr, assignee = "EMP0002")
        val html = page(canManage = false)
        assertEquals(listOf("HR"), chips(html), "the backlog card did not wear its bucket")
        assertTrue(html.contains("tcode bucket-chip"), "the bucket chip is not told apart from a project code")
    }

    @Test
    fun `the bucket picker lists the board's columns and opens on all buckets`() {
        val hr = BucketRepository.insert("HR")
        val site = BucketRepository.insert("Site works")
        backlog("Sign the contract", hr, assignee = "EMP0002")
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        val html = page(canManage = false)
        val filter = formWithClass(html, "other-bucket-filter")
        assertTrue(filter.isNotEmpty(), "the Other-tasks bucket picker is missing")
        assertTrue(filter.contains("name=\"bucket\""), "the picker has no bucket field")
        assertTrue(filter.contains("data-autosubmit"), "picking a bucket would wait for a second press")
        assertTrue(filter.contains("action=\"/app/assigned-tasks\""), "the picker does not come back here")
        assertTrue(filter.contains(">HR</option>"), "the HR column is not offered")
        assertTrue(filter.contains(">Site works</option>"), "the Site works column is not offered")
        assertTrue(optionFor(filter, "All buckets").contains("selected"), "an unfiltered board did not open on All")
        assertFalse(optionFor(filter, "HR").contains("selected"), "a bucket was preselected on the whole board")
        // Both cards are on the board: the picker narrows, it never hides by itself.
        assertTrue(html.contains("Sign the contract") && html.contains("Re-line the crusher"),
            "the unfiltered board hid a card")
    }

    @Test
    fun `the bucket picker is one labelled field, not a toolbar across the page`() {
        val hr = BucketRepository.insert("HR")
        backlog("Sign the contract", hr, assignee = "EMP0002")
        val filter = formWithClass(page(canManage = false), "other-bucket-filter")
        // Stage 89: the select lives **inside** its label — the pair the picker styling keys on. As a sibling of
        // the label it fell through to the bare `select { width: 100% }` rule and stretched across the page, which
        // is what made a one-field filter read as a toolbar.
        val label = Regex("<label class=\"board-key\">.*?</label>", RegexOption.DOT_MATCHES_ALL)
            .find(filter)?.value.orEmpty()
        assertTrue(label.contains(">Bucket<"), "the picker's field has no label of its own")
        assertTrue(label.contains("id=\"otherBucket\""), "the select left its label, and the styling with it")
        assertFalse(Regex("<label[^>]* for=\"otherBucket\"").containsMatchIn(filter),
            "a for= still points out of the label at a field it now holds")
    }

    @Test
    fun `choosing a bucket narrows the reader's other tasks to it`() {
        val hr = BucketRepository.insert("HR")
        val site = BucketRepository.insert("Site works")
        backlog("Sign the contract", hr, assignee = "EMP0002")
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        val html = page(canManage = false, bucketId = site)
        assertTrue(html.contains("Re-line the crusher"), "the chosen bucket's card is missing")
        assertFalse(html.contains("Sign the contract"), "a card from another bucket was left on the board")
        val filter = formWithClass(html, "other-bucket-filter")
        assertTrue(optionFor(filter, "Site works").contains("selected"), "the picker did not open on the chosen bucket")
        assertFalse(optionFor(filter, "All buckets").contains("selected"), "All buckets stayed selected")
        // The status board is still the whole board: five columns, the chosen bucket's card in its status.
        assertEquals(2 * TASK_STATUSES.size, occurrences(html, "kcol-head"), "the filter redrew the columns")
    }

    @Test
    fun `a bucket the board no longer has answers with all of them`() {
        val site = BucketRepository.insert("Site works")
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        val html = page(canManage = false, bucketId = 9999)
        assertTrue(html.contains("Re-line the crusher"), "a stale bucket id hid the reader's whole board")
        assertTrue(optionFor(formWithClass(html, "other-bucket-filter"), "All buckets").contains("selected"),
            "a stale bucket id left the picker on nothing")
    }

    @Test
    fun `an empty bucket says which bucket it is empty of`() {
        val hr = BucketRepository.insert("HR")
        val site = BucketRepository.insert("Site works")
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        val html = page(canManage = false, bucketId = hr)
        assertTrue(html.contains("You don't have any other tasks in HR."), "the empty line did not name the bucket")
        assertFalse(html.contains("Re-line the crusher"), "another bucket's card was printed under the filter")
        // The picker is still there, so the reader can leave the empty bucket without editing the URL.
        assertTrue(formWithClass(html, "other-bucket-filter").isNotEmpty(), "the empty bucket hid its own picker")
    }

    @Test
    fun `the chosen bucket survives opening, saving and refusing a card`() {
        val site = BucketRepository.insert("Site works")
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        val html = page(canManage = false, bucketId = site)
        // The page's one opener and its save form both carry the filter, which is the whole of Stage 88's
        // round trip: the reader comes back to the board they were reading rather than the unfiltered one.
        val opener = elementWithId(html, "taskEditOpener").orEmpty()
        assertTrue(opener.contains("action=\"/app/assigned-tasks?bucket=$site\""), "the opener lost the filter: $opener")
        val save = formWithAction(html, "/app/assigned-tasks/save")
        assertTrue(save.contains("<input type=\"hidden\" name=\"back\" value=\"assigned:$site\">"),
            "a save from a filtered board would answer on the unfiltered one")
    }

    @Test
    fun `a bucket filter is a view token the routes read back, never an echo of arbitrary text`() {
        assertEquals("assigned:7", backForBucket(7))
        assertEquals("assigned", backForBucket(0))
        assertEquals(7, bucketOfBack("assigned:7"))
        assertEquals(0, bucketOfBack("assigned"))
        assertEquals(0, bucketOfBack("assigned:not-a-number"))
        assertTrue(taskRedirect("assigned:7", 0).endsWith("?bucket=7"), "the filter was lost on the way back")
        assertTrue(taskRedirect("assigned:7", 0, edit = 5).contains("edit=5"), "the popup was lost on the way back")
        assertEquals("/app/assigned-tasks", taskRedirect("assigned", 0), "an unfiltered board grew a bucket key")
        // A posted token is normalised: a made-up `back` is the unfiltered board, an `assigned:<id>` keeps its id.
        val made = Parameters.build { append("back", "assigned:7") }
        assertEquals("assigned:7", backOf(made), "a posted bucket filter was dropped")
        val junk = Parameters.build { append("back", "assigned:'; drop table tasks; --") }
        assertEquals("assigned", backOf(junk), "a hand-built back token was echoed back")
    }

    // --- Stage 107: the projects board's own picker ----------------------------------------------

    @Test
    fun `the project picker lists every project and opens on all projects`() {
        val road = projectRow()
        val crusher = projectRow(code = "S-02", name = "Crusher service")
        assigned("Spread base course", assignee = "EMP0002", projectId = road)
        assigned("Service the jaw", assignee = "EMP0002", projectId = crusher)
        val html = page(canManage = false)
        val filter = formWithClass(html, "assigned-project-filter")
        assertTrue(filter.isNotEmpty(), "the projects board has no project picker")
        assertTrue(filter.contains("name=\"project\""), "the picker has no project field")
        assertTrue(filter.contains("data-autosubmit"), "picking a project would wait for a second press")
        assertTrue(filter.contains("action=\"/app/assigned-tasks\""), "the picker does not come back here")
        assertTrue(filter.contains(">S-01 · Haul road widening</option>"), "the first project is not offered")
        assertTrue(filter.contains(">S-02 · Crusher service</option>"), "the second project is not offered")
        assertTrue(optionFor(filter, "All projects").contains("selected"), "an unfiltered board did not open on All")
        assertFalse(optionFor(filter, "S-01 · Haul road widening").contains("selected"),
            "a project was preselected on the whole board")
        // Both cards are on the board: the picker narrows, it never hides by itself.
        assertTrue(html.contains("Spread base course") && html.contains("Service the jaw"),
            "the unfiltered board hid a card")
    }

    @Test
    fun `choosing a project narrows the projects board and names it in the scope line`() {
        val road = projectRow()
        val crusher = projectRow(code = "S-02", name = "Crusher service")
        val site = BucketRepository.insert("Site works")
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        assigned("Spread base course", assignee = "EMP0002", projectId = road)
        assigned("Service the jaw", assignee = "EMP0002", projectId = crusher)
        val html = page(canManage = false, projectId = road)
        assertTrue(html.contains("Spread base course"), "the chosen project's card is missing")
        assertFalse(html.contains("Service the jaw"), "a card from another project was left on the projects board")
        val filter = formWithClass(html, "assigned-project-filter")
        assertTrue(optionFor(filter, "S-01 · Haul road widening").contains("selected"),
            "the picker did not open on the chosen project")
        assertFalse(optionFor(filter, "All projects").contains("selected"), "All projects stayed selected")
        // It narrows the projects board only: the Other-tasks board below is whole, and neither board loses its
        // five status columns to a filter.
        assertTrue(html.contains("Re-line the crusher"), "the project filter hid an Other-task card")
        assertEquals(2 * TASK_STATUSES.size, occurrences(html, "kcol-head"), "the filter redrew a board")
        assertTrue(html.contains("Only the cards assigned to you on S-01 · Haul road widening are on this board."),
            "the scope line did not name the project the board is narrowed to")
    }

    @Test
    fun `a project the page no longer has answers with all of them`() {
        val road = projectRow()
        assigned("Spread base course", assignee = "EMP0002", projectId = road)
        val html = page(canManage = false, projectId = 9999)
        assertTrue(html.contains("Spread base course"), "a stale project id hid the reader's whole board")
        assertTrue(optionFor(formWithClass(html, "assigned-project-filter"), "All projects").contains("selected"),
            "a stale project id left the picker on nothing")
    }

    @Test
    fun `the two pickers carry each other's choice`() {
        val hr = BucketRepository.insert("HR")
        val road = projectRow()
        backlog("Sign the contract", hr, assignee = "EMP0002")
        assigned("Spread base course", assignee = "EMP0002", projectId = road)
        val html = page(canManage = false, bucketId = hr, projectId = road)
        val projectFilter = formWithClass(html, "assigned-project-filter")
        assertTrue(projectFilter.contains("name=\"bucket\" value=\"$hr\""),
            "choosing a project would widen the Other-tasks board: $projectFilter")
        val bucketFilter = formWithClass(html, "other-bucket-filter")
        assertTrue(bucketFilter.contains("name=\"project\" value=\"$road\""),
            "choosing a bucket would widen the projects board: $bucketFilter")
        val add = formWithClass(html, "kcol-add")
        assertTrue(add.contains("name=\"bucket\" value=\"$hr\""), "the create line lost the bucket: $add")
        assertTrue(add.contains("name=\"project\" value=\"$road\""), "the create line lost the project: $add")
    }

    @Test
    fun `a project-only filter still comes back to the projects board`() {
        val road = projectRow()
        assigned("Spread base course", assignee = "EMP0002", projectId = road)
        val html = page(canManage = false, projectId = road)
        // The projects board carries the page's one opener here, and it must come back to the narrowed board.
        val opener = elementWithId(html, "taskEditOpener").orEmpty()
        assertTrue(opener.contains("action=\"/app/assigned-tasks?project=$road\""),
            "the opener lost the project filter: $opener")
        val save = formWithAction(html, "/app/assigned-tasks/save")
        assertTrue(save.contains("<input type=\"hidden\" name=\"back\" value=\"assigned:0:$road\">"),
            "a save from the narrowed projects board would answer on the whole board")
    }

    @Test
    fun `the chosen project survives opening, saving and refusing a card beside a bucket`() {
        val site = BucketRepository.insert("Site works")
        val road = projectRow()
        backlog("Re-line the crusher", site, assignee = "EMP0002")
        assigned("Spread base course", assignee = "EMP0002", projectId = road)
        val html = page(canManage = false, bucketId = site, projectId = road)
        // One `back` token carries both filters, and taskRedirect rebuilds both keys from it: opening a card,
        // saving it and answering a refusal all land on the doubly-filtered page the reader was reading.
        val opener = elementWithId(html, "taskEditOpener").orEmpty()
        assertTrue(opener.contains("action=\"/app/assigned-tasks?bucket=$site&amp;project=$road\""),
            "the opener lost one of the filters: $opener")
        val save = formWithAction(html, "/app/assigned-tasks/save")
        assertTrue(save.contains("<input type=\"hidden\" name=\"back\" value=\"assigned:$site:$road\">"),
            "a save from the filtered board would answer on another page")
    }

    @Test
    fun `the project filter is a view token the routes read back`() {
        assertEquals("assigned:0:5", backForFilters(0, 5))
        assertEquals("assigned:7:5", backForFilters(7, 5))
        assertEquals("assigned:7", backForFilters(7, 0))
        assertEquals("assigned", backForFilters(0, 0))
        assertEquals(5, projectOfBack("assigned:0:5"))
        assertEquals(5, projectOfBack("assigned:7:5"))
        assertEquals(7, bucketOfBack("assigned:7:5"))
        assertEquals(0, projectOfBack("assigned:7"), "a bucket-only token guessed a project")
        assertEquals(0, projectOfBack("assigned"))
        assertTrue(taskRedirect("assigned:0:5", 0).endsWith("?project=5"), "the project filter was lost on the way back")
        assertTrue(taskRedirect("assigned:7:5", 0).endsWith("?bucket=7&project=5"),
            "one of the two filters was lost on the way back")
        assertEquals("/app/assigned-tasks", taskRedirect("assigned", 0), "an unfiltered board grew a project key")
        val made = Parameters.build { append("back", "assigned:0:5") }
        assertEquals("assigned:0:5", backOf(made), "a posted project filter was dropped")
        val half = Parameters.build { append("back", "assigned::5") }
        assertEquals("assigned:0:5", backOf(half), "a half-written token was not normalised")
    }
}
