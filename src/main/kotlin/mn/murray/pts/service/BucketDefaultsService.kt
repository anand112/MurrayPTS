package mn.murray.pts.service

import mn.murray.pts.domain.PROJECT_BUCKET_MAX
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.ProjectBucket
import mn.murray.pts.domain.isStandardLane
import mn.murray.pts.repository.TypeDefaultBucketRepository

/**
 * The rules behind Settings' *Bucket defaults* panel: the extra lanes a project category adds to a new
 * board, on top of the six every board carries.
 *
 * The six ([mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS]) are not editable from anywhere, and this service
 * refuses a default whose name is one of them: a card is filed in a standard lane or it is not on a project
 * (Stage 48), so a catalogue that could re-offer "Site projects" as an extra would put one column on a board
 * twice and leave the picker guessing which row a card went into. What is editable here is only the set
 * *besides* those six — the lane a Fabrication job always wants and a Labor supply contract never does.
 *
 * A write here touches the catalogue and nothing else. [ProjectService.create] reads it once, in the breath
 * after the six are seeded, so changing a default shapes the *next* project of that category and leaves every
 * live board exactly as its manager left it — no lane is added to, renamed on, or taken off a project that
 * already exists, and no card is ever refiled.
 */
object BucketDefaultsService {

    /** This category's extra lanes, in the order they will be appended to a board. */
    fun extrasOf(type: String): List<ProjectBucket> =
        TypeDefaultBucketRepository.of(if (type in PROJECT_TYPES) type else PROJECT_TYPES.first())

    /** The category the panel should print: a hand-typed or stale `?lanes=` falls back to the first one. */
    fun typeFor(raw: String?): String = raw?.trim()?.takeIf { it in PROJECT_TYPES } ?: PROJECT_TYPES.first()

    /**
     * Offer one more lane to [type]. Nothing is written and [BucketDefaultResult.message] says why when the
     * caller is not management, the category is not on the list, the name is blank, too long for a column
     * head, one of the six, or already in this category's set (compared trimmed and ignoring case, the way a
     * duplicate lane on a live board is compared).
     */
    fun add(type: String, rawName: String, canManage: Boolean): BucketDefaultResult {
        if (!canManage) return BucketDefaultResult(false, "Only management sets a project's default lanes.")
        val category = typeFor(type)
        val name = rawName.trim()
        when {
            name.isEmpty() -> return BucketDefaultResult(false, "Enter a lane name to add.")
            name.length > PROJECT_BUCKET_MAX ->
                return BucketDefaultResult(false, "A lane name is $PROJECT_BUCKET_MAX characters or fewer.")
            isStandardLane(name) -> return BucketDefaultResult(
                false,
                "$name is one of the six every project board already starts with — it is not an extra.",
            )
            TypeDefaultBucketRepository.namesOf(category).any { it.equals(name, ignoreCase = true) } ->
                return BucketDefaultResult(false, "$category already adds $name.")
        }
        TypeDefaultBucketRepository.insert(category, name)
        return BucketDefaultResult(true, "$name will be added to new $category projects.")
    }

    /**
     * Take one default off the catalogue. The row goes and the boards do not: a project that was created
     * while this default stood keeps its lane, because the lane on a board is a [mn.murray.pts.domain.ProjectBucket]
     * row, not a pointer into this table. Returns false when the caller is not management or the id is gone.
     */
    fun remove(id: Int, canManage: Boolean): Boolean {
        if (!canManage) return false
        if (TypeDefaultBucketRepository.find(id) == null) return false
        return TypeDefaultBucketRepository.delete(id) > 0
    }
}

/** A catalogue write: whether it landed, and the line the panel prints either way. */
data class BucketDefaultResult(val ok: Boolean, val message: String)
