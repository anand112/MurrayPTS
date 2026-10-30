package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.domain.PROJECT_STATUSES
import mn.murray.pts.domain.TASK_STATUSES
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V19 renames one word: a project that called itself **Ongoing** now calls itself **In progress**, because the
 * Stage 96 ask — the new-project Status picker should read as the progression — turned out to be two vocabularies
 * wearing one idea. Cards already said *In progress*; projects said *Ongoing*; and every surface showing both
 * (the Calendar's list, the Team page's dots) carried a translation for it. One word app-wide removes those maps,
 * and one `UPDATE` moves the rows.
 *
 * As in [ChecklistCompletionMigrationTest], what a data migration has to get right is the rows written *before* it
 * ran, so each test builds V1–V18, inserts projects and cards the way a live database holds them, and then runs the
 * real [Migrations.migrate]. Four promises are pinned here:
 *
 *  - `Ongoing` becomes `In progress`, and the board it lands on is the picker's own list — the migration and
 *    `PROJECT_STATUSES` have to say the same word, or a migrated project shows a status no form can choose;
 *  - the other four statuses, and any status the app cannot name, survive exactly as written. This is a rename,
 *    not a cleaning pass;
 * - `updated_at` does not move: a four-year-old project that has simply been renamed must not look as though a
 *    manager touched it today;
 *  - nothing outside `projects.status` is touched — `tasks.status` least of all, since a card in *In progress* is
 *    the word the migration is walking the projects toward, and a card that somehow holds the old word is not this
 *    migration's business.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class ProjectStatusMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )

    /** V1–[version] only: the schema a database has before V19 runs. */
    private fun migrateTo(ds: HikariDataSource, version: String) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .target(version)
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    private fun migrateToV18(ds: HikariDataSource) = migrateTo(ds, "18")

    private fun update(ds: HikariDataSource, sql: String) {
        ds.connection.use { conn -> conn.createStatement().use { it.executeUpdate(sql) } }
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

    /** A project row in the shape a live database holds one, carrying the status the app wrote for it. */
    private fun insertProject(ds: HikariDataSource, code: String, name: String, status: String) {
        update(
            ds,
            "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                "VALUES ('$code', '$name', '$status', 40, '2027-03-30', 'Site projects', " +
                "'2026-02-02T02:02:02Z', '2026-05-05T05:05:05Z')",
        )
    }

    private fun statusOf(ds: HikariDataSource, code: String): String =
        query(ds, "SELECT status FROM projects WHERE code = '$code'").single().single()!!

    private fun stampOf(ds: HikariDataSource, code: String): String =
        query(ds, "SELECT updated_at FROM projects WHERE code = '$code'").single().single()!!

    /** A card with no project and no assignee, in the column it was left in. */
    private fun insertCard(ds: HikariDataSource, title: String, status: String) {
        update(
            ds,
            "INSERT INTO tasks (kind, title, due, priority, status) " +
                "VALUES ('ASSIGNED', '$title', '2026-11-12', 'High', '$status')",
        )
    }

    @Test
    fun `a project that was Ongoing is In progress after the migration, and the word it kept is one the picker offers`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v18-live.db"))
        try {
            migrateToV18(ds)
            insertProject(ds, "S-01", "Haul road widening", "Ongoing")
            insertProject(ds, "S-02", "Weir repair", "Not started")
            insertProject(ds, "S-03", "Bench reprofiling", "On hold")
            insertProject(ds, "S-04", "Stockpile drainage", "Blocked")
            insertProject(ds, "S-05", "Old conveyor survey", "Completed")
            Migrations.migrate(ds)

            assertEquals("In progress", statusOf(ds, "S-01"), "the renamed project kept the retired word")
            // The five words the picker lists are the five a migrated database can hold — and the renamed row
            // lands among them, which is what makes the board's Status column and the form agree afterwards.
            val statuses = listOf("S-01", "S-02", "S-03", "S-04", "S-05").map { statusOf(ds, it) }
            assertTrue(statuses.all { it in PROJECT_STATUSES }, "a migrated status is not a picker choice: $statuses")
            assertEquals(PROJECT_STATUSES.sorted(), statuses.sorted(), "the migration did not leave one row per stage")
            // Only the one word moved, so the other four read exactly as the app left them this morning.
            assertEquals(
                listOf("Not started", "On hold", "Blocked", "Completed"),
                listOf("S-02", "S-03", "S-04", "S-05").map { statusOf(ds, it) },
                "a status the migration never mentions changed anyway",
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `the migration renames one word and rewrites nothing else`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v18-edges.db"))
        try {
            migrateToV18(ds)
            insertProject(ds, "S-06", "Hand-edited stage", "Stalled")
            insertProject(ds, "S-07", "Legacy spelling", "On track")
            insertCard(ds, "Chase the hydraulic hoses", "In progress")
            insertCard(ds, "Strip the platform", "Ongoing")
            Migrations.migrate(ds)

            // A status the app cannot name survives as written: V19 renames *Ongoing*, it does not tidy the column.
            assertEquals("Stalled", statusOf(ds, "S-06"), "the migration cleaned a status nobody asked it to touch")
            assertEquals("On track", statusOf(ds, "S-07"), "the migration rewrote a legacy spelling it does not know")
            // `projects` is the only table this migration names, so a card is untouched even when it holds the
            // word the projects are being moved away from.
            assertEquals(
                listOf("In progress", "Ongoing"),
                query(ds, "SELECT status FROM tasks ORDER BY id").map { it.single()!! },
                "the migration reached into tasks.status",
            )
            assertTrue("In progress" in TASK_STATUSES, "the card word this migration walks projects toward is not a card status")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a renamed project keeps the date it was last written`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v18-stamp.db"))
        try {
            migrateToV18(ds)
            insertProject(ds, "S-08", "Berms at the laydown", "Ongoing")
            insertProject(ds, "S-09", "Weighbridge repair", "Not started")
            val before = listOf("S-08", "S-09").map { stampOf(ds, it) }
            Migrations.migrate(ds)
            // This is the app renaming its own vocabulary, not a manager moving a project, so `updated_at` stays
            // where it was: stamping every row would make a job from last year look as if it moved today.
            assertEquals(before, listOf("S-08", "S-09").map { stampOf(ds, it) }, "the migration stamped the rows it renamed")
        } finally {
            ds.close()
        }
    }
}
