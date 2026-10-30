package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V17 gives a bucket a colour of its own — one nullable `colour_slot` on each of the two tables a bucket is
 * kept on: `project_buckets`, the columns of one project's board, and `type_default_buckets`, the catalogue a
 * category offers its new projects.
 *
 * What a migration of this shape has to get right is the rows that already exist. There are **none** to write:
 * a hue is a choice somebody makes on a form, and a migration has nobody behind it, so V17 leaves every existing
 * bucket NULL. NULL is not "no colour" — it is "wear the colour of my place in the run", which is the rule the
 * board painted by before this column existed ([mn.murray.pts.domain.effectiveColourSlot]). A project that was
 * on the table before V17 therefore looks exactly as it did the moment after it, and the stylesheet keeps the
 * only copy of the six hues.
 *
 * A slot is stored rather than a colour for the same reason the app renders a page rather than shipping a
 * theme: `dot-0`…`dot-5` are the stylesheet's, and a hex string in a row could paint a colour no theme asked
 * for. Nothing here touches `data/` — each test gets its own temporary SQLite file.
 */
class BucketColourMigrationTest {

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
    fun `V17 adds one nullable slot to each bucket table and writes no colours`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v17-boot.db"))
        try {
            // A database at V16: the three phases are already seeded onto the project, and one category already
            // has two defaults on its catalogue.
            migrateTo(ds, "16")
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate(
                        "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                            "VALUES ('S-01', 'Haul road widening', 'In progress', 0, '2027-03-30', 'Site projects', " +
                            "'2026-01-01', '2026-01-01')",
                    )
                    val projectId = st.executeQuery("SELECT id FROM projects WHERE code = 'S-01'")
                        .let { it.next(); it.getInt(1) }
                    DEFAULT_PROJECT_BUCKETS.forEachIndexed { pos, name ->
                        st.executeUpdate(
                            "INSERT INTO project_buckets (project_id, name, position) VALUES ($projectId, '$name', $pos)",
                        )
                    }
                    listOf("Commissioning", "QAQC").forEachIndexed { pos, name ->
                        st.executeUpdate(
                            "INSERT INTO type_default_buckets (type, name, position) " +
                                "VALUES ('Fabrication/Manufacturing', '$name', $pos)",
                        )
                    }
                }
            }
            val lanesBefore = query(ds, "SELECT id, name, position FROM project_buckets ORDER BY id")
            val defaultsBefore = query(ds, "SELECT id, type, name, position FROM type_default_buckets ORDER BY id")

            Migrations.migrate(ds)

            assertTrue("colour_slot" in columnsOf(ds, "project_buckets"), "a board's buckets cannot hold a colour")
            assertTrue("colour_slot" in columnsOf(ds, "type_default_buckets"), "the catalogue cannot hold a colour")
            // …and the rows that were there are the rows that are there, in the same order, with nothing chosen
            // for them: every existing bucket is NULL, which the board reads as its position.
            assertEquals(lanesBefore, query(ds, "SELECT id, name, position FROM project_buckets ORDER BY id"),
                "V17 moved or rewrote a board's lanes")
            assertEquals(defaultsBefore, query(ds, "SELECT id, type, name, position FROM type_default_buckets ORDER BY id"),
                "V17 moved or rewrote the catalogue")
            query(ds, "SELECT colour_slot FROM project_buckets").forEach {
                assertNull(it.single(), "a migration invented a colour for an existing column")
            }
            query(ds, "SELECT colour_slot FROM type_default_buckets").forEach {
                assertNull(it.single(), "a migration invented a colour for an existing default")
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `the slot is nullable, holds an integer, and a second boot changes nothing`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v17-reboot.db"))
        try {
            Migrations.migrate(ds)
            // The column's declared type and its nullability are what the repository relies on: a slot is an
            // integer or absent, and "absent" has to be representable or a bucket could not be added without one.
            val info = query(
                ds,
                "SELECT \"type\", \"notnull\" FROM pragma_table_info('project_buckets') WHERE name = 'colour_slot'",
            ).single()
            assertEquals("INTEGER", info[0], "the slot is not the number the six positions are")
            assertEquals("0", info[1], "every new bucket would have to be given a colour")

            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate(
                        "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                            "VALUES ('S-02', 'Berms at the laydown', 'In progress', 0, '2027-04-12', 'Site projects', " +
                            "'2026-01-01', '2026-01-01')",
                    )
                }
            }
            val before = query(ds, "SELECT id, name, colour_slot FROM project_buckets ORDER BY id")
            Migrations.migrate(ds)
            assertEquals(before, query(ds, "SELECT id, name, colour_slot FROM project_buckets ORDER BY id"),
                "a second boot touched a bucket's colour")
        } finally {
            ds.close()
        }
    }
}
