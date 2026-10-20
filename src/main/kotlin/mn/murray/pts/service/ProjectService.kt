package mn.murray.pts.service

import mn.murray.pts.domain.PROJECT_STATUSES
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.TYPE_PREFIX
import mn.murray.pts.domain.codeMatchesType
import mn.murray.pts.domain.isValidProjectCode
import mn.murray.pts.domain.normalizeProjectCode
import mn.murray.pts.repository.PinRepository
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Application-level operations for the projects + pins aggregate.
 *
 * Owns the validation rules for creating a project — category and status whitelists,
 * project-number format / uniqueness / category-letter match, ISO date ordering, manager
 * roster membership and revenue parsing — so the routes stay thin and the rules live in exactly one
 * place. [create] takes the raw [ProjectDraft] straight
 * off the form and answers with either a new id or the field-keyed errors the modal renders
 * under each input. Persistence goes through [ProjectRepository] / [PinRepository] /
 * [ProjectBucketRepository]; nothing here touches the legacy JSON store.
 */
object ProjectService {

    /** The status a freshly created project gets when the form does not choose one. */
    const val DEFAULT_STATUS = "Not started"

    fun all(): List<Project> = ProjectRepository.findAll()

    fun byId(id: Int): Project? = ProjectRepository.findById(id)

    fun exists(id: Int): Boolean = ProjectRepository.findById(id) != null

    fun existsByName(name: String): Boolean = ProjectRepository.idByName().containsKey(name)

    /** Pinned project ids for an account, in pin order. */
    fun pinnedIds(empNo: String): List<Int> = PinRepository.pinnedProjectIds(empNo)

    /** The number to pre-fill the create form with, per category. */
    fun suggestedCodes(): Map<String, String> = ProjectRepository.suggestedCodes()

    /**
     * Create a project from the modal form. Returns a [CreateProjectResult]: `projectId`
     * is non-null and `errors` is empty on success; on failure nothing is written and
     * `errors` names the field each message belongs under.
     */
    fun create(draft: ProjectDraft, actorEmpNo: String = ""): CreateProjectResult {
        val errors = LinkedHashMap<ProjectField, String>()
        val code = normalizeProjectCode(draft.code)
        val type = draft.type.trim()
        val name = draft.name.trim()
        val client = draft.client.trim()
        val start = draft.start.trim()
        val end = draft.end.trim()
        val status = if (draft.status in PROJECT_STATUSES) draft.status else DEFAULT_STATUS

        if (type !in PROJECT_TYPES) errors += ProjectField.TYPE to "Choose a project category."

        when {
            code.isBlank() -> errors += ProjectField.CODE to "Enter a project number."
            !isValidProjectCode(code) ->
                errors += ProjectField.CODE to "Use the category letter, a dash and a number, like S-01."
            type in PROJECT_TYPES && !codeMatchesType(code, type) ->
                errors += ProjectField.CODE to "Start the number with ${TYPE_PREFIX[type]}- for ${type.lowercase()}."
            ProjectRepository.existingCodes().contains(code) ->
                errors += ProjectField.CODE to "Project number $code is already used."
        }

        if (name.isBlank()) errors += ProjectField.NAME to "Enter a short description of the project."

        if (start.isNotEmpty() && !isIsoDate(start)) errors += ProjectField.START to "Use the date picker (yyyy-mm-dd)."
        when {
            end.isNotEmpty() && !isIsoDate(end) -> errors += ProjectField.END to "Use the date picker (yyyy-mm-dd)."
            isIsoDate(start) && isIsoDate(end) && end < start ->
                errors += ProjectField.END to "The end date cannot be before the start date."
        }

        val managerName = draft.managerName.trim()
        var managerEmpNo: String? = null
        if (managerName.isNotEmpty()) {
            managerEmpNo = TeamRepository.empNoByName()[managerName]
            if (managerEmpNo == null) {
                errors += ProjectField.MANAGER to "Choose a manager from the team list, or leave it empty."
            }
        }

        val revenue = parseRevenue(draft.revenue)
        if (revenue == null && draft.revenue.trim().isNotEmpty()) {
            errors += ProjectField.REVENUE to "Enter revenue as a number, like 1250000."
        }

        if (errors.isNotEmpty()) return CreateProjectResult(null, errors, draft)

        val id = ProjectRepository.insert(
            code = code,
            name = name,
            status = status,
            progress = 0,
            managerEmpNo = managerEmpNo,
            due = end,                       // `due` is the end date — see V3__project_categories.sql
            type = type,
            client = client,
            start = start,
            revenue = revenue ?: 0.0,
        )
        // A project arrives with the six standard sub-categories already on it — the same families the Projects
        // board cuts itself by — and the manager of the project adds to them from the page. The create form does
        // not ask for lane names, because a new project is not a form somebody has to understand before they can
        // use it, and because a card cannot be written until its board exists: the six being fixed is what lets
        // a card be *filed* rather than left unplaced (see DEFAULT_PROJECT_BUCKETS and V10).
        ProjectBucketRepository.seedDefaults(id)
        // On top of the six, a category's own defaults (V12) land on the board — positions 6 onwards, in the
        // order management added them to the catalogue on Settings. A category with none writes nothing, so a
        // project is exactly the six-lane board it was before the table existed. See BucketDefaultsService:
        // this is the only place the catalogue reaches a board, and it never reaches one that already exists.
        TypeDefaultBucketRepository.seedExtras(id, type)
        NotificationService.projectManagerAssigned(id, actorEmpNo)
        return CreateProjectResult(id, emptyMap(), draft)
    }

