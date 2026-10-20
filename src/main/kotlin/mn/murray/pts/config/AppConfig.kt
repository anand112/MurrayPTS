package mn.murray.pts.config

import java.io.File

/**
 * Central, dependency-free configuration.
 *
 * Lookup precedence for every key (first hit wins):
 *   1. JVM system property   (-Dpts.port=9000)      — preserves the legacy behavior
 *   2. OS environment var    (PTS_PORT=9000)         — preserves the legacy behavior
 *   3. `.env` file entry      (PTS_PORT=9000)         — new in v2, gitignored
 *   4. built-in default
 *
 * Keeping this hand-rolled (rather than pulling a config framework) means the whole
 * resolution order is readable in one place — see ARCHITECTURE.md → Security → Secrets.
 */
object AppConfig {

    /** Parsed once from a `.env` file next to the working directory, if present. */
    private val dotEnv: Map<String, String> by lazy { loadDotEnv(File(".env")) }

    private fun loadDotEnv(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        file.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                val key = line.substring(0, eq).trim()
                var value = line.substring(eq + 1).trim()
                // Strip one layer of matching quotes.
                if (value.length >= 2 &&
                    ((value.startsWith('"') && value.endsWith('"')) ||
                        (value.startsWith('\'') && value.endsWith('\'')))
                ) {
                    value = value.substring(1, value.length - 1)
                }
                out[key] = value
            }
        }
        return out
    }

    /** Resolve a string config value by system property, env var, .env, then default. */
    private fun resolve(sysProp: String, envKey: String, default: String): String =
        System.getProperty(sysProp)
            ?: System.getenv(envKey)
            ?: dotEnv[envKey]
            ?: default

    private fun resolveInt(sysProp: String, envKey: String, default: Int): Int =
        resolve(sysProp, envKey, default.toString()).toIntOrNull() ?: default

    private fun resolveBool(sysProp: String, envKey: String, default: Boolean): Boolean =
        resolve(sysProp, envKey, default.toString()).toBooleanStrictOrNull() ?: default

    // --- Server -------------------------------------------------------------
    val port: Int get() = resolveInt("pts.port", "PTS_PORT", 8080)
    val host: String get() = resolve("pts.host", "PTS_HOST", "0.0.0.0")

    /** "development" (default) or "production". Production enforces a real session secret. */
    val environment: String get() = resolve("pts.env", "PTS_ENV", "development")
    val isProduction: Boolean get() = environment.equals("production", ignoreCase = true)

    /**
     * Trust `X-Forwarded-*` headers set by a reverse proxy (nginx/Caddy) in front of the app.
     *
     * Default false, and that default is a security boundary: on a directly exposed port these
     * headers are attacker-supplied, so trusting them would let anyone spoof their way past the
     * per-IP login rate limiter. Turn it on ONLY when the app listens on a private interface and
     * the proxy is the only route in — it installs Ktor's `ForwardedHeaders` plugin (correct
     * host/scheme for redirects) and makes [mn.murray.pts.clientIp] read `X-Forwarded-For`.
     */
    val trustProxy: Boolean get() = resolveBool("pts.trust.proxy", "PTS_TRUST_PROXY", false)

    // --- Persistence --------------------------------------------------------
    /** SQLite database file. Parent directories are created on connect. */
    val dbPath: String get() = resolve("pts.db.path", "PTS_DB_PATH", "data/pts.db")

    /** Legacy JSON store, read once by the importer on first run. */
    val legacyJsonPath: String
        get() = resolve("pts.data.file", "PTS_DATA_FILE", "data/pts-data.json")

    /**
     * Seed DemoData into an empty database. Default false (v2): an empty DB shows an
     * empty state rather than silently reseeding. Set PTS_SEED_DEMO=true to opt in.
     */
    val seedDemo: Boolean get() = resolveBool("pts.seed.demo", "PTS_SEED_DEMO", false)

    // --- Security -----------------------------------------------------------
    /**
     * HMAC key for the CSRF tokens in [mn.murray.pts.security.Csrf], and the reason
     * [validate] refuses to boot in production while it is still the built-in default.
     *
     * Sessions themselves are random DB-backed tokens (see `SessionRepository`), so nothing
     * here signs the cookie. Note that `Csrf` is currently exercised only by its own tests —
     * the POST routes still rely on the `SameSite=Lax` session cookie for CSRF defence.
     */
    val sessionSecret: String get() = resolve("pts.session.secret", "PTS_SESSION_SECRET", DEV_SECRET)
    private const val DEV_SECRET = "dev-only-insecure-session-secret-change-me"

    /** Cookie name — preserved from the legacy app so existing browsers stay logged in. */
    val sessionCookieName: String get() = resolve("pts.session.cookie", "PTS_SESSION_COOKIE", "PTS_SID")

    /**
     * Session lifetime in hours. Absolute, not sliding: `SessionRepository.create` stamps
     * `expires_at = now + ttl` and `findValid` only reads it, so a session ends ttl hours after
     * login however actively the user works. Raise this if operators complain about being
     * logged out mid-day.
     */
    val sessionTtlHours: Long get() = resolveInt("pts.session.ttl.hours", "PTS_SESSION_TTL_HOURS", 12).toLong()

    /** Mark session cookie Secure (HTTPS-only). Defaults to true in production. */
    val secureCookies: Boolean get() = resolveBool("pts.secure.cookies", "PTS_SECURE_COOKIES", isProduction)

    // --- Admin bootstrap ----------------------------------------------------
    /** Employee number ensured on every boot as super admin. */
    val superAdminEmpNo: String get() = resolve("pts.admin.empno", "PTS_ADMIN_EMPNO", "EMP0000")

    /**
     * Optional bootstrap password for the super admin on a fresh DB. If unset, the
     * legacy rule applies (password defaults to the employee number) and the admin is
     * forced to change it. Never logged.
     */
    val superAdminBootstrapPassword: String?
        get() = (System.getProperty("pts.admin.password")
            ?: System.getenv("PTS_ADMIN_PASSWORD")
            ?: dotEnv["PTS_ADMIN_PASSWORD"])?.takeIf { it.isNotBlank() }

    /** Login rate limit: max attempts per IP within [loginWindowSeconds]. */
    val loginMaxAttempts: Int get() = resolveInt("pts.login.max", "PTS_LOGIN_MAX_ATTEMPTS", 5)
    val loginWindowSeconds: Long get() = resolveInt("pts.login.window", "PTS_LOGIN_WINDOW_SECONDS", 60).toLong()

    // --- Data directory -----------------------------------------------------
    /** Directory holding the SQLite file and migrated JSON backups. */
    val dataDir: String get() = resolve("pts.data.dir", "PTS_DATA_DIR", "data")

    // --- Logging ------------------------------------------------------------
    /**
     * Rolling log directory and root level.
     *
     * Deliberately resolved from the JVM system property / OS environment only, NOT from
     * `.env`: Logback configures itself from the real environment before any Kotlin runs, so
     * a `.env` entry would change this banner but not the appender. Set these two as actual
     * environment variables (the run scripts and Dockerfile export them for you).
     */
    val logDir: String
        get() = System.getProperty("pts.log.dir") ?: System.getenv("PTS_LOG_DIR") ?: "logs"
    val logLevel: String
        get() = (System.getProperty("pts.log.level") ?: System.getenv("PTS_LOG_LEVEL") ?: "INFO").uppercase()

    /**
     * One INFO line per request (CallLogging). Off by default so local development output
     * stays exactly as it was; production deployments normally turn it on.
     */
    val logRequests: Boolean get() = resolveBool("pts.log.requests", "PTS_LOG_REQUESTS", false)

    // --- Due-date alerts (SMTP) ---------------------------------------------
    /**
     * Master switch for the daily "not finished, due date arriving" sweep. On by default, but a
     * sweep with no `smtpHost` configured cannot send: it logs the message instead and says so in
     * [warnings], so an unconfigured deployment is visible rather than silently inert.
     */
    val alertsEnabled: Boolean get() = resolveBool("pts.alerts.enabled", "PTS_ALERTS_ENABLED", true)

    /** How many days before the due date the first alert goes out. */
    val alertLeadDays: Int get() = resolveInt("pts.alert.lead.days", "PTS_ALERT_LEAD_DAYS", 7)

    /**
     * Sweep interval, in minutes — 5 by default. The cadence is short because the same sweep now
     * also writes the inbox rows behind the bell, and an assignment someone sees as a "due tomorrow"
     * note should arrive the same afternoon, not the next day.
     *
     * The dedupe is unaffected: both the alert ledger and `notifications` key on the calendar day, so
     * 288 sweeps a day still deliver one mail and one inbox item per person, per item, per day.
     */
    val alertIntervalMinutes: Long
        get() = resolveInt("pts.alert.interval.minutes", "PTS_ALERT_INTERVAL_MINUTES", 5).coerceAtLeast(1).toLong()

    /** Delay after boot before the first sweep, so startup work and log noise stay separate. */
    val alertStartupDelaySeconds: Long
        get() = resolveInt("pts.alert.startup.delay.seconds", "PTS_ALERT_STARTUP_DELAY_SECONDS", 30).coerceAtLeast(1).toLong()

    /** How many days of alert history to keep before the ledger is trimmed. 0 disables trimming. */
    val alertLedgerKeepDays: Int
        get() = resolveInt("pts.alert.ledger.keep.days", "PTS_ALERT_LEDGER_KEEP_DAYS", 90).coerceAtLeast(0)

    /**
     * How many days of unread *and* read inbox rows to keep before `notifications` is trimmed; 0
     * disables trimming. Shorter than the ledger on purpose — the ledger is the audit trail, an inbox
     * is a courtesy, and a bell full of last quarter's assignments is noise.
     */
    val notificationKeepDays: Int
        get() = resolveInt("pts.notification.keep.days", "PTS_NOTIFICATION_KEEP_DAYS", 30).coerceAtLeast(0)

    /**
     * Log the rendered message instead of handing it to SMTP. Default off: with a host configured
     * the app really sends. Turn it on to rehearse a rule change without mailing 20 people — the
     * sweep still runs, still writes the ledger, and the full text lands in `logs/pts.log`.
     */
    val smtpDryRun: Boolean get() = resolveBool("pts.smtp.dry.run", "PTS_SMTP_DRY_RUN", false)

    /** Empty means "no mail server configured": alerts fall back to log-only. */
    val smtpHost: String get() = resolve("pts.smtp.host", "PTS_SMTP_HOST", "")
    val smtpPort: Int get() = resolveInt("pts.smtp.port", "PTS_SMTP_PORT", 587)

    /** `starttls` (default, port 587), `ssl`/`smtps` (implicit TLS, port 465) or `none`. */
    val smtpSecurity: String
        get() = resolve("pts.smtp.security", "PTS_SMTP_SECURITY", "starttls").lowercase()

    /** Login user for the SMTP server. Also the default envelope sender if `smtpFrom` is unset. */
    val smtpUsername: String get() = resolve("pts.smtp.user", "PTS_SMTP_USERNAME", "")
    val smtpPassword: String get() = resolve("pts.smtp.password", "PTS_SMTP_PASSWORD", "")
    val smtpFrom: String get() = resolve("pts.smtp.from", "PTS_SMTP_FROM", smtpUsername)

    /** True only when there is a host to talk to and dry-run is off. */
    val smtpConfigured: Boolean get() = smtpHost.isNotBlank() && !smtpDryRun

    private const val MIN_SECRET_LENGTH = 32

    /**
     * Fail-fast checks run at boot. In production we refuse to start with the default
     * session secret or one that is too short, because that would let anyone forge
     * session cookies.
     */
    fun validate() {
        if (!isProduction) return
        val problems = buildList {
            if (sessionSecret == DEV_SECRET) add("PTS_SESSION_SECRET is not set")
            else if (sessionSecret.length < MIN_SECRET_LENGTH) add("PTS_SESSION_SECRET must be at least $MIN_SECRET_LENGTH characters")
        }
        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "Refusing to start in production: " + problems.joinToString("; ") +
                    ". Set a strong PTS_SESSION_SECRET (e.g. 64 random hex chars)."
            )
        }
    }

    /** Human-readable summary for the startup banner. Never includes the secret. */
    fun summary(): String = buildString {
        appendLine("environment      = $environment")
        appendLine("host:port        = $host:$port")
        appendLine("trustProxy       = $trustProxy")
        appendLine("dbPath           = $dbPath")
        appendLine("legacyJsonPath   = $legacyJsonPath")
        appendLine("seedDemo         = $seedDemo")
        appendLine("sessionCookie    = $sessionCookieName (ttl=${sessionTtlHours}h, secure=$secureCookies)")
        appendLine("sessionSecret    = " + if (sessionSecret == DEV_SECRET) "<default dev secret>" else "<set, ${sessionSecret.length} chars>")
        appendLine("logging          = $logLevel to console + $logDir/pts.log (per-request=$logRequests)")
        appendLine(
            "due-date alerts  = " + if (!alertsEnabled) "disabled"
            else "every ${alertIntervalMinutes}min, lead ${alertLeadDays}d, inbox kept ${notificationKeepDays}d via " +
                if (smtpConfigured) "SMTP $smtpHost:$smtpPort as ${smtpFrom.ifBlank { "(no sender)" }}"
                else if (smtpDryRun) "DRY RUN (log only)"
                else "LOG ONLY — PTS_SMTP_HOST is not set"
        )
    }

    /**
     * Non-fatal production advisories, logged after boot so an operator can fix the
     * deployment instead of being refused at startup. Each one describes the symptom it
     * would cause, because most of them only bite once the app is publicly reachable.
     */
    fun warnings(): List<String> = buildList {
        // Not production-gated: "the mailer is configured but cannot send" is worth saying on a
        // developer machine too, and it is the single most common way this feature goes silent.
        if (alertsEnabled && smtpHost.isBlank() && !smtpDryRun) {
            add(
                "due-date alerts are enabled but PTS_SMTP_HOST is empty: every alert will be written to " +
                    "the log with channel=log and no mail will be sent. Set PTS_SMTP_HOST/PTS_SMTP_USER/" +
                    "PTS_SMTP_PASSWORD, or PTS_SMTP_DRY_RUN=true to make the log-only mode deliberate."
            )
        }
        if (alertsEnabled && smtpHost.isNotBlank() && smtpFrom.isBlank()) {
            add("PTS_SMTP_HOST is set but there is no sender address (PTS_SMTP_FROM/PTS_SMTP_USER): sends will fail.")
        }
        // A host with no password is the half-configured state: the mailer stops falling back to
        // log-only and every send comes back as an authentication failure instead.
        if (alertsEnabled && smtpHost.isNotBlank() && !smtpDryRun && smtpPassword.isBlank()) {
            add(
                "PTS_SMTP_HOST is set but PTS_SMTP_PASSWORD is empty: the mailer will try to send and every " +
                    "alert will land in due_date_alerts with delivered=0 and an authentication error. Set the " +
                    "password, or PTS_SMTP_DRY_RUN=true until you have it."
            )
        }
        if (!isProduction) return@buildList
        if (secureCookies && !trustProxy) {
            add(
                "session cookie is marked Secure but PTS_TRUST_PROXY is off: if you reach this host " +
                    "over plain http:// the browser will never send it back and login will loop. " +
                    "Put TLS in front (see README) or set PTS_SECURE_COOKIES=false for a local test run."
            )
        }
        if (host == "0.0.0.0" && !trustProxy) {
            add(
                "listening on 0.0.0.0 without proxy trust: anyone who can route to this port talks to the app " +
                    "directly. Bind PTS_HOST=127.0.0.1 when a reverse proxy is the only entry point."
            )
        }
        if (superAdminBootstrapPassword == null) {
            add(
                "no PTS_ADMIN_PASSWORD set: the super admin's password falls back to its employee number " +
                    "and is forced to change on first login."
            )
        }
    }
}
