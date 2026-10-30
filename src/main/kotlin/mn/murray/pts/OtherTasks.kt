package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import mn.murray.pts.service.BucketError
import mn.murray.pts.service.TaskService

/**
 * The line the page head prints beside *Other tasks*.
 *
 * The title says what the page is; this says what the work on it is not, which is the one thing a reader
 * landing here from the sidebar has to be able to check. It is the backlog's own heading sentence, shortened —
 * the section head that used to carry it under the board repeated the page's name a second time, and the page
 * now has one head rather than two.
 */
const val OTHER_TASKS_SUBTITLE = "tasks outside of projects"

/**
 * The Other tasks page: the backlog, grouped by bucket and cut by status.
 *
 * This is the work that belongs to no project, and since Stage 47 it is a page rather than a section at the foot
 * of somebody else's: the sidebar item names it, the head says what it is, and everything underneath belongs to
 * that one board.
 *
 * **Since Stage 57 the board is read the same way a project's board is.** It is no longer a set of columns cut by
 * bucket; it is one **group per bucket**, each with the bucket's name printed on top the way a project page prints
 * its project name, and inside that group the cards are cut by the same five **status** columns the project and
 * Assigned boards use — Not started, In progress, On hold, Blocked, Completed. So a bucket says *what kind* of
 * off-project work this is, and the columns beneath it say *how far* each card has got. A group is printed for
 * every bucket that **holds cards**, because a run of nine names where five hold nothing is a list of intentions
 * rather than of work — and hiding a bucket is not deleting it. Since **Stage 97** the way to see a bucket that
 * holds nothing is the picker below the heading: it lists every column, empty ones included, and a chosen bucket
 * prints its own group even when the answer is five *Nothing here.* panels. *(Until Stage 97 there was a second
 * way — a `Show all buckets` toggle in the page head that widened the whole board at once. The user asked for the
 * page to read as one control, and the picker already covers both of that button's readings: name a bucket to see
 * it, name *All buckets* to come back. See Stage 40 and Stage 57.)*
 *
 * What is on the board is everybody's to read and everybody's to write into: [mn.murray.pts.service.TaskService.createBacklog]
 * is open to every signed-in account, which is why the `+ Add task` line at the foot of a column is drawn for
 * a Viewer as readily as for management. Since Stage 48 so is the dashed box that names a new column — the work
 * outside the projects is noticed by whoever stands nearest to it, and a column for it is one line of typing.
 * What stays management's is the **rest** of a column's life: renaming one and emptying one, which move or erase
 * the cards sitting in it, and the `⋮` that offers both is drawn only for the reader whose routes accept it. The
 * popup travels with every board, because the add half is everybody's, and it prints the manage half only for
 * the reader who may use it. The routes check the role again; markup is presentation of a right, never the right
 * itself.
 *
 * [state] is the card popup, and it is up when the page was asked to arrive that way — `?new=1&column=<id>`
 * from a column's own line, `?edit=<card>` from a card, or the page a rejected save came back as. [bucket] is
 * the same arrangement for a column's popup. Both popups are the app's shared ones: a card written here is
 * edited in the very form an assigned card opens, because it is the same card shape.
 *
 * **Since Stage 93 the board carries the same Bucket picker the Assigned page's Other-task half has.** The reader
 * picks one of the board's own buckets and sees only that group; *All buckets* is the absence of a filter and the
 * page they get by default. The key is `?show=<id>` rather than `?bucket=` because on **this** URL `?bucket=` already
 * names a column's *popup* (see [bucketMenuForm]) — one key with two meanings would make a bookmark of a filtered
 * board indistinguishable from a bookmark of a rename form. [showBucketId] is that id; an id the board no longer has
 * answers with every bucket, exactly as the Assigned page's stale filter does.
 *
 * It is management's board that the picker narrows — it lists the shared backlog's buckets — so it is drawn for
 * [canManage] alone. A Viewer's page stays the one-bucket slice Stage 51 gave them, and a `?show=` from their
 * address bar is answered with that slice rather than with an empty group naming a bucket they hold no card in.
 *
 * Since **Stage 97** this is the board's only view control. The page used to carry a second one — a `Show all
 * buckets` toggle that printed the empty columns alongside the full ones — and the two then had to be kept in step
 * with each other and with every form that came back to this page. With the toggle gone there is one state to
 * carry: `show`, which the picker posts, the head's `+ Add bucket` rides, and every popup returns on.
 */
