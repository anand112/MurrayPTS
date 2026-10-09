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
     * Secret used to sign session cookies. MUST be set via PTS_SESSION_SECRET in
     * production; the built-in default is for local development only and [validate]
     * refuses to boot in production when it is still in use.
     */
    val sessionSecret: String get() = resolve("pts.session.secret", "PTS_SESSION_SECRET", DEV_SECRET)
    private const val DEV_SECRET = "dev-only-insecure-session-secret-change-me"

    /** Cookie name — preserved from the legacy app so existing browsers stay logged in. */
    val sessionCookieName: String get() = resolve("pts.session.cookie", "PTS_SESSION_COOKIE", "PTS_SID")

    /** Session lifetime in hours (sliding). */
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
        appendLine("dbPath           = $dbPath")
        appendLine("legacyJsonPath   = $legacyJsonPath")
        appendLine("seedDemo         = $seedDemo")
        appendLine("sessionCookie    = $sessionCookieName (ttl=${sessionTtlHours}h, secure=$secureCookies)")
        appendLine("sessionSecret    = " + if (sessionSecret == DEV_SECRET) "<default dev secret>" else "<set, ${sessionSecret.length} chars>")
    }
}
