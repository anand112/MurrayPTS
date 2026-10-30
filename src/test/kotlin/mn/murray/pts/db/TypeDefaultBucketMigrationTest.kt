package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V12 adds the *Bucket defaults* catalogue (Stage 61): the extra lanes a project category seeds onto a new
 * board, on top of the three every board opens with.
 *
 * The two promises a migration of this kind is judged on are the ones that are easy to break and invisible
 * until a user hits them: the new table arrives **empty**, because a per-category default is a management
 * decision rather than something the schema can know, and **no existing project's board changes**, because
 * the whole design is that editing a default shapes the *next* project and leaves the last twenty alone.
 * The lanes a project already carries stand untouched, in the same order, with the same ids — a card filed in
 * one of them must still point at the same row after another migration runs. What those lanes are called is
 * V16's business, and [ProjectBucketMigrationTest] pins that; here the board is whatever the build seeds.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class TypeDefaultBucketMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )

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

    private fun insertProject(ds: HikariDataSource, code: String) {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                        "VALUES ('$code', 'Job $code', 'In progress', 0, '2027-03-30', 'Site projects', '2026-01-01', '2026-01-01')",
                )
            }
        }
    }

    @Test
    fun `the catalogue carries V12's four columns plus the colour V17 added`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v12-table.db"))
        try {
            Migrations.migrate(ds)
            val columns = query(ds, "SELECT name FROM pragma_table_info('type_default_buckets')").map { it.single()!! }
            // V12 defines the catalogue and V17 gives it a hue: `colour_slot` is nullable and V17 writes no
            // backfill, so every default that already exists keeps wearing the colour of its position rather
            // than being assigned a shade by a migration nobody asked.
            assertEquals(
                listOf("id", "type", "name", "position", "colour_slot"), columns,
                "the catalogue's columns are not the five the migrations define",
            )
            assertEquals(
                "0",
                query(ds, "SELECT \"notnull\" FROM pragma_table_info('type_default_buckets') WHERE name = 'colour_slot'")
                    .single().single(),
                "a bucket cannot be added without a colour",
            )
            assertEquals(0, query(ds, "SELECT count(*) FROM type_default_buckets").single().single()!!.toInt(),
                "V12 seeded defaults the migration has no way to know")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a project that already exists keeps its lanes, unchanged`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v12-no-touch.db"))
        try {
            Migrations.migrate(ds)
            // A project created *after* the migrations gets its three from ProjectService, then management adds
            // nothing and files a card in one of the lanes it opened with.
            insertProject(ds, "S-01")
            val projectId = query(ds, "SELECT id FROM projects WHERE code = 'S-01'").single().single()!!.toInt()
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    DEFAULT_PROJECT_BUCKETS.forEachIndexed { pos, name ->
                        st.executeUpdate("INSERT INTO project_buckets (project_id, name, position) VALUES ($projectId, '$name', $pos)")
                    }
                }
            }
            val productionId = query(ds, "SELECT id FROM project_buckets WHERE project_id = $projectId AND name = 'Production'")
                .single().single()!!.toInt()
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate(
                        "INSERT INTO tasks (kind, title, project_id, due, priority, status, project_bucket_id) " +
                            "VALUES ('ASSIGNED', 'Order the liner', $projectId, '2026-11-12', 'High', 'In progress', $productionId)",
                    )
                }
            }

            val lanesBefore = query(ds, "SELECT id, name, position FROM project_buckets WHERE project_id = $projectId ORDER BY position")
            Migrations.migrate(ds)

            // The migration is versioned and V12 does not read project_buckets at all — but pinning it here is
            // the promise the user is owed: changing the *default* catalogue never re-cuts a board that exists.
            val lanesAfter = query(ds, "SELECT id, name, position FROM project_buckets WHERE project_id = $projectId ORDER BY position")
            assertEquals(lanesBefore, lanesAfter, "a boot changed an existing project's lanes")
            assertEquals(DEFAULT_PROJECT_BUCKETS, lanesAfter.map { it[1]!! })
            assertEquals(
                productionId.toString(),
                query(ds, "SELECT project_bucket_id FROM tasks WHERE title = 'Order the liner'").single().single(),
                "the card stopped pointing at its lane",
            )
            assertTrue(query(ds, "SELECT count(*) FROM type_default_buckets").single().single()!!.toInt() == 0)
        } finally {
            ds.close()
        }
    }

    @Test
    fun `the catalogue is not replayed on the next boot`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v12-reboot.db"))
        try {
            Migrations.migrate(ds)
            // A management add, then a second boot: the versioned migration does not run again, so a default
            // that was set stays set and is never duplicated by a replay. The position is the first one an
            // extra takes — the three a board opens with hold 0..2.
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("INSERT INTO type_default_buckets (type, name, position) VALUES ('Site projects', 'Commissioning', 3)")
                }
            }
            Migrations.migrate(ds)
            assertEquals(1, query(ds, "SELECT count(*) FROM type_default_buckets").single().single()!!.toInt(),
                "a reboot replayed V12 and doubled the catalogue")
            assertEquals("Commissioning", query(ds, "SELECT name FROM type_default_buckets").single().single())
        } finally {
            ds.close()
        }
    }
}