fun DIV.otherTasksContent(
    session: UserSession,
    canManage: Boolean,
    state: TaskForm? = null,
    bucket: BucketForm = BucketForm(),
    showBucketId: Int = 0,
) {
    val columns = otherColumns(canManage, session.employeeNumber)
    // The board's own buckets, for the picker and to ask the filter against them: a `?show=` naming a column that
    // has been deleted (or never existed) is *All buckets*, never an empty board under a picker with nothing in it.
    val buckets = TaskService.bucketRows()
    // Management's board is the whole backlog, so it is the whole set of buckets this filter may name. A
    // Viewer's page is their own slice (Stage 51) — one bucket, named by their card — so `?show=` is not a
    // filter there but a way to ask for a bucket they hold no card in, and it answers with their own board
    // instead. The picker below is drawn for the same reader, which is why both halves ask `canManage`.
    val chosen = if (canManage) buckets.firstOrNull { it.id == showBucketId } else null
    // One bucket asked for is one group printed, even when it holds nothing — the reader named it, so an empty
    // answer that names it is the honest one. Without a filter the board keeps its Stage 57 rule: only the buckets
    // holding work. Stage 97 removed the third case (the wide view) that used to sit between these two.
    val shown = when {
        chosen != null -> columns.filter { it.first.id == chosen.id }.ifEmpty { listOf(chosen to emptyList()) }
        else -> columns.filter { it.second.isNotEmpty() }
    }
    // The filter's id as it is safe to carry: the chosen column, or *All buckets* when the id was stale.
    val filterId = chosen?.id ?: 0
    // The picker stands above the board rather than in the head, so the control the head owns (`+ Add bucket`)
    // reads as the page's own while this one reads as the board's — the same place the
    // Assigned page puts its copy. Management alone: a listing of every bucket is the shared board's, and a
    // Viewer's page is scoped to the one bucket their card stands in (Stage 51) rather than narrowed by a picker.
    if (canManage && buckets.isNotEmpty()) otherBucketPicker(buckets, chosen)

    // The id is what every control on this page posts back into: app.js swaps this one block for the copy in the
    // response, so a bucket arriving, renaming, emptying or gaining a card's percentage moves on the board without
    // the page restarting under the reader. Since Stage 57 this block is a **vertical run of bucket groups**, not a
    // row of columns — the swap target is still the whole board, so one id still covers every group.
    //
    // Since Stage 93 it is also the page's **live region**: the Bucket picker above is a filter form, and app.js
    // answers it by replacing this block in place and rewriting the URL — the same exchange the projects board's
    // sort pickers use. That is why the picker is outside it: the form the reader just changed must not be the
    // node the answer replaces, or the caret and the open state would be re-made under their hands.
    div("other-groups") {
        id = "otherBoard"
        attributes["data-live-region"] = "true"
        shown.forEach { (row, inBucket) ->
            // One bucket, one group: its name on top like a project name, and its own status board underneath.
            div("bucket-group") {
                div("bucket-group-head") {
                    // The accent colour marks the bucket's name, never a status — the five status columns below
                    // each carry their own status dot, and this says only *which kind* of off-project work this is.
                    span("dot d-bucket") { }
                    span("bucket-group-title") { +row.name }
                    span("bucket-group-count") { +"${inBucket.size}" }
                    if (canManage) bucketMenuForm(row.id, filterId)
                }
                // The bucket's cards, cut by the same five statuses the project and Assigned boards print. An
                // empty status column says "Nothing here." exactly like those boards, so a reader who knows one
                // board knows this one. The `.kcol[data-status]` shape is what `kanban.js` recognises a status
                // column by — since Stage 94 that recognition happens on `document` as the drag passes over
                // whatever column the page is showing now, which is what keeps this board draggable after the
                // picker below has swapped it, so the owner drags their card along this row the way they do
                // anywhere else (Stage 103 retired the arrows that used to sit under it).
                div("kanban") {
                    TASK_STATUSES.forEach { status ->
                        val inCol = inBucket.filter { it.status == status }
                        div("kcol") {
                            attributes["data-status"] = status
                            div("kcol-head") {
                                span("dot ${statusDot(status)}") { }
                                span("kcol-title") { +status }
                                span("kcol-count") { +"${inCol.size}" }
                            }
                            if (inCol.isEmpty()) p("kcol-empty") { +"Nothing here." }
                            inCol.forEach { otherCard(it, session, canManage, filterId) }
                        }
                    }
                }
                // Stage 51: writing a *new* card onto the shared backlog stays management's, and the line is drawn
                // **once for the whole bucket** rather than once per status column — it creates a card *in this
                // bucket* (its status is chosen in the popup), so aiming one line per column would ask the reader
                // to pick a starting status they have not decided yet. A Viewer gets no line and no bucket tools.
                if (canManage) bucketAddTaskForm(row, filterId)
            }
        }
    }
    // The bucket popup travels with the board it changes. Since Stage 51 only management has a column to change,
    // so the popup — add, rename, delete — is drawn for that reader alone; a Viewer's copy would be a landing
    // point no control on their scoped board opens.
    if (canManage) bucketModal(bucket, columns, canManage, filterId)

    // The card popup travels with the page, for every reader. It used to be handed only to the roles that had a
    // card to open, and the cost was spelled as one hidden form nobody had to see; what that cost buys is the
    // landing point every `+ Add task` line and every card on this board swaps into.
    //
    // `state ?: TaskForm(...)` is the placeholder a plain page ships: drawn for the reader standing here, so a
    // normal account's copy holds only their own name and status rather than the whole roster — the node is a
    // landing point for `?edit=` and `?new=1`, not a directory of management's pickers.
    //
    // The board the popup answers on is named by the token, and since Stage 93 that token carries the bucket filter
    // (`other:<id>`) exactly as the Assigned page's `assigned:<id>` does — so a save, a step tick, a note, a delete
    // and a refusal all land on the narrowed board the card was opened from rather than on the full one.
    taskFormModal(state ?: TaskForm(canEditAll = canManage), backForOtherBucket(filterId))
    // The board's status moves live in an external script (Stage 7): the markup contract is
    // .kcol[data-status], .kcard[draggable][data-id] and .kmove-btn[data-*]. Since Stage 57 the columns here
    // **are** statuses, and since Stage 58 a backlog card a reader may move carries `draggable`, so dragging it
    // into another column of its bucket posts `/app/tasks/status` and reloads — the card comes back filed in the
    // column it landed in under the same bucket heading. **Stage 103 retired the owner's `.kmove-btn` arrows from
    // both status boards**, so the drag is the way: the contract above still names `.kmove-btn` because a
    // project's lane board prints its holder's ✓ Done / ↺ Reopen button and `kanban.js` binds the same delegated
    // handler for it. A drag changes status, never the bucket: which bucket a card belongs to is said in its
    // popup by the one reader whose popup lets it be said — management's, since Stage 49 (see
    // [mn.murray.pts.service.TaskService.updateAssigned]).
    script(src = "/static/js/kanban.js") {}
}

