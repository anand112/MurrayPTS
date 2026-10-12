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
 * The `must_change_password` flag and the lifecycle the launch gate depends on: an account
 * starts flagged, only setting a real password clears it, and every path that clears it also
 * revokes the sessions so an old cookie cannot keep a flagged account alive.
 */
class MustChangePasswordTest {

    private fun freshDb(dir: File): HikariDataSource {
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "must.db").absolutePath}"
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
    fun `a self-service password change clears the flag and leaves a usable session`(@TempDir dir: File) {
        freshDb(dir).use {
            seed("E410", "Self", "EMP410", mustChange = true)
            val login = AuthService.authenticate("E410", "EMP410", ip = "10.3.0.1") as AuthResult.Success
            assertTrue(AuthService.mustChangePassword("E410"))

            val status = UserService.changeOwnPassword("E410", current = "EMP410", new = "brand-new", confirm = "brand-new")
            assertEquals(PasswordStatus.CHANGED, status)

            assertFalse(AuthService.mustChangePassword("E410"), "flag must clear so the gate opens")
            // changeOwnPassword revokes every session, including the one used to change it...
            assertNull(AuthService.resolve(login.token), "the pre-change cookie must be dead")
            // ...so the route re-issues one; that fresh token resolves.
            assertNotNull(AuthService.resolve(AuthService.reissue("E410", ip = "10.3.0.1")))
            assertTrue(AuthService.authenticate("E410", "brand-new", ip = "10.3.0.2") is AuthResult.Success)
        }
    }

    @Test
    fun `a wrong current password leaves the account gated`(@TempDir dir: File) {
        freshDb(dir).use {
            seed("E420", "Wrong", "EMP420", mustChange = true)
            val status = UserService.changeOwnPassword("E420", current = "guess", new = "newpw", confirm = "newpw")
            assertEquals(PasswordStatus.WRONG_CURRENT, status)
            assertTrue(AuthService.mustChangePassword("E420"), "a failed attempt must not open the gate")
        }
    }

    @Test
    fun `an admin reset clears the flag too`(@TempDir dir: File) {
        freshDb(dir).use {
            seed("E430", "AdminSet", "EMP430", mustChange = true)
            assertEquals(PasswordStatus.CHANGED, UserService.setPasswordByAdmin("E430", "given-pw"))
            assertFalse(AuthService.mustChangePassword("E430"))
            assertTrue(AuthService.authenticate("E430", "given-pw", ip = "10.4.0.1") is AuthResult.Success)
        }
    }
}
