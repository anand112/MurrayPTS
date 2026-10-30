package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V15 gives one checklist step the fact V5's table never kept: where it stood the moment before its card
 * was moved into **Completed**, which ticks a card's whole plan (Stage 70). Without that memory the way
 * back out of *Completed* could only be "untick everything" — the loss of work a card's owner had already
 * recorded on the plan.
 *
 * As in [ProjectBucketMigrationTest], what a migration has to get right is the rows that were written
 * *before* it ran, so each test builds V1–V14, inserts cards and steps the way a live database holds them,
 * and then runs the real [Migrations.migrate]. Two promises are pinned here:
 *
 *  - the column arrives, nullable and with no default, because NULL means *nothing remembered* while 0 is
 *    a claim about a completion that never happened;
 *  - the backfill is not decoration — a card already sitting in *Completed* gets the one completion its own
 *    rule now says it must have, and its steps' earlier states land in `pre_completed` so reopening that
 *    old card gives back the plan it had rather than a wall of ticks. Cards in any other column, and every
 *    step of theirs, are left exactly as they were.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class ChecklistCompletionMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
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

    private fun migrateToV14(ds: HikariDataSource) = migrateTo(ds, "14")

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

    /** A card in the shape a live database holds one: no project, no assignee, and the status it was left in. */
    private fun insertCard(ds: HikariDataSource, title: String, status: String) {
        update(
            ds,
            "INSERT INTO tasks (kind, title, due, priority, status) " +
                "VALUES ('ASSIGNED', '$title', '2026-11-12', 'High', '$status')",
        )
    }

    private fun cardId(ds: HikariDataSource, title: String): Int =
        query(ds, "SELECT id FROM tasks WHERE title = '$title'").single().single()!!.toInt()

    /** One step of a card's plan, in the order it was typed and the state it was left in. */
    private fun insertStep(ds: HikariDataSource, taskId: Int, label: String, done: Boolean, position: Int) {
        update(
            ds,
            "INSERT INTO task_checklist_items (task_id, label, done, position) " +
                "VALUES ($taskId, '$label', ${if (done) 1 else 0}, $position)",
        )
    }

    /** The card's plan as the table holds it: label, done, and the memory V15 added. */
    private fun steps(ds: HikariDataSource, title: String): List<Triple<String, String?, String?>> {
        val taskId = cardId(ds, title)
        return query(
            ds,
            "SELECT label, done, pre_completed FROM task_checklist_items " +
                "WHERE task_id = $taskId ORDER BY position",
        ).map { Triple(it[0]!!, it[1], it[2]) }
    }

    @Test
    fun `V15 adds the memory as a column that defaults to nothing remembered`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v15-column.db"))
        try {
            migrateToV14(ds)
            Migrations.migrate(ds)
            val columns = query(ds, "SELECT name FROM pragma_table_info('task_checklist_items')").map { it.single()!! }
            assertEquals(
                listOf("id", "task_id", "label", "done", "position", "pre_completed"),
                columns,
                "the step's memory is not the column V15 defines",
            )
            // Nullable, and with no default: 0 would say *this step was unticked when the card was
            // finished*, which a step of a card that has never been through one has never said.
            val definition = query(
                ds,
                "SELECT dflt_value, \"notnull\" FROM pragma_table_info('task_checklist_items') " +
                    "WHERE name = 'pre_completed'",
            ).single()
            assertNull(definition[0], "the memory column was given a default instead of NULL")
            assertEquals("0", definition[1], "the memory column cannot hold NULL")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a card already in Completed is completed once, with each step's earlier state kept`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v15-backfill.db"))
        try {
            migrateToV14(ds)
            insertCard(ds, "Finished before this build", "Completed")
            val finished = cardId(ds, "Finished before this build")
            insertStep(ds, finished, "Survey the start line", done = true, position = 0)
            insertStep(ds, finished, "Compact the sub-base", done = false, position = 1)
            insertStep(ds, finished, "Sign off the layer test", done = true, position = 2)
            // A card still being worked, with a plan half ticked: none of this is the migration's business.
            insertCard(ds, "Still open", "In progress")
            val open = cardId(ds, "Still open")
            insertStep(ds, open, "Order the geotextile", done = true, position = 0)
            insertStep(ds, open, "Set out the haul road", done = false, position = 1)

            Migrations.migrate(ds)

            // Every step of the finished card reads done, and remembers what it was before the tick.
            assertEquals(
                listOf(
                    Triple("Survey the start line", "1", "1"),
                    Triple("Compact the sub-base", "1", "0"),
                    Triple("Sign off the layer test", "1", "1"),
                ),
                steps(ds, "Finished before this build"),
                "the backfill did not run one completion over the card that was already finished",
            )
            assertEquals(
                listOf(
                    Triple("Order the geotextile", "1", null),
                    Triple("Set out the haul road", "0", null),
                ),
                steps(ds, "Still open"),
                "the backfill touched a card that is not finished",
            )
            // The state the app now refuses to show: a ticked-out plan with no memory behind it could not be
            // restored, so a step may only be done-without-memory when it was written that way.
            assertEquals(
                0,
                query(
                    ds,
                    "SELECT count(*) FROM task_checklist_items WHERE done = 0 AND pre_completed IS NOT NULL",
                ).single().single()!!.toInt(),
                "a step was remembered as ticked and left unticked",
            )
        } finally {
            ds.close()
        }
    }

    @Test
    fun `the completion is not run again over a plan somebody has since worked`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v15-reboot.db"))
        try {
            migrateToV14(ds)
            insertCard(ds, "Reopened card", "In progress")
            val card = cardId(ds, "Reopened card")
            insertStep(ds, card, "Break through the wet layer", done = false, position = 0)
            Migrations.migrate(ds)
            assertEquals(
                listOf(Triple("Break through the wet layer", "0", null)),
                steps(ds, "Reopened card"),
                "V15's backfill reached a card that was not finished",
            )
            // A second boot, and a card finished *after* the repair ran: the plan is the application's to
            // tick from here (Stage 70's rule lives in TaskService), so a migration that replayed itself
            // would overwrite whatever its owner left on a reopened card.
            update(ds, "UPDATE tasks SET status = 'Completed' WHERE id = $card")
            insertCard(ds, "Finished later", "Completed")
            val later = cardId(ds, "Finished later")
            insertStep(ds, later, "Commission the weighbridge", done = false, position = 0)
            Migrations.migrate(ds)
            assertEquals(
                listOf(Triple("Break through the wet layer", "0", null)),
                steps(ds, "Reopened card"),
                "a reboot re-ran the completion over a plan its owner had since worked",
            )
            assertEquals(
                listOf(Triple("Commission the weighbridge", "0", null)),
                steps(ds, "Finished later"),
                "a reboot ticked a card finished after the repair",
            )
        } finally {
            ds.close()
        }
    }
}
