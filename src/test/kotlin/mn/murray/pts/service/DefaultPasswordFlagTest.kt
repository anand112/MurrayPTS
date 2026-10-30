package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.PasswordStatus
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.PasswordHasher
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The `must_change_password` flag as it stands after Stage 99: an account whose password the system
 * assigned is still recorded as such, an administrator's new password clears the record, and nothing
 * else depends on it. The route gate and the Settings password box that used to read it are gone, so a
 * flagged account signs in and works exactly like any other — there is no state it is waiting in.
 */
class DefaultPasswordFlagTest {

    private fun freshDb(dir: File): HikariDataSource {
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "default-password.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        return ds
    }

    private fun seed(empNo: String, name: String, plain: String, mustChange: Boolean) {
        TeamRepository.upsert(Member(employeeNumber = empNo, name = name, department = "Ops", position = "Viewer"))
        UserRepository.insert(
            employeeNumber = empNo,
            name = name,
            passwordHash = PasswordHasher.hash(plain),
            access = "Viewer",
            email = "u@murray.mn",
            positionId = null,
            isSuperAdmin = false,
            mustChangePassword = mustChange,
        )
    }

    @Test
    fun `the flag is readable per account and false for a normal one`(@TempDir dir: File) {
        freshDb(dir).use {
            seed("E400", "Flagged", "EMP400", mustChange = true)
            seed("E401", "Normal", "chosen", mustChange = false)
            assertTrue(AuthService.mustChangePassword("E400"))
            assertFalse(AuthService.mustChangePassword("E401"))
            // Unknown accounts read as "not flagged" rather than throwing.
            assertFalse(AuthService.mustChangePassword("E999"))
        }
    }

    @Test
    fun `a flagged account signs in like any other until an administrator sets a password`(@TempDir dir: File) {
        freshDb(dir).use {
            seed("E410", "Flagged", "EMP410", mustChange = true)
            assertTrue(AuthService.mustChangePassword("E410"))

            // Stage 99: the flag no longer walls the account off — the sign-in succeeds and the cookie
            // resolves, which is what the retired route gate used to refuse.
            val login = AuthService.authenticate("E410", "EMP410", ip = "10.3.0.1") as AuthResult.Success
            assertNotNull(AuthService.resolve(login.token), "a flagged account's session must be usable")

            // Nothing clears the record on its own; a new password typed by an administrator does, and
            // that write still revokes the sessions it found.
            assertEquals(PasswordStatus.CHANGED, UserService.setPasswordByAdmin("E410", "brand-new"))
            assertFalse(AuthService.mustChangePassword("E410"))
            assertNull(AuthService.resolve(login.token), "the reset revokes the sessions it found")
            assertTrue(AuthService.authenticate("E410", "brand-new", ip = "10.3.0.2") is AuthResult.Success)
        }
    }
}
