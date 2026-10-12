package mn.murray.pts.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mn.murray.pts.config.AppConfig
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Runs [DueDateAlertService.runSweep] once a day inside the app process.
 *
 * Why in-process instead of cron / a sidecar: the sweep needs the same SQLite file and the same
 * config the app already owns, and a second container that opens `data/pts.db` would contend with
 * the app's WAL writes. One process, one writer. The consequence is that this is a **single-node**
 * feature — running several app instances behind a load balancer would run one scheduler per
 * instance. The ledger's UNIQUE key makes that safe (only one alert per person per item per day
 * gets recorded) but wasteful, so scale by keeping one app instance, or set
 * `PTS_ALERTS_ENABLED=false` everywhere and trigger sweeps externally.
 *
 * The first sweep happens a short time after boot rather than at midnight: an operator can restart
 * the service during the day and see the reminders go out, and a host that was down overnight still
 * covers its users on the way back up.
 */
object DueDateAlertScheduler {

    private val log = LoggerFactory.getLogger(DueDateAlertScheduler::class.java)

    @Volatile
    private var scope: CoroutineScope? = null

    /** True while the scheduler coroutine is alive (a disabled or failed start leaves this false). */
    val running: Boolean get() = scope?.isActive == true

    /**
     * Start the loop. Idempotent, and a no-op when alerts are switched off, so both the boot path
     * and a re-entrant test can call it freely.
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (!AppConfig.alertsEnabled) {
            log.info("Due-date alerts disabled (PTS_ALERTS_ENABLED=false): scheduler not started")
            return false
        }
        val interval = Duration.ofHours(AppConfig.alertIntervalHours)
        val startupDelay = Duration.ofSeconds(AppConfig.alertStartupDelaySeconds)
        // A dedicated scope: cancelling it on shutdown takes the loop down without touching anything
        // else in the process, and a thrown exception cannot silently kill the daily heartbeat.
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            delay(startupDelay.toMillis())
            while (isActive) {
                try {
                    // A sweep is blocking I/O (SQLite + SMTP); this coroutine lives on Dispatchers.IO.
                    DueDateAlertService.runSweep()
                } catch (e: CancellationException) {
                    throw e                      // shutdown must not be swallowed as a "failure"
                } catch (e: Exception) {
                    // Never let one bad sweep kill the loop: the feature is a daily heartbeat and
                    // the next day's run matters more than this one's stack trace.
                    log.warn("Due-date sweep failed: {}", "${e.javaClass.simpleName}: ${e.message ?: e}")
                }
                try {
                    DueDateAlertService.purgeLedgerOlderThan(AppConfig.alertLedgerKeepDays)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Due-date ledger trim failed: {}", "${e.javaClass.simpleName}: ${e.message ?: e}")
                }
                delay(interval.toMillis())
            }
        }
        log.info(
            "Due-date alert scheduler started: first sweep in {} s, then every {} h ({})",
            startupDelay.toSeconds(), interval.toHours(), DueDateAlertService.currentMode(),
        )
        return true
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        log.info("Due-date alert scheduler stopped")
    }
}
