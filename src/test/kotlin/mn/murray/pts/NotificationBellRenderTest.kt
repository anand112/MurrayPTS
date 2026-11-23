package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.NotificationService
import mn.murray.pts.service.TaskDraft
import mn.murray.pts.service.TaskService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML

/**
 * What the bell actually renders into the page head, on the same harness as [TaskBoardRenderTest].
 *
 * The dropdown has no fetch endpoint, so every promise the script relies on is a promise the view has
 * to keep: the panel is in the document already and starts closed, `hidden` is what hides it (no
 * inline style a CSP would block), each row links to the read-marking open route, and "Mark all read"
 * carries the page it should go back to.
 */
class NotificationBellRenderTest {

    private lateinit var ds: HikariDataSource
    private val worker = UserSession("EMP0002")

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "bell.db").absolutePath}"
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

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    /** Assign a card to the test user the way a manager's form does, which is what raises the row. */
    private var seq = 0

    private fun assign(title: String) {
        seq++
        val name = "Project ${seq.toString().padStart(2, '0')}"
        val projectId = mn.murray.pts.repository.ProjectRepository.insert(
            code = "S-%02d".format(seq), name = name, status = "In progress", progress = 0,
            managerEmpNo = "EMP0001", due = "", type = "Site projects",
        )
        // The three phases the card is filed in (Stage 48); this harness writes the project row itself, so it
        // gives the project the three-lane board every board had before Stage 100.
        mn.murray.pts.seedOpeningThree(projectId)
        val result = TaskService.create(
            TaskDraft(
                title = title, projectName = name, assigneeEmpNo = "EMP0002",
                projectLane = mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS.first(),
            ),
            callerEmpNo = "EMP0001",
            canManage = true,
        )
        checkNotNull(result.taskId) { "task creation failed: ${result.errors}" }
    }

    private fun bell(session: UserSession = worker, section: String = "projects"): String =
        render { notificationBell(session, section) }

    @Test
    fun `the bell ships closed, with its panel already in the document`() {
        val html = bell()
        assertTrue("data-notif-toggle" in html, html)
        assertTrue("data-notif-panel" in html, html)
        // `hidden` is the whole open-state: the script flips the attribute and nothing else.
        assertTrue("hidden=\"true\"" in html, html)
        assertEquals(1, occurrences(html, "class=\"notif-panel\""))
        assertTrue("/static/js/notifications.js" in html, "the behaviour must travel with the markup")
        assertFalse("onclick=" in html, "a CSP that drops inline handlers must not be needed here")
    }

    @Test
    fun `an empty inbox says so and shows no badge`() {
        val html = bell()
        assertTrue("Nothing yet" in html, html)
        assertFalse("notif-badge" in html, "zero unread means no number at all")
        assertFalse("read-all" in html, "nothing to mark, so no button")
    }

    @Test
    fun `the badge counts unread rows and the clear button comes with them`() {
        assign("Spread base course")
        assign("Service the crusher")
        val html = bell()
        assertTrue("class=\"notif-badge\">2<" in html, html)
        assertTrue("action=\"/app/notifications/read-all\"" in html, html)
        assertTrue("data-live-target=\"#notif\"" in html, html)
        assertEquals(2, occurrences(html, "class=\"notif-item unread\""))
    }

    @Test
    fun `every row goes through the open route so reading it is not a separate step`() {
        assign("Spread base course")
        val id = NotificationService.inbox("EMP0002").single().id
        val html = bell()
        assertTrue("href=\"/app/notifications/$id/open\"" in html, html)
        // The row's own project link never reaches the markup — only the marking route does.
        assertFalse("href=\"/app/projects/" in html, html)
    }

    @Test
    fun `a read row loses its unread styling but stays in the list`() {
        assign("Spread base course")
        val id = NotificationService.inbox("EMP0002").single().id
        assertTrue(NotificationService.markRead("EMP0002", id))
        val html = bell()
        assertEquals(1, occurrences(html, "class=\"notif-item\""), html)
        assertFalse("notif-item unread" in html, html)
        assertFalse("notif-badge" in html, "the badge follows the unread count, not the list length")
    }

    @Test
    fun `marking all read returns to the page the panel was opened on`() {
        assign("Spread base course")
        val projects = bell(section = "projects")
        assertTrue("name=\"back\" value=\"/app/projects\"" in projects, projects)
        val teamPage = bell(section = "team")
        assertTrue("name=\"back\" value=\"/app/team\"" in teamPage, teamPage)
    }

    @Test
    fun `the list is newest first and every row carries its own age`() {
        assign("First card")
        assign("Second card")
        val html = bell()
        assertTrue(html.indexOf("Second card") < html.indexOf("First card"), "newest at the top")
        assertEquals(2, occurrences(html, "class=\"notif-time\""), html)
        // Rendered on the server, so "just now" is the honest answer for a row created a moment ago.
        assertEquals(2, occurrences(html, "just now"), html)
    }
}
