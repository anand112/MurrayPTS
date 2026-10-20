package mn.murray.pts.db.migration

import mn.murray.pts.DemoData
import mn.murray.pts.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Opt-in demo seeding, wired to `PTS_SEED_DEMO` (default false).
 *
 * v2 deliberately dropped the legacy behaviour of resurrecting demo rows on every boot:
 * a real deployment with an empty database must show an empty state, not sample projects.
 * Set `PTS_SEED_DEMO=true` only when you want a populated sandbox.
 *
 * Two guards keep this safe:
 *  1. it runs only when the `users` table is empty, so it can never overwrite real data;
 *  2. it runs only when the one-shot legacy JSON import did not already populate the DB.
 *
 * The seed graph goes through [LegacyNormalizer] so a demo database ends up in exactly the
 * shape a migrated legacy database would have — same statuses, same manager-assigned tasks,
 * same position catalog.
 */
object DemoSeeder {

    private val log = LoggerFactory.getLogger(DemoSeeder::class.java)

    /** Seed when enabled and the database is still empty. Returns true when rows were written. */
    fun seedIfEnabled(): Boolean {
        if (!AppConfig.seedDemo) return false
        if (!JsonImporter.databaseIsEmpty()) {
            log.info("PTS_SEED_DEMO=true but the database already has users; demo seed skipped.")
            return false
        }
        // A legacy file still in place means the JSON importer owns this bootstrap — including
        // the corrupt-JSON case, where filling the DB with demo rows would hide real data.
        val legacy = File(AppConfig.legacyJsonPath)
        if (legacy.exists()) {
            log.warn("PTS_SEED_DEMO=true but {} exists; leaving the database empty for the JSON importer.", legacy.path)
            return false
        }
        val summary = JsonImporter.import(LegacyNormalizer.normalize(DemoData.seed()))
        log.info(
            "Seeded demo dataset ({} users, {} projects, {} tasks). Set PTS_SEED_DEMO=false for a clean database.",
            summary.users, summary.projects, summary.tasks
        )
        return true
    }
}
