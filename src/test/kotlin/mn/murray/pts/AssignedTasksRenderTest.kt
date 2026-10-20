package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.repository.BucketRepository
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

    private fun page(canManage: Boolean, state: TaskForm? = null): String = render {
        assignedTasksContent(if (canManage) manager else engineer, canManage, state)
    }

    /** A card of this board: assigned work, sitting in [status] and owned by [assignee]. */
    private fun assigned(
        title: String,
        assignee: String? = "EMP0002",
        status: String = "Not started",
    ): Int = TaskRepository.insert(
        kind = "ASSIGNED",
        title = title,
        projectId = null,
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
        // chrome, and both people's cards would never appear. The backlog's column name is not a heading here.
        assertFalse(html.contains("Site works"), "a backlog column name was printed as if this were that board")
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
        // The one control left that names the backlog's wide view is the card popup's own hidden field, and
        // this page never sets it: `taskFormModal` is called without `showAllBuckets` because there is no
        // board here for a save to widen.
        val html = page(canManage = true, state = editFormOf(
            TaskRepository.findById(assigned("Grade the haul road"))!!, true, "EMP0001",
        ))
        val form = formWithAction(html, "/app/assigned-tasks/save")
        assertTrue(form.isNotEmpty(), "the popup has no save form")
        assertFalse(form.contains("allbuckets"), "a card saved here answers on the backlog's wide board")
    }

    @Test
    fun `the editor this page opens comes back to this page`() {
        assigned("Grade the haul road")
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
}
