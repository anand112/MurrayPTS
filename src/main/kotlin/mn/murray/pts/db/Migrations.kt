package mn.murray.pts.db

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Applies versioned SQL migrations from `classpath:db/migration` (V1__init.sql, ...).
 *
 * This is the single seam between the app and schema evolution. If Flyway's SQLite
 * support is ever unavailable, only this file changes — the V*.sql files and the rest
 * of the DB layer stay put (see ARCHITECTURE.md → Data model (SQLite), which lists the
 * migrations and says where Flyway runs).
 */
object Migrations {

    private val log = LoggerFactory.getLogger(Migrations::class.java)

    fun migrate(dataSource: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            // If pointed at a pre-existing, non-Flyway database, adopt it as the baseline
            // instead of failing. Harmless on a fresh DB.
            .baselineOnMigrate(true)
            .load()

        val result = flyway.migrate()
        log.info(
            "Flyway: {} migration(s) applied, schema at version {}",
            result.migrationsExecuted,
            result.targetSchemaVersion ?: "<none>"
        )
    }
}
