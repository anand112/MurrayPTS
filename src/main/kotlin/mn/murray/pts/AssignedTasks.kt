package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.select
import mn.murray.pts.service.ProjectService
import mn.murray.pts.service.TaskService

/**
 * The Assigned tasks page: what is on *me* now — the projects board, and the Other tasks assigned to me.
 *
 * This page is **personal for every reader, management included** (Stage 53): whatever somebody's role, both
 * boards show only the cards with their own employee number on them — it is "what is on *me* now", never a
 * team-wide list. The same rule the sidebar count reads through, so the number beside *Assigned tasks* is
 * exactly the cards this page then prints — which since Stage 55 means *both* the project cards below and the
 * reader's own Other-task cards. What a manager still sees the *whole* of, from elsewhere, is the team: the
 * Team sheet's OPEN TASKS drill-down, a project's board and the Calendar all read
 * [mn.murray.pts.service.TaskService.assignedFor] with management's wider view, so nothing about running the
 * team was lost here — only this one board stopped trying to be two things (a person's own list *and* the
 * manager's overview) at once.
 *
 * The reader's own **Other tasks** — work that belongs to no project — ride along here since **Stage 55**, as a
 * second status board under the projects one, with a heading and its own empty line. That is the half of the
 * screen Stage 47's split left hanging: it took the shared backlog *board* onto its own page, but a person's own
 * backlog card had nowhere personal to surface, and since Stage 51 a Viewer is not even offered the *Other tasks*
 * link. So "what is on me" was quietly two-of-nine for somebody whose work is not on a project. This page now
 * answers it whole: the cards filed under a project, then the cards that are theirs but on no project, or the
 * plain statement that they have none. It is **not** the shared backlog board returning here — no bucket columns,
 * no `⋮`, no view toggle: only the reader's own cards, cut by status like the projects board above, opened and
 * saved through the very same popup. The shared board stays on [mn.murray.pts.otherTasksContent]; Stage 124
 * adds only its column-create popup to this page, not its columns or their management menus.
 *
 * Since **Stage 90** the Other-task half is also where a reader **writes their first card**: one `+ Add task`
 * line under the board opens the blank popup on the bucket the picker is showing. It restores the self-service
 * half of [mn.murray.pts.service.TaskService.createBacklog] that Stage 51 had narrowed to management on the
 * boards — a non-manager's card lands as **theirs** and **Not started**, pinned by the service, while
 * management writes the same form with the whole roster. The bucket picker above stays even when the reader has
 * no cards yet, because it is now also the chooser that line uses.
 *
 * Since **Stage 107** the projects board above is narrowed the same way: a **Project** picker in the bucket
 * picker's shape (`?project=`, [assignedProjectFilter]) cuts the reader's project cards down to one project,
 * still in the five status columns. The two pickers narrow two different boards on one page, so each carries
 * the other's choice as a hidden field and both ride the page's `back` token ([backForFilters]) — a card opened,
 * saved, stepped, noted, deleted or refused from a filtered page returns to that same filtered page.
 *
 * **Since Stage 124 the empty projects board offers `+ New bucket`** where it used to repeat the page head's
 * `+ New task`: the Other tasks board's own add-a-column popup ([newBucketButton]), drawn for a reader who may
 * *Manage buckets*. The head already writes a card; a column is the one thing this page can still be short of
 * — without one there is no `+ Add task` line to write under — and the popup's answer lands in `#assignedOther`
 * so the picker, the reader's own cards and that line all arrive together, with no reload.
 *
 * The two boards share the page's **one** card popup and **one** editor opener: the project board draws the
 * opener only when the Other-task board has nothing to draw it with (see the `drawOpener` argument), because
 * [task-modal.js] finds that opener by id and a second one would be a dead form. So the same click opens either
 * kind of card, and either card's save comes back to this page.
 *
 * Creating and editing an assigned task happen in the popup from [taskFormModal], whose opener management
 * gets in the page head and whose editor anybody reaches from their own card's menu. [state] is non-null
 * when the page was asked to arrive with the popup up — `?new=1`, `?edit=<card>`, or the page a rejected
 * submit came back as — and that page prints the same markup already open.
 */
