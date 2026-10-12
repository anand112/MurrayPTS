package mn.murray.pts.service

import mn.murray.pts.MIN_PASSWORD_LENGTH
import mn.murray.pts.PasswordStatus
import mn.murray.pts.domain.AccessRow
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.Position
import mn.murray.pts.domain.User
import mn.murray.pts.domain.autoEmail
import mn.murray.pts.repository.PositionRepository
import mn.murray.pts.repository.SessionRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.PasswordHasher
import mn.murray.pts.security.Role

/**
 * People / access domain service. Wraps the user, team-member and position repositories
 * with the legacy business rules so routes stay thin:
 *
 *  - passwords are bcrypt-hashed (never stored plaintext),
 *  - `EMP0000` is an undeletable super admin,
 *  - managers may not delete admins,
 *  - a user cannot delete themselves,
 *  - `MIN_PASSWORD_LENGTH` is enforced,
 *  - auto-email `first.last@murray.mn` when none is supplied,
 *  - adding a user also rosters them on the team,
 *  - changing a password revokes that user's sessions.
 *
 * `AccessRow.position` is resolved for display from the linked position's name when the
 * account has one, otherwise from the team-roster position string, else "Team Member".
 */
object UserService {

    private const val SUPER_ADMIN = "EMP0000"

    // ---- reads -------------------------------------------------------------

    fun positions(): List<Position> = PositionRepository.findAll()

    fun team(): List<Member> = TeamRepository.findAll()

    fun users(): List<User> = UserRepository.findAll()

    /** Legacy-shaped access rows for the UI, backed by the DB. Password is never exposed. */
    fun accessRows(): List<AccessRow> {
        val positionsById = PositionRepository.findAll().associateBy { it.id }
        val teamByEmp = TeamRepository.findAll().associateBy { it.employeeNumber }
        return UserRepository.findAll().map { u ->
            val positionName = u.positionId?.let { positionsById[it]?.name }
                ?: teamByEmp[u.employeeNumber]?.position
                ?: "Team Member"
            AccessRow(
                employeeNumber = u.employeeNumber,
                name = u.name,
                position = positionName,
                access = u.access,
                password = "",           // hashed in DB; never surfaced
                email = u.email,
            )
        }
    }

    fun userFor(employeeNumber: String): AccessRow =
        accessRows().firstOrNull { it.employeeNumber == employeeNumber }
            ?: AccessRow(employeeNumber, "Employee $employeeNumber", "Team Member", "Viewer")

    fun effectiveRole(employeeNumber: String): Role = mn.murray.pts.security.Authorization.effectiveRole(employeeNumber)

    // ---- account mutations -------------------------------------------------

    /**
     * Add a user account. [canSetRoles] mirrors the legacy rule: only admins may set
     * position / access / password; managers create basic Viewer accounts. Returns the
     * flash-banner status.
     */
    fun addUser(
        employeeNumber: String,
        name: String,
        email: String,
        requestedPosition: String?,
        requestedAccess: String?,
        newPassword: String,
        canSetRoles: Boolean,
    ): PasswordStatus {
        val empNo = employeeNumber.trim().uppercase()
        if (empNo.isEmpty() || name.isBlank()) return PasswordStatus.EMPTY
        if (UserRepository.findByEmpNo(empNo) != null) return PasswordStatus.USER_EXISTS

        val rosterPosition = TeamRepository.findByEmpNo(empNo)?.position
        val positionName = (if (canSetRoles) requestedPosition?.takeIf { it.isNotEmpty() } else null)
            ?: rosterPosition ?: "Team Member"
        val access = (if (canSetRoles) requestedAccess?.takeIf { it in mn.murray.pts.domain.ACCESS_LEVELS } else null)
            ?: "Viewer"
        val resolvedEmail = email.trim().lowercase().ifBlank { autoEmail(name) }

        // Position catalog link (nullable FK); team_members keeps the free-text position.
        val positionId = PositionRepository.findByName(positionName)?.id

        // Legacy default: an unset password means "password == employee number", forced change.
        val initialPlain = newPassword.ifEmpty { empNo }
        val mustChange = newPassword.isEmpty()

        if (TeamRepository.findByEmpNo(empNo) == null) {
            TeamRepository.upsert(Member(employeeNumber = empNo, name = name, department = "General", position = positionName))
        }
        UserRepository.insert(
            employeeNumber = empNo,
            name = name,
            passwordHash = PasswordHasher.hash(initialPlain),
            access = access,
            email = resolvedEmail,
            positionId = positionId,
            isSuperAdmin = empNo == SUPER_ADMIN,
            mustChangePassword = mustChange,
        )
        return PasswordStatus.USER_ADDED
    }

