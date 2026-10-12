package mn.murray.pts.security

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, per-key (normally per-IP) sliding-window limiter for login attempts.
 *
 * Bounds set from AppConfig (`loginMaxAttempts` within `loginWindowSeconds`). This is a
 * best-effort throttle against online password guessing on a single node; it is not a
 * substitute for account lockout or a WAF. Attempts older than the window are pruned on
 * each check, so memory stays bounded by active keys.
 */
class LoginRateLimiter(
    private val maxAttempts: Int,
    private val windowSeconds: Long,
    private val now: () -> Instant = { Instant.now() },
) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    /** True when [key] has exceeded the allowed attempts within the window. */
    fun isBlocked(key: String): Boolean {
        prune(key)
        val count = hits[key]?.size ?: 0
        return count >= maxAttempts
    }

    /** Record a failed attempt for [key]. Successful logins should call [reset]. */
    fun recordFailure(key: String) {
        val q = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) { q.addLast(now()) }
    }

    /** Clear the history for [key] (after a successful login). */
    fun reset(key: String) {
        hits.remove(key)
    }

    /** Seconds until [key] is allowed to try again (0 when not blocked). */
    fun retryAfterSeconds(key: String): Long {
        val q = hits[key] ?: return 0
        val oldest = synchronized(q) { q.firstOrNull() } ?: return 0
        val elapsed = now().epochSecond - oldest.epochSecond
        val remaining = windowSeconds - elapsed
        return if (remaining > 0) remaining else 0
    }

    private fun prune(key: String) {
        val q = hits[key] ?: return
        val cutoff = now().minusSeconds(windowSeconds)
        synchronized(q) {
            while (q.isNotEmpty() && q.first().isBefore(cutoff)) q.removeFirst()
            if (q.isEmpty()) hits.remove(key)
        }
    }
}
