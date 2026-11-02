package mn.murray.pts.service

import mn.murray.pts.domain.BOARD_OTHER
import mn.murray.pts.domain.BUCKET_DOT_COLOURS
import mn.murray.pts.domain.BUCKET_NAME_MAX
import mn.murray.pts.domain.Bucket
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.ProjectBucket
import mn.murray.pts.domain.PROJECT_BUCKET_MAX
import mn.murray.pts.domain.STATUS_COMPLETED
import mn.murray.pts.domain.TASK_PRIORITIES
import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.domain.TaskItem
import mn.murray.pts.domain.UNSORTED_LANES

import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ChecklistRepository
import mn.murray.pts.repository.NoteRepository
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Application-level operations for the tasks + buckets aggregate.
 *
 * A single [TaskItem] shape backs both the assigned-tasks kanban and the other-tasks
 * board; the DB `kind` column ("ASSIGNED" | "OTHER") tells them apart. This service owns
 * the legacy validation rules that used to live inline in the route handlers (priority /
 * status whitelists, roster membership for the assignee, project-name resolution, and the
 * kanban "manager or own task" authorization check) and translates the name-based legacy
 * inputs into the foreign-key ids the repositories persist.
 */
object TaskService {

    private const val KIND_ASSIGNED = "ASSIGNED"
    private const val KIND_OTHER = "OTHER"
    private val PRIORITIES = TASK_PRIORITIES

    /**
     * How long one checklist step may be. The card carries it as a single line, and the add box is
     * rendered with the same `maxlength`, so the field stops a reader where the service would —
     * nobody types 120 characters and then has them silently cut.
     */
    const val CHECKLIST_LABEL_MAX = 120

    /**
     * How long one note may be — wider than a step, because a note is a sentence or two about how the
     * work went rather than a line of a plan. The add box carries the same `maxlength`, so the field
     * stops a writer where this service would.
     */
    const val NOTE_MAX = 500

    fun assigned(): List<TaskItem> = TaskRepository.findByKind(KIND_ASSIGNED)

    fun other(): List<TaskItem> = TaskRepository.findByKind(KIND_OTHER)

    /**
     * The cards one reader is allowed to see. Management sees the whole board — that is the page they
     * run the team from — and everybody else sees only the cards with their own number on them.
     *
     * This is the single place the rule lives: the Assigned tasks board, a project's board, the sidebar
     * count, the Calendar and the Team drill-down all read through here, so a task cannot leak out of a
     * view somebody forgot to filter.
     */
    fun assignedFor(employeeNumber: String, canManage: Boolean): List<TaskItem> =
        if (canManage) assigned() else assigned().filter { it.assignee == employeeNumber }

    /**
     * The **Other-task** (backlog) cards assigned to one reader — the personal slice of the board [other] returns
     * in full.
     *
     * Since **Stage 55** the Assigned tasks page prints this next to [assignedFor]'s project cards: a card that
     * belongs to no project is still work that is *on somebody*, and Stage 53 made that page "what is on me now"
     * for every role. A Viewer no longer has the shared Other tasks board in their sidebar (Stage 51), so this is
     * the one place their own backlog work reaches them. It is scoped by the assignee alone — the same rule the
     * project board uses, personal for management included — so the sidebar pill beside *Assigned tasks* can count
     * both lists and always match what that page then shows.
     */
    fun assignedOtherFor(employeeNumber: String): List<TaskItem> =
        other().filter { it.assignee == employeeNumber }

    /**
     * The percentage a set of [cards] measures together: the average of the cards that carry a plan, each
     * of them counted once.
     *
     * Weighting the cards equally rather than their steps is deliberate — how finely somebody breaks one
     * piece of work into a list is a style choice, and a card written in twelve small steps should not
     * outvote nine colleagues written in one line each. A card with no plan is not "0% done", it is
     * unmeasured, so it is left out; null answers when *nothing* in the set can be measured, which is what
     * lets the caller keep its own fallback rather than printing a number the checklists never claimed.
     */
    fun progressOf(cards: List<TaskItem>): Int? {
        val measured = cards.mapNotNull { it.progressPercent }
        if (measured.isEmpty()) return null
        return measured.average().roundToInt()
    }

    /**
     * Every project's progress, measured from the ticked boxes on its cards, keyed by project name — with
     * the maths of [progressOf] applied per project, and no entry for a project nothing can measure.
     *
     * [cards] defaults to *every* assigned card rather than the reader's own slice, because a project's
     * progress is a fact about the project: the Projects board, that project's page, Settings and Team all
     * print one number per project, and a Viewer's page disagreeing with the board they came from would
     * read as a bug rather than as privacy. Nothing but the percentage leaves this function — no titles,
     * no holders, no card counts. There are no per-reader roll-up numbers on a project page any more: Stage
     * 28 deleted that strip, and each card now carries its own `n of m done` while the board's column heads
     * carry their counts.
     */
    fun progressByProject(cards: List<TaskItem> = assigned()): Map<String, Int> =
        cards.groupBy { it.project }
            .mapNotNull { (name, group) -> progressOf(group)?.let { name to it } }
            .toMap()

    /**
     * The percentage to print for [project] out of the [measured] map: the one its checklists measure when
     * they can, and the number stored on the row when no card on this project has a plan yet.
     *
     * The fallback keeps a bar drawn on a project nobody has written steps for, which is the state every
     * project in this app was in before checklists existed; it is the same figure that row has always
     * shown, not a fresh claim about the work.
     */
    fun displayedProgress(project: Project, measured: Map<String, Int>): Int =
        measured[project.name] ?: project.progress

    fun buckets(): List<String> = BucketRepository.names()

    /**
     * The backlog's columns as rows rather than as names (Stage 40).
     *
     * The board used to need only the names, because the one thing anybody could do to a column was add
     * another. Renaming one and emptying one are both aimed at an id — a name is not a stable thing to put
     * in a form field when the same form can also change it.
     */
    fun bucketRows(): List<Bucket> = BucketRepository.findAll()

    /** How many cards this column holds, which is what a delete has to say out loud before it asks. */
    fun bucketCardCount(bucketId: Int): Int = TaskRepository.countInBucket(bucketId)

    /**
     * Put a new card on the Other tasks board, from the same popup an assigned card is written from.
     *
     * **Every signed-in account may do this**, which is the one place the backlog is wider than a project
     * board. It is the right thing for a system that tracks projects: work that is not on a project yet is
     * usually noticed by the person standing nearest to it, and making them ask management to type one line
     * is how it never gets written down. What they may say is still the split [create] draws: the title, the
     * column, the two dates and the priority, while the card lands **as theirs** and **Not started** — handing
     * work to another person, or saying it is already under way, stays management's call. The pinning happens
     * by rebuilding the draft *before* it is validated, so a hand-built POST that names a colleague's employee
     * number writes the caller's own.
     *
     * A backlog card belongs to a column and to **no project** — that is the whole difference between the two
     * boards — so the project field is not part of this form and the row is written with none whatever the
     * draft carries.
     *
     * The same [TaskSaveResult] shape as [create]: field-keyed errors plus the values to print back, so the
     * popup re-opens with the reason under the field that caused it.
     */
    fun createBacklog(draft: TaskDraft, callerEmpNo: String, canManage: Boolean): TaskSaveResult {
        val written = (if (canManage) draft else draft.copy(assigneeEmpNo = callerEmpNo, status = "Not started"))
            .copy(projectName = "", projectLane = null)
        val resolved = resolve(written, requireAssignee = false, inBacklog = true, creating = true)
        if (resolved.errors.isNotEmpty()) return TaskSaveResult(null, resolved.errors, written)
        val id = TaskRepository.insert(
            kind = KIND_OTHER,
            title = resolved.title,
            projectId = null,
            due = resolved.due,
            priority = resolved.priority,
            assigneeEmpNo = resolved.assigneeEmpNo,
            status = resolved.status,
            bucketId = resolved.bucketId,
            started = resolved.started,
        )
        // The same note an assigned card sends: the person it names hears about it, and somebody naming
        // themselves — which is every create this route makes for an account that is not management — does not.
        NotificationService.taskAssigned(id, callerEmpNo)
        return TaskSaveResult(id, emptyMap(), written)
    }

    /**
     * Kanban drag-drop status change. Mirrors the legacy guard: a manager may move any task,
     * a non-manager may only move a task assigned to them. Returns false when the task is
     * missing, the status is not whitelisted, or the caller is not permitted.
     *
     * A card that really crosses into or out of *Completed* also has its plan moved here, by
     * [syncPlanWithStatus] — this is the route a drag answers to (and the route a project card's ✓ Done
     * button posts, since Stage 103 retired the arrows that used to post here too), so both halves of the
     * tick-and-restore rule live in the service rather than in whichever page noticed the move.
     */
    fun updateStatus(id: Int, status: String, callerEmpNo: String, canManage: Boolean): Boolean {
        if (status !in TASK_STATUSES) return false
        val task = TaskRepository.findById(id) ?: return false
        if (!canManage && task.assignee != callerEmpNo) return false
        TaskRepository.updateStatus(id, status)
        syncPlanWithStatus(id, task.status, status)
        return true
    }

    /**
     * Keep a card's checklist in step with the card's own status, on the rule the user asked for:
     * finishing a card means every step on it was done, and taking it back off *Completed* means the
     * plan is not a wall of ticks any more — it is whatever it was before the finish ticked it.
     *
     * Two deliberate details:
     *
     *  - Only a real crossing moves anything. A save that keeps the status where it was — which is
     *    most saves through the editor, since a due-date edit carries the card's own status back
     *    through it — must not overwrite the memory a pending restore is holding.
     *  - A card created straight into *Completed* needs neither call: it has no steps to tick at the
     *    moment it is written. Steps written onto it afterwards land unticked like any other (Stage 101),
     *    so a card finished before its plan was known reads the plan it actually has — the finish ticks
     *    what is there when it happens, not what is written after it.
     */
    private fun syncPlanWithStatus(taskId: Int, from: String, to: String) {
        if (from == to) return
        when (to) {
            STATUS_COMPLETED -> ChecklistRepository.finishAll(taskId)
            else -> if (from == STATUS_COMPLETED) ChecklistRepository.restoreAll(taskId)
        }
    }

