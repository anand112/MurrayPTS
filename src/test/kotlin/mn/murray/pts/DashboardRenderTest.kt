package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class DashboardRenderTest {

    private lateinit var ds: HikariDataSource
    private val engineer = UserSession("EMP0002")

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "dashboard.db").absolutePath}"
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

    private fun render(session: UserSession, today: LocalDate): String =
        createHTML().div { dashboardContent(session, today) }

    private fun task(
        kind: String,
        title: String,
        due: String,
        assignee: String,
        projectId: Int? = null,
        bucketId: Int? = null,
        status: String = "Not started",
    ): Int = TaskRepository.insert(
        kind = kind,
        title = title,
        projectId = projectId,
        due = due,
        priority = "High",
        assigneeEmpNo = assignee,
        status = status,
        bucketId = bucketId,
        started = "",
    )

    @Test
    fun `urgent tasks are personal and due this week follows overdue`() {
        val today = LocalDate.of(2026, 11, 23)
        val weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val projectId = ProjectRepository.insert(
            code = "S-01", name = "Haul road", status = "In progress", progress = 42,
            managerEmpNo = "EMP0001", due = weekEnd.toString(), type = "Site projects",
        )
        val bucketId = BucketRepository.insert("Site works")
        val overdueId = task("ASSIGNED", "Past deadline", today.minusDays(1).toString(), "EMP0002", projectId)
        val dueTodayId = task("ASSIGNED", "Due today", today.toString(), "EMP0002", projectId)
        val dueSundayId = task("OTHER", "Due Sunday", weekEnd.toString(), "EMP0002", bucketId = bucketId)
        task("ASSIGNED", "Already complete", today.minusDays(2).toString(), "EMP0002", projectId, status = "Completed")
        task("ASSIGNED", "Colleague's task", today.minusDays(1).toString(), "EMP0001", projectId)
        task("ASSIGNED", "Invalid due date", "not-a-date", "EMP0002", projectId)
        task("ASSIGNED", "No due date", "", "EMP0002", projectId)

        val html = render(engineer, today)

        assertTrue(html.contains("Past deadline"), "the overdue task is missing")
        assertTrue(html.contains("Due today"), "today's task is missing from this week's list")
        assertTrue(html.contains("Due Sunday"), "the last day of the week is missing")
        assertFalse(html.contains("Already complete"), "completed work appeared as urgent")
        assertFalse(html.contains("Colleague's task"), "the dashboard exposed another person's task")
        assertFalse(html.contains("Invalid due date"), "a task with an invalid due date appeared")
        assertFalse(html.contains("No due date"), "a task with no due date appeared")
        assertTrue(html.contains("href=\"/app/assigned-tasks?due=overdue\""), "the overdue card has no filtered link")
        assertTrue(html.contains("href=\"/app/assigned-tasks?due=week\""), "the week card has no filtered link")
        assertTrue(
            html.contains("due=overdue&amp;edit=$overdueId"),
            "opening an overdue task did not keep its filtered list",
        )
        assertTrue(
            html.contains("due=week&amp;edit=$dueTodayId") && html.contains("due=week&amp;edit=$dueSundayId"),
            "a task link did not open its card from the due-this-week list",
        )
        assertTrue(
            html.indexOf("Past deadline") < html.indexOf("Due today"),
            "the overdue group is not placed before the upcoming group",
        )
        assertTrue(html.contains("aria-valuenow=\"42\""), "project progress lost its accessible value")
        assertTrue(html.contains("style=\"width: 42%\""), "the project bar did not use the existing progress value")
        assertTrue(html.contains("href=\"/app/projects/$projectId\""), "the project progress row is not linked")
    }

    @Test
    fun `empty dashboard groups give a positive message and a project next step`() {
        val html = render(engineer, LocalDate.of(2026, 11, 23))

        assertTrue(html.contains("Nothing overdue — your work is clear."))
        assertTrue(html.contains("Nothing else is due this week."))
        assertTrue(html.contains("No projects yet."))
        assertTrue(html.contains("href=\"/app/projects\""), "the empty project state has no next action")
    }

    @Test
    fun `dashboard layout uses fluid grids without adding a breakpoint`() {
        val css = File("src/main/resources/static/css/app.css").readText()

        assertTrue(css.contains(".dashboard-urgency"))
        assertTrue(css.contains("grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr))"))
        assertTrue(css.contains(".progress.dashboard-progress"))
        assertEquals(2, Regex("@media\\s*\\(").findAll(css).count(), "the dashboard added a width breakpoint")
    }
}
