package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.config.AppConfig
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Owns the SQLite connection pool and Exposed binding.
 *
 * Call [init] once at startup (Application.kt). Idempotent and thread-safe.
 * Foreign keys are enforced per connection via Hikari's connectionInitSql, and
 * WAL journaling is enabled once so concurrent readers don't block the writer.
 */
object DatabaseFactory {

    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    @Volatile
    private var initialized = false

    lateinit var dataSource: HikariDataSource
        private set

    @Synchronized
    fun init(dbPath: String = AppConfig.dbPath) {
        if (initialized) return

        val file = File(dbPath).absoluteFile
        file.parentFile?.mkdirs()

        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
            // SQLite ignores FK constraints unless this is set on EVERY connection.
            connectionInitSql = "PRAGMA foreign_keys = ON"
            maximumPoolSize = 4
            minimumIdle = 1
            poolName = "pts-pool"
            isAutoCommit = true
        }
        dataSource = HikariDataSource(config)

        // WAL is persistent for the database file; enabling it once is enough and
        // dramatically reduces SQLITE_BUSY under concurrent read + single writer.
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
        }

        Migrations.migrate(dataSource)
        Database.connect(dataSource)

        initialized = true
        log.info("SQLite ready at {} (foreign_keys=ON, journal_mode=WAL, migrations applied)", file.absolutePath)
    }

    /** Close the pool (used by tests and graceful shutdown). */
    @Synchronized
    fun close() {
        if (initialized) {
            dataSource.close()
            initialized = false
        }
    }

    /** True once [init] has completed. */
    val isReady: Boolean get() = initialized
}
