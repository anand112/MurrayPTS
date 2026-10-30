package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V3 is the first migration that rewrites production data, so it gets its own test.
 *
 * The other migration tests start from an empty file, where every `UPDATE` in V3 is a no-op and the
 * only thing proven is that the DDL parses. Here the schema is built up to version 2, legacy rows are
 * inserted the way the v1 JSON store left them — four free-text categories and three-letter codes —
 * and then the real [Migrations.migrate] runs. That is the exact path a live `data/pts.db` takes on
 * the first boot of this build, so the assertions below are about the rows people actually have.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class ProjectCategoriesMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )

    /** V1 + V2 only: the schema a database has before this build's V3 runs. */
    private fun migrateToV2(ds: HikariDataSource) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .target("2")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    /** A row in the pre-V3 shape: old label, old code, no client/start/revenue columns. */
    private fun insertLegacyProject(ds: HikariDataSource, code: String, name: String, type: String) {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                        "VALUES ('$code', '$name', 'In progress', 40, '2026-12-15', '$type', '2026-01-01', '2026-01-01')"
                )
            }
        }
    }

    private fun rows(ds: HikariDataSource): List<Triple<String, String, String>> =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT code, type, client || '|' || start_date || '|' || revenue FROM projects ORDER BY code")
                val out = mutableListOf<Triple<String, String, String>>()
                while (rs.next()) out += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                out
            }
        }

    @Test
    fun `legacy labels and three-letter codes are rewritten in place`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v3-legacy.db"))
        try {
            migrateToV2(ds)
            listOf(
                Triple("SIT-04", "Nalaikh Tailings", "Site projects"),
                Triple("MFG-01", "Erdenet Crusher", "Manufacturing"),
                Triple("RMS-02", "Darkhan Conveyor", "Repair/ Maintenance/ Other Services"),
                Triple("LAB-03", "Altai Crew Supply", "Labour supply"),
            ).forEach { (code, name, type) -> insertLegacyProject(ds, code, name, type) }

            Migrations.migrate(ds)

            assertEquals(
                listOf(
                    Triple("L-03", "Labor supply", "||0.0"),
                    Triple("M-01", "Fabrication/Manufacturing", "||0.0"),
                    Triple("R-02", "Repair/Maintenance/other services", "||0.0"),
                    Triple("S-04", "Site projects", "||0.0"),
                ),
                rows(ds),
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `the new columns arrive with neutral defaults so old rows stay valid`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v3-columns.db"))
        try {
            migrateToV2(ds)
            insertLegacyProject(ds, "SIT-01", "Tavan Tolgoi East", "Site projects")

            Migrations.migrate(ds)

            val columns = ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    val rs = st.executeQuery("PRAGMA table_info(projects)")
                    val names = mutableListOf<String>()
                    while (rs.next()) names += rs.getString("name")
                    names
                }
            }
            assertTrueAll(listOf("client", "start_date", "revenue"), columns)
            // `due` survives as the end date: the Calendar and the alerts still key off it.
            assertTrueAll(listOf("due"), columns)
            assertEquals("||0.0", rows(ds).single().third)
        } finally {
            ds.close()
        }
    }

    @Test
    fun `values that are already current are left exactly as written`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v3-passthru.db"))
        try {
            Migrations.migrate(ds) // straight to V3: a database created by this build
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate(
                        "INSERT INTO projects (code, name, status, progress, due, type, client, start_date, revenue, created_at, updated_at) " +
                            "VALUES ('E-07', 'Frontier Survey', 'In progress', 10, '2027-01-01', 'Engineering', 'ERM', '2026-09-01', 1250000.0, '2026-09-01', '2026-09-01')"
                    )
                    // A hand-edited category nobody mapped: it must show up on the board, not be guessed at.
                    st.executeUpdate(
                        "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                            "VALUES ('Z-01', 'Odd Job', 'In progress', 0, '', 'Rail siding works', '2026-09-01', '2026-09-01')"
                    )
                }
            }

            // Re-running the migration engine proves V3 is not queued twice; the rows must not move.
            Migrations.migrate(ds)

            assertEquals(
                listOf(
                    Triple("E-07", "Engineering", "ERM|2026-09-01|1250000.0"),
                    Triple("Z-01", "Rail siding works", "||0.0"),
                ),
                rows(ds),
            )
        } finally {
            ds.close()
        }
    }

    private fun assertTrueAll(expected: List<String>, actual: List<String>) {
        expected.forEach { assertTrue(it in actual, "expected column '$it' in $actual") }
    }
}
