package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.DIV
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.script
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
 * plain statement that they have none. It is **not** the shared backlog board returning here — no columns, no
 * `⋮`, no *Add bucket*, no view toggle: only the reader's own cards, cut by status like the projects board above,
 * opened and saved through the very same popup. The shared board and its management controls stay on
 * [mn.murray.pts.otherTasksContent], which is where the backlog *as a board* lives.
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
) {
    // Stated on the page for the same reason the sidebar count is scoped: two cards out of nine,
    // with nothing saying why, reads as work that went missing. Since Stage 53 it is true of every reader, so
    // the sentence is printed to management too rather than only to a Viewer.
    p("board-scope") { +"Only the cards assigned to you are on this board." }
    // Both lists are the reader's own cards whichever role they hold ([canManage] governs the rights beneath —
    // create, the full editor, the drag — not who appears). This is the one place the personal-scoping is applied;
    // `assignedFor` and `assignedOtherFor` still answer management's wider read for the Calendar, the project
    // boards and the Team drill-down that need it.
    val cards = TaskService.assignedFor(session.employeeNumber, canManage = false)
    val ownOther = TaskService.assignedOtherFor(session.employeeNumber)
    // The general board has no one project to grant on, so writing the first card here stays
    // management's — [mn.murray.pts.service.TaskService.mayAddTasks] takes a project for a reason.
    // The opener travels with this board only while the Other-task board below has no card of its own to carry it.
    taskKanban(cards, session, canManage, BACK_ASSIGNED, canAdd = canManage, drawOpener = ownOther.isEmpty())

    h2("assigned-other-head") { +"Other tasks" }
    p("assigned-other-sub") { +"Work assigned to you that is not on a project." }
    if (ownOther.isEmpty()) {
        p("assigned-other-empty") { +"You don't have any other tasks assigned." }
    } else {
        // The reader's own backlog cards, cut by status exactly like the projects board above. `canAdd = false`:
        // this is a personal read of cards that already exist, not the shared board's create line — a new Other
        // card is written from the Other tasks page (management) or the Team sheet's assign panel, never from
        // here. The board carries the page's one opener, since the project board handed it over above.
        taskKanban(ownOther, session, canManage, BACK_ASSIGNED, canAdd = false)
    }

    // The popup travels with the page, for every reader: management's `+ New task` in the head swaps into it,
    // and a reader's own card — from either board — swaps into it from the card itself. `state ?: TaskForm(...)`
    // is the placeholder a plain page ships — drawn for the reader standing here, so a normal account's copy holds
    // only their own name and status rather than the whole roster. The save route re-checks the role; markup is
    // not a permission.
    taskFormModal(state ?: TaskForm(canEditAll = canManage), BACK_ASSIGNED)
    // Board drag-and-drop + move buttons live in an external script (Stage 7): the markup
    // contract is .kcol[data-status], .kcard[draggable][data-id] and .kmove-btn[data-*].
    script(src = "/static/js/kanban.js") {}
}
