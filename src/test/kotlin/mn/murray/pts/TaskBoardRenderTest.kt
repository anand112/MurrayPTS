package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.ProjectBucket
import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.domain.UNSORTED_LANES
import mn.murray.pts.domain.TaskItem
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.TaskDraft
import mn.murray.pts.service.TaskService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML

/**
 * What the task board actually renders, on the same real-schema harness as [TaskServiceTest].
 *
 * The routes only hand these strings to the browser, so this is where the promises behind the board get
 * pinned: every column shows even with nothing assigned, a card the reader owns or manages is a click
 * target that opens its editor, and the editor prints the card as the database holds it — every field for
 * management, four of them greyed for the person it is assigned to, with the plan and the log underneath.
 * There is no menu and no status dropdown on a card any more: a board carries one hidden opener form, and
 * a column is changed by dragging, by the owner's arrows, or by management inside the popup. A card prints
 * one number about its plan and nothing else (Stage 41) — the steps and their tick boxes are in the editor
 * the click opens, offered to exactly the two readers the route answers to. A rejected form comes back with
 * the popup open and its message under its own field.
 */
class TaskBoardRenderTest {

    private lateinit var ds: HikariDataSource
    private val manager = UserSession("EMP0001")
    private val engineer = UserSession("EMP0002")
    private lateinit var project: Project

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "board.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"))
        val projectId = ProjectRepository.insert(
            code = "S-01", name = "Haul road widening", status = "Ongoing", progress = 0,
            managerEmpNo = "EMP0001", due = "2027-03-30", type = "Site projects",
        )
        project = ProjectRepository.findById(projectId)!!
        // Every project the application has holds the same six sub-categories (V7, fixed by Stage 48) —
        // `ProjectService.create` gives them out and V10 gave them to the projects that predate it — so the
        // project page this suite renders is a lane board, exactly as the real one is.
        ProjectBucketRepository.seedDefaults(projectId)
        TaskService.create(
            TaskDraft(
                title = "Spread base course", projectName = project.name,
                assigneeEmpNo = "EMP0002", started = "2026-11-01", due = "2026-11-12",
                priority = "High", status = "Not started",
                // Stage 48: a project card is filed in one of the six, so the card this harness starts every
                // test with says which.
                projectLane = DEFAULT_PROJECT_BUCKETS.first(),
            ),
            callerEmpNo = "EMP0001",
            canManage = true,
        )
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    @Test
    fun `an empty board still shows all five columns`() {
        val html = render { taskKanban(emptyList(), engineer, canManage = false, BACK_PROJECT, project.id) }
        assertEquals(5, occurrences(html, "kcol-head"))
        assertEquals(5, occurrences(html, "kcol-empty"))
        TASK_STATUSES.forEach { assertTrue(html.contains(it), "column $it missing") }
    }

    @Test
    fun `an empty board hands the first card to the reader who may write it`() {
        val mayWrite = render {
            taskKanban(
                emptyList(), engineer, canManage = false, BACK_PROJECT, project.id,
                canAdd = true, fixedProject = project,
            )
        }
        // The same call the route makes for a reader this board says nothing to: no right, no invitation.
        val mayNot = render { taskKanban(emptyList(), engineer, canManage = false, BACK_PROJECT, project.id) }
        assertTrue(mayWrite.contains("board-empty"), "an empty board said nothing about starting work")
        // The opener is the page-head form again, so clicking it swaps the popup instead of reloading.
        assertTrue(mayWrite.contains("name=\"new\""), "no create opener inside the empty board")
        assertTrue(
            mayWrite.contains("action=\"/app/projects/${project.id}\""),
            "a new card was aimed somewhere other than this project",
        )
        assertFalse(mayNot.contains("board-empty"), "a reader who cannot write was invited to")
        assertFalse(mayNot.contains("name=\"new\""), "a create opener appeared without the right")
    }

    @Test
    fun `a project manager's blank card is writable and pinned to its project`() {
        TeamRepository.upsert(Member("EMP0003", "Naran", "Survey", "Surveyor"))
        // Built the way `?new=1` builds it, so a form the route hands the page cannot drift from the one
        // these assertions describe.
        val html = render { taskFormModal(newFormOf(canManage = false), BACK_PROJECT, project) }
        // Not management, and still the person writing this card: it needs a title, a holder and a column.
        listOf("title", "assignee", "status").forEach { field ->
            assertFalse(control(html, field).contains("disabled"), "$field greyed on the one writing it")
        }
        assertFalse(html.contains("modal-scope"), "a create form read as somebody else's card")
        // The project, though, is the page they are standing on — one greyed option, not a list to aim at.
        assertTrue(control(html, "project").contains("disabled"), "a new card could be aimed elsewhere")
        val picker = Regex("<select[^>]*name=\"project\"[^>]*>(.*?)</select>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("no project picker rendered")
        assertEquals(1, occurrences(picker, "<option"), "the pinned project still listed every project")
        assertTrue(selectedOption(html, project.name))
        // And the sentence under it says what the page does, not what management's does: a project
        // manager who was offered other projects would find their card on this page anyway.
        assertTrue(html.contains("this project is yours to write for"))
        assertFalse(html.contains("pick another project"))
    }

    @Test
    fun `the right to write a new card never opens an existing one`() {
        val task = card()
        val html = render {
            taskFormModal(
                TaskForm(taskId = task.id, canEditAll = false, canCreate = true, open = true),
                BACK_PROJECT, project,
            )
        }
        // [TaskForm.canCreate] is a create's right and nothing else: this is Stage 23's split, whole.
        listOf("title", "project", "assignee", "status").forEach { field ->
            assertTrue(control(html, field).contains("disabled"), "$field opened on an edit")
        }
        assertTrue(html.contains("modal-scope"), "an owner's editor forgot to say which fields are theirs")
    }

    @Test
    fun `a card is clickable for its manager and its owner, and for nobody else`() {
        val tasks = TaskService.assigned()
        val asManager = render { taskKanban(tasks, manager, canManage = true, BACK_ASSIGNED) }
        val asAssignee = render { taskKanban(tasks, engineer, canManage = false, BACK_ASSIGNED) }
        val asStranger = render { taskKanban(tasks, UserSession("EMP9999"), canManage = false, BACK_ASSIGNED) }
        // The card is the trigger and carries only what the opener needs: the id of the card to ask for.
        listOf(asManager, asAssignee).forEach {
            assertTrue(it.contains("data-task-card=\"1\""))
            // Focusable and named, so the same popup is reachable without a mouse; the ⋯ tab that used
            // to hint at a menu is gone, because there is no menu to open any more.
            assertTrue(it.contains("tabindex=\"0\""), "a clickable card cannot be tabbed to")
            assertTrue(it.contains("aria-label=\"Open Spread base course\""), "the card names no action")
            assertFalse(it.contains("kcard-tab"), "the card still shows the menu mark")
            // Nothing else about the card travels in the markup, because the popup reads the row itself.
            assertFalse(it.contains("data-title="), "the card still ships its fields for an editor")
            assertFalse(it.contains("data-assignee="))
            assertFalse(it.contains("kcard-actions"))
            assertFalse(it.contains("/app/assigned-tasks/delete"), "a card still deletes itself")
        }
        // Nothing at all stands on a manager's card, the status arrows included: everything a manager can
        // do to a card is behind clicking it, and a column is what they change inside that popup.
        assertFalse(asManager.contains("kmove-btn"), "a manager's card still carries move buttons")
        // The owner's arrows stay: their editor greys Status, so a card's column must not depend on
        // management being the one who changes it.
        assertTrue(asAssignee.contains("kmove-btn"))
        assertTrue(asAssignee.contains("draggable"))
        // A reader with no part in the card gets the plain, inert board: no click target, no opener, no
        // drag and no arrows — and no popup to ask this page for.
        assertFalse(asStranger.contains("data-task-card"))
        assertFalse(asStranger.contains("taskEditOpener"))
        assertFalse(asStranger.contains("tabindex"))
        assertFalse(asStranger.contains("draggable"))
        assertFalse(asStranger.contains("kmove-btn"))
    }

    @Test
    fun `a card offers no status dropdown anywhere on the board`() {
        val onManagersBoard = render { taskKanban(TaskService.assigned(), manager, canManage = true, BACK_ASSIGNED) }
        val onOwnersBoard = render { taskKanban(TaskService.assigned(), engineer, canManage = false, BACK_ASSIGNED) }
        // The menu's mover went with the menu. `/app/tasks/status` is still the route the arrows and the
        // drag post to — see kanban.js — it simply has no rendered control of its own on a board.
        listOf(onManagersBoard, onOwnersBoard).forEach {
            assertFalse(it.contains("/app/tasks/status"), "the board still prints a status form")
            assertFalse(it.contains("aria-label=\"Move this task to\""))
            assertFalse(it.contains("data-autosubmit"), "the board still auto-submits a mover")
        }
    }

    @Test
    fun `the board renders one hidden opener for its whole column set`() {
        val html = render { taskKanban(TaskService.assigned(), manager, canManage = true, BACK_PROJECT, project.id) }
        assertEquals(1, occurrences(html, "id=\"taskEditOpener\""))
        assertTrue(html.contains("hidden=\"true\""))
        // One form for the whole board, so forty cards still carry one field and one id, which
        // task-modal.js fills from whichever card was clicked. It has no button and no label: nothing on
        // it is meant to be seen or clicked.
        assertEquals(1, occurrences(html, "card-edit-opener"))
        assertEquals(0, occurrences(html, "<button"), "the opener grew a control of its own")
        // The menu is deleted, not hidden: no element, no old class names, no Edit text, no delete.
        assertFalse(html.contains("taskCardMenu"), "the menu is still on the page")
        assertFalse(html.contains("card-menu"), "the menu's markup came back")
        assertFalse(html.contains(">Edit<"), "the board still prints an Edit button")
        assertFalse(html.contains("/app/assigned-tasks/delete"), "the board still offers Delete")
    }

    @Test
    fun `a card click asks its own page for that card's editor`() {
        val tasks = TaskService.assigned()
        val mine = render { taskKanban(tasks, engineer, canManage = false, BACK_PROJECT, project.id) }
        val theirs = render { taskKanban(tasks, manager, canManage = true, BACK_ASSIGNED) }
        // A GET form onto the page it sits on, aimed at #taskModal: app.js fetches that page and swaps
        // the popup instead of navigating, and with scripts off the same URL is an ordinary load of the
        // same page. Which card to open is a field the click writes, not an href per card.
        listOf(mine to "/app/projects/${project.id}", theirs to "/app/assigned-tasks").forEach {
            (html, page) ->
            assertTrue(html.contains("action=\"$page\""), "the opener points off this page")
            assertTrue(html.contains("method=\"get\""), "the opener would navigate instead of swapping")
            assertTrue(html.contains("data-live-target=\"#taskModal\""))
            // 0 until a card is clicked: a hand-sent ?edit=0 is a page that opens nothing.
            assertTrue(html.contains("name=\"edit\" value=\"0\""))
        }
    }

    @Test
    fun `nothing on the board deletes a card`() {
        val html = render { taskKanban(TaskService.assigned(), manager, canManage = true, BACK_ASSIGNED) }
        // The delete is gone from the markup outright, so a hand-built click has nothing to find; the
        // route itself is unchanged and still checks the role.
        assertFalse(html.contains("task-del-btn"))
        assertFalse(html.contains("/app/assigned-tasks/delete"))
        assertFalse(html.contains("data-confirm"), "a card still asks to confirm something it cannot do")
        // The only card writer on a board is the menu's opener; the save form lives in the popup, which
        // this render does not include.
        assertFalse(html.contains("/app/assigned-tasks/save"))
    }

    @Test
    fun `a project page is cut by status and carries no manager panel`() {
        val html = render { projectDetailContent(manager, project, canManage = true) }
        val tasks = html.indexOf("Tasks on this project")
        assertTrue(tasks >= 0, "no task board on the project page")
        // The assignees box is still gone (assignees remain in the data for alerts). The manager panel went in
        // Stage 30 and the manager is edited nowhere but the header strip now — Stage 60 moved it into the
        // editable fields up top, which the user's picture labels "PROJECT MANAGER", so the two words are on the
        // page by design. What must stay gone is the separate bottom-of-page panel: its own "Set manager"
        // control and the distinct /manager route it posted to, both of which no inline field ever prints.
        assertFalse(html.contains("Project assignees"))
        assertFalse(html.contains("Set manager"), "the manager panel came back")
        assertFalse(html.contains("/manager"), "a separate manager route came back")
        // Stage 52 cut the project page to the **five statuses**, the same columns the general Assigned tasks
        // page prints. Each is a `.kcol[data-status]` a drag posts to `/app/tasks/status`, and the word the
        // old lane board refused to print is a column head again.
        assertEquals(
            TASK_STATUSES.size, occurrences(html, "class=\"kcol\" data-status"),
            "the project board is not its five status columns",
        )
        TASK_STATUSES.forEach {
            assertTrue(html.contains("<span class=\"kcol-title\">$it</span>"), "$it is not a column here")
        }
        // The six sub-categories the project still files cards into behind the scenes are no longer columns.
        assertFalse(html.contains("data-lane"), "a lane column is on the project board")
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertFalse(html.contains("<span class=\"kcol-title\">$it</span>"), "$it is still a column here")
        }
        // And nothing under or beside these columns names a lane or takes one off (Stage 35 stands), not even
        // for the management account this render is drawn for: the two `/buckets` routes stay in the
        // application with no control pointing at them.
        assertFalse(html.contains("/buckets"), "a lane route is on the page")
        assertFalse(html.contains("laneName"), "a box to name a lane came back")
        assertFalse(html.contains("laneArea"), "a lane swap region came back")
    }

    @Test
    fun `the popup posts one form and carries where it came from`() {
        val html = render { taskFormModal(TaskForm(), BACK_PROJECT, project) }
        assertTrue(html.contains("action=\"/app/assigned-tasks/save\""))
        assertTrue(html.contains("name=\"taskId\""))
        assertTrue(html.contains("id=\"taskModal\""))
        assertTrue(html.contains("/static/js/task-modal.js"))
        // Hidden, until something opens it.
        assertFalse(html.contains("style=\"display:block\""))
        // Every field the card stores, under the names the service reads.
        listOf("title", "project", "assignee", "started", "due", "priority", "status").forEach {
            assertTrue(html.contains("name=\"$it\""), "missing field $it")
        }
        // On a project page the project cannot be blanked, so the task never leaves the board.
        assertFalse(html.contains("— No project —"))
    }

    @Test
    fun `a rejected popup comes back open, with the message under its own field`() {
        val draft = TaskDraft(
            title = "", projectName = "Haul road widening", assigneeEmpNo = "EMP0002",
            projectLane = DEFAULT_PROJECT_BUCKETS.first(),
        )
        val rejected = TaskService.create(draft, "EMP0001", canManage = true)
        val html = render { taskFormModal(taskFormOf(taskId = 0, rejected), BACK_ASSIGNED) }
        assertTrue(html.contains("style=\"display:block\""))
        // The rendered markup has no line breaks, so "under the title field" is checked as
        // order: the sentence sits between the title input and the next field's control.
        val message = html.indexOf("Give the task a title.")
        assertTrue(message > html.indexOf("name=\"title\""), "message printed before the title input")
        assertTrue(message < html.indexOf("name=\"project\""), "message printed past the next field")
        assertEquals(1, occurrences(html, "field-err"))
        // The value that was not at fault comes back as it was submitted.
        assertTrue(html.contains("value=\"Haul road widening\""))
        // And Stage 48 took the blank choice out of the picker on this board too: a card that names no project
        // belongs to neither world, so the popup lists projects and the service says so under the field.
        assertFalse(html.contains("— No project —"), "the picker still offers a card on no project")
        assertTrue(html.contains("name=\"lane\""), "the general board's popup cannot file a card in a lane")
    }

    @Test
    fun `the new-task opener asks its own page for a blank popup`() {
        val onProject = render { newTaskButton(project) }
        val onBoard = render { newTaskButton() }
        listOf(onProject to "/app/projects/${project.id}", onBoard to "/app/assigned-tasks").forEach {
            (html, page) ->
            assertTrue(html.contains("btn-sm"), "the opener is not the small page button")
            assertTrue(html.contains("+ New task"))
            assertTrue(html.contains("action=\"$page\""))
            assertTrue(html.contains("method=\"get\""), "the opener would navigate instead of swapping")
            assertTrue(html.contains("name=\"new\" value=\"1\""), "the page was not asked to open a popup")
            assertTrue(html.contains("data-live-target=\"#taskModal\""))
            // The card's fields no longer travel as data attributes: `?new=1` comes back from the
            // server with this page's project already picked, which is the only default it needs.
            assertFalse(html.contains("data-task-modal-open"))
            assertFalse(html.contains("data-project="))
        }
    }

    @Test
    fun `a card action returns to the page it came from, popup up when one asked for it`() {
        assertEquals("/app/projects/7", taskRedirect(BACK_PROJECT, 7))
        assertEquals("/app/assigned-tasks", taskRedirect(BACK_ASSIGNED, 7))
        assertEquals("/app/assigned-tasks", taskRedirect(BACK_PROJECT, 0))
        // A step added or a note written from inside the popup is a change to that card, so the way
        // back names the card and the form comes back open on it. A save closes it: no id, no query.
        assertEquals("/app/projects/7?edit=3", taskRedirect(BACK_PROJECT, 7, 3))
        assertEquals("/app/assigned-tasks?edit=3", taskRedirect(BACK_ASSIGNED, 7, 3))
        assertEquals("/app/assigned-tasks", taskRedirect(BACK_ASSIGNED, 0, 0))
    }

    // --- the editor popup: the card as the row holds it, its plan and its log -----------------

    /**
     * The editor for [setUp]'s card, read back fresh so whatever was just written to it is in the markup.
     * [viewer] is who the page thinks is standing in front of it, because the plan's tick boxes are drawn
     * for that person alone — management or the one the card belongs to.
     */
    private fun edit(
        canManage: Boolean,
        viewer: String = "EMP0001",
        back: String = BACK_ASSIGNED,
        fixed: mn.murray.pts.domain.Project? = null,
        showAllBuckets: Boolean = false,
    ): String = render {
        taskFormModal(
            editFormOf(card(), canEditAll = canManage, viewerEmpNo = viewer),
            back,
            fixed,
            showAllBuckets,
        )
    }

    /** [edit] as the person the card is assigned to sees it: not management, but the one who works it. */
    private fun editAsOwner(showAllBuckets: Boolean = false): String =
        edit(canManage = false, viewer = "EMP0002", showAllBuckets = showAllBuckets)

    @Test
    fun `management's editor arrives filled, with the plan and the log under the fields`() {
        val task = card()
        TaskService.addChecklistItem(task.id, "Set out the haul road", "EMP0001", true)
        TaskService.addNote(task.id, "Layer is wet, holding.", "EMP0002", false)
        val html = edit(canManage = true)
        // Open, and carrying the row rather than defaults: this is what `?edit=<card>` answers with.
        assertTrue(html.contains("style=\"display:block\""))
        assertTrue(html.contains("Edit task T-"), "the popup is still the create form")
        assertTrue(html.contains("value=\"Spread base course\""))
        assertTrue(html.contains("value=\"2026-11-01\""))
        assertTrue(html.contains("value=\"2026-11-12\""))
        assertTrue(selectedOption(html, "EMP0002"), "the assignee on the card is not the one picked")
        // All seven fields are management's, so nothing is greyed and no scope line is needed.
        assertFalse(html.contains("disabled"), "management was shown a read-only field")
        assertFalse(html.contains("modal-scope"))
        // Two live regions, each redrawn by the forms inside it, each saying which card to come back to.
        assertTrue(html.contains("id=\"task-steps-${task.id}\""))
        assertTrue(html.contains("id=\"task-notes-${task.id}\""))
        assertTrue(html.contains("data-live-target=\"#task-steps-${task.id}\""))
        assertTrue(html.contains("data-live-target=\"#task-notes-${task.id}\""))
        assertTrue(html.contains("name=\"edit\" value=\"${task.id}\""), "a write would close the popup")
        assertEquals(1, occurrences(html, "action=\"/app/tasks/checklist/add\""))
        assertEquals(1, occurrences(html, "action=\"/app/tasks/checklist/remove\""))
        assertEquals(1, occurrences(html, "action=\"/app/tasks/notes/add\""))
        assertEquals(1, occurrences(html, "action=\"/app/tasks/notes/remove\""))
        // The card's own words print inside the popup, which is where they are read and written.
        assertTrue(html.contains("Set out the haul road"))
        assertTrue(html.contains("Layer is wet, holding."))
    }

    @Test
    fun `an owner's editor greys the four fields management owns and keeps theirs open`() {
        val task = card()
        val step = TaskService.addChecklistItem(task.id, "Set out the haul road", "EMP0001", true)!!
        TaskService.addNote(task.id, "Layer is wet, holding.", "EMP0002", false)
        val html = editAsOwner()
        // The line says it before the greying has to be guessed at.
        assertTrue(html.contains("modal-scope"))
        assertTrue(html.contains("The dates and the priority are yours to set."))
        listOf("title", "project", "assignee", "status").forEach { field ->
            val tag = control(html, field)
            assertTrue(tag.contains("disabled"), "$field is open to the person who does not own it")
        }
        listOf("started", "due", "priority").forEach { field ->
            val tag = control(html, field)
            assertFalse(tag.contains("disabled"), "$field was taken from the person who sets it")
        }
        // The plan the card no longer prints is here instead, and here alone: this popup is the only place
        // its owner can tick it, so taking the section out would take the tick out of the app. Writing the
        // plan is still management's, so the add line and the × stay out of this copy.
        assertTrue(html.contains("id=\"task-steps-${task.id}\""), "the owner's editor carries no plan")
        assertTrue(html.contains("id=\"chk-$step\""), "the plan printed no box")
        assertTrue(html.contains("action=\"/app/tasks/checklist/toggle\""))
        assertFalse(html.contains("checklist/add"), "the owner was offered to write a step")
        assertFalse(html.contains("checklist/remove"), "the owner was offered to delete a step")
        // The log is open to the person doing the work: the box is here, the × is not.
        assertTrue(html.contains("id=\"task-notes-${task.id}\""))
        assertTrue(html.contains("action=\"/app/tasks/notes/add\""))
        assertFalse(html.contains("notes/remove"), "an owner was offered to delete a log entry")
        assertTrue(html.contains("Layer is wet, holding."), "the owner cannot read the log they write")
    }

    /** The opening tag of the control a named field is posted by, so attributes can be read off it. */
    private fun control(html: String, name: String): String =
        Regex("<(?:input|select|textarea)[^>]*name=\"$name\"[^>]*>").find(html)?.value
            ?: error("no $name control rendered")

    /** True when an `<option>` carries [value] and is the one rendered as picked. */
    private fun selectedOption(html: String, value: String): Boolean =
        Regex("<option[^>]*value=\"${Regex.escape(value)}\"[^>]*selected").containsMatchIn(html)

    @Test
    fun `a greyed picker lists the card, not the whole roster`() {
        // A colleague nobody in these tests is assigned to, so their name appearing anywhere below is
        // the picker showing itself rather than the card.
        TeamRepository.upsert(Member("EMP0003", "Naran", "Survey", "Surveyor"))
        val placeholder = render { taskFormModal(TaskForm(canEditAll = false), BACK_PROJECT, project) }
        val management = render { taskFormModal(TaskForm(), BACK_PROJECT, project) }
        assertTrue(management.contains("Naran"), "management's picker lost the roster")
        // Every page carries a hidden popup so `?edit=` has a node to swap into. That placeholder is
        // drawn for the reader standing here: a landing point, not a directory of the whole team and
        // every project for somebody who may change neither.
        assertFalse(placeholder.contains("Naran"), "a greyed field shipped the roster")
        assertFalse(placeholder.contains("style=\"display:block\""), "the placeholder came up open")
        // What it does carry is the card's own answer, printed once and greyed: a picker with one
        // option has nothing to open, and no department grouping to read the roster off.
        assertEquals(0, occurrences(placeholder, "<optgroup"), "a greyed picker still grouped the roster")
        assertTrue(selectedOption(placeholder, project.name))
        assertTrue(placeholder.contains("Nobody yet"), "an empty card invented a holder")
        assertTrue(control(placeholder, "assignee").contains("disabled"))
    }

    @Test
    fun `a create popup carries neither section and no card to hang them on`() {
        val html = render { taskFormModal(newFormOf(), BACK_ASSIGNED) }
        assertTrue(html.contains("style=\"display:block\""), "?new=1 came back closed")
        assertTrue(html.contains(">New task<"))
        assertFalse(html.contains("modal-checklist"))
        assertFalse(html.contains("modal-notes"))
        assertFalse(html.contains("modal-scope"), "a blank form is management's own")
        assertTrue(html.contains("name=\"taskId\" value=\"0\""))
        // Stage 48 took the blank choice out of both pickers: a card that names no project belongs to neither
        // world, and one that names no lane of it is nowhere on that board. So the general board's create lists
        // the projects and opens on the first real one, with the six sub-categories beside it.
        assertFalse(html.contains("— No project —"), "the general board can still create a card on no project")
        assertTrue(html.contains("value=\"${project.name}\""), "this project is off the general board's picker")
        assertTrue(selectedOption(html, DEFAULT_PROJECT_BUCKETS.first()), "a create opened on no lane")
        val onProject = render { taskFormModal(newFormOf(), BACK_PROJECT, project) }
        assertFalse(onProject.contains("— No project —"))
        assertTrue(selectedOption(onProject, project.name), "a new card was not opened on this project")
    }

    @Test
    fun `a step in the popup is the tick box itself, and the whole row sends it`() {
        val task = card()
        val first = TaskService.addChecklistItem(task.id, "Set out the haul road", "EMP0001", true)!!
        TaskService.addChecklistItem(task.id, "Compact the sub-base", "EMP0001", true)
        assertTrue(TaskService.toggleChecklistItem(first, "EMP0001", true))
        val html = edit(canManage = true)
        assertTrue(html.contains("1 of 2 done"))
        // The box replaced the ✓/· mark this section used to print, because it is the only way left to work
        // a plan now that the card carries none: the input posts the toggle, and the row's `<label for>`
        // means clicking the words works as well as clicking the square.
        assertTrue(html.contains("class=\"chk-label done\""), "a done step is not marked as done")
        assertTrue(html.contains("for=\"chk-$first\""), "a step's words are not a way to tick it")
        assertTrue(html.contains("data-autosubmit"), "ticking would need a button press")
        assertEquals(2, occurrences(html, "action=\"/app/tasks/checklist/toggle\""))
        assertFalse(html.contains(">·<"), "the read-only mark is still being drawn")
        assertFalse(html.contains("step-mark"), "the read-only mark is still being styled")
        // An add is one line and one row, and nothing here rewrites a step in place.
        assertTrue(html.contains("id=\"stepAdd-${task.id}\""))
        assertTrue(html.contains("name=\"label\""), "the step line has no field")
        // And that line is the whole control: no button, because Enter on the form's one writable field
        // submits it, `app.js` runs its live-target swap on that very send, and a second way to post the
        // same step would only be a second thing to keep in sync.
        val add = html.substringAfter("action=\"/app/tasks/checklist/add\"").substringBefore("</form>")
        assertFalse(add.contains("<button"), "the line that adds on Enter grew a button")
        assertEquals(1, occurrences(add, "type=\"text\""), "Enter would have two fields to choose from")
    }

    @Test
    fun `a tick posts the card, the step, the page and the popup it came from`() {
        val task = card()
        val (itemId) = steps("Set out the haul road")
        val html = edit(canManage = true, back = BACK_PROJECT, fixed = project)
        listOf("taskId", "itemId", "back", "projectId", "edit").forEach {
            assertTrue(html.contains("name=\"$it\""), "the tick form is missing $it")
        }
        assertTrue(html.contains("value=\"${task.id}\""))
        assertTrue(html.contains("value=\"$itemId\""))
        assertTrue(html.contains("value=\"${project.id}\""), "a project card lost where it came from")
        // Nothing says which way the box is going: the route flips what the row already says.
        assertFalse(html.contains("name=\"done\" value="))
        // And the swap it asks for is the popup's own list, with the card behind it and the project's bar
        // riding along — the three numbers a tick moves.
        assertTrue(html.contains("data-live-target=\"#task-steps-${task.id}\""))
        assertEquals(
            3,
            occurrences(html, "data-live-extra=\"#project-progress,#task-body-${task.id}\""),
            "toggle, remove and add do not all name the blocks a step write moves",
        )
    }

    @Test
    fun `a step written from the wide backlog board leaves the board wide`() {
        steps("Set out the haul road")
        val wide = edit(canManage = true, showAllBuckets = true)
        // Five forms answer a step or a note write with the whole page, and the page is only wide if the
        // write says so: one of them dropping the flag would quietly narrow the board the reader was on.
        assertEquals(
            5,
            occurrences(wide, "name=\"allbuckets\""),
            "a write from the wide board did not carry the view back",
        )
        assertFalse(edit(canManage = true).contains("name=\"allbuckets\""), "the narrow board asked for all")
    }

    @Test
    fun `a card with nothing on it opens both sections so there is somewhere to write`() {
        val task = card()
        val html = edit(canManage = true)
        assertEquals(2, occurrences(html, "section-empty"))
        assertTrue(html.contains("No steps on this card yet."))
        assertTrue(html.contains("Nothing has been written on this card yet."))
        assertTrue(html.contains("0 entries"))
        // The boxes are still there, which is the point: an empty list is written from the popup.
        assertTrue(html.contains("id=\"stepAdd-${task.id}\""))
        assertTrue(html.contains("id=\"noteAdd-${task.id}\""))
    }

    @Test
    fun `the log reads newest first, signed with a name and a day`() {
        val task = card()
        TaskService.addNote(task.id, "First in the book", "EMP0002", false)
        TaskService.addNote(task.id, "On top of it", "EMP0001", true)
        val html = edit(canManage = true)
        assertTrue(html.contains("2 entries"))
        assertTrue(html.indexOf("On top of it") < html.indexOf("First in the book"), "the log reads oldest first")
        // An entry is signed by a person, not a number: the roster resolves the employee number, which
        // is the whole reason it is stored that way.
        assertTrue(html.contains("Bat-Erdene"), "an entry was signed with its employee number")
        assertTrue(html.contains("Munkhtsetseg"))
    }

    @Test
    fun `the note box posts one body, its card, and where to come back to`() {
        val task = card()
        val html = edit(canManage = true, back = BACK_PROJECT, fixed = project)
        listOf("taskId", "back", "projectId", "edit").forEach {
            assertTrue(html.contains("name=\"$it\""), "the note form is missing $it")
        }
        assertTrue(html.contains("name=\"body\""))
        assertTrue(html.contains("<textarea"), "a note would post as a one-line input")
        assertTrue(html.contains("maxlength=\"${TaskService.NOTE_MAX}\""), "the box and the rule disagree")
        // A note written from a project page comes back to that page, so the reader finds the note on the
        // card instead of being dropped on the general board.
        assertTrue(html.contains("value=\"${project.id}\""), "a project note lost where it came from")
    }

    // --- what a card prints, now that its plan lives in the popup ---------------------------

    /** The card [setUp] creates, which every step test hangs its steps off. */
    private fun card(): TaskItem = TaskService.assigned().single()

    /**
     * The popup's plan section exactly as it stands — the block a step write swaps in place, from its own id
     * to the log underneath it. Everything a tick is supposed to move lives inside this slice.
     */
    private fun planBlock(html: String): String =
        html.substringAfter("id=\"task-steps-${card().id}\"")
            .substringBefore("id=\"task-notes-${card().id}\"")

    private fun steps(vararg labels: String): List<Int> =
        labels.map {
            TaskService.addChecklistItem(card().id, it, "EMP0001", canManage = true)!!
        }

    @Test
    fun `a card prints no plan and no boxes, the person it belongs to included`() {
        val task = card()
        steps("Set out the haul road", "Compact the sub-base")
        val mine = render { taskKanban(TaskService.assigned(), engineer, canManage = false, BACK_ASSIGNED) }
        val theirs = render { taskKanban(TaskService.assigned(), manager, canManage = true, BACK_ASSIGNED) }
        // The card keeps one number and nothing else. Every piece of the list that used to sit under it is
        // gone from the board outright, not merely hidden: no heading, no count, no row, and no form left
        // on the page for a hand-built click to find.
        listOf(
            "task-checklist", ">Checklist<", "chk-row", "chk-box", "0 of 2 done",
            "name=\"done\"", "data-autosubmit",
            "/app/tasks/checklist/toggle", "/app/tasks/checklist/add", "/app/tasks/checklist/remove",
        ).forEach {
            assertFalse(mine.contains(it), "the owner's board still prints $it")
            assertFalse(theirs.contains(it), "management's board still prints $it")
        }
        // The words of the plan are not on the board either — a card is a title, a date, and a number.
        assertFalse(mine.contains("Set out the haul road"), "a card printed the words of its plan")
        // What survives is the block the popup's tick repaints, and it still stops short of the `.kcard`
        // node itself, which is what kanban.js binds its drag handle to.
        assertTrue(mine.contains("id=\"task-body-${task.id}\""), "the card lost the block a tick repaints")
    }

    @Test
    fun `a card is one draggable click target, with nothing inside it left to exempt`() {
        steps("Set out the haul road")
        val html = render { taskKanban(TaskService.assigned(), engineer, canManage = false, BACK_ASSIGNED) }
        assertTrue(html.contains("draggable=\"true\""), "the card stopped being draggable")
        // A plan marked itself and its rows non-draggable, because a control inside a dragged element is not
        // reliably clickable. With no controls left inside the card there is nothing to exempt, and a
        // leftover `draggable="false"` would be a layer that stopped the drag without meaning to.
        assertEquals(0, occurrences(html, "draggable=\"false\""), "a layer inside the card is still exempted")
    }

    @Test
    fun `the boxes follow the right to tick, not the page the card is on`() {
        steps("Set out the haul road")
        val unowned = card().copy(assignee = "")
        // `toggleChecklistItem` answers to management and to the card's own assignee, and to a card that
        // belongs to nobody it is management alone. `editableBy` hands the card to that same pair, so the
        // popup a reader can open is always one whose boxes they may use.
        val management = render {
            taskFormModal(editFormOf(unowned, canEditAll = true, viewerEmpNo = "EMP0001"), BACK_ASSIGNED)
        }
        assertTrue(management.contains("action=\"/app/tasks/checklist/toggle\""), "management cannot work a card")
        val nobody = render {
            taskFormModal(editFormOf(unowned, canEditAll = false, viewerEmpNo = "EMP0002"), BACK_ASSIGNED)
        }
        assertFalse(nobody.contains("task-steps-"), "an unassigned card was offered to a stranger as theirs")
        assertFalse(nobody.contains("name=\"done\""), "a reader the route would refuse was handed a box")
    }

    // --- the one number a card keeps --------------------------------------------------------

    private fun board(canManage: Boolean = false) = render {
        taskKanban(TaskService.assigned(), if (canManage) manager else engineer, canManage, BACK_ASSIGNED)
    }

    @Test
    fun `a ticked step comes back ticked and struck through`() {
        val ids = steps("Set out the haul road", "Survey the start line")
        assertTrue(TaskService.toggleChecklistItem(ids.first(), "EMP0002", false))
        val html = editAsOwner()
        assertTrue(html.contains("1 of 2 done"))
        assertTrue(html.contains("class=\"chk-label done\""), "a done step is not marked as done")
        // The box itself carries the state, so the popup and the stored row agree — and the step nobody
        // ticked keeps an empty box, which is what tells the two of them apart.
        val ticked = Regex("<input[^>]*id=\"chk-${ids.first()}\"[^>]*>").find(html)?.value
        val open = Regex("<input[^>]*id=\"chk-${ids[1]}\"[^>]*>").find(html)?.value
        assertTrue(ticked != null && "checked" in ticked, "the tick box did not render as checked")
        assertTrue(open != null && "checked" !in open, "an unticked step rendered ticked")
    }

    @Test
    fun `a card prints the percentage its own steps add up to`() {
        val ids = steps("Set out the haul road", "Compact the sub-base", "Book the crew", "Lay the kerb")
        // Four steps and none ticked is an honest 0% — somebody wrote the plan, and nothing on it is done.
        assertTrue(board().contains("class=\"kpct\">0%</span>"), "an untouched plan printed no percentage")
        assertTrue(TaskService.toggleChecklistItem(ids.first(), "EMP0002", false))
        val one = board()
        assertTrue(one.contains("class=\"kpct\">25%</span>"), "one tick in four did not read as 25%")
        // This number is the card's only remaining word about its plan, so it has to sit inside
        // `#task-body-<id>` — that block is the one a tick in the popup repaints, and a number printed
        // outside it would be left holding the value from before the click.
        val body = one.substringAfter("id=\"task-body-${card().id}\"")
        assertTrue("kpct" in body, "the percentage left the block a tick swaps")
        assertTrue(body.indexOf("kpct") < body.indexOf("kmove"), "the percentage is not on the card's own line")
    }

    @Test
    fun `a card nobody wrote steps for prints no percentage at all`() {
        assertFalse(board().contains("kpct"), "an unplanned card claimed a percentage")
    }

    @Test
    fun `management reads a colleague's percentage with no boxes to tick`() {
        val ids = steps("Set out the haul road", "Compact the sub-base")
        assertTrue(TaskService.toggleChecklistItem(ids.first(), "EMP0001", true))
        val html = board(canManage = true)
        assertTrue(html.contains("class=\"kpct\">50%</span>"), "the board management runs from showed no progress")
        assertFalse(html.contains("chk-"), "management was handed tick boxes out on the board")
    }

    @Test
    fun `the popup prints the same percentage the card does`() {
        val ids = steps("Set out the haul road", "Compact the sub-base", "Book the crew", "Lay the kerb")
        assertTrue(TaskService.toggleChecklistItem(ids.first(), "EMP0002", false))
        val html = edit(canManage = true)
        assertTrue(html.contains("1 of 4 done · 25%"), "the popup's plan line carried no measurement")
    }

    @Test
    fun `the popup's bar is filled to the figure the card prints`() {
        val ids = steps("Set out the haul road", "Compact the sub-base", "Book the crew", "Lay the kerb")
        assertTrue(TaskService.toggleChecklistItem(ids.first(), "EMP0002", false))
        val plan = planBlock(edit(canManage = true))
        // One tick in four, drawn with the one progress bar the app has — `.progress` holding a `.bar`, the
        // same pair a Projects row and a project page's Progress cell use, at the same size.
        assertTrue(plan.contains("class=\"progress\""), "the popup opened with no bar on it")
        assertTrue(plan.contains("style=\"width:25%\""), "the bar did not follow the card's own figure")
        // One bar, and above the rows it measures, so a reader takes the picture and the list in one look.
        assertEquals(1, occurrences(plan, "class=\"progress\""), "the popup grew a second bar")
        assertTrue(plan.indexOf("kprog") < plan.indexOf("chk-row"), "the bar sits under the steps it measures")
        // And the card out on the board still says the same number the bar is filled to.
        assertTrue(board(canManage = true).contains("class=\"kpct\">25%</span>"), "card and bar disagree")
    }

    @Test
    fun `two steps with one ticked fill the popup's bar to half, inside the block the tick swaps`() {
        val ids = steps("Set out the haul road", "Compact the sub-base")
        // Two steps and neither ticked is still a plan, so there is a bar to fill — an empty one.
        assertTrue(planBlock(editAsOwner()).contains("style=\"width:0%\""), "an untouched plan opened barless")
        assertTrue(TaskService.toggleChecklistItem(ids.first(), "EMP0002", false))
        assertTrue(planBlock(editAsOwner()).contains("style=\"width:50%\""), "one tick in two read as anything but half")
        // The forms that move it all name this region as their target, so the fill comes back in the very
        // same response as the tick: the bar cannot be left holding the number from before the click.
        assertTrue(
            planBlock(editAsOwner()).contains("data-live-target=\"#task-steps-${card().id}\""),
            "a tick would have had to repaint somewhere else to move this bar",
        )
    }

    @Test
    fun `a popup on a card with no plan counts nothing and claims nothing`() {
        val html = edit(canManage = true)
        assertTrue(html.contains("0 of 0 done"))
        assertFalse(html.contains(" done ·"), "an empty plan printed a percentage")
        // No steps means nothing to add up, and an empty bar would read as a plan sitting at zero. The card's
        // chip obeys the same rule; the bar has to agree with the number it pictures.
        assertFalse(html.contains("class=\"progress\""), "an unplanned card claimed a bar")
    }

    // --- a project's own lanes (V7) ------------------------------------------------------------------

    /** This project's lanes, read the way the page reads them. */
    private fun lanes(): List<ProjectBucket> = TaskService.lanesOf(project.id)

    /**
     * Takes the harness's card back out of its lane, which is the one state Stage 48 stops a **new** card from
     * reaching and the state every project card written before it is still in. Nothing in the service will do
     * this any more — [mn.murray.pts.service.TaskService.moveToLane] refuses lane 0 — so the column itself is
     * emptied straight off the row.
     */
    private fun unfiled(): TaskItem {
        TaskRepository.setProjectBucket(card().id, null)
        return card()
    }

    /**
     * The project page's board, with the same arguments [mn.murray.pts.projectDetailContent] passes it — which
     * is why [canMoveCards] defaults to [canAdd] rather than to anything of its own (Stage 49): the page hands
     * the same flag to both, because writing a card onto a project and filing one of its cards in another lane
     * of it are one rule, [mn.murray.pts.service.TaskService.mayAddTasks].
     */
    private fun laneBoard(
        tasks: List<TaskItem> = listOf(card()),
        reader: UserSession = engineer,
        canManage: Boolean = false,
        canAdd: Boolean = false,
        canMoveCards: Boolean = canAdd,
    ): String = render {
        taskKanban(
            tasks, reader, canManage, BACK_PROJECT, project.id,
            canAdd = canAdd,
            fixedProject = project,
            lanes = lanes(),
            canMoveCards = canMoveCards,
        )
    }

    /** The `<form>` tags this board prints for [classes], one string each — attributes stay on one line. */
    private fun formTags(html: String, classes: String): List<String> =
        Regex("<form[^>]*class=\"${Regex.escape(classes)}\"[^>]*>").findAll(html).map { it.value }.toList()

    @Test
    fun `a project board is cut by its lanes and never by status`() {
        val fabrication = lanes().first()
        val html = laneBoard()
        // Every lane is a column, in the order the board was given them, and the card sits in the one it
        // was filed in. `data-lane` is what a drop posts; a status column would have said `data-status`.
        // Since Stage 48 the lanes are the same six on every project — see [DEFAULT_PROJECT_BUCKETS].
        assertEquals(DEFAULT_PROJECT_BUCKETS, lanes().map { it.name })
        lanes().forEach { assertTrue(html.contains("<span class=\"kcol-title\">${it.name}</span>"), "${it.name} is not a column") }
        assertTrue(html.contains("data-lane=\"${fabrication.id}\""), "the lanes carry no id for a drop")
        // No *column* is cut by status here — `data-lane` is what a drop posts. A card's own Done button
        // still writes a status, and that is the only one on the page.
        assertFalse(html.contains("class=\"kcol\" data-status"), "a status column is on this board")
        // The card was filed in the first of the six, so nothing here is waiting for a lane — and a board with
        // nothing to put in **Unsorted** prints no Unsorted column (Stage 37): the columns are the project's
        // lanes, the way the board was drawn to look.
        assertFalse(html.contains("data-lane=\"0\""), "an empty Unsorted column is printed as a seventh lane")
        TASK_STATUSES.forEach {
            assertFalse(html.contains("<span class=\"kcol-title\">$it</span>"), "$it is a column here")
        }
        // The card's own line prints no status word either — the column is the only thing that used to say it.
        assertTrue(html.contains("Spread base course"))
    }

    @Test
    fun `the drag is the mover's and the click is the owner's`() {
        // Stage 49's whole difference on this page, and it is a one-attribute difference: `draggable` is all
        // kanban.js looks for in a drag source, so a card this reader may not move simply does not start a drag.
        // Three readers stand on the same board with the same card, which belongs to the engineer:
        val asOwner = laneBoard()
        val asManagement = laneBoard(reader = manager, canManage = true, canAdd = true)
        // …and the account this project names as its manager, who is not management: the page hands that reader
        // `canAdd`, and since Stage 49 the same flag for the drag, because the two rights are one rule.
        val asProjectManager = laneBoard(reader = manager, canManage = false, canAdd = true)
        assertFalse(asOwner.contains("draggable"), "the card's owner was still allowed to move it between lanes")
        listOf(asManagement, asProjectManager).forEach {
            assertTrue(it.contains("draggable=\"true\""), "a mover's card cannot be dragged")
        }
        // What the owner keeps is everything that describes their own day on the card: it opens for them, and it
        // still carries the Done button, because a status is not a place on somebody's board.
        assertTrue(asOwner.contains("data-task-card=\"${card().id}\""), "the owner lost the click")
        assertTrue(asOwner.contains("kmove-btn"), "the owner lost the button that finishes their own card")
        // And the two are independent, not one flag with two names: a mover who has no work on this card can
        // drag it and still cannot open it, and an owner who may not drag it still opens it.
        val asMoverWithoutWork = laneBoard(reader = UserSession("EMP9999"), canManage = false, canAdd = true)
        assertTrue(asMoverWithoutWork.contains("draggable=\"true\""), "a mover could not drag somebody else's card")
        assertFalse(asMoverWithoutWork.contains("data-task-card"), "a stranger's card was made clickable")
        assertFalse(asMoverWithoutWork.contains("kmove-btn"), "a mover was handed the owner's buttons")
    }

    @Test
    fun `a card nobody has filed is shown rather than hidden`() {
        // Nothing chooses a lane for a card that has none: one whose lane was taken away, or one written before
        // Stage 48 made filing compulsory. It appears under Unsorted until somebody says where it belongs — the
        // one thing that keeps that column on a board, and why removing it from the empty board (Stage 37)
        // hides no card.
        unfiled()
        val html = laneBoard()
        assertTrue(html.contains("data-lane=\"0\""), "an unfiled card was filed out of sight")
        assertTrue(html.contains("<span class=\"kcol-title\">$UNSORTED_LANES</span>"))
        assertTrue(html.contains("Spread base course"))
    }

    @Test
    fun `the board prints Unsorted only while it has a card in it`() {
        // The column the user asked to have gone off the board. It is gone from the board that shows the six
        // lanes, and comes back for the card that has no lane — and what makes that safe is in app.css, not
        // here: `.kanban-lanes` is a run of fixed 288px tracks, so an extra column arriving or leaving takes no
        // width off the others. Under the `auto-fit` `1fr` tracks it replaced, filing the one unfiled card took
        // a column off the board and every card on it went from 389px wide to 523px.
        unfiled()
        val unfiledBoard = laneBoard(canAdd = true)
        // Filed by the project's manager (Stage 49): the drag this test used to stand in for is no longer the
        // assignee's to make, and the assertion below is about the column appearing and going, not the caller.
        assertTrue(TaskService.moveToLane(card().id, lanes().first().id, "EMP0001", canManage = false))
        val filed = laneBoard(canAdd = true)

        assertEquals(lanes().size + 1, occurrences(unfiledBoard, "kcol-head"), "an unfiled board is short a column")
        assertEquals(1, occurrences(unfiledBoard, "data-lane=\"0\""), "the unfiled card has nowhere to be shown")
        assertTrue(
            Regex("<span class=\"kcol-title\">$UNSORTED_LANES</span>\\s*<span class=\"kcol-count\">1</span>")
                .containsMatchIn(unfiledBoard),
            "Unsorted does not count the card it is there for",
        )
        assertEquals(lanes().size, occurrences(filed, "kcol-head"), "a filed board still prints Unsorted")
        assertFalse(filed.contains("data-lane=\"0\""), "Unsorted stayed for a board with nothing unfiled")
        assertFalse(filed.contains(">$UNSORTED_LANES<"), "the word went off the board with the column")
        // The lanes' own lines are the same on both boards: only the columns between them moved.
        assertEquals(
            occurrences(unfiledBoard, "class=\"kcol-add\""), occurrences(filed, "class=\"kcol-add\""),
            "the lanes' add lines moved with the count",
        )
    }

    // --- nobody controls this project's lanes from the page (Stage 35) -----------------------------------

    @Test
    fun `no reader is offered a control over the lanes`() {
        // The board an assignee stands on, the board management stands on, and the board of a reader who is
        // offered the per-lane `+ Add task` lines: none of the three prints a way to name a lane or take one
        // off. `POST /app/projects/{id}/buckets` and its `delete` twin are still wired and still check the role
        // — [mn.murray.pts.service.TaskService.addLane] and `removeLane` keep their rules, tested in
        // TaskServiceTest — but nothing on this page reaches them, so a project's columns are the six it was
        // created with and no refusal code has a sentence printed from it.
        listOf(
            "an assignee" to laneBoard(),
            "management" to laneBoard(reader = manager, canManage = true),
            "a reader who may write cards" to laneBoard(reader = manager, canManage = true, canAdd = true),
        ).forEach { (who, html) ->
            assertFalse(html.contains("/buckets"), "$who was pointed at a lane route")
            assertFalse(html.contains("lane-del"), "$who was given a lane ×")
            assertFalse(html.contains("laneName"), "$who was given a box to name a lane")
            assertFalse(html.contains("New lane"), "$who was told a lane could be made")
            assertFalse(html.contains("laneArea"), "$who was given the lane swap region")
            assertFalse(html.contains("Remove the "), "$who was offered to remove a lane")
            assertFalse(html.contains("field-err"), "$who was shown a refusal nobody asked them for")
        }
    }

    @Test
    fun `a lane head is its name and its count`() {
        unfiled()
        val html = laneBoard(canAdd = true)
        // The whole of a column's chrome: one span of name, one span of number, and nothing else in between.
        // A control cannot hide inside a head drawn to this shape.
        val heads = Regex(
            "<div class=\"kcol-head\">\\s*<span class=\"kcol-title\">[^<]*</span>" +
                "\\s*<span class=\"kcol-count\">\\d+</span>\\s*</div>",
        ).findAll(html).toList()
        assertEquals(lanes().size + 1, heads.size, "a head carries something besides its name and its count")
        assertEquals(lanes().size + 1, occurrences(html, "kcol-head"), "the board is not its six lanes plus Unsorted")
        // Taking the lane controls away took none of the lanes themselves: the columns are still the drop
        // targets `kanban.js` posts to, and they still carry the Stage 34 lines that start a card in one.
        assertEquals(lanes().size + 1, occurrences(html, "data-lane=\""), "a column stopped answering drops")
        assertEquals(lanes().size, occurrences(html, "class=\"kcol-add\""), "the lanes lost their add lines")
    }

    @Test
    fun `the status board has no lane control either`() {
        // The Assigned tasks board's columns are statuses, which were never names a reader could edit. On the
        // project page the lanes now sit in the same position: drawn, fixed, and not controlled from here.
        val html = render {
            taskKanban(TaskService.assigned(), manager, canManage = true, BACK_ASSIGNED, canAdd = true)
        }
        assertFalse(html.contains("lane-del"), "a lane control is on the board that has no lanes")
        assertFalse(html.contains("/buckets"), "the status board offers to delete a column")
        assertFalse(html.contains("laneName"), "the status board offers to name a column")
        assertFalse(html.contains("kcol-add"), "a status column was given a lane's add line")
        assertEquals(5, occurrences(html, "kcol-head"), "the general board is not its five statuses")
    }

    @Test
    fun `an assignee closes a card from a lane board with one button`() {
        // There is no Completed column to drag to here, so the reader who cannot set Status in the popup
        // gets the one move that still matters: done, or not done yet. `kanban.js` posts the same
        // /app/tasks/status it always has, and the route is the same rule.
        val html = laneBoard()
        assertEquals(1, occurrences(html, "kmove-btn"), "the card carries a status walk rather than one button")
        assertTrue(html.contains("✓ Done"))
        assertTrue(Regex("data-status=\"Completed\"").containsMatchIn(html))
        assertFalse(html.contains("← "), "the status arrows came back")
        assertFalse(html.contains("In progress"), "the board printed the status it no longer shows")
    }

    @Test
    fun `a finished card on a lane board offers to reopen itself`() {
        assertTrue(TaskService.updateStatus(card().id, "Completed", "EMP0002", canManage = false))
        val html = laneBoard()
        assertTrue(html.contains("↺ Reopen"))
        assertTrue(Regex("data-status=\"Not started\"").containsMatchIn(html))
        // A card that is done reads as done even with no column saying so; an open one wears no status
        // colour, because the colour would be the word the page is not allowed to print.
        assertTrue(html.contains("class=\"kcard s-completed\""), "a finished card was not marked")
        assertFalse(html.contains("s-inprogress"))
        // The move is the project manager's (Stage 49); the status writes around it stay the assignee's, which
        // is the whole difference the stage drew: this card's owner still finishes it, no longer relocates it.
        val open = TaskService.moveToLane(card().id, lanes().last().id, "EMP0001", canManage = false)
        assertTrue(open)
        assertTrue(TaskService.updateStatus(card().id, "In progress", "EMP0002", canManage = false))
        assertFalse(laneBoard().contains("s-inprogress"), "a lane board tinted a card by its status")
    }

    @Test
    fun `the popup lists the six sub-categories and opens on the one the card is in`() {
        val html = render {
            taskFormModal(editFormOf(card(), canEditAll = true).copy(lanes = lanes()), BACK_PROJECT, project)
        }
        // Posted by **name** since Stage 48: the six are the same six on every project, so a name is the one
        // answer a popup can post without knowing this project's lane ids. (A drop still posts an id — that is
        // `kanban.js` talking to the board it is standing on, where the ids are already in the page.)
        assertTrue(html.contains("name=\"lane\""), "the editor has no lane field")
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(html.contains("value=\"$it\""), "$it is missing from the picker")
        }
        assertTrue(selectedOption(html, card().projectBucket), "the lane the card is in is not the one picked")
        // A filed row opens on its lane and prints no placeholder — there is nothing for the reader to answer.
        assertFalse(html.contains("— $UNSORTED_LANES —"), "the picker offered Unsorted to a filed card")
        // A row that has no lane, which is every project card written before this rule, opens on the placeholder
        // instead, and the service refuses to save past it until somebody files the card.
        unfiled()
        val legacy = render {
            taskFormModal(editFormOf(card(), canEditAll = true).copy(lanes = lanes()), BACK_PROJECT, project)
        }
        assertTrue(Regex("value=\"\"[^>]*selected").containsMatchIn(legacy), "an unfiled card opened on no lane at all")
        assertTrue(legacy.contains("— $UNSORTED_LANES —"), "the placeholder names nothing but itself")
        // The general board's popup lists the same six, because filing a card in a part of its project is a
        // question that page can ask without knowing any lane id.
        val general = edit(canManage = true)
        assertTrue(general.contains("name=\"lane\""), "the general board cannot ask which part of a project")
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(general.contains("value=\"$it\""), "$it is off the general board's picker")
        }
        // And the person the card belongs to reads the lane but does not write it.
        val mine = render {
            taskFormModal(editFormOf(card(), canEditAll = false).copy(lanes = lanes()), BACK_PROJECT, project)
        }
        assertTrue(Regex("name=\"lane\"[^>]*disabled").containsMatchIn(mine), "an assignee was handed the column")
    }

    @Test
    fun `a lane this project added itself is offered after the six, on its own board`() {
        // Stage 48 keeps a project's extra lanes, printed after the shared six — on the board and in the picker
        // alike. An edit that posts a name the project does not hold is refused (TaskServiceTest), so a reader
        // must never be able to pick one from here either.
        val extraId = ProjectBucketRepository.insert(project.id, "Tendering")
        val extra = ProjectBucketRepository.find(extraId)!!
        val html = render {
            taskFormModal(
                editFormOf(card(), canEditAll = true).copy(lanes = lanes() + extra),
                BACK_PROJECT, project,
            )
        }
        assertTrue(html.contains("value=\"Tendering\""), "this project's own lane is off its picker")
        assertTrue(
            html.indexOf("value=\"Tendering\"") > html.indexOf("value=\"${DEFAULT_PROJECT_BUCKETS.last()}\""),
            "the extra lane is not after the shared six",
        )
        // The six are all still there, ahead of it: an extra lane joins the picker, it does not replace it.
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(html.contains("value=\"$it\""), "$it left the picker when the extra lane joined")
        }
    }

    @Test
    fun `a card filed in a lane the general board does not list still reads its own lane`() {
        // The same card, opened from the Assigned tasks page: that popup knows the six names and not this
        // project's extras, and a picker that silently dropped the card's lane would read as a card sitting in
        // the first one. So the row's own name prints after the six, selected — the board draws extras after
        // them the same way, and saving what the popup shows keeps the card where it is.
        val extra = ProjectBucketRepository.insert(project.id, "Tendering")
        assertTrue(TaskService.moveToLane(card().id, extra, "EMP0001", canManage = false))
        val html = edit(canManage = true)
        assertTrue(selectedOption(html, "Tendering"), "a general-board popup hid the card's own lane")
        assertEquals(1, occurrences(html, "value=\"Tendering\""), "the extra lane printed more than once")
        assertTrue(
            html.indexOf("value=\"Tendering\"") > html.indexOf("value=\"${DEFAULT_PROJECT_BUCKETS.last()}\""),
            "the card's own lane was printed before the six",
        )
    }

    @Test
    fun `a new card on a project page lands on its first lane`() {
        val html = render {
            taskFormModal(newFormOf().copy(lanes = lanes()), BACK_PROJECT, project)
        }
        // Unsorted is a column for cards nobody has placed, not for the next one being written now.
        assertTrue(selectedOption(html, lanes().first().name), "a new card was left unfiled by default")
        assertTrue(html.contains("A new card lands under ${lanes().first().name}"))
    }

    // --- `+ Add task` at the foot of each lane (Stage 34) ----------------------------------------------

    @Test
    fun `every lane carries its own add line for a reader who may write here`() {
        unfiled()
        // The card is unfiled, so this board prints an Unsorted column as well as its six lanes.
        val html = laneBoard(canAdd = true)
        assertEquals(lanes().size + 1, occurrences(html, "kcol-head"), "this board is not its six lanes plus Unsorted")
        assertTrue(html.contains("data-lane=\"0\""), "there is no Unsorted column here to check against")
        assertEquals(lanes().size, occurrences(html, "class=\"kcol-add\""), "the board is not one add line per lane")
        assertEquals(lanes().size, occurrences(html, "class=\"kcol-add-btn\""), "a lane's line has no button")
        lanes().forEach { lane ->
            assertTrue(
                html.contains("<input type=\"hidden\" name=\"into\" value=\"${lane.id}\">"),
                "${lane.name} does not carry its own id into the popup",
            )
        }
        // Each line is a create of its own: the hidden `new=1` the page head's opener posts, plus the lane.
        assertEquals(lanes().size, occurrences(html, "<input type=\"hidden\" name=\"new\" value=\"1\">"))
        // It is the page-head opener's own mechanism, so it is a form and not a link: the popup the server
        // renders for it swaps into #taskModal over the board the reader is standing on.
        val adds = formTags(html, "kcol-add")
        assertEquals(lanes().size, adds.size)
        adds.forEach { tag ->
            assertTrue(tag.contains("/app/projects/${project.id}"), "an add line aims at another page: $tag")
            assertTrue(tag.contains("data-live-target=\"#taskModal\""), "an add line would reload the page: $tag")
        }
    }

    @Test
    fun `Unsorted is not offered as a place to start a card`() {
        // Nobody sets out to file a card under Unsorted; it is where unplaced cards wait. The column is
        // drawn here precisely because the card has no lane, and it carries the line for no lane.
        unfiled()
        val html = laneBoard(canAdd = true)
        assertTrue(html.contains(">$UNSORTED_LANES<"), "there is no Unsorted column to check")
        assertFalse(html.contains("name=\"into\" value=\"0\""), "an add line aims at Unsorted")
        assertEquals(lanes().size, occurrences(html, "class=\"kcol-add\""), "Unsorted got a lane's add line")
    }

    @Test
    fun `a reader who may not write a card is offered no add line`() {
        // The same flag the page head's `+ New task` uses, so the two can never disagree about who sees them
        // — and the service refuses a create from this reader on the same rule.
        val html = laneBoard()
        assertFalse(html.contains("kcol-add"), "an add line was printed for somebody whose create would be refused")
        assertFalse(html.contains("name=\"into\""), "a lane was offered as a destination without the right")
        val can = laneBoard(canAdd = true)
        assertTrue(can.contains("kcol-add-btn"), "the reader who may write a card has no lane to write it in")
    }

    @Test
    fun `an add line opens the popup already in that lane`() {
        val other = lanes().last()
        // Built the way `?new=1&into=<lane>` builds it — the route reads the id the line carries and hands the
        // popup the lane's **name**, which is what the picker posts (Stage 48).
        val html = render {
            taskFormModal(newFormOf(into = other.name).copy(lanes = lanes()), BACK_PROJECT, project)
        }
        assertTrue(selectedOption(html, other.name), "the picker did not open on the lane it was called from")
        assertFalse(selectedOption(html, lanes().first().name), "the picker opened on two lanes")
        assertTrue(html.contains("A new card lands under ${other.name}."), "the hint names another column")
        // With no lane aimed at it, the create behaves exactly as it always did.
        val plain = render { taskFormModal(newFormOf().copy(lanes = lanes()), BACK_PROJECT, project) }
        assertTrue(selectedOption(plain, lanes().first().name), "an unaimed create stopped landing on the first lane")
        assertTrue(plain.contains("A new card lands under ${lanes().first().name}."))
    }

    @Test
    fun `the general board is still the five status columns`() {
        // The lane board is a project's own view of its work; the page a reader asks "what is on me today"
        // from cuts every project by the same five statuses it has always had.
        val html = render { taskKanban(listOf(card()), engineer, canManage = false, BACK_ASSIGNED) }
        assertEquals(5, occurrences(html, "kcol-head"), "the Assigned tasks board lost a status column")
        assertTrue(html.contains("data-status=\"Not started\""), "the board no longer names its columns")
        // The needle the project board above denies: here the columns themselves are the statuses.
        assertEquals(5, occurrences(html, "class=\"kcol\" data-status"), "a status column does not answer drops")
        assertFalse(html.contains("data-lane"), "a lane column appeared where there are none")
        assertFalse(html.contains("/buckets"), "the general board was given a project's lane box")
    }
}