    /**
     * Whether this account may put a **new** card on a project: management, or the one account that
     * project names as its manager.
     *
     * The second half compares employee numbers — `projects.manager_emp_no` is the column, and the
     * display name a page prints is resolved from it — so the right follows an account, not a string
     * somebody could type into a form. A project with no manager set grants it to nobody but management.
     * [create] asks this before it writes anything, which is what keeps "you may add to *this* project"
     * from becoming "you may add to any project": the caller's card is pinned to [projectId] there.
     */
    fun mayAddTasks(projectId: Int, callerEmpNo: String, canManage: Boolean): Boolean {
        if (canManage) return true
        if (projectId <= 0) return false
        return ProjectRepository.managerOf(projectId) == callerEmpNo
    }

    /**
     * Create an assigned task from the popup: management from any page, and a project's own manager
     * from that project's page. Same field-keyed errors as [updateAssigned]; the card lands in the
     * column chosen in the form, which defaults to "Not started" and afterwards moves by dragging.
     *
     * [callerEmpNo] is who pressed the button: [canManage] comes from the session and is what decides the
     * role half, [pageProjectId] is the project the popup was opened on — server-rendered, never typed —
     * and the number is what tells [NotificationService] not to notify somebody about their own
     * assignment.
     */
    fun create(
        draft: TaskDraft,
        callerEmpNo: String,
        canManage: Boolean,
        pageProjectId: Int = 0,
    ): TaskSaveResult {
        val pageProject = if (pageProjectId > 0) ProjectRepository.findById(pageProjectId) else null
        if (!canManage && !(pageProject != null && mayAddTasks(pageProjectId, callerEmpNo, false))) {
            return TaskSaveResult(
                null,
                mapOf(TaskField.PERMISSION to "Only management or this project's manager can add tasks."),
                draft,
            )
        }
        // A project's manager writes cards for *this* project, so the page picks it the way
        // [updateAssigned] rebuilds a protected field from the row: standing on project A and posting
        // project B is not something this account may do. Management keeps the form's choice, because
        // their popup lists every project on purpose.
        val written = if (canManage || pageProject == null) draft else draft.copy(projectName = pageProject.name)
        val resolved = resolve(written, creating = true, laneRequired = true)
        if (resolved.errors.isNotEmpty()) return TaskSaveResult(null, resolved.errors, written)
        val id = TaskRepository.insert(
            kind = KIND_ASSIGNED,
            title = resolved.title,
            projectId = resolved.projectId,
            due = resolved.due,
            priority = resolved.priority,
            assigneeEmpNo = resolved.assigneeEmpNo,
            status = resolved.status,
            bucketId = null,
            started = resolved.started,
            projectBucketId = resolved.projectBucketId,
        )
        // The assignee hears about it — unless they assigned themselves, which [NotificationService]
        // skips, because that person just pressed the button.
        NotificationService.taskAssigned(id, callerEmpNo)
        return TaskSaveResult(id, emptyMap(), written)
    }

    /**
     * The card somebody is allowed to **open**, or null when they are not.
     *
     * **Stage 86 widened this from an editor to a card.** It used to hand a card only to management and to the
     * person it is assigned to, on the reasoning that opening was editing; the user asked for the other reading —
     * *a non-manager may click a card and see it, not change it* — so the popup now opens for any reader and the
     * writes inside it are what narrow. This is only the read: [updateAssigned], [toggleChecklistItem] and
     * [addNote] each keep their own rule and refuse the stranger the popup now admits.
     *
     * What "any reader" means is the board's own audience, not every account everywhere. A **project** card is
     * on the project's page, which is the one shared, whole-project board every signed-in account may read
     * (Stage 56), so anybody may open it. A **backlog** card is on the Other tasks board, and since Stage 51 a
     * non-manager's view of that board is only their own cards — so somebody else's card there is not theirs to
     * open, and a hand-typed `?edit=` for one comes back as the ordinary page, exactly as a card that does not
     * exist does.
     *
     * The render layer asks this before it fills the popup, so the answer here is what decides whether a URL
     * opens a card at all; whether the popup it opens may be written is [mn.murray.pts.TaskForm]'s answer.
     */
    fun openableBy(id: Int, callerEmpNo: String, canManage: Boolean): TaskItem? {
        val task = TaskRepository.findById(id) ?: return null
        // Stage 81: an archived card is not on any board, so it has no popup to open. Its two controls used to
        // live in the project's Archive panel and are now reachable only by a hand-built POST (Stage 106 retired
        // the panel); either way a hand-typed `?edit=` for a card somebody put away must land on the ordinary page
        // rather than on an editor for work that is not there.
        if (task.archived) return null
        if (canManage) return task
        // Stage 86: a project card is on a board the whole team reads. A backlog card is only ever on a
        // non-manager's board when it is theirs ([mn.murray.pts.otherColumns]).
        if (task.isBacklog && task.assignee != callerEmpNo) return null
        return task
    }

    /**
     * Edit an assigned task from the card popup.
     *
     * Two rights, not one: management may change every field, and the person the card is assigned to
     * may change the three that describe their own day on it — start date, due date and priority. The
     * title, the project, the assignee, the status and the lane on its project's board stay with
     * management, because those move work between people and between columns rather than booking time on it.
     * A **backlog** card's column belongs to that same management set since Stage 49: it used to be an extra
     * right for the card's owner, on the reasoning that nothing on that board drags so the picker had to stand
     * in for the drag [moveToLane] allowed there. Both halves of that are gone — the user asked for management
     * to move cards everywhere — so where a card sits is management's answer on either board, and what an
     * owner keeps is the three fields that describe their day.
     *
     * The narrowing happens by rebuilding the draft from the stored row, not by trusting what the form
     * left out. A disabled input posts nothing, so reading the form field by field would take a blank
     * for "unchanged"; and a hand-built POST from a normal account arrives with all seven fields filled
     * and must not slip a reassignment through. Every field the caller may not touch is read back from
     * the database, so the only thing that can change on that path is what the rule allows.
     *
     * Nothing is written while [TaskSaveResult.errors] is non-empty; the same result carries
     * [TaskSaveResult.values] back so the popup can re-render open with the message under the
     * field it belongs to.
     */
    fun updateAssigned(id: Int, draft: TaskDraft, callerEmpNo: String, canManage: Boolean): TaskSaveResult {
        val existing = TaskRepository.findById(id)
            ?: return TaskSaveResult(null, mapOf(TaskField.TITLE to "That task no longer exists."), draft)
        // Stage 81: the archive is a place a card waits, not a place it is edited. Until it is back on a board the
        // card takes no write — a hand-built POST naming it changes nothing, the same answer the popup gives by not
        // opening on an archived id at all (see [openableBy]). **Stage 106** took the panel's Restore button out of
        // the app, so the message no longer sends the reader to one; it says what is true of the row.
        if (existing.archived) {
            return TaskSaveResult(
                null,
                mapOf(TaskField.PERMISSION to "That task is archived, and an archived task cannot be changed."),
                draft,
            )
        }
        if (!canManage && existing.assignee != callerEmpNo) {
            return TaskSaveResult(
                null,
                mapOf(TaskField.PERMISSION to "Only management or the person this task is assigned to can change it."),
                draft,
            )
        }
        // Which board this card is on is a fact about the row, and which board it is *going* to be on is a fact
        // only management can state (Stage 48): the popup's "where it lives" picker is a management field like
        // the title above it, so for anybody else the answer is the row's own value and a hand-built POST that
        // names the other board changes nothing.
        //
        // A form that names **no** board is the same answer as a form that names nothing about a lane: the card
        // stays where it is. The popup prints the picker whenever management may refile, so this is the odd case
        // — a route or a test posting the fields it has without the one that says which half they belong to —
        // and the safe reading of that is the row, not a crossing nobody asked for.
        val inBacklog = existing.isBacklog
        val goesBacklog = if (canManage && draft.board != null) draft.board == BOARD_OTHER else inBacklog
        val moving = goesBacklog != inBacklog
        // A backlog card has no project and no lane, whatever a form posts into those fields, so they are read
        // back from the row for every role — the same way every field a caller may not write is read back below.
        val posted = if (goesBacklog) draft.copy(projectName = "", projectLane = null) else draft
        val allowed = if (canManage) posted else TaskDraft(
            title = existing.title,
            projectName = existing.project,
            assigneeEmpNo = existing.assignee,
            started = posted.started,
            due = posted.due,
            priority = posted.priority,
            status = existing.status,
            // Which column a card sits in is management's answer on **both** boards since Stage 49, so the lane
            // and the backlog's column are treated alike here: neither is what the form said. **null** is the
            // right value twice over for each — the field is greyed, so a real page posts nothing, and a
            // hand-built POST that names a lane or a column is read back as the row's own place.
            //
            // The lane used to be the exception, on the reasoning that [moveToLane] let the card's owner drag
            // between lanes; saving a due date must not refile a card, and since Stage 49 the drag is the
            // project's manager's too, so there is nothing left to except. The column likewise used to be an
            // exception because nothing on the backlog drags and the picker stood in for the drag; the user
            // retired the exception rather than the drag, so a non-manager's card stays in its column and
            // [resolve] keeps the stored value.
            projectLane = null,
            columnId = null,
        )
        // A card that arrived on no project and no board is the one state this stage leaves behind rather than
        // forbids. Its owner cannot be told to choose a project by a field they may not write, so the save stops
        // here with the sentence that says who can: management picks the world, the assignee carries on.
        if (!canManage && !goesBacklog && existing.project.isBlank()) {
            return TaskSaveResult(
                null,
                mapOf(TaskField.PROJECT to "This card is on no project yet — ask management to choose one."),
                allowed,
            )
        }
        // A lane is a column **of a project**, so a save that hands a card to a different project leaves its
        // column behind on the old board exactly the way a crossing to the backlog does: the stored lane is not a
        // place on the new one, and keeping it would file the card under an id that belongs to somebody else's
        // board — a card that prints on neither. The form has to name where it lands, which is what
        // [laneRequired] below asks for. A save that keeps the same project is not this, so an ordinary edit
        // still keeps its lane by doing nothing with the field.
        val changingProject = !goesBacklog && allowed.projectName.trim() != existing.project
        val resolved = resolve(
            allowed,
            storedLane = if (changingProject) null else existing.projectBucketId.takeIf { it != 0 },
            // A backlog card belongs to a column, not to a person, so the popup that opens on one may be
            // saved with the assignee still blank. The row decides that, not the form: a hand-built POST
            // cannot make an assigned card assignable-to-nobody by leaving the field out.
            requireAssignee = !goesBacklog,
            // The column is checked against the board's own columns only for a card that lives — or is being
            // sent — there; a value posted at a card that stays on a project is not a column of any board it
            // could be filed on.
            inBacklog = goesBacklog,
            storedColumn = existing.bucketId.takeIf { it != 0 },
            // A card crossing into the backlog is a card that has to be given a column, which is the one thing
            // [creating] asks for: a row with none is invisible on the only board that prints it. A card
            // crossing onto a project has to be filed in a lane for the same reason — its old board's lane is
            // not a place on this one.
            creating = moving && goesBacklog,
            laneRequired = (moving && !goesBacklog) || changingProject,
        )
        // `allowed` rather than the raw [draft] on the way back: what the popup re-renders is what the
        // server actually tried to write, so an owner's rejected due date comes back with their title and
        // project still in the greyed fields instead of blank.
        if (resolved.errors.isNotEmpty()) return TaskSaveResult(null, resolved.errors, allowed)
        TaskRepository.update(
            id = id,
            title = resolved.title,
            projectId = resolved.projectId,
            due = resolved.due,
            priority = resolved.priority,
            assigneeEmpNo = resolved.assigneeEmpNo,
            status = resolved.status,
            started = resolved.started,
        )
        // The popup's **Status** picker is one of the two ways a card changes status, the drag being the other
        // since Stage 103 retired the arrows, so the plan follows it by the same call and the same rule — finished
        // on the way in, back to where it stood on the way out. A save that did not move the status is a no-op
        // here, which is what keeps an assignee's due-date edit from re-writing the memory a restore needs.
        syncPlanWithStatus(id, existing.status, resolved.status)
        // The lane is a separate statement, because [update] deliberately does not write it — see
        // [TaskRepository.setProjectBucket]. Writing only when it actually moved keeps an assignee's due-date
        // save from touching the board at all, and files a card that has just been handed to a project — or to
        // a lane of the one it is on — in one statement rather than two.
        val was = existing.projectBucketId.takeIf { it != 0 }
        if (resolved.projectBucketId != was) TaskRepository.setProjectBucket(id, resolved.projectBucketId)
        // The backlog's column likewise: [update] never writes it, and only a card that really changed column is
        // written — including the card that just crossed onto a project, whose column is now nothing at all. A
        // row left pointing at both a lane and a column would be filed on two boards at once.
        val wantedColumn = if (goesBacklog) resolved.bucketId else null
        val wasColumn = existing.bucketId.takeIf { it != 0 }
        if (wantedColumn != wasColumn) TaskRepository.setBucket(id, wantedColumn)
        // The board itself moves last, and only on a real crossing: `kind` is the one column that says which
        // page prints this card, and a save that changed none of the above must not rewrite it.
        if (moving) TaskRepository.setKind(id, if (goesBacklog) KIND_OTHER else KIND_ASSIGNED)
        // Only a *move* tells somebody: renaming a card or shifting its due date must not mail
        // the person who is already working on it. The new assignee hears; the one who lost the
        // card does not, which is what the agreed scope says.
        if (resolved.assigneeEmpNo != null && resolved.assigneeEmpNo != existing.assignee) {
            NotificationService.taskAssigned(id, callerEmpNo)
        }
        return TaskSaveResult(id, emptyMap(), draft)
    }

