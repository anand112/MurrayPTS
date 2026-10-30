package mn.murray.pts.service

import mn.murray.pts.domain.PROJECT_BUCKET_MAX
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.ProjectBucket
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository

/**
 * The rules behind Settings' *Project bucket settings* panel: the columns a project category is made of — the
 * buckets every board of that category carries, the ones that already exist and every one still to come.
 *
 * **Since Stage 100 this list is the whole board.** [mn.murray.pts.service.ProjectService.create] cuts a new
 * project's board from exactly these rows, in this order, and refuses a category that lists none — nothing is
 * added to a board that is not here. The three phases V16 gave the boards that already exist are not special any
 * more: a category that wants them lists them, and [add] accepts one of their names like any other bucket.
 *
 * That is the whole of what a row is. A project's own manager may still retitle, empty and remove any column on
 * that project's board; what this panel decides is what a *new* board opens with. [nameProblem] therefore has no
 * standard-name branch to switch off any more — [mn.murray.pts.service.TaskService.saveLanes] and this panel read
 * one set of name rules.
 *
 * **Since Stage 98 a bucket here is a column, not an offer.** [add] writes the catalogue row *and* puts that
 * column on every project of the category that does not already carry the name, and
 * [mn.murray.pts.service.ProjectService.create] seeds the same set on a new board — so a board reads the same
 * whether it was made before the add or after it. Two things deliberately still stop at the catalogue:
 * [remove] (the row stops future projects getting the column; boards that carry it keep it, exactly as removing
 * a bucket from one project archives its cards rather than deleting them), and a bucket typed into a project
 * page's own picker, whose `also_type` tick makes the name part of the category for *new* boards without
 * reshaping the other boards of that category — it was named for one project, and a tick that quietly adds a
 * column to a dozen others is not what the box says.
 */
object BucketDefaultsService {

    /** This category's buckets, in the order a new board is cut from them. */
    fun bucketsOf(type: String): List<ProjectBucket> =
        TypeDefaultBucketRepository.of(if (type in PROJECT_TYPES) type else PROJECT_TYPES.first())

    /**
     * Every category's buckets, each in its own catalogue order — the read behind the create popup's Buckets line
     * (Stage 104), where management sees what the board will get from Settings before they add to it.
     */
    fun catalogueByType(): Map<String, List<ProjectBucket>> = TypeDefaultBucketRepository.allByType()

    /** The category the panel should print: a hand-typed or stale `?lanes=` falls back to the first one. */
    fun typeFor(raw: String?): String = raw?.trim()?.takeIf { it in PROJECT_TYPES } ?: PROJECT_TYPES.first()

    /**
     * Add one bucket to [type]: the catalogue row, and a column on every board of that category that is not
     * already carrying the name. Nothing is written and [BucketDefaultResult.message] says why when the caller
     * is not management, the category is not on the list, the name is blank, too long for a column head, or
     * already in this category's set (compared trimmed and ignoring case, the way a duplicate lane on a live
     * board is compared).
     *
     * The name rules are [nameProblem]'s, shared with the pickers Stage 73 added: one rule, read the same way
     * whether the name arrives from Settings, from a project page or from a create draft.
     *
     * The row is written with **no colour of its own**, so it wears the shade of its position in the run
     * ([mn.murray.pts.domain.effectiveColourSlot]) here and on every board it seeds. That is Stage 83's
     * answer to *"when creating a new bucket don't need to choose colour"*: a new bucket is named, and the app
     * paints it. A row that already carries a slot — written before Stage 83, or copied from a board that was
     * recoloured — keeps it.
     *
     * **Stage 98 — the add is also the columns.** The catalogue row alone would be invisible to everybody
     * standing on an existing project (the create popup stopped asking about buckets in Stage 92, so nothing
     * else reads it), which is exactly what the user reported: *"when add a new bucket on project bucket
     * settings its not adding on project"*. So each project of the category is asked whether it already holds
     * that name — compared ignoring case, because a manager may have made a column of their own by that word —
     * and only the boards without it are given the column, through
     * [TypeDefaultBucketRepository.seedOne] so the lane carries the catalogue's spelling and colour.
     * The receipt says how many boards took it, because a message that only promises *the next project* reads
     * as nothing having happened.
     */
    fun add(type: String, rawName: String, canManage: Boolean): BucketDefaultResult {
        if (!canManage) return BucketDefaultResult(false, "Only management sets a project's default lanes.")
        val category = typeFor(type)
        val name = rawName.trim()
        nameProblem(name)?.let { return BucketDefaultResult(false, it) }
        if (TypeDefaultBucketRepository.namesOf(category).any { it.equals(name, ignoreCase = true) }) {
            return BucketDefaultResult(false, "$category already adds $name.")
        }
        TypeDefaultBucketRepository.insert(category, name)
        val boards = ProjectRepository.findByType(category).count { project ->
            ProjectBucketRepository.namesOf(project.id).none { it.equals(name, ignoreCase = true) } &&
                TypeDefaultBucketRepository.seedOne(project.id, category, name) != null
        }
        return BucketDefaultResult(true, receiptFor(category, name, boards))
    }

