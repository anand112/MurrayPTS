package mn.murray.pts.service

import mn.murray.pts.domain.BOARD_OTHER
import mn.murray.pts.domain.BUCKET_NAME_MAX
import mn.murray.pts.domain.Bucket
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.ProjectBucket
import mn.murray.pts.domain.PROJECT_BUCKET_MAX
import mn.murray.pts.domain.TASK_PRIORITIES
import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.domain.TaskItem
import mn.murray.pts.domain.UNSORTED_LANES
import mn.murray.pts.domain.isStandardLane
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ChecklistRepository
import mn.murray.pts.repository.NoteRepository
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
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
     */
    fun updateStatus(id: Int, status: String, callerEmpNo: String, canManage: Boolean): Boolean {
        if (status !in TASK_STATUSES) return false
        val task = TaskRepository.findById(id) ?: return false
        if (!canManage && task.assignee != callerEmpNo) return false
        TaskRepository.updateStatus(id, status)
        return true
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
     * The card somebody is allowed to open the editor on, or null when they are not.
     *
     * Management edits any card; everybody else reaches the editor only through the card that carries
     * their own number. The render layer asks this before it fills the popup, so a hand-typed `?edit=`
     * for somebody else's card comes back as the ordinary page rather than as somebody's task form.
     */
    fun editableBy(id: Int, callerEmpNo: String, canManage: Boolean): TaskItem? {
        val task = TaskRepository.findById(id) ?: return null
        return if (canManage || task.assignee == callerEmpNo) task else null
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
        return TaskRepository.delete(id) > 0
    }

    // --- the lanes of one project's board (V7) ------------------------------------------------

    /** The lanes this project's board is built from, in the order the board prints them. */
    fun lanesOf(projectId: Int): List<ProjectBucket> = ProjectBucketRepository.ofProject(projectId)

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
     */
    fun addLane(projectId: Int, name: String, callerEmpNo: String, canManage: Boolean): LaneAddResult {
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
        val id = ProjectBucketRepository.insert(projectId, text)
        return LaneAddResult.added(ProjectBucket(id, projectId, text))
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
     * A lane that holds cards is still removable, and that is the point of the nullable
     * `tasks.project_bucket_id` — the key answers `ON DELETE SET NULL`, so the cards drop into the board's
     * **Unsorted** column, stay visible and stay draggable. Deleting a lane is a change to the columns, never
     * a change to the work: no card is touched here, and none is lost.
     *
     * One lane is left standing, because a project page without lanes has nothing to be a lane board with: it
     * would fall back to the five status columns, which is the shape Stage 30 decided that page does not have.
     * So the last lane of a project refuses to go, and says so under the box.
     *
     * `null` means it went; the [LaneError] says which rule stopped it, so the page can print the complaint
     * under the box that owns the lane list.
     *
     * The six sub-categories every project carries are not removable ([LaneError.STANDARD], Stage 48): a board
     * missing one of them would refuse every card the general board's popup files there, because that picker
     * lists the six and nothing else, and the cards already filed in the lane would be set free by the
     * `ON DELETE SET NULL` clause V7 wrote. What a manager may still do is add a lane of their own and take
     * that one off again — the six are the floor of a board, not its ceiling.
     */
    fun removeLane(projectId: Int, laneId: Int, callerEmpNo: String, canManage: Boolean): LaneError? {
        if (!mayEditLanes(projectId, callerEmpNo, canManage)) return LaneError.DENIED
        if (ProjectRepository.findById(projectId) == null) return LaneError.NO_PROJECT
        val lane = ProjectBucketRepository.find(laneId)
            ?: return LaneError.NO_LANE
        if (lane.projectId != projectId) return LaneError.NO_LANE
        if (isStandardLane(lane.name)) return LaneError.STANDARD
        if (ProjectBucketRepository.ofProject(projectId).size < 2) return LaneError.LAST_LANE
        return if (ProjectBucketRepository.delete(laneId) > 0) null else LaneError.NO_LANE
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
     * Append one step to a card's checklist. Management writes the list, so a normal account is
     * refused here exactly as it is by [create] — the add box is simply not rendered for anyone else,
     * and this is what that promise looks like when the POST arrives without it.
     *
     * Returns the new step's id, or null when there was nothing to add: a blank or over-long line,
     * or a card that is not on the board any more.
     */
    fun addChecklistItem(taskId: Int, label: String, callerEmpNo: String, canManage: Boolean): Int? {
        if (!canManage) return null
        val text = label.trim()
        if (text.isEmpty() || text.length > CHECKLIST_LABEL_MAX) return null
        TaskRepository.findById(taskId) ?: return null
        return ChecklistRepository.insert(taskId, text)
    }

    /** Take one step off a card's checklist. Management only, same rule as [addChecklistItem]. */
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
     */
    fun toggleChecklistItem(itemId: Int, callerEmpNo: String, canManage: Boolean): Boolean {
        val taskId = ChecklistRepository.taskOf(itemId) ?: return false
        val task = TaskRepository.findById(taskId) ?: return false
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
     * cannot be written into neither world — and it is **filed in one of that project's six sub-categories**.
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
        // The lane travels with the project it belongs to (V7), and since Stage 48 the form posts it by **name**
        // — the six sub-categories are the same six on every board, so a name is the one answer the general
        // board's popup can post without knowing this project's lane ids. A name that is not on the project the
        // form resolved to is a refusal, the way a borrowed lane id always was: it says the card would land on
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
     * Which lane of its project's board the card is filed in, **by name** (V7, named since Stage 48): the six
     * sub-categories are the same on every project, so a name is the one answer both the project page's picker
     * and the general board's picker can post without knowing that project's lane ids first. An empty string is
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
 * [NO_LANE] is the one only a hand-built or stale request can reach, since a lane the reader can see always
 * carries its own ×, and [LAST_LANE] is the one a real click finds on a board that has nothing left to remove.
 * [STANDARD] is Stage 48's: one of the six sub-categories every project carries, which no board may be left
 * without because the general board's lane picker lists exactly those six.
 */
enum class LaneError { BLANK, TOO_LONG, DUPLICATE, DENIED, NO_PROJECT, NO_LANE, LAST_LANE, STANDARD }

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
