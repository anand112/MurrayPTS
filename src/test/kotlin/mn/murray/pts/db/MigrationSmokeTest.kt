package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Decisive check for the v2 persistence decision: can Flyway 10 apply our V1 schema
 * to a SQLite file? If this passes, the whole DB layer is built on Flyway. If it ever
 * fails (e.g. a Flyway version drops SQLite from core), swap Migrations.kt for the
 * Exposed/SchemaUtils fallback — the V*.sql files are reused either way.
 */
class MigrationSmokeTest {

    private fun poolFor(dbFile: File): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )

    private fun tableNames(ds: HikariDataSource): Set<String> =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")
                val names = mutableSetOf<String>()
                while (rs.next()) names += rs.getString(1)
                names
            }
        }

    @Test
    fun `flyway applies V1 schema to sqlite`(@TempDir dir: File) {
        val dbFile = File(dir, "smoke.db")
        poolFor(dbFile).use { ds ->
            Migrations.migrate(ds)

            val tables = tableNames(ds)
            // Every table from V1__init.sql must exist.
            val expected = setOf(
                "positions", "team_members", "users", "projects", "project_assignees",
                "buckets", "tasks", "forecasts", "lessons", "todo_lists", "todo_items",
                "pins", "sessions",
            )
            for (t in expected) {
                assertTrue(t in tables, "expected table '$t' to exist; got $tables")
            }
            // Flyway's own history table proves the engine ran (not a no-op).
            assertTrue("flyway_schema_history" in tables, "Flyway history table missing: $tables")
        }
    }

    @Test
    fun `foreign keys are enforced and migrate is idempotent`(@TempDir dir: File) {
        val dbFile = File(dir, "fk.db")
        poolFor(dbFile).use { ds ->
            Migrations.migrate(ds)
            Migrations.migrate(ds) // second run must be a no-op, not an error

            // Insert a task referencing a non-existent project: must fail when FKs are ON.
            val fkEnforced = ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.execute("PRAGMA foreign_keys = ON")
                    runCatching {
                        st.executeUpdate(
                            "INSERT INTO tasks (kind, title, project_id) VALUES ('ASSIGNED', 'x', 999999)"
                        )
                    }.isFailure
                }
            }
            assertTrue(fkEnforced, "FK constraint was NOT enforced on tasks.project_id")

            // A user row must cascade-delete its sessions; sanity-check the FK link exists.
            val sessionFk = ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    val rs = st.executeQuery("PRAGMA foreign_key_list(sessions)")
                    var found = false
                    while (rs.next()) if (rs.getString("table") == "users") found = true
                    found
                }
            }
            assertTrue(sessionFk, "sessions.employee_number FK to users not found")
            assertEquals(true, sessionFk)
        }
    }
}
