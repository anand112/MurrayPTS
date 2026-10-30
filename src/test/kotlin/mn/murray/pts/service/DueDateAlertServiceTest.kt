package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.AlertRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.UserRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/**
 * The due-date alert rules, against a real SQLite file with the real Flyway schema.
 *
 * Delivery is forced into dry-run (`pts.smtp.dry.run`) so no test can reach the network or depend on
 * a developer's `.env`: in that mode [Mailer] returns `LoggedOnly` and the sweep still writes the
 * ledger, which is exactly what these assertions read. That is the point of splitting rules from
 * transport — the wording, the window, the recipients and the once-a-day dedupe are all verifiable
 * without a mail server, which is the situation this deployment is in today.
 */
class DueDateAlertServiceTest {

    private lateinit var ds: HikariDataSource

    /** Fixed "today" so window arithmetic is deterministic. */
    private val today: LocalDate = LocalDate.of(2026, 10, 12)
    private val day: String get() = today.toString()

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        System.setProperty("pts.smtp.dry.run", "true")
        System.setProperty("pts.alerts.enabled", "true")
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "alerts.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)

        // Roster: a manager, two workers (one with an address, one without).
        listOf(
            Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"),
            Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"),
            Member("EMP0003", "Naran", "Site", "Technician"),
        ).forEach { TeamRepository.upsert(it) }
        UserRepository.insert("EMP0001", "Munkhtsetseg", "x", "Admin", "munkho@murray.mn", null, false, false)
        UserRepository.insert("EMP0002", "Bat-Erdene", "x", "Manager", "bat@murray.mn", null, false, false)
        // EMP0003 deliberately has no user row: an alert to them must still be recorded.
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("pts.smtp.dry.run")
        System.clearProperty("pts.alerts.enabled")
        ds.close()
    }

    private fun project(
        code: String,
        due: String,
        status: String = "In progress",
        progress: Int = 40,
        manager: String? = "EMP0001",
        assignees: List<String> = emptyList(),
    ): Int {
        val id = ProjectRepository.insert(code, "$code job", status, progress, manager, due, "Site projects")
        assignees.forEach { ProjectRepository.addAssignee(id, it) }
        return id
    }

    private fun task(
        title: String,
        due: String,
        status: String = "In progress",
        assignee: String? = "EMP0002",
        projectId: Int? = null,
    ): Int = TaskRepository.insert("ASSIGNED", title, projectId, due, "Medium", assignee, status, null, "")

    private fun ledger() = AlertRepository.forDay(day)

    @Test
    fun `a project inside the lead window alerts its manager and its assignees`() {
        project("SIT-01", due = today.plusDays(5).toString(), progress = 40, assignees = listOf("EMP0002"))

        val summary = DueDateAlertService.runSweep(today)

        assertEquals(2, summary.alertsAttempted, summary.render())
        assertEquals(setOf("EMP0001", "EMP0002"), ledger().map { it.recipientEmpNo }.toSet())
        val managerRow = ledger().first { it.recipientEmpNo == "EMP0001" }
        assertTrue(managerRow.subject.contains("Due in 5 days"), managerRow.subject)
        assertTrue(managerRow.subject.contains("SIT-01"), managerRow.subject)
        assertFalse(managerRow.delivered, "dry run must not report a delivery")
        assertEquals("log", managerRow.channel)
        assertTrue(managerRow.body.contains("Progress : 40%"), managerRow.body)
        assertTrue(managerRow.body.contains("assigned manager"), managerRow.body)
        // The assignee is told why they are hearing about it, not just that they are.
        assertTrue(ledger().first { it.recipientEmpNo == "EMP0002" }.body.contains("listed as an assignee"))
    }

    @Test
    fun `a finished project is silent`() {
        // Both completion signals must be honoured: status Completed, or progress 100.
        project("SIT-02", due = today.plusDays(1).toString(), status = "Completed", progress = 60)
        project("SIT-03", due = today.plusDays(1).toString(), status = "In progress", progress = 100)

        val summary = DueDateAlertService.runSweep(today)

        assertEquals(0, summary.alertsAttempted, summary.render())
        assertTrue(ledger().isEmpty())
    }

    @Test
    fun `an item outside the lead window waits`() {
        project("SIT-04", due = today.plusDays(30).toString())

        assertEquals(0, DueDateAlertService.runSweep(today).alertsAttempted)
        // The day before the window opens is also silent, so the count is the window's, not an off-by-one.
        project("SIT-05", due = today.plusDays(7).toString())
        assertEquals(1, DueDateAlertService.runSweep(today).itemsMatching)
    }

    @Test
    fun `due today and overdue both escalate the wording and keep alerting`() {
        project("SIT-06", due = today.toString())
        project("SIT-07", due = today.minusDays(12).toString())

        DueDateAlertService.runSweep(today)

        val todayRow = ledger().first { it.subject.contains("SIT-06") }
        val lateRow = ledger().first { it.subject.contains("SIT-07") }
        assertTrue(todayRow.subject.contains("Due today"), todayRow.subject)
        assertTrue(todayRow.body.contains("is due today"), todayRow.body)
        assertTrue(lateRow.subject.contains("OVERDUE by 12 days"), lateRow.subject)
        assertTrue(lateRow.body.contains("it was due on ${today.minusDays(12)}"), lateRow.body)
    }

    @Test
    fun `a task alerts its assignee and its project manager`() {
        val projectId = project("SIT-08", due = today.plusDays(20).toString(), manager = "EMP0001")
        task("Replace hydraulic hose", due = today.plusDays(2).toString(), assignee = "EMP0002", projectId = projectId)

        val summary = DueDateAlertService.runSweep(today)

        // The project itself is 20 days out, so the only in-window item is the task, and it reaches
        // two people: the person doing it and the manager of the project it belongs to.
        assertEquals(2, summary.alertsAttempted, summary.render())
        val rows = ledger()
        assertEquals(setOf("EMP0001", "EMP0002"), rows.map { it.recipientEmpNo }.toSet())
        assertTrue(rows.all { it.kind == DueDateAlertService.KIND_TASK })
        assertTrue(rows.first { it.recipientEmpNo == "EMP0002" }.body.contains("the task assignee"))
        assertTrue(rows.first { it.recipientEmpNo == "EMP0001" }.body.contains("manager of the project"))
    }

    @Test
    fun `nobody is mailed twice for the same item on the same day`() {
        project("SIT-09", due = today.plusDays(3).toString(), assignees = listOf("EMP0002"))

        val first = DueDateAlertService.runSweep(today)
        val second = DueDateAlertService.runSweep(today)

        assertEquals(2, first.alertsAttempted)
        assertEquals(0, second.alertsAttempted, second.render())
        assertEquals(2, second.skippedAlreadySent, second.render())
        assertEquals(2, ledger().size, "the UNIQUE key must hold the day even across sweeps")
    }

    @Test
    fun `a recipient without an email address is recorded, not dropped`() {
        // EMP0003 is on the roster but has no account, so there is no address to send to.
        project("SIT-10", due = today.plusDays(1).toString(), manager = null, assignees = listOf("EMP0003"))

        val summary = DueDateAlertService.runSweep(today)

        assertEquals(1, summary.loggedOnly, summary.render())
        val row = ledger().single()
        assertFalse(row.delivered)
        assertTrue(row.error.contains("no email address", ignoreCase = true), row.error)
    }

    @Test
    fun `items with no usable due date are skipped`() {
        project("SIT-11", due = "")
        project("SIT-12", due = "12/10/2026")   // legacy shape: never guess a deadline from it

        assertEquals(0, DueDateAlertService.runSweep(today).alertsAttempted)
        assertTrue(ledger().isEmpty())
    }

    @Test
    fun `an item with nobody attached is reported rather than silently ignored`() {
        project("SIT-13", due = today.plusDays(2).toString(), manager = null, assignees = emptyList())

        val summary = DueDateAlertService.runSweep(today)

        assertEquals(0, summary.alertsAttempted)
        assertTrue(summary.detail.any { it.contains("no manager or assignees") }, summary.detail.toString())
    }

    @Test
    fun `the ledger itself rejects a second row for the same person and day`() {
        // The in-sweep set is only an optimisation: the UNIQUE index has to hold even if two
        // processes overlap, otherwise a restart mid-sweep would mail people twice.
        project("SIT-15", due = today.plusDays(1).toString())
        DueDateAlertService.runSweep(today)
        val existing = ledger().single()

        val again = AlertRepository.record(
            kind = existing.kind, itemId = existing.itemId, recipientEmpNo = existing.recipientEmpNo,
            alertDate = existing.alertDate, subject = "dup", body = "dup", due = existing.due,
            delivered = true, channel = "email", error = "", sentAt = java.time.Instant.now().toString(),
        )

        assertFalse(again, "a duplicate (kind, item, recipient, day) must be rejected by the index")
        assertEquals(1, ledger().size)
        // A different day is a different alert, and must be allowed through.
        val nextDay = AlertRepository.record(
            kind = existing.kind, itemId = existing.itemId, recipientEmpNo = existing.recipientEmpNo,
            alertDate = today.plusDays(1).toString(), subject = "next", body = "next",
            due = existing.due, delivered = true, channel = "email", error = "",
            sentAt = java.time.Instant.now().toString(),
        )
        assertTrue(nextDay, "tomorrow's reminder must not be blocked by today's row")
    }

    @Test
    fun `the ledger can be trimmed by day`() {
        project("SIT-14", due = today.plusDays(1).toString())
        DueDateAlertService.runSweep(today)
        assertEquals(1, ledger().size)

        val removed = AlertRepository.purgeOlderThan(today.plusDays(1).toString())
        assertEquals(1, removed)
        assertTrue(AlertRepository.forDay(day).isEmpty())
    }

    @Test
    fun `the delivery test sends nothing in dry run and spends no alert budget`() {
        val result = DueDateAlertService.runDeliveryTest("munkho@murray.mn")

        assertTrue(result is MailResult.LoggedOnly, "dry run must not open a connection: $result")
        assertTrue(
            ledger().isEmpty(),
            "a diagnostic must not write a due_date_alerts row — that would silence the real reminder today",
        )
    }

    @Test
    fun `the delivery test body names the configuration it used but never the password`() {
        System.setProperty("pts.smtp.password", "sentinel-secret-do-not-leak")
        try {
            val body = DueDateAlertService.testBody("munkho@murray.mn")

            assertFalse(body.contains("sentinel-secret-do-not-leak"), "the password reached the message body")
            assertTrue(body.contains("munkho@murray.mn"), body)
            assertTrue(body.contains("dry run", ignoreCase = true), "the body must state the mode: $body")
            assertTrue(DueDateAlertService.testSubject().startsWith("[PTS]"), "subject keeps the alert prefix")
        } finally {
            System.clearProperty("pts.smtp.password")
        }
    }

    @Test
    fun `emailFor reads the address on file and comes back blank when there is none`() {
        assertEquals("munkho@murray.mn", DueDateAlertService.emailFor("EMP0001"))
        assertEquals("", DueDateAlertService.emailFor("EMP0003"), "no user row means no address, not an error")
    }
}
