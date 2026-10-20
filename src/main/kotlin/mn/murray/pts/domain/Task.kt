package mn.murray.pts.domain

import kotlin.math.roundToInt

/**
 * Task domain model. A single [TaskItem] shape backs both the assigned-tasks
 * kanban and the other-tasks board; the `kind` discriminator on the DB row
 * (see db/tables/Tasks.kt) tells them apart.
 */

val TASK_STATUSES = listOf("Not started", "In progress", "On hold", "Blocked", "Completed")

/** The only priorities a task card may carry; the popup offers exactly these as its options. */
val TASK_PRIORITIES = listOf("High", "Medium", "Low")

/**
 * One tickable step under a task card (V5). Management adds them, the assignee ticks them;
 * nothing here moves the card between kanban columns — `TaskItem.status` still owns that.
 */
@kotlinx.serialization.Serializable
data class ChecklistItem(
    val id: Int = 0,
    val label: String,
    val done: Boolean = false,
)

/**
 * One entry in a card's running log (V6). Written by whoever has something to say about the work —
 * management and the person it is assigned to — and never edited: a note is a record of a moment, so
 * correcting one in place would rewrite the history the log exists to keep. [author] is the employee
 * number on the row; the view resolves it to a name when the account is still on the roster.
 */
@kotlinx.serialization.Serializable
data class TaskNote(
    val id: Int = 0,
    val body: String,
    val author: String = "",
    val created: String = "",
)

@kotlinx.serialization.Serializable
data class TaskItem(
    val title: String,
    val project: String,
    val due: String,
    val priority: String,
    val assignee: String = "",
    val status: String = "Not started",
    val id: Int = 0,
    // Which column of the Other tasks board the card is in, as the row holds it: 0 when it has none. [bucket]
    // is that column's *name*, which is what a board groups by; the id is what a picker selects, because a
    // name is not a stable thing to aim a form field at when the same page can rename the column under it.
    val bucketId: Int = 0,
    val bucket: String = "Management",
    // Which board this row belongs to — `ASSIGNED` or `OTHER` (see db/tables/Tasks.kt). The shape is shared by
    // both, so a view that has to tell them apart reads this rather than guessing from the fields one of them
    // happens to leave blank.
    val kind: String = "ASSIGNED",
    // Which lane this card sits on **on its project's page** (V7). [projectBucket] is that lane's name and
    // 0 / "" when the card has none, which the project board shows as an Unsorted column rather than
    // dropping the card. Nothing else reads these: [bucket] and the Other tasks board's own columns are a
    // separate grouping, and a card has one lane per board, not one lane total.
    val projectBucket: String = "",
    val projectBucketId: Int = 0,
    val started: String = "",
    // The card's own steps, in the order management wrote them. Read with the card, so a board
    // render never asks for them again; `[]` on a card nobody has given a checklist.
    val checklist: List<ChecklistItem> = emptyList(),
    // The card's own log, newest first. Read with the card for the same reason [checklist] is, so
    // the editor popup and the card's note count come from the one fetch; `[]` on a card nobody has
    // written on.
    val notes: List<TaskNote> = emptyList(),
) {
    /**
     * Whether the card still counts as work in progress.
     *
     * The Completed column is the only one that closes a card: not started, in progress, on hold and blocked
     * are all still open, even when nobody is moving them. [mn.murray.pts.DashboardCommon] counts the sidebar
     * pill through here, and so does [mn.murray.pts.Team] when it sorts a colleague's cards. A card being
     * closed does not remove it from a board — it only stops it being something somebody still has to do.
     */
    val isOpen: Boolean get() = status != "Completed"
    val hasChecklist: Boolean get() = checklist.isNotEmpty()
    val checklistDone: Int get() = checklist.count { it.done }
    val hasNotes: Boolean get() = notes.isNotEmpty()

    /**
     * Whether this card lives on the Other tasks board rather than on an assigned one.
     *
     * The two share everything else — the same columns, the same steps and log, the same editor popup — and
     * differ in exactly one thing: a backlog card belongs to a **column** of that board and to **no project**,
     * which is what makes it work that is not part of a project. Views read this instead of inferring it from
     * [project] being blank, because a card can be unassigned from a project on somebody's project board and
     * still be an assigned card.
     */
    val isBacklog: Boolean get() = kind == "OTHER"

    /**
     * How far down this card's own plan it has got, as a whole percentage — or null when the card has no
     * plan at all.
     *
     * The ticked boxes *are* the measurement: four steps with one ticked reads as 25%, and nothing else
     * moves that number. A card nobody has written a plan for reports null rather than 0%, because 0% is
     * a claim about unfinished work and an unplanned card has made none — see
     * [mn.murray.pts.service.TaskService.progressByProject], which leaves such a card out of a project's
     * average entirely.
     */
    val progressPercent: Int?
        get() = if (checklist.isEmpty()) null else (checklistDone * 100.0 / checklist.size).roundToInt()
}

