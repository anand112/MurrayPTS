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
 * V18 gives a card an archive: a flag, the lane it came from (id and name), and the day it left.
 *
 * The whole shape is a **flag on the existing row** rather than an archive table, so what this migration has to get
 * right is that nothing moves. Every card already on the table reads as not archived, its lane is untouched, and the
 * three new columns are absent rather than invented. The snapshot's lane id is deliberately a plain integer with no
 * foreign key: the path that matters most is a bucket being removed, and a referencing column would be SET NULL by
 * the same delete that archives the card — losing the memory the column exists to keep.
 *
 * Nothing here touches `data/`; each test gets its own temporary SQLite file.
 */
class TaskArchiveMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )

    /** V1–[version] only: the schema a database has before the migrations above it run. */
    private fun migrateTo(ds: HikariDataSource, version: String) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .target(version)
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

    private fun columnsOf(ds: HikariDataSource, table: String): List<String> =
        query(ds, "SELECT name FROM pragma_table_info('$table')").map { it.single()!! }

    @Test
    fun `V18 flags no card that was already on the table and keeps every lane filing`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v18-boot.db"))
        try {
            // A database at V17 with one project, one column and one card filed in it — the shape every board has
            // the moment before this migration runs.
            migrateTo(ds, "17")
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate(
                        "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                            "VALUES ('S-01', 'Haul road widening', 'In progress', 0, '2027-03-30', 'Site projects', " +
                            "'2026-01-01', '2026-01-01')",
                    )
                    val projectId = st.executeQuery("SELECT id FROM projects WHERE code = 'S-01'")
                        .let { it.next(); it.getInt(1) }
                    st.executeUpdate(
                        "INSERT INTO project_buckets (project_id, name, position) VALUES ($projectId, 'Production', 2)",
                    )
                    val laneId = st.executeQuery("SELECT id FROM project_buckets WHERE project_id = $projectId")
                        .let { it.next(); it.getInt(1) }
                    st.executeUpdate(
                        "INSERT INTO tasks (kind, title, project_id, due, priority, assignee_emp_no, status, " +
                            "started, project_bucket_id) VALUES ('ASSIGNED', 'Spread base course', $projectId, " +
                            "'2026-11-12', 'High', NULL, 'Not started', '2026-11-01', $laneId)",
                    )
                }
            }
            val before = query(
                ds,
                "SELECT id, title, project_id, status, project_bucket_id FROM tasks ORDER BY id",
            )

            Migrations.migrate(ds)

            assertTrue("archived" in columnsOf(ds, "tasks"), "a card cannot be archived")
            assertTrue("archived_lane_id" in columnsOf(ds, "tasks"), "the archive cannot remember the column")
            assertTrue("archived_lane" in columnsOf(ds, "tasks"), "the archive cannot print where a card came from")
            assertTrue("archived_on" in columnsOf(ds, "tasks"), "the archive cannot date a row")
            // The card that was there is the card that is there: same row, same lane, same status — only flagged
            // not archived, which is what keeps every board the shape it had before the upgrade.
            assertEquals(
                before,
                query(ds, "SELECT id, title, project_id, status, project_bucket_id FROM tasks ORDER BY id"),
                "V18 moved or rewrote a card",
            )
            query(ds, "SELECT archived, archived_lane_id, archived_lane, archived_on FROM tasks").forEach {
                assertEquals("0", it[0], "an existing card was archived by the migration")
                assertNull(it[1], "a migration invented a lane snapshot")
                assertNull(it[2], "a migration invented a lane name")
                assertNull(it[3], "a migration invented a day")
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `the flag defaults to zero, the snapshot is nullable, and a second boot changes nothing`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v18-reboot.db"))
        try {
            Migrations.migrate(ds)
            // The declared shapes are what the repository relies on: a card inserted without an archive is still
            // valid (`archived` defaults to 0), and a snapshot is optional because most cards are never archived.
            val flag = query(
                ds,
                "SELECT \"type\", \"notnull\", \"dflt_value\" FROM pragma_table_info('tasks') WHERE name = 'archived'",
            ).single()
            assertEquals("BOOLEAN", flag[0], "the flag is not SQLite's boolean")
            assertEquals("1", flag[1], "a card could be written with no archive answer")
            assertEquals("0", flag[2], "existing and new cards would not default to on the board")
            listOf("archived_lane_id" to "INTEGER", "archived_lane" to "TEXT", "archived_on" to "TEXT")
                .forEach { (name, type) ->
                    val info = query(
                        ds,
                        "SELECT \"type\", \"notnull\" FROM pragma_table_info('tasks') WHERE name = '$name'",
                    ).single()
                    assertEquals(type, info[0], "$name is not a $type")
                    assertEquals("0", info[1], "$name would have to be given a value")
                }
            // The snapshot is not a reference: a removed bucket must not take the memory with it. The card's own
            // `project_bucket_id` is the live filing and does cascade; `archived_lane_id` is the past.
            val references = query(ds, "SELECT \"from\" FROM pragma_foreign_key_list('tasks')").map { it.single() }
            assertTrue("project_bucket_id" in references, "a card's filing is no longer a reference")
            assertTrue("archived_lane_id" !in references, "the archive's memory is tied to a column that can go")

            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate(
                        "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                            "VALUES ('S-02', 'Berms at the laydown', 'In progress', 0, '2027-04-12', 'Site projects', " +
                            "'2026-01-01', '2026-01-01')",
                    )
                    // A card written the way the application writes one, with none of V18's columns named.
                    st.executeUpdate(
                        "INSERT INTO tasks (kind, title, due, priority, status, started) " +
                            "VALUES ('OTHER', 'Clean the spill bund', '2026-11-12', 'Medium', 'Not started', '')",
                    )
                }
            }
            val before = query(ds, "SELECT id, title, archived, archived_on FROM tasks ORDER BY id")
            assertEquals(1, before.size)
            assertEquals("0", before.single()[2], "a new card was not on the board by default")
            Migrations.migrate(ds)
            assertEquals(before, query(ds, "SELECT id, title, archived, archived_on FROM tasks ORDER BY id"),
                "a second boot touched a card's archive")
        } finally {
            ds.close()
        }
    }
}
