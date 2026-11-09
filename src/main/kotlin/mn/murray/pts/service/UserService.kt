package mn.murray.pts.service

import mn.murray.pts.MIN_PASSWORD_LENGTH
import mn.murray.pts.PasswordStatus
import mn.murray.pts.domain.AccessRole
import mn.murray.pts.domain.AccessRow
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.Permission
import mn.murray.pts.domain.Position
import mn.murray.pts.domain.User
import mn.murray.pts.domain.autoEmail
import mn.murray.pts.repository.PositionRepository
import mn.murray.pts.repository.RoleRepository
import mn.murray.pts.repository.SessionRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.Authorization
import mn.murray.pts.security.PasswordHasher

/**
 * People / access domain service. Wraps the user, team-member and position repositories
 * with the legacy business rules so routes stay thin:
 *
 *  - passwords are bcrypt-hashed (never stored plaintext),
 *  - `EMP0000` is an undeletable super admin,
 *  - managers may not delete admins,
 *  - a user cannot delete themselves,
 *  - `MIN_PASSWORD_LENGTH` is enforced,
 *  - auto-email `firstname.surname@murrays.mn` when none is supplied,
 *  - adding a user also rosters them on the team,
 *  - changing a password revokes that user's sessions.
 *
 * `AccessRow.position` is resolved for display from the linked position's name when the
 * account has one, otherwise from the team-roster position string, else "Team Member".
 */
object UserService {

