package mn.murray.pts.security

import org.mindrot.jbcrypt.BCrypt

/**
 * Password hashing (bcrypt). Replaces the legacy plaintext passwords.
 *
 * Cost 12 is a good balance for an internal tool (~250ms/hash). During JSON import,
 * each user's effective password (stored value, or employee number when unset) is
 * hashed once; the plaintext is never persisted.
 */
object PasswordHasher {

    private const val COST = 12

    fun hash(plain: String): String = BCrypt.hashpw(plain, BCrypt.gensalt(COST))

    /** Constant-time-ish check; returns false for blank/invalid hashes rather than throwing. */
    fun verify(plain: String, hashed: String): Boolean {
        if (hashed.isBlank() || plain.isEmpty()) return false
        return runCatching { BCrypt.checkpw(plain, hashed) }.getOrDefault(false)
    }

    /** True if the value already looks like a bcrypt hash (avoids double-hashing on re-import). */
    fun looksHashed(value: String): Boolean =
        value.length == 60 && (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$"))
}