    /** The line the panel prints after a catalogue add: what changed, on how many boards, and what is promised. */
    private fun receiptFor(category: String, name: String, boards: Int): String = when (boards) {
        0 -> "$name is a $category bucket now — every new $category project will open with it."
        1 -> "$name is a $category bucket now — the one $category board without it has the column, and every " +
            "new project will open with it."
        else -> "$name is a $category bucket now — $boards $category boards gained the column, and every new " +
            "project will open with it."
    }

    /**
     * Take one default off the catalogue. The row goes and the boards do not: a project carries its lane as a
     * [mn.murray.pts.domain.ProjectBucket] row, not a pointer into this table, so the column stays wherever it
     * already stands — including every board Stage 98's [add] put one on. What stops is the *next* project of
     * the category opening with it. Returns false when the caller is not management or the id is gone.
     */
    fun remove(id: Int, canManage: Boolean): Boolean {
        if (TypeDefaultBucketRepository.find(id) == null) return false
        if (!canManage) return false
        return TypeDefaultBucketRepository.delete(id) > 0
    }

    /**
     * The buckets a form's tick-list resolves to for [type], and the one reason it cannot be taken at its word.
     *
     * This is Stage 73's rule for the one page that still asks the question: a project page's own bucket popup,
     * which posts the whole board plus any name typed beside it. The create popup used to read this too; since
     * Stage 98 a new board is cut from the category's catalogue and asks nothing ([ProjectService.create]).
     * Names are posted rather than ids because a name is what a lane is filed by on the general board (Stage 48)
     * and the one answer that survives a category's catalogue changing while the form sits open.
     *
     * A posted name is kept when [type] offers it. A posted name the catalogue does not know is a **new bucket
     * typed on this page**, and since Stage 77 what happens to it is the form's decision rather than this
     * service's guess: [alsoForType] is that page's tick, and it is off by default. Off, the name is this
     * project's alone — it comes back here as a bucket to put on the board and is never written into the
     * category's set, so the next project of this category is not offered it. On, it is written into the
     * catalogue as well, which is what makes it an offer that next project sees.
     *
     * Only management may put a name in the catalogue, and the tick is the only way to try: a caller who is not
     * management is refused **when they tick it**, and otherwise simply gets the project-local answer they were
     * shown. That is the change from Stage 73, which refused a non-management caller any unknown name at all on
     * the reasoning that writing a bucket meant widening the category — once a bucket can be written onto one
     * board alone, that reasoning no longer applies to a project's own manager naming a column of their own
     * project. Widening the type stays management's. The refusal is printed at the field rather than quietly
     * dropping the tick, because a box the reader ticked that writes nothing is the worst answer a form can give.
     *
     * Nothing is written when [BucketChoice.error] is set; the caller stops there and shows it. [typeFor] gives a
     * hand-typed or retired category the same fallback every other reader of this table uses.
     */
    fun chosenFor(
        type: String,
        posted: List<String>,
        canManage: Boolean,
        alreadyOnBoard: Set<String> = emptySet(),
        alsoForType: Boolean = false,
    ): BucketChoice {
        val category = typeFor(type)
        val offered = TypeDefaultBucketRepository.namesOf(category)
        val names = LinkedHashSet<String>()
        // Two passes, deliberately: a tick-list that is refused half-way would leave the first new bucket in
        // the catalogue and the second one not, so the reader would see a Settings list that disagrees with the
        // board they were refused. Every name is checked before any of them is written.
        val wanted = posted.map { it.trim() }.filter { it.isNotEmpty() }
        for (name in wanted) {
            nameProblem(name, alreadyOnBoard)?.let { return BucketChoice(emptyList(), it) }
            val unknown = offered.none { it.equals(name, ignoreCase = true) }
            if (unknown && alsoForType && !canManage) {
                return BucketChoice(emptyList(), "Only management adds a new bucket to the $category catalogue.")
            }
        }
        for (name in wanted) {
            if (names.any { it.equals(name, ignoreCase = true) }) continue   // ticked twice by a hand-built post
            val known = offered.firstOrNull { it.equals(name, ignoreCase = true) }
            if (known != null) {
                // The catalogue's own spelling, so a board never carries a lane that differs from the bucket it
                // came from by an initial letter.
                names += known
            } else {
                // Stage 77: the tick decides whether a name this category has never heard of stops at this
                // project's board or joins the category's defaults too.
                if (alsoForType && canManage) TypeDefaultBucketRepository.insert(category, name)
                names += name
            }
        }
        return BucketChoice(names.toList(), null)
    }

