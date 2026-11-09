package mn.murray.pts.db.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.PasswordStatus
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.AppSettingRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.service.UserService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The one pass that puts accounts written before Stage 125 under the Stage 125 address rule (Stage 126).
 *
 * [UserEmailBackfill.applyOnce] recomputes each stored address through the same
 * [mn.murray.pts.domain.autoEmail] the create paths call, so these tests are as much about the two agreeing as
 * about the rewrite: an account the pass leaves alone has to be an account the rule already matched.
 */
class UserEmailBackfillTest {

    private fun freshDb(dir: File): HikariDataSource {
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "email-backfill.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        return ds
    }

    private fun add(employeeNumber: String, name: String, email: String) {
        assertEquals(
            PasswordStatus.USER_ADDED,
            UserService.addUser(
                employeeNumber = employeeNumber,
                name = name,
                email = email,
                requestedPosition = null,
                requestedRole = null,
                newPassword = "",
                canSetRoles = false,
            ),
            "$employeeNumber was not created",
        )
    }

    @Test
    fun `an account on the old rule is rewritten to firstname dot surname at the company domain`(@TempDir dir: File) {
        freshDb(dir).use {
            add("EMP0701", "Otgonbayar Lkhagvadorj Bilegsaikhan", "otgonbayar.lkhagvadorj.bilegsaikhan@murray.mn")
            add("EMP0702", "Tuyaa Jamsran", "tuyaa.jamsran@murray.mn")

            assertEquals(2, UserEmailBackfill.applyOnce(), "both accounts read differently from the rule")
            assertEquals("otgonbayar.bilegsaikhan@murrays.mn", UserRepository.findByEmpNo("EMP0701")!!.email)
            assertEquals("tuyaa.jamsran@murrays.mn", UserRepository.findByEmpNo("EMP0702")!!.email)
            assertEquals(UserEmailBackfill.RULE_VERSION, AppSettingRepository.get(UserEmailBackfill.RULE_KEY))
        }
    }

    @Test
    fun `the pass runs once, so an address typed afterwards stays typed`(@TempDir dir: File) {
        freshDb(dir).use {
            add("EMP0703", "Ganbold Erdene", "ganbold.erdene@murray.mn")
            assertEquals(1, UserEmailBackfill.applyOnce())
            assertEquals("ganbold.erdene@murrays.mn", UserRepository.findByEmpNo("EMP0703")!!.email)

            UserService.updateEmail("EMP0703", "g.erdene@example.com")
            assertEquals(0, UserEmailBackfill.applyOnce(), "the marker says this database has been put through the rule")
            assertEquals("g.erdene@example.com", UserRepository.findByEmpNo("EMP0703")!!.email)
        }
    }

    @Test
    fun `an account the rule already matches is left untouched`(@TempDir dir: File) {
        freshDb(dir).use {
            add("EMP0704", "Saren Dulamjav", "")
            assertNotNull(UserRepository.findByEmpNo("EMP0704"))

            assertEquals(0, UserEmailBackfill.applyOnce(), "a row that already reads as the rule produces is not a rewrite")
            assertEquals("saren.dulamjav@murrays.mn", UserRepository.findByEmpNo("EMP0704")!!.email)
        }
    }

    @Test
    fun `a name with no word in it never has its address blanked`(@TempDir dir: File) {
        freshDb(dir).use {
            TeamRepository.upsert(Member("EMP0705", "...", "IT / Systems", "Tester"))
            UserRepository.insert(
                employeeNumber = "EMP0705",
                name = "...",
                passwordHash = "x",
                access = "Employee",
                email = "kept@example.com",
                positionId = null,
                isSuperAdmin = false,
                mustChangePassword = false,
            )

            UserEmailBackfill.applyOnce()
            assertEquals("kept@example.com", UserRepository.findByEmpNo("EMP0705")!!.email)
        }
    }
}
