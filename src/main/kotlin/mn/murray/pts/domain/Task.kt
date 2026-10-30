package mn.murray.pts.domain

import kotlin.math.roundToInt

/**
 * Task domain model. A single [TaskItem] shape backs both the assigned-tasks
 * kanban and the other-tasks board; the `kind` discriminator on the DB row
 * (see db/tables/Tasks.kt) tells them apart.
 */

/**
 * The status that closes a card, named once because more than one rule turns on it: it is the column a
 * board counts as finished, the one a card's [TaskItem.isOpen] measures against, and the one a card's
 * plan is ticked whole on the way into and restored on the way out of.
 */
const val STATUS_COMPLETED = "Completed"

val TASK_STATUSES = listOf("Not started", "In progress", "On hold", "Blocked", STATUS_COMPLETED)

/** The only priorities a task card may carry; the popup offers exactly these as its options. */
val TASK_PRIORITIES = listOf("High", "Medium", "Low")

/**
 * One tickable step under a task card (V5). Management adds them, the assignee ticks them;
 * nothing here moves the card between kanban columns — `TaskItem.status` still owns that. The one
 * thing that does run the other way is the card's own completion: a card that reaches
 * [STATUS_COMPLETED] has its whole plan ticked, and a card taken back off it gets the plan it had
 * before, step by step. That rule lives in [mn.murray.pts.service.TaskService], not here.
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
    // The project's own **id**, 0 when the card belongs to no project. [project] is its name, which is what every
    // board prints and groups by, but a name is not an address: the archive rules have to look this project's
    // lanes up by row ([mn.murray.pts.service.TaskService.mayEditLanes],
    // [mn.murray.pts.service.TaskService.restoreCard]), and a project renamed while an archive panel sits open
    // must not turn the cards it holds into cards filed under nothing. It sits after [started] rather than beside
    // [project] because the demo cards in [mn.murray.pts.DemoData] are built positionally.
    val projectId: Int = 0,
    // The card's own steps, in the order management wrote them. Read with the card, so a board
    // render never asks for them again; `[]` on a card nobody has given a checklist.
    val checklist: List<ChecklistItem> = emptyList(),
    // The card's own log, newest first. Read with the card for the same reason [checklist] is, so
    // the editor popup and the card's note count come from the one fetch; `[]` on a card nobody has
    // written on.
    val notes: List<TaskNote> = emptyList(),
    // Whether this card is off every board, in the Archive (V18). A board never shows one —
    // [mn.murray.pts.repository.TaskRepository.findByKind] leaves them in the database — and the archive panel on
    // a project page is read through [mn.murray.pts.repository.TaskRepository.findArchived], which is the only
    // place this is true of a card a reader sees.
    val archived: Boolean = false,
    // Which lane the card was filed in when it left the board, as [archivedLane]'s name and the day it happened.
    // The id is a **hint, not a promise**: the lane it points at may have been removed with the card's own column,
    // which is the usual case, so [mn.murray.pts.service.TaskService.restoreCard] checks the lane still exists on
    // the project before filing the card back into it. A card archived by itself, with its column still standing,
    // is the case where the id does resolve.
    val archivedLaneId: Int = 0,
    val archivedLane: String = "",
    val archivedOn: String = "",
    // The **code** of the project this card belongs to — `S-01` and the like — so a cross-project board can say
    // which world a card comes from without printing the card's own `T-00012` number (Stage 88). It sits at the
    // very end rather than beside [project] for the same reason [projectId] does: the demo cards in
    // [mn.murray.pts.DemoData] are built positionally, and a new field mid-list would shift every argument that
    // follows it. Blank on a card with no project, which is every backlog card.
    val projectCode: String = "",
) {
    /**
     * Whether the card still counts as work in progress.
     *
     * The Completed column is the only one that closes a card: not started, in progress, on hold and blocked
     * are all still open, even when nobody is moving them. [mn.murray.pts.DashboardCommon] counts the sidebar
     * pill through here, and so does [mn.murray.pts.Team] when it sorts a colleague's cards. A card being
     * closed does not remove it from a board — it only stops it being something somebody still has to do.
     */
    val isOpen: Boolean get() = status != STATUS_COMPLETED
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
 * Since Stage 100 a board is created from its category's Settings list and nothing else, so the lanes a project
 * opens with are exactly the rows that list holds. The manager of the project adds to them as the work needs them,
 * and takes any lane off again — deleting a lane leaves its cards unfiled rather than destroying them, which is
 * why [TaskItem.projectBucketId] is nullable and why the board prints an [UNSORTED_LANES] column while anything on
 * it has no lane. A *new* card is not allowed to be one of those: it is filed in a lane as it is written, so the
 * Unsorted column is the legacy cards' resting place, not a place to put work.
 *
 * [colourSlot] (V17, Stage 80) is this lane's own hue in the stylesheet's cycle, or null for *wear the colour of
 * my position* — which is what every lane written before Stage 80 does, what a category's list keeps doing, and
 * what a board looks like until somebody picks a colour on that column's popup.
 */