    /**
     * Delete an assigned task. Manager-only, and deliberately stricter than [updateAssigned]: an owner
     * may book their own time on a card, but making the card disappear is management's call — the
     * caller's employee number is kept in the signature so the rule reads the same from any route.
     */
    fun deleteAssigned(id: Int, callerEmpNo: String, canManage: Boolean): Boolean {
        if (!canManage) return false
        // Stage 81: a project card's permanent delete is the archived-card route's, one deliberate step after the
        // card has left the board. A hand-built POST naming an archived row has no control behind it and is refused.
        if (TaskRepository.findById(id)?.archived == true) return false
        return TaskRepository.delete(id) > 0
    }

    // --- the lanes of one project's board (V7) ------------------------------------------------

    /** The lanes this project's board is built from, in the order the board prints them. */
    fun lanesOf(projectId: Int): List<ProjectBucket> = ProjectBucketRepository.ofProject(projectId)

    /**
     * How many cards are filed in each of this project's buckets, keyed by bucket id — the figure a picker
     * needs before it can offer to take one off (Stage 73). A bucket with nothing in it is absent rather than
     * zero, so `counts[id].orEmpty() > 0` and `id in counts` answer the same question.
     */
    fun cardCounts(projectId: Int): Map<Int, Int> = ProjectBucketRepository.cardCounts(projectId)

    /**
     * Whether this account may change a project's lane list: management, or the one account that project
     * names as its manager — [mayAddTasks] by another name, deliberately.
     *
     * The two rights are the same person for the same reason: somebody who may put a card on this board is
     * somebody who may decide what the board is divided into, and a lane list a Viewer could edit would let
     * a person re-shape a project that is not theirs.
     */
    fun mayEditLanes(projectId: Int, callerEmpNo: String, canManage: Boolean): Boolean =
        mayAddTasks(projectId, callerEmpNo, canManage)

    /**
     * Put one more lane on a project's board.
     *
     * The name is trimmed, bounded to [PROJECT_BUCKET_MAX] (the same length the input's `maxlength` stops at,
     * so nobody types a lane name and then has it cut), and refused when the project already has that lane —
     * two columns of the same name on one board is a mistake, not a grouping. Every refusal comes back as a
     * [LaneAddResult] naming which field to complain about, because the box stays on the page.
     *
     * [colourSlot] (Stage 80) is the hue the reader picked for this column. Anything outside the palette — or
     * simply not asked, which is every path that prints no picker — is read as null, and the lane wears the
     * positional shade it has always worn.
     */
    fun addLane(
        projectId: Int,
        name: String,
        callerEmpNo: String,
        canManage: Boolean,
        colourSlot: Int? = null,
    ): LaneAddResult {
        val slot = colourSlot?.takeIf { it in 0 until BUCKET_DOT_COLOURS }
        if (!mayEditLanes(projectId, callerEmpNo, canManage)) {
            return LaneAddResult.refused(LaneError.DENIED)
        }
        if (ProjectRepository.findById(projectId) == null) return LaneAddResult.refused(LaneError.NO_PROJECT)
        val text = name.trim()
        if (text.isEmpty()) return LaneAddResult.refused(LaneError.BLANK)
        if (text.length > PROJECT_BUCKET_MAX) return LaneAddResult.refused(LaneError.TOO_LONG)
        // Compared after the trim and ignoring case, so " engineering " onto a board that already prints
        // Engineering is refused rather than becoming a second column of the same phase with a different
        // initial letter. The board's names come from this table, so this is the only place the rule lives.
        if (ProjectBucketRepository.namesOf(projectId).any { it.equals(text, ignoreCase = true) }) {
            return LaneAddResult.refused(LaneError.DUPLICATE)
        }
        val id = ProjectBucketRepository.insert(projectId, text, slot)
        return LaneAddResult.added(ProjectBucket(id, projectId, text, colourSlot = slot))
    }

    /**
     * Take one lane off a project's board, or refuse and change nothing.
     *
     * The same right that adds a lane removes one — [mayEditLanes], management or the account this project
     * names as its manager — because the list of phases is one decision read two ways, and a reader who may
     * add **Survey** must be the reader who may decide it is not needed after all.
     *
     * The lane is looked up rather than trusted from the form: it has to exist *and* belong to the project in
     * the URL, which is what stops a POST naming a lane of somebody else's project from emptying their board.
     *
     * **Since Stage 81 a lane that holds cards takes them with it, into the Archive.** Before that the removal was
     * the easy half of the decision and the work was left to the schema: `tasks.project_bucket_id` answers
     * `ON DELETE SET NULL`, so the cards stayed on the board filed under nothing, visible only as an **Unsorted**
     * column nobody chose — a card that has gone missing from its reader's point of view, whatever the foreign key
     * calls it. The prototype this board was copied from had a third answer and the ask was to port it: every card
     * filed in the column is flagged archived with its lane remembered, the lane row then goes, and the cards can
     * be put back one at a time by [restoreCard] with their steps and their log intact. **Since Stage 106** no page
     * prints a control for that, so [removeLane]'s sentence only promises that the cards leave the board; the
     * restore route is still there for a hand-built POST, and the rows are untouched.
     * [TaskRepository.archiveLane] runs **before** the delete for exactly that reason — after it, the cards have
     * lost the lane the snapshot exists to name.
     *
     * One lane is left standing, because a project page without lanes has nothing to be a lane board with: it
     * would fall back to the five status columns, which is the shape Stage 75 took off that page for good.
     * So the last lane of a project refuses to go, and says so under the box. That is now the only thing that
     * stops a removal — work stopped being a reason the day a column's work gained somewhere to go.
     *
     * [LaneRemoval] carries both halves of the answer: the [LaneError] that stopped it, or how many cards went with
     * the column, which is the sentence the board has to print over the columns the reader is looking at.
     *
     * **Since Stage 75 the three a board opens with are removable.** They were Stage 48's floor, refused here
     * by a rule of their own on the reasoning that the general board's popup files a card by those six names
     * and a board missing one could not receive it. Neither half of that survives: the six are gone (V16), the
     * popup files a card in *this project's* lanes read from this project's board, and a project whose manager
     * retitled or emptied **Engineering** has said something about that project — not about the company. What
     * still stops a removal is work: the last lane of a board, and nothing else.
     */
    fun removeLane(projectId: Int, laneId: Int, callerEmpNo: String, canManage: Boolean): LaneRemoval {
        if (!mayEditLanes(projectId, callerEmpNo, canManage)) return LaneRemoval.refused(LaneError.DENIED)
        if (ProjectRepository.findById(projectId) == null) return LaneRemoval.refused(LaneError.NO_PROJECT)
        val lane = ProjectBucketRepository.find(laneId)
            ?: return LaneRemoval.refused(LaneError.NO_LANE)
        if (lane.projectId != projectId) return LaneRemoval.refused(LaneError.NO_LANE)
        if (ProjectBucketRepository.ofProject(projectId).size < 2) return LaneRemoval.refused(LaneError.LAST_LANE)
        val archived = TaskRepository.archiveLane(laneId, lane.name, archiveDay())
        return if (ProjectBucketRepository.delete(laneId) > 0) LaneRemoval.gone(archived)
        else LaneRemoval.refused(LaneError.NO_LANE)
    }

