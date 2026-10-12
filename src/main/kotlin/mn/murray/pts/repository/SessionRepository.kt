package mn.murray.pts.repository

import mn.murray.pts.db.Sessions
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * A resolved, still-valid login session.
 */
data class SessionRecord(
    val id: String,
    val employeeNumber: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * DB-backed session store. Replaces the legacy in-memory `ConcurrentHashMap` so that
 * sessions survive a restart and can be revoked by an admin (e.g. after a password
 * change or when disabling an account). The cookie name stays `PTS_SID` (see AppConfig).
 *
 * Expiry is enforced at read time ([findValid]) and can be swept with [purgeExpired].
 */
object SessionRepository {

    /** Create a new session token for [employeeNumber] living [ttlHours] hours. */
    fun create(
        employeeNumber: String,
        ttlHours: Long,
        userAgent: String = "",
        ip: String = "",
    ): String = transaction {
        val now = Instant.now()
        val id = UUID.randomUUID().toString()
        Sessions.insert {
            it[Sessions.id] = id
            it[Sessions.employeeNumber] = employeeNumber
            it[createdAt] = now.toString()
            it[expiresAt] = now.plusSeconds(ttlHours * 3600).toString()
            it[Sessions.userAgent] = userAgent.take(512)
            it[Sessions.ip] = ip
        }
        id
    }

    /** Return the session for [token] only if it exists and has not expired. */
    fun findValid(token: String?): SessionRecord? {
        if (token.isNullOrBlank()) return null
        return transaction {
            val row = Sessions.selectAll().where { Sessions.id eq token }.firstOrNull() ?: return@transaction null
            val rec = SessionRecord(
                id = row[Sessions.id],
                employeeNumber = row[Sessions.employeeNumber],
                createdAt = runCatching { Instant.parse(row[Sessions.createdAt]) }.getOrDefault(Instant.EPOCH),
                expiresAt = runCatching { Instant.parse(row[Sessions.expiresAt]) }.getOrDefault(Instant.EPOCH),
            )
            if (rec.expiresAt.isBefore(Instant.now())) {
                // Lazily drop the expired row so the table does not grow unbounded.
                Sessions.deleteWhere { Sessions.id eq token }
                null
            } else {
                rec
            }
        }
    }

    fun revoke(token: String) = transaction {
        Sessions.deleteWhere { Sessions.id eq token }
    }

    /** Revoke every session for a user (used on password change / account disable). */
    fun revokeAllFor(employeeNumber: String): Int = transaction {
        Sessions.deleteWhere { Sessions.employeeNumber eq employeeNumber }
    }

    /** Delete all sessions whose expiry is in the past. Returns rows removed. */
    fun purgeExpired(): Int = transaction {
        val now = Instant.now()
        // ISO instant strings have variable fractional-second length, so lexicographic
        // SQL comparison is unreliable; parse and compare as instants instead.
        val expiredIds = Sessions.selectAll()
            .mapNotNull { row ->
                val exp = runCatching { Instant.parse(row[Sessions.expiresAt]) }.getOrNull()
                if (exp != null && exp.isBefore(now)) row[Sessions.id] else null
            }
        expiredIds.forEach { id -> Sessions.deleteWhere { Sessions.id eq id } }
        expiredIds.size
    }
}