fun DIV.assignedTasksContent(
    session: UserSession,
    canManage: Boolean,
    state: TaskForm? = null,
    otherBucketId: Int = 0,
    projectFilter: Int = 0,
    canAddBuckets: Boolean = false,
    bucket: BucketForm = BucketForm(),
) {
    // Both lists are the reader's own cards whichever role they hold ([canManage] governs the rights beneath —
    // create, the full editor, the drag — not who appears). This is the one place the personal-scoping is applied;
    // `assignedFor` and `assignedOtherFor` still answer management's wider read for the Calendar, the project
    // boards and the Team drill-down that need it.
    val allCards = TaskService.assignedFor(session.employeeNumber, canManage = false)
    // The projects board is cut by **project** as well as by status since **Stage 107**: the reader picks one of
    // the board's projects (`S-01 · Haul road widening`, …) and sees the cards of their own filed under it, still
    // standing in the five status columns they are used to. The picker only narrows; it never moves a card.
    val projects = ProjectService.all()
    // An id the board no longer has — a stale bookmark, a project somebody deleted — answers **all projects**
    // rather than an empty board under a picker with nothing selected in it.
    val chosenProject = projects.firstOrNull { it.id == projectFilter }
    val cards = chosenProject?.let { chosen -> allCards.filter { it.projectId == chosen.id } } ?: allCards
    val allOther = TaskService.assignedOtherFor(session.employeeNumber)
    // The Other-task half is cut by **bucket** as well as by status since **Stage 88**: the reader picks one of
    // the board's own columns (`Finance`, `HR`, …) and sees the cards filed in it, still standing in the five
    // status columns they are used to. The picker only narrows; it never moves a card between columns.
    val buckets = TaskService.bucketRows()
    // An id the board no longer has — a stale bookmark, a column somebody deleted — answers **all buckets**
    // rather than an empty board under a picker with nothing selected in it.
    val chosenBucket = buckets.firstOrNull { it.id == otherBucketId }
    val ownOther = chosenBucket?.let { chosen -> allOther.filter { it.bucketId == chosen.id } } ?: allOther
    // Every action out of this page posts this token and comes back through `taskRedirect`, so the filters the
    // reader chose are still the page they return to: opening a card, saving it, ticking a step, writing a note,
    // deleting or answering a refusal all land on the same narrowed board (Stage 88, and Stage 107 for the
    // projects board above).
    val back = backForFilters(chosenBucket?.id ?: 0, chosenProject?.id ?: 0)
    // Stated on the page for the same reason the sidebar count is scoped: two cards out of nine,
    // with nothing saying why, reads as work that went missing. Since Stage 53 it is true of every reader, and
    // since Stage 107 the line also names the project when the board is narrowed, so five empty columns are not
    // mistaken for a reader who has nothing at all.
    p("board-scope") {
        +(if (chosenProject != null)
            "Only the cards assigned to you on ${projectLabel(chosenProject)} are on this board."
        else "Only the cards assigned to you are on this board.")
    }
    // Drawn whenever there is a project to choose, whether or not this reader has a card on one yet: it is the
    // board's only filter, and a reader with nothing would otherwise have no way to ask "what is on me here".
    if (projects.isNotEmpty()) {
        assignedProjectFilter(projects, chosenProject, chosenBucket)
    }
    // The projects board has no one project to grant on, so writing the first *project* card here stays
    // management's — [mn.murray.pts.service.TaskService.mayAddTasks] takes a project for a reason. The Other-task
    // board's own line is a separate right since Stage 90 (see below).
    // The opener travels with this board only while the Other-task board below has no card of its own to carry it.
    //
    // **Stage 124:** on an empty board this panel used to offer the page head's `+ New task` a second time. It now
    // offers `+ New bucket` instead — the Other tasks board's own "new column" popup, the same one `+ Add bucket`
    // opens there — for the reader who may name a column. That is the one thing this page can still be short of:
    // the head already writes a card, while a reader whose board has no column at all has no `+ Add task` line to
    // write one under. A reader who may write cards but not name columns keeps the old opener, so nothing is taken
    // away from a role that never held the bucket right.
    taskKanban(
        cards, session, canManage, back,
        canAdd = canManage,
        drawOpener = ownOther.isEmpty(),
        emptyOffer = if (canAddBuckets) {
            { it.newBucketButton(chosenBucket, chosenProject) }
        } else {
            { it.newTaskButton() }
        },
        emptyHint = if (canAddBuckets) {
            "Write the first card from the page head, or name a bucket for work that is not on a project."
        } else {
            "The board is the plan — write the first card and it appears under its column."
        },
    )

    h2("assigned-other-head") { +"Other tasks" }
    p("assigned-other-sub") { +"Work assigned to you that is not on a project." }
    // Everything a bucket write can change on this half of the page: the picker that lists the columns, the
    // empty line or the reader's own cards, and the `+ Add task` line that needs a column to aim at. **Stage 124**
    // made it the live block the bucket popup answers on, so a column named from the panel above lands here —
    // without it the create would have to reload the page or leave the reader a picker one name short.
    div("assigned-other") {
        id = "assignedOther"
        // Drawn whenever the board has columns, since **Stage 90** even when the reader has no Other-task cards yet:
        // it is now also the chooser for the `+ Add task` line under the board, so hiding it on an empty board would
        // leave a reader with no way to say which bucket their first card belongs in. A stale `?bucket=` still opens
        // it so there is a way back to *All buckets*. The projects filter above rides along as a hidden field, so
        // choosing a bucket does not quietly widen the board over it.
        if (buckets.isNotEmpty()) {
            otherBucketFilter(buckets, chosenBucket, chosenProject?.id ?: 0)
        }
        if (ownOther.isEmpty()) {
            p("assigned-other-empty") {
                +(if (chosenBucket != null) "You don't have any other tasks in ${chosenBucket.name}."
                else "You don't have any other tasks assigned.")
            }
        } else {
            // The reader's own backlog cards, cut by status exactly like the projects board above. `canAdd = false`:
            // the page's create line is the row *below* the board, aimed at a bucket rather than a status column —
            // the same reason the shared board puts one line at the foot of a bucket instead of one per status. The
            // board carries the page's one opener, since the project board handed it over above.
            taskKanban(ownOther, session, canManage, back, canAdd = false)
        }
        // Stage 90: the reader's own way in, on an empty board as readily as a full one. One line for the whole
        // Other board, opening the blank card popup on the bucket the picker shows — the first column for *All
        // buckets*, because a non-manager's popup pins its column (Stage 49). A non-manager's card lands as **theirs**
        // and **Not started** ([mn.murray.pts.service.TaskService.createBacklog] rebuilds the draft that way);
        // management writes the same form with the roster and the status picker. The project filter rides along like
        // the bucket does, so a card written from a narrowed page answers on that same page.
        if (buckets.isNotEmpty()) {
            otherAddTaskForm(buckets, chosenBucket, chosenProject?.id ?: 0)
        }
    }
    // The column popup the empty panel's `+ New bucket` opens (**Stage 124**): the Other tasks board's own add-a-bucket
    // form, drawn here because this is where it is opened from. It answers on `#assignedOther` — the block above —
    // so the column it makes appears in this page's picker without the page restarting, and its refusal comes back
    // with the word still in the field and the reason under it. Management only: naming a column is the
    // *Manage buckets* right, and the POST route asks for it again.
    if (canAddBuckets) {
        bucketModal(
            bucket, emptyList(), canManage = true, target = "#assignedOther", from = "assigned-tasks",
            assignedBucketId = chosenBucket?.id ?: 0, projectFilter = chosenProject?.id ?: 0,
        )
    }

    // The popup travels with the page, for every reader: management's `+ New task` in the head swaps into it,
    // and a reader's own card — from either board — swaps into it from the card itself. `state ?: TaskForm(...)`
    // is the placeholder a plain page ships — drawn for the reader standing here, so a normal account's copy holds
    // only their own name and status rather than the whole roster. The save route re-checks the role; markup is
    // not a permission. `back` carries both filters like the boards do, so a save answers on the same board.
    taskFormModal(state ?: TaskForm(canEditAll = canManage), back)
    // Board drag-and-drop lives in an external script (Stage 7): the markup contract is .kcol[data-status],
    // .kcard[draggable][data-id] and .kmove-btn[data-*]. Stage 103 retired this board's prev/next arrows, so the
    // drag is the way to change a status here; `.kmove-btn` survives in the contract for the project lane board's
    // single ✓ Done / ↺ Reopen button, which the same script posts.
    script(src = "/static/js/kanban.js") {}
}

