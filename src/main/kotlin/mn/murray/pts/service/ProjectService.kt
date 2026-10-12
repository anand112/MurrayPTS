package mn.murray.pts.service

import mn.murray.pts.domain.PROJECT_STATUSES
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.Project
import mn.murray.pts.repository.PinRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TeamRepository

/**
 * Application-level operations for the projects + pins aggregate.
 *
 * Owns the legacy validation rules that used to live inline in the route handlers
 * (project type/status whitelists, `SIT|MFG|RMS|LAB-NN` code generation, manager
 * name↔employee-number resolution, roster membership checks) so the routes stay thin
 * and the rules live in exactly one place. Persistence goes through
 * [ProjectRepository] / [PinRepository]; nothing here touches the legacy JSON store.
 */
object ProjectService {

    fun all(): List<Project> = ProjectRepository.findAll()

    fun byId(id: Int): Project? = ProjectRepository.findById(id)

    fun exists(id: Int): Boolean = ProjectRepository.findById(id) != null

    fun existsByName(name: String): Boolean = ProjectRepository.idByName().containsKey(name)

    /** Pinned project ids for an account, in pin order. */
    fun pinnedIds(empNo: String): List<Int> = PinRepository.pinnedProjectIds(empNo)

    /**
     * Create a project. Returns the new id, or null when the name is blank.
     * Type/status fall back to the legacy defaults when the submitted value is not
     * whitelisted; the project code is derived from the type (e.g. `SIT-07`).
     */
    fun create(
        name: String,
        type: String,
        status: String,
        progress: Int,
        managerName: String,
        due: String,
    ): Int? {
        if (name.isBlank()) return null
        val validType = if (type in PROJECT_TYPES) type else "Site projects"
        val validStatus = if (status in PROJECT_STATUSES) status else "Not started"
        val managerEmpNo = managerName.takeIf { it.isNotBlank() }?.let { TeamRepository.empNoByName()[it] }
        val code = ProjectRepository.nextCode(validType)
        return ProjectRepository.insert(
            code = code,
            name = name,
            status = validStatus,
            progress = progress.coerceIn(0, 100),
            managerEmpNo = managerEmpNo,
            due = due,
            type = validType,
        )
    }

    /**
     * Set (or clear, when [managerName] is blank) a project's manager. Returns false when
     * the project is missing or the manager name is not on the team roster — mirroring the
     * legacy guard `manager.isEmpty() || team.any { it.name == manager }`.
     */
    fun setManager(id: Int, managerName: String): Boolean {
        if (!exists(id)) return false
        val empNo = if (managerName.isBlank()) {
            null
        } else {
            TeamRepository.empNoByName()[managerName] ?: return false
        }
        ProjectRepository.setManager(id, empNo)
        return true
    }

    /** Add a roster member as an assignee. Returns false when the project or member is unknown. */
    fun addAssignee(id: Int, empNo: String): Boolean {
        if (empNo.isBlank() || !exists(id)) return false
        if (TeamRepository.findByEmpNo(empNo) == null) return false
        ProjectRepository.addAssignee(id, empNo)
        return true
    }

    /** Remove an assignee. Returns false when the employee number is blank. */
    fun removeAssignee(id: Int, empNo: String): Boolean {
        if (empNo.isBlank()) return false
        ProjectRepository.removeAssignee(id, empNo)
        return true
    }

    /** Toggle a per-user pin. Returns the new pinned state, or null when the project is unknown. */
    fun togglePin(empNo: String, projectId: Int): Boolean? {
        if (!exists(projectId)) return null
        return PinRepository.toggle(empNo, projectId)
    }
}
