package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.repository.AppSettingRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The rules behind the Stage 62 master switch for outbound mail.
 *
 * This is the object [mn.murray.pts.service.Mailer] asks before it opens a connection, so the promise worth
 * pinning is the one the whole feature rests on: an unset switch is **off**, only a super admin can move it,
 * and a value that is not exactly `"true"` never reads as on. The right to flip it is checked inside the
 * service and again at the route (the project's "layout, not rights" convention), so a refusal here writes
 * nothing at all.
 *
 * Each test gets its own temporary SQLite file — nothing here touches `data/`.
 */
class MailSwitchTest {

    private lateinit var ds: HikariDataSource

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "mail-switch.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )
        Migrations.migrate(ds)
        Database.connect(ds)
    }

    @AfterEach
    fun tearDown() = ds.close()

    @Test
    fun `an unset switch reads as off and writes nothing`() {
        assertFalse(MailSwitch.enabled(), "a fresh install with no stored row must not be able to send")
        assertNull(AppSettingRepository.get(MailSwitch.KEY), "unset should read as an absent key, not a stored false")
    }

    @Test
    fun `only an admin can turn sending on, and the value round-trips`() {
        assertFalse(MailSwitch.setEnabled(on = true, canAdmin = false), "a non-admin turned the system mailer on")
        assertFalse(MailSwitch.enabled(), "a refused enable still wrote the key")

        assertTrue(MailSwitch.setEnabled(on = true, canAdmin = true))
        assertTrue(MailSwitch.enabled(), "an admin's enable did not stick")

        assertTrue(MailSwitch.setEnabled(on = false, canAdmin = true))
        assertFalse(MailSwitch.enabled(), "an admin's disable did not stick")
    }

    @Test
    fun `only the exact word true reads as on`() {
        // A hand-written row is not a way to quietly enable sending: "TRUE", "1" and "on" all read as off.
        listOf("TRUE", "1", "on", "yes").forEach {
            AppSettingRepository.put(MailSwitch.KEY, it)
            assertFalse(MailSwitch.enabled(), "\"$it\" is not the stored value that means on")
        }
        AppSettingRepository.put(MailSwitch.KEY, "true")
        assertTrue(MailSwitch.enabled())
    }
}