/**
 * The **Bucket** picker above the shared board (Stage 93): the Assigned page's Other-task filter, on the page where
 * the backlog lives.
 *
 * It is the same control, not a second design — `board-toolbar other-bucket-filter`, a label reading *Bucket*
 * wrapped around the select, `data-autosubmit` so a choice answers at once, and a **Show** button for a browser with
 * no script. The select sits **inside** its label for the reason Stage 89 gave the other one: that pair is what the
 * field styling keys on, and as siblings the select falls through to the bare `select { width: 100% }` rule and
 * stretches a one-field filter across the page.
 *
 * *All buckets* is option `0` — the absence of a filter, and what a plain URL means. The key is `show` rather than
 * `bucket` because `?bucket=` on this URL already opens a column's popup; the two would be indistinguishable to a
 * bookmark.
 *
 * The form is a **filter form**, not a block form: `app.js` answers it by replacing the live region (`#otherBoard`)
 * in place and rewriting the URL, so the board changes without the page restarting. `+ Add bucket` is named as its
 * companion because it carries the filter in its own hidden field — an add button left pointing at an unfiltered
 * popup is the half-updated page that mechanism exists to prevent.
 *
 * **Since Stage 97 this is the board's only view control.** The page head's `Show all buckets` toggle used to be
 * listed here as a second companion, and the picker carried the wide flag so a reader could come back to the view
 * they had left; both went with the toggle, and the empty columns it revealed are now reached one at a time — by
 * naming them.
 */
