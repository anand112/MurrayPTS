package mn.murray.pts.security

import mn.murray.pts.domain.Position
import mn.murray.pts.domain.User
import mn.murray.pts.repository.PositionRepository
import mn.murray.pts.repository.UserRepository

/**
 * Central authorization decisions ("RoleGuard").
 *
 * v2 change (locked decision): a user's effective role now derives from the linked
 * position's `permissionLevel`, falling back to the account's own `access` value when
 * the user has no position. Previously `permissionLevel` was stored but ignored and
 * `access` was the only effective role — so for users who have a position, the effective
 * role may differ from the legacy behaviour. Super admins are always [Role.Admin].
 *
 * Resolution order:
 *   1. `isSuperAdmin`            -> Admin
 *   2. position.permissionLevel -> that role (when the user has a resolvable position)
 *   3. user.access              -> that role (fallback)
 *   4. otherwise                -> Viewer
 */
object Authorization {

    /** Effective role for an already-loaded [user], resolving its position from [positionsById]. */
    fun effectiveRole(user: User, positionsById: Map<Int, Position> = emptyMap()): Role {
        if (user.isSuperAdmin) return Role.Admin
        val positionRole = user.positionId?.let { positionsById[it] }?.let { Role.from(it.permissionLevel) }
        return positionRole ?: Role.from(user.access)
    }

    /** Convenience: load the user + positions from the repositories and resolve the role. */
    fun effectiveRole(employeeNumber: String): Role {
        val user = UserRepository.findByEmpNo(employeeNumber) ?: return Role.Viewer
        val positions = user.positionId?.let { PositionRepository.findById(it) }
        val map = if (positions != null) mapOf(positions.id to positions) else emptyMap()
        return effectiveRole(user, map)
    }

    fun canManage(employeeNumber: String): Boolean = effectiveRole(employeeNumber).atLeast(Role.Manager)
    fun canAdmin(employeeNumber: String): Boolean = effectiveRole(employeeNumber).atLeast(Role.Admin)

    /**
     * Legacy compatibility helper. The pre-v2 app gated management UIs on
     * `access in {"Admin","Manager"}`. This mirrors that check but through the new
     * position-aware role resolution, so route code can migrate one call at a time.
     */
    fun isManagerOrAdmin(employeeNumber: String): Boolean = canManage(employeeNumber)
}
