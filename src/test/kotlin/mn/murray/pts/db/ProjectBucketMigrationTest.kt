package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V7 gives every project its own board lanes, so it is the first migration that writes rows into a
 * brand-new table for records that already exist, and V10 and V16 are the two that change the lanes a
 * project already has.
 *
 * A project created by this build gets its three opening lanes from [mn.murray.pts.service.ProjectService.create],
 * which no migration test exercises — what a migration has to get right is the projects that were made
 * *before* it ran. So each test here builds the schema up to the version underneath the one it is about,
 * inserts projects and cards the way a live database holds them, and then runs the real
 * [Migrations.migrate]. The assertions are about those rows: the lanes a person already has work on, in
 * the order the board will draw them, and the cards that belong to none of them yet.
 *
 * V16's promises are the ones a migration can most easily break, so they are pinned by name rather than by
 * count: every project that already exists ends up with Engineering, Procurement and Production in that
 * order — *seed all of them* — and nothing it held a card in is dropped or duplicated. A lane that holds
 * work cannot go quietly, because `tasks.project_bucket_id` answers `ON DELETE SET NULL` — deleting the
 * column unfiles the cards — and a lane whose name one of the three now claims has to keep its own row,
 * because that row is what its cards point at.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class ProjectBucketMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                // The cascades and the SET NULL below are only real when the connection enforces them,
                // which is exactly how the application opens its own connections.
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )

    /** V1–[version] only: the schema a database has before the migrations above it run. */
    private fun migrateTo(ds: HikariDataSource, version: String) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .target(version)
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    /** V1–V6: the schema and the rows a database holds before this build's V7…V16 run. */
    private fun migrateToV6(ds: HikariDataSource) = migrateTo(ds, "6")

    private fun insertProject(ds: HikariDataSource, code: String, name: String) {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO projects (code, name, status, progress, due, type, created_at, updated_at) " +
                        "VALUES ('$code', '$name', 'In progress', 0, '2027-03-30', 'Site projects', '2026-01-01', '2026-01-01')"
                )
            }
        }
    }

    /** A card in the pre-V7 shape: a status, a project, no lane. */
    private fun insertCard(ds: HikariDataSource, title: String, projectId: Int) {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO tasks (kind, title, project_id, due, priority, status) " +
                        "VALUES ('ASSIGNED', '$title', $projectId, '2026-11-12', 'High', 'In progress')"
                )
            }
        }
    }

    private fun projectId(ds: HikariDataSource, code: String): Int =
        query(ds, "SELECT id FROM projects WHERE code = '$code'").single().single()!!.toInt()

    /** Rows from a one- or two-column SELECT, as strings, in the order the database gives them. */
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

    /** A project's lanes as the board reads them: name by position. */
    private fun lanesOf(ds: HikariDataSource, projectId: Int) =
        query(
            ds,
            "SELECT name FROM project_buckets WHERE project_id = $projectId ORDER BY position",
        ).map { it.single()!! }

    /** The same rows by id, for proving two boards were given two sets of lanes. */
    private fun laneIds(ds: HikariDataSource, projectId: Int) =
        query(
            ds,
            "SELECT id FROM project_buckets WHERE project_id = $projectId ORDER BY id",
        ).map { it.single()!!.toInt() }.toSet()

    /** One lane's id by name, the way the board's own forms address it. */
    private fun laneId(ds: HikariDataSource, projectId: Int, name: String): Int =
        query(ds, "SELECT id FROM project_buckets WHERE project_id = $projectId AND name = '$name'")
            .single().single()!!.toInt()

    /** A lane a manager added to a board before the re-cut, numbered the way the append numbered it. */
    private fun addLane(ds: HikariDataSource, projectId: Int, name: String, position: Int) {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO project_buckets (project_id, name, position) VALUES ($projectId, '$name', $position)",
                )
            }
        }
    }

    /** File [title] in the lane [name] is filed under, the way a card that predates its lane was filed. */
    private fun fileCard(ds: HikariDataSource, title: String, projectId: Int, laneId: Int) {
        insertCard(ds, title, projectId)
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("UPDATE tasks SET project_bucket_id = $laneId WHERE title = '$title'")
            }
        }
    }

    @Test
    fun `every project that already exists is given the three phases, in the order the board draws them`(
        @TempDir dir: File,
    ) {
        val ds = poolFor(File(dir, "v16-seed.db"))
        try {
            migrateToV6(ds)
            insertProject(ds, "S-01", "Haul road widening")
            insertProject(ds, "M-02", "Crusher liner supply")

            Migrations.migrate(ds)

            val expected = DEFAULT_PROJECT_BUCKETS
            val first = projectId(ds, "S-01")
            val second = projectId(ds, "M-02")
            assertEquals(expected, lanesOf(ds, first))
            assertEquals(expected, lanesOf(ds, second))
            // V10's six sub-categories arrived on these boards on the way and left again: every one of them
            // stood empty here, so V16 could take them off without unfiling anything, and the three came in
            // their place rather than beside them.
            val retired = listOf(
                "Fabrication/Manufacturing", "Repair/Maintenance", "Site projects", "Labor supply",
                "Goods sales", "Other",
            )
            assertFalse(lanesOf(ds, first).any { it in retired }, "an empty sub-category is still on the board")
            // The positions are the order the columns are drawn in, so a migration that numbered them
            // arbitrarily would put *Production* in front of *Engineering* on every existing project page —
            // and the picker lists them in this same order.
            assertEquals(
                listOf("0", "1", "2"),
                query(ds, "SELECT position FROM project_buckets WHERE project_id = $first ORDER BY position")
                    .map { it.single()!! },
            )
            // …and two separate sets of rows, not one shared list pointed at twice: a lane belongs to one
            // board, which is the whole reason this table carries a project id.
            assertEquals(6, query(ds, "SELECT count(*) FROM project_buckets").single().single()!!.toInt())
            assertTrue(laneIds(ds, first).intersect(laneIds(ds, second)).isEmpty(), "two projects share lane rows")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a lane that holds a card under one of the three names keeps its row, and is not given a twin`(
        @TempDir dir: File,
    ) {
        val ds = poolFor(File(dir, "v16-held.db"))
        try {
            // The project and its lanes are both older than V16, and the card is filed in one of them the way
            // a live board holds it: after V7 seeded the three phases, through V10's decision that those names
            // were not sub-categories, and up to V16 putting them back.
            migrateToV6(ds)
            insertProject(ds, "S-06", "Pump station refit")
            migrateTo(ds, "7")
            val projectId = projectId(ds, "S-06")
            val production = laneId(ds, projectId, "Production")
            fileCard(ds, "Re-seal the pump", projectId, production)

            Migrations.migrate(ds)

            // The board reads as the three, and the *Production* column is the original row: V16's insert skips
            // a name the board already carries, so a card is never left pointing at a column while the picker
            // offers a second one of the same name.
            assertEquals(DEFAULT_PROJECT_BUCKETS, lanesOf(ds, projectId))
            assertEquals(3, query(ds, "SELECT count(*) FROM project_buckets WHERE project_id = $projectId")
                .single().single()!!.toInt())
            assertEquals(listOf(production.toString()),
                query(ds, "SELECT project_bucket_id FROM tasks WHERE title = 'Re-seal the pump'").map { it.single() })
            assertEquals("Production",
                query(ds, "SELECT name FROM project_buckets WHERE id = $production").single().single())
            // And the number one of the three occupies is 0..2 like its two neighbours, not the 6 V10 gave it:
            // renumbering a lane that holds work is safe, because the id it points at is untouched.
            assertEquals("2", query(ds, "SELECT position FROM project_buckets WHERE id = $production")
                .single().single())
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a retired sub-category that holds work survives as an ordinary lane after the three`(
        @TempDir dir: File,
    ) {
        val ds = poolFor(File(dir, "v16-legacy.db"))
        try {
            // The case the decision "never destroy a card's filing" is about: a board that has work filed under
            // one of V10's six names. V16 does not refile it into a phase — which phase a piece of work belongs
            // to is a guess — and it does not drop the column, because that would set the card's lane to NULL.
            // The lane simply stops being special: it is the fourth column, editable like any other.
            migrateToV6(ds)
            insertProject(ds, "S-08", "Conveyor rebuild")
            migrateTo(ds, "10")
            val projectId = projectId(ds, "S-08")
            val goods = laneId(ds, projectId, "Goods sales")
            fileCard(ds, "Order the idler set", projectId, goods)

            Migrations.migrate(ds)

            assertEquals(DEFAULT_PROJECT_BUCKETS + "Goods sales", lanesOf(ds, projectId))
            assertEquals(listOf(goods.toString()),
                query(ds, "SELECT project_bucket_id FROM tasks WHERE title = 'Order the idler set'").map { it.single() })
            // The five sub-categories nothing was filed in left the board, which is the other half of the rule.
            assertEquals(4, query(ds, "SELECT count(*) FROM project_buckets WHERE project_id = $projectId")
                .single().single()!!.toInt())
            assertFalse(
                lanesOf(ds, projectId).any { it in listOf("Fabrication/Manufacturing", "Repair/Maintenance", "Other") },
                "an empty retired name is still standing beside the three",
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a lane the project added for itself stands on after the three`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v16-extra.db"))
        try {
            migrateToV6(ds)
            insertProject(ds, "S-07", "Batch plant upgrade")
            migrateTo(ds, "7")
            val projectId = projectId(ds, "S-07")
            // Two columns this project's manager named for itself, appended after V7's three defaults.
            addLane(ds, projectId, "Tendering", 3)
            addLane(ds, projectId, "Surveys", 4)

            Migrations.migrate(ds)

            // The three first, in the picker's order, then this board's own columns in the order they were
            // added — the arrangement the ask needs, and the one a board with a lane of its own has to keep or
            // the manager's work disappears off the end of the row.
            assertEquals(DEFAULT_PROJECT_BUCKETS + listOf("Tendering", "Surveys"), lanesOf(ds, projectId))
            assertEquals(listOf("3", "4"),
                query(ds, "SELECT position FROM project_buckets WHERE project_id = $projectId " +
                    "AND name IN ('Tendering', 'Surveys') ORDER BY position").map { it.single()!! })
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a card that predates its lane is left unfilled rather than guessed at`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v7-cards.db"))
        try {
            migrateToV6(ds)
            insertProject(ds, "S-03", "Topsoil stripping")
            val projectId = projectId(ds, "S-03")
            insertCard(ds, "Clear the verge", projectId)
            insertCard(ds, "Order the geotextile", projectId)

            Migrations.migrate(ds)

            // NULL, not the first lane: the migration cannot know which phase a piece of work belongs to,
            // and the project board shows an unfiled card under Unsorted instead of hiding it.
            val filed = query(
                ds,
                "SELECT project_bucket_id FROM tasks WHERE project_id = $projectId ORDER BY title",
            )
            assertEquals(2, filed.size)
            filed.forEach { assertNull(it.single()) }
            val columns = query(ds, "SELECT name FROM pragma_table_info('tasks')").map { it.single()!! }
            assertTrue("project_bucket_id" in columns, "the lane column did not arrive: $columns")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `removing a lane unfiles its cards and removing a project takes its lanes`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v7-refs.db"))
        try {
            // The project is created before the migrations so its lanes arrive with them; a project created
            // after them is seeded by ProjectService, which is the other half of the same promise.
            migrateToV6(ds)
            insertProject(ds, "S-04", "Bench wall repair")
            Migrations.migrate(ds)
            val projectId = projectId(ds, "S-04")
            val procurement = laneId(ds, projectId, "Procurement")
            insertCard(ds, "Place the concrete", projectId)
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("UPDATE tasks SET project_bucket_id = $procurement WHERE title = 'Place the concrete'")
                }
            }

            // A lane is deleted on its own, from the board — the card must stay and read as Unsorted. This is
            // the schema promise V16's header argues from, and the reason the service refuses to remove a lane
            // that still holds cards rather than let this happen through its own route.
            ds.connection.use { conn ->
                conn.createStatement().use { st -> st.executeUpdate("DELETE FROM project_buckets WHERE id = $procurement") }
            }
            assertNull(
                query(ds, "SELECT project_bucket_id FROM tasks WHERE title = 'Place the concrete'").single().single(),
                "deleting a lane took its card with it",
            )

            // A project goes entirely: no lane rows may be left pointing at a project that is not there.
            ds.connection.use { conn ->
                conn.createStatement().use { st -> st.executeUpdate("DELETE FROM projects WHERE id = $projectId") }
            }
            assertTrue(
                query(ds, "SELECT id FROM project_buckets WHERE project_id = $projectId").isEmpty(),
                "the project left its lanes behind",
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `the backfill runs once and is not replayed on the next boot`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v16-reboot.db"))
        try {
            // A project that predates V16, then two boots — the engine runs again on every start of the
            // application, and a versioned migration is not replayed. A project created *after* it gets its
            // lanes from ProjectService instead, which is what [mn.murray.pts.service.ProjectServiceTest]
            // covers; the table must not end up with two *Engineering* columns either way.
            migrateToV6(ds)
            insertProject(ds, "S-05", "Lay the survey grid")
            val projectId = projectId(ds, "S-05")

            Migrations.migrate(ds)
            val afterFirstBoot = query(ds, "SELECT count(*) FROM project_buckets").single().single()
            Migrations.migrate(ds)

            assertEquals(DEFAULT_PROJECT_BUCKETS, lanesOf(ds, projectId))
            assertEquals(3, query(ds, "SELECT count(*) FROM project_buckets WHERE project_id = $projectId")
                .single().single()!!.toInt())
            assertEquals(afterFirstBoot, query(ds, "SELECT count(*) FROM project_buckets").single().single())
        } finally {
            ds.close()
        }
    }
}