/**
 * The **Project** picker above the reader's own projects board (**Stage 107**): the Bucket picker's twin on the
 * board above it — a real GET form onto this page whose `project` key is the whole filter, so the URL is the
 * state and a bookmark or a back button restores the same narrow view. `data-autosubmit` (app.js) submits it on
 * change, and the button below the select is the same submission for a browser with no script.
 *
 * *All projects* is option `0` — the absence of a filter. The list is every project the Projects board holds,
 * not only the ones this reader has a card on: the picker is the same world the sidebar's Projects link opens,
 * and a project with nothing on it yet is still a legitimately empty answer rather than a missing option. The
 * chosen project rides `back` ([backForFilters]) and the hidden `bucket` below keeps the Other-tasks filter
 * beneath it, so changing one picker never silently widens the other board.
 *
 * The select sits **inside** its label, the shape Stage 89 settled on for the Bucket picker: the pair is what
 * the field styling keys on, so a one-dropdown filter stays one small field rather than a page-wide toolbar.
 */
private fun DIV.assignedProjectFilter(projects: List<Project>, chosen: Project?, chosenBucket: Bucket?) {
    form(action = "/app/assigned-tasks", method = FormMethod.get, classes = "board-toolbar assigned-project-filter") {
        label("board-key") {
            +"Project"
            select {
                id = "assignedProject"
                name = "project"
                attributes["data-autosubmit"] = "true"
                option {
                    value = "0"
                    selected = chosen == null
                    +"All projects"
                }
                projects.forEach { project ->
                    option {
                        value = "${project.id}"
                        selected = project.id == chosen?.id
                        +projectLabel(project)
                    }
                }
            }
        }
        if (chosenBucket != null) {
            input(type = InputType.hidden, name = "bucket") { value = "${chosenBucket.id}" }
        }
        button(type = ButtonType.submit, classes = "submit btn-sm") { +"Show" }
    }
}