    // --- the archive (V18) ------------------------------------------------------------------------

    /** The day an archive line is written, in the `yyyy-MM-dd` every other date in this app prints. */
    private fun archiveDay(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** This project's archived cards, newest first — the rows nothing prints since Stage 106 retired the panel. */
    fun archived(projectId: Int): List<TaskItem> = TaskRepository.findArchived(projectId)

    /**
     * Take one card off its project's board and into the Archive.
     *
     * **No page prints a control for this since Stage 106** (the card-level route is kept for the same reason as
     * the panel's own two — see `Application.kt`), so in practice the archive is written by [removeLane].
     *
     * The right is the board's, not the card's: [mayEditLanes] — management, or the account this project names as
     * its manager. That is the same line that removes the column the card stands in, and it is deliberately not
     * [openableBy]: an assignee may book their own time on a card, tick its steps and write its log, while taking
     * the card off somebody else's board is the project's call — as it has been for every destructive write here
     * since [deleteAssigned]. The card's lane is snapshotted off the row, so [restoreCard] can put it back there
     * and the row keeps the name of the column it left.
     *
     * A backlog card is refused, because it has no lane and no project: the Other tasks board is cut by status
     * (Stage 57) and its columns are a shared list, so archiving one of its cards would be a card vanishing from a
     * board into an archive that belongs to a page it does not stand on. That board keeps management's **Delete
     * task**, exactly as the prototype's standalone card keeps its delete.
     */
    fun archiveCard(taskId: Int, callerEmpNo: String, canManage: Boolean): Boolean {
        val task = TaskRepository.findById(taskId) ?: return false
        if (task.isBacklog || task.archived || task.projectId == 0) return false
        if (!mayEditLanes(task.projectId, callerEmpNo, canManage)) return false
        return TaskRepository.archive(
            taskId,
            task.projectBucketId.takeIf { it != 0 },
            task.projectBucket,
            archiveDay(),
        )
    }

    /**
     * Put one archived card back on its project's board, and answer with the lane it landed in.
     *
     * Null means nothing moved: the card is not archived, it is not on this project, the board has no lane to
     * receive it, or the reader has no right here — the same [mayEditLanes] line that archived it. The lane comes
     * back out of the call so the page can name it, because the two answers a reader can get here are not the same
     * sentence: a card whose column is still on the board goes home to it, and a card archived *with* a column that
     * has since gone — the usual case, since archiving a lane is what takes a bucket off the board — lands in the
     * project's first column rather than being filed under nothing. An unfiled card would be the **Unsorted**
     * column this stage exists to stop producing.
     *
     * The card's status, its plan and its log are not touched. That is the whole of the flag design (V18): the row
     * was never moved, so restoring it is one field off and a lane back, and a card that left with three of four
     * steps ticked comes back with three of four steps ticked.
     */
    fun restoreCard(taskId: Int, projectId: Int, callerEmpNo: String, canManage: Boolean): String? {
        val task = TaskRepository.findById(taskId) ?: return null
        if (!task.archived) return null
        if (task.projectId != projectId) return null
        if (!mayEditLanes(projectId, callerEmpNo, canManage)) return null
        // The snapshot resolves only against the project it was taken from: a lane id is a row's identity, and a
        // lane that has since been dropped — or belongs to a different project's board — is not a destination.
        val remembered = task.archivedLaneId.takeIf { it != 0 }
            ?.let { id -> ProjectBucketRepository.find(id)?.takeIf { it.projectId == projectId } }
        val home = remembered ?: ProjectBucketRepository.ofProject(projectId).firstOrNull() ?: return null
        return if (TaskRepository.restore(taskId, home.id)) home.name else null
    }

    /**
     * Delete an archived card for good, with its plan and its log.
     *
     * **No page prints a control for this since Stage 106**: Stage 81 put it on the Archive panel's rows, and that
     * panel went with its opener. The route answers a hand-built POST and nothing else. Since **Stage 84** the
     * permanent delete a reader can actually press is the live card popup's **Delete task** — Stage 81 had removed
     * that button in favour of *Archive task*, and Stage 84 put it back. It is gated on the card actually being
     * archived: a live card is not reachable here, and a route that would delete one handed to it by hand is a card
     * destroyed by a click meant to restore it. The right is the same [mayEditLanes] line as the archive itself,
     * because whoever may put a card away may agree it is gone.
     *
     * The children need no cleanup. `task_checklist_items` and `task_notes` cascade from the card and
     * `PRAGMA foreign_keys` is on for every connection, so dropping the row takes its steps, its notes and its
     * completion memory with it — [deleteAssigned] has always relied on the same thing.
     */
    fun deleteArchived(taskId: Int, projectId: Int, callerEmpNo: String, canManage: Boolean): Boolean {
        val task = TaskRepository.findById(taskId) ?: return false
        if (!task.archived || task.projectId != projectId) return false
        if (!mayEditLanes(projectId, callerEmpNo, canManage)) return false
        return TaskRepository.delete(taskId) > 0
    }

    /**
     * Change one lane's heading, or refuse and change nothing (Stage 74).
     *
     * The `⋯` on a project board's column offers this, and it is the only way a lane is renamed: the
     * picker underneath the board ticks and unticks lanes, it does not retitle them.
     *
     * **A rename is by id, never by name.** The popup on the Other tasks board names its column by id because
     * two columns of one board can be called the same thing once management renames one of them — here the name
     * is what the reader is changing, so the only address that survives the write is the row's own. The lane is
     * therefore looked up and checked against the project in the URL exactly as [removeLane] checks it, which is
     * why a POST naming another project's lane renames nothing.
     *
     * The name rules are [addLane]'s — trimmed, bounded to [PROJECT_BUCKET_MAX], and refused when another lane
     * of this board already carries it ignoring case — and since **Stage 75 they are all of them**: a lane that
     * opens the board is retitled exactly like a lane somebody added. Until then one of the standard names kept
     * its name by a rule of its own, because the six were what the general board's popup filed a card by; the
     * popup now files by *this* project's lanes, so a heading is this board's own business.
     *
     * A rename *into* one of the three names needs no special case any more either: it is refused as a
     * [LaneError.DUPLICATE] when the board still carries that lane, and is a plain rename of some other column
     * onto a phase name when the manager has taken that one off — which is a thing a project is now free to do.
     *
     * What a rename deliberately does **not** touch is the category's bucket catalogue. A lane's cards point at
     * it by id, so the heading is this board's own business; the catalogue is the list of names new projects of
     * the category are *offered*, and widening or retitling that is management's write on the Settings sheet and
     * on this page's picker, not a side effect of retitling one column.
     */
    fun renameLane(
        projectId: Int,
        laneId: Int,
        name: String,
        callerEmpNo: String,
        canManage: Boolean,
        colourSlot: Int? = null,
    ): LaneRenameResult {
        val slot = colourSlot?.takeIf { it in 0 until BUCKET_DOT_COLOURS }
        if (!mayEditLanes(projectId, callerEmpNo, canManage)) {
            return LaneRenameResult.refused(LaneError.DENIED)
        }
        if (ProjectRepository.findById(projectId) == null) {
            return LaneRenameResult.refused(LaneError.NO_PROJECT)
        }
        val lane = ProjectBucketRepository.find(laneId)
            ?: return LaneRenameResult.refused(LaneError.NO_LANE)
        if (lane.projectId != projectId) return LaneRenameResult.refused(LaneError.NO_LANE)
        val text = name.trim()
        if (text.isEmpty()) return LaneRenameResult.refused(LaneError.BLANK, "Give the bucket a name.")
        if (text.length > PROJECT_BUCKET_MAX) {
            return LaneRenameResult.refused(
                LaneError.TOO_LONG,
                "Bucket names are $PROJECT_BUCKET_MAX characters or fewer.",
            )
        }
        // Stage 80: the popup sends this column's heading and its colour in one post, so either half of it can
        // be the whole of what moved. A name that did not change is no longer the same as nothing changed — the
        // reader may well have just moved the shade on the swatch row beside the field.
        val recolour = slot != null && slot != lane.colourSlot
        if (text.equals(lane.name, ignoreCase = true)) {
            return if (!recolour) LaneRenameResult.saved
            else if (ProjectBucketRepository.setColour(laneId, slot)) LaneRenameResult.saved
            else LaneRenameResult.refused(LaneError.NO_LANE)
        }
        val taken = ProjectBucketRepository.ofProject(projectId)
            .filter { it.id != laneId }
            .any { it.name.equals(text, ignoreCase = true) }
        if (taken) {
            return LaneRenameResult.refused(
                LaneError.DUPLICATE,
                "This project already has a bucket called $text.",
            )
        }
        if (!ProjectBucketRepository.rename(laneId, text)) {
            return LaneRenameResult.refused(LaneError.NO_LANE)
        }
        // Both halves of one post, name first: a colour written before a refused rename would have repainted a
        // column the reader was told nothing about.
        if (recolour) ProjectBucketRepository.setColour(laneId, slot)
        return LaneRenameResult.saved
    }

    /**
     * Save the whole bucket picker on a project page in one write (Stage 73).
     *
     * The form posts the **names** it has ticked, plus one name typed into its own add box, and this is what
     * answers for both: the lanes left standing on the board afterwards, or the single reason it changed
     * nothing. Names rather than ids because a name is what a card is filed by on the general board (Stage 48)
     * and the only answer that survives the category's catalogue being edited while the page sits open.
     *
     * Four rules hold it together:
     *
     *   * **every lane on the board is in this form's hands, the three phases included**. Stage 75 made those
     *     three ordinary columns; since Stage 100 they are not even written by the create any more unless the
     *     category lists them, so the picker treats them exactly like every other lane. A name the naming rules
     *     would refuse is still refused at the field, through [BucketDefaultsService.nameProblem], which has no
     *     standard-name branch left to switch off (Stage 100).
     *   * **a bucket that holds cards comes off with its work in it, and the work leaves the board with it**. Until
     *     Stage 81 this was the picker's refusal — "still has cards in it, move them to another bucket first",
     *     which made the reader relocate every card by hand before the column they had already decided against
     *     could be taken away. The prototype this page was copied from answers a third way, and it is the one
     *     shipped: the lane's cards are flagged archived with [TaskRepository.archiveLane] in the same breath the
     *     lane row is dropped, so they leave the board *as a set*, plan and log and all. **Since Stage 106** no page
     *     prints them afterwards — the restore and permanent-delete routes still answer a hand-built POST, but the
     *     sentence this save closes with promises only that the work left the board. Two older answers to the same
     *     situation are worth naming, because both are still true of the schema: [removeLane] alone would have let
     *     the cards fall to no bucket under `ON DELETE SET NULL` and reappear on an **Unsorted** column nobody
     *     chose, and dropping the cards with the lane was never on the table — the schema's `ON DELETE SET NULL`
     *     exists precisely so that a lane's removal cannot delete work.
     *   * **a name the category does not know is this project's, unless the form ticks otherwise**. Since
     *     Stage 77 [alsoForType] is that tick, and it is off by default: a bucket named here goes on this board
     *     and nowhere else, which is what "every project has its own buckets" means in practice — the column a
     *     Fabrication job needed for its own weld repair is not quietly made a rule for every future Fabrication
     *     job. Ticked, and only management may tick it, the name also goes on the
     *     [TypeDefaultBucketRepository] catalogue, so the next project of this category is offered it. A
     *     project's own manager who is not management therefore gets the whole of their own board to name, and
     *     nothing shared: their tick is refused at the field, because a box that writes nothing is worse than a
     *     box they were not shown. No name is known to a board without being on it or in its category's list:
     *     since Stage 100 the phases are ordinary buckets, so putting **Engineering** back on a board is a
     *     project-local column like any other (tick it to share it with the category).
     *   * **a board never ends with nothing on it**. Unticking every lane is refused as [LaneError.LAST_LANE],
     *     the same rule [removeLane] applies to a column's `⋯`: a project page with no lanes has no bucket board
     *     to draw, and Stage 75 decided that page is its columns for good.
     *
     * Nothing is written until the whole list passes, because a picker that applied its first three ticks and
     * then refused the fourth would leave a board that matches nothing on the screen.
     *
     * [removeLane] is the same rule at one column instead of a whole board, and since Stage 81 it archives the
     * same way: it is what a column's `⋯` posts to, and a reader who removes **Weld repair** from the head of
     * that column and a reader who unticks it on the form are making one decision. `tasks.project_bucket_id`'s
     * `ON DELETE SET NULL` is still the schema's backstop that dropping a lane cannot delete the work in it — it
     * simply no longer gets the chance to, because the cards are already off the board by the time the row goes.
     *
     * Since **Stage 83** the add box asks for a name and nothing else: a column this save creates is written
     * with no colour of its own, so it wears the positional shade the board has always given a new column —
     * whether it was typed here or ticked from the catalogue. A reader who wants a different one recolours it
     * from the column's own `⋯`, the same popup's manage half.
     */
    fun saveLanes(
        projectId: Int,
        posted: List<String>,
        newBucket: String,
        callerEmpNo: String,
        canManage: Boolean,
        alsoForType: Boolean = false,
        // Stage 110: naming a column on *this* board is MANAGE_BUCKETS, but offering the name to the
        // category's catalogue is MANAGE_PROJECT_TYPES — two chips that a custom role may hold apart. The
        // default keeps the pre-110 call sites (and their tests) coherent: whoever could run a board could
        // also widen its category.
        canManageTypes: Boolean = canManage,
    ): LaneSaveResult {
        if (!mayEditLanes(projectId, callerEmpNo, canManage)) return LaneSaveResult.refused(LaneError.DENIED)
        val project = ProjectRepository.findById(projectId) ?: return LaneSaveResult.refused(LaneError.NO_PROJECT)

        // The ticked set, trimmed and deduplicated ignoring case, in the order the form posted them. The add
        // box rides along as one more ticked name: to this service a bucket chosen and a bucket typed are the
        // same answer, which is the whole point of offering both on one page.
        val wanted = LinkedHashSet<String>()
        for (raw in (posted + listOf(newBucket)).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (wanted.any { it.equals(raw, ignoreCase = true) }) continue
            wanted += raw
        }

        // Every refusal first, so a picker that cannot be taken at its word changes no board at all. Since
        // Stage 100 there is one set of name rules for every place a bucket is named: the phases are ordinary
        // buckets, so nothing here has to switch a standard-name branch off.
        for (name in wanted) {
            BucketDefaultsService.nameProblem(name)?.let {
                return LaneSaveResult.refused(LaneError.BAD_NAME, it)
            }
        }
        val onBoard = ProjectBucketRepository.ofProject(projectId)
        val boardNames = onBoard.map { it.name }
        val catalogue = TypeDefaultBucketRepository.namesOf(project.type)
        // The names a board may be given without anybody widening a catalogue: this category's list. Since
        // Stage 100 the three phases are no longer a special case here either — a board that removed one and
        // wants it back is naming a new bucket like any other, project-local unless the tick shares it.
        val known = catalogue
        val extra = wanted.firstOrNull { name ->
            boardNames.none { it.equals(name, ignoreCase = true) } &&
                known.none { it.equals(name, ignoreCase = true) }
        }
        // Stage 77: a name this category has never heard of is now an ordinary project-local column, so the only
        // thing here that needs management is the *tick* that would share it. A non-management caller who posts
        // `also_type` anyway — a hand-built form, or a page left open while their role changed — is refused
        // rather than silently given the project-local answer, because the box they pressed said it would go
        // further than one board.
        if (extra != null && alsoForType && !canManageTypes) {
            return LaneSaveResult.refused(
                LaneError.CATALOGUE_DENIED,
                "Only management adds a new bucket to the ${project.type} catalogue.",
            )
        }
        val counts = ProjectBucketRepository.cardCounts(projectId)
        // Every lane the picker is about to take off, not just the first, and the work standing in them. Since
        // **Stage 81** an occupied lane is not a refusal: the untidy answer Stage 73 made the reader fix by hand
        // ("move them to another bucket first") became the prototype's third way — the column and everything in it
        // leave the board together, flagged archived. Since **Stage 106** no panel lists them, so the closing
        // sentence says the work left rather than where to go and find it.
        // The count is kept for the closing sentence, because a reader who just took three cards off the board
        // should be told how much left with the columns rather than left to find them empty.
        val leaving = onBoard.filter { lane -> wanted.none { it.equals(lane.name, ignoreCase = true) } }
        val held = leaving.sumOf { counts[it.id] ?: 0 }
        if (wanted.isEmpty()) {
            return LaneSaveResult.refused(
                LaneError.LAST_LANE,
                "A board keeps at least one bucket — tick one to leave standing.",
            )
        }

        // The board now: the unticked lanes go, whatever their name, and the ticked ones that are not on it yet
        // are appended in the order the picker posted them. A lane's cards are archived **before** its row is
        // dropped, which is the only order that works: the delete fires `ON DELETE SET NULL` on
        // `tasks.project_bucket_id`, and a card whose lane has already been nulled has lost the column it was
        // filed in — the snapshot [TaskRepository.archiveLane] writes is that card's memory of where it stood.
        for (lane in onBoard) {
            if (wanted.any { it.equals(lane.name, ignoreCase = true) }) continue
            TaskRepository.archiveLane(lane.id, lane.name, archiveDay())
            ProjectBucketRepository.delete(lane.id)
        }
        // The names this save put into the category's catalogue, kept for the closing sentence: a reader who
        // ticked a bucket the catalogue already knew changed one board, and it would be wrong to tell them they
        // changed every future project of the category too.
        val widened = mutableListOf<String>()
        for (name in wanted) {
            if (boardNames.any { it.equals(name, ignoreCase = true) }) continue
            // Keep the catalogue's own spelling when it knows the name, so a board never carries a lane that
            // differs from the default it came from by an initial letter — the domain's spelling of an opening
            // lane plays the same part, which is what keeps a re-ticked **production** from becoming a second
            // column beside the one the board still has.
            val stored = known.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
            // The catalogue only ever hears about this name when the reader's own tick said so (Stage 77);
            // the board gets the lane either way, and `ProjectBucketRepository.insert` is the only write a
            // caller who is not management can cause here. Neither row is given a colour (Stage 83): a new
            // bucket is painted by its place in the run, on this board and on the boards it later seeds.
            if (alsoForType && canManageTypes && known.none { it.equals(name, ignoreCase = true) }) {
                TypeDefaultBucketRepository.insert(project.type, stored)
                widened += stored
            }
            ProjectBucketRepository.insert(projectId, stored)
        }
        val now = ProjectBucketRepository.ofProject(projectId)
        // One sentence, and it says the thing the panel cannot: whether the picker also widened the category.
        // What the board itself now holds is on show underneath it, so repeating the list here would be a second
        // copy to keep in step with the first. Since Stage 75 the count is the whole board's, the three a project
        // opened with included — they are lanes like any other, and a sentence that quietly set three of them
        // aside would read as though the reader had just removed work from a board that had not changed.
        val board = "${now.size} bucket${if (now.size == 1) "" else "s"} on this project"
        val also = when {
            !widened.isEmpty() -> " — ${widened.joinToString()} added to the ${project.type} catalogue, so new " +
                "projects of that category are offered it"
            // Stage 81: the sentence names the work the untick took off the board, because a card that has gone
            // quiet on a column the reader just removed looks exactly like a card that has gone missing. **Stage
            // 106** retired the panel that used to list those cards, so the half of the answer that still holds is
            // that the work left the board with its column — it is not destroyed.
            held > 0 -> " — $held ${if (held == 1) "card" else "cards"} left the board with it"
            else -> ""
        }
        return LaneSaveResult(now, null, "Saved: $board$also.")
    }

    /**
     * Move a card into one of its own project's lanes.
     *
     * **Since Stage 49 the mover is [mayAddTasks]: management, or the account this card's project names as its
     * manager.** Until then the rule was the one [updateStatus] still uses — management, or the person the card
     * is assigned to — on the reasoning that a lane is a place on the board and moving a card to a place is
     * what an owner has always been allowed to do. The user retired that reasoning: what a card belongs to
     * *is* its project, and the lanes underneath it are the project's own structure, so filing a card in one
     * of them is the same kind of act as putting a new card on that project. That is also why the check below
     * asks about the card's project rather than about the card: a Viewer who manages project A may move A's
     * cards, and may not move A's neighbour on B even when both print on a page they can reach.
     *
     * Two things are still checked before anything is written, and neither is the form's word: the lane must
     * exist, and it must belong to the project this card is on. The second is why a card cannot be filed on a
     * lane of somebody else's project by posting that lane's id — and, since the mover is the project's
     * manager, it is also what makes that check load-bearing for permission rather than only for tidiness:
     * the project the lane belongs to is the project the mover must have a right to write for.
     *
     * A drag onto **no lane at all** — [UNSORTED_LANES], the old `0` — is refused since Stage 48: a card on a
     * project is filed in a sub-category, and Unsorted is where the cards that pre-date that rule rest, not a
     * column a board can be pushed into. The board does not offer it as a drop target either, so a person
     * dragging a legacy card has somewhere to send it and nowhere to take it back to.
     */
    fun moveToLane(id: Int, bucketId: Int, callerEmpNo: String, canManage: Boolean): Boolean {
        val task = TaskRepository.findById(id) ?: return false
        if (bucketId == 0) return false
        val lane = ProjectBucketRepository.find(bucketId) ?: return false
        val cardProjectId = if (task.project.isEmpty()) 0 else ProjectRepository.idByName()[task.project] ?: 0
        if (cardProjectId <= 0 || cardProjectId != lane.projectId) return false
        if (!mayAddTasks(cardProjectId, callerEmpNo, canManage)) return false
        TaskRepository.setProjectBucket(id, lane.id)
        return true
    }

    // --- the checklist under a card -------------------------------------------------------

    /**
     * Append one step to a card's checklist. Management plans any card, and **since Stage 89** so does
     * the person the card is assigned to — the plan is their work, and a step they know has to happen
     * should not wait for management to type it. Anyone else is refused here exactly as [addNote]
     * refuses them, and the add box is simply not rendered for them.
     *
     * Taking a step off stays management's ([removeChecklistItem]): adding to a plan is knowing the
     * work, but striking a line somebody else wrote off it is a decision about the plan itself.
     *
     * A step always lands **unticked**, whatever column its card sits in. The tick is the reader's act,
     * not the form's: a line typed onto a **Completed** card used to arrive already done, on the reading
     * that a finished card's plan is its measure and a late line must not drag it below 100%. **Since
     * Stage 101** that is not what a line written after the fact means — it is work the reader only now
     * knows has to happen, and it prints as work still to do. The finish rule did not move: moving a card
     * into *Completed* still ticks the plan it has ([syncPlanWithStatus]). What changed is the new line:
     * a finished card that gains one reads below its whole count until the card is taken back out of
     * *Completed* and the step is worked.
     *
     * Returns the new step's id, or null when there was nothing to add: a card that is not on the
     * board any more, a reader with no part in it, or a blank or over-long line.
     */
    fun addChecklistItem(taskId: Int, label: String, callerEmpNo: String, canManage: Boolean): Int? {
        val task = TaskRepository.findById(taskId) ?: return null
        if (!canManage && task.assignee != callerEmpNo) return null
        val text = label.trim()
        if (text.isEmpty() || text.length > CHECKLIST_LABEL_MAX) return null
        return ChecklistRepository.insert(taskId, text)
    }

    /**
     * Take one step off a card's checklist. Management only — the card's own assignee may add to their
     * plan since Stage 89, but striking a line off it is a change to the plan rather than to the work.
     */
    fun removeChecklistItem(itemId: Int, callerEmpNo: String, canManage: Boolean): Boolean {
        if (!canManage) return false
        val taskId = ChecklistRepository.taskOf(itemId) ?: return false
        TaskRepository.findById(taskId) ?: return false
        return ChecklistRepository.delete(itemId)
    }

    /**
     * Tick or untick a step. The card's own rule, not the step's: management set the list up, so they
     * may work it, and the person the card is assigned to is the one doing the work. Anyone else who
     * can see the card — a colleague on a shared project page — gets nothing.
     *
     * Neither of them gets anything from a **Completed** card either, which is the second half of this
     * route's rule and the reason the popup draws those steps as plain ticked labels rather than as
     * boxes. A finished card's plan is already all ticked by [syncPlanWithStatus]; letting one box be
     * unticked here would make the card print 4 of 5 done while sitting in the *Completed* column, and
     * would leave a half-ticked plan for the restore to bring back when the card is reopened. The way
     * to change a finished card's steps is to take the card out of *Completed*, which puts every one of
     * them back where it stood.
     */
    fun toggleChecklistItem(itemId: Int, callerEmpNo: String, canManage: Boolean): Boolean {
        val taskId = ChecklistRepository.taskOf(itemId) ?: return false
        val task = TaskRepository.findById(taskId) ?: return false
        if (task.status == STATUS_COMPLETED) return false
        if (!canManage && task.assignee != callerEmpNo) return false
        val current = ChecklistRepository.find(itemId) ?: return false
        return ChecklistRepository.setDone(itemId, !current.done)
    }

    // --- the running log under a card -----------------------------------------------------

    /**
     * Append one note to a card's log.
     *
     * Management and the person the card is assigned to may write here, which is one rule wider than
     * the checklist above it on purpose: a step is a line of the plan management made, while a note is
     * somebody reporting on the work, and the person doing the work is the one who knows how it went.
     * Anyone else who can see the card — a colleague on a shared project page — is refused, exactly as
     * [toggleChecklistItem] refuses them.
     *
     * Notes are never edited in place, only added and read, so the log keeps the wording its author
     * used. Returns the new note's id, or null when there was nothing to store: a blank or over-long
     * body, or a card that is off the board.
     */
    fun addNote(taskId: Int, body: String, callerEmpNo: String, canManage: Boolean): Int? {
        val task = TaskRepository.findById(taskId) ?: return null
        if (!canManage && task.assignee != callerEmpNo) return null
        val text = body.trim()
        if (text.isEmpty() || text.length > NOTE_MAX) return null
        return NoteRepository.insert(
            taskId = taskId,
            body = text,
            authorEmpNo = callerEmpNo,
            created = LocalDate.now(ZoneOffset.UTC).toString(),
        )
    }

    /**
     * Take one note off a card's log. Management only: an author removing their own entry would leave
     * the record looking like it was never made, so the account that wrote it asks management to
     * delete it, the same way a wrong step is removed by the team that owns the list.
     */
    fun removeNote(noteId: Int, callerEmpNo: String, canManage: Boolean): Boolean {
        if (!canManage) return false
        val taskId = NoteRepository.taskOf(noteId) ?: return false
        TaskRepository.findById(taskId) ?: return false
        return NoteRepository.delete(noteId)
    }

    /**
     * Normalise + validate one task form. Errors are keyed per field.
     *
     * A card belongs to exactly one of the two boards (Stage 48), and that shows up here as two refusals: an
     * assigned card **names a project that exists** — the blank the popup used to print is gone, so a card
     * cannot be written into neither world — and it is **filed in one of that project's own lanes**.
     * A backlog card is asked neither: its row has no project whatever the form posts, and its column is
     * checked by [inBacklog] below.
     *
     * [storedLane] is the card's own lane as the database holds it, and it is consulted only when the form
     * posted no lane field at all — which is what a disabled one does, a greyed select being the reason an
     * assignee can save their own due date without being answerable for a field they cannot write.
     *
     * A lane the form **does** post is never allowed to be blank: the placeholder a card that has never been
     * filed opens on is the card's state, not an answer, and filing it is one choice in the same field. That is
     * the whole of the compulsion, and [laneRequired] is the narrower case beside it — a create, or an edit
     * moving a card **onto** a project, whose form has to post a lane at all rather than leave the field out.
     * It asks nothing of an owner's save, so a card nobody placed before this rule existed can still have its
     * dates put right by the person working on it.
     *
     * [requireAssignee] is false only for a backlog card. A card on the Other tasks board is nobody's work
     * by design — it is filed under a column, and only the person who picks it up puts a name on it — so
     * saving it with the assignee still blank is the normal case, and refusing it would lock the popup that
     * Stage 40 exists to open. A name that is typed anyway is still checked against the roster.
     *
     * [inBacklog] says this form carries the board's **Column** picker, and [storedColumn] is the column the
     * row is in. Together they are the same pair [storedLane] is for the lane field: a card that lives on the
     * backlog is filed in one of its columns and the choice is checked against the columns the board actually
     * has, while a form that prints no such field is not a form re-columning anything. Unlike a lane there is
     * no *Unsorted* column to fall back to — `tasks.bucket_id` going null takes a card off every column of
     * that board, which is why [deleteBucket] refuses to do it — so a column that is missing or names a
     * column that is gone is a refusal, not a quiet un-filing.
     *
     * [creating] is the one case where a board with no columns at all is a refusal rather than a pass: an
     * OTHER row written without a column is invisible on the only board that prints it, so there is no card to
     * show the reader at the end of that write. An *edit* of such a row still saves — its popup draws no
     * picker, and a due date is a due date whatever the board looks like.
     */
    private fun resolve(
        draft: TaskDraft,
        storedLane: Int? = null,
        requireAssignee: Boolean = true,
        inBacklog: Boolean = false,
        storedColumn: Int? = null,
        creating: Boolean = false,
        laneRequired: Boolean = false,
    ): ResolvedTask {
        val errors = LinkedHashMap<TaskField, String>()
        val title = draft.title.trim()
        if (title.isEmpty()) errors += TaskField.TITLE to "Give the task a title."

        // Every card is on one of the two boards, and this is where the assigned half of that is enforced: a
        // card names a project that exists, or it is not written. The blank option the picker used to print is
        // gone, and a row that still carries no project is one the reader has to choose a world for.
        val projectName = draft.projectName.trim()
        val projectId = when {
            inBacklog -> null
            projectName.isEmpty() -> {
                errors += TaskField.PROJECT to "Choose which project this card is on."
                null
            }
            else -> ProjectRepository.idByName()[projectName].also {
                if (it == null) errors += TaskField.PROJECT to "Choose a project that exists."
            }
        }

        val assignee = draft.assigneeEmpNo.trim()
        val assigneeEmpNo = if (assignee.isEmpty()) {
            if (requireAssignee) errors += TaskField.ASSIGNEE to "Assign the task to someone on the team."
            null
        } else if (TeamRepository.findByEmpNo(assignee) == null) {
            errors += TaskField.ASSIGNEE to "That employee is not on the team roster."
            null
        } else {
            assignee
        }

        val due = draft.due.trim()
        if (due.isNotEmpty() && !isIsoDate(due)) errors += TaskField.DUE to "Use the date picker (yyyy-mm-dd)."
        val started = draft.started.trim()
        if (started.isNotEmpty() && !isIsoDate(started)) errors += TaskField.START to "Use the date picker (yyyy-mm-dd)."

        val status = if (draft.status in TASK_STATUSES) draft.status else {
            errors += TaskField.STATUS to "Choose a status from the list."
            "Not started"
        }
        val priority = if (draft.priority in PRIORITIES) draft.priority else {
            errors += TaskField.PRIORITY to "Choose a priority from the list."
            "Medium"
        }
        // The lane travels with the project it belongs to (V7), and since Stage 48 the form posts it by **name**:
        // until Stage 75 the six sub-categories were the same six on every board, so a name was the one answer the
        // general board's popup could post without knowing this project's lane ids. That is still the answer the
        // popup posts, and it is resolved by [mn.murray.pts.repository.ProjectBucketRepository.findByName]
        // against *this* project's lanes, which is what makes the Stage 75 freedom safe: a board whose manager
        // retitled or removed a column simply stops offering that name, exactly as it already stopped offering a
        // lane id somebody else's project owns. A name that is not on the project the form
        // resolved to is a refusal, the way a borrowed lane id always was: it says the card would land on
        // somebody else's board, and the picker would never have offered it.
        //
        // No field at all is a third answer and the common one: a greyed select posts nothing, so an assignee
        // saving their own due date keeps the column their card is in rather than being made answerable for a
        // field they may not write. A **create** has no lane to keep, so a create that posts no lane is a form
        // that never asked — and filing the card under nothing is exactly what this stage stops.
        //
        // A backlog card is asked none of this (Stage 42): it has no project, so it has no lanes, and its
        // popup draws no Lane field. A hand-posted `lane` at one is dropped rather than refused, because a
        // refusal would print under a field the reader has never seen.
        val projectBucketId = if (inBacklog || projectId == null) null else {
            val posted = draft.projectLane?.trim()
            when {
                posted == null -> {
                    if (laneRequired && storedLane == null) {
                        errors += TaskField.LANE to "Choose which part of this project the card is on."
                        null
                    } else storedLane
                }
                // The picker's own placeholder, and the answer a card that has never been filed opens on. It is
                // not a choice the reader may keep: **Unsorted is where the cards that arrived before this rule
                // rest, not a place to send one**, so the sentence points at the field that fixes it. An owner's
                // form never reaches here — their picker is greyed and posts nothing — so nobody is stopped from
                // booking their own time by a lane that is management's to choose.
                posted.isEmpty() -> {
                    errors += TaskField.LANE to "Choose which part of this project the card is on."
                    null
                }
                else -> {
                    val lane = ProjectBucketRepository.findByName(projectId, posted)
                    if (lane == null) {
                        errors += TaskField.LANE to "Choose which part of this project the card is on."
                        null
                    } else lane.id
                }
            }
        }
        // The backlog's column, checked against the columns that are on the board rather than against the
        // word the form was opened from: a column renamed or emptied while this popup was up is not a place a
        // card can be filed in, and the reader is told so instead of watching the card go missing from a board
        // that groups by that id.
        //
        // A board with no columns asks nothing of an *edit*, because it prints no picker to answer with and a
        // saved date must not be held hostage to the shape of the board. A create is refused there — see
        // [creating] — because a card with no column is a card on no board.
        val bucketId = if (!inBacklog) null else {
            val columns = bucketRows()
            val chosen = draft.columnId ?: storedColumn
            when {
                columns.isEmpty() -> {
                    if (creating) {
                        errors += TaskField.COLUMN to
                            "The Other tasks board has no column to put this card in yet."
                    }
                    null
                }
                chosen == null || columns.none { it.id == chosen } -> {
                    errors += TaskField.COLUMN to "Choose which column this card sits in."
                    null
                }
                else -> chosen
            }
        }
        return ResolvedTask(
            errors = errors, title = title, projectId = projectId, due = due,
            priority = priority, assigneeEmpNo = assigneeEmpNo, status = status, started = started,
            projectBucketId = projectBucketId, bucketId = bucketId,
        )
    }

    // --- the columns of the backlog board (Stage 40) -------------------------------------------

    /**
     * Put one more column on the Other tasks board.
     *
     * Same three refusals a lane makes — nothing typed, too long, a name that column already has — checked
     * the same way: after the trim, and ignoring case, so " site works " onto a board printing *Site works*
     * is a mistake rather than a second column of the same thing.
     *
     * **Every signed-in account may add one** (Stage 48), which is the same right that lets them write a card
     * there at all: work that is not on a project is noticed by whoever stands nearest to it, and a column named
     * for it is one line of typing. What stays management's is the other half of a column's life — renaming one,
     * taking one off, and the `⋮` that offers both — because those move or erase the work sitting in it, the same
     * line [updateAssigned] draws under the title and the status. Neither [callerEmpNo] nor [canManage] is
     * consulted here — the role check lives on the two writes that need one, and these two fields are in the
     * signature so every backlog column write reads the same from the page.
     *
     * Returns `null` when the column is on the board and a [BucketError] naming what stopped it otherwise.
     * [BucketError.DENIED] can no longer come back from this call; the enum keeps it for the two writes that
     * still answer with it.
     */
    fun addBucket(name: String, callerEmpNo: String, canManage: Boolean): BucketError? {
        val text = name.trim()
        if (text.isEmpty()) return BucketError.BLANK
        if (text.length > BUCKET_NAME_MAX) return BucketError.TOO_LONG
        if (BucketRepository.names().any { it.equals(text, ignoreCase = true) }) return BucketError.DUPLICATE
        BucketRepository.insert(text)
        return null
    }

    /**
     * Change a column's name, or refuse and change nothing.
     *
     * The row behind the cards keeps its id, so this is the one backlog write that never touches a card:
     * every task in the column follows the rename because the board groups them by that id. The duplicate
     * check is made against the *other* columns, so keeping the name as it is stays a no-op success rather
     * than becoming a complaint about a name the reader did not type.
     */
    fun renameBucket(id: Int, name: String, callerEmpNo: String, canManage: Boolean): BucketError? {
        if (!canManage) return BucketError.DENIED
        val bucket = BucketRepository.find(id) ?: return BucketError.NO_BUCKET
        val text = name.trim()
        if (text.isEmpty()) return BucketError.BLANK
        if (text.length > BUCKET_NAME_MAX) return BucketError.TOO_LONG
        if (BucketRepository.except(id).any { it.name.equals(text, ignoreCase = true) }) {
            return BucketError.DUPLICATE
        }
        return if (BucketRepository.rename(bucket.id, text)) null else BucketError.NO_BUCKET
    }

    /**
     * Take a column off the board, with its cards told where to go first.
     *
     * This is where the backlog's delete differs from a lane's. Removing a **lane** can leave its cards
     * unfiled, because that board prints an **Unsorted** column for exactly that state. The Other tasks
     * board has no such column: `tasks.bucket_id` answers `ON DELETE SET NULL`, so dropping a bucket that
     * still holds cards would take those cards off every column on the board — work that has gone missing
     * rather than work that has been moved. So a column holding cards is only removable once the caller has
     * named another column to carry them, and [moveBucketCards] runs in the same call before the row goes.
     *
     * The destination is looked up rather than trusted: it has to exist and it cannot be the column being
     * deleted. A column nobody has put a card in needs no destination, and the last column on the board that
     * still holds cards has nowhere to put them, so it refuses — the board may end up with no columns at all,
     * which is a backlog waiting to be sorted, but not with cards that belong to nothing.
     *
     * `null` means it went; the [BucketError] says which rule stopped it, so the popup can print the reason
     * under the field that owns it.
     */
    fun deleteBucket(id: Int, moveToId: Int?, callerEmpNo: String, canManage: Boolean): BucketError? {
        if (!canManage) return BucketError.DENIED
        val bucket = BucketRepository.find(id) ?: return BucketError.NO_BUCKET
        if (TaskRepository.countInBucket(bucket.id) > 0) {
            val others = BucketRepository.except(bucket.id)
            if (others.isEmpty()) return BucketError.LAST_BUCKET
            val dest = moveToId ?: return BucketError.NO_DEST
            if (dest == bucket.id || others.none { it.id == dest }) return BucketError.NO_DEST
            TaskRepository.moveBucketCards(bucket.id, dest)
        }
        return if (BucketRepository.delete(bucket.id) > 0) null else BucketError.NO_BUCKET
    }
}

/**
 * Which input a task message belongs under. `PERMISSION` has no field of its own — it is
 * printed once at the bottom of the popup, because nothing the user typed is wrong.
 */
enum class TaskField { TITLE, PROJECT, ASSIGNEE, START, DUE, PRIORITY, STATUS, LANE, COLUMN, PERMISSION }

/** The raw task popup, exactly as submitted so it can be re-rendered with what was typed. */
data class TaskDraft(
    val title: String = "",
    val projectName: String = "",
    val assigneeEmpNo: String = "",
    val started: String = "",
    val due: String = "",
    val priority: String = "Medium",
    val status: String = "Not started",
    /**
     * Which lane of its project's board the card is filed in, **by name** (V7, named since Stage 48): the name
     * is the one answer both the project page's picker and the general board's picker can post without knowing
     * that project's lane ids first, and it is resolved against that project's own lanes, so a board a manager
     * has restructured (Stage 75) simply offers different names. An empty string is
     * the Unsorted choice, which only a card that arrived unfiled can still post; **null** is a form that has no
     * lane picker at all — the backlog's popup — and it is not the same answer as a blank one.
     */
    val projectLane: String? = null,
    /**
     * Which column of the Other tasks board the card sits in (Stage 42). Like [projectLane] the two answers are
     * not the same: **null** is a form that has no Column picker to post from — every create of an assigned
     * card, and every edit of one — while a number is a place the card is being put. There is no 0 here,
     * because the backlog has no Unsorted column to catch a card that has been taken off its own.
     */
    val columnId: Int? = null,
    /**
     * The popup's "where it lives" answer (Stage 48): [mn.murray.pts.domain.BOARD_PROJECT] or
     * [mn.murray.pts.domain.BOARD_OTHER]. Only management can move a card with it, and only an edit offers it,
     * so **null** — a form that posted nothing at the field — leaves the card on the board its row says it is
     * on, which is what every create and every ordinary account's save amounts to.
     */
    val board: String? = null,
)

/** `taskId` on success, field-keyed `errors` plus the submitted `values` when the popup stays open. */
data class TaskSaveResult(
    val taskId: Int?,
    val errors: Map<TaskField, String>,
    val values: TaskDraft,
) {
    fun error(field: TaskField): String? = errors[field]
}

/**
 * Why a lane write was refused. Each one is a message the page prints under the box it came from —
 * [NO_LANE] is the one only a hand-built or stale request can reach, since a column the reader can see always
 * carries its own `⋯`, and [LAST_LANE] is the one a real click finds on a board that has nothing left to remove.
 * Since **Stage 75** that is the whole of what a board may not lose: the three lanes a project opens with used to
 * answer here with a code of their own, because the general board's picker filed a card by those names, and both
 * [removeLane] and [renameLane] refused to touch them. The picker files a card by *this project's* lane names
 * now, so an opening column is a column.
 *
 * [BAD_NAME] is Stage 73's and belongs to the picker rather than to a single column: a name the naming rules
 * refuse, carried with the words to print at the field.
 * [CATALOGUE_DENIED] is the third, and since **Stage 77** it is about the tick and not about the bucket: a
 * project's own manager may now name a column that is only theirs, so this code answers only a reader who asked
 * for that name to join the *category's* defaults, which stays management's act. Both carry a
 * [LaneSaveResult.message] instead of a canned line, because the reason names the bucket or quotes the rule —
 * which a reader cannot get out of a code alone.
 *
 * **HAS_CARDS is gone (Stage 81).** It was the code that refused an untick while the bucket still held work —
 * "move them to another bucket first", which made the reader relocate every card by hand before a column they had
 * already decided against could be taken away. The day a column's work gained somewhere to go, the Archive, its
 * card's plan and log left exactly where they were, the refusal went with it: an occupied lane is removed and its
 * cards are archived with it. Nothing prints the code any more, so nothing carries it.
 *
 * Since **Stage 74** the codes are read by two answers, not one: [LaneSaveResult] for the picker and
 * [LaneRenameResult] for a column's `⋯`, whose refusals all carry their own sentence for the same reason.
 * Since **Stage 81** a third reads them, [LaneRemoval], the same shape as the other two plus the number of cards
 * that went with the column.
 */
enum class LaneError {
    BLANK, TOO_LONG, DUPLICATE, DENIED, NO_PROJECT, NO_LANE, LAST_LANE,
    BAD_NAME, CATALOGUE_DENIED,
}

/**
 * What [TaskService.removeLane] answered a column's `⋯`: the rule that stopped it, or how many of its cards went
 * into the Archive with it (Stage 81).
 *
 * The count is why this is a result rather than the bare [LaneError] the route used to read. Removing a bucket that
 * holds work is now an ordinary thing to do, and a board that says *gone* while cards the reader could see a moment
 * ago are not on it has answered the wrong question — the page has to say how many went and where. [archived] is
 * zero on a lane nobody had filed anything in, which is why the sentence that prints it is conditional.
 */
data class LaneRemoval(val archived: Int, val error: LaneError?) {
    val ok: Boolean get() = error == null

