package mn.murray.pts.service

import mn.murray.pts.domain.ProjectType
import mn.murray.pts.domain.typeLabel
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.ProjectTypeRepository

/** A catalogue write: whether it landed, and the line the panel prints either way. */
data class ProjectTypeResult(val ok: Boolean, val message: String)

/**
 * The rules behind Settings' *Project types* panel (Stage 63, V14): what a category's letter and label may
 * be, and when a category is safe to take away.
 *
 * A category is not a free string. Its letter is the first token of every project number filed under it
 * (`S-01`), so the three rules that look pedantic are what keeps the numbering honest: the letter is one
 * character, it is upper case, and no second category may hold it. The label matters almost as much, because
 * `projects.type` stores the label rather than the letter — which is why [rename] carries the old label
 * across the projects and the lane defaults in the same write, and why a rename does **not** touch a single
 * project number: those keep leading with the letter they were given, which is the only way an `S-04` written
 * in March still means the same project in November.
 *
 * [remove] is the one place the app refuses to do what it is told. Deleting a category that still has
 * projects under it would leave those rows filed against a label no board panel, no tab and no picker can
 * name — they would silently vanish off the Projects page and stop counting on the Forecasts sheet. So the
 * answer is a count and an instruction: move the projects first, then retire the category.
 */
object ProjectTypeService {

    /** Long enough for a real category name, short enough to fit a board panel head and a picker option. */
    const val NAME_MAX = 60

    /** The catalogue, in board order. Reading it is also what republishes it for the view layer. */
    fun list(): List<ProjectType> = ProjectTypeRepository.all()

    /**
     * Add a category led by [rawCode] and named [rawName].
     *
     * Nothing is written when the caller is not management, the letter is not a single A-Z character or is
     * already leading another category's numbers, or the label is blank, too long for a picker, or already
     * on the list (compared trimmed and ignoring case, the way a duplicate lane name is compared on a board).
     */
    fun add(rawCode: String, rawName: String, canManage: Boolean): ProjectTypeResult {
        if (!canManage) return ProjectTypeResult(false, "Only management sets the project categories.")
        val current = ProjectTypeRepository.all()
        val code = rawCode.trim().uppercase()
        // A-Z only, because a project number is validated as `<A-Z>-<tail>`: a letter outside that range
        // would hand out numbers the create form then refuses to save.
        if (code.length != 1 || code[0] !in 'A'..'Z') {
            return ProjectTypeResult(false, "A category letter is one character, A to Z.")
        }
        current.firstOrNull { it.code == code }?.let { held ->
            // The label rather than `typeLabel`: the sentence already starts with the letter, so printing
            // "P — Parts supply" here would say P twice.
            return ProjectTypeResult(false, "$code already leads ${held.name}'s project numbers.")
        }
        val name = rawName.trim()
        labelProblem(name, current.map { it.name })?.let { return ProjectTypeResult(false, it) }
        ProjectTypeRepository.insert(code, name)
        return ProjectTypeResult(true, "$code — $name is now one of the categories a project can be filed under.")
    }

    /**
     * Rename the category currently called [oldName] to [rawName].
     *
     * The letter is not part of a rename and cannot be changed here: it is the primary key, and every
     * project number already issued under it starts with that letter. On the way out the answer says how
     * many projects moved with the label, because that is what a rename actually costs.
     */
    fun rename(oldName: String, rawName: String, canManage: Boolean): ProjectTypeResult {
        if (!canManage) return ProjectTypeResult(false, "Only management sets the project categories.")
        val current = ProjectTypeRepository.all()
        val existing = current.find { it.name == oldName }
            ?: return ProjectTypeResult(false, "There is no category called $oldName to rename.")
        val name = rawName.trim()
        if (name == existing.name) return ProjectTypeResult(true, "$name is unchanged.")
        labelProblem(name, current.filter { it.code != existing.code }.map { it.name })
            ?.let { return ProjectTypeResult(false, it) }
        val moved = ProjectTypeRepository.rename(existing.name, name)
        val projects = when (moved) {
            0 -> "No projects are filed under it yet, so nothing else moved."
            1 -> "1 project now reads as $name."
            else -> "$moved projects now read as $name."
        }
        return ProjectTypeResult(true, "Renamed to $name. ${existing.code} still leads its project numbers — $projects")
    }

    /**
     * Retire the category led by [code], and its lane defaults with it.
     *
     * Refused while any project is still filed under the label — see the class note. A category with no
     * projects left is a label nobody is holding on to, so taking it away cannot orphan a row.
     */
    fun remove(code: String, canManage: Boolean): ProjectTypeResult {
        if (!canManage) return ProjectTypeResult(false, "Only management sets the project categories.")
        val target = ProjectTypeRepository.findByCode(code)
            ?: return ProjectTypeResult(false, "That category is no longer on the list.")
        val holding = ProjectRepository.findByType(target.name).size
        if (holding > 0) {
            val count = if (holding == 1) "1 project is" else "$holding projects are"
            return ProjectTypeResult(
                false,
                "$count filed under ${target.name}. Move them to another category first, then retire it.",
            )
        }
        ProjectTypeRepository.delete(target.code)
        return ProjectTypeResult(true, "${typeLabel(target.name)} is no longer a category.")
    }

    /** What is wrong with this label, or null when it is a name the catalogue can hold. */
    private fun labelProblem(name: String, taken: List<String>): String? = when {
        name.isEmpty() -> "Enter a category name."
        name.length > NAME_MAX -> "A category name is $NAME_MAX characters or fewer."
        taken.any { it.equals(name, ignoreCase = true) } -> "There is already a category called $name."
        else -> null
    }
}
