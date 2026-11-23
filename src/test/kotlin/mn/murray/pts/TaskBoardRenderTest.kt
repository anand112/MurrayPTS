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
import mn.murray.pts.domain.bucketDotClass
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.TaskDraft
import mn.murray.pts.service.TaskField
import mn.murray.pts.service.TaskSaveResult
import mn.murray.pts.service.TaskService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * a column is changed by dragging, or by management inside the popup — **Stage 103** took the owner's arrows out,
 * so a status-board card prints no button at all. A card prints
 * one number about its plan and nothing else (Stage 41) — the steps and their tick boxes are in the editor
 * the click opens, offered to exactly the two readers the route answers to. A rejected form comes back with
 * the popup open and its message under its own field. The Assigned page's `+ New task` asks which project the
 * card is on and takes its columns from that project's own board — **Stage 111** — so its two pickers are
 * pinned here in every state they can be rendered in: nothing chosen, a board named, a board with nothing on
 * it, and a refusal that has to come back with the same list the reader was refused against.
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
            code = "S-01", name = "Haul road widening", status = "In progress", progress = 0,
            managerEmpNo = "EMP0001", due = "2027-03-30", type = "Site projects",
        )
        project = ProjectRepository.findById(projectId)!!
        // The three phases (V7, V10's six, V16's three again). Since Stage 100 a create takes its board from the
        // category's Settings list, so this harness — which inserts the project row itself — writes the board by
        // hand: the project page this suite renders is a lane board, exactly as the real one is.
        seedOpeningThree(projectId)
        TaskService.create(
            TaskDraft(
                title = "Spread base course", projectName = project.name,
                assigneeEmpNo = "EMP0002", started = "2026-11-01", due = "2026-11-12",
                priority = "High", status = "Not started",
                // Filing is compulsory (Stage 48) into the three a board opens with (Stage 75), so the card every
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
    fun `a card is clickable for everybody and moves only for its manager and owner`() {
        val tasks = TaskService.assigned()
        val asManager = render { taskKanban(tasks, manager, canManage = true, BACK_ASSIGNED) }
        val asAssignee = render { taskKanban(tasks, engineer, canManage = false, BACK_ASSIGNED) }
        val asStranger = render { taskKanban(tasks, UserSession("EMP9999"), canManage = false, BACK_ASSIGNED) }
        // The card is the trigger and carries only what the opener needs: the id of the card to ask for.
        // **Stage 86** made that true of every card for every reader — the popup behind the click is what
        // narrows to a read-only view ([TaskForm.viewOnly]), not the click itself.
        listOf(asManager, asAssignee, asStranger).forEach {
            assertTrue(it.contains("data-task-card=\"1\""), "a card on the board was not clickable")
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
        // **Stage 103 retired the prev/next arrows**, so no card on a status board carries a standing button for
        // anybody — not management, whose Status field lives in the popup they open by clicking the card, and not
        // its holder, whose way to another column is the drag the card already offers. What the board still owes
        // every reader is the click, and what it still owes the holder is the grab.
        listOf(asManager, asAssignee, asStranger).forEach {
            assertFalse(it.contains("kmove"), "a status-board card still carries a move button")
            assertFalse(it.contains("← "), "a status-board card still points backwards")
        }
        assertTrue(asAssignee.contains("draggable"))
        // Opening is not moving. A reader with no part in the card can click it — and can read it — but gets no
        // drag, and the board still ships the opener their click fills.
        assertTrue(asStranger.contains("taskEditOpener"), "the click had no form to fill")
        assertFalse(asStranger.contains("draggable"))
    }

    @Test
    fun `a card offers no status dropdown anywhere on the board`() {
        val onManagersBoard = render { taskKanban(TaskService.assigned(), manager, canManage = true, BACK_ASSIGNED) }
        val onOwnersBoard = render { taskKanban(TaskService.assigned(), engineer, canManage = false, BACK_ASSIGNED) }
        // The menu's mover went with the menu. `/app/tasks/status` is still the route the drag and the
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
    fun `a project page is cut by its buckets and carries no manager panel`() {
        val html = render { projectDetailContent(manager, project, canEdit = true) }
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
        // Stage 75 put this page back on its **buckets**, always: the columns are the project's lanes, one per
        // bucket it carries, and a card's status is a line on the card changed through its own Done button, not a
        // place on the board. Each lane is a `.kcol[data-lane]` a drag posts to `/app/tasks/bucket`.
        assertEquals(
            lanes().size, occurrences(html, "data-lane="),
            "the project board is not its bucket columns",
        )
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(html.contains("<span class=\"kcol-title\">$it</span>"), "$it is not a column here")
        }
        // …and the five statuses are not. Nothing on this board is a `.kcol[data-status]`, and no status word
        // stands as a column head; the words appear only as a card's own line and inside its popup.
        assertFalse(html.contains("class=\"kcol\" data-status"), "a status column is on the project board")
        // Nothing that predates the board's own controls comes back either (Stage 35 stands): no lane swap region
        // and no `?lane=` opener beside the columns. The per-column `⋯`, the dashed `+ Add bucket` box and the
        // remove form inside the column popup are the page's own now, and ProjectsPageRenderTest pins those.
        assertFalse(html.contains("?lane="), "a lane code opener came back")
        assertFalse(html.contains("laneArea"), "a lane swap region came back")
        // **Stage 79** took the tick-list panel that sat under the header off this page, so the board's own forms
        // are the page's only bucket writers: the save route is printed exactly once, by the `+ Add bucket` half
        // of the column popup, and it swaps the board the reader is standing on — not a panel that no longer
        // exists — alongside the card popup's Sub-category picker and the popup itself.
        assertFalse(html.contains("id=\"projectBuckets\""), "the bucket panel came back")
        assertFalse(html.contains("Save buckets"), "the panel's button came back")
        assertEquals(1, occurrences(html, "/buckets/save"), "a second bucket form is on this page")
        val saveForm = html.substringAfter("<form action=\"/app/projects/${project.id}/buckets/save\"")
            .substringBefore(">")
        assertTrue(
            saveForm.contains("data-live-target=\"#projectBoard\""),
            "adding a bucket redraws something other than the columns",
        )
        assertTrue(
            saveForm.contains("data-live-extra=\"#taskLane,#projectLaneModal\""),
            "a bucket added here leaves the card popup or the popup itself stale",
        )
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
    fun `a project popup offers a second person and a backlog popup does not`() {
        // Stage 122: the picker sits under the first one, on the same form, and starts on nobody.
        val projectForm = render { taskFormModal(TaskForm(), BACK_PROJECT, project) }
        assertTrue(projectForm.contains("name=\"assignee2\""), "the project popup has no second picker")
        assertTrue(projectForm.contains("Also assign to"))
        val second = projectForm.substringAfter("id=\"taskAssignee2\"").substringBefore("</select>")
        assertTrue(second.contains("— Nobody else —"), "the second picker has no way to say nobody")
        assertTrue(
            Regex("value=\"\"[^>]*selected").containsMatchIn(second),
            "a blank second person is not the one picked",
        )
        assertTrue(projectForm.contains("the card's plan, its log and its dates"))
        // The Other tasks board's card is one person's work, so its create never asks for a second.
        val backlogForm = render {
            taskFormModal(backlogFormOf(true, "EMP0001", BucketRepository.findAll()), BACK_OTHER)
        }
        assertFalse(backlogForm.contains("name=\"assignee2\""), "a backlog popup asks for a second holder")
        assertFalse(backlogForm.contains("Also assign to"))
    }

    @Test
    fun `both holders open the editor filled and a lane card paints both avatars`() {
        val id = card().id
        assertTrue(
            TaskService.updateAssigned(
                id,
                TaskDraft(
                    title = "Spread base course", projectName = project.name,
                    assigneeEmpNo = "EMP0002", assignee2EmpNo = "EMP0001",
                    started = "2026-11-01", due = "2026-11-12", priority = "High",
                    status = "Not started", projectLane = DEFAULT_PROJECT_BUCKETS.first(),
                ),
                "EMP0001", canManage = true,
            ).taskId == id,
            "the card could not be given a second person",
        )
        // The editor picks the second name and offers the rest of the roster beside it.
        val html = edit(canManage = true)
        val second = html.substringAfter("id=\"taskAssignee2\"").substringBefore("</select>")
        assertTrue(selectedOption(second, "EMP0001"), "the second name on the card is not the one picked")
        assertTrue(second.contains("— Nobody else —"), "the picker cannot take management's second name off")
        // The lane board's foot prints one avatar per holder, in the order the card lists them.
        val board = laneBoard()
        val foot = board.substringAfter("<div class=\"kmeta kfoot\">").substringBefore("</div>")
        assertEquals(2, occurrences(foot, "avatar xs"), "the card did not paint both holders")
        assertTrue(foot.contains("title=\"Munkhtsetseg\""), "the second holder has no avatar")
        assertTrue(foot.contains("title=\"Bat-Erdene\""), "the first holder has no avatar")
    }

    @Test
    fun `a rejected popup comes back open, with the message under its own field`() {
        val draft = TaskDraft(
            title = "", projectName = "Haul road widening", assigneeEmpNo = "EMP0002",
            projectLane = DEFAULT_PROJECT_BUCKETS.first(),
        )
        val rejected = TaskService.create(draft, "EMP0001", canManage = true)
        // Rendered with the same state the save route passes: a create refused on the general board comes back
        // carrying every project's columns, so the field it names stays the field the reader can answer
        // (Stage 111) — see the refusal test below for what that picker lists.
        val html = render {
            taskFormModal(
                taskFormOf(taskId = 0, result = rejected, lanesByProject = TaskService.lanesByProjectName()),
                BACK_ASSIGNED,
            )
        }
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
    ): String = render {
        taskFormModal(
            editFormOf(card(), canEditAll = canManage, viewerEmpNo = viewer),
            back,
            fixed,
        )
    }

    /** [edit] as the person the card is assigned to sees it: not management, but the one who works it. */
    private fun editAsOwner(): String = edit(canManage = false, viewer = "EMP0002")

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
        // Every field on this popup is management's, so nothing is greyed and no scope line is needed.
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
        // The project and the sub-category are not printed at all since Stage 87, so what is greyed here is
        // what the popup still has: the title, the holder and the status.
        listOf("title", "assignee", "status").forEach { field ->
            val tag = control(html, field)
            assertTrue(tag.contains("disabled"), "$field is open to the person who does not own it")
        }
        listOf("started", "due", "priority").forEach { field ->
            val tag = control(html, field)
            assertFalse(tag.contains("disabled"), "$field was taken from the person who sets it")
        }
        // The plan the card no longer prints is here instead, and here alone: this popup is the only place
        // its owner can tick it, so taking the section out would take the tick out of the app. Since Stage 89
        // the owner also writes their own plan, so the *New step…* line is here — but the × is not: taking a
        // step off is still a decision about the plan rather than about the work.
        assertTrue(html.contains("id=\"task-steps-${task.id}\""), "the owner's editor carries no plan")
        assertTrue(html.contains("id=\"chk-$step\""), "the plan printed no box")
        assertTrue(html.contains("action=\"/app/tasks/checklist/toggle\""))
        assertEquals(1, occurrences(html, "action=\"/app/tasks/checklist/add\""), "the owner was refused a step")
        assertTrue(html.contains("id=\"stepAdd-${task.id}\""), "the owner's add line has no field")
        assertTrue(html.contains("name=\"edit\" value=\"${task.id}\""), "an added step would close the popup")
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

    @Test
    fun `a colleague's card opens to be read and not written`() {
        val task = card()
        val step = TaskService.addChecklistItem(task.id, "Set out the haul road", "EMP0001", true)!!
        TaskService.addNote(task.id, "Layer is wet, holding.", "EMP0002", false)
        // Stage 86: a reader with no part in the card opens it from the shared board and gets a view.
        val html = edit(canManage = false, viewer = "EMP9999", back = BACK_PROJECT, fixed = project)
        // The heading says what it is, and the line under it names the person it belongs to.
        assertTrue(html.contains("Task T-"), "the read-only popup still calls itself an editor")
        assertFalse(html.contains("Edit task"), "a stranger was offered an edit")
        assertTrue(html.contains("This task belongs to Bat-Erdene."), "the popup did not say whose card it is")
        assertTrue(html.contains("You can read its plan and its log"), "the popup did not say what is left")
        // Nothing on it writes: no save, no box, no ×, no add line, no delete and no note box. (The `<form>`
        // wrapper is still there, with every field in it disabled — it is the shape the popup has always had.)
        assertFalse(html.contains("Save task"), "a stranger was handed a save")
        assertFalse(html.contains("class=\"submit\""), "a stranger was offered a submit button")
        listOf(
            "checklist/toggle", "checklist/add", "checklist/remove",
            "notes/add", "notes/remove", "assigned-tasks/delete",
        ).forEach { assertFalse(html.contains(it), "a stranger was offered $it") }
        // Every field the popup still prints is greyed — including the three an owner keeps, because this
        // reader is not the owner. The project and sub-category left the editor in Stage 87, so there is
        // nothing of them to grey; the next test pins their absence.
        listOf("title", "assignee", "status", "started", "due", "priority").forEach { field ->
            assertTrue(control(html, field).contains("disabled"), "$field opened for a stranger")
        }
        // The plan and the log are what the card *is*, so both are here to read; the plan is drawn stopped.
        assertTrue(html.contains("id=\"task-steps-${task.id}\""), "the plan left the card")
        assertTrue(html.contains("id=\"chk-$step\""), "the plan printed no row")
        assertTrue(html.contains("chk-box locked"), "a stranger's plan is not drawn stopped")
        assertFalse(html.contains("name=\"done\""), "a stranger was handed a tick box")
        assertTrue(html.contains("Layer is wet, holding."), "the log left the card")
        assertFalse(html.contains("class=\"note-foot\""), "a stranger was handed the note foot")
        // One way out, and it says what it does rather than offering a cancel of nothing.
        assertTrue(html.contains(">Close<"), "the read-only popup has no Close")
        assertFalse(html.contains(">Cancel<"), "the read-only popup offers to cancel nothing")
    }

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
        val html = generalCreate()
        assertTrue(html.contains("style=\"display:block\""), "?new=1 came back closed")
        assertTrue(html.contains(">New task<"))
        assertFalse(html.contains("modal-checklist"))
        assertFalse(html.contains("modal-notes"))
        assertFalse(html.contains("modal-scope"), "a blank form is management's own")
        assertTrue(html.contains("name=\"taskId\" value=\"0\""))
        // Stage 48 took the blank choice out of both pickers: a card that names no project belongs to neither
        // world, and one that names no lane of it is nowhere on that board. **Stage 111** leaves that true and
        // adds the other half of it — the blank this page's create opens on is a question, and the tests below
        // say what its two pickers do about it.
        assertFalse(html.contains("— No project —"), "the general board can still create a card on no project")
        val onProject = render { taskFormModal(newFormOf().copy(lanes = lanes()), BACK_PROJECT, project) }
        assertFalse(onProject.contains("— No project —"))
        assertTrue(selectedOption(onProject, project.name), "a new card was not opened on this project")
    }

    // --- the general board's create chooses its own board (Stage 111) ----------------------

    /**
     * The Assigned page's `+ New task` popup exactly as `/app/assigned-tasks?new=1` builds it: management's
     * blank, plus every project's own columns, because this is the one create that asks which project the card
     * is on and so cannot already know its board. [draft] is what a refused save would post.
     */
    private fun generalCreate(draft: TaskDraft = TaskDraft()): String = render {
        taskFormModal(
            newFormOf().copy(lanesByProject = TaskService.lanesByProjectName(), values = draft),
            BACK_ASSIGNED,
        )
    }

    /** One picker's contents — its option tags, nothing above or below them. */
    private fun picker(html: String, name: String): String =
        Regex("<select[^>]*name=\"$name\"[^>]*>(.*?)</select>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("no $name picker rendered")

    /** The opening tag of the option that prints [text], so its attributes and its state can be read off it. */
    private fun optionTag(picker: String, text: String): String {
        val at = picker.indexOf(">$text</option>")
        assertTrue(at >= 0, "no option prints $text")
        val open = picker.lastIndexOf("<option", at)
        return picker.substring(open, picker.indexOf('>', open) + 1)
    }

    /** How many of this picker's options the page opens on. */
    private fun selectedCount(picker: String): Int =
        Regex("<option[^>]*selected").findAll(picker).count()

    /**
     * [attribute] of the option that prints [text], exactly as the page wrote it — no entity read back, so an
     * assertion can ask what the attribute is actually carrying.
     */
    private fun optionAttrRaw(picker: String, text: String, attribute: String): String {
        val tag = optionTag(picker, text)
        return Regex("$attribute=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            ?: error("$text carries no $attribute: $tag")
    }

    /**
     * [attribute] of the option that prints [text], read back through whatever entities the attribute escaping
     * put there — a column list is free text from Settings, so the comparison is made on the text, not on the
     * byte form the page happened to write.
     */
    private fun optionAttr(picker: String, text: String, attribute: String): String =
        optionAttrRaw(picker, text, attribute)
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    @Test
    fun `the general create opens on a project to choose, and its columns wait for one`() {
        val html = generalCreate()
        val projects = picker(html, "project")
        // The page this button sits on is the reader's own board, not a project, so the popup must not answer the
        // project question for them. Stage 111 found it doing exactly that — opening on the first project in the
        // list and filing the card under that board's columns, which the reader never chose and could not see.
        assertTrue(optionTag(projects, CHOOSE_A_PROJECT).contains("selected"), "the picker chose a project anyway")
        assertEquals(1, selectedCount(projects), "more than one project is picked")
        // The Sub-category field is the same question one step later, and it has nothing to say yet.
        val lanes = picker(html, "lane")
        assertEquals(1, occurrences(lanes, "<option"), "columns were listed with no board chosen")
        assertTrue(lanes.contains(CHOOSE_A_PROJECT_FIRST), "the empty column picker did not say what it waits for")
        assertTrue(control(html, "lane").contains("disabled"), "a column was pickable out of no board")
        // Not the legacy trio either: those three are a fallback for a form that already knows its board.
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertFalse(lanes.contains("value=\"$it\""), "a board nobody chose was listed under it")
        }
        // And nothing on the form claims where the card lands, because nothing on it knows yet.
        assertTrue(control(html, "project").contains("data-lane-fill"), "the column list has no way to follow the project")
        assertFalse(html.contains("A new card lands under"), "the popup named a landing column it was never asked for")
        assertTrue(html.contains("whichever project you pick"), "the popup said nothing about choosing a project")
    }

    @Test
    fun `every project in the general create carries its own board's columns`() {
        val second = ProjectRepository.insert(
            code = "S-02", name = "Wash plant upgrade", status = "Planned", progress = 0,
            managerEmpNo = "EMP0002", due = "2027-06-30", type = "Site projects",
        )
        ProjectBucketRepository.insert(second, "Design")
        ProjectBucketRepository.insert(second, "Build")
        ProjectRepository.insert(
            code = "S-03", name = "Stockpile survey", status = "Planned", progress = 0,
            managerEmpNo = "EMP0002", due = "2027-09-30", type = "Site projects",
        )
        val awkward = ProjectRepository.insert(
            code = "S-04", name = "Tailings dam raise", status = "Planned", progress = 0,
            managerEmpNo = "EMP0002", due = "2027-12-31", type = "Site projects",
        )
        ProjectBucketRepository.insert(awkward, "Drill & Blast \"phase\"")
        val projects = picker(generalCreate(), "project")
        // Each option names the columns of the board it names, in board order — which is what lets the browser
        // swap the Sub-category list without asking the page again, and what stops one project's board from
        // filing a card under another's names.
        assertEquals(
            "[\"Engineering\",\"Procurement\",\"Production\"]",
            optionAttr(projects, project.name, "data-lanes"),
            "this project's own board is not on its option",
        )
        assertEquals(
            "[\"Design\",\"Build\"]",
            optionAttr(projects, "Wash plant upgrade", "data-lanes"),
            "a second board's columns were not carried on its own option",
        )
        assertEquals("[]", optionAttr(projects, "Stockpile survey", "data-lanes"), "a bare board invented a column")
        // A column name is free text from Settings, and it travels inside a quoted attribute as JSON, so both
        // layers have to hold: a raw quote there would end the attribute early and hand the browser half a list,
        // and an ampersand the page resolves into something else is a JSON escape the script cannot parse — which
        // would read as a board with no columns at all.
        val awkwardTag = optionTag(projects, "Tailings dam raise")
        assertTrue(
            Regex("data-lanes=\"[^\"]*\">").containsMatchIn(awkwardTag),
            "a column name broke the attribute it is carried in: $awkwardTag",
        )
        val awkwardList = optionAttr(projects, "Tailings dam raise", "data-lanes")
        assertFalse(awkwardList.contains("&"), "an ampersand reached the JSON the script parses")
        assertTrue(awkwardList.contains("u0026"), "the ampersand did not go out as a JSON escape")
        assertTrue(awkwardList.startsWith("[") && awkwardList.endsWith("]"), "the column list is not a JSON array")
    }

    @Test
    fun `a chosen project whose board has no columns says so`() {
        ProjectRepository.insert(
            code = "S-03", name = "Stockpile survey", status = "Planned", progress = 0,
            managerEmpNo = "EMP0002", due = "2027-09-30", type = "Site projects",
        )
        // The state a project with an emptied board is in, reached without a script: the reader named it and the
        // save came back refused on the column, because there is no column on that board to name.
        val refused = TaskService.create(
            TaskDraft(
                title = "Drill the pit wall", projectName = "Stockpile survey", assigneeEmpNo = "EMP0002",
                projectLane = DEFAULT_PROJECT_BUCKETS.first(),
            ),
            callerEmpNo = "EMP0001", canManage = true,
        )
        val html = generalCreate(refused.values)
        val lanes = picker(html, "lane")
        assertTrue(lanes.contains(NO_COLUMN_ON_BOARD), "an empty board was not named as empty")
        assertEquals(1, occurrences(lanes, "<option"), "a board with no columns grew a choice")
        assertTrue(control(html, "lane").contains("disabled"), "a column could be picked off an empty board")
        assertFalse(lanes.contains(CHOOSE_A_PROJECT_FIRST), "a missing choice was blamed for a missing board")
    }

    @Test
    fun `a refused create comes back on the chosen project's own columns`() {
        val withBoard = ProjectRepository.insert(
            code = "S-02", name = "Wash plant upgrade", status = "Planned", progress = 0,
            managerEmpNo = "EMP0002", due = "2027-06-30", type = "Site projects",
        )
        ProjectBucketRepository.insert(withBoard, "Design")
        ProjectBucketRepository.insert(withBoard, "Build")
        // A column of one board posted against another — which is what the old popup let a reader do, because it
        // listed three names from no board in particular. The refusal is the no-JavaScript round trip, so it has
        // to be the honest one: the project they named comes back chosen, and its columns come back listed.
        val refused = TaskService.create(
            TaskDraft(
                title = "Spread base course", projectName = project.name, assigneeEmpNo = "EMP0002",
                projectLane = "Design",
            ),
            callerEmpNo = "EMP0001", canManage = true,
        )
        val html = render {
            taskFormModal(
                taskFormOf(taskId = 0, result = refused, lanesByProject = TaskService.lanesByProjectName()),
                BACK_ASSIGNED,
            )
        }
        val projects = picker(html, "project")
        assertTrue(optionTag(projects, project.name).contains("selected"), "the refusal forgot the project they chose")
        assertEquals(0, occurrences(projects, CHOOSE_A_PROJECT), "the refusal put the question back")
        val lanes = picker(html, "lane")
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(lanes.contains("value=\"$it\""), "$it is not on the refused popup")
        }
        assertFalse(lanes.contains("value=\"Design\""), "the column that was refused is offered back as an answer")
        assertFalse(control(html, "lane").contains("disabled"), "the refused field was shut on a board that has columns")
        assertTrue(lanes.contains(NOT_A_COLUMN_OF_THIS_PROJECT), "the picker did not say why the posted name is missing")
        val message = html.indexOf("Choose which part of this project the card is on.")
        assertTrue(message > html.indexOf("name=\"lane\""), "the column's message printed before its field")
        assertEquals(1, occurrences(html, "field-err"), "a refusal came back with a second message")
    }

    @Test
    fun `a project page's create keeps the board the page already names`() {
        val onProject = render { taskFormModal(newFormOf().copy(lanes = lanes()), BACK_PROJECT, project) }
        // The page is a project, so there is nothing to choose and nothing to follow: one board, already named,
        // opened on its first column. Stage 111 changes no part of this popup — including its copy.
        assertFalse(onProject.contains("data-lane-fill"), "the page's own board was put up for choosing")
        assertFalse(onProject.contains("data-lanes"), "a fixed board shipped a list to swap it for")
        assertFalse(onProject.contains(CHOOSE_A_PROJECT), "a project page asked which project")
        assertTrue(selectedOption(onProject, project.name), "the create opened on no project")
        assertTrue(selectedOption(onProject, DEFAULT_PROJECT_BUCKETS.first()), "the create opened on no column")
        assertFalse(control(onProject, "lane").contains("disabled"), "this project's columns were greyed")
        assertTrue(onProject.contains("New tasks land on ${project.name}"), "the page stopped naming the board")
        assertTrue(onProject.contains("A new card lands under ${DEFAULT_PROJECT_BUCKETS.first()}"))
    }

    @Test
    fun `a backlog create is not asked which project it is on`() {
        BucketRepository.insert("Site works")
        val html = render {
            taskFormModal(
                backlogFormOf(canManage = true, viewerEmpNo = "EMP0001", columns = TaskService.bucketRows()),
                BACK_OTHER,
            )
        }
        // Its question is which *column* of the Other tasks board, and the project half is the one the reader
        // switches to — so the picker that would need a per-project column list is not on show here at all.
        assertFalse(html.contains("data-lane-fill"), "a backlog create was handed the project's column refill")
        assertFalse(html.contains(CHOOSE_A_PROJECT), "a backlog card was asked which project it is on")
        assertFalse(html.contains(CHOOSE_A_PROJECT_FIRST), "the backlog's column picker was told to wait for a project")
        assertTrue(control(html, "column").isNotEmpty(), "the backlog create lost its own column picker")
        assertFalse(html.contains("name=\"lane\""), "a backlog create grew a project's sub-category field")
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
    fun `a finished card's steps are settled rather than clickable, for the reader who may tick them`() {
        val task = card()
        val (first, second) = steps("Set out the haul road", "Compact the sub-base")
        assertTrue(TaskService.toggleChecklistItem(second, "EMP0001", true))
        assertTrue(TaskService.updateStatus(task.id, "Completed", "EMP0001", canManage = true))
        val html = edit(canManage = true)
        // The plan reads as the plan it was a moment ago — same rows, every one struck through, and the
        // head counts what the service ticked.
        assertTrue(html.contains("2 of 2 done · 100%"), "a finished card's plan did not print as done")
        assertEquals(2, occurrences(html, "class=\"chk-row\""))
        assertEquals(2, occurrences(html, "class=\"chk-label done\""))
        // …but nothing in it is a control: the route refuses every box on a completed card, so drawing one
        // would be a refusal waiting to happen. The form is gone rather than merely inert.
        assertEquals(0, occurrences(html, "action=\"/app/tasks/checklist/toggle\""), "a finished card was handed tick boxes")
        assertFalse(html.contains("data-autosubmit"), "a locked box would still submit itself on a click")
        assertEquals(2, occurrences(html, "class=\"chk-box locked\""))
        val box = Regex("<input[^>]*id=\"chk-$first\"[^>]*>").find(html)?.value
        assertNotNull(box, "the finished card printed no square at all")
        assertTrue(box!!.contains("disabled"), "a step on a finished card was still tickable")
        assertTrue(box.contains("checked"), "a settled step printed unticked")
        // The reason is on the row, and the way round it is the card's status, not the step.
        assertTrue(html.contains("This card is Completed"), "a locked box said nothing about why")
        // Writing the plan is a different route and stays open: management can still correct a finished card.
        assertEquals(1, occurrences(html, "action=\"/app/tasks/checklist/add\""))
        assertEquals(2, occurrences(html, "action=\"/app/tasks/checklist/remove\""))
        // The same card, seen by the person it belongs to: they keep the plan and lose the boxes with it, and
        // keep the add line they gained in Stage 89 — which writes a step unticked like any other (Stage 101).
        val asOwner = editAsOwner()
        assertTrue(asOwner.contains("id=\"task-steps-${task.id}\""), "the owner lost a finished card's plan")
        assertEquals(0, occurrences(asOwner, "action=\"/app/tasks/checklist/toggle\""), "the owner was handed boxes on a finished card")
        assertEquals(2, occurrences(asOwner, "class=\"chk-box locked\""))
        assertEquals(1, occurrences(asOwner, "action=\"/app/tasks/checklist/add\""), "a finished card lost the owner's add line")
        assertFalse(asOwner.contains("checklist/remove"), "the owner was offered to strike a line off")
    }

    @Test
    fun `a step written onto a settled plan prints unticked`() {
        val task = card()
        steps("Set out the haul road", "Compact the sub-base")
        assertTrue(TaskService.updateStatus(task.id, "Completed", "EMP0001", canManage = true))
        // Stage 101: the add line writes work still to do, even on a card that is already finished — so the
        // plan the card prints is the plan it has, and the one row added after the fact is the one row in it
        // that is not struck through.
        val late = TaskService.addChecklistItem(task.id, "Photograph the start", "EMP0002", false)!!
        val html = edit(canManage = true)
        assertTrue(html.contains("2 of 3 done"), "a step added to a finished card counted as done")
        assertEquals(2, occurrences(html, "class=\"chk-label done\""), "the late step printed struck through")
        val box = Regex("<input[^>]*id=\"chk-$late\"[^>]*>").find(html)?.value
        assertNotNull(box, "the late step printed no square")
        assertFalse(box!!.contains("checked"), "a step added to a finished card printed ticked")
        assertTrue(box.contains("disabled"), "a finished card's rows stopped being stopped")
        // The owner sees the same reading: the plan, still stopped, with the new line unticked.
        val asOwner = editAsOwner()
        assertTrue(asOwner.contains("2 of 3 done"))
        assertEquals(3, occurrences(asOwner, "class=\"chk-box locked\""))
    }

    @Test
    fun `an open card keeps its boxes, and a completed one is the only card that loses them`() {
        val task = card()
        steps("Set out the haul road")
        assertTrue(TaskService.updateStatus(task.id, "Blocked", "EMP0001", canManage = true))
        val html = edit(canManage = true)
        assertEquals(1, occurrences(html, "action=\"/app/tasks/checklist/toggle\""))
        assertFalse(html.contains("chk-box locked"), "a card nobody has finished had its plan stopped")
        // *Blocked* is not *Completed*: the four open columns are worked the same way *In progress* is.
        assertTrue(TaskService.updateStatus(task.id, "Completed", "EMP0001", canManage = true))
        assertTrue(edit(canManage = true).contains("chk-box locked"), "only the Completed column locks the plan")
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
    fun `no popup form carries the backlog's retired wide view`() {
        // **Stage 40** put a hidden `allbuckets` field on every form in this panel — the step toggle, the step
        // remove, the step add, the note, and management's **Delete task** address — so a write from the wide board
        // answered on the wide board. **Stage 97** took the wide view out with the button that set it, and the field
        // with it: five forms used to carry it, so this counts none.
        steps("Set out the haul road")
        val html = edit(canManage = true)
        assertFalse(html.contains("name=\"allbuckets\""), "a popup form still posts the retired flag")
        assertFalse(html.contains("showAllBuckets"), "the retired view is still named in the markup")
        // Which board the answer lands on is said once, by `back` — and on the backlog that token carries the
        // bucket filter (Stage 93), the one piece of view state the page still has.
        assertTrue(html.contains("name=\"back\" value=\"assigned\""), "the popup forgot the board it came from")
        assertTrue(
            formPosting(edit(canManage = true, back = "other:7"), "/app/assigned-tasks/save")
                .contains("name=\"back\" value=\"other:7\""),
            "a card opened from a narrowed backlog does not answer there",
        )
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
        // belongs to nobody it is management alone. **Since Stage 86** the popup opens for a third reader —
        // anybody — but the *boxes* still follow that rule: a stranger gets the plan drawn stopped, which is
        // why the section is on the page and the tick form is not.
        val management = render {
            taskFormModal(editFormOf(unowned, canEditAll = true, viewerEmpNo = "EMP0001"), BACK_ASSIGNED)
        }
        assertTrue(management.contains("action=\"/app/tasks/checklist/toggle\""), "management cannot work a card")
        val nobody = render {
            taskFormModal(editFormOf(unowned, canEditAll = false, viewerEmpNo = "EMP0002"), BACK_ASSIGNED)
        }
        assertTrue(nobody.contains("task-steps-"), "the plan is part of the card and left the popup")
        assertFalse(nobody.contains("name=\"done\""), "a reader the route would refuse was handed a box")
        assertFalse(nobody.contains("checklist/add"), "a stranger was offered to write a step")
        assertTrue(nobody.contains("chk-box locked"), "the stranger's plan is not drawn stopped")
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
        assertTrue("kpct" in body.substringBefore("</div>"), "the percentage is not on the card's own line")
        // **Stage 103** took the arrows off this board, so nothing follows that line inside the block a tick
        // repaints — a button row printed back here would sit under the card and outside the swapped region.
        assertFalse("kmove" in body, "a status board grew a button row under its card again")
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
     *
     * [canManageLanes] is the fourth right and it stays **off** by default (Stage 74): the page computes it from
     * [mn.murray.pts.service.TaskService.mayEditLanes] and passes it, so a board rendered without the argument is
     * the board a reader who cannot name a bucket stands on — the columns are there and nothing beside them
     * writes one. That is why the Stage 35 tests below can say *no control* and mean the openers as much as the
     * old per-lane forms.
     */
    private fun laneBoard(
        tasks: List<TaskItem> = listOf(card()),
        reader: UserSession = engineer,
        canManage: Boolean = false,
        canAdd: Boolean = false,
        canMoveCards: Boolean = canAdd,
        canManageLanes: Boolean = false,
        doneOpen: Set<Int> = emptySet(),
    ): String = render {
        taskKanban(
            tasks, reader, canManage, BACK_PROJECT, project.id,
            canAdd = canAdd,
            fixedProject = project,
            lanes = lanes(),
            canMoveCards = canMoveCards,
            canManageLanes = canManageLanes,
            doneOpen = doneOpen,
        )
    }

    /** The `<form>` tags this board prints for [classes], one string each — attributes stay on one line. */
    private fun formTags(html: String, classes: String): List<String> =
        Regex("<form[^>]*class=\"${Regex.escape(classes)}\"[^>]*>").findAll(html).map { it.value }.toList()

    /**
     * The whole `<form …>` block this board prints for [classes] — its opening tag, its children and its
     * `</form>`. [formTags] stops at the opening tag, which is what its assertions want (a form's own `action` and
     * `data-live-target` are attributes), and is the wrong slice for an **ask**, because the field carrying it is a
     * child of the form: `?laneedit=<id>` and `?laneadd=1` are hidden inputs, not attributes.
     */
    private fun formSection(html: String, classes: String): String {
        val opener = Regex("<form[^>]*class=\"${Regex.escape(classes)}\"[^>]*>").find(html)
            ?: return ""
        val end = html.indexOf("</form>", opener.range.first)
        return html.substring(opener.range.first, if (end < 0) html.length else end + "</form>".length)
    }

    /**
     * Every form carrying [classes], in the order the page draws them. A folded column is one form per column that
     * holds finished work, so a promise about *which* columns a press leaves open can only be read off the row of
     * them, not off the first.
     */
    private fun formSections(html: String, classes: String): List<String> =
        Regex("<form[^>]*class=\"${Regex.escape(classes)}\"[^>]*>").findAll(html).map { opener ->
            val end = html.indexOf("</form>", opener.range.first)
            html.substring(opener.range.first, if (end < 0) html.length else end + "</form>".length)
        }.toList()

    @Test
    fun `a project board is cut by its lanes and never by status`() {
        val fabrication = lanes().first()
        val html = laneBoard()
        // Every lane is a column, in the order the board was given them, and the card sits in the one it
        // was filed in. `data-lane` is what a drop posts; a status column would have said `data-status`.
        // The harness gave this board the three phases (see [seedOpeningThree]); since Stage 100 that is a choice
        // the category's Settings list makes, not something every create writes.
        assertEquals(DEFAULT_PROJECT_BUCKETS, lanes().map { it.name })
        lanes().forEach { assertTrue(html.contains("<span class=\"kcol-title\">${it.name}</span>"), "${it.name} is not a column") }
        assertTrue(html.contains("data-lane=\"${fabrication.id}\""), "the lanes carry no id for a drop")
        // No *column* is cut by status here — `data-lane` is what a drop posts. A card's own Done button
        // still writes a status, and that is the only one on the page.
        assertFalse(html.contains("class=\"kcol\" data-status"), "a status column is on this board")
        // The card was filed in the first of the three, so nothing here is waiting for a lane — and a board with
        // nothing to put in **Unsorted** prints no Unsorted column (Stage 37): the columns are the project's
        // lanes, the way the board was drawn to look.
        assertFalse(html.contains("data-lane=\"0\""), "an empty Unsorted column is printed as a seventh lane")
        TASK_STATUSES.forEach {
            assertFalse(html.contains("<span class=\"kcol-title\">$it</span>"), "$it is a column here")
        }
        // No *column* is cut by status here — `data-lane` is what a drop posts. What the board stopped printing
        // is the column, not the stage: since Stage 76 the card says its own stage, and since Stage 78 it says it
        // on its foot line beside the holder. The only place it is *not* printed is a head above it.
        assertTrue(html.contains("Spread base course"))
        assertTrue(html.contains("<span class=\"kstat s-notstarted\">Not started</span>"), "the card lost its stage")
    }

    @Test
    fun `the drag is the mover's and the click is everybody's`() {
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
        // drag it, and since Stage 86 can also open it — read-only — while nobody hands them the owner's button.
        val asMoverWithoutWork = laneBoard(reader = UserSession("EMP9999"), canManage = false, canAdd = true)
        assertTrue(asMoverWithoutWork.contains("draggable=\"true\""), "a mover could not drag somebody else's card")
        assertTrue(
            asMoverWithoutWork.contains("data-task-card=\"${card().id}\""),
            "a colleague's card on a shared board was not clickable",
        )
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
        // The column the user asked to have gone off the board. It is gone from the board that shows its lanes,
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
        // TaskServiceTest — but nothing on this page reaches them, so a project's columns are the three it was
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
    fun `a lane head is its colour, its name and its count`() {
        unfiled()
        val html = laneBoard(canAdd = true)
        // The whole of a lane's chrome: one dot, one span of name, one span of number, and nothing else in
        // between. A control cannot hide inside a head drawn to this shape. The dot is Stage 74's — the picture
        // the user handed over puts one on every column head, and it is the same span the Settings catalogue's
        // bucket rows use, so a board and the picker beneath it say the same thing about the same bucket.
        val heads = Regex(
            "<div class=\"kcol-head\">\\s*<span class=\"bucket-dot dot-\\d\"></span>" +
                "\\s*<span class=\"kcol-title\">[^<]*</span>" +
                "\\s*<span class=\"kcol-count\">\\d+</span>\\s*</div>",
        ).findAll(html).toList()
        assertEquals(lanes().size, heads.size, "a head carries something besides its colour, its name and its count")
        // Unsorted is the column with no bucket behind it, so it wears no dot and keeps its old head.
        val unsorted = Regex(
            "<div class=\"kcol-head\">\\s*<span class=\"kcol-title\">$UNSORTED_LANES</span>" +
                "\\s*<span class=\"kcol-count\">\\d+</span>\\s*</div>",
        ).findAll(html).toList()
        assertEquals(1, unsorted.size, "the unfiled column's head is not the dotless one")
        assertEquals(lanes().size + 1, occurrences(html, "kcol-head"), "the board is not its lanes plus Unsorted")
        // The dots are the stylesheet's six hues, cycled by **position** rather than stored per bucket: index 0 is
        // `dot-0` on the board and in the catalogue, which is what keeps the two surfaces from drifting.
        lanes().forEachIndexed { index, lane ->
            assertTrue(
                Regex(
                    "<span class=\"${bucketDotClass(index)}\"></span>\\s*<span class=\"kcol-title\">" +
                        Regex.escape(lane.name),
                ).containsMatchIn(html),
                "${lane.name} is not the colour position ${index + 1} is given",
            )
        }
        // Taking the lane controls away took none of the lanes themselves: the columns are still the drop
        // targets `kanban.js` posts to, and they still carry the Stage 34 lines that start a card in one.
        assertEquals(lanes().size + 1, occurrences(html, "data-lane=\""), "a column stopped answering drops")
        assertEquals(lanes().size, occurrences(html, "class=\"kcol-add\""), "the lanes lost their add lines")
    }

    // --- Stage 74: the openers that name a bucket live on the lane board ------------------------------------

    /**
     * The board with one extra bucket on it. The extra is here because a board with four columns shows the
     * arrangement Stage 75 leaves: the three it opened with and a column somebody named for itself, all of them
     * ordinary — each carries its own menu and the dashed box trails the run.
     */
    private fun boardWithAnExtra(canManageLanes: Boolean): String {
        ProjectBucketRepository.insert(project.id, "Site works")
        return laneBoard(canManageLanes = canManageLanes)
    }

    @Test
    fun `every bucket on a board its reader may re-shape carries its own menu`() {
        val html = boardWithAnExtra(canManageLanes = true)
        // One opener per column, the three a board opens with included. Stage 75 took away the name protection
        // — [mn.murray.pts.service.TaskService.renameLane] and `removeLane` refuse no lane for what it is
        // called — so a menu on **Engineering** answers, and leaving it off would be the opposite of Stage 51's
        // rule: a control the reader needs, withheld because of a name.
        assertEquals(lanes().size, occurrences(html, "kcol-menu-btn"), "a column of this board has no menu")
        assertTrue(html.contains("Rename or remove this bucket"))
        // The opener is the backlog's shape: a GET onto this page carrying the lane's id, which `app.js` follows
        // and answers by swapping `#projectLaneModal` rather than by moving the board. Read as a whole form, because
        // the ask is a hidden input — a child of the form, not an attribute of it.
        assertEquals(lanes().size, occurrences(html, "class=\"kcol-menu\""), "two columns shared one menu")
        Regex("<form[^>]*class=\"kcol-menu\"[^>]*>.*?</form>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.value }.toList().forEachIndexed { index, menu ->
                assertTrue(menu.contains("action=\"/app/projects/${project.id}\""), "the opener points off this page")
                assertTrue(menu.contains("method=\"get\""), "the opener would navigate instead of swapping")
                assertTrue(menu.contains("data-live-target=\"#projectLaneModal\""))
                assertTrue(
                    menu.contains("name=\"laneedit\" value=\"${lanes()[index].id}\""),
                    "column ${index + 1}'s menu asks for the wrong bucket",
                )
            }
        // The dot the picture puts on every head cycles by position, so the fourth bucket is `dot-3` — the same
        // agreement the Settings catalogue's rows make with the stylesheet, offset there by the three this board
        // opens with.
        assertTrue(
            Regex("<span class=\"bucket-dot dot-3\"></span>\\s*<span class=\"kcol-title\">Site works</span>")
                .containsMatchIn(html),
            "the extra column's dot is not the one its position is given",
        )
    }

    @Test
    fun `the add box trails the run of columns without being one of them`() {
        val html = boardWithAnExtra(canManageLanes = true)
        assertEquals(1, occurrences(html, "class=\"lane-add-box\""), "the board offered two boxes to add a bucket")
        val box = formSection(html, "lane-add-box")
        assertTrue(box.contains("name=\"laneadd\" value=\"1\""), "the box opens nothing")
        assertTrue(box.contains("data-live-target=\"#projectLaneModal\""))
        assertTrue(box.contains("+ Add bucket"))
        // `kanban.js` binds its drop targets as `.kcol[data-lane]`. The box is a column's width and a column's
        // height without being a column: it holds no bucket, so a card dragged into it would be a move the
        // service refuses. Neither marker is on the form, and the board's columns are still exactly the buckets.
        assertFalse(box.contains("kcol"), "the add box is shaped like a column")
        assertFalse(box.contains("data-lane"), "the add box is a drop target")
        assertEquals(lanes().size, occurrences(html, "data-lane=\""), "the add box was counted as a column")
    }

    @Test
    fun `a reader who may not write buckets is given neither opener`() {
        // The right is [mn.murray.pts.service.TaskService.mayEditLanes], and the board asks it for both controls:
        // a `⋯` whose every button the route refuses, and a box that opens a form the save refuses, are the two
        // dead controls Stage 51 removed for the same reason.
        val html = boardWithAnExtra(canManageLanes = false)
        assertFalse(html.contains("kcol-menu"), "a column menu is on a board its reader cannot rename")
        assertFalse(html.contains("lane-add-box"), "an add box is on a board its reader cannot widen")
        assertFalse(html.contains("laneedit"), "a hand-built ?laneedit= has a form to be aimed at")
        assertFalse(html.contains("/buckets"), "a bucket write is reachable from this board")
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
        // The arrows are gone because there is no status to *walk*, but the stage is still a fact the reader is
        // owed, and since Stage 76 the card prints it: this card is Not started, and no other
        // stage word appears on the board.
        assertFalse(html.contains("In progress"), "the card printed a stage it does not have")
        assertTrue(html.contains("<span class=\"kstat s-notstarted\">Not started</span>"), "the card printed no stage at all")
    }

    @Test
    fun `a lane card carries its stage and its holder on one foot line`() {
        // Stage 78's shape, read off the server's own markup. The card is its title, then priority and due date,
        // then a foot line holding the stage at the left and the holder's avatar at the right — and no code chip,
        // because a project page is one project's cards and the number identifies nothing here.
        val html = laneBoard()
        assertFalse(html.contains("kcard-top"), "a lane card printed a code line")
        assertFalse(html.contains("tcode"), "a lane card printed its code")
        val foot = html.substringAfter("<div class=\"kmeta kfoot\">").substringBefore("</div>")
        assertTrue(foot.contains("<span class=\"kstat s-notstarted\">Not started</span>"), "the foot has no stage")
        assertTrue(foot.contains("avatar xs"), "the foot has no holder")
        // The avatar moved *out* of the meta line rather than being duplicated, and the meta line is still the
        // one `task-modal.js` swaps when a step is ticked.
        val meta = html.substringAfter("<div class=\"kbody\"").substringBefore("</div>")
        assertTrue(meta.contains("prio p-high"), "the meta line lost its priority")
        assertFalse(meta.contains("avatar"), "the holder is on both lines")
        // A status board keeps the code chip and prints no foot line of its own: its column head is the stage,
        // and its cards come from every project at once, which is exactly where a code earns its line.
        val general = render { taskKanban(listOf(card()), engineer, canManage = false, BACK_ASSIGNED, 0) }
        // **Stage 104 made that name the way to the project.** kotlinx writes an `<a>`'s attributes in map order,
        // so the assertions read the tag whole and ask it each question rather than pinning an order.
        val chip = Regex("<div class=\"kcard-top\"><a[^>]*>[^<]*</a>").find(general)?.value.orEmpty()
        assertTrue(chip.isNotEmpty(), "the code chip left the status board")
        assertTrue(chip.contains("class=\"tcode\""), "the chip is not the app's code chip any more: $chip")
        assertTrue(chip.contains("href=\"/app/projects/${project.id}\""), "the chip links to no project: $chip")
        // …because since Stage 88 what it carries is the card's **project** (`S-01`), not the card's own
        // `T-00012`: on a board of cards from every project at once, "which project is this work for" is the fact.
        assertTrue(chip.endsWith(">S-01</a>"), "the chip does not name its project: $chip")
        assertTrue(
            chip.contains("title=\"Open project S-01\"") &&
                chip.contains("aria-label=\"Open project S-01 on the Projects board\""),
            "the chip does not say where pressing it goes: $chip",
        )
        assertFalse(general.contains("<span class=\"tcode\">S-01</span>"), "the project chip is still a dead label")
        assertFalse(general.contains("T-0000"), "the status card still printed its own task number")
        assertFalse(general.contains("kfoot"), "a status card grew the lane board's foot line")
    }

    @Test
    fun `a finished card on a lane board offers to reopen itself`() {
        assertTrue(TaskService.updateStatus(card().id, "Completed", "EMP0002", canManage = false))
        val html = laneBoard()
        assertTrue(html.contains("↺ Reopen"))
        assertTrue(Regex("data-status=\"Not started\"").containsMatchIn(html))
        // A card that is done reads as done at a glance, and since **Stage 78** so does one that is merely open:
        // the tint is the stage's, on every board, the way a status board has always worn it. What the word is for
        // is the exact stage, which the colour alone cannot tell apart from its neighbours.
        assertTrue(html.contains("class=\"kcard s-completed\""), "a finished card was not marked")
        assertTrue(
            html.contains("<span class=\"kstat s-completed\">Completed</span>"),
            "a finished card lost its stage",
        )
        // The move is the project manager's (Stage 49); the status writes around it stay the assignee's, which
        // is the whole difference the stage drew: this card's owner still finishes it, no longer relocates it.
        val open = TaskService.moveToLane(card().id, lanes().last().id, "EMP0001", canManage = false)
        assertTrue(open)
        assertTrue(TaskService.updateStatus(card().id, "In progress", "EMP0002", canManage = false))
        val working = laneBoard()
        assertTrue(
            working.contains("<span class=\"kstat s-inprogress\">In progress</span>"),
            "a board with no status column printed no stage for the card that is being worked",
        )
        // …and the card wears it too: the word and the tint are the same fact drawn twice on purpose, one for the
        // reader scanning a column and one for the reader who is already looking at this card.
        assertTrue(working.contains("class=\"kcard s-inprogress\""), "a lane board left an open card untinted")
    }

    // --- Stage 80: a column's finished cards folded behind one line ------------------------------------

    @Test
    fun `a column with a finished card folds it behind a count, and a column without prints no line`() {
        assertTrue(TaskService.updateStatus(card().id, "Completed", "EMP0002", canManage = false))
        val html = laneBoard()
        // The line exists only where there is something to open, so three columns and one card print exactly one
        // toggle — the other two have no finished work and offer nothing.
        assertEquals(1, occurrences(html, "class=\"kcol-done-toggle\""), "a column invented a completed section")
        assertTrue(html.contains("▸ Completed 1"), "the folded cards were not counted")
        // The card is still on the page — a board that hid work is a board that lost it — but inside a block the
        // stylesheet keeps shut until the reader asks.
        assertTrue(html.contains("class=\"kcol-done\" hidden=\"true\""), "the finished cards were not folded away")
        assertTrue(html.contains("Spread base course"), "a folded card is a card the page does not have")
        // …and the head still counts it: the number above the column is how much work the bucket holds, which is
        // what it has always counted and what kanban.js recomputes the same way after a drag.
        assertTrue(
            html.contains("<span class=\"kcol-title\">${lanes().first().name}</span><span class=\"kcol-count\">1</span>"),
            "folding a card took it off the column's count",
        )
        // One card finished is one card, singular; the line says so.
        assertFalse(html.contains("Completed 1 cards"), "the toggle mis-counted its own cards")
    }

    @Test
    fun `the toggle is a GET onto the project page that names the columns open after the press`() {
        assertTrue(TaskService.updateStatus(card().id, "Completed", "EMP0002", canManage = false))
        val lane = lanes().first()
        val other = lanes().last()
        val html = laneBoard()
        val form = formSection(html, "kcol-done-toggle")
        // The board's own mechanism, not a script's memory: the fold is a key of the URL the page reads, so the
        // same address always answers with the same board and a reader with JavaScript off gets the identical
        // open-and-shut state.
        assertTrue(form.contains("action=\"/app/projects/${project.id}\""), "the toggle posted somewhere else")
        assertTrue(form.contains("method=\"get\""), "folding a column is not a write")
        assertTrue(form.contains("data-live-target=\"#projectBoard\""), "opening a column reloaded the page")
        // Shut at the moment of the press, so the set it asks for is this one column open and nothing else.
        assertTrue(
            form.contains("<input type=\"hidden\" name=\"showdone\" value=\"${lane.id}\">"),
            "the toggle did not ask for its own column to open",
        )
        assertFalse(form.contains("value=\"$other\""), "pressing one column opened another")
    }

    @Test
    fun `opening one column leaves the others folded and its own press closes it again`() {
        val lane = lanes().first()
        val second = lanes()[1]
        TaskService.create(
            TaskDraft(
                title = "Label the culverts", projectName = project.name, assigneeEmpNo = "EMP0002",
                started = "2026-11-01", due = "2026-11-14", priority = "Medium", status = "Not started",
                projectLane = second.name,
            ),
            callerEmpNo = "EMP0001", canManage = true,
        )
        TaskService.assigned().forEach { assertTrue(TaskService.updateStatus(it.id, "Completed", "EMP0002", canManage = false)) }
        val opened = laneBoard(tasks = TaskService.assigned(), doneOpen = setOf(lane.id))
        // The column the reader opened prints its cards and points the arrow down…
        assertTrue(opened.contains("▾ Completed 1"), "an opened column still reads as folded")
        // …and it is the only one: both columns hold finished cards, and exactly one of the two blocks is shut.
        // Counting the shut ones is the whole promise — `hidden` on both would mean opening one opened none, and
        // on neither would mean opening one opened them all.
        assertEquals(2, occurrences(opened, "class=\"kcol-done\""), "both columns are not holding finished cards")
        assertEquals(
            1, occurrences(opened, "class=\"kcol-done\" hidden=\"true\""),
            "an opened column kept its cards shut, or opening one column opened them all",
        )
        assertTrue(opened.contains("▸ Completed 1"), "opening one column opened them all")
        // Each line carries the set as it will read *after* the press, which is how one button can shut its own
        // column while leaving the other one open: the opened column's form asks for nothing (a board with no key
        // folded is the default), and the folded column's form asks for itself **and** for the column already
        // standing open — pressing it opens its own cards without closing the other's.
        val forms = formSections(opened, "kcol-done-toggle")
        assertEquals(2, forms.size, "a column holding finished cards grew no line of its own")
        assertFalse(forms[0].contains("name=\"showdone\""), "the opened column's own press left it open again")
        assertTrue(forms[1].contains("<input type=\"hidden\" name=\"showdone\" value=\"${second.id}\">"),
            "the folded column did not ask for its own cards")
        assertTrue(forms[1].contains("<input type=\"hidden\" name=\"showdone\" value=\"${lane.id}\">"),
            "opening a folded column shut the one already open")
    }

    @Test
    fun `an unfiled column keeps no fold and the status board keeps none at all`() {
        // **Unsorted is not one of the project's buckets** — it is drained by design and vanishes when it empties,
        // so folding its cards would hide the one column whose purpose is to be looked at.
        val loose = unfiled()
        assertTrue(TaskService.updateStatus(loose.id, "Completed", "EMP0002", canManage = false))
        val html = laneBoard(tasks = listOf(loose))
        assertFalse(html.contains("kcol-done-toggle"), "the Unsorted column grew a fold")
        assertFalse(html.contains("kcol-done"), "a folded block appeared outside the project's buckets")
        // A status board never had this: its columns are the statuses, so a Completed card is already where it
        // belongs and hiding it would hide the board's own answer.
        val statusBoard = render {
            taskKanban(listOf(loose), engineer, canManage = false, BACK_ASSIGNED, 0)
        }
        assertFalse(statusBoard.contains("kcol-done"), "the status board folded its Completed column's cards")
    }

    @Test
    fun `a lane board prints the stage and a status board leaves it to its column`() {
        // The one rule Stage 76 drew and Stage 78 kept, read from both boards at once. A project page has no
        // status column to read a card against, so the card says its own stage; the general board cuts cards by
        // exactly that stage, so repeating it under the column head would be two places for one fact. Each stage
        // is printed as a word and worn as a tint, on the same card, at the same time.
        TASK_STATUSES.forEach { status ->
            assertTrue(TaskService.updateStatus(card().id, status, "EMP0002", canManage = false))
            val slug = when (status) {
                "In progress" -> "s-inprogress"
                "On hold" -> "s-onhold"
                "Blocked" -> "s-blocked"
                "Completed" -> "s-completed"
                else -> "s-notstarted"
            }
            val board = laneBoard()
            assertTrue(
                board.contains("<span class=\"kstat $slug\">$status</span>"),
                "the lane board printed no stage word for $status",
            )
            assertTrue(board.contains("class=\"kcard $slug\""), "the lane card wore no tint for $status")
        }
        val general = render {
            taskKanban(listOf(card()), engineer, canManage = false, BACK_ASSIGNED, 0)
        }
        assertFalse(general.contains("kstat"), "a card under its own status column was told its status again")
        // What the two boards each keep one of: a lane card's foot line holds the stage and the holder and no code,
        // while a status card's top line holds the code and its meta line the holder.
        val foot = laneBoard().substringAfter("<div class=\"kmeta kfoot\">").substringBefore("</div>")
        assertTrue(foot.contains("kstat") && foot.contains("avatar"), "the stage and the holder are not on one line")
        val top = general.substringAfter("<div class=\"kcard-top\">").substringBefore("</div>")
        assertTrue(top.contains("class=\"tcode\""), "the status board lost its code line")
    }

    @Test
    fun `a create popup lists the three phases and opens on the lane it was called from`() {
        val html = render { taskFormModal(newFormOf().copy(lanes = lanes()), BACK_PROJECT, project) }
        // Posted by **name**: the three are the same three on every new board, so a name is the one
        // answer a popup can post without knowing this project's lane ids. (A drop still posts an id — that is
        // `kanban.js` talking to the board it is standing on, where the ids are already in the page.)
        assertTrue(html.contains("name=\"lane\""), "the create popup has no lane field")
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(html.contains("value=\"$it\""), "$it is missing from the picker")
        }
        // The lane whose `+ Add task` line was pressed is the one the blank form opens on.
        val aimed = render {
            taskFormModal(
                newFormOf(into = DEFAULT_PROJECT_BUCKETS.last()).copy(lanes = lanes()),
                BACK_PROJECT, project,
            )
        }
        assertTrue(selectedOption(aimed, DEFAULT_PROJECT_BUCKETS.last()),
            "the aimed create did not open on the lane it was called from")
    }

    @Test
    fun `a lane this project added itself is offered after the three, on its own board`() {
        // A project keeps its extra lanes, printed after the three it opened with — on the board and in the
        // create popup's picker alike. A save that posts a name the project does not hold is refused
        // (TaskServiceTest), so a reader must never be able to pick one from here either.
        val extraId = ProjectBucketRepository.insert(project.id, "Tendering")
        val extra = ProjectBucketRepository.find(extraId)!!
        val html = render {
            taskFormModal(newFormOf().copy(lanes = lanes() + extra), BACK_PROJECT, project)
        }
        assertTrue(html.contains("value=\"Tendering\""), "this project's own lane is off its picker")
        assertTrue(
            html.indexOf("value=\"Tendering\"") > html.indexOf("value=\"${DEFAULT_PROJECT_BUCKETS.last()}\""),
            "the extra lane is not after the three",
        )
        // The three are all still there, ahead of it: an extra lane joins the picker, it does not replace them.
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(html.contains("value=\"$it\""), "$it left the picker when the extra lane joined")
        }
    }

    @Test
    fun `an edit of a card on a project prints neither the project nor the sub-category picker`() {
        // Stage 87: a card's project is a fact of the row and its column is filed by dragging it on the board
        // behind the popup, so an edit opens on the card's own fields instead of repeating the page in two
        // drop-downs. The project still rides along as a hidden field: the save has to name the world it writes
        // to, and a save that named none would be read as a different project.
        val html = edit(canManage = true)
        assertFalse(html.contains("id=\"taskProject\""), "the project picker came back to a card's editor")
        assertFalse(html.contains("id=\"taskLane\""), "the sub-category picker came back to a card's editor")
        assertFalse(html.contains(">Sub-category<"), "the sub-category label came back")
        assertTrue(
            html.contains("<input type=\"hidden\" name=\"project\" value=\"${project.name}\">"),
            "the save lost the project it writes to",
        )
        // The rest of the popup is untouched: the board picker, the holder, the dates, the priority, the status.
        listOf("board", "assignee", "started", "due", "priority", "status").forEach {
            assertTrue(control(html, it).isNotEmpty(), "$it left the editor")
        }
        // A create still asks both — there is no row to read them off.
        val fresh = render { taskFormModal(newFormOf().copy(lanes = lanes()), BACK_PROJECT, project) }
        assertTrue(fresh.contains("id=\"taskProject\""), "a create lost its project picker")
        assertTrue(fresh.contains("id=\"taskLane\""), "a create lost its sub-category picker")
        // And so does a backlog card's editor, in the half its "where it lives" picker reveals: that is the one
        // crossing in which the two fields are the answer to a question the reader just asked.
        val backlogId = TaskRepository.insert(
            kind = "OTHER", title = "Clean the spill bund", projectId = null, due = "",
            priority = "Medium", assigneeEmpNo = "EMP0002", status = "Not started", bucketId = null, started = "",
        )
        val backlog = render {
            taskFormModal(
                editFormOf(TaskRepository.findById(backlogId)!!, canEditAll = true, viewerEmpNo = "EMP0001"),
                BACK_OTHER,
            )
        }
        assertTrue(backlog.contains("id=\"taskProject\""), "a backlog card lost the project it can move onto")
        assertTrue(backlog.contains("id=\"taskLane\""), "a backlog card lost the column it lands in")
    }

    @Test
    fun `a refused project card's save comes back with the pickers its message belongs under`() {
        // The popup posts the card's own project, so the only refusal about these two fields is a hand-built
        // or stale form. When one happens the pair returns — message, picker and all — rather than a popup
        // that says nothing, which is the one shape this app does not answer a refusal with.
        val task = card()
        val html = render {
            taskFormModal(
                taskFormOf(
                    taskId = task.id,
                    result = TaskSaveResult(
                        null,
                        mapOf(TaskField.PROJECT to "Choose which project this card is on."),
                        TaskDraft(title = task.title),
                    ),
                    task = task,
                    canEditAll = true,
                ),
                BACK_ASSIGNED,
            )
        }
        assertTrue(html.contains("id=\"taskProject\""), "the refusal had no project field to print under")
        assertTrue(html.contains("id=\"taskLane\""), "the refusal had no sub-category field to print under")
        assertTrue(html.contains("Choose which project this card is on."), "the reason is not on the page")
        assertFalse(
            html.contains("<input type=\"hidden\" name=\"project\""),
            "the popup posts a picker and a hidden field of the same name",
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
        // The card is unfiled, so this board prints an Unsorted column as well as its lanes.
        val html = laneBoard(canAdd = true)
        assertEquals(lanes().size + 1, occurrences(html, "kcol-head"), "this board is not its lanes plus Unsorted")
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

    // --- Stage 84: Delete task on every card's popup (Stage 81's Archive button is gone) --------

    /**
     * The whole `<form>` posting to [action], hidden fields and all. A tag-only match stops at the opening
     * `>`, which is exactly where every field this route reads lives.
     */
    private fun formPosting(html: String, action: String): String {
        val open = Regex("<form[^>]*action=\"${Regex.escape(action)}\"").find(html)
            ?: error("nothing on this page posts to $action")
        val end = html.indexOf("</form>", open.range.first)
        assertTrue(end > open.range.first, "the form posting to $action never closes")
        return html.substring(open.range.first, end)
    }

    @Test
    fun `a project card's popup carries management's Delete task beside the note box`() {
        val task = card()
        val html = edit(canManage = true, back = BACK_PROJECT, fixed = project)
        val form = formPosting(html, "/app/assigned-tasks/delete")
        assertTrue(html.contains(">Delete task<"))
        // Stage 84: the Archive button Stage 81 had put here is gone, and a live project card is destroyed from
        // this popup again — the Archive panel is for cards a removed bucket already took off the board.
        assertFalse(html.contains("/app/tasks/archive"), "a live project card still offered the old archive button")
        assertTrue(form.contains("method=\"post\""))
        assertTrue(form.contains("class=\"opener-form task-del\""))
        // The three fields the route reads, server-rendered from the page the popup is on — never typed.
        assertTrue(form.contains("name=\"taskId\" value=\"${task.id}\""))
        assertTrue(form.contains("name=\"back\" value=\"$BACK_PROJECT\""))
        assertTrue(form.contains("name=\"projectId\" value=\"${project.id}\""))
        // No `edit`, unlike every other form in this panel: those come back with the popup open on the card,
        // and a deleted card has no board to be open on.
        assertFalse(form.contains("name=\"edit\""), "the way back would reopen the popup on a deleted card")
        // The same shape the app already uses for a destructive press, confirm and all — and this one cannot be
        // undone, which is what the sentence says.
        assertTrue(form.contains("ghost-btn btn-sm danger"), "the delete weighs less than the button beside it")
        assertTrue(form.contains("data-confirm=\"Delete ${task.title}?"))
        assertTrue(form.contains("This cannot be undone"))
        // Not a block form: the card it names is leaving the page, so the route answers with the board it came
        // from rather than repainting a popup that has nothing left to show.
        assertFalse(form.contains("data-live-target"))
    }

    @Test
    fun `a delete answers on the board the card was opened from`() {
        // Every card offers management's Delete task since Stage 84, and the address it lands on is the board it was
        // opened from. **Stage 40** sent `allbuckets` beside that address so the wide backlog view came back with
        // the delete; **Stage 97** retired the view and the field, and `back` names the board alone — which on a
        // narrowed backlog is the bucket filter (Stage 93).
        val backlogId = TaskRepository.insert(
            kind = "OTHER", title = "Clean the spill bund", projectId = null, due = "2026-11-12",
            priority = "Medium", assigneeEmpNo = "EMP0002", status = "Not started", bucketId = null, started = "",
        )
        val backlog = TaskRepository.findById(backlogId)!!
        val full = render {
            taskFormModal(editFormOf(backlog, canEditAll = true, viewerEmpNo = "EMP0001"), BACK_OTHER)
        }
        val form = formPosting(full, "/app/assigned-tasks/delete")
        assertTrue(form.contains("name=\"back\" value=\"other\""), "the delete did not name the board it came from")
        assertFalse(form.contains("allbuckets"), "the delete still carries the retired wide view")
        assertFalse(full.contains("/app/tasks/archive"), "a backlog card was offered an archive it has no page for")
        val narrowed = render {
            taskFormModal(editFormOf(backlog, canEditAll = true, viewerEmpNo = "EMP0001"), "other:7")
        }
        assertTrue(
            formPosting(narrowed, "/app/assigned-tasks/delete").contains("name=\"back\" value=\"other:7\""),
            "a delete from a narrowed backlog answers on the full board",
        )
        // The route's own half of the same promise.
        assertEquals("/app/other-tasks", taskRedirect(BACK_OTHER, 0))
        assertEquals("/app/other-tasks?show=7", taskRedirect("other:7", 0))
    }

    @Test
    fun `nobody but management is offered the Delete task button`() {
        // The person a card belongs to, a reader with no part in it, and a project's own manager (who may write
        // the board but is not management): the route checks `canManage` whatever the markup offered, so none of
        // them is shown a button that would do nothing — and the old archive button is gone for all of them.
        listOf(
            editAsOwner(),
            edit(canManage = false, viewer = "EMP9999"),
            edit(canManage = false, viewer = "EMP0001", back = BACK_PROJECT, fixed = project),
        ).forEach { html ->
            assertFalse(html.contains("/app/tasks/archive"), "an archive reached a non-manager")
            assertFalse(html.contains("/app/assigned-tasks/delete"), "a delete reached a non-manager")
        }
        // The note box follows a different line, and Stage 86 is where the two part: the owner writes notes on
        // their own card ([TaskForm.mayNote]) while a stranger's popup — the project's manager's copy included —
        // is a log to read, with no box under it and no save above it.
        assertTrue(editAsOwner().contains("action=\"/app/tasks/notes/add\""), "the owner lost their note box")
        listOf(
            edit(canManage = false, viewer = "EMP9999"),
            edit(canManage = false, viewer = "EMP0001", back = BACK_PROJECT, fixed = project),
        ).forEach { html ->
            assertFalse(html.contains("action=\"/app/tasks/notes/add\""), "a stranger was handed the note box")
            assertFalse(html.contains("Save task"), "a stranger was handed a save")
        }
        // And a card being created has nothing yet to destroy.
        val fresh = generalCreate()
        assertFalse(fresh.contains("/app/tasks/archive"))
        assertFalse(fresh.contains("/app/assigned-tasks/delete"))
    }

    @Test
    fun `the delete button shares the note box's own foot line`() {
        val css = File("src/main/resources/static/css/app.css").readText()
        // The delete button is a sibling form — a form cannot live inside the note form — so it is the row, not
        // nesting, that puts it at the right end of the Add note line, bottoms aligned.
        listOf(
            ".note-foot { display: flex; align-items: flex-end; gap: var(--sp-2); }",
            ".note-foot .note-add { flex: 1 1 auto; min-width: 0; }",
            ".note-foot .task-del { flex: 0 0 auto; }",
        ).forEach { assertTrue(css.contains(it), "the note foot lost its rule: $it") }
        // `.ghost-btn` is written after `.btn-sm` in this file and would win the padding and the font size,
        // leaving a taller button standing beside a shorter one, so the delete button's metrics are spelled out.
        val metrics = css.substringAfter(".note-foot .task-del .ghost-btn {").substringBefore("}")
        val small = css.substringAfter(".submit.btn-sm, .btn-sm {").substringBefore("}")
        // Stage A named the sizes, so "the same size as the Add note button" is now a claim about two rules asking
        // for the same two tokens — plus the tokens still standing for the pixels they were drawn at, because two
        // buttons agreeing on `--sp-1` would agree and both be wrong.
        val borrowed = listOf("padding: var(--sp-1) var(--sp-3)", "font-size: var(--fs-8)")
        borrowed.forEach {
            assertTrue(metrics.contains(it), "the delete is not the Add note button's size: missing $it")
            assertTrue(small.contains(it), "the note box's own Add button no longer measures $it")
        }
        val ramp = File("src/main/resources/static/css/base.css").readText()
        listOf("--sp-3: 12px;", "--fs-8: 12.5px;").forEach {
            assertTrue(ramp.contains(it), "the scale step the pair is measured in changed: $it")
        }
        // And the markup really does wrap both of them in that row, note box first.
        val foot = edit(canManage = true).substringAfter("class=\"note-foot\"")
        assertTrue(
            foot.substringBefore(">Delete task<").contains("action=\"/app/tasks/notes/add\""),
            "Delete task is not beside the note box",
        )
    }

    @Test
    fun `long card titles and metadata stay within narrow cards`() {
        val css = File("src/main/resources/static/css/app.css").readText()
        val title = css.substringAfter(".ktitle {").substringBefore("}")
        listOf(
            "min-width: 0",
            "overflow-wrap: anywhere",
            "-webkit-line-clamp: 2",
            "overflow: hidden",
        ).forEach { assertTrue(title.contains(it), "card title is missing $it") }

        val meta = css.substringAfter(".kmeta {").substringBefore("}")
        assertTrue(meta.contains("flex-wrap: wrap"), "card metadata cannot wrap")
        assertTrue(meta.contains("min-width: 0"), "card metadata can force its column wider")

        val html = render { taskKanban(TaskService.assigned(), manager, canManage = true, BACK_ASSIGNED) }
        assertTrue(
            html.contains("<div class=\"ktitle\" title=\"Spread base course\">Spread base course</div>"),
            "the full title is not available on hover",
        )
    }
}