/**
 * Kanban column for the other-tasks board. The legacy store kept buckets as a plain
 * `List<String>`; v2 gives them an id + explicit position so tasks can reference a
 * bucket by FK and the board order is stable.
 */
data class Bucket(
    val id: Int = 0,
    val name: String,
    val position: Int = 0,
)

/**
 * How long a backlog column's name may be — the same bound the lane list uses, because the two boards print
 * their headings the same way and [mn.murray.pts.service.TaskService.renameBucket] refuses a name the field
 * would have to cut.
 */
const val BUCKET_NAME_MAX = 40

/**
 * The two answers a card's popup gives to "where does this live" (Stage 48): on a project, in one of its
 * sub-categories, or in Other tasks, in one of that board's columns. There is no third one — a card belongs to
 * exactly one of the two boards, which is the whole structure the app is built on — so the popup that moves a
 * card from one to the other posts one of these two words and nothing else.
 *
 * The views print them as a picker's option values and the service reads them back; which column of the row
 * they turn into is [mn.murray.pts.service.TaskService]'s business, not the form's.
 */
const val BOARD_PROJECT = "project"
const val BOARD_OTHER = "other"

/**
 * One lane on one **project's** board (V7) — the phase of work a card belongs to, not how far it has got.
 *
 * A project is created with six of them already on it — [DEFAULT_PROJECT_BUCKETS], in that order — so nobody
 * has to touch the list to get a usable board; the create form asks nothing about lanes. The manager of the
 * project adds to those six as the work needs them, and takes one of their own off again — deleting a lane
 * leaves its cards unfiled rather than destroying them,
 * which is why [TaskItem.projectBucketId] is nullable and why the board prints an [UNSORTED_LANES] column
 * while anything on it has no lane. A *new* card is not allowed to be one of those: it is filed in a lane as
 * it is written, so the Unsorted column is the legacy cards' resting place, not a place to put work.
 */
data class ProjectBucket(
    val id: Int = 0,
    val projectId: Int,
    val name: String,
    val position: Int = 0,
)

/**
 * The lanes **every** project starts with and is expected to keep, in the order they print —
 * [ProjectBucketRepository.seedDefaults] writes them as the project row is created, V7 gave the original three
 * to every project that already existed, and V10 gave the rest of these to the projects that pre-date the list.
 *
 * They are the same families the Projects board cuts itself by ([PROJECT_TYPES]), shortened to the six the
 * company plans work under, so a card's sub-category and its project's category are words the reader already
 * knows. Because the list is shared, a card is filed in **one of them or it is not on a project** — see
 * [mn.murray.pts.service.TaskService.resolve] — which is what makes the general board able to offer the same
 * six pickers without first being told which project the card is going to.
 *
 * A project's own extra lanes are never taken off it: the six are the floor, not the ceiling, and the manager
 * of a project still adds to them as the work needs.
 */
val DEFAULT_PROJECT_BUCKETS = listOf(
    "Fabrication/Manufacturing",
    "Repair/Maintenance",
    "Site projects",
    "Labor supply",
    "Goods sales",
    "Other",
)

/**
 * Whether a lane name is one of the six every project carries. Compared after the trim and ignoring case, the
 * way a duplicate lane name is compared, so a hand-typed post cannot remove a standard column from a board and
 * leave the cards filed in it unfiled.
 */
fun isStandardLane(name: String): Boolean =
    DEFAULT_PROJECT_BUCKETS.any { it.equals(name.trim(), ignoreCase = true) }

/** The heading of the column that holds a project's cards nobody has filed into a lane. */
const val UNSORTED_LANES = "Unsorted"

/** How long a lane name may be — the column head shows it on one line, the form stops the writer here. */
const val PROJECT_BUCKET_MAX = 40