    companion object {
        fun gone(archived: Int) = LaneRemoval(archived, null)
        fun refused(error: LaneError) = LaneRemoval(0, error)
    }
}

/**
 * What [TaskService.renameLane] answered a column's `⋯`: either nothing, or the rule that stopped it and the
 * words to print under the name field. [ok] is the caller's test, and a refusal means the heading is exactly what
 * it was — the service checks the lane, the name and the board before it writes anything.
 *
 * There is no [LaneSaveResult.lanes] here because a rename is one row's heading, not a whole board's list: the
 * page re-reads the lanes the same way it does for any render, and the route has nothing to carry back.
 */
data class LaneRenameResult(val error: LaneError?, val message: String?) {
    val ok: Boolean get() = error == null

    companion object {
        val saved = LaneRenameResult(null, null)
        fun refused(error: LaneError, message: String? = null) = LaneRenameResult(error, message)
    }
}

/**
 * What [TaskService.saveLanes] answered a bucket picker: the lanes the board now carries, and either nothing
 * else or the one refusal to print under the picker. [ok] is the caller's test, and a refusal always means the
 * board is exactly what it was, since the service checks the whole list before it writes any of it.
 *
 * A refusal with a [message] is one the page prints where the reader made it; one without is a right or a
 * missing project, which the route answers by sending the reader back to the list of projects.
 */
data class LaneSaveResult(val lanes: List<ProjectBucket>, val error: LaneError?, val message: String?) {
    val ok: Boolean get() = error == null

