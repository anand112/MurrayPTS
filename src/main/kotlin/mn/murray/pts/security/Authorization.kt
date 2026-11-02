package mn.murray.pts.security

import mn.murray.pts.domain.AccessRole
import mn.murray.pts.domain.Permission
import mn.murray.pts.domain.User
import mn.murray.pts.repository.RoleRepository

/**
 * Central authorization decisions (Stage 110).
 *
 * Until this stage an account's rights were three ranks — Viewer < Manager < Admin — resolved from the
 * super-admin flag, the account's position level and then its own `access` word. The picture the user
 * attached to Access control replaced that with **roles and permissions**: thirteen named chips, a role
 * holding any set of them, and every account filed under one role. So this object now answers exactly two
 * questions:
 *
 *  * **[canDo]** — does this account's role hold this permission?
 *  * **[canAny]** — does it hold at least one of these?
 *
 * and one convenience pair kept for the page-level flag every renderer already takes:
 *
 *  * **[canManage]** — the account holds any board/project right (all the chips except *View teammates'
 *    tasks*). It is not itself a permission: a route still asks for the chip it is about.
 *  * **[canAdmin]** — the account holds *Manage employees* or *Manage roles & permissions*, the two
 *    people-facing chips.
 *
 * The role is read through [RoleRepository], which falls back to the default (Employee) when an account
 * carries no role at all — so "no role" is never "no rights" and never "all rights".
 *
 * **Route guards use the chip, not the convenience flags.** `canManage` exists because a dozen renderers
 * take one boolean for "draw the board as an editor"; the POST behind each of those renderers asks
 * [canDo] for the permission the control is about, so a custom role that holds one chip gets exactly the
 * one power that chip names.
 */
object Authorization {

    /** Every chip except *View teammates' tasks*: the rights that let somebody reshape shared work. */
    val MANAGEMENT: Set<Permission> = Permission.entries.toSet() - Permission.VIEW_TEAMMATES_TASKS

    /** The two chips that decide people: managing accounts, and managing the roles they sign in under. */
    val ADMINISTRATION: Set<Permission> = setOf(Permission.MANAGE_EMPLOYEES, Permission.MANAGE_ROLES)

    /** The chips whose panels live on Settings — the whole reason that page is opened at all. */
    val SETTINGS: Set<Permission> =
        setOf(Permission.MANAGE_EMPLOYEES, Permission.MANAGE_PROJECT_TYPES, Permission.MANAGE_EMAIL_SETTINGS)

    /** The role an account signs in under, already loaded with its chips. */
    fun roleOf(employeeNumber: String): AccessRole = RoleRepository.roleOf(employeeNumber)

    /** The role for an account read earlier, for callers that already hold the row. */
    fun roleOf(user: User?): AccessRole = RoleRepository.roleFor(user)

    fun permissionsOf(employeeNumber: String): Set<Permission> = roleOf(employeeNumber).permissions

    /** Does this account hold [permission]? Unknown accounts answer through the default role. */
    fun canDo(employeeNumber: String, permission: Permission): Boolean =
        roleOf(employeeNumber).may(permission)

    /** Does this account hold at least one of [permissions]? */
    fun canAny(employeeNumber: String, permissions: Collection<Permission>): Boolean =
        roleOf(employeeNumber).mayAny(permissions)

    /** Any board/project right — the one boolean the content renderers take. Not a route guard. */
    fun canManage(employeeNumber: String): Boolean = canAny(employeeNumber, MANAGEMENT)

    /** *Manage employees* or *Manage roles & permissions* — the people-facing pair. */
    fun canAdmin(employeeNumber: String): Boolean = canAny(employeeNumber, ADMINISTRATION)
}
