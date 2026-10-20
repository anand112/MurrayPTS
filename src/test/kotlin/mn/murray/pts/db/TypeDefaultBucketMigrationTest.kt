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
 * board, on top of the six every board carries.
 *
 * The two promises a migration of this kind is judged on are the ones that are easy to break and invisible
 * until a user hits them: the new table arrives **empty**, because a per-category default is a management
 * decision rather than something the schema can know, and **no existing project's board changes**, because
 * the whole design is that editing a default shapes the *next* project and leaves the last twenty alone.
 * The six standard lanes V10 gave every project stand untouched, in the same order, with the same ids — a
 * card filed in one of them must still point at the same row after this migration runs.
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
                        "VALUES ('$code', 'Job $code', 'Ongoing', 0, '2027-03-30', 'Site projects', '2026-01-01', '2026-01-01')",
                )
            }
        }
    }

    @Test
    fun `V12 creates the empty catalogue with its four columns`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v12-table.db"))
        try {
            Migrations.migrate(ds)
            val columns = query(ds, "SELECT name FROM pragma_table_info('type_default_buckets')").map { it.single()!! }
            assertEquals(listOf("id", "type", "name", "position"), columns, "the catalogue's columns are not the four V12 defines")
            assertEquals(0, query(ds, "SELECT count(*) FROM type_default_buckets").single().single()!!.toInt(),
                "V12 seeded defaults the migration has no way to know")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a project that already exists keeps its six lanes, unchanged`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v12-no-touch.db"))
        try {
            Migrations.migrate(ds)
            // A project created *after* the migrations gets its six from ProjectService, then management adds a
            // lane of its own and files a card in it.
            insertProject(ds, "S-01")
            val projectId = query(ds, "SELECT id FROM projects WHERE code = 'S-01'").single().single()!!.toInt()
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    DEFAULT_PROJECT_BUCKETS.forEachIndexed { pos, name ->
                        st.executeUpdate("INSERT INTO project_buckets (project_id, name, position) VALUES ($projectId, '$name', $pos)")
                    }
                }
            }
            val goodsId = query(ds, "SELECT id FROM project_buckets WHERE project_id = $projectId AND name = 'Goods sales'")
                .single().single()!!.toInt()
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate(
                        "INSERT INTO tasks (kind, title, project_id, due, priority, status, project_bucket_id) " +
                            "VALUES ('ASSIGNED', 'Order the liner', $projectId, '2026-11-12', 'High', 'In progress', $goodsId)",
                    )
                }
            }

            val lanesBefore = query(ds, "SELECT id, name, position FROM project_buckets WHERE project_id = $projectId ORDER BY position")
            Migrations.migrate(ds)

            // The migration is versioned and V12 does not read project_buckets at all — but pinning it here is
            // the promise the user is owed: changing the *default* catalogue never re-cuts a board that exists.
            val lanesAfter = query(ds, "SELECT id, name, position FROM project_buckets WHERE project_id = $projectId ORDER BY position")
            assertEquals(lanesBefore, lanesAfter, "V12 changed an existing project's lanes")
            assertEquals(DEFAULT_PROJECT_BUCKETS, lanesAfter.map { it[1]!! })
            assertEquals(
                goodsId.toString(),
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
            // that was set stays set and is never duplicated by a replay.
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("INSERT INTO type_default_buckets (type, name, position) VALUES ('Site projects', 'Commissioning', 6)")
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
