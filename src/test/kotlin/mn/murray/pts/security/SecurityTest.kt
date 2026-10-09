package mn.murray.pts.security

import mn.murray.pts.domain.Position
import mn.murray.pts.domain.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleTest {

    @Test
    fun `roles are ordered and parse case-insensitively`() {
        assertTrue(Role.Admin.atLeast(Role.Manager))
        assertTrue(Role.Manager.atLeast(Role.Viewer))
        assertFalse(Role.Viewer.atLeast(Role.Manager))
        assertEquals(Role.Admin, Role.from("admin"))
        assertEquals(Role.Viewer, Role.from("nonsense"))
        assertEquals(Role.Viewer, Role.from(null))
    }
}

class AuthorizationTest {

    private fun user(
        access: String,
        positionId: Int? = null,
        superAdmin: Boolean = false,
    ) = User(
        employeeNumber = "E1", name = "Test", passwordHash = "x", access = access,
        email = "t@murray.mn", positionId = positionId, isSuperAdmin = superAdmin,
        mustChangePassword = false, createdAt = "", updatedAt = "",
    )

    private val managerPosition = Position(id = 10, name = "Site Manager", permissionLevel = "Manager")
    private val adminPosition = Position(id = 11, name = "Ops Admin", permissionLevel = "Admin")

    @Test
    fun `super admin is always Admin regardless of position or access`() {
        val u = user(access = "Viewer", positionId = 10, superAdmin = true)
        assertEquals(Role.Admin, Authorization.effectiveRole(u, mapOf(10 to managerPosition)))
    }

    @Test
    fun `position permissionLevel wins over account access`() {
        // Legacy behaviour would have used access=Viewer; v2 derives Manager from the position.
        val u = user(access = "Viewer", positionId = 10)
        assertEquals(Role.Manager, Authorization.effectiveRole(u, mapOf(10 to managerPosition)))
    }

    @Test
    fun `falls back to account access when no position is linked`() {
        assertEquals(Role.Admin, Authorization.effectiveRole(user(access = "Admin"), emptyMap()))
        assertEquals(Role.Manager, Authorization.effectiveRole(user(access = "Manager"), emptyMap()))
    }

    @Test
    fun `unresolvable position id falls back to account access`() {
        val u = user(access = "Viewer", positionId = 999)
        assertEquals(Role.Viewer, Authorization.effectiveRole(u, mapOf(10 to managerPosition)))
    }

    @Test
    fun `admin position grants admin`() {
        val u = user(access = "Viewer", positionId = 11)
        assertEquals(Role.Admin, Authorization.effectiveRole(u, mapOf(11 to adminPosition)))
    }
}

class LoginRateLimiterTest {

    @Test
    fun `blocks after max attempts within the window and recovers after it passes`() {
        var now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val limiter = LoginRateLimiter(maxAttempts = 3, windowSeconds = 60, now = { now })

        assertFalse(limiter.isBlocked("ip1"))
        limiter.recordFailure("ip1")
        limiter.recordFailure("ip1")
        assertFalse(limiter.isBlocked("ip1"))
        limiter.recordFailure("ip1")
        assertTrue(limiter.isBlocked("ip1"))
        assertTrue(limiter.retryAfterSeconds("ip1") in 1..60)

        // Other keys are independent.
        assertFalse(limiter.isBlocked("ip2"))

        // Advance beyond the window -> attempts age out.
        now = now.plusSeconds(61)
        assertFalse(limiter.isBlocked("ip1"))
    }

    @Test
    fun `reset clears history`() {
        val limiter = LoginRateLimiter(maxAttempts = 1, windowSeconds = 60)
        limiter.recordFailure("ip")
        assertTrue(limiter.isBlocked("ip"))
        limiter.reset("ip")
        assertFalse(limiter.isBlocked("ip"))
    }
}

class CsrfTest {

    private val secret = "test-secret-value"

    @Test
    fun `token is stable for a session and validates`() {
        val t1 = Csrf.tokenFor("session-abc", secret)
        val t2 = Csrf.tokenFor("session-abc", secret)
        assertEquals(t1, t2)
        assertTrue(Csrf.isValid("session-abc", secret, t1))
    }

    @Test
    fun `token from another session or secret is rejected`() {
        val t = Csrf.tokenFor("session-abc", secret)
        assertFalse(Csrf.isValid("session-xyz", secret, t))
        assertFalse(Csrf.isValid("session-abc", "other-secret", t))
    }

    @Test
    fun `blank inputs are rejected`() {
        assertFalse(Csrf.isValid(null, secret, "x"))
        assertFalse(Csrf.isValid("", secret, "x"))
        assertFalse(Csrf.isValid("session-abc", secret, null))
        assertFalse(Csrf.isValid("session-abc", secret, ""))
    }
}