    companion object {
        fun refused(error: LaneError, message: String? = null) = LaneSaveResult(emptyList(), error, message)
    }
}

/**
 * Why a backlog column write was refused. [BLANK], [TOO_LONG] and [DUPLICATE] are the three a reader can
 * reach by typing; [NO_DEST] and [LAST_BUCKET] are the two a delete answers with, because a column cannot be
 * dropped while it still holds cards and there is nowhere agreed to put them. [DENIED] and [NO_BUCKET] are
 * the branches only a hand-built request or a stale popup reaches — the page draws no control for a reader
 * who may not use it, and a column that is gone has no menu left to press — so those two send the reader back
 * to the board instead of printing a message under a field.
 */
enum class BucketError { BLANK, TOO_LONG, DUPLICATE, DENIED, NO_BUCKET, NO_DEST, LAST_BUCKET }

/** The outcome of [TaskService.addLane]: the new lane, or which rule stopped it. */
data class LaneAddResult(val bucket: ProjectBucket?, val error: LaneError?) {
    val added: Boolean get() = bucket != null

    companion object {
        fun added(bucket: ProjectBucket) = LaneAddResult(bucket, null)
        fun refused(error: LaneError) = LaneAddResult(null, error)
    }
}

/** A validated task, ready to hand to the repository; [errors] is what kept it from being written. */
private data class ResolvedTask(
    val errors: Map<TaskField, String>,
    val title: String,
    val projectId: Int?,
    val due: String,
    val priority: String,
    val assigneeEmpNo: String?,
    val status: String,
    val started: String,
    val projectBucketId: Int?,
    /** Which column of the backlog this card is filed in, or null when the form prints no Column picker. */
    val bucketId: Int? = null,
)

private val ISO_DATE = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun isIsoDate(value: String): Boolean = try {
    java.time.LocalDate.parse(value, ISO_DATE)
    true
} catch (e: Exception) {
    false
}
