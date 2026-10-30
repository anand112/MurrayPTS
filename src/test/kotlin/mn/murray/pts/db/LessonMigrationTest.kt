package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V9 turns the Lesson learnt notes into the sheet's rows, and it is the second migration that widens a
 * table with live data instead of adding one — so what it has to get right is the rows that were already
 * there when it ran.
 *
 * [mn.murray.pts.service.LessonServiceTest] covers the page's own reads against a database that has
 * already arrived at V9; this file is about the arrival. Each test builds the schema up to version 8,
 * writes `lessons` rows in the shape the app before this one wrote them — a title over a summary, a
 * project, nobody and nothing else — and then runs the real [Migrations.migrate]. The assertions are
 * about those rows: the one column of text the sheet reads, the blanks that must stay blanks rather than
 * become a guessed date or an invented author, and the keys that must not take a lesson with them when
 * the project or the person behind them goes.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class LessonMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                // The SET NULLs below are only real when the connection enforces them, which is exactly
                // how the application opens its own connections.
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )

    /** V1–V8 only: the schema a database has before this build's V9 runs. */
    private fun migrateToV8(ds: HikariDataSource) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .target("8")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    private fun query(ds: HikariDataSource, sql: String): List<List<String?>> =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery(sql)
                val width = rs.metaData.columnCount
                val out = mutableListOf<List<String?>>()
                while (rs.next()) out += (1..width).map { rs.getString(it) }
                out
            }
        }

    private fun update(ds: HikariDataSource, sql: String) {
        ds.connection.use { conn -> conn.createStatement().use { it.executeUpdate(sql) } }
    }

    /** One cell of a one-column SELECT, or null when the row is not there. */
    private fun cell(ds: HikariDataSource, sql: String): String? = query(ds, sql).single().single()

    private fun addProject(ds: HikariDataSource, code: String) {
        update(
            ds,
            "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                "VALUES ('$code', 'Job $code', 'In progress', 0, '2027-03-30', 'Site projects', '2026-01-01', '2026-01-01')",
        )
    }

    private fun addMember(ds: HikariDataSource, empNo: String) {
        update(ds, "INSERT INTO team_members (employee_number, name, department, position) VALUES ('$empNo', 'A Person', 'Projects', 'Engineer')")
    }

    private fun addTask(ds: HikariDataSource, title: String, projectId: Int) {
        update(
            ds,
            "INSERT INTO tasks (kind, title, project_id, due, priority, status) " +
                "VALUES ('ASSIGNED', '$title', $projectId, '2026-11-12', 'High', 'In progress')",
        )
    }

    /** A note in the V1 shape: a title, an optional summary, a project, and nothing else. */
    private fun addNote(ds: HikariDataSource, title: String, summary: String, projectId: Int?) {
        update(
            ds,
            "INSERT INTO lessons (title, project_id, summary) VALUES ('$title', ${projectId ?: "NULL"}, '$summary')",
        )
    }

    private fun projectId(ds: HikariDataSource, code: String): Int =
        cell(ds, "SELECT id FROM projects WHERE code = '$code'")!!.toInt()

    @Test
    fun `the six columns arrive and the rows already there keep their words in one of them`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v9-columns.db"))
        try {
            migrateToV8(ds)
            addProject(ds, "S-01")
            val pid = projectId(ds, "S-01")
            addNote(ds, "Monsoon drainage planning", "Undersized culverts caused rework.", pid)
            addNote(ds, "Night shift needs supervision", "", pid)

            Migrations.migrate(ds)

            val columns = query(ds, "SELECT name FROM pragma_table_info('lessons')").map { it.single()!! }
            listOf("lesson", "employee_number", "task_id", "logged_on", "discussed", "archived").forEach {
                assertTrue(it in columns, "the $it column did not arrive: $columns")
            }
            // One cell of text for the sheet to print, joined with the em-dash the page uses — and the
            // second note, which has no summary, keeps just its title rather than a dangling separator.
            assertEquals(
                listOf(
                    "Monsoon drainage planning — Undersized culverts caused rework.",
                    "Night shift needs supervision",
                ),
                query(ds, "SELECT lesson FROM lessons ORDER BY id").map { it.single()!! },
            )
            // The V1 columns stay: `title` is NOT NULL with no default, so an INSERT still needs it, and
            // nothing on the page reads either of them any more.
            assertEquals("Monsoon drainage planning", cell(ds, "SELECT title FROM lessons ORDER BY id LIMIT 1"))
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a carried-over row arrives blank rather than guessed at`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v9-blanks.db"))
        try {
            migrateToV8(ds)
            addProject(ds, "S-02")
            addNote(ds, "A note from the old page", "With a summary.", projectId(ds, "S-02"))

            Migrations.migrate(ds)

            // Nobody owns it, it belongs to no card, and it has no date — the three blanks the sheet prints
            // as dashes and sorts to the end of, rather than filling in with whoever is looking at it.
            assertNull(cell(ds, "SELECT employee_number FROM lessons"))
            assertNull(cell(ds, "SELECT task_id FROM lessons"))
            assertEquals("", cell(ds, "SELECT logged_on FROM lessons"))
            assertEquals("0", cell(ds, "SELECT discussed FROM lessons"))
            assertEquals("0", cell(ds, "SELECT archived FROM lessons"))
            // The project it was about is still the project it was about: only the three new keys are blank.
            assertEquals("S-02", cell(ds, "SELECT p.code FROM lessons l JOIN projects p ON p.id = l.project_id"))
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a lesson outlives the project, the person and the card it points at`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v9-refs.db"))
        try {
            migrateToV8(ds)
            addProject(ds, "S-03")
            val pid = projectId(ds, "S-03")
            addNote(ds, "Order the liner early", "", pid)
            Migrations.migrate(ds)
            val lessonId = cell(ds, "SELECT id FROM lessons")!!.toInt()
            addMember(ds, "EMP0009")
            addTask(ds, "Raise the procurement request", pid)
            val taskId = cell(ds, "SELECT id FROM tasks")!!.toInt()
            update(
                ds,
                "UPDATE lessons SET employee_number = 'EMP0009', task_id = $taskId WHERE id = $lessonId",
            )

            // The card goes first: the lesson that came out of it is the most useful thing on the sheet,
            // not a dependent row to clean up after.
            update(ds, "DELETE FROM tasks WHERE id = $taskId")
            assertEquals(1, query(ds, "SELECT count(*) FROM lessons").single().single()!!.toInt(), "losing a card deleted its lesson")
            assertNull(cell(ds, "SELECT task_id FROM lessons WHERE id = $lessonId"))

            // Then the account behind it — which is what makes an unowned row management's to tidy rather
            // than a hole in the page.
            update(ds, "DELETE FROM team_members WHERE employee_number = 'EMP0009'")
            assertNull(cell(ds, "SELECT employee_number FROM lessons WHERE id = $lessonId"))
            assertEquals("Order the liner early", cell(ds, "SELECT lesson FROM lessons WHERE id = $lessonId"))

            // And the whole project, with its cards: the lesson stays and reads as about nothing.
            update(ds, "DELETE FROM projects WHERE id = $pid")
            assertNull(cell(ds, "SELECT project_id FROM lessons WHERE id = $lessonId"))
            assertEquals(1, query(ds, "SELECT count(*) FROM lessons").single().single()!!.toInt(), "losing a project deleted its lessons")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `the backfill runs once and does not touch a row written after it`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v9-reboot.db"))
        try {
            migrateToV8(ds)
            addProject(ds, "S-04")
            addNote(ds, "Camp beds", "Keep a ten percent buffer.", projectId(ds, "S-04"))
            Migrations.migrate(ds)

            // Somebody edits the words the sheet reads; the title column is left as the old row had it.
            update(ds, "UPDATE lessons SET lesson = 'Keep ten percent spare beds.' WHERE id = 1")

            Migrations.migrate(ds)

            // The engine runs again on every boot of the application, and a versioned migration is not
            // replayed — so the edited row is still the edited row, and there is still one lesson on the
            // page rather than a second copy of it.
            assertEquals("Keep ten percent spare beds.", cell(ds, "SELECT lesson FROM lessons WHERE id = 1"))
            assertEquals(1, query(ds, "SELECT count(*) FROM lessons").single().single()!!.toInt())
            // Pinned against V9 rather than against the history's last row: this build has migrations after
            // it now, and the promise this test makes is about *this* backfill not running twice, which is
            // the same promise whatever arrives after it.
            assertEquals(
                1,
                query(ds, "SELECT count(*) FROM flyway_schema_history WHERE version = '9'").single().single()!!.toInt(),
                "the lesson backfill was replayed on the second boot",
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `the two lists and the two pickers the page reads are indexed`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v9-indexes.db"))
        try {
            migrateToV8(ds)
            Migrations.migrate(ds)
            val indexes = query(ds, "SELECT name FROM pragma_index_list('lessons')").map { it.single()!! }
            // The sheet reads one half of the page at a time, newest first, and both pickers cut a list
            // down before it is printed.
            listOf("idx_lessons_archived", "idx_lessons_employee", "idx_lessons_task").forEach {
                assertTrue(it in indexes, "the $it index did not arrive: $indexes")
            }
        } finally {
            ds.close()
        }
    }
}
