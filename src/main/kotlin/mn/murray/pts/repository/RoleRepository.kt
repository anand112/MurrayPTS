package mn.murray.pts.repository

import mn.murray.pts.db.RolePermissions
import mn.murray.pts.db.Roles
import mn.murray.pts.db.Users
import mn.murray.pts.domain.AccessRole
import mn.murray.pts.domain.AccessRoles
import mn.murray.pts.domain.Permission
import mn.murray.pts.domain.User
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Data access for the role catalogue (V21, Stage 110): the rows in `roles`, the chips in
 * `role_permissions`, and the role an account is filed under.
 *
 * A role is read as one [AccessRole] — its name, its order and its whole set of [Permission]s — because
 * every caller asks the same question ("what may this role do?") and a half-loaded role is a right waiting
 * to be forgotten. The chips are fetched for the whole catalogue in one query rather than once per role, so
 * the Access control table's thirteen rows cost two reads and not fourteen.
 *
 * Nothing here re-checks who may write. The two routes that change a role hold [Permission.MANAGE_ROLES];
 * this object's job is to write what it is told and to answer null when the row it was told about is gone.
 */
object RoleRepository {

    private fun map(r: ResultRow, chips: Map<Int, Set<Permission>>) = AccessRole(
        id = r[Roles.id],
        name = r[Roles.name],
        permissions = chips[r[Roles.id]].orEmpty(),
        sortOrder = r[Roles.sortOrder],
        isDefault = r[Roles.isDefault],
    )

    /** Every role's chips, keyed by role id — the one read every catalogue method starts from. */
    private fun chipsByRole(): Map<Int, Set<Permission>> =
        RolePermissions.selectAll()
            .groupBy({ it[RolePermissions.roleId] }) { row -> row[RolePermissions.permission] }
            .mapValues { (_, keys) -> Permission.ofAll(keys) }

    /** The catalogue in its own order: Owner, Administrator, Project Manager, Employee, then any added. */
    fun findAll(): List<AccessRole> = transaction {
        val chips = chipsByRole()
        Roles.selectAll()
            .orderBy(Roles.sortOrder to SortOrder.ASC, Roles.id to SortOrder.ASC)
            .map { map(it, chips) }
    }

    fun findById(id: Int): AccessRole? = transaction {
        val chips = chipsByRole()
        Roles.selectAll().where { Roles.id eq id }.firstOrNull()?.let { map(it, chips) }
    }

    /** A role by name, case- and space-insensitively — the way the sheet and a form name it. */
    fun findByName(name: String): AccessRole? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        return findAll().firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    /**
     * The role an account with no `role_id` falls back to: the seeded Employee. A database whose catalogue
     * somehow has no default answers with the first row (or, if empty, the in-code Employee) rather than
     * with nothing, so "no role" can never be read as "no rights" and lock the last account out.
     */
    fun defaultRole(): AccessRole {
        val all = findAll()
        return all.firstOrNull { it.isDefault }
            ?: all.firstOrNull()
            ?: AccessRole(0, AccessRoles.EMPLOYEE, AccessRoles.EMPLOYEE_PERMISSIONS, isDefault = true)
    }

    /**
     * The role this account signs in under. A null `role_id` — a row written by hand, or the instant before
     * V21's backfill — answers the default role; an account that does not exist answers it too, which is what
     * a route that has already been refused would have used anyway.
     */
    fun roleFor(user: User?): AccessRole =
        user?.roleId?.let(::findById) ?: defaultRole()

    fun roleOf(employeeNumber: String): AccessRole = roleFor(UserRepository.findByEmpNo(employeeNumber))

    fun permissionsOf(roleId: Int): Set<Permission> = findById(roleId)?.permissions.orEmpty()

    /**
     * How many accounts hold each role, for the EMPLOYEES column of the picture.
     *
     * Accounts with a null role are counted under the default, because that is the role they actually sign
     * in under ([roleFor]); counting them nowhere would print a total smaller than the roster.
     */
    fun counts(): Map<Int, Int> = transaction {
        val fallback = defaultRole().id
        Users.selectAll()
            .groupBy { it[Users.roleId] ?: fallback }
            .mapValues { (_, rows) -> rows.size }
    }

