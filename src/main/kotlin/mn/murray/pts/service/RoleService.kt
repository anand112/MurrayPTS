package mn.murray.pts.service

import mn.murray.pts.domain.AccessRole
import mn.murray.pts.domain.Permission
import mn.murray.pts.repository.RoleRepository

/** What [RoleService.save] answers: the row as it is now, or the one sentence about the field that stopped it. */
data class RoleSaveResult(val role: AccessRole?, val error: String? = null) {
    val saved: Boolean get() = role != null
}

/**
 * The role catalogue (Stage 110) — the reads the Access control table is drawn from and the one write that
 * changes it.
 *
 * The catalogue is small (four seeded roles plus whatever an account adds), its rows are read twice per page
 * — once for the table, once for each People row's picker — and every rule about a save is a rule about the
 * **name** field, because the chips are a set and any subset of them is a legitimate role. So [save] answers
 * with [RoleSaveResult] rather than with a code: the sentence it carries is the one printed under the box, and
 * it names nothing the reader cannot see.
 *
 * Nothing here asks who is calling. The route holds *Manage roles & permissions* and [RoleRepository] writes
 * what it is told; this object's part is the three refusals a name can earn — blank, already taken, and an id
 * that is not a role — decided in one place so the popup's message and the row it refused cannot disagree.
 */
object RoleService {

    /** The catalogue in its own order — Owner, Administrator, Project Manager, Employee, then any added. */
    fun catalogue(): List<AccessRole> = RoleRepository.findAll()

    /** How many accounts hold each role, for the table's EMPLOYEES column. */
    fun counts(): Map<Int, Int> = RoleRepository.counts()

    /** One role by id, for the opener that names it in the address; null for 0 and for a row that has gone. */
    fun byId(id: Int): AccessRole? = if (id > 0) RoleRepository.findById(id) else null

    /**
     * Save one role: [roleId] 0 creates, any other value rewrites that row's name and chips.
     *
     * A blank name, one the catalogue already carries, and an id belonging to no role all answer with a reason
     * and write nothing. A rename that lands changes the row every account points at, so everybody holding the
     * role reads the new set of chips at their next request — the change is not copied onto their rows.
     */
    fun save(roleId: Int, name: String, permissions: Set<Permission>): RoleSaveResult {
        val wanted = name.trim()
        if (wanted.isEmpty()) return RoleSaveResult(null, "A role needs a name.")
        val editing = if (roleId > 0) RoleRepository.findById(roleId) else null
        if (roleId > 0 && editing == null) {
            // The popup was opened on a row that is gone — a second tab saved the delete while this one was up.
            // It is not a create: the reader named a role, and answering by adding a new row would leave them
            // with two of them. The sentence is about the row, not the name typed into the box.
            return RoleSaveResult(null, "That role is no longer on the catalogue.")
        }
        if (editing == null && RoleRepository.findByName(wanted) != null) {
            return RoleSaveResult(null, "That role name is taken.")
        }
        if (editing != null && RoleRepository.findByName(wanted)?.id?.let { it != editing.id } == true) {
            return RoleSaveResult(null, "That role name is taken.")
        }
        val saved = editing?.let { RoleRepository.update(it.id, wanted, permissions) }
            ?: RoleRepository.create(wanted, permissions)
        return if (saved != null) RoleSaveResult(saved) else RoleSaveResult(null, "That role name is taken.")
    }
}