    /** Delete a user account (and its team row). Enforces the legacy protections. */
    fun deleteUser(employeeNumber: String, callerEmpNo: String, callerRole: Role): PasswordStatus {
        val empNo = employeeNumber.trim().uppercase()
        if (empNo.isEmpty()) return PasswordStatus.EMPTY
        val target = UserRepository.findByEmpNo(empNo)
        val onTeam = TeamRepository.findByEmpNo(empNo) != null
        if (target == null && !onTeam) return PasswordStatus.USER_NOT_FOUND
        if (empNo == SUPER_ADMIN) return PasswordStatus.CANNOT_DELETE_SUPER
        if (empNo == callerEmpNo) return PasswordStatus.CANNOT_DELETE_SELF
        if (callerRole == Role.Manager && target?.access == "Admin") return PasswordStatus.CANNOT_DELETE_ADMIN

        // Sessions cascade-delete via FK; revoke explicitly too so an in-flight cookie dies now.
        SessionRepository.revokeAllFor(empNo)
        UserRepository.delete(empNo)
        TeamRepository.delete(empNo)
        return PasswordStatus.USER_DELETED
    }

    /** Admin sets another user's password. */
    fun setPasswordByAdmin(employeeNumber: String, newPlain: String): PasswordStatus {
        val empNo = employeeNumber.trim().uppercase()
        if (empNo.isEmpty() || newPlain.isEmpty()) return PasswordStatus.EMPTY
        if (UserRepository.findByEmpNo(empNo) == null) return PasswordStatus.USER_NOT_FOUND
        if (newPlain.length < MIN_PASSWORD_LENGTH) return PasswordStatus.TOO_SHORT
        UserRepository.updatePasswordHash(empNo, PasswordHasher.hash(newPlain), mustChange = false)
        SessionRepository.revokeAllFor(empNo)
        return PasswordStatus.CHANGED
    }

    /** Self-service password change: verifies the current password with bcrypt. */
    fun changeOwnPassword(employeeNumber: String, current: String, new: String, confirm: String): PasswordStatus {
        if (current.isEmpty() || new.isEmpty() || confirm.isEmpty()) return PasswordStatus.EMPTY
        val user = UserRepository.findByEmpNo(employeeNumber) ?: return PasswordStatus.WRONG_CURRENT
        if (!PasswordHasher.verify(current, user.passwordHash)) return PasswordStatus.WRONG_CURRENT
        if (new != confirm) return PasswordStatus.MISMATCH
        if (new.length < MIN_PASSWORD_LENGTH) return PasswordStatus.TOO_SHORT
        UserRepository.updatePasswordHash(employeeNumber, PasswordHasher.hash(new), mustChange = false)
        SessionRepository.revokeAllFor(employeeNumber)
        return PasswordStatus.CHANGED
    }

    /** Admin updates a user's position (by name) and/or access level. */
    fun updateUserAccess(employeeNumber: String, positionName: String, access: String) {
        val empNo = employeeNumber.trim().uppercase()
        if (empNo.isEmpty()) return
        if (positionName.isNotEmpty()) {
            val positionId = PositionRepository.findByName(positionName)?.id
            UserRepository.updatePosition(empNo, positionId)
            // Keep the roster's free-text position in sync for display.
            TeamRepository.findByEmpNo(empNo)?.let { m ->
                TeamRepository.upsert(m.copy(position = positionName))
            }
        }
        if (access in mn.murray.pts.domain.ACCESS_LEVELS) {
            UserRepository.updateAccess(empNo, access)
        }
    }

    /** Update a user's email (blank falls back to the auto-generated company email). */
    fun updateEmail(employeeNumber: String, email: String) {
        val empNo = employeeNumber.trim().uppercase()
        val user = UserRepository.findByEmpNo(empNo) ?: return
        val resolved = email.trim().lowercase().ifBlank { autoEmail(user.name) }
        UserRepository.updateEmail(empNo, resolved)
    }

    // ---- position catalog --------------------------------------------------

    fun addPosition(name: String, permissionLevel: String, description: String): Position? {
        if (name.isBlank()) return null
        val id = PositionRepository.insert(name.trim(), permissionLevel, description)
        return PositionRepository.findById(id)
    }

    fun updatePosition(id: Int, name: String, permissionLevel: String, description: String): Position? {
        val existing = PositionRepository.findById(id) ?: return null
        val newName = name.ifBlank { existing.name }
        val newLevel = permissionLevel.takeIf { it in mn.murray.pts.domain.PERMISSION_LEVELS } ?: existing.permissionLevel
        PositionRepository.update(id, newName, newLevel, description)
        return PositionRepository.findById(id)
    }

    fun deletePosition(id: Int) {
        PositionRepository.delete(id)
    }
}
