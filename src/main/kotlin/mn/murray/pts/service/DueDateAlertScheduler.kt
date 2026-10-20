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
 * Runs [DueDateAlertService.runSweep] on a timer inside the app process — every
 * `PTS_ALERT_INTERVAL_MINUTES`, five by default.
 *
 * The interval is short because the sweep is no longer only email: it is also what writes the due-date
 * rows behind the notification bell, and a reminder that lands a day late is a reminder nobody needed.
 * Nothing about that changes what a person receives — the ledger and `notifications` both key on the
 * calendar day, so 288 sweeps in a day still send one mail and one inbox item per person, per item.
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
        val interval = Duration.ofMinutes(AppConfig.alertIntervalMinutes)
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
                    // Never let one bad sweep kill the loop: the feature is a heartbeat and
                    // the next run matters more than this one's stack trace.
                    log.warn("Due-date sweep failed: {}", "${e.javaClass.simpleName}: ${e.message ?: e}")
                }
                trimSafely("Due-date ledger trim") { DueDateAlertService.purgeLedgerOlderThan(AppConfig.alertLedgerKeepDays) }
                trimSafely("Notification inbox trim") { NotificationService.purgeOlderThan(AppConfig.notificationKeepDays) }
                delay(interval.toMillis())
            }
        }
        log.info(
            "Due-date alert scheduler started: first sweep in {} s, then every {} min ({})",
            startupDelay.toSeconds(), interval.toMinutes(), DueDateAlertService.currentMode(),
        )
        return true
    }

    /**
     * One housekeeping trim that cannot end the loop. Both tables grow on their own (the ledger per
     * alert sent, the inbox per notification), and a locked file or a bad cutoff should be a warning
     * tonight, not a stopped scheduler in the morning.
     */
    private fun trimSafely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("{} failed: {}", what, "${e.javaClass.simpleName}: ${e.message ?: e}")
        }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        log.info("Due-date alert scheduler stopped")
    }
}
