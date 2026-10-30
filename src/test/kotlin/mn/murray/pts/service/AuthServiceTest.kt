package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.PasswordHasher
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end check of the v2 auth path against a real SQLite file: bcrypt verification,
 * DB-backed session issue/resolve/revoke, and session revocation on password change.
 */
class AuthServiceTest {

    private fun freshDb(dir: File): HikariDataSource {
        val dbFile = File(dir, "auth.db")
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

    private fun seedUser(empNo: String, name: String, plain: String) {
        TeamRepository.upsert(Member(employeeNumber = empNo, name = name, department = "Ops", position = "Manager"))
        UserRepository.insert(
            employeeNumber = empNo,
            name = name,
            passwordHash = PasswordHasher.hash(plain),
            access = "Manager",
            email = "a@murray.mn",
            positionId = null,
            isSuperAdmin = false,
            mustChangePassword = false,
        )
    }

    @Test
    fun `authenticates with bcrypt, issues a resolvable session, and rejects bad credentials`(@TempDir dir: File) {
        freshDb(dir).use {
            seedUser("E100", "Alice", "secret")

            // Correct password (case-insensitive employee number) -> Success + token.
            val ok = AuthService.authenticate("e100", "secret", ip = "10.0.0.1")
            assertTrue(ok is AuthResult.Success, "expected success, got $ok")
            val token = (ok as AuthResult.Success).token
            assertTrue(token.isNotBlank())
            assertEquals("E100", ok.user.employeeNumber)

            // The token resolves to a live DB session.
            val session = AuthService.resolve(token)
            assertNotNull(session)
            assertEquals("E100", session!!.employeeNumber)

            // Wrong password and unknown account both fail generically (distinct IPs avoid throttle).
            assertTrue(AuthService.authenticate("E100", "nope", ip = "10.0.0.2") is AuthResult.Failure)
            assertTrue(AuthService.authenticate("ZZZZ", "x", ip = "10.0.0.3") is AuthResult.Failure)

            // Logout revokes the session so the cookie no longer resolves.
            AuthService.logout(token)
            assertNull(AuthService.resolve(token))
        }
    }

    @Test
    fun `username letter case and surrounding spaces do not change login but password case does`(@TempDir dir: File) {
        freshDb(dir).use {
            seedUser("EMP0100", "Case Test", "MixedPass42!")
            listOf("emp0100", "EmP0100", "EMP0100", "  emp0100  ").forEachIndexed { index, typed ->
                val result = AuthService.authenticate(typed, "MixedPass42!", ip = "10.3.0.${index + 1}")
                assertTrue(result is AuthResult.Success, "username spelling $typed was refused")
                val success = result as AuthResult.Success
                assertEquals("EMP0100", success.user.employeeNumber)
                assertEquals("EMP0100", AuthService.resolve(success.token)?.employeeNumber)
            }
            assertTrue(
                AuthService.authenticate("emp0100", "mixedpass42!", ip = "10.3.1.1") is AuthResult.Failure,
                "lowercasing the username must not lowercase the password",
            )
        }
    }

    @Test
    fun `changing the password revokes existing sessions`(@TempDir dir: File) {
        freshDb(dir).use {
            seedUser("E200", "Bob", "first")
            val login = AuthService.authenticate("E200", "first", ip = "10.1.0.1") as AuthResult.Success
            assertNotNull(AuthService.resolve(login.token))

            AuthService.changePassword("E200", "second")

            // Old session is dead; the new password works.
            assertNull(AuthService.resolve(login.token))
            val again = AuthService.authenticate("E200", "second", ip = "10.1.0.2")
            assertTrue(again is AuthResult.Success)
        }
    }

    @Test
    fun `expired sessions do not resolve`(@TempDir dir: File) {
        freshDb(dir).use {
            seedUser("E300", "Cara", "pw")
            // ttl of 0 hours -> expires immediately.
            val token = mn.murray.pts.repository.SessionRepository.create("E300", ttlHours = 0, ip = "10.2.0.1")
            // Give the clock a moment so expiresAt is strictly in the past.
            Thread.sleep(1100)
            assertNull(AuthService.resolve(token))
        }
    }
}