    /**
     * Add a role at the end of the catalogue. Null when the name is blank or already taken — the two
     * refusals the sheet prints under the name field, decided here so a hand-built POST cannot make a
     * second "Administrator".
     */
    fun create(name: String, permissions: Set<Permission>): AccessRole? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        if (findByName(wanted) != null) return null
        val id = transaction {
            val last = Roles.selectAll().maxOfOrNull { it[Roles.sortOrder] } ?: 0
            val newId = Roles.insert {
                it[Roles.name] = wanted
                it[sortOrder] = last + 1
                it[isDefault] = false
            } get Roles.id
            replaceChips(newId, permissions)
            newId
        }
        return findById(id)
    }

    /**
     * Rename a role and rewrite its chips in one transaction. Null when the role is gone, or when the new
     * name is blank or belongs to a different role.
     *
     * The rename is carried into nothing else: accounts point at the row by id, so everybody holding it keeps
     * their rights and merely reads the new word on the sheet.
     */
    fun update(id: Int, name: String, permissions: Set<Permission>): AccessRole? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        if (findById(id) == null) return null
        if (findByName(wanted)?.id?.let { it != id } == true) return null
        transaction {
            Roles.update({ Roles.id eq id }) { it[Roles.name] = wanted }
            replaceChips(id, permissions)
        }
        return findById(id)
    }

    private fun replaceChips(roleId: Int, permissions: Set<Permission>) {
        RolePermissions.deleteWhere { RolePermissions.roleId eq roleId }
        permissions.sortedBy { it.ordinal }.forEach { permission ->
            RolePermissions.insert {
                it[RolePermissions.roleId] = roleId
                it[RolePermissions.permission] = permission.key
            }
        }
    }

    /**
     * File one account under [roleId]. False when that role is not on the catalogue, in which case nothing
     * is written — a role id is not a free-form value and an unknown one would leave the account falling
     * back to the default without anybody being told.
     *
     * The legacy `users.access` word is kept in step as a label (Owner/Administrator read as Admin, Project
     * Manager as Manager, everything else as Viewer). Nothing authorizes off it since V21; it is written so
     * a database read by the old JSON importer or by an operator's SQL still says something true.
     */
    fun setRole(employeeNumber: String, roleId: Int): Boolean {
        val role = findById(roleId) ?: return false
        val empNo = employeeNumber.trim().uppercase()
        if (empNo.isEmpty()) return false
        transaction {
            Users.update({ Users.employeeNumber eq empNo }) {
                it[Users.roleId] = role.id
                it[Users.access] = legacyAccessWord(role.name)
            }
        }
        return true
    }

    /** The `users.access` word a role has always been printed as, for rows written before V21. */
    fun legacyAccessWord(roleName: String): String = when (roleName.trim().lowercase()) {
        AccessRoles.OWNER.lowercase(), AccessRoles.ADMINISTRATOR.lowercase() -> "Admin"
        AccessRoles.PROJECT_MANAGER.lowercase() -> "Manager"
        else -> "Viewer"
    }

    /**
     * The name of the role an old `access` word (and super-admin flag) resolves to — the same mapping V21's
     * backfill applies, for rows created in code after the migration: a test that inserts an `Admin` account
     * still gets the Administrator's rights without having to name a role.
     */
    fun legacyRoleName(access: String?, isSuperAdmin: Boolean): String {
        if (isSuperAdmin) return AccessRoles.OWNER
        return when (access?.trim()?.lowercase()) {
            "admin" -> AccessRoles.ADMINISTRATOR
            "manager" -> AccessRoles.PROJECT_MANAGER
            else -> AccessRoles.EMPLOYEE
        }
    }

    /** The id for [legacyRoleName], or null when even the seeded catalogue is missing. */
    fun legacyRoleId(access: String?, isSuperAdmin: Boolean): Int? =
        findByName(legacyRoleName(access, isSuperAdmin))?.id
}
