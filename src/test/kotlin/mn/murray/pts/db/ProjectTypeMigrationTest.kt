package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.domain.SHIPPED_PROJECT_TYPES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V14 adds `project_types`, the catalogue Settings' *Project types* panel edits (Stage 63): the nine
 * categories the app shipped with, moved out of a compile-time list and into a table.
 *
 * Unlike V12 and V13 — which arrive **empty** on purpose — this one has to seed. A catalogue with no row is
 * a create-project form with no category to pick and nine project-code letters nobody holds, which would
 * break the app the moment the migration ran. So the promise this test pins is the opposite one: the nine
 * arrive, in the shipped order, and a second boot neither replays them nor doubles them, so a category
 * management renamed or added across a restart is still there.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class ProjectTypeMigrationTest {

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
    fun `V14 seeds the nine shipped categories in board order`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v14-table.db"))
        try {
            Migrations.migrate(ds)
            val columns = query(ds, "SELECT name FROM pragma_table_info('project_types')").map { it.single()!! }
            assertEquals(listOf("code", "name", "position"), columns, "the catalogue's columns are not the three V14 defines")
            val rows = query(ds, "SELECT code, name, position FROM project_types ORDER BY position")
            assertEquals(SHIPPED_PROJECT_TYPES.map { it.code }, rows.map { it[0] }, "the seeded letters are not the shipped nine")
            assertEquals(SHIPPED_PROJECT_TYPES.map { it.name }, rows.map { it[1] }, "a seeded label is not the one the app shipped")
            assertEquals((0..8).map { it.toString() }, rows.map { it[2] }, "the seeded positions are not the picker order")
            // The letter is the key, and it is what two categories may not share — the whole point of the table.
            assertEquals(
                9,
                query(ds, "SELECT count(DISTINCT code) FROM project_types").single().single()!!.toInt(),
                "the catalogue's key does not make a letter unique",
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a category management changed survives the next boot unchanged`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v14-reboot.db"))
        try {
            Migrations.migrate(ds)
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("UPDATE project_types SET name = 'Site works' WHERE code = 'S'")
                    st.executeUpdate("INSERT INTO project_types (code, name, position) VALUES ('P', 'Parts supply', 9)")
                }
            }
            Migrations.migrate(ds) // a second boot: the versioned migration does not run again
            assertEquals(10, query(ds, "SELECT count(*) FROM project_types").single().single()!!.toInt(), "V14 replayed and doubled the catalogue")
            assertEquals(
                listOf("Site works"),
                query(ds, "SELECT name FROM project_types WHERE code = 'S'").map { it.single()!! },
                "a reboot reset a renamed category",
            )
            assertTrue(
                query(ds, "SELECT name FROM project_types WHERE code = 'P'").map { it.single()!! }.contains("Parts supply"),
                "a reboot lost a category management added",
            )
        } finally {
            ds.close()
        }
    }
}
