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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The reader changing their *own* password (Stage 123).
 *
 * Settings is every account's page now, and the one write on it that is not a chip's is this one: prove
 * the current password, type the new one twice, and every session the account had is revoked. The
 * service is where those rules live, so they are read here rather than through the renderer.
 */
class OwnPasswordTest {

    private fun freshDb(dir: File): HikariDataSource {
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "own-password.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        return ds
    }

    private fun seed(empNo: String, name: String, plain: String) {
        TeamRepository.upsert(Member(employeeNumber = empNo, name = name, department = "Ops", position = "Engineer"))
        UserRepository.insert(
            employeeNumber = empNo,
            name = name,
            passwordHash = PasswordHasher.hash(plain),
            access = "Viewer",
            email = "u@murray.mn",
            positionId = null,
            isSuperAdmin = false,
            mustChangePassword = false,
        )
    }

    @Test
    fun `the checks run in the order the form reads, and a refusal writes nothing`(@TempDir dir: File) {
        freshDb(dir).use {
            seed("E500", "Bat-Erdene", "current1")
            val before = UserRepository.findByEmpNo("E500")!!.passwordHash

            // Every field is asked for first — a hand-built send can leave all three empty at once.
            assertEquals(PasswordStatus.EMPTY, UserService.changeOwnPassword("E500", "", "", ""))
            assertEquals(PasswordStatus.EMPTY, UserService.changeOwnPassword("E500", "current1", "newpass1", ""))
            // The current password has to be the one that signs in.
            assertEquals(PasswordStatus.WRONG_CURRENT, UserService.changeOwnPassword("E500", "not-it", "newpass1", "newpass1"))
            // The two new words have to agree, and be long enough.
            assertEquals(PasswordStatus.MISMATCH, UserService.changeOwnPassword("E500", "current1", "newpass1", "newpass2"))
            assertEquals(PasswordStatus.TOO_SHORT, UserService.changeOwnPassword("E500", "current1", "ab", "ab"))
            assertEquals(before, UserRepository.findByEmpNo("E500")!!.passwordHash, "a refusal changed the password")

            // An account that is not there answers as one would expect rather than throwing.
            assertEquals(PasswordStatus.USER_NOT_FOUND, UserService.changeOwnPassword("E999", "current1", "newpass1", "newpass1"))
        }
    }

    @Test
    fun `a change writes the new password and revokes every session the account had`(@TempDir dir: File) {
        freshDb(dir).use {
            seed("E510", "Ganbold", "current1")
            val login = AuthService.authenticate("E510", "current1", ip = "10.5.0.1") as AuthResult.Success
            assertTrue(AuthService.resolve(login.token) != null, "the reader was not signed in")

            assertEquals(PasswordStatus.CHANGED, UserService.changeOwnPassword("E510", "current1", "newpass1", "newpass1"))
            // The old word stops opening the account and the new one starts.
            assertTrue(AuthService.authenticate("E510", "current1", ip = "10.5.0.2") is AuthResult.Failure)
            assertTrue(AuthService.authenticate("E510", "newpass1", ip = "10.5.0.3") is AuthResult.Success)
            // The session that was open is gone: the route re-issues one for the browser doing the change.
            assertNull(AuthService.resolve(login.token), "the change left the old session alive")
        }
    }
}