    /** The one account the app will not let anybody take off the sheet — see [deleteUser]. */
    const val SUPER_ADMIN = "EMP0000"

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
                role = Authorization.roleOf(u).name,
            )
        }
    }

    fun userFor(employeeNumber: String): AccessRow =
        accessRows().firstOrNull { it.employeeNumber == employeeNumber }
            ?: AccessRow(employeeNumber, "Employee $employeeNumber", "Team Member", "Viewer")

    /** The role this account signs in under (Stage 110). */
    fun roleOf(employeeNumber: String): AccessRole = Authorization.roleOf(employeeNumber)

    // ---- account mutations -------------------------------------------------

    /**
     * Add a user account. [canSetRoles] is the reader's *Manage roles & permissions* right: only then may the
     * new account be filed under a role other than the default. [canSetProfile] is *Manage employees*: only
     * then may its position (a job title) and first password be chosen here; otherwise the account starts as
     * the default role with its employee number as password, as one added by a colleague always has.
     * [canSetDepartment] is the Owner/Administrator pair's (Stage 117): only then is [requestedDepartment]
     * written onto the roster row, and an account created for somebody already rostered takes it too.
     */
    fun addUser(
        employeeNumber: String,
        name: String,
        email: String,
        requestedPosition: String?,
        requestedRole: String?,
        newPassword: String,
        canSetRoles: Boolean,
        canSetProfile: Boolean = canSetRoles,
        requestedDepartment: String? = null,
        canSetDepartment: Boolean = false,
        /**
         * The super admin alone (Stage 123): another person's first password. Without it the posted word is
         * ignored and the account starts on its employee number, exactly like one added by a colleague.
         */
        canSetPassword: Boolean = canSetProfile,
    ): PasswordStatus {
        val empNo = employeeNumber.trim().uppercase()
        if (empNo.isEmpty() || name.isBlank()) return PasswordStatus.EMPTY
        if (UserRepository.findByEmpNo(empNo) != null) return PasswordStatus.USER_EXISTS

        val rosterPosition = TeamRepository.findByEmpNo(empNo)?.position
        val positionName = (if (canSetProfile) requestedPosition?.takeIf { it.isNotEmpty() } else null)
            ?: rosterPosition ?: "Team Member"
        val role = (if (canSetRoles) requestedRole?.trim()?.let { RoleRepository.findByName(it) } else null)
            ?: RoleRepository.defaultRole()
        val access = RoleRepository.legacyAccessWord(role.name)
        val resolvedEmail = email.trim().lowercase().ifBlank { autoEmail(name) }

        // Position catalog link (nullable FK); team_members keeps the free-text position.
        val positionId = PositionRepository.findByName(positionName)?.id

        // Legacy default: an unset password means "password == employee number", forced change. Only the
        // super admin may hand out a different first password (Stage 123); for anybody else the posted
        // word is treated as though the field had been left blank.
        val chosen = if (canSetPassword) newPassword else ""
        val initialPlain = chosen.ifEmpty { empNo }
        val mustChange = chosen.isEmpty()

        val department = (if (canSetDepartment) requestedDepartment?.trim() else null).orEmpty()
        val roster = TeamRepository.findByEmpNo(empNo)
        if (roster == null) {
            // A department is written only by the Owner/Administrator pair; anyone else's new person is
            // filed without one and shows as "Unassigned" until somebody with the right assigns them.
            TeamRepository.upsert(Member(employeeNumber = empNo, name = name, department = department, position = positionName))
        } else if (department.isNotEmpty() && department != roster.department) {
            // The person was already on the roster without a login (the pencil on their row creates one),
            // so the chosen department lands on that row rather than being dropped with the create.
            TeamRepository.upsert(roster.copy(department = department))
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
            roleId = role.id.takeIf { it > 0 },
        )
        return PasswordStatus.USER_ADDED
    }

    /**
     * Delete a user account (and its team row). Enforces the protections: the super admin and yourself are
     * never the target, and an account without *Manage roles & permissions* may not delete one that holds it.
     */
    fun deleteUser(employeeNumber: String, callerEmpNo: String, callerCanAdmin: Boolean): PasswordStatus {
        val empNo = employeeNumber.trim().uppercase()
        if (empNo.isEmpty()) return PasswordStatus.EMPTY
        val target = UserRepository.findByEmpNo(empNo)
        val onTeam = TeamRepository.findByEmpNo(empNo) != null
        if (target == null && !onTeam) return PasswordStatus.USER_NOT_FOUND
        if (empNo == SUPER_ADMIN) return PasswordStatus.CANNOT_DELETE_SUPER
        if (empNo == callerEmpNo) return PasswordStatus.CANNOT_DELETE_SELF
        if (!callerCanAdmin && target != null && Authorization.roleOf(target).may(Permission.MANAGE_ROLES)) {
            return PasswordStatus.CANNOT_DELETE_ADMIN
        }

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

    /**
     * A reader changes their *own* password (Stage 123): they prove the current one, type the new one twice,
     * and — because [AuthService.changePassword] revokes every session — every other browser is signed out
     * while the route that calls this issues one fresh session back to the browser doing the change.
     *
     * The checks are in the order the form reads: nothing typed, then the wrong current password, then the
     * two new words disagreeing, then a new word too short — each answering with the field it belongs to.
     */
    fun changeOwnPassword(
        employeeNumber: String,
        current: String,
        new: String,
        confirm: String,
    ): PasswordStatus {
        val empNo = employeeNumber.trim().uppercase()
        if (current.isEmpty() || new.isEmpty() || confirm.isEmpty()) return PasswordStatus.EMPTY
        val account = UserRepository.findByEmpNo(empNo) ?: return PasswordStatus.USER_NOT_FOUND
        if (!PasswordHasher.verify(current, account.passwordHash)) return PasswordStatus.WRONG_CURRENT
        if (new != confirm) return PasswordStatus.MISMATCH
        if (new.length < MIN_PASSWORD_LENGTH) return PasswordStatus.TOO_SHORT
        AuthService.changePassword(empNo, new, mustChange = false)
        return PasswordStatus.CHANGED
    }

    /**
     * Update a user's position (a job title, by name) and/or the role they are filed under, and — for the
     * Owner/Administrator pair — the department on the roster row (Stage 117).
     *
     * Blank position and blank department mean "leave it": the popup's Position picker follows the roster
     * when it is not touched, and an edit that did not open the department picker must not erase an old
     * free-text word the roster already prints.
     */
    fun updateUserProfile(
        employeeNumber: String,
        positionName: String,
        roleName: String,
        departmentName: String = "",
    ) {
        val empNo = employeeNumber.trim().uppercase()
        if (empNo.isEmpty()) return
        if (positionName.isNotEmpty()) {
            val positionId = PositionRepository.findByName(positionName)?.id
            UserRepository.updatePosition(empNo, positionId)
        }
        if (positionName.isNotEmpty() || departmentName.isNotEmpty()) {
            // Keep the roster's free-text job and department in sync for display.
            TeamRepository.findByEmpNo(empNo)?.let { m ->
                TeamRepository.upsert(
                    m.copy(
                        position = positionName.ifEmpty { m.position },
                        department = departmentName.ifEmpty { m.department },
                    ),
                )
            }
        }
        RoleRepository.findByName(roleName)?.let { role ->
            RoleRepository.setRole(empNo, role.id)
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
