package mn.murray.pts.db.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.db.Users
import mn.murray.pts.db.Projects
import mn.murray.pts.db.Tasks
import mn.murray.pts.db.TeamMembers
import mn.murray.pts.security.PasswordHasher
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end check of the JSON → SQLite migration path: point the importer at the real
 * `data/pts-data.json.example`, run it against a fresh SQLite file, and assert the data
 * landed correctly with hashed passwords and intact foreign keys.
 */
class JsonImporterTest {

    private val exampleJson = File("data/pts-data.json.example")

    private fun freshDb(dir: File): HikariDataSource {
        val dbFile = File(dir, "import.db")
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        return ds
    }

    @Test
    fun `imports example json into sqlite with hashed passwords`(@TempDir dir: File) {
        assertTrue(exampleJson.exists(), "expected ${exampleJson.absolutePath} to exist for the test")

        // Copy the example to a temp file because the importer renames it after success.
        val legacy = File(dir, "pts-data.json")
        exampleJson.copyTo(legacy, overwrite = true)

        freshDb(dir).use { ds ->
            val summary = JsonImporter.importFromFile(legacy)

            assertTrue(summary.imported, "import was skipped: ${summary.reason}")
            assertTrue(summary.users > 0, "no users imported")
            assertTrue(summary.projects > 0, "no projects imported")
            assertTrue(summary.teamMembers > 0, "no team members imported")

            // The legacy file must be archived (renamed) so we never import twice.
            assertTrue(!legacy.exists(), "legacy JSON should have been renamed after import")
            assertTrue(
                dir.listFiles().orEmpty().any { it.name.startsWith("pts-data.json.migrated-") },
                "expected an archived pts-data.json.migrated-* file"
            )

            transaction {
                // Super admin always exists, is flagged, and has a bcrypt hash (not plaintext).
                val admin = Users.selectAll().where { Users.employeeNumber eq "EMP0000" }.firstOrNull()
                assertNotNull(admin, "EMP0000 super admin missing after import")
                assertTrue(admin!![Users.isSuperAdmin], "EMP0000 should be flagged super admin")
                val hash = admin[Users.passwordHash]
                assertTrue(PasswordHasher.looksHashed(hash), "password should be bcrypt-hashed, got: $hash")
                assertTrue(PasswordHasher.verify("EMP0000", hash), "default password (empNo) should verify")

                // Every task's project_id (when set) resolves to a real project — FK integrity.
                val projectIds = Projects.selectAll().map { it[Projects.id] }.toSet()
                Tasks.selectAll().forEach { row ->
                    val pid = row[Tasks.projectId]
                    if (pid != null) assertTrue(pid in projectIds, "task references missing project $pid")
                }

                // Every user resolves to a team member (FK), and counts are consistent.
                val teamEmps = TeamMembers.selectAll().map { it[TeamMembers.employeeNumber] }.toSet()
                Users.selectAll().forEach { u ->
                    assertTrue(u[Users.employeeNumber] in teamEmps, "user without team_member row")
                }
            }

            // Re-running must be a no-op (guard: users table no longer empty / file gone).
            val again = JsonImporter.importFromFile(legacy)
            assertTrue(again.skipped, "second import should be skipped")
        }
    }

    @Test
    fun `corrupt json is skipped without touching the database`(@TempDir dir: File) {
        val legacy = File(dir, "pts-data.json")
        legacy.writeText("{ this is not valid json ")
        freshDb(dir).use {
            val summary = JsonImporter.importFromFile(legacy)
            assertTrue(summary.skipped, "corrupt JSON should be skipped")
            assertEquals(0L, transaction { Users.selectAll().count() })
            // Corrupt file is left in place (not archived) so the operator can inspect it.
            assertTrue(legacy.exists(), "corrupt legacy file should be preserved for inspection")
        }
    }
}
