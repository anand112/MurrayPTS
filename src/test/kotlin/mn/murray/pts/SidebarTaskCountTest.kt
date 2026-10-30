package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
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
 * The number the sidebar prints beside *Assigned tasks*, on the same real-schema harness as
 * [TaskBoardRenderTest].
 *
 * Since **Stage 54** the pill counts every card on the reader's board, Completed included — it answers *how many
 * tasks are assigned to me*, and a finished card is still one of those. This retires Stage 27's exception (which
 * left Completed out so the figure would fall as work finished); the reader asked for the opposite. So the pill and
 * the board are no longer allowed to disagree: the number beside the link equals the count of cards the reader's own
 * board prints. What still separates an open card from a closed one is the Completed column, the board's per-column
 * counts, and the Team sheet's OPEN TASKS figure — the last is deliberately open-only and is asserted untouched here
 * by the fact that this suite reads the sidebar's `navItems`, never the Team sheet. The role scoping the pill
 * inherits from [mn.murray.pts.service.TaskService.assignedFor] (personal for every reader since Stage 53) is pinned
 * alongside, and there is no project-level roll-up to agree with, since Stage 28 took the strip off the project page.
 */
class SidebarTaskCountTest {

    private lateinit var ds: HikariDataSource

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "sidebar.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"))
        TeamRepository.upsert(Member("EMP0003", "Naran", "Site", "Technician"))
        // One account with a role that sees the whole board, one without: the two readers the pill differs for.
        UserRepository.insert("EMP0001", "Munkhtsetseg", "x", "Manager", "munkho@murray.mn", null, false, false)
        UserRepository.insert("EMP0002", "Bat-Erdene", "x", "Viewer", "bat@murray.mn", null, false, false)
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    /** The figure the sidebar would print for [empNo]; -1 rather than a null assertion if the item goes missing. */
    private fun pill(empNo: String): Int =
        navItems(empNo).first { it.id == "assigned-tasks" }.count ?: -1

    private var seq = 0

    /** One card for [empNo] on its own project, written the way a manager's popup writes it. Returns its id. */
    private fun card(title: String, empNo: String, status: String = "Not started"): Int {
        seq++
        val name = "Project ${seq.toString().padStart(2, '0')}"
        val projectId = ProjectRepository.insert(
            code = "S-%02d".format(seq), name = name, status = "In progress", progress = 0,
            managerEmpNo = "EMP0001", due = "", type = "Site projects",
        )
        // A project's board holds the three phases (Stage 48), and a card has to name one of them; this harness
        // writes the project row itself, so the board is written here too.
        seedOpeningThree(projectId)
        val result = TaskService.create(
            TaskDraft(
                title = title, projectName = name, assigneeEmpNo = empNo, status = status,
                projectLane = DEFAULT_PROJECT_BUCKETS.first(),
            ),
            callerEmpNo = "EMP0001",
            canManage = true,
        )
        return checkNotNull(result.taskId) { "task creation failed: ${result.errors}" }
    }

    /** A card management has dragged into the Completed column. */
    private fun complete(taskId: Int) {
        assertTrue(TaskService.updateStatus(taskId, "Completed", "EMP0001", canManage = true))
    }

    /**
     * An **Other-task** (backlog) card owned by [empNo], on a column of its own — the work that is on the reader
     * but on no project, which the pill counts alongside their project cards since Stage 55.
     */
    private fun otherCard(title: String, empNo: String): Int {
        seq++
        val bucketId = BucketRepository.insert("Backlog ${seq.toString().padStart(2, '0')}")
        return TaskRepository.insert(
            kind = "OTHER", title = title, projectId = null, due = "", priority = "Medium",
            assigneeEmpNo = empNo, status = "Not started", bucketId = bucketId, started = "",
        )
    }

    @Test
    fun `the pill counts the reader's own other tasks alongside their project cards`() {
        // Stage 55 put the reader's own Other-task cards on the Assigned page, so the pill beside that link counts
        // them too: the number is the reader's project cards plus their own backlog cards, and never anybody
        // else's backlog work. Both lists are personal, so management is measured the same way as everyone.
        card("Spread base course", "EMP0002")
        otherCard("Re-line the crusher", "EMP0002")
        otherCard("Water the haul road", "EMP0003")
        assertEquals(2, pill("EMP0002"), "one project card and one of the reader's own other tasks")
        assertEquals(1, pill("EMP0003"), "a colleague's backlog card never lands in this number")
        assertEquals(0, pill("EMP0001"), "management counts their own cards, and this manager holds none")
    }

    @Test
    fun `a finished card stays in the number and on the board`() {
        card("Spread base course", "EMP0002")
        card("Service the crusher", "EMP0002")
        val done = card("Survey the borrow pit", "EMP0002")
        assertEquals(3, pill("EMP0002"), "three assigned cards, three in the menu")

        complete(done)

        // Stage 54: closing a card does not take it off the count — a finished task is still one assigned to me.
        assertEquals(3, pill("EMP0002"), "the pill counts every card, Completed included")
        // The board the same reader opens is built from this list, and it carries all three too — no disagreement.
        val board = TaskService.assignedFor("EMP0002", canManage = false)
        assertEquals(3, board.size, "the board still carries all three")
        assertTrue(board.first { it.id == done }.status == "Completed")
        val html = render { taskKanban(board, UserSession("EMP0002"), canManage = false, "/app/assigned-tasks", 0) }
        assertTrue("Survey the borrow pit" in html, "a completed card is still visible where it was left")
    }

    @Test
    fun `every column including Completed counts`() {
        card("Not started card", "EMP0002", "Not started")
        card("In progress card", "EMP0002", "In progress")
        card("On hold card", "EMP0002", "On hold")
        card("Blocked card", "EMP0002", "Blocked")
        complete(card("Completed card", "EMP0002"))
        // Stage 54: the finished column is not subtracted any more — every card on the board is counted.
        assertEquals(5, pill("EMP0002"))
    }

    @Test
    fun `the pill is the reader's own cards for every role, management included`() {
        // Stage 53: the Assigned tasks board — and the pill that counts it — are personal for every reader, so
        // the number beside the link is the cards with the reader's OWN number on them, management included. The
        // manager here created these cards but is assigned none, so their pill is zero, not the team's total.
        card("Spread base course", "EMP0002")
        val theirs = card("Service the crusher", "EMP0002")
        card("Log the blast holes", "EMP0003")

        assertEquals(0, pill("EMP0001"), "management's board is their own cards, and this manager holds none")
        assertEquals(2, pill("EMP0002"), "the engineer counts their own two cards")
        assertEquals(1, pill("EMP0003"))

        complete(theirs)

        assertEquals(0, pill("EMP0001"), "closing somebody else's card never moves management's number")
        // Stage 54: finishing one of the reader's own cards no longer drops the count — both still count.
        assertEquals(2, pill("EMP0002"), "a card the reader closed is still one assigned to them")
        assertEquals(1, pill("EMP0003"), "closing somebody else's card cannot change this number")
    }

    @Test
    fun `a reader whose only card is finished still reads one`() {
        val done = card("Spread base course", "EMP0002")
        assertEquals(1, pill("EMP0002"))
        complete(done)
        // Stage 54: closing the card does not empty the count. The one assigned card is finished, not gone.
        assertEquals(1, navItems("EMP0002").first { it.id == "assigned-tasks" }.count ?: -1)
        assertEquals(1, TaskService.assignedFor("EMP0002", canManage = false).size)
    }

    @Test
    fun `the pill is the reader's whole board, closed cards included`() {
        val open = card("Spread base course", "EMP0002")
        card("Service the crusher", "EMP0002")
        complete(card("Survey the borrow pit", "EMP0002"))
        val cards = TaskService.assignedFor("EMP0002", canManage = false)
        // Three cards on this reader's board, one of them finished. Since Stage 54 the pill is the SAME figure as
        // the board — the number beside the link is exactly how many cards the reader's own board prints.
        assertEquals(3, cards.size)
        assertEquals(3, pill("EMP0002"), "the pill counts the whole board, not the board minus its closed cards")
        assertEquals(cards.size, pill("EMP0002"), "the pill is the board count with nothing subtracted")
        assertTrue(cards.first { it.id == open }.isOpen, "the untouched card is still somebody's task")
    }

    @Test
    fun `the management-only sections leave a viewer's sidebar but stay in the list the bell resolves through`() {
        // Stage 51: Settings, Access control and Other tasks are management's functions, so a Viewer is not
        // offered them in the menu. The three must still be in [navItems], because the notification bell maps a
        // stored item's section back to a path through it — dropping them there would send a Viewer's own-card
        // backlog notification to /app/projects instead of the board. So the filter lives at [navItemsFor], the
        // sidebar's render, and not in the list every resolver reads.
        val management = navItemsFor("EMP0001").map { it.id }
        MANAGEMENT_ONLY_NAV.forEach { assertTrue(it in management, "management lost the $it link") }
        val viewer = navItemsFor("EMP0002").map { it.id }
        MANAGEMENT_ONLY_NAV.forEach { assertFalse(it in viewer, "a Viewer was offered the $it link") }
        // The other seven links are untouched, and the complete list the bell resolves through still has all ten.
        val personal = setOf("projects", "assigned-tasks", "calendar", "team", "forecasts", "lessons", "todo")
        personal.forEach { assertTrue(it in viewer, "a Viewer lost the $it link, which is theirs") }
        assertEquals(10, navItems("EMP0002").size, "the resolver's list was trimmed, not the render")
        assertEquals(7, viewer.size, "a Viewer's sidebar dropped more than the three management sections")
    }
}
