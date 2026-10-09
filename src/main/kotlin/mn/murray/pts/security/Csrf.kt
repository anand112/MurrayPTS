package mn.murray.pts.security

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless, session-bound CSRF tokens (synchronizer-token pattern without server state).
 *
 * A token is `HMAC_SHA256(secret, sessionId)` base64url-encoded. Because it is derived
 * from the session id and the server secret, it cannot be forged by another origin, and
 * it rotates automatically when the session changes. Validation recomputes the MAC and
 * compares in constant time.
 *
 * Wiring into the forms/JS is done in Stage 6/7 (the token must be rendered into every
 * POST form and attached by the fetch()-based kanban/reorder handlers).
 */
object Csrf {

    private const val ALG = "HmacSHA256"

    fun tokenFor(sessionId: String, secret: String): String {
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALG))
        val raw = mac.doFinal(sessionId.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun isValid(sessionId: String?, secret: String, presented: String?): Boolean {
        if (sessionId.isNullOrBlank() || presented.isNullOrBlank()) return false
        val expected = tokenFor(sessionId, secret)
        return constantTimeEquals(expected, presented)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ab.size != bb.size) return false
        var diff = 0
        for (i in ab.indices) diff = diff or (ab[i].toInt() xor bb[i].toInt())
        return diff == 0
    }
}