/** How a project is named in the picker: its code, then its name — the Projects board's own label. */
private fun projectLabel(project: Project): String =
    if (project.code.isBlank()) project.name else "${project.code} · ${project.name}"

/**
 * The **Bucket** picker above the reader's own Other-task board (Stage 88): a real GET form onto this page whose
 * `bucket` key is the whole filter — the URL is the state, so a bookmark or a back button restores the same
 * narrow view. `data-autosubmit` (app.js) submits it on change: picking a bucket answers in place rather than
 * asking for a second press. The button below the select is the same submission for a browser with no script,
 * which is why it is a `submit` and not a link.
 *
 * *All buckets* is option `0` — the absence of a filter, and the value a plain page's URL has. Selecting one of
 * the board's columns narrows the cards; it never changes how they are grouped, and the five status columns
 * above them are still the whole board. Since **Stage 90** it is drawn even when the reader has no Other-task
 * cards yet, because the `+ Add task` line beneath the board aims at whatever bucket it shows — an empty board
 * without it would leave the first card with no bucket to land in.
 *
 * The select sits **inside** its label, exactly like the projects board's sort pickers, because that pair is what
 * the field styling keys on. **Stage 89** shrank it to the length of one card on the board it narrows: as a
 * sibling of the label it fell through to the bare `select { width: 100% }` and stretched across the page, which
 * made a one-dropdown filter read like a toolbar.
 *
 * [chosenProjectId] is the projects board's filter above (**Stage 107**), carried as a hidden field: the two
 * pickers narrow two boards on one page, and pressing Show on one must not widen the other. Only a live choice
 * rides along, so the form's URL is the reader's view and nothing more.
 */
