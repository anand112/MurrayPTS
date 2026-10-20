package mn.murray.pts.db.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.DemoData
import mn.murray.pts.db.Migrations
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.service.AuthResult
import mn.murray.pts.service.AuthService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The "brand-new production database" path — the one a launch actually takes, and the one that
 * has no legacy JSON to import. It used to crash on boot: `users.employee_number` has a foreign
 * key into `team_members`, so inserting the super admin before any roster row existed violated
 * SQLITE_CONSTRAINT_FOREIGNKEY. These tests pin the fixed behaviour.
 */
class FreshDatabaseBootstrapTest {

    private fun freshDb(dir: File, name: String): HikariDataSource {
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, name).absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        return ds
    }

    @Test
    fun `an empty database bootstraps a super admin that can log in`(@TempDir dir: File) {
        freshDb(dir, "fresh.db").use {
            assertTrue(JsonImporter.databaseIsEmpty(), "a migrated database must start empty")

            AuthService.ensureSuperAdmin()

            val admin = UserRepository.findByEmpNo("EMP0000")
            assertNotNull(admin, "ensureSuperAdmin must create the account")
            assertNotNull(TeamRepository.findByEmpNo("EMP0000"), "the roster row the FK needs must exist too")
            // No PTS_ADMIN_PASSWORD in tests, so the legacy default applies and the gate engages.
            assertTrue(AuthService.mustChangePassword("EMP0000"))
            assertTrue(AuthService.authenticate("EMP0000", "EMP0000", ip = "127.0.0.1") is AuthResult.Success)

            // Idempotent: a second boot must not duplicate or reset anything.
            AuthService.ensureSuperAdmin()
            assertEquals(1L, UserRepository.count())
        }
    }

    @Test
    fun `demo seeding fills an empty database and still gates the admin`(@TempDir dir: File) {
        freshDb(dir, "seed.db").use {
            val summary = JsonImporter.import(DemoData.seed())

            assertTrue(summary.users > 0 && summary.projects > 0 && summary.tasks > 0, "seed wrote nothing: $summary")
            assertFalse(JsonImporter.databaseIsEmpty(), "a seeded database is no longer empty")
            // Seeded accounts never had an explicit password, so they must all change it first.
            assertTrue(AuthService.mustChangePassword("EMP0000"))
            assertTrue(AuthService.authenticate("EMP0002", "EMP0002", ip = "127.0.0.1") is AuthResult.Success)
        }
    }
}