    /**
     * Set (or clear, when [managerName] is blank) a project's manager. Returns false when
     * the project is missing or the manager name is not on the team roster — mirroring the
     * legacy guard `manager.isEmpty() || team.any { it.name == manager }`.
     *
     * The new manager hears about it ([NotificationService.projectManagerAssigned]), but only when
     * the assignment actually changed hands — re-pressing "Set manager" with the same name on file
     * must not mail anyone a second time.
     */
    fun setManager(id: Int, managerName: String, actorEmpNo: String = ""): Boolean {
        if (!exists(id)) return false
        val empNo = if (managerName.isBlank()) {
            null
        } else {
            TeamRepository.empNoByName()[managerName] ?: return false
        }
        val previous = ProjectRepository.managerOf(id)
        ProjectRepository.setManager(id, empNo)
        if (empNo != null && empNo != previous) {
            NotificationService.projectManagerAssigned(id, actorEmpNo)
        }
        return true
    }

    /** Toggle a per-user pin. Returns the new pinned state, or null when the project is unknown. */
    fun togglePin(empNo: String, projectId: Int): Boolean? {
        if (!exists(projectId)) return null
        return PinRepository.toggle(empNo, projectId)
    }

    /**
     * Save an edit made on the project page's own header strip (Stage 60), management-only at the route.
     *
     * It reuses every rule [create] enforces — category whitelist, required description, ISO dates with the end
     * not before the start, a manager who is on the roster, and non-negative numeric revenue and invoiced — and
     * answers the same way: nothing is written and [UpdateProjectResult.errors] names the field each message
     * belongs under, so the strip re-renders with the value that was typed still in it and the complaint beneath
     * the input. Two fields are deliberately **not** in this form:
     *
     *   * the **project number** never changes, so it is not re-validated against a category here — the breadcrumb
     *     prints it as a fixed reference and editing the category does not re-code a project mid-flight;
     *   * **progress** is not a thing anybody types any more; it is what the cards' checklists add up to
     *     ([mn.murray.pts.service.TaskService.displayedProgress]), so an edit leaves the stored number alone
     *     and the bar is recomputed from the board the next time the page reads.
     *
     * [ProjectRepository.update] writes the whole row, so `progress` is passed back as the value already on it
     * rather than reset to zero. When the manager changes hands the new one is told, exactly as on [setManager].
     */
    fun update(id: Int, draft: ProjectDraft, actorEmpNo: String = ""): UpdateProjectResult {
        val existing = ProjectRepository.findById(id)
            ?: return UpdateProjectResult(false, mapOf(ProjectField.NAME to "This project no longer exists."), draft)
        val errors = LinkedHashMap<ProjectField, String>()
        val type = draft.type.trim()
        val name = draft.name.trim()
        val client = draft.client.trim()
        val start = draft.start.trim()
        val end = draft.end.trim()
        val status = if (draft.status in PROJECT_STATUSES) draft.status else DEFAULT_STATUS

        if (type !in PROJECT_TYPES) errors += ProjectField.TYPE to "Choose a project category."
        if (name.isBlank()) errors += ProjectField.NAME to "Enter a short description of the project."

        if (start.isNotEmpty() && !isIsoDate(start)) errors += ProjectField.START to "Use the date picker (yyyy-mm-dd)."
        when {
            end.isNotEmpty() && !isIsoDate(end) -> errors += ProjectField.END to "Use the date picker (yyyy-mm-dd)."
            isIsoDate(start) && isIsoDate(end) && end < start ->
                errors += ProjectField.END to "The end date cannot be before the start date."
        }

        val managerName = draft.managerName.trim()
        var managerEmpNo: String? = null
        if (managerName.isNotEmpty()) {
            managerEmpNo = TeamRepository.empNoByName()[managerName]
            if (managerEmpNo == null) {
                errors += ProjectField.MANAGER to "Choose a manager from the team list, or leave it empty."
            }
        }

        val revenue = parseRevenue(draft.revenue)
        if (revenue == null && draft.revenue.trim().isNotEmpty()) {
            errors += ProjectField.REVENUE to "Enter revenue as a number, like 1250000."
        }
        val invoiced = parseRevenue(draft.invoiced)
        if (invoiced == null && draft.invoiced.trim().isNotEmpty()) {
            errors += ProjectField.INVOICED to "Enter the amount invoiced as a number, like 750000."
        }

        if (errors.isNotEmpty()) return UpdateProjectResult(false, errors, draft)

        val previousManager = ProjectRepository.managerOf(id)
        ProjectRepository.update(
            id = id,
            name = name,
            status = status,
            progress = existing.progress,   // derived from the board, never typed — see the header
            managerEmpNo = managerEmpNo,
            due = end,                       // `due` is the end date — see V3__project_categories.sql
            type = type,
            client = client,
            start = start,
            revenue = revenue ?: 0.0,
            invoiced = invoiced ?: 0.0,
        )
        if (managerEmpNo != null && managerEmpNo != previousManager) {
            NotificationService.projectManagerAssigned(id, actorEmpNo)
        }
        return UpdateProjectResult(true, emptyMap(), draft)
    }

