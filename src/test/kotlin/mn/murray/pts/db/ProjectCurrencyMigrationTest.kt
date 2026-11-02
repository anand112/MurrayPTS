package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.domain.Currency
import mn.murray.pts.repository.ProjectRepository
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V20 gives every project a currency, and the rows written before it are tugrik.
 *
 * Until Stage 108 every amount in the app was tugrik, so the sign on revenue was a rule about the app rather
 * than a fact about the row. The user then asked for USD and CNY to be choosable — with tugrik the default —
 * which moved the fact onto the project. As in [ProjectStatusMigrationTest], what a data migration has to get
 * right is the rows that already exist, so each case builds V1–V19, writes projects and a ledger line the way a
 * live database holds them, and then runs the real [Migrations.migrate]. Three promises are pinned here:
 *
 *  - every project already on a board is `MNT` afterwards, because that is the unit its figures were always
 *    written in — the column's default is what says so, and nothing had to be converted;
 *  - a row written *without* naming a currency still lands in tugrik, which pins the `DEFAULT` on the column
 *    rather than the backfill of yesterday's rows;
 *  - nothing else moves: revenue, invoiced, `updated_at` and the forecast ledger are exactly as written, and a
 *    currency code the app cannot name survives as written — the board reads it as tugrik instead
 *    ([mn.murray.pts.domain.Currency.of] answers null and the repository falls back), which is the same
 *    tolerance V1 gave `projects.status`.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class ProjectCurrencyMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )

    /** V1–[version] only: the schema a database has before V20 runs. */
    private fun migrateTo(ds: HikariDataSource, version: String) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .target(version)
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    private fun update(ds: HikariDataSource, sql: String) {
        ds.connection.use { conn -> conn.createStatement().use { it.executeUpdate(sql) } }
    }

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

    /** A project row in the shape a V19 database holds one, with the figures the app wrote for it. */
    private fun insertProject(
        ds: HikariDataSource,
        code: String,
        name: String,
        revenue: Double,
        invoiced: Double,
        stamp: String,
    ) {
        update(
            ds,
            "INSERT INTO projects (code, name, status, progress, due, type, client, revenue, invoiced, " +
                "created_at, updated_at) VALUES ('$code', '$name', 'In progress', 40, '2027-03-30', " +
                "'Site projects', 'Oyu Tolgoi LLC', $revenue, $invoiced, '2026-02-02T02:02:02Z', '$stamp')",
        )
    }

    private fun insertLedgerLine(ds: HikariDataSource, code: String, month: String, forecast: Double, invoiced: Double) {
        update(
            ds,
            "INSERT INTO project_forecasts (project_id, month, forecast_amount, invoiced_amount, invoiced_month, invoice_status) " +
                "SELECT id, '$month', $forecast, $invoiced, '2026-10', 'Partially invoiced' FROM projects WHERE code = '$code'",
        )
    }

    @Test
    fun `every project already on a board is counted in tugrik after the migration`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v19-live.db"))
        try {
            migrateTo(ds, "19")
            insertProject(ds, "S-01", "Haul road widening", 12_500_000.0, 3_000_000.0, "2026-05-05T05:05:05Z")
            insertProject(ds, "M-01", "Crusher liner supply", 4_000_000.0, 0.0, "2026-06-06T06:06:06Z")
            val before = query(ds, "SELECT code, revenue, invoiced, updated_at FROM projects ORDER BY code")
            Migrations.migrate(ds)

            // The default is the unit every figure on those rows was always written in, so this is a label, not
            // a conversion: nothing about the amounts changed when the column appeared.
            assertEquals(
                listOf("MNT", "MNT"),
                query(ds, "SELECT currency FROM projects ORDER BY code").map { it.single() },
                "a project that existed before V20 is not tugrik",
            )
            assertEquals(
                before,
                query(ds, "SELECT code, revenue, invoiced, updated_at FROM projects ORDER BY code"),
                "the migration rewrote a row's figures or its stamp",
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a row written without a currency is tugrik, and the forecast ledger is untouched`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v19-default.db"))
        try {
            migrateTo(ds, "19")
            insertProject(ds, "S-02", "Berms at the laydown", 2_000_000.0, 0.0, "2026-07-07T07:07:07Z")
            insertLedgerLine(ds, "S-02", "2026-10", 500_000.0, 250_000.0)
            Migrations.migrate(ds)

            // V1's report importer and any hand-run INSERT know nothing of a currency; the column's own DEFAULT
            // is what keeps them from writing a board that renders with no sign at all.
            update(
                ds,
                "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                    "VALUES ('S-03', 'Weighbridge repair', 'In progress', 0, '', 'Site projects', 'x', 'x')",
            )
            assertEquals(
                "MNT",
                query(ds, "SELECT currency FROM projects WHERE code = 'S-03'").single().single(),
                "a project inserted without a currency did not land in tugrik",
            )
            // The ledger is per project and inherits that project's money when the sheet renders; its own rows
            // gained no column and lost no amount.
            assertEquals(
                listOf(listOf<String?>("2026-10", "500000.0", "250000.0")),
                query(ds, "SELECT month, forecast_amount, invoiced_amount FROM project_forecasts"),
                "the migration wrote to the forecast ledger",
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a code the app cannot name survives as written and reads as tugrik`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v19-unknown.db"))
        try {
            migrateTo(ds, "19")
            insertProject(ds, "S-04", "Stockpile drainage", 1_500_000.0, 0.0, "2026-08-08T08:08:08Z")
            Migrations.migrate(ds)
            // There is deliberately no CHECK on the column: a hand-edited row is not this migration's business.
            update(ds, "UPDATE projects SET currency = 'EUR' WHERE code = 'S-04'")
            assertEquals("EUR", query(ds, "SELECT currency FROM projects WHERE code = 'S-04'").single().single())

            // The board, though, has one answer to a money it cannot sign: tugrik. [Currency.of] answers null
            // and the repository falls back to the default rather than printing a figure with no unit.
            Database.connect(ds)
            val stored = ProjectRepository.findAll().single()
            assertEquals(Currency.MNT, stored.currency, "an unknown currency code reached the page")
            assertEquals(1_500_000.0, stored.revenue, "the unknown code cost the row its amount")
        } finally {
            ds.close()
        }
    }
}
