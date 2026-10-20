package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V13 adds `app_settings`, the tiny key/value store behind the in-app master switch for outbound mail
 * (Stage 62): the one decision the app owns across a restart rather than the operator's `data/.env`.
 *
 * The two promises this kind of migration is judged on: the table arrives **empty**, because a switch with no
 * stored row reads as OFF (see [mn.murray.pts.service.MailSwitch.enabled]) and the system must not start
 * emailing people because a migration ran; and it is **not replayed** on the next boot, so a value a super
 * admin set stays set rather than being reset or doubled.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class AppSettingsMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )

    private fun query(ds: HikariDataSource, sql: String): List<List<String?>> =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery(sql)
                val width = rs.metaData.columnCount
                val out = mutableListOf<List<String?>>()
                while (rs.next()) out += (1..width).map { rs.getString(it) }
                out
            }
        }

    @Test
    fun `V13 creates the empty two-column store`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v13-table.db"))
        try {
            Migrations.migrate(ds)
            val columns = query(ds, "SELECT name FROM pragma_table_info('app_settings')").map { it.single()!! }
            assertEquals(listOf("key", "value"), columns, "the store's columns are not the two V13 defines")
            assertEquals(
                0, query(ds, "SELECT count(*) FROM app_settings").single().single()!!.toInt(),
                "V13 seeded a switch the migration has no way to know the answer to",
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a value an admin set survives the next boot`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v13-reboot.db"))
        try {
            Migrations.migrate(ds)
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("INSERT INTO app_settings (key, value) VALUES ('mail_send_enabled', 'true')")
                }
            }
            Migrations.migrate(ds) // a second boot: the versioned migration does not run again
            assertEquals(
                1, query(ds, "SELECT count(*) FROM app_settings").single().single()!!.toInt(),
                "a reboot replayed V13 and reset or doubled the switch",
            )
            assertEquals("true", query(ds, "SELECT value FROM app_settings WHERE key = 'mail_send_enabled'").single().single())
        } finally {
            ds.close()
        }
    }
}