    /**
     * Delete a project and every card filed under it (Stage 60). Management-only — a Viewer's route never calls
     * it, and it refuses again here so the rule cannot be reached past the route by some later caller. Returns
     * false when the caller may not, or when there was no such project; [ProjectRepository.deleteWithTasks]
     * takes the cards with it so dropping the project cannot leave its work standing unfiled.
     */
    fun delete(id: Int, canManage: Boolean): Boolean {
        if (!canManage) return false
        return ProjectRepository.deleteWithTasks(id) > 0
    }
}

/** Which input a create-project error belongs under; also keys the re-populated form. */
enum class ProjectField {
    TYPE, CODE, NAME, CLIENT, START, END, STATUS, MANAGER, REVENUE, INVOICED,
}

/** The raw create-project form, exactly as submitted so the modal can be re-rendered. */
data class ProjectDraft(
    val type: String = "",
    val code: String = "",
    val name: String = "",
    val client: String = "",
    val start: String = "",
    val end: String = "",
    val status: String = ProjectService.DEFAULT_STATUS,
    val managerName: String = "",
    val revenue: String = "",
    val invoiced: String = "",
)

/** `projectId` on success, field-keyed `errors` when the form has to stay open. */
data class CreateProjectResult(
    val projectId: Int?,
    val errors: Map<ProjectField, String>,
    val values: ProjectDraft,
) {
    fun error(field: ProjectField): String? = errors[field]
}

/** `ok` when the header strip saved; on refusal `errors` keys the message under each input and `values` is what was typed. */
data class UpdateProjectResult(
    val ok: Boolean,
    val errors: Map<ProjectField, String>,
    val values: ProjectDraft,
) {
    fun error(field: ProjectField): String? = errors[field]
}

private val ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun isIsoDate(value: String): Boolean = try {
    LocalDate.parse(value, ISO_DATE)
    true
} catch (e: Exception) {
    false
}

/** Accepts `1 250 000` and `1,250,000.00`; rejects negatives and anything non-numeric. */
private fun parseRevenue(raw: String): Double? {
    val cleaned = raw.trim().replace(" ", "").replace(",", "")
    if (cleaned.isEmpty()) return null
    val value = cleaned.toDoubleOrNull() ?: return null
    return if (value < 0.0 || !value.isFinite()) null else value
}