private fun DIV.otherBucketFilter(buckets: List<Bucket>, chosen: Bucket?, chosenProjectId: Int) {
    form(action = "/app/assigned-tasks", method = FormMethod.get, classes = "board-toolbar other-bucket-filter") {
        label("board-key") {
            +"Bucket"
            select {
                id = "otherBucket"
                name = "bucket"
                attributes["data-autosubmit"] = "true"
                option {
                    value = "0"
                    selected = chosen == null
                    +"All buckets"
                }
                buckets.forEach { bucket ->
                    option {
                        value = "${bucket.id}"
                        selected = bucket.id == chosen?.id
                        +bucket.name
                    }
                }
            }
        }
        if (chosenProjectId > 0) {
            input(type = InputType.hidden, name = "project") { value = "$chosenProjectId" }
        }
        button(type = ButtonType.submit, classes = "submit btn-sm") { +"Show" }
    }
}

/**
 * The `+ Add task` line under the reader's own Other-task board (Stage 90): the blank card popup, aimed at a
 * bucket of that board.
 *
 * It is the Other-tasks page's `bucketAddTaskForm` mechanism — a GET form carrying `new=1` and the `column` to
 * open on — aimed at this page's URL instead, so `app.js` swaps the popup into `#taskModal` without disturbing
 * the board behind it. The line is drawn for every reader because writing a card onto the backlog is every
 * signed-in account's ([mn.murray.pts.service.TaskService.createBacklog]); the popup then prints a non-manager's
 * writer and starting status as facts ([TaskForm.selfPinned]) and management's as choices.
 *
 * `column` is the bucket the picker shows, or the board's first when it shows *All buckets*: a non-manager's
 * popup pins its column (Stage 49), so the line has to name one. `bucket` rides only while a filter is on, which
 * is what brings the save back to the same narrowed board; [chosenProjectId] rides the same way so a save made
 * while the projects board above is narrowed answers on that same view (Stage 107).
 */
private fun DIV.otherAddTaskForm(buckets: List<Bucket>, chosen: Bucket?, chosenProjectId: Int) {
    val aim = chosen ?: buckets.firstOrNull() ?: return
    form(action = "/app/assigned-tasks", method = FormMethod.get, classes = "kcol-add") {
        attributes["data-live-target"] = "#taskModal"
        input(type = InputType.hidden, name = "new") { value = "1" }
        input(type = InputType.hidden, name = "column") { value = "${aim.id}" }
        if (chosen != null) input(type = InputType.hidden, name = "bucket") { value = "${chosen.id}" }
        if (chosenProjectId > 0) input(type = InputType.hidden, name = "project") { value = "$chosenProjectId" }
        button(type = ButtonType.submit, classes = "kcol-add-btn") { +"+ Add task" }
    }
}

/**
 * The `+ New bucket` opener in the empty board's panel (**Stage 124**): the Other tasks board's "new column",
 * offered from the panel that used to repeat the page head's `+ New task`.
 *
 * It is the Other tasks page's `+ Add bucket` mechanism, aimed at this page: a GET form carrying `newbucket=1`
 * (and whichever filters are on, so opening the popup never rebuilds a widened board behind it) that
 * `static/js/app.js` answers by fetching this URL and swapping only `#bucketModal` — the server draws the
 * add-a-bucket popup open, exactly as it does there. Scripts off, the same click loads this page with the same
 * popup up. The popup's own form posts to `/app/other-buckets` with `back=assigned-tasks`, and that route answers
 * with this page so the column it makes arrives in `#assignedOther` (the picker, the reader's own cards and the
 * `+ Add task` line) without a reload.
 *
 * Drawn for the reader who may name a column — *Manage buckets* — not for every card-writer: the route refuses
 * the write for anybody else, so offering it would be a button a role cannot use. A reader who writes cards
 * without that right keeps the old `+ New task` offer in the panel instead.
 */
private fun DIV.newBucketButton(chosenBucket: Bucket?, chosenProject: Project?) {
    form(action = "/app/assigned-tasks", method = FormMethod.get, classes = "opener-form") {
        attributes["data-live-target"] = "#bucketModal"
        input(type = InputType.hidden, name = "newbucket") { value = "1" }
        if (chosenBucket != null) input(type = InputType.hidden, name = "bucket") { value = "${chosenBucket.id}" }
        if (chosenProject != null) input(type = InputType.hidden, name = "project") { value = "${chosenProject.id}" }
        button(type = ButtonType.submit, classes = "submit btn-sm new-bucket-btn") { +"+ New bucket" }
    }
}
