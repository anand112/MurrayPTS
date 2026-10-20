package mn.murray.pts.service

import mn.murray.pts.config.AppConfig
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.TEAM_ROSTER
import mn.murray.pts.domain.User
import mn.murray.pts.domain.autoEmail
import mn.murray.pts.repository.SessionRecord
import mn.murray.pts.repository.SessionRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.LoginRateLimiter
import mn.murray.pts.security.PasswordHasher
import org.slf4j.LoggerFactory

/** Outcome of a login attempt. Never carries the password or hash. */
sealed class AuthResult {
    data class Success(val user: User, val token: String) : AuthResult()
    /** Blocked by the rate limiter; [retryAfterSeconds] is a hint for the UI. */
    data class Throttled(val retryAfterSeconds: Long) : AuthResult()
    /** Bad credentials or unknown account. The message is intentionally generic. */
    data class Failure(val message: String = "Invalid employee number or password.") : AuthResult()
}

/**
 * Authentication and session lifecycle. Single entry point for login/logout so the
 * bcrypt verification, rate limiting and DB-backed session handling stay in one place.
 *
 * Passwords are verified against `users.password_hash` with [PasswordHasher] (bcrypt
 * cost 12). Plaintext comparison (the legacy behaviour) is gone. Sessions live in the
 * DB (see [SessionRepository]) so they survive restarts and can be revoked.
 */
object AuthService {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /** Login throttle keyed by client IP; bounds come from AppConfig. */
    val rateLimiter = LoginRateLimiter(
        maxAttempts = AppConfig.loginMaxAttempts,
        windowSeconds = AppConfig.loginWindowSeconds,
    )

    fun authenticate(employeeNumber: String, password: String, ip: String, userAgent: String = ""): AuthResult {
        val key = ip.ifBlank { "unknown" }
        if (rateLimiter.isBlocked(key)) {
            return AuthResult.Throttled(rateLimiter.retryAfterSeconds(key))
        }
        val empNo = employeeNumber.trim().uppercase()
        val user = if (empNo.isEmpty()) null else UserRepository.findByEmpNo(empNo)
        if (user == null || !PasswordHasher.verify(password, user.passwordHash)) {
            rateLimiter.recordFailure(key)
            return AuthResult.Failure()
        }
        rateLimiter.reset(key)
        val token = SessionRepository.create(
            employeeNumber = user.employeeNumber,
            ttlHours = AppConfig.sessionTtlHours,
            userAgent = userAgent,
            ip = ip,
        )
        return AuthResult.Success(user, token)
    }

    /** Resolve a session cookie token to a live session, or null when absent/expired. */
    fun resolve(token: String?): SessionRecord? = SessionRepository.findValid(token)

    /**
     * True while the account still carries a password it never chose (a legacy import that
     * defaulted to the employee number, or a bootstrapped super admin with no bootstrap
     * password). The route gate reads this through [mn.murray.pts.currentSession] so no
     * handler has to remember to check it.
     */
    fun mustChangePassword(employeeNumber: String): Boolean =
        UserRepository.mustChangePassword(employeeNumber)

    fun logout(token: String?) {
        if (!token.isNullOrBlank()) SessionRepository.revoke(token)
    }

    /**
     * Issue a fresh session token for an already-authenticated user. Used after a
     * self-service password change, which revokes all sessions (including the current
     * one) — re-issuing keeps the user logged in so the success banner is shown.
     */
    fun reissue(employeeNumber: String, ip: String = "", userAgent: String = ""): String =
        SessionRepository.create(employeeNumber, AppConfig.sessionTtlHours, userAgent, ip)

    /**
     * Set a new password, then revoke every existing session for the user so a stolen
     * cookie cannot survive the change.
     */
    fun changePassword(employeeNumber: String, newPlain: String, mustChange: Boolean = false) {
        UserRepository.updatePasswordHash(employeeNumber, PasswordHasher.hash(newPlain), mustChange)
        SessionRepository.revokeAllFor(employeeNumber)
    }

    /**
     * Guarantee the configured super admin exists on every boot. On a fresh DB with no
     * legacy JSON this is the only way in. If no bootstrap password is configured, the
     * legacy rule applies (password defaults to the employee number) and the admin is
     * forced to change it. Never logs the password.
     */
    fun ensureSuperAdmin() {
        val empNo = AppConfig.superAdminEmpNo
        if (UserRepository.findByEmpNo(empNo) != null) return
        val name = TeamRepository.empNoByName().entries.firstOrNull { it.value == empNo }?.key ?: "Super Admin"
        val bootstrap = AppConfig.superAdminBootstrapPassword
        val initial = bootstrap ?: empNo
        // users.employee_number has a FK into team_members, so an account cannot exist without a
        // roster row. A migrated database gets placeholders from the importer; a brand-new one
        // reaches here with nothing, and inserting straight away fails SQLITE_CONSTRAINT_FOREIGNKEY.
        if (TeamRepository.findByEmpNo(empNo) == null) {
            TeamRepository.upsert(
                TEAM_ROSTER.firstOrNull { it.employeeNumber == empNo }
                    ?: Member(employeeNumber = empNo, name = name, department = "IT / Systems", position = "Super Admin")
            )
        }
        UserRepository.insert(
            employeeNumber = empNo,
            name = name,
            passwordHash = PasswordHasher.hash(initial),
            access = "Admin",
            email = autoEmail(name),
            positionId = null,
            isSuperAdmin = true,
            mustChangePassword = bootstrap == null,
        )
        log.info("Bootstrapped super admin {} (mustChangePassword={})", empNo, bootstrap == null)
    }
}