private fun DIV.otherBucketPicker(buckets: List<Bucket>, chosen: Bucket?) {
    form(action = "/app/other-tasks", method = FormMethod.get, classes = "board-toolbar other-bucket-filter") {
        id = "otherBucketFilter"
        attributes["data-live-extra"] = "#addBucketOpener"
        label("board-key") {
            +"Bucket"
            select {
                id = "otherBucket"
                name = "show"
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
        button(type = ButtonType.submit, classes = "submit btn-sm") { +"Show" }
    }
}

/**
 * The backlog as the board reads it: one entry per column, in board order, each holding the cards filed in it.
 *
 * Grouped by the column's **name** rather than its id, because the name is the field the card carries and the
 * field a rename writes — a card always follows the column it is in, under whatever that column is called now.
 */
fun otherColumns(
    canManage: Boolean = true,
    viewerEmpNo: String = "",
): List<Pair<Bucket, List<TaskItem>>> {
    val backlog = DemoData.otherTasks
    // Stage 51: the board prints everybody's cards for the reader who runs the board, but a Viewer is shown only
    // the backlog work that is theirs — the card a management assign or their own notification handed them. A
    // column holding none of their cards is not part of their view at all, so the column skeleton management
    // recognises never reaches them. This is what keeps the personal path (their own card) while taking the
    // *function* (the shared backlog and its columns) back to management.
    val rows = TaskService.bucketRows().map { row ->
        row to backlog.filter { it.bucket == row.name && (canManage || it.assignee == viewerEmpNo) }
    }
    return if (canManage) rows else rows.filter { it.second.isNotEmpty() }
}

/**
 * What the backlog's bucket popup holds.
 *
 * It is one popup for the board's three column writes — add, rename, delete — because they are one decision
 * about one column, and because the page can only swap a block that has one id: a second `#bucketModal` would
 * be a second node for `app.js` to find and neither of them would be the one the reader is looking at.
 * So [bucketId] says which column the popup is about, and 0 — the value no column has — says it is the create.
 *
 * The two errors are kept apart rather than merged into one line at the top of the popup: [nameError] belongs
 * under the name field that caused it and [deleteError] under the choice of where the cards go, which is where
 * a reader whose delete was refused will be looking.
 *
 * [typed] is the name as it was posted, so a refused rename comes back with the word still in the box, and
 * [moveId] is the destination as it was posted, so a refused delete comes back with the choice still made.
 */
data class BucketForm(
    val typed: String = "",
    val nameError: String? = null,
    val deleteError: String? = null,
    val open: Boolean = false,
    val bucketId: Int = 0,
    val moveId: Int = 0,
) {
    /** Whether this popup is about a column that is already on the board. */
    val isManage: Boolean get() = bucketId != 0

    /**
     * Up on the page a reader asked for it (`?newbucket=1`, or a column's `?bucket=<id>`), and up on the page
     * a refusal came back as — the reader wrote something and is owed the reason it did not land. Naming a
     * column is the one case where the popup is drawn for the id it is about rather than for a flag: a `⋮`
     * press cannot say "and keep it closed".
     */
    val isOpen: Boolean get() = open || bucketId != 0 || nameError != null || deleteError != null
}

/**
 * The words a refused column write is printed in. The popup carries the reason, not the URL: a lane's refusal
 * travels as `?lane=` because nothing on that page posts to its route any more (Stage 35), while every bucket
 * write here is a form that answers itself, so the message and the field it belongs to come back together.
 */
fun bucketMessage(error: BucketError): String = when (error) {
    BucketError.BLANK -> "Give the bucket a name."
    BucketError.TOO_LONG -> "Bucket names are $BUCKET_NAME_MAX characters or fewer."
    BucketError.DUPLICATE -> "There is already a bucket called that."
    BucketError.NO_DEST -> "Choose which bucket takes its cards."
    BucketError.LAST_BUCKET ->
        "This is the only bucket left and it still holds cards. Add another one first."
    BucketError.DENIED -> "Only management can rename or delete a bucket."
    BucketError.NO_BUCKET -> "That bucket is not on the board any more."
}

/**
 * The `⋮` on one column's head: the opener for that column's popup.
 *
 * [newTaskButton]'s mechanism, aimed at a column — a GET form onto this page as `?bucket=<id>`, which
 * `static/js/app.js` answers by fetching that URL and swapping only `#bucketModal`, so the popup the server
 * draws opens over the board the reader is standing on. Scripts off, the same click loads the same page with the
 * same popup up.
 *
 * The id travels as a field of this form rather than as an `href` written per column, which is why there is one
 * mechanism for however many columns the board has.
 */
private fun DIV.bucketMenuForm(bucketId: Int, showBucketId: Int = 0) {
    form(action = "/app/other-tasks", method = FormMethod.get, classes = "kcol-menu") {
        attributes["data-live-target"] = "#bucketModal"
        input(type = InputType.hidden, name = "bucket") { value = "$bucketId" }
        // The popup this opens redraws the board its own forms answer on, so the board's filter has to be in
        // the fetch as well or opening a column's menu would quietly widen the board behind it (Stage 93).
        if (showBucketId > 0) input(type = InputType.hidden, name = "show") { value = "$showBucketId" }
        button(type = ButtonType.submit, classes = "kcol-menu-btn") {
            attributes["aria-label"] = "Manage this bucket"
            attributes["title"] = "Rename or delete this bucket"
            +"⋮"
        }
    }
}

/**
 * The `+ Add bucket` control in the page head.
 *
 * It sits there rather than at the foot of the board because what it opens changes what the reader is looking at:
 * a new column joins the run of bucket groups beneath. The dashed full-height box
 * it used to be sized itself as a column of the board, but the board is a vertical run of groups now (Stage 57),
 * so a lone column-shaped box trailing under it read as a stranded placeholder rather than an offer. A small button
 * opposite the title is the page's own vocabulary for "add one to this page" — the same shape the head's `+ New
 * project` already uses. *(Until **Stage 97** this was the head's second control, beside the `Show all buckets`
 * toggle; the toggle went, and this one is the only thing the head offers.)*
 *
 * It is management's (Stage 51), on the same reasoning as the `⋮`: renaming and deleting columns are
 * a different right from naming one, and the popup this opens prints those halves only for the reader who holds them.
 * It is not a field you can type a name into either — the name is written in the popup, where a refusal has somewhere
 * to print its reason.
 *
 * The same GET form onto this page carrying `newbucket=1` the box carried, so the create mechanism is unchanged:
 * [mn.murray.pts.Application]'s bucket route swaps `#bucketModal` open over the board.
 *
 * Since **Stage 93** the form names itself (`#addBucketOpener`) and carries the board's bucket filter when one is on,
 * so the popup it opens writes into the same narrowed board and the picker's own refresh can put the filter back in
 * this button rather than leaving it pointing at a view the reader has already left.
 */
fun DIV.addBucketButton(showBucketId: Int = 0) {
    form(action = "/app/other-tasks", method = FormMethod.get, classes = "opener-form addbucket-form") {
        id = "addBucketOpener"
        attributes["data-live-target"] = "#bucketModal"
        attributes["data-live-extra"] = "#bucketModal"
        input(type = InputType.hidden, name = "newbucket") { value = "1" }
        if (showBucketId > 0) input(type = InputType.hidden, name = "show") { value = "$showBucketId" }
        button(type = ButtonType.submit, classes = "ghost-btn btn-sm") { +"+ Add bucket" }
    }
}

/**
 * The `+ Add task` line at the foot of one column: the card's own popup, aimed at the column under which it
 * sits.
 *
 * It is [laneAddTaskForm]'s mechanism and its shape — a GET form onto this page
 * carrying `new=1` plus the column to open the blank form on, swapped into `#taskModal` so the board underneath
 * never moves. It used to be a title box that posted the card straight into the column, and that is why it is
 * drawn for every reader rather than for management alone: an ordinary account may write a backlog card too
 * (see [mn.murray.pts.service.TaskService.createBacklog]), and the form that lets them set dates, a priority
 * and a column is the same popup an assigned card opens. A title box could only ever hold a title.
 *
 * `column` rather than `bucket` because `?bucket=` on this URL already names a column's *popup* (see
 * [bucketMenuForm]) — two meanings on one key would make a column's menu and an aimed create indistinguishable
 * to a bookmark, exactly the clash the lane list avoids with `into`.
 */
private fun DIV.bucketAddTaskForm(bucket: Bucket, showBucketId: Int = 0) {
    form(action = "/app/other-tasks", method = FormMethod.get, classes = "kcol-add") {
        attributes["data-live-target"] = "#taskModal"
        input(type = InputType.hidden, name = "new") { value = "1" }
        input(type = InputType.hidden, name = "column") { value = "${bucket.id}" }
        // The blank popup answers on the board it was opened from, and on a narrowed board that is this id —
        // the same carriage `?edit=` gets from a card's own opener (Stage 93).
        if (showBucketId > 0) input(type = InputType.hidden, name = "show") { value = "$showBucketId" }
        button(type = ButtonType.submit, classes = "kcol-add-btn") { +"+ Add task" }
    }
}

/**
 * The popup behind the `+ Add bucket` box and a column's `⋮`: one column's name, and where its cards go.
 *
 * `#bucketModal` is the block the two openers swap in, and `#otherBoard` is the block this popup's own sends
 * swap, with the popup named in `data-live-extra` as their companion. That pairing is what makes the whole
 * exchange need no script of its own to open or close it: the response the server renders decides whether the
 * popup is up — a write that landed comes back with the board redrawn and a closed popup, a write that was
 * refused comes back with the board unchanged, this popup open, and its reason under the field it came from.
 *
 * Rename and delete are two forms, not one form with two buttons: `app.js` reads the block list off the form
 * being sent and posts it to its own `action`, so two buttons in one form would be one button aimed at the
 * wrong route. Each names the field it writes, and the delete names the column its cards are handed to.
 *
 * [canManage] decides which half of that the reader is shown. Since Stage 48 the popup is drawn on every copy of
 * this page, because adding a column is everybody's write — so the manage half is gated here rather than by the
 * whole popup being gated, and a reader without the right gets one field and one button. The routes refuse the
 * two writes they cannot see, so this is presentation; and a `?bucket=<id>` from such a reader is answered with
 * the add half rather than a rename form they were never owed.
 */
private fun DIV.bucketModal(
    state: BucketForm,
    columns: List<Pair<Bucket, List<TaskItem>>>,
    canManage: Boolean,
    showBucketId: Int = 0,
) {
    val managed = columns.firstOrNull { it.first.id == state.bucketId }
    div("modal") {
        id = "bucketModal"
        if (state.isOpen) attributes["style"] = "display:block"
        div("modal-content") {
            span("close") {
                attributes["data-bucket-modal-close"] = "true"
                +"×"
            }
            if (canManage && state.isManage && managed != null) bucketManageBody(managed, state, columns, showBucketId)
            else bucketAddBody(state, showBucketId)
        }
    }
    script(src = "/static/js/bucket-modal.js") {}
}

/** The create half of the popup: one field, the name of the column to add. */
private fun DIV.bucketAddBody(state: BucketForm, showBucketId: Int = 0) {
    h3 { +"Add a bucket" }
    p("modal-scope") {
        +(
            "A bucket is a column of its own on the Other tasks board — a name for work that is not " +
                "on a project."
            )
    }
    form(action = "/app/other-buckets", method = FormMethod.post) {
        attributes["data-live-target"] = "#otherBoard"
        // The picker is a companion: a column that landed is an option it did not offer a moment ago, and a
        // picker still one name short is the half-updated page this mechanism exists to prevent (Stage 93).
        attributes["data-live-extra"] = "#bucketModal,#otherBucketFilter"
        div("field") {
            label { +"Bucket" }
            input(type = InputType.text, name = "name") {
                id = "bucketName"
                placeholder = "e.g. Site works"
                required = true
                attributes["maxlength"] = "$BUCKET_NAME_MAX"
                value = state.typed
            }
            state.nameError?.let { p("field-err") { +it } }
        }
        if (showBucketId > 0) input(type = InputType.hidden, name = "show") { value = "$showBucketId" }
        div("modal-actions") {
            button(type = ButtonType.submit, classes = "submit") { +"Add bucket" }
            button(type = ButtonType.button, classes = "ghost-btn") {
                attributes["data-bucket-modal-close"] = "true"
                +"Cancel"
            }
        }
    }
}

/**
 * The manage half of the popup: the column's name, and the way out of it.
 *
 * Renaming is the cheap half and it is drawn first: the cards point at the column by its id, so a new name is
 * a new heading over the same work and nothing else moves.
 *
 * Deleting is the half that has to say what it does to the cards underneath. [mn.murray.pts.db.Tasks.bucketId]
 * answers `ON DELETE SET NULL`, so dropping a column that still holds cards would take them off **every**
 * column on this board rather than move them — and this board, unlike a project's lane board, has no Unsorted
 * column to catch them. So the field is not "are you sure", it is "which column takes them", and the count is
 * printed in the label because the reader is being asked to account for that much work. A column with nothing
 * in it needs no answer, and the last column on the board with cards in it has nowhere to put them, so the
 * delete is not offered for it at all — the route refuses the same request if it arrives without the markup.
 */
private fun DIV.bucketManageBody(
    column: Pair<Bucket, List<TaskItem>>,
    state: BucketForm,
    columns: List<Pair<Bucket, List<TaskItem>>>,
    showBucketId: Int = 0,
) {
    val bucket = column.first
    val held = column.second.size
    // Every column but this one, in board order — the choices the delete offers, and the reason a delete is
    // refused outright when the list comes out empty. Taken from the whole board rather than from the columns
    // printed behind this popup: a hidden empty column is still somewhere the cards could go.
    val others = columns.map { it.first }.filter { it.id != bucket.id }
    h3 { +"Bucket" }
    p("modal-scope") {
        +(
            "Rename this column, or take it off the board. A bucket's cards belong to it by identity, so a " +
                "new name is only a new heading — deleting it has to say where its cards go first."
            )
    }
    form(action = "/app/other-buckets/${bucket.id}/rename", method = FormMethod.post) {
        attributes["data-live-target"] = "#otherBoard"
        // A rename rewrites the word the picker offers for this column, so the picker is a companion too.
        attributes["data-live-extra"] = "#bucketModal,#otherBucketFilter"
        div("field") {
            label { +"Name" }
            input(type = InputType.text, name = "name") {
                id = "bucketName"
                required = true
                attributes["maxlength"] = "$BUCKET_NAME_MAX"
                // The typed word after a refusal, the stored name before one: a reader who had the name cut
                // short or doubled should see what they posted, not what the database still holds.
                value = if (state.nameError != null) state.typed else bucket.name
            }
            state.nameError?.let { p("field-err") { +it } }
        }
        if (showBucketId > 0) input(type = InputType.hidden, name = "show") { value = "$showBucketId" }
        div("modal-actions") {
            button(type = ButtonType.submit, classes = "submit") { +"Rename bucket" }
            button(type = ButtonType.button, classes = "ghost-btn") {
                attributes["data-bucket-modal-close"] = "true"
                +"Cancel"
            }
        }
    }
    if (others.isEmpty()) {
        p("modal-scope") {
            +(
                if (held == 0) "This is the backlog's only column, and it holds nothing, so there is nothing " +
                    "to move. " else "This is the backlog's only column and it holds $held " +
                    "card${if (held == 1) "" else "s"}, so there is nowhere to move them. "
                )
            +"Add another bucket first."
        }
        if (state.deleteError != null) p("field-err") { +state.deleteError }
    } else if (held == 0) {
        p("modal-scope") { +"Deleting this column moves nothing — it holds no cards." }
        bucketDeleteForm(bucket, emptyList(), state, showBucketId)
    } else {
        p("modal-scope") { +"Deleting this column hands its $held card${if (held == 1) "" else "s"} to another." }
        bucketDeleteForm(bucket, others, state, showBucketId)
    }
}

/**
 * The delete half of a column's popup: the cards' new home, then the button that drops the old one.
 *
 * The destination is a required choice over the other columns and nothing else, and it is a *field* of this
 * form rather than a query on the route, because the cards have to be re-pointed before the row goes — see
 * [mn.murray.pts.service.TaskService.deleteBucket]. With no cards to move there is no choice to make and the
 * form is the button alone.
 */
private fun DIV.bucketDeleteForm(
    bucket: Bucket,
    others: List<Bucket>,
    state: BucketForm,
    showBucketId: Int = 0,
) {
    form(action = "/app/other-buckets/${bucket.id}/delete", method = FormMethod.post) {
        attributes["data-live-target"] = "#otherBoard"
        // A deleted column leaves the picker as well as the board.
        attributes["data-live-extra"] = "#bucketModal,#otherBucketFilter"
        if (others.isNotEmpty()) {
            div("field") {
                label { +"Move its cards to" }
                select {
                    id = "bucketMove"
                    name = "moveTo"
                    attributes["required"] = "required"
                    others.forEach { other ->
                        option {
                            value = "${other.id}"
                            selected = other.id == state.moveId
                            +other.name
                        }
                    }
                }
                state.deleteError?.let { p("field-err") { +it } }
            }
        }
        if (showBucketId > 0) input(type = InputType.hidden, name = "show") { value = "$showBucketId" }
        div("modal-actions") {
            button(type = ButtonType.submit, classes = "submit danger-btn") { +"Delete bucket" }
            button(type = ButtonType.button, classes = "ghost-btn") {
                attributes["data-bucket-modal-close"] = "true"
                +"Cancel"
            }
        }
    }
}

/**
 * A backlog card, opened for its editor by a click, and moved between the status columns by dragging — the one
 * way to change a status since **Stage 103** took the owner's prev/next arrows off both status boards.
 */
private fun DIV.otherCard(
    task: TaskItem,
    session: UserSession,
    canManage: Boolean,
    showBucketId: Int = 0,
) {
    val mine = task.assignee == session.employeeNumber
    // Every card wears its stage's tint, on every board (**Stage 95** for this one). A backlog card sits under a
    // column head that *is* its status, so the word is not repeated on the card (Stage 57) — but the colour is a
    // picture, not a sentence, and the reader who looks at the assigned board and then at this one was seeing the
    // same five stages painted two different ways. `statusSlug` is the same mapping the assigned and project cards
    // are drawn with, so amber means *In progress* on every board in the app. `kcard-overlay` is not a colour: it
    // marks the card that carries the whole-card opener below, the one node `app.css` has to site the form against
    // (it used to be called `s-other`, which read as a sixth stage and quietly overrode the tint's edge colour).
    div("kcard ${statusSlug(task.status)} kcard-overlay") {
        attributes["data-id"] = "${task.id}"
        // Draggable for exactly the reader whose status change `/app/tasks/status` accepts — management (any card)
        // or the card's own assignee (Stage 58). This is [mn.murray.pts.service.TaskService.updateStatus]'s rule
        // read as presentation, and the very flag the assigned and project status boards put on their cards, so a
        // card a reader may not move does not look grabbable, does not start a drag, and needs no script of its own
        // to say so. `kanban.js` answers a status drop through whichever `.kcol[data-status]` the pointer is over
        // (which Stage 57 gave these columns, and Stage 94 made the handler find by delegation rather than bind
        // at load, so a board this page's picker has swapped in place still takes the drop), so the one thing that
        // was missing was the drag source: a drop here posts the status and
        // reloads, and the card comes back filed in the column it landed in **under the same bucket heading** —
        // a status move never changes the bucket, which is the popup's Column picker's answer (Stage 49).
        if (canManage || mine) attributes["draggable"] = "true"
        div("ktitle") { +task.title }
        // The card's live region, the same id and the same contents an assigned card swaps when a step in its
        // popup is ticked (see [cardBodyRegion]). A backlog card has the same plan and the same number, so the
        // same response has to move the same line here or the percentage sits on the value from before the
        // click. It is this one line rather than the `.kcard` because the card node carries the opener —
        // replacing it would drop the form the reader is standing on.
        div("kbody") {
            id = cardBodyRegion(task.id)
            div("kmeta") {
                span("prio ${prioClass(task.priority)}") { +task.priority }
                span("due") { +"📅 ${formatDue(task.due)}" }
                // What the card's own steps add up to, and nothing at all on a card nobody has planned —
                // unmeasured is not zero, which is the rule the assigned board's cards print by too.
                task.progressPercent?.let { span("kpct") { +"$it%" } }
            }
        }
        div("kmeta kfoot") {
            // The card no longer prints its own status word (Stage 57): the column it sits in *is* its status, so
            // repeating it would be the two-places-for-one-figure the project board stopped doing. What the card
            // still has to say is **who holds it**, and that is the holder's avatar — the same one an assigned
            // card carries, name in the hover label.
            val a = DemoData.team.firstOrNull { it.employeeNumber == task.assignee }
            if (a != null) span("avatar xs") { attributes["title"] = a.name; +initials(a.name) }
        }
        // **Stage 103 retired the arrows here too**, with the identical pair on the Assigned board: the column is
        // the way to change a status, and this board has taken the drop since Stage 58 (Stage 94 kept it working
        // through the picker's in-place swap). Management never had arrows — the popup owns their Status field —
        // and the owner now has the same one path everybody else has, plus the popup they can read.
        if (canManage || mine) openBacklogCard(task, showBucketId, mayMove = canManage || mine)
    }
}

/**
 * The click that opens a backlog card's editor.
 *
 * It is a GET form onto this page as `?edit=<card>` wearing the whole card, rather than the `data-task-card`
 * marker and `#taskEditOpener` the two project boards use. One form per card needs no script to be told which
 * card was pressed, so the same click on the same markup loads the same editor with JavaScript switched off —
 * and the view the reader is standing on rides in the form's own fields, which a bare link could not carry.
 *
 * **Why the button, not just the card, carries `draggable` (Stage 58 fix):** that whole-card overlay is the
 * topmost node under the pointer, so a real drag starts on the button, never on the `.kcard` behind it — and a
 * `<button>` is not a native HTML5 drag origin, so `dragstart` did not fire and the card would not move. Marking
 * the button `draggable` makes it the handle: `kanban.js` starts the drag here and `cardOf()` climbs
 * (`closest('.kcard[draggable="true"]')`) to the card that carries the `data-id`, so the drop still posts that
 * card's status; a plain click on the same button still submits the form and opens the popup. It is gated on
 * [mayMove] so only a reader the route would accept gets a grab handle, matching the `draggable` the card itself
 * prints under the same condition.
 */
private fun DIV.openBacklogCard(task: TaskItem, showBucketId: Int, mayMove: Boolean) {
    form(action = "/app/other-tasks", method = FormMethod.get, classes = "kcard-open") {
        attributes["data-live-target"] = "#taskModal"
        input(type = InputType.hidden, name = "edit") { value = "${task.id}" }
        // The click that opens the editor is a GET onto this page, so the view it is standing on has to ride
        // with it.
        // The bucket filter rides the same way (Stage 93): an editor opened on a narrowed board answers on that
        // board, not on the full run of buckets.
        if (showBucketId > 0) input(type = InputType.hidden, name = "show") { value = "$showBucketId" }
        button(type = ButtonType.submit, classes = "kcard-open-btn") {
            attributes["aria-label"] = "Open ${task.title}"
            attributes["title"] = "Open this task"
            // The whole-card overlay makes this button the node the pointer actually grabs, so the drag has to
            // start here — see the KDoc above. The card behind keeps its own `draggable` as the `data-id` carrier
            // `kanban.js` climbs to.
            if (mayMove) attributes["draggable"] = "true"
        }
    }
}

/* The backlog card's own formatting, file-private like every other view here — TaskBoard.kt
   keeps an identical private set for the task cards it renders. What is *not* copied is the stage tint: a colour
   is the one thing two boards must not be allowed to drift apart on, so the card above reads TaskBoard's internal
   [statusSlug] the same way it already reads its internal [statusDot] for the column dots. */
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatDue(iso: String): String {
    val p = iso.split("-")
    if (p.size != 3) return iso
    val m = p[1].toIntOrNull() ?: return iso
    val d = p[2].toIntOrNull() ?: return iso
    return "%02d %s %s".format(d, MONTHS[m - 1], p[0])
}

private fun prioClass(priority: String): String = when (priority) {
    "High" -> "p-high"
    "Medium" -> "p-med"
    else -> "p-low"
}