    /**
     * Why this bucket name cannot be used, or null when it can. The same rules [add] applies to a name typed on
     * Settings, kept apart from the write so the create form and a project page can check a whole tick-list
     * before any of it lands. [alreadyOnBoard] is what makes a duplicate complaint about the board in front of
     * the reader rather than about the catalogue.
     *
     * There is no standard-name branch any more (Stage 100): the three phases stopped being an opening set the
     * code writes, so a board may legitimately be given **Engineering** by its category's Settings list or by its
     * own picker, and the only name rules left are blank, length and a duplicate on the board.
     */
    fun nameProblem(
        name: String,
        alreadyOnBoard: Set<String> = emptySet(),
    ): String? = when {
        name.isBlank() -> "Enter a bucket name."
        name.length > PROJECT_BUCKET_MAX -> "A bucket name is $PROJECT_BUCKET_MAX characters or fewer."
        alreadyOnBoard.any { it.equals(name, ignoreCase = true) } -> "$name is already a bucket on this project."
        else -> null
    }
}

/**
 * What [BucketDefaultsService.chosenFor] resolved a tick-list to: the bucket names that go on the board, each
 * spelled the way the catalogue spells it, and the single line to print under the picker when it refused. A
 * brand-new name is in the catalogue by the time this comes back **only when the form's tick asked for it**
 * ([chosenFor]'s `alsoForType`) — otherwise it is this project's alone, and the board it lands on is the only
 * thing the call wrote. Board order is the catalogue's, not this list's: [mn.murray.pts.repository.TypeDefaultBucketRepository.seedBoard]
 * and [mn.murray.pts.service.TaskService.saveLanes] both append in the order the category offers its buckets,
 * with a project-local name last because it is in no catalogue to be ordered by.
 */
data class BucketChoice(val names: List<String>, val error: String?) {
    val ok: Boolean get() = error == null
}

/** A catalogue write: whether it landed, and the line the panel prints either way. */
data class BucketDefaultResult(val ok: Boolean, val message: String)
