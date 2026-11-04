package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.db.Notifications
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.NotificationDraft
import mn.murray.pts.repository.NotificationRepository
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.seedOpeningThree
import mn.murray.pts.seedOpeningThreeFor
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/**
 * Who gets told, once, and where the message points — the rules behind the bell, on the same
 * real-schema harness as [DueDateAlertServiceTest] (a throwaway SQLite file, the real Flyway V1–V4).
 *
 * Delivery runs in SMTP dry run (`pts.smtp.dry.run`), which is what lets the mail half of each event
 * be asserted at all: the mailer answers `LoggedOnly`, the status lands in the row, and no test can
 * reach a network or read a developer's `.env`.
 *
 * The three rules these cases exist to protect are the ones a user would notice breaking: nobody
 * hears about their own action, one item produces one inbox row per day however many times it is
 * touched, and an inbox row only ever points inside `/app/`.
 */
class NotificationServiceTest {

    private lateinit var ds: HikariDataSource
    private val manager = "EMP0001"
    private val worker = "EMP0002"
    private val technician = "EMP0003"

    private lateinit var projectName: String
    private var projectId: Int = 0

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        System.setProperty("pts.smtp.dry.run", "true")
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "notifications.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        listOf(
            Member(manager, "Munkhtsetseg", "Operations", "Project Manager"),
            Member(worker, "Bat-Erdene", "Workshop", "Engineer"),
            Member(technician, "Naran", "Site", "Technician"),
        ).forEach { TeamRepository.upsert(it) }
        // Only two of the three have an account: a notification to the third must still be written.
        UserRepository.insert(manager, "Munkhtsetseg", "x", "Admin", "munkho@murray.mn", null, false, false)
        UserRepository.insert(worker, "Bat-Erdene", "x", "Manager", "bat@murray.mn", null, false, false)
        projectId = ProjectRepository.insert(
            code = "S-01", name = "Haul road widening", status = "In progress", progress = 0,
            managerEmpNo = manager, due = "2027-03-30", type = "Site projects",
        )
        // The three phases (Stage 48), which a card has to name; this harness writes the project row itself, so
        // it gives the project the three-lane board every board had before Stage 100.
        seedOpeningThree(projectId)
        // Stage 100: a project made through the create route below takes its board from this list, so the
        // category is given the same three.
        seedOpeningThreeFor("Site projects")
        projectName = ProjectRepository.findById(projectId)!!.name
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("pts.smtp.dry.run")
        ds.close()
    }

    // --- helpers -------------------------------------------------------------

    private fun createTask(
        title: String,
        assignee: String,
        caller: String = manager,
        status: String = "Not started",
        /** The second person on a project card (Stage 122); blank is the ordinary one-owner card. */
        assignee2: String = "",
    ): Int {
        val id = TaskService.create(
            TaskDraft(
                title = title,
                projectName = projectName,
                assigneeEmpNo = assignee,
                assignee2EmpNo = assignee2,
                due = "2026-11-12",
                projectLane = DEFAULT_PROJECT_BUCKETS.first(),
                status = status,
            ),
            callerEmpNo = caller,
            canManage = true,
        ).taskId
        assertNotNull(id, "the task should have been created")
        return id!!
    }

    /** A row written straight to the table, for the cases the event methods cannot produce. */
    private fun draftOf(recipient: String, kind: String, itemId: Int, day: String, link: String) =
        NotificationDraft(
            recipientEmpNo = recipient,
            kind = kind,
            itemKind = "TASK",
            itemId = itemId,
            dedupeDay = day,
            title = "Hand-written row",
            body = "written straight to the table",
            link = link,
        )

    private fun mailStatus(id: Int): String = transaction {
        Notifications.selectAll().where { Notifications.id eq id }.first()[Notifications.mailStatus]
    }

    // --- task assignment -------------------------------------------------------

    @Test
    fun `creating a task tells the assignee, with the project as its target`() {
        createTask("Rig the bench", worker)
        val items = NotificationService.inbox(worker)
        assertEquals(1, items.size)
        val row = items.first()
        assertTrue(row.isUnread, "a fresh row is unread")
        assertEquals(NotificationService.KIND_TASK_ASSIGNED, row.kind)
        assertEquals("/app/projects/$projectId", row.link)
        assertTrue(row.title.contains("Rig the bench"), row.title)
        // The line names the person who pressed the button, not their employee number.
        assertTrue("Munkhtsetseg" in row.body && "EMP0001" !in row.body, row.body)
        assertEquals(1, NotificationService.unreadCount(worker))
        assertEquals(0, NotificationService.unreadCount(manager), "the manager is not told about their own act")
    }

    /**
     * The notice is about *who holds the card*, not about where the card starts.
     *
     * Work is often under way — or even finished — by the time somebody enters it, and the popup's Status
     * field is how that is recorded on a create. None of those statuses make the person already doing the
     * work any less the person who was handed it, so the same inbox row and the same mail are written as
     * for a card saved as *Not started*.
     */
    @Test
    fun `a card entered already under way, or already finished, still tells the assignee`() {
        createTask("Strip the pump", worker, status = "In progress")
        createTask("Pour the pad", technician, status = "Completed")
        val underWay = NotificationService.inbox(worker).single()
        assertEquals(NotificationService.KIND_TASK_ASSIGNED, underWay.kind)
        assertTrue(underWay.isUnread, "whatever the status, the notice is new")
        assertTrue(underWay.title.contains("Strip the pump"), underWay.title)
        assertEquals("/app/projects/$projectId", underWay.link)
        assertEquals(1, NotificationService.unreadCount(worker))
        assertEquals("logged", mailStatus(underWay.id), "the mail goes with the bell row, status or no status")
        assertEquals(1, NotificationService.inbox(technician).size, "a card entered as finished is still an assignment")
    }

    @Test
    fun `a card already under way still tells the person it moves to`() {
        val id = createTask("Strip the pump", worker, status = "In progress")
        TaskService.updateAssigned(
            id,
            TaskDraft(
                title = "Strip the pump", projectName = projectName,
                assigneeEmpNo = technician, status = "In progress",
            ),
            callerEmpNo = manager,
            canManage = true,
        )
        assertEquals(1, NotificationService.inbox(technician).size, "a move is a move at any status")
        assertEquals(1, NotificationService.inbox(worker).size, "and the card that left is still the old holder's news")
    }

    @Test
    fun `dry-run delivery is recorded on the row, address or no address`() {
        createTask("Rig the bench", worker)
        createTask("Site walk", technician)
        val withAddress = NotificationService.inbox(worker).first()
        val without = NotificationService.inbox(technician).first()
        assertEquals("logged", mailStatus(withAddress.id), "dry run logs instead of sending")
        assertEquals("no-address", mailStatus(without.id), "no user row means nothing to send to")
        assertEquals(1, NotificationService.inbox(technician).size, "the inbox row survives having no mail")
    }

    @Test
    fun `nobody is notified about their own assignment`() {
        createTask("Own errand", manager, caller = manager)
        assertTrue(NotificationService.inbox(manager).isEmpty())
    }

    @Test
    fun `one task produces one inbox row a day, however many times it is touched`() {
        val id = createTask("Rig the bench", worker)
        NotificationService.taskAssigned(id, manager)
        NotificationService.taskAssigned(id, manager)
        NotificationService.taskAssigned(id, technician)
        assertEquals(1, NotificationService.inbox(worker).size, "the UNIQUE day key is the whole rule")
    }

    @Test
    fun `an unknown actor still reads like a person`() {
        createTask("Rig the bench", worker, caller = "EMP9999")
        val body = NotificationService.inbox(worker).single().body
        assertTrue(body.startsWith("A manager"), body)
    }

    @Test
    fun `a card with two people tells both of them, and nobody hears about their own act`() {
        createTask("Rig the bench", worker, caller = manager, assignee2 = technician)
        assertEquals(1, NotificationService.inbox(worker).size, "the first holder was not told")
        assertEquals(1, NotificationService.inbox(technician).size, "the second holder was not told")
        assertEquals(0, NotificationService.unreadCount(manager), "the manager is told about their own act")
        // Both rows point at the same card and name the same person, so neither reads as a different event.
        val theirs = NotificationService.inbox(technician).single()
        assertEquals(NotificationService.KIND_TASK_ASSIGNED, theirs.kind)
        assertEquals("/app/projects/$projectId", theirs.link)
        assertTrue(theirs.title.contains("Rig the bench"), theirs.title)
        // A card whose second person is the person pressing the button is management on their own card, so it
        // is not an assignment to them: only the first holder hears.
        createTask("Pour the pad", worker, caller = technician, assignee2 = technician)
        assertEquals(1, NotificationService.inbox(technician).size, "a self-assignment was announced")
    }

    // --- re-assignment ----------------------------------------------------------

    @Test
    fun `editing a task only tells somebody when it moves to them`() {
        val id = createTask("Rig the bench", worker)
        // Renamed, same assignee: the person already working on it gets no second message.
        TaskService.updateAssigned(
            id,
            TaskDraft(title = "Rig and level the bench", projectName = projectName, assigneeEmpNo = worker),
            callerEmpNo = manager,
            canManage = true,
        )
        assertEquals(1, NotificationService.inbox(worker).size, "a rename is not a re-assignment")
        // Moved: the new holder hears, the previous one does not.
        TaskService.updateAssigned(
            id,
            TaskDraft(title = "Rig and level the bench", projectName = projectName, assigneeEmpNo = technician),
            callerEmpNo = manager,
            canManage = true,
        )
        assertEquals(1, NotificationService.inbox(technician).size)
        assertEquals(1, NotificationService.inbox(worker).size, "losing a card is not a notification")
    }

    @Test
    fun `an edit that adds a second person tells only the new one`() {
        val id = createTask("Rig the bench", worker)
        assertEquals(1, NotificationService.inbox(worker).size)
        // The same card, now shared with the technician. The person already on it keeps their one row.
        TaskService.updateAssigned(
            id,
            TaskDraft(
                title = "Rig the bench", projectName = projectName,
                assigneeEmpNo = worker, assignee2EmpNo = technician,
            ),
            callerEmpNo = manager,
            canManage = true,
        )
        assertEquals(1, NotificationService.inbox(technician).size, "the person added was not told")
        assertEquals(1, NotificationService.inbox(worker).size, "the person already on the card was told again")
        // A later edit that keeps both names announces nothing to either.
        TaskService.updateAssigned(
            id,
            TaskDraft(
                title = "Rig and level the bench", projectName = projectName,
                assigneeEmpNo = worker, assignee2EmpNo = technician,
            ),
            callerEmpNo = manager,
            canManage = true,
        )
        assertEquals(1, NotificationService.inbox(worker).size)
        assertEquals(1, NotificationService.inbox(technician).size)
    }

    // --- project manager ---------------------------------------------------------

    @Test
    fun `a project created with a manager tells that manager`() {
        val id = ProjectService.create(
            ProjectDraft(
                type = "Site projects", code = "S-02", name = "Wash plant ties",
                managerName = "Naran", start = "2026-11-01", end = "2026-12-20",
            ),
            actorEmpNo = manager,
        ).projectId
        assertNotNull(id)
        val row = NotificationService.inbox(technician).single()
        assertEquals(NotificationService.KIND_PROJECT_MANAGER, row.kind)
        assertEquals("/app/projects/$id", row.link)
        assertTrue(row.body.contains("End date 2026-12-20"), row.body)
    }

    @Test
    fun `setting a manager tells them once, and re-pressing the button tells nobody`() {
        ProjectService.setManager(projectId, "Naran", manager)
        assertEquals(1, NotificationService.inbox(technician).size)
        // Same name as already on file: the project did not change hands, so nothing is written.
        ProjectService.setManager(projectId, "Naran", manager)
        assertEquals(1, NotificationService.inbox(technician).size)
    }

    @Test
    fun `a manager taking a project for themselves gets no message`() {
        val id = ProjectRepository.insert(
            code = "S-09", name = "Unmanaged job", status = "Not started", progress = 0,
            managerEmpNo = null, due = "", type = "Site projects",
        )
        ProjectService.setManager(id, "Munkhtsetseg", manager)
        assertTrue(NotificationService.inbox(manager).isEmpty())
    }

    // --- the due-date copy --------------------------------------------------------

    @Test
    fun `a due-date reminder lands in the inbox without mailing again`() {
        NotificationService.dueDate(worker, NotificationService.ITEM_PROJECT, projectId, "S-01 due soon", "Line one\nLine two")
        NotificationService.dueDate(technician, NotificationService.ITEM_TASK, 7, "T-00007 due soon", "Go now")
        val row = NotificationService.inbox(worker).single()
        assertEquals("/app/projects/$projectId", row.link)
        assertEquals("Line one", row.body, "the dropdown shows the headline, not the letter")
        assertEquals("inbox only", mailStatus(row.id), "the sweep already mailed this one")
        assertEquals("/app/assigned-tasks", NotificationService.inbox(technician).single().link)
        // A second sweep the same day — which a 5-minute interval guarantees — writes nothing more.
        NotificationService.dueDate(worker, NotificationService.ITEM_PROJECT, projectId, "S-01 due soon", "Line one")
        assertEquals(1, NotificationService.inbox(worker).size)
    }

    // --- reading -------------------------------------------------------------------

    @Test
    fun `marking read only ever touches your own rows`() {
        createTask("Rig the bench", worker)
        createTask("Site walk", technician)
        val theirs = NotificationService.inbox(technician).single().id
        val mine = NotificationService.inbox(worker).single().id
        assertFalse(NotificationService.markRead(worker, theirs), "another person's id is not yours to read")
        assertTrue(NotificationService.markRead(worker, mine))
        assertFalse(NotificationService.markRead(worker, mine), "a row already read is not re-marked")
        assertEquals(0, NotificationService.unreadCount(worker))
        assertEquals(1, NotificationService.unreadCount(technician))
    }

    @Test
    fun `mark all read clears one badge and leaves the rest of the roster alone`() {
        createTask("Toward the workshop", worker)
        createTask("Toward the site", technician)
        assertEquals(1, NotificationService.markAllRead(worker))
        assertEquals(0, NotificationService.unreadCount(worker))
        assertEquals(1, NotificationService.unreadCount(technician))
        assertEquals(0, NotificationService.markAllRead(manager), "an empty inbox has nothing to flip")
    }

    @Test
    fun `a link is only honoured inside the app`() {
        createTask("Rig the bench", worker)
        val mine = NotificationService.inbox(worker).single()
        assertEquals("/app/projects/$projectId", NotificationService.safeLink(worker, mine.id))
        assertNull(NotificationService.safeLink(technician, mine.id), "not your row, not your link")

        NotificationRepository.insert(
            draftOf(worker, "CUSTOM", 900, LocalDate.now().toString(), "https://evil.example/steal")
        )
        val planted = NotificationService.inbox(worker).first { it.kind == "CUSTOM" }
        assertNull(NotificationService.safeLink(worker, planted.id), "an outside target is refused, not followed")
    }

    // --- housekeeping -----------------------------------------------------------------

    @Test
    fun `the bell shows the newest twenty, newest first`() {
        val ids = (1..25).map { i ->
            NotificationRepository.insert(draftOf(worker, "CUSTOM", i, "2026-0$i", "/app/projects"))
        }
        val inbox = NotificationService.inbox(worker)
        assertEquals(20, inbox.size)
        assertEquals(ids.last(), inbox.first().id, "descending id is descending time")
        assertEquals(ids.takeLast(20).reversed(), inbox.map { it.id })
    }

    @Test
    fun `old rows are trimmed and the setting can switch trimming off`() {
        val today = LocalDate.now().toString()
        val stale = LocalDate.now().minusDays(45).toString()
        NotificationRepository.insert(draftOf(worker, "OLD", 1, stale, "/app/projects"))
        NotificationRepository.insert(draftOf(worker, "NEW", 2, today, "/app/projects"))
        assertEquals(0, NotificationService.purgeOlderThan(0), "0 disables trimming")
        assertEquals(2, NotificationService.inbox(worker).size)
        assertEquals(1, NotificationService.purgeOlderThan(30))
        assertEquals("NEW", NotificationService.inbox(worker).single().kind)
    }
}