data class ProjectBucket(
    val id: Int = 0,
    val projectId: Int,
    val name: String,
    val position: Int = 0,
    val colourSlot: Int? = null,
)

/**
 * The three phases this app's boards were cut from — Engineering, Procurement, Production.
 *
 * **They are no longer written by a create.** Since Stage 100 a new board is exactly the rows Settings lists for
 * its category; a category that wants these phases lists them there, and one that does not gets a board of its own
 * shape. The list survives because three places still speak it: **V16** gave these three to every board that
 * already exists, the legacy JSON importer writes them onto the boards it restores, and the general boards' popup
 * offers them as a fallback lane list when it does not know the project's own (Stage 48).
 *
 * The three were always ordinary lanes once the project existed (Stage 75): retitling or removing one is the
 * project's business. Since Stage 100 nothing protects the names anywhere — a category may list **Engineering**
 * like any other bucket.
 */
val DEFAULT_PROJECT_BUCKETS = listOf(
    "Engineering",
    "Procurement",
    "Production",
)

/** The heading of the column that holds a project's cards nobody has filed into a lane. */
const val UNSORTED_LANES = "Unsorted"

/** How long a lane name may be — the column head shows it on one line, the form stops the writer here. */
const val PROJECT_BUCKET_MAX = 40

/**
 * How many bucket colours `.bucket-dot` is given in app.css.
 *
 * A count rather than the hues themselves, because the colours live in the stylesheet and this is the one place
 * Kotlin has to agree with it. Two surfaces print over it — the Settings catalogue's bucket rows (Stage 61) and,
 * since Stage 74, a project board's column heads.
 *
 * **Since Stage 80 a bucket can own one of these six**, as the V17 `colour_slot`, and it is then drawn with that
 * hue wherever it prints — on its own board, and on the Settings catalogue row it was seeded from once that
 * catalogue learned to carry a colour too. What a slot is *not* is a colour value: it is an index into this
 * cycle, which is what keeps the hues in the stylesheet, keeps both themes readable, and keeps a hand-typed
 * POST to the range the picker can offer.
 *
 * A bucket with no slot — every row that existed before Stage 80 — falls back to [effectiveColourSlot]'s
 * positional answer: its place in the run. The Settings catalogue and the board it cuts print the same shade
 * because both number the list from 0 (Stage 100; the catalogue used to offset its rows by the three opening
 * lanes). That agreement is still a courtesy rather than a rule the data can be relied on for — a project's own
 * manager may take a lane off, and the columns that remain close up, so one board's fourth column can be
 * another's third.
 */
const val BUCKET_DOT_COLOURS = 6

/** The six hues, by slot, in the words a colour picker's label uses. Ordered exactly as app.css's `.dot-0 … .dot-5`. */
val BUCKET_COLOUR_NAMES = listOf("blue", "amber", "teal", "violet", "rose", "slate")

/** Which of the [BUCKET_DOT_COLOURS] hues a bucket wears: its own [colourSlot] when it has one, else its [index]. */
fun effectiveColourSlot(index: Int, colourSlot: Int?): Int =
    (colourSlot ?: index).coerceAtLeast(0) % BUCKET_DOT_COLOURS

/** The dot classes for the [index]'th bucket of a list, in its own colour when it has one (V17, Stage 80). */
fun bucketDotClass(index: Int, colourSlot: Int? = null): String =
    "bucket-dot dot-${effectiveColourSlot(index, colourSlot)}"

/**
 * A colour a form posted, read the only way the picker can send one: a slot number in
 * [0, BUCKET_DOT_COLOURS). Anything else — absent, blank, a word, a number past the palette — is null, which
 * is the form answering *I have no preference* and leaves the bucket on its positional shade. A refused value
 * is never a guess at a colour nobody offered.
 */
fun colourSlotOf(raw: String?): Int? =
    raw?.trim()?.toIntOrNull()?.takeIf { it in 0 until BUCKET_DOT_COLOURS }
