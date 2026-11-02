package mn.murray.pts.service

import mn.murray.pts.domain.Currency
import mn.murray.pts.domain.PROJECT_BUCKET_MAX
import mn.murray.pts.domain.PROJECT_STATUSES
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.TYPE_PREFIX
import mn.murray.pts.domain.codeMatchesType
import mn.murray.pts.domain.isValidProjectCode
import mn.murray.pts.domain.normalizeProjectCode
import mn.murray.pts.repository.PinRepository
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
 * [TypeDefaultBucketRepository]; nothing here touches the legacy JSON store.
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
            errors += ProjectField.REVENUE to "Enter revenue as a number, like 1,250,000."
        }

        // **Stage 109: a new project picks the currency its revenue is in, and tugrik is the pick when the form
        // says nothing.** The choice is stored as the ISO code and it is settled here, once: [ProjectRepository.update]
        // never writes the column, so the header strip has no say and a project cannot change the money it is
        // counted in after the fact — the same permanence the project number has. Nothing converts anything: the
        // stored figure is the figure the manager typed, and only its sign changes on the page.
        val currency = Currency.of(draft.currency.ifBlank { Currency.DEFAULT.code })
        if (currency == null) {
            errors += ProjectField.CURRENCY to "Choose a currency: tugrik, US dollar or yuan."
        }

        // **Stage 104 puts a bucket line back on the create form, as an addition rather than a choice.** Since
        // Stage 100 the answer to "what columns does a new board have" was entirely the category's — the whole
        // Settings list, nothing else — and a category that listed none could not be created from at all. Now this
        // form may name extra columns too, and they are **appended after** the catalogue rows: the list Settings
        // keeps is still what every project of the category starts with, and what management types here is this
        // project's own. Nothing below writes a `type_default_buckets` row, so a bucket added at creation joins no
        // catalogue and no other board — the same reach Stage 77 gave a bucket typed on a live board with its
        // `also_type` tick left off, and the reason this form prints no tick at all.
        //
        // A typed name that repeats the catalogue is refused instead of being quietly dropped. [TypeDefaultBucketRepository.seedBoard]
        // would have ignored it without a word — the shape Stage 79 accepted for a re-typed name on a live board —
        // and here the reader would have watched a chip they added vanish from a board that says it was created.
        val typedBuckets = draft.newBuckets.map { it.trim() }.filter { it.isNotEmpty() }
        val catalogue = if (type in PROJECT_TYPES) TypeDefaultBucketRepository.of(type) else emptyList()
        if (type in PROJECT_TYPES) {
            val seen = LinkedHashSet<String>()
            val problem = typedBuckets.map { it.trim() }.firstNotNullOfOrNull { name ->
                val shape = BucketDefaultsService.nameProblem(name)
                when {
                    shape != null -> shape
                    !seen.add(name.lowercase()) -> "$name is on this board twice."
                    catalogue.any { it.name.equals(name, ignoreCase = true) } ->
                        "$name is already one of ${type.lowercase()}'s buckets — this board gets it from Settings."
                    else -> null
                }
            }
            if (problem != null) {
                errors += ProjectField.BUCKETS to problem
            } else if (catalogue.isEmpty() && typedBuckets.isEmpty()) {
                // The one refusal left, and it is narrower than Stage 100's: an empty category is still a board
                // with nowhere to file work, but the reader can now fill it from here instead of walking to
                // Settings first.
                errors += ProjectField.BUCKETS to
                    "This category has no buckets yet. Add one above, or list them in Settings under Project bucket settings."
            }
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
            currency = currency ?: Currency.DEFAULT,
        )
        // **Stage 100: the board starts as the category's Settings list, all of it. Stage 104 appends what this
        // form named.** The three phases V16 gave every board that already exists are not added here any more; a
        // category that wants them lists them. [seedBoard] writes the rows in catalogue order — the same rows
        // Settings' add puts on the boards that already exist when management adds a bucket there — and appends a
        // name the catalogue does not know last, in the order the form posted it, which is where a bucket typed
        // during creation lands. It carries no colour, so that column wears the shade of the position it took
        // (Stage 80), exactly like one added later from the board's own `+ Add bucket` box.
        TypeDefaultBucketRepository.seedBoard(id, type, LinkedHashSet(catalogue.map { it.name } + typedBuckets))
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
            errors += ProjectField.REVENUE to "Enter revenue as a number, like 1,250,000."
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

/**
 * Which input a create-project error belongs under; also keys the re-populated form.
 *
 * There is no bucket *choice* field: the popup never asks which of the category's rows the board should hold.
 * Stage 73's `BUCKETS` — the one entry that keyed a group of controls rather than a single input — was deleted
 * with the tick-list in Stage 98 and put back by Stage 100 for the empty-category refusal alone.
 */
enum class ProjectField {
    TYPE, CODE, NAME, CLIENT, START, END, STATUS, MANAGER, REVENUE, INVOICED,

    /**
     * The currency picker that sits under Revenue in the create popup (**Stage 109**). It is refused only for a
     * value the app cannot name — a hand-made post or a stale form — since a missing one means tugrik and the
     * page offers no other kind of answer. The message belongs under the picker itself, like every other field's.
     */
    CURRENCY,
    /**
     * The create popup's bucket line. Since **Stage 104** it does name an input — the Add bucket box — so a
     * message here is either a name that broke a rule (too long, twice on the board, already one of the
     * category's), or the refusal of a create whose category lists nothing and whose form typed nothing. The
     * message is one line for the whole line of boxes, because a chip has no field of its own to be refused in.
     */
    BUCKETS,
}

/**
 * The raw create-project form, exactly as submitted so the modal can be re-rendered.
 *
 * A draft remembers no *choice* about buckets — since Stage 100 nothing on this form selects from the category's
 * list — but since **Stage 104** it remembers what management **typed** in the Add bucket line: [newBuckets] is
 * every `new_bucket` value posted, chips and the name still in the box alike, in the order the form sent them. A
 * rejected create comes back as chips holding exactly these, and a successful one lands them on the new board
 * after the category's own rows. Stage 73's `buckets` / `newBucket` and Stage 77's `bucketForType` were deleted
 * in Stage 98 along with the popup fields that posted them; this is a new field with a narrower job, not that pair
 * coming back — there is no tick-list and nothing here reaches the category's catalogue.
 */
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
    val newBuckets: List<String> = emptyList(),
    /**
     * The currency Revenue was typed in (**Stage 109**), posted from the picker under the amount as an ISO code.
     * It defaults to tugrik, so a draft built without one — the header strip's edit, a test's minimal form — reads
     * as the money every project held before the choice existed, and a blank post means the same thing.
     */
    val currency: String = Currency.DEFAULT.code,
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

/**
 * Accepts `1 250 000`, `1,250,000.00` and — since **Stage 108**, when revenue began printing with its currency's
 * sign — `₮ 1,250,000`, sign and non-breaking space included, so a figure copied out of a revenue cell can be
 * pasted back into an input. Since **Stage 109** that sign may be any of [Currency]'s, `$` and `¥` included, and
 * every one of them is stripped the same way: the amount is a plain number on disk whoever's money it is.
 * Rejects negatives and anything non-numeric.
 */
private fun parseRevenue(raw: String): Double? {
    var cleaned = raw.trim()
        .replace("\u00A0", "")
        .replace(" ", "")
        .replace(",", "")
    Currency.entries.forEach { cleaned = cleaned.replace(it.symbol, "") }
    if (cleaned.isEmpty()) return null
    val value = cleaned.toDoubleOrNull() ?: return null
    return if (value < 0.0 || !value.isFinite()) null else value
}
