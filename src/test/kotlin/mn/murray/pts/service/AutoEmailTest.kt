package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.PasswordStatus
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.autoEmail
import mn.murray.pts.repository.UserRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The address a blank email becomes (Stage 125).
 *
 * Every create path — Access control's add card, the Settings popup, the super-admin bootstrap and the
 * legacy importer — falls through [autoEmail], so the rule lives here in one place and is read straight
 * off it, plus through [UserService.addUser] to prove the stored row really carries it.
 */
class AutoEmailTest {

    private fun freshDb(dir: File): HikariDataSource {
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "auto-email.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        return ds
    }

    @Test
    fun `a two-word name reads firstname dot surname at the company domain`() {
        assertEquals("tuyaa.jamsran@murrays.mn", autoEmail("Tuyaa Jamsran"))
        assertEquals("tuyaa.jamsran@murrays.mn", autoEmail("  TUYAA   Jamsran  "), "case and spacing were not normalised")
    }

    @Test
    fun `a middle word is dropped and the last word stands as the surname`() {
        assertEquals(
            "otgonbayar.bilegsaikhan@murrays.mn",
            autoEmail("Otgonbayar Lkhagvadorj Bilegsaikhan"),
            "the old rule joined every word; the ask is firstname.surname",
        )
    }

    @Test
    fun `a one-word name keeps that single word`() {
        assertEquals("nyambaapurev@murrays.mn", autoEmail("Nyambaapurev"))
    }

    @Test
    fun `a hyphen survives inside a word and punctuation splits words`() {
        assertEquals("bat-erdene.tudev@murrays.mn", autoEmail("Bat-Erdene Tudev"))
        assertEquals("n.enkhtuyaa@murrays.mn", autoEmail("N. Enkhtuyaa"))
    }

    @Test
    fun `a name with no words at all yields no address`() {
        assertEquals("", autoEmail("   "))
        assertEquals("", autoEmail("..."), "punctuation is a separator, not a word")
    }

    @Test
    fun `adding an account with a blank email files the generated address`(@TempDir dir: File) {
        freshDb(dir).use {
            assertEquals(
                PasswordStatus.USER_ADDED,
                UserService.addUser(
                    employeeNumber = "EMP0501",
                    name = "Tuyaa Jamsran",
                    email = "",
                    requestedPosition = null,
                    requestedRole = null,
                    newPassword = "",
                    canSetRoles = false,
                ),
            )
            assertEquals("tuyaa.jamsran@murrays.mn", UserRepository.findByEmpNo("EMP0501")!!.email)
        }
    }

    @Test
    fun `a typed address is kept and lowercased, not overwritten by the rule`(@TempDir dir: File) {
        freshDb(dir).use {
            UserService.addUser(
                employeeNumber = "EMP0502",
                name = "Ganbold Erdene",
                email = "  Ganbold.E@Murrays.MN ",
                requestedPosition = null,
                requestedRole = null,
                newPassword = "",
                canSetRoles = false,
            )
            assertEquals("ganbold.e@murrays.mn", UserRepository.findByEmpNo("EMP0502")!!.email)
        }
    }

    @Test
    fun `blanking a stored address regenerates it from the name`(@TempDir dir: File) {
        freshDb(dir).use {
            UserService.addUser(
                employeeNumber = "EMP0503",
                name = "Saren Dulamjav",
                email = "somewhere@example.com",
                requestedPosition = null,
                requestedRole = null,
                newPassword = "",
                canSetRoles = false,
            )
            UserService.updateEmail("EMP0503", "")
            assertEquals("saren.dulamjav@murrays.mn", UserRepository.findByEmpNo("EMP0503")!!.email)
        }
    }
}
