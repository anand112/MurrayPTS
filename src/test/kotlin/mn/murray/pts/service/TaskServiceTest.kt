package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.BOARD_OTHER
import mn.murray.pts.domain.BOARD_PROJECT
import mn.murray.pts.domain.ChecklistItem
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.BUCKET_NAME_MAX
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.PROJECT_BUCKET_MAX
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.ProjectBucket
import mn.murray.pts.domain.TaskItem
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ChecklistRepository
import mn.murray.pts.repository.NoteRepository
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The rules behind the task popup — the editor, the checklist and the running log — against a real
 * SQLite file with the real Flyway schema, the same harness as [ProjectServiceTest].
 *
 * The board hides and greys controls, but that is only presentation: what matters is that the service
 * refuses the same POST when it arrives without those controls, and that it takes the fields a caller
 * may not write from the stored row rather than from the form. Each rejection is asserted on the field
 * key it is attached to, because the popup prints the sentence under that input and stays open.
 */
class TaskServiceTest {

    private lateinit var ds: HikariDataSource

    private val haulRoad = "Haul road widening"

    /** A second project, used by the rules that have to tell two boards apart. */
    private val bay = "Workshop bay rewiring"

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "tasks.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"))
        val id = ProjectRepository.insert(
            code = "S-01", name = haulRoad, status = "Ongoing", progress = 0,
            managerEmpNo = "EMP0001", due = "2027-03-30", type = "Site projects",
        )
        // Stage 48: every project board carries the six, and a project card has to name one of them, so the
        // harness seeds them the way [ProjectService.create] does — before any card is written.
        ProjectBucketRepository.seedDefaults(id)
    }

    @AfterEach
    fun tearDown() = ds.close()

    /**
     * The form a card is written with.
     *
     * [lane] is the **name** the popup posts (Stage 48), because that is what the field carries: the six
     * sub-categories are the same six on every project, so a name is the answer a form can post without
     * knowing this project's lane ids. It defaults to one of them so the tests about everything else — dates,
     * assignees, plans — describe a card that is actually filed, and a test that wants the other answers says
     * so: `lane = null` is a form that posted no field at all (a greyed select), and `lane = ""` is the
     * placeholder a reader left chosen.
     */
    private fun draft(
        title: String = "Spread base course",
        project: String = haulRoad,
        assignee: String = "EMP0002",
        started: String = "2026-11-01",
        due: String = "2026-11-12",
        priority: String = "High",
        status: String = "Not started",
        lane: String? = "Site projects",
    ) = TaskDraft(
        title = title, projectName = project, assigneeEmpNo = assignee,
        started = started, due = due, priority = priority, status = status,
        projectLane = lane,
    )

    // --- creating ------------------------------------------------------------------------

    @Test
    fun `a manager creates a card in the column it was submitted with`() {
        val id = TaskService.create(draft(status = "In progress"), "EMP0001", true).taskId
        assertNotNull(id)
        val task = TaskRepository.findById(id!!)!!
        assertEquals("In progress", task.status)
        assertEquals(haulRoad, task.project)
        assertEquals("EMP0002", task.assignee)
        assertEquals("High", task.priority)
    }

    @Test
    fun `a normal account cannot create one, and nothing is written`() {
        val result = TaskService.create(draft(), "EMP0002", false)
        assertNull(result.taskId)
        assertEquals("Only management or this project's manager can add tasks.", result.error(TaskField.PERMISSION))
        assertTrue(TaskService.assigned().isEmpty())
    }

    @Test
    fun `the account a project names as its manager writes its first card`() {
        val id = TaskService.create(draft(), "EMP0001", canManage = false, pageProjectId = projectId()).taskId
        assertNotNull(id, "the project's own manager was refused a card on it")
        val task = TaskRepository.findById(id!!)!!
        assertEquals(haulRoad, task.project)
        assertEquals("EMP0002", task.assignee)
    }

    @Test
    fun `a manager's new card is pinned to the page it came from, not to the form`() {
        // The popup they are shown lists one project and greys it, but the check is the service's: a POST
        // aimed at another project — by name, or at one that does not exist — still lands here.
        val id = TaskService.create(
            draft(project = "Somebody else's project"), "EMP0001", canManage = false, pageProjectId = projectId(),
        ).taskId
        assertNotNull(id)
        assertEquals(haulRoad, TaskRepository.findById(id!!)!!.project)
    }

    @Test
    fun `a project's manager is not management on the general board`() {
        val result = TaskService.create(draft(), "EMP0001", canManage = false)
        assertNull(result.taskId)
        assertNotNull(result.error(TaskField.PERMISSION))
        assertTrue(TaskService.assigned().isEmpty())
    }

    @Test
    fun `the right to add a card is management, or that project's manager, and nobody else`() {
        val id = projectId()
        val unmanaged = ProjectRepository.insert(
            code = "M-01", name = "Blast design review", status = "Not started", progress = 0,
            managerEmpNo = null, due = "", type = "Mine plans",
        )
        assertTrue(TaskService.mayAddTasks(id, "EMP0001", canManage = false))
        assertTrue(TaskService.mayAddTasks(unmanaged, "EMP0001", canManage = true), "management asks nowhere")
        assertFalse(TaskService.mayAddTasks(id, "EMP0002", canManage = false))
        assertFalse(TaskService.mayAddTasks(unmanaged, "EMP0001", canManage = false), "a vacancy appoints somebody")
        assertFalse(TaskService.mayAddTasks(0, "EMP0001", canManage = false), "no project granted a right")
    }

    /** The project the harness seeds, resolved the way the routes resolve a posted one. */
    private fun projectId(): Int = ProjectRepository.idByName().getValue(haulRoad)

    @Test
    fun `a blank title keeps the popup open on the title field`() {
        val result = TaskService.create(draft(title = "   "), "EMP0001", true)
        assertNull(result.taskId)
        assertEquals("Give the task a title.", result.error(TaskField.TITLE))
        assertEquals("   ", result.values.title)   // what was typed comes back untouched
    }

    @Test
    fun `a card names a project that exists, or it is not written`() {
        val wrong = TaskService.create(draft(project = "No such project"), "EMP0001", true)
        assertNull(wrong.taskId)
        assertEquals("Choose a project that exists.", wrong.error(TaskField.PROJECT))

        // Stage 48 closed the other half of this. A card used to be allowed onto a board with no project on it,
        // which meant it printed nowhere: neither on a project's lanes nor on the backlog, because the backlog
        // is decided by `kind` and this row had the assigned kind with nothing to hang a column on. The picker's
        // blank option is gone and the route refuses it, so a card is on a project or it is in the backlog.
        val none = TaskService.create(draft(project = ""), "EMP0001", true)
        assertNull(none.taskId, "a card belonging to neither world was written")
        assertEquals("Choose which project this card is on.", none.error(TaskField.PROJECT))
        assertTrue(TaskService.assigned().isEmpty(), "the refusal wrote a row anyway")
    }

    @Test
    fun `an assignee has to be on the roster`() {
        val result = TaskService.create(draft(assignee = "EMP9999"), "EMP0001", true)
        assertNull(result.taskId)
        assertEquals("That employee is not on the team roster.", result.error(TaskField.ASSIGNEE))
    }

    // --- editing -------------------------------------------------------------------------

    @Test
    fun `a manager can rewrite a card, its column included`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val result = TaskService.updateAssigned(
            id,
            draft(title = "Compact sub-base", assignee = "EMP0001", due = "2026-12-24", priority = "Low", status = "Blocked"),
            "EMP0001",
            true,
        )
        assertEquals(id, result.taskId)
        val task = TaskRepository.findById(id)!!
        assertEquals("Compact sub-base", task.title)
        assertEquals("EMP0001", task.assignee)
        assertEquals("2026-12-24", task.due)
        assertEquals("Low", task.priority)
        assertEquals("Blocked", task.status)
    }

    @Test
    fun `an owner moves their own dates and priority, and nothing else on the card`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val result = TaskService.updateAssigned(
            id,
            draft(
                title = "Hijacked", project = "", assignee = "EMP0001", status = "Completed",
                started = "2026-11-05", due = "2026-11-30", priority = "Low",
            ),
            "EMP0002",
            false,
        )
        assertEquals(id, result.taskId, "the owner was refused the whole edit")
        val task = TaskRepository.findById(id)!!
        // The three fields that book time on a card they are working went through …
        assertEquals("2026-11-05", task.started)
        assertEquals("2026-11-30", task.due)
        assertEquals("Low", task.priority)
        // … and the four that move work between people came back from the row, not from the form. A
        // hand-built POST arrives with all seven fields filled, which is why the rule reads the stored
        // values rather than trusting what the greyed inputs left out.
        assertEquals("Spread base course", task.title)
        assertEquals(haulRoad, task.project)
        assertEquals("EMP0002", task.assignee)
        assertEquals("Not started", task.status)
    }

    @Test
    fun `a stranger cannot edit a card they can see on a shared page`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val result = TaskService.updateAssigned(id, draft(due = "2026-12-01"), "EMP9999", false)
        assertNull(result.taskId)
        assertEquals(
            "Only management or the person this task is assigned to can change it.",
            result.error(TaskField.PERMISSION),
        )
        assertEquals("2026-11-12", TaskRepository.findById(id)!!.due, "a refused edit still moved the date")
    }

    @Test
    fun `an owner's rejected date comes back with the greyed fields still filled`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val result = TaskService.updateAssigned(id, draft(due = "12/2026"), "EMP0002", false)
        assertNull(result.taskId)
        assertEquals("Use the date picker (yyyy-mm-dd).", result.error(TaskField.DUE))
        // What the popup re-renders is what the server tried to write, so the disabled inputs print the
        // row's own values instead of the blanks they posted.
        assertEquals("Spread base course", result.values.title)
        assertEquals(haulRoad, result.values.projectName)
        assertEquals("EMP0002", result.values.assigneeEmpNo)
        assertEquals("12/2026", result.values.due, "what the owner typed was thrown away")
    }

    @Test
    fun `an edit of a card that is already gone says so on the title`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        TaskRepository.delete(id)
        val result = TaskService.updateAssigned(id, draft(), "EMP0001", true)
        assertNull(result.taskId)
        assertEquals("That task no longer exists.", result.error(TaskField.TITLE))
    }

    @Test
    fun `a bad date is refused before anything is written`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val result = TaskService.updateAssigned(id, draft(due = "12/2026"), "EMP0001", true)
        assertNull(result.taskId)
        assertEquals("Use the date picker (yyyy-mm-dd).", result.error(TaskField.DUE))
        assertEquals("2026-11-12", TaskRepository.findById(id)!!.due)
    }

    @Test
    fun `an invented priority falls back to the list the popup offers`() {
        val result = TaskService.create(draft(priority = "Urgent!!!"), "EMP0001", true)
        assertNull(result.taskId)
        assertEquals("Choose a priority from the list.", result.error(TaskField.PRIORITY))
    }

    // --- who may open the editor -------------------------------------------------------------

    @Test
    fun `management opens any card, an owner only their own, and a typed id opens nothing`() {
        val mine = TaskService.create(draft(), "EMP0001", true).taskId!!
        val theirs = TaskService.create(
            draft(title = "Plan the shutdown", assignee = "EMP0001"), "EMP0001", true
        ).taskId!!
        assertNotNull(TaskService.editableBy(mine, "EMP0002", canManage = false))
        assertNull(TaskService.editableBy(theirs, "EMP0002", canManage = false))
        // A manager reading their own card is a worker on it, so the assignment alone is enough too.
        assertNotNull(TaskService.editableBy(theirs, "EMP0001", canManage = false))
        assertNotNull(TaskService.editableBy(mine, "EMP0001", canManage = true))
        // A card visible on a shared project page is not the reader's to open, and a hand-typed
        // `?edit=` for a card that has gone comes back as the plain page.
        assertNull(TaskService.editableBy(mine, "EMP9999", canManage = false))
        assertNull(TaskService.editableBy(404, "EMP0001", canManage = true))
    }

    // --- deleting ------------------------------------------------------------------------

    @Test
    fun `a manager deletes a card and the board no longer lists it`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        assertTrue(TaskService.deleteAssigned(id, "EMP0001", true))
        assertNull(TaskRepository.findById(id))
    }

    @Test
    fun `a normal account cannot delete, not even someone's own card`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        assertFalse(TaskService.deleteAssigned(id, "EMP0002", false))
        assertNotNull(TaskRepository.findById(id))
    }

    @Test
    fun `deleting a card that is already gone reports nothing was removed`() {
        assertFalse(TaskService.deleteAssigned(404, "EMP0001", true))
    }

    // --- dragging (the rule the board already had) ----------------------------------------

    @Test
    fun `the assignee may drag their own card but a stranger may not`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        assertTrue(TaskService.updateStatus(id, "In progress", "EMP0002", false))
        assertEquals("In progress", TaskRepository.findById(id)!!.status)
        assertFalse(TaskService.updateStatus(id, "Completed", "EMP9999", false))
        assertEquals("In progress", TaskRepository.findById(id)!!.status)
    }

    // --- who is allowed to see which cards ---------------------------------------------------

    @Test
    fun `management sees the whole board and everybody else only their own cards`() {
        TaskService.create(draft(), "EMP0001", true)                                  // EMP0002's card
        TaskService.create(
            draft(title = "Plan the shutdown", assignee = "EMP0001"), "EMP0001", true // the manager's own
        )
        assertEquals(2, TaskService.assigned().size, "the board itself was filtered")
        assertEquals(
            listOf("Spread base course", "Plan the shutdown"),
            TaskService.assignedFor("EMP0001", canManage = true).map { it.title },
        )
        assertEquals(
            listOf("Spread base course"),
            TaskService.assignedFor("EMP0002", canManage = false).map { it.title },
        )
        assertEquals(
            listOf("Plan the shutdown"),
            TaskService.assignedFor("EMP0001", canManage = false).map { it.title },
            "a manager with the switch off still saw the whole board",
        )
    }

    @Test
    fun `an account with nothing assigned gets an empty board, not somebody else's`() {
        TaskService.create(draft(), "EMP0001", true)
        assertTrue(TaskService.assignedFor("EMP9999", canManage = false).isEmpty())
        // A manager who has no cards of their own still runs the team from this page.
        assertEquals(1, TaskService.assignedFor("EMP0001", canManage = true).size)
    }

    // --- the checklist under a card ---------------------------------------------------------

    @Test
    fun `management writes the steps and the card carries them in the order they were added`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        assertNotNull(TaskService.addChecklistItem(id, "Set out the haul road", "EMP0001", true))
        val second = TaskService.addChecklistItem(id, "  Compact the sub-base  ", "EMP0001", true)
        assertNotNull(second)
        assertEquals(
            listOf("Set out the haul road", "Compact the sub-base"),
            ChecklistRepository.itemsOf(id).map { it.label },
            "steps are not in append order or the typed spaces were not trimmed",
        )
        assertTrue(ChecklistRepository.itemsOf(id).none { it.done }, "a new step starts ticked")
        // This is the list the card renders, so it has to arrive with the card.
        val task = TaskRepository.findById(id)!!
        assertEquals(2, task.checklist.size)
        assertEquals(0, task.checklistDone)
    }

    @Test
    fun `a normal account cannot write the list, even on their own card`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val step = TaskService.addChecklistItem(id, "Manager step", "EMP0001", true)!!
        assertNull(TaskService.addChecklistItem(id, "My own step", "EMP0002", false))
        assertFalse(TaskService.removeChecklistItem(step, "EMP0002", false))
        assertEquals(listOf("Manager step"), ChecklistRepository.itemsOf(id).map { it.label })
        // …but they do work the list management wrote for them.
        assertTrue(TaskService.toggleChecklistItem(step, "EMP0002", false))
    }

    @Test
    fun `nothing is written for a blank step, a long one or a card that is gone`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        assertNull(TaskService.addChecklistItem(id, "   ", "EMP0001", true))
        assertNull(
            TaskService.addChecklistItem(id, "x".repeat(TaskService.CHECKLIST_LABEL_MAX + 1), "EMP0001", true)
        )
        assertNull(TaskService.addChecklistItem(404, "A real step", "EMP0001", true))
        assertTrue(ChecklistRepository.itemsOf(id).isEmpty())
        // The length the form enforces with maxlength is exactly the longest one that is stored.
        assertNotNull(
            TaskService.addChecklistItem(id, "x".repeat(TaskService.CHECKLIST_LABEL_MAX), "EMP0001", true)
        )
    }

    @Test
    fun `the assignee ticks their own step and nobody else may`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val step = TaskService.addChecklistItem(id, "Set out the haul road", "EMP0001", true)!!
        assertTrue(TaskService.toggleChecklistItem(step, "EMP0002", false))
        assertTrue(ChecklistRepository.find(step)!!.done)
        assertFalse(TaskService.toggleChecklistItem(step, "EMP9999", false), "a stranger unticked it")
        assertTrue(ChecklistRepository.find(step)!!.done, "a refused tick still changed the row")
        assertFalse(TaskService.toggleChecklistItem(404, "EMP0002", false))
    }

    @Test
    fun `management works any card's list and a second tick unticks it`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val step = TaskService.addChecklistItem(id, "Survey the start line", "EMP0001", true)!!
        assertTrue(TaskService.toggleChecklistItem(step, "EMP0001", true))
        assertEquals(1, TaskRepository.findById(id)!!.checklistDone)
        assertTrue(TaskService.toggleChecklistItem(step, "EMP0001", true))
        assertFalse(ChecklistRepository.find(step)!!.done)
    }

    @Test
    fun `taking a step off leaves the rest in place`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val first = TaskService.addChecklistItem(id, "First", "EMP0001", true)!!
        TaskService.addChecklistItem(id, "Second", "EMP0001", true)
        assertTrue(TaskService.removeChecklistItem(first, "EMP0001", true))
        assertNull(ChecklistRepository.find(first))
        assertEquals(listOf("Second"), ChecklistRepository.itemsOf(id).map { it.label })
        assertFalse(TaskService.removeChecklistItem(first, "EMP0001", true), "a gone step reported removal")
        assertFalse(TaskService.removeChecklistItem(404, "EMP0001", true))
    }

    @Test
    fun `deleting the card takes its steps with it`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val step = TaskService.addChecklistItem(id, "Set out the haul road", "EMP0001", true)!!
        assertTrue(TaskService.deleteAssigned(id, "EMP0001", true))
        assertNull(ChecklistRepository.find(step), "the step outlived its card")
        // A tick on the row that used to hang off it cannot report success.
        assertFalse(TaskService.toggleChecklistItem(step, "EMP0002", false))
    }

    // --- the running log under a card ---------------------------------------------------------

    @Test
    fun `the owner and management both write the log and the newest entry reads first`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val byOwner = TaskService.addNote(id, "Broke through the wet layer.", "EMP0002", false)
        val byManager = TaskService.addNote(id, "  Compaction re-test on Monday.  ", "EMP0001", true)
        assertNotNull(byOwner)
        assertNotNull(byManager)
        // Read with the card, exactly like its steps, so a popup render never asks for the log again.
        val notes = TaskRepository.findById(id)!!.notes
        assertEquals(2, notes.size, "the log does not arrive with the card")
        assertEquals("Compaction re-test on Monday.", notes.first().body, "the log reads oldest first")
        assertEquals("Broke through the wet layer.", notes.last().body)
        assertEquals("EMP0001", notes.first().author, "the entry lost who wrote it")
        // The day is stamped by the server, so nothing here lets a caller backdate an entry.
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), notes.first().created)
    }

    @Test
    fun `nobody else writes the log, and a blank or over-long entry is refused`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        assertNull(TaskService.addNote(id, "Looking in", "EMP9999", false), "a stranger wrote on the card")
        assertNull(TaskService.addNote(id, "   ", "EMP0002", false))
        assertNull(TaskService.addNote(id, "x".repeat(TaskService.NOTE_MAX + 1), "EMP0002", false))
        assertNull(TaskService.addNote(404, "On a card that is gone", "EMP0001", true))
        assertTrue(TaskRepository.findById(id)!!.notes.isEmpty(), "a refused note still wrote a row")
        // The maxlength the box carries and the number the service enforces are the same one.
        assertNotNull(TaskService.addNote(id, "x".repeat(TaskService.NOTE_MAX), "EMP0002", false))
    }

    @Test
    fun `only management can take an entry off the log`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val mine = TaskService.addNote(id, "My own note", "EMP0002", false)!!
        val theirs = TaskService.addNote(id, "Management's note", "EMP0001", true)!!
        // Deliberately stricter than writing: an author deleting their own entry would leave the record
        // looking as though it was never made, so they ask management instead.
        assertFalse(TaskService.removeNote(mine, "EMP0002", false))
        assertNotNull(NoteRepository.find(mine))
        assertTrue(TaskService.removeNote(mine, "EMP0002", true))
        assertNull(NoteRepository.find(mine))
        assertFalse(TaskService.removeNote(mine, "EMP0001", true), "a gone note reported deletion")
        assertFalse(TaskService.removeNote(404, "EMP0001", true))
        assertNotNull(NoteRepository.find(theirs), "deleting one entry took another with it")
    }

    @Test
    fun `deleting the card takes its notes with it`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val note = TaskService.addNote(id, "Compaction passed", "EMP0002", false)!!
        assertTrue(TaskService.deleteAssigned(id, "EMP0001", true))
        assertNull(NoteRepository.find(note), "the note outlived its card")
        assertFalse(TaskService.removeNote(note, "EMP0001", true))
    }

    // --- cards built by hand ------------------------------------------------------------------

    /**
     * A card with no row behind it. The percentage rules below count the list they are handed and never
     * query, so they are testable without a board — the tests that do write rows are the ones about what
     * a board contains.
     */
    private fun card(
        title: String,
        status: String = "Not started",
        due: String = "",
        assignee: String = "EMP0002",
        steps: Int = 0,
        stepsDone: Int = 0,
        project: String = haulRoad,
    ) = TaskItem(
        title = title, project = project, due = due, priority = "Medium",
        assignee = assignee, status = status,
        checklist = (0 until steps).map { ChecklistItem(id = it, label = "Step ${it + 1}", done = it < stepsDone) },
    )

    // --- what the checklists measure ---------------------------------------------------------

    @Test
    fun `one tick in four steps is 25 percent on the card`() {
        assertEquals(25, card("A", steps = 4, stepsDone = 1).progressPercent)
        // An untouched plan is a real 0% — somebody wrote the steps and none is done. It is the *absent*
        // plan that must not read as zero, and that is the next test.
        assertEquals(0, card("B", steps = 4).progressPercent)
        assertEquals(100, card("C", steps = 4, stepsDone = 4).progressPercent)
        // To the nearest whole percent, which is what a card that says "25%" has to promise.
        assertEquals(33, card("D", steps = 3, stepsDone = 1).progressPercent)
        assertEquals(67, card("E", steps = 3, stepsDone = 2).progressPercent)
    }

    @Test
    fun `a card nobody wrote steps for is unmeasured, not at zero`() {
        assertNull(card("Bare").progressPercent, "an unplanned card read as 0% done")
    }

    @Test
    fun `a project averages its cards equally, not their steps`() {
        // 5 of 10 and 2 of 2 are two cards at 50% and 100%, so the project is at 75%. Counting steps
        // instead would answer 7 of 12 and let one card somebody broke into ten pieces outvote a
        // colleague's that they left as one.
        val cards = listOf(card("Big", steps = 10, stepsDone = 5), card("Small", steps = 2, stepsDone = 2))
        assertEquals(75, TaskService.progressOf(cards))
    }

    @Test
    fun `an unplanned card is left out of a project's average entirely`() {
        assertEquals(25, TaskService.progressOf(listOf(card("Plan", steps = 4, stepsDone = 1), card("No plan"))))
        assertNull(
            TaskService.progressOf(listOf(card("No plan"), card("Nor this"))),
            "a project of unplanned cards claimed a percentage nobody measured",
        )
        assertNull(TaskService.progressOf(emptyList()), "an empty project measured something")
    }

    @Test
    fun `each project is measured from its own cards and no other`() {
        val cards = listOf(
            card("A", steps = 2, stepsDone = 1),
            card("B", steps = 2, stepsDone = 2),
            card("C", project = bay, steps = 4, stepsDone = 1),
            // Unplanned, and therefore not a 0% dragging the bay down to 12.5.
            card("D", project = bay),
        )
        assertEquals(mapOf(haulRoad to 75, bay to 25), TaskService.progressByProject(cards))
    }

    @Test
    fun `a project nothing can measure keeps the number stored on its row`() {
        val unplanned = Project(id = 7, code = "F-01", name = "Office redecorating", status = "Ongoing", progress = 40, manager = "", due = "")
        val measured = Project(id = 8, code = "F-02", name = haulRoad, status = "Ongoing", progress = 40, manager = "", due = "")
        val board = mapOf(haulRoad to 75)
        assertEquals(40, TaskService.displayedProgress(unplanned, board), "a project with no plan lost its own figure")
        assertEquals(75, TaskService.displayedProgress(measured, board), "the stored number still won where the checklists measured")
    }

    @Test
    fun `ticking a step on the board moves its project's measurement`() {
        // The route hands the page [TaskService.progressByProject] with no list of its own, so this is
        // the whole read path: cards off the board, their steps loaded with them, averaged per project.
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        val first = TaskService.addChecklistItem(id, "Set out the alignment", "EMP0001", true)!!
        TaskService.addChecklistItem(id, "Order the aggregate", "EMP0001", true)
        assertEquals(0, TaskService.progressByProject()[haulRoad], "a fresh plan measured something it had not done")
        assertTrue(TaskService.toggleChecklistItem(first, "EMP0001", true))
        assertEquals(50, TaskService.progressByProject()[haulRoad], "the tick never reached the project's number")
    }

    // --- the lanes a project's board is cut by (V7) ------------------------------------------------

    /** The demo project's id, which every lane below hangs off. */
    private fun haulRoadId(): Int = ProjectRepository.findAll().first { it.name == haulRoad }.id

    /**
     * This project's six lanes, seeded the way [ProjectService.create] seeds a project it just wrote.
     *
     * Seeded on first use rather than in [setUp] so the rules above stay about what they test: none of them
     * reads a lane row, and the cards they write name a lane by **name**, which is what the popup posts.
     */
    private fun lanes(): List<ProjectBucket> {
        val id = haulRoadId()
        if (TaskService.lanesOf(id).isEmpty()) ProjectBucketRepository.seedDefaults(id)
        return TaskService.lanesOf(id)
    }

    /** A second project with a board of its own, for the rules that have to tell two sets of lanes apart. */
    private fun otherProject(): Int {
        val id = ProjectRepository.findAll().firstOrNull { it.name == bay }?.id ?: ProjectRepository.insert(
            code = "M-09", name = bay, status = "Ongoing", progress = 0,
            managerEmpNo = "EMP0001", due = "2027-03-30", type = "Manufacturing",
        )
        if (TaskService.lanesOf(id).isEmpty()) ProjectBucketRepository.seedDefaults(id)
        return id
    }

    @Test
    fun `a project starts with the six shared lanes and an extra is appended to its own board`() {
        assertEquals(DEFAULT_PROJECT_BUCKETS, lanes().map { it.name })
        val added = TaskService.addLane(haulRoadId(), "Haul", "EMP0001", canManage = true)
        assertTrue(added.added, "a lane name nobody objected to was refused")
        // Stage 48: the six are the same six on every board, and a lane a project adds itself rides at the end
        // of them rather than replacing one.
        assertEquals(
            DEFAULT_PROJECT_BUCKETS + "Haul",
            TaskService.lanesOf(haulRoadId()).map { it.name },
        )
        // The order is the order of the columns on the board, so a new lane lands at the right end.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), TaskService.lanesOf(haulRoadId()).map { it.position })
        // Somebody else's board is untouched: an extra lane belongs to one project, even though the six do not.
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(otherProject()).map { it.name })
    }

    @Test
    fun `a lane name that is blank, over-long or already on this board is refused`() {
        val id = haulRoadId()
        lanes()
        assertEquals(LaneError.BLANK, TaskService.addLane(id, "   ", "EMP0001", canManage = true).error)
        assertEquals(
            LaneError.TOO_LONG,
            TaskService.addLane(id, "x".repeat(PROJECT_BUCKET_MAX + 1), "EMP0001", canManage = true).error,
        )
        // Trimmed and case-compared before it is checked, so the spaces and the capital a person leaves
        // behind do not become a second column of the same thing.
        assertEquals(
            LaneError.DUPLICATE,
            TaskService.addLane(id, " site PROJECTS ", "EMP0001", canManage = true).error,
        )
        assertEquals(6, TaskService.lanesOf(id).size, "a refused lane wrote a column anyway")
    }

    @Test
    fun `only management or this project's manager may name a lane`() {
        val id = haulRoadId()
        // EMP0002 works a card on this project; the account it names as manager is EMP0001.
        assertEquals(LaneError.DENIED, TaskService.addLane(id, "Survey", "EMP0002", canManage = false).error)
        // The manager may, with no elevated role at all — the same right that lets them write cards here.
        assertTrue(TaskService.addLane(id, "Survey", "EMP0001", canManage = false).added)
        // And a project that is gone is refused before its name is even read, so nothing is left pointing
        // at a board nobody will open again.
        assertEquals(LaneError.NO_PROJECT, TaskService.addLane(4242, "Survey", "EMP0001", canManage = true).error)
    }

    @Test
    fun `a new card lands in the lane its form names, and the project's manager drags it from there`() {
        val fabrication = lanes().first()
        val id = TaskService.create(draft(), "EMP0001", canManage = true).taskId!!
        // Stage 48: the card is filed where the form said. A name is what the popup posts, because the six are
        // the same six on every board, and this is the answer the general board's popup gives.
        val named = lanes().first { it.name == "Site projects" }
        assertEquals(named.id, TaskRepository.findById(id)!!.projectBucketId, "the lane the form named was not kept")
        // Stage 49: the mover is the account that may write onto this project, and EMP0001 is that account with
        // no elevated role at all — the project names them as its manager.
        assertTrue(TaskService.moveToLane(id, fabrication.id, "EMP0001", canManage = false))
        val moved = TaskRepository.findById(id)!!
        assertEquals(fabrication.id, moved.projectBucketId)
        assertEquals(
            "Fabrication/Manufacturing",
            moved.projectBucket,
            "the board cannot name the column the card is in",
        )
    }

    @Test
    fun `a project card with no lane named is refused, and nothing is written`() {
        lanes()
        // The field absent from the form: a create has no stored lane to keep, so this is the popup that never
        // asked — the hole the picker's blank option used to fall through.
        val absent = TaskService.create(draft(lane = null), "EMP0001", canManage = true)
        assertNull(absent.taskId, "an unfiled project card was created")
        assertEquals("Choose which part of this project the card is on.", absent.error(TaskField.LANE))
        // The placeholder itself, posted as a word: the same refusal, since Unsorted is where the cards that
        // arrived before this rule rest and not a place to send one.
        val blank = TaskService.create(draft(lane = ""), "EMP0001", canManage = true)
        assertNull(blank.taskId, "the picker's empty option filed a card")
        assertEquals(TaskField.LANE, blank.errors.keys.first())
        assertTrue(TaskService.assigned().isEmpty(), "a refused create wrote rows anyway")
    }

    @Test
    fun `a lane name that is not on this project's board is refused`() {
        lanes()
        // The six carry the same names on every board, so a name cannot borrow one project's standard column
        // from another; only a lane a project added for itself can be foreign, and that is the case the lookup
        // has to catch. The check is made against the project the form resolved to, never against the name.
        val extra = TaskService.addLane(otherProject(), "Haul", "EMP0001", canManage = true).bucket!!
        val id = TaskService.create(draft(), "EMP0001", canManage = true).taskId!!
        val refused = TaskService.updateAssigned(id, draft(lane = extra.name), "EMP0001", canManage = true)
        assertNull(refused.taskId)
        assertTrue(refused.errors.containsKey(TaskField.LANE), "a borrowed lane was accepted: ${refused.errors}")
        // And a name that is simply not a lane at all, however well it is spelled.
        val nonsense = TaskService.updateAssigned(id, draft(lane = "Something nobody named"), "EMP0001", true)
        assertNull(nonsense.taskId)
        assertTrue(nonsense.errors.containsKey(TaskField.LANE), "an invented lane was accepted: ${nonsense.errors}")
    }

    @Test
    fun `a stranger cannot file a card, nor file one on somebody else's lane`() {
        val fabrication = lanes().first()
        val id = TaskService.create(draft(), "EMP0001", canManage = true).taskId!!
        val whereItStarted = TaskRepository.findById(id)!!.projectBucketId
        TeamRepository.upsert(Member("EMP0003", "Naran", "Survey", "Surveyor"))
        assertFalse(TaskService.moveToLane(id, fabrication.id, "EMP0003", canManage = false))
        // **The card's own assignee is one of those strangers now** (Stage 49). They work this card — its dates,
        // its priority, its own Done button — but which lane of the project it sits in is a statement about the
        // project's plan, and the account that owns the plan is the one that gets to change it.
        assertFalse(TaskService.moveToLane(id, fabrication.id, "EMP0002", canManage = false))
        // A lane of another project is not a place on this card's board, even for management: the lane id a
        // drop posts is the browser's word for where the card went, and the board it came from is checkable.
        val foreign = TaskService.lanesOf(otherProject()).first()
        assertFalse(TaskService.moveToLane(id, foreign.id, "EMP0001", canManage = true))
        assertFalse(TaskService.moveToLane(id, 4242, "EMP0001", canManage = true))
        assertEquals(whereItStarted, TaskRepository.findById(id)!!.projectBucketId, "a refused move wrote a lane")
    }

    @Test
    fun `the project's manager moves its cards without being management`() {
        // The two halves of [mayAddTasks] read the same way here as they do on the page's `+ Add task` lines:
        // management anywhere, and on this board the one account `projects.manager_emp_no` names — whatever the
        // card itself says about who is doing the work.
        val fabrication = lanes().first()
        val id = TaskService.create(draft(), "EMP0001", canManage = true).taskId!!
        assertTrue(TaskService.moveToLane(id, fabrication.id, "EMP0001", canManage = false))
        assertEquals(fabrication.id, TaskRepository.findById(id)!!.projectBucketId)
        // A project that names nobody is management's board alone. `null` rather than a blank, because the
        // column is a key into the roster and SQLite's foreign keys read "" as a person who is not in it.
        ProjectRepository.setManager(projectId(), null)
        assertFalse(TaskService.moveToLane(id, lanes().last().id, "EMP0001", canManage = false))
        assertTrue(TaskService.moveToLane(id, lanes().last().id, "EMP0001", canManage = true))
    }

    @Test
    fun `the Unsorted column is not somewhere a card can be sent`() {
        val fabrication = lanes().first()
        val id = TaskService.create(draft(), "EMP0001", canManage = true).taskId!!
        // Asked of a mover (Stage 49), so the refusal below is about the column and not about the caller.
        assertTrue(TaskService.moveToLane(id, fabrication.id, "EMP0001", canManage = false))
        // The column is still printed for the cards that arrived before this rule, and it is the state a legacy
        // row reads as — but a drag onto it would take a card off every column on the board, so it is refused.
        assertFalse(TaskService.moveToLane(id, 0, "EMP0001", canManage = false))
        assertEquals(fabrication.id, TaskRepository.findById(id)!!.projectBucketId, "a refused drag un-filed the card")
    }

    @Test
    fun `a save that says nothing about a lane leaves the card where it is`() {
        val fabrication = lanes().first()
        val id = TaskService.create(draft(lane = fabrication.name), "EMP0001", canManage = true).taskId!!
        // An assignee's popup greys the lane, and a disabled select posts nothing at all: absent is not the same
        // answer as the empty option, and a due date saved there must not clear the card's column.
        val result = TaskService.updateAssigned(
            id, draft(due = "2026-11-30", lane = null), "EMP0002", canManage = false,
        )
        assertEquals(id, result.taskId, "${result.errors}")
        assertEquals(fabrication.id, TaskRepository.findById(id)!!.projectBucketId)
        // Their form names no project either; both answers come back from the row.
        val theirs = TaskService.updateAssigned(
            id, draft(project = bay, status = "Blocked", lane = null), "EMP0002", canManage = false,
        )
        assertEquals(id, theirs.taskId, "${theirs.errors}")
        assertEquals(fabrication.id, TaskRepository.findById(id)!!.projectBucketId, "an owner refiled their own card")
    }

    @Test
    fun `management moves a card to the lane the popup names`() {
        val lanes = lanes()
        val repair = lanes.first { it.name == "Repair/Maintenance" }
        val id = TaskService.create(draft(), "EMP0001", canManage = true).taskId!!
        val moved = TaskService.updateAssigned(id, draft(lane = repair.name), "EMP0001", canManage = true)
        assertEquals(id, moved.taskId, "${moved.errors}")
        assertEquals(repair.id, TaskRepository.findById(id)!!.projectBucketId)
        // Leaving the field alone is not the same as naming the placeholder: an edit that posts no lane keeps the
        // card where it stands, because the board it is on did not change.
        val untouched = TaskService.updateAssigned(id, draft(priority = "Low", lane = null), "EMP0001", true)
        assertEquals(id, untouched.taskId, "${untouched.errors}")
        assertEquals(repair.id, TaskRepository.findById(id)!!.projectBucketId, "a save moved a card nobody redirected")
    }

    @Test
    fun `a card handed to another project is filed on the new board, not left in a lane of the old one`() {
        val fabrication = lanes().first()
        val id = TaskService.create(draft(lane = fabrication.name), "EMP0001", canManage = true).taskId!!
        val bayId = otherProject()
        // Both boards print the six, so a name is the whole answer: the card lands in the new project's own
        // column of that name, which is a different row from the one it left.
        val result = TaskService.updateAssigned(id, draft(project = bay), "EMP0001", canManage = true)
        assertEquals(id, result.taskId, "${result.errors}")
        val card = TaskRepository.findById(id)!!
        assertEquals(bay, card.project)
        assertEquals("Site projects", card.projectBucket, "the card kept a lane of the board it left")
        assertNotNull(ProjectBucketRepository.find(card.projectBucketId))
        assertTrue(card.projectBucketId != fabrication.id, "the card still points at the column it arrived from")
        // And a form that changes the project without naming a column is refused rather than filed under nothing:
        // the stored lane belongs to the board the card is leaving, so there is nothing here to keep.
        val refused = TaskService.updateAssigned(
            id, draft(project = haulRoad, lane = null), "EMP0001", canManage = true,
        )
        assertNull(refused.taskId, "a card crossed boards with no column on the far side")
        assertEquals(TaskField.LANE, refused.errors.keys.first())
        assertEquals(bay, TaskRepository.findById(id)!!.project, "the refused save moved the card back")
    }

    @Test
    fun `a card whose lane is taken away stays on the board`() {
        val procurement = lanes()[1]
        val id = TaskService.create(draft(), "EMP0001", canManage = true).taskId!!
        // Filed by this project's manager rather than by its assignee: since Stage 49 the assignee cannot
        // move a card, and this test needs the card in the lane so it can have the lane taken away.
        assertTrue(TaskService.moveToLane(id, procurement.id, "EMP0001", canManage = false))
        ProjectBucketRepository.delete(procurement.id)
        val card = TaskRepository.findById(id)
        assertNotNull(card, "deleting a lane deleted the card in it")
        assertEquals(0, card!!.projectBucketId, "the card still points at a column that is gone")
    }

    // --- relieving a board of a lane (Stage 31, bounded by Stage 48) ---------------------------------

    /** This board's six, plus one lane the project added for itself — the removable half. */
    private fun extraLane(id: Int = haulRoadId()): ProjectBucket =
        TaskService.addLane(id, "Haul", "EMP0001", canManage = true).bucket!!

    @Test
    fun `a lane the project added itself can be taken off its board`() {
        val id = haulRoadId()
        val haul = extraLane()
        // The whole right-hand side of the service: the name goes, and the board is the board minus one.
        assertNull(TaskService.removeLane(id, haul.id, "EMP0001", canManage = false), "the manager's own lane refused")
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(id).map { it.name })
        // The columns keep the positions they were given rather than being renumbered: the order on the board
        // is what it always was, only with the extra one gone.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), TaskService.lanesOf(id).map { it.position })
        // Somebody else's board is untouched, as with every other lane rule.
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(otherProject()).map { it.name })
    }

    @Test
    fun `the six sub-categories cannot be taken off a board`() {
        val id = haulRoadId()
        // Stage 48 made these the structure rather than a starting suggestion: every project card has to be
        // filed in one of them, so a board missing one could not hold the cards the popup offers to file. The
        // refusal is the same for the project's own manager and for management, and it comes before the lane is
        // looked at the board for, so nothing is half-done.
        lanes().forEach { lane ->
            assertEquals(LaneError.STANDARD, TaskService.removeLane(id, lane.id, "EMP0001", canManage = false))
            assertEquals(LaneError.STANDARD, TaskService.removeLane(id, lane.id, "EMP0001", canManage = true))
        }
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(id).map { it.name }, "a refusal took a column")
        // A name that happens to match the domain's, written by hand, is still the standard lane it is: the rule
        // reads the name, not how the row arrived.
        assertNotNull(ProjectBucketRepository.find(lanes().first().id))
    }

    @Test
    fun `only management or this project's manager may take a lane off`() {
        val id = haulRoadId()
        val haul = extraLane()
        // The same right that names a lane, so a control nobody may use is never printed: EMP0002 works a card
        // here and EMP0003 is an account with nothing to do with this project, and neither can empty a column.
        assertEquals(LaneError.DENIED, TaskService.removeLane(id, haul.id, "EMP0002", canManage = false))
        assertEquals(LaneError.DENIED, TaskService.removeLane(id, haul.id, "EMP0003", canManage = false))
        assertEquals(7, TaskService.lanesOf(id).size, "a refused removal took a column anyway")
        // Management may, from any project's board; this project's own manager may with no elevated role at all.
        assertNull(TaskService.removeLane(id, haul.id, "EMP0002", canManage = true))
        val second = extraLane()
        assertNull(TaskService.removeLane(id, second.id, "EMP0001", canManage = false))
    }

    @Test
    fun `a lane from another board or no board at all is refused and left standing`() {
        val id = haulRoadId()
        val haul = extraLane()
        val bayId = otherProject()
        val foreign = ProjectBucketRepository.ofProject(bayId).first()
        // The form posts a lane id, and an id is the browser's word for a column, not proof of one: a lane of
        // another project's board is not on this one, however much the person posting it may manage both.
        assertEquals(LaneError.NO_LANE, TaskService.removeLane(id, foreign.id, "EMP0001", canManage = true))
        assertEquals(LaneError.NO_LANE, TaskService.removeLane(id, 4242, "EMP0001", canManage = true))
        assertEquals(LaneError.NO_LANE, TaskService.removeLane(id, 0, "EMP0001", canManage = true))
        // A project that is gone has no board to relieve either, and that is read before the lane is.
        assertEquals(LaneError.NO_PROJECT, TaskService.removeLane(4242, haul.id, "EMP0001", canManage = true))
        assertNotNull(ProjectBucketRepository.find(foreign.id), "a refused removal deleted the borrowed lane")
        assertNotNull(ProjectBucketRepository.find(haul.id), "a refused removal deleted the lane it aimed at")
        assertEquals(7, TaskService.lanesOf(id).size)
        assertEquals(DEFAULT_PROJECT_BUCKETS.size, TaskService.lanesOf(bayId).size)
    }

    @Test
    fun `removing a lane un-files its cards instead of taking them with it`() {
        val id = haulRoadId()
        val haul = extraLane()
        val card = TaskService.create(draft(lane = haul.name), "EMP0001", canManage = true).taskId!!
        assertNull(TaskService.removeLane(id, haul.id, "EMP0001", canManage = false))
        val left = TaskRepository.findById(card)
        assertNotNull(left, "the lane went and took the card with it")
        assertEquals(0, left!!.projectBucketId, "the card still names a column that is gone")
        // Un-filing is all that happens to it: still this project's card, still the same work, still somebody's.
        assertEquals("Spread base course", left.title)
        assertEquals(haulRoad, left.project)
        assertEquals("EMP0002", left.assignee)
        // Which is the one state the board prints as Unsorted, and the popup opens it on that placeholder —
        // see [moveToLane]: the column shows the card, it is not somewhere a card can be sent.
    }

    @Test
    fun `a board keeps its last lane`() {
        val id = haulRoadId()
        // With the six standing there is no way down to one lane through the service, and that is the point of
        // LaneError.STANDARD — which is why it is checked before the count. So the shared rows are taken out
        // behind its back, the shape a hand-edited board would be, and the one lane left standing is an extra.
        lanes().forEach { ProjectBucketRepository.delete(it.id) }
        val last = ProjectBucketRepository.insert(id, "Tendering")
        // A project page with no lanes at all falls back to the five status columns, which is the shape Stage 30
        // decided that page does not have — and the in-place swap would have no lane area to swap. So one stays.
        assertEquals(LaneError.LAST_LANE, TaskService.removeLane(id, last, "EMP0001", canManage = false))
        assertEquals(LaneError.LAST_LANE, TaskService.removeLane(id, last, "EMP0001", canManage = true))
        assertEquals(listOf("Tendering"), TaskService.lanesOf(id).map { it.name }, "the final lane was removed anyway")
        // And the lane that stands is still a lane a card can be filed in.
        val card = TaskService.create(draft(lane = "Tendering"), "EMP0001", canManage = true).taskId!!
        assertEquals(last, TaskRepository.findById(card)!!.projectBucketId)
    }

    // --- the backlog's own columns (Stage 40) ---------------------------------------------------------

    /**
     * A column of the **Other tasks** board, not a lane of a project's. `tasks.bucket_id` points at these, so
     * they are the ones whose removal takes cards off every column rather than filing them under Unsorted.
     */
    private fun backlog(name: String): Int = BucketRepository.insert(name)

    private fun otherCard(title: String, bucketId: Int): Int = TaskRepository.insert(
        kind = "OTHER", title = title, projectId = null, due = "2026-11-12",
        priority = "Medium", assigneeEmpNo = null, status = "Not started",
        bucketId = bucketId, started = "2026-11-01",
    )

    private fun columnNames(): List<String> = TaskService.bucketRows().map { it.name }

    @Test
    fun `any signed-in account may name a backlog column, and the word is stored trimmed`() {
        assertNull(TaskService.addBucket("  Site works  ", "EMP0001", canManage = true))
        assertEquals(listOf("Site works"), columnNames(), "the column was stored with the spaces around it")
        assertEquals(0, TaskService.bucketRows().first().position, "the first column was given a gap beside it")
        assertNull(TaskService.addBucket("Depot", "EMP0001", canManage = true))
        assertEquals(listOf(0, 1), TaskService.bucketRows().map { it.position }, "columns do not queue in order")
        // Stage 48: the create asks no permission. The account that notices the kind of work is the one that
        // names it — and it is the same right that already lets them write a card into a column.
        assertNull(TaskService.addBucket("Overhaul", "EMP0002", canManage = false))
        assertEquals(listOf("Site works", "Depot", "Overhaul"), columnNames(), "a Viewer's column did not land")
        // What the name is still answerable for, whoever types it.
        assertEquals(BucketError.DUPLICATE, TaskService.addBucket("overhaul", "EMP0002", canManage = false))
    }

    @Test
    fun `a backlog column's name is refused when it is blank, too long, or already taken`() {
        val site = backlog("Site works")
        assertEquals(BucketError.BLANK, TaskService.addBucket("", "EMP0001", canManage = true))
        assertEquals(BucketError.BLANK, TaskService.addBucket("   ", "EMP0001", canManage = true))
        assertEquals(BucketError.TOO_LONG, TaskService.addBucket("x".repeat(BUCKET_NAME_MAX + 1), "EMP0001", true))
        // A board is read by its headings, so two columns a reader cannot tell apart are one column too many —
        // including the case-insensitive pair the popup would print identically.
        assertEquals(BucketError.DUPLICATE, TaskService.addBucket("site WORKS", "EMP0001", canManage = true))
        assertEquals(listOf("Site works"), columnNames(), "a refused name took a column anyway")
        // The bound is the field's own length, so a name exactly at it lands rather than being cut.
        val max = "y".repeat(BUCKET_NAME_MAX)
        assertNull(TaskService.addBucket(max, "EMP0001", canManage = true))
        assertEquals(2, TaskService.bucketRows().size)
        assertNotNull(BucketRepository.find(site), "the column already standing was taken with the refusal")
    }

    @Test
    fun `renaming a backlog column changes its heading and nothing about its cards`() {
        val site = backlog("Site works")
        val depot = backlog("Depot")
        val card = otherCard("Grade the haul road", site)
        assertNull(TaskService.renameBucket(site, "  Yard works  ", "EMP0001", canManage = true))
        assertEquals(listOf("Yard works", "Depot"), columnNames(), "the name did not reach the heading")
        assertEquals("Yard works", TaskRepository.findById(card)!!.bucket, "the board cannot name its card's column")
        // A card belongs to its column by id, so a rename moves nothing and renumbers nothing.
        assertEquals(site, TaskService.bucketRows().first().id, "the renamed column became a different column")
        assertEquals(0, TaskService.bucketRows().first().position, "the renamed column lost its place in the row")
        assertEquals(1, TaskService.bucketCardCount(site), "the rename moved work out of the column")
        assertEquals(0, TaskService.bucketCardCount(depot))
    }

    @Test
    fun `a rename is refused for a name the board cannot carry, or a column it is not on`() {
        val site = backlog("Site works")
        val depot = backlog("Depot")
        assertEquals(BucketError.BLANK, TaskService.renameBucket(site, "  ", "EMP0001", canManage = true))
        assertEquals(BucketError.TOO_LONG, TaskService.renameBucket(site, "z".repeat(BUCKET_NAME_MAX + 1), "EMP0001", true))
        // The name a column already carries is its own, not a duplicate of a neighbour's.
        assertNull(TaskService.renameBucket(site, "Site works", "EMP0001", canManage = true))
        assertEquals(BucketError.DUPLICATE, TaskService.renameBucket(site, "depot", "EMP0001", canManage = true))
        assertEquals(listOf("Site works", "Depot"), columnNames(), "a refused rename wrote a name")
        // The id is the browser's word for a column, not proof of one: something else, or nothing, is refused
        // before any name is looked at.
        assertEquals(BucketError.NO_BUCKET, TaskService.renameBucket(4242, "Overhaul", "EMP0001", canManage = true))
        assertEquals(BucketError.NO_BUCKET, TaskService.renameBucket(0, "Overhaul", "EMP0001", canManage = true))
        // A normal account gets the same refusal whether the column is theirs to read or not.
        assertEquals(BucketError.DENIED, TaskService.renameBucket(site, "Mine now", "EMP0002", canManage = false))
        assertEquals("Site works", columnNames().first(), "a refused rename wrote a name")
        assertNotNull(BucketRepository.find(depot))
    }

    @Test
    fun `a column with cards cannot be deleted until the board has somewhere to send them`() {
        val only = backlog("Site works")
        val card = otherCard("Grade the haul road", only)
        // The whole board is this one column: `tasks.bucket_id` answers ON DELETE SET NULL and this board has no
        // Unsorted column, so there is no answer to "where do the cards go" and the delete is refused outright.
        assertEquals(BucketError.LAST_BUCKET, TaskService.deleteBucket(only, null, "EMP0001", canManage = true))
        assertEquals(BucketError.LAST_BUCKET, TaskService.deleteBucket(only, only, "EMP0001", canManage = true))
        assertNotNull(BucketRepository.find(only), "the last column with work in it was deleted anyway")
        assertNotNull(TaskRepository.findById(card), "the delete took the card with the column")
        // Once a second column stands the answer is a choice, and a bad one is not accepted in its place.
        val depot = backlog("Depot")
        assertEquals(BucketError.NO_DEST, TaskService.deleteBucket(only, null, "EMP0001", canManage = true))
        assertEquals(BucketError.NO_DEST, TaskService.deleteBucket(only, only, "EMP0001", canManage = true))
        assertEquals(BucketError.NO_DEST, TaskService.deleteBucket(only, 4242, "EMP0001", canManage = true))
        assertEquals(BucketError.NO_BUCKET, TaskService.deleteBucket(4242, depot, "EMP0001", canManage = true))
        assertEquals(BucketError.DENIED, TaskService.deleteBucket(only, depot, "EMP0002", canManage = false))
        assertEquals(listOf("Site works", "Depot"), columnNames(), "a refused delete emptied a column")
        assertEquals(only, TaskService.bucketRows().first().id, "a refused delete recreated the column it aimed at")
    }

    @Test
    fun `deleting a column hands its cards to the one chosen and takes nothing else with it`() {
        val site = backlog("Site works")
        val depot = backlog("Depot")
        val first = otherCard("Grade the haul road", site)
        val second = otherCard("Re-line the crusher", site)
        val kept = otherCard("Fix the gate", depot)
        assertNull(TaskService.deleteBucket(site, depot, "EMP0001", canManage = true))
        assertEquals(listOf("Depot"), columnNames(), "the column the delete named is still on the board")
        assertEquals(0, TaskService.bucketCardCount(site), "the deleted column still counts as holding cards")
        assertEquals(3, TaskService.bucketCardCount(depot), "the cards did not all arrive where they were sent")
        listOf(first, second, kept).forEach { id ->
            val card = TaskRepository.findById(id)
            assertNotNull(card, "the delete deleted a card")
            assertEquals("Depot", card!!.bucket, "a card was left naming a column that is gone")
            assertEquals(depot, TaskService.bucketRows().single().id)
        }
        // Moving is all that happened to them: same titles, same status, still backlog cards.
        assertEquals("Grade the haul road", TaskRepository.findById(first)!!.title)
        assertEquals("Not started", TaskRepository.findById(second)!!.status)
    }

    @Test
    fun `an empty column is taken off the board without a destination being asked for`() {
        val site = backlog("Site works")
        val idle = backlog("Idle plant")
        val card = otherCard("Grade the haul road", site)
        // Nothing to move means nothing to choose: the popup posts only the column's id here.
        assertNull(TaskService.deleteBucket(idle, null, "EMP0001", canManage = true))
        assertEquals(listOf("Site works"), columnNames(), "an empty column could not be taken off the board")
        assertNotNull(TaskRepository.findById(card), "the delete reached the other column's work")
        assertEquals(site, TaskService.bucketRows().single().id, "the wrong column is standing")
        // And a normal account is refused the same empty delete, with the board left whole.
        assertEquals(BucketError.DENIED, TaskService.deleteBucket(site, null, "EMP0002", canManage = false))
        assertEquals(listOf("Site works"), columnNames(), "a refused delete emptied a column")
    }

    @Test
    fun `a backlog card saves with nobody assigned to it`() {
        val site = backlog("Site works")
        val card = otherCard("Grade the haul road", site)
        // What the popup on a backlog card actually posts: the card's own fields, with no project and no
        // person on it — `createOther` files it under a column, not under a person, so a blank assignee is
        // the card's normal state rather than an omission. This is the round trip the picture asks for:
        // click the card, move its status, save.
        val posted = draft(title = "Grade the haul road", project = "", assignee = "")
        val result = TaskService.updateAssigned(
            card, posted.copy(status = "In progress"), "EMP0001", canManage = true)
        assertEquals(card, result.taskId, "the backlog card refused to save: ${result.errors}")
        val saved = TaskRepository.findById(card)!!
        assertEquals("In progress", saved.status, "the status the popup posted did not land")
        assertEquals("", saved.assignee, "saving a backlog card put somebody on it")
        assertEquals("Site works", saved.bucket, "the save moved the card off its column")
        assertEquals("", saved.project, "saving a backlog card gave it a project")
        // A name typed anyway is still checked against the roster — blank is allowed, nonsense is not.
        val wrong = TaskService.updateAssigned(
            card, posted.copy(assigneeEmpNo = "EMP9999"), "EMP0001", canManage = true)
        assertEquals("That employee is not on the team roster.", wrong.error(TaskField.ASSIGNEE))
        assertNull(wrong.taskId, "a card was saved through a name that is not on the roster")
        assertEquals("In progress", TaskRepository.findById(card)!!.status, "a refused save wrote the form")
        // And management can put a real person on a backlog card from the same popup.
        val mine = TaskService.updateAssigned(
            card, posted.copy(assigneeEmpNo = "EMP0002"), "EMP0001", canManage = true)
        assertEquals(card, mine.taskId, "a backlog card could not be assigned")
        assertEquals("EMP0002", TaskRepository.findById(card)!!.assignee)
        assertEquals("Site works", TaskRepository.findById(card)!!.bucket, "assigning it moved it off the board")
    }

    @Test
    fun `an assigned card still cannot be saved with the assignee blank`() {
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        // The rule is read from the row's `kind`, never from the form: the same blank that a backlog card
        // answers with would otherwise strip the person off somebody's assigned work.
        val result = TaskService.updateAssigned(id, draft().copy(assigneeEmpNo = ""), "EMP0001", true)
        assertEquals("Assign the task to someone on the team.", result.error(TaskField.ASSIGNEE))
        assertNull(result.taskId, "an assigned card was saved with nobody on it")
        assertEquals("EMP0002", TaskRepository.findById(id)!!.assignee, "a refused save unassigned the card")
    }

    // --- Stage 42: writing a backlog card from the popup, and moving it between its columns ----

    /**
     * The create a backlog column's `+ Add task` line posts: no project, no lane, and the column the line
     * was pressed under. Everything the popup can name is filled, including the two fields a normal account
     * is pinned out of, because a hand-built POST arrives with all of them and the rule has to hold there.
     */
    private fun backlogDraft(
        title: String = "Clean the spill bund",
        column: Int,
        assignee: String = "",
        status: String = "Not started",
    ) = TaskDraft(
        title = title, projectName = "", assigneeEmpNo = assignee,
        started = "2026-11-01", due = "2026-11-20", priority = "High", status = status,
        columnId = column,
    )

    @Test
    fun `an ordinary account writes a backlog card that lands as its own and not started`() {
        val site = backlog("Site works")
        val depot = backlog("Depot")
        // The writer is not management, and the form names a colleague and a status already under way. The
        // draft is rebuilt from the caller *before* it is validated, so neither of those can be put in by
        // somebody who may not put them in — and the fields that are theirs still land.
        val result = TaskService.createBacklog(
            backlogDraft(column = depot, assignee = "EMP0001", status = "Blocked"), "EMP0002", canManage = false)
        val id = result.taskId
        assertNotNull(id, "a normal account was refused a backlog card: ${result.errors}")
        val card = TaskRepository.findById(id!!)!!
        assertTrue(card.isBacklog, "the card was written onto a project board")
        assertEquals("Depot", card.bucket, "the card did not land in the column it was aimed at")
        assertEquals(depot, card.bucketId, "the card is filed under a column the form never named")
        assertEquals("EMP0002", card.assignee, "a create handed the card to the number the form typed")
        assertEquals("Not started", card.status, "a create let its writer say the work had begun")
        assertEquals("High", card.priority, "the priority the writer chose was thrown away")
        assertEquals("", card.project, "a backlog card was given a project")
        assertEquals("", card.projectBucket, "a backlog card was filed in a project's lane")
        // Both boards read it the way their own rules say: on the backlog, and not on anybody's assigned board.
        assertTrue(TaskService.other().any { it.id == id }, "the card is not on the backlog it was written into")
        assertFalse(TaskService.assignedFor("EMP0002", false).any { it.id == id },
            "a backlog card showed on the assigned board")
    }

    @Test
    fun `management keeps the person and the status a backlog create names`() {
        val site = backlog("Site works")
        val result = TaskService.createBacklog(
            backlogDraft(column = site, assignee = "EMP0002", status = "In progress"), "EMP0001", canManage = true)
        val card = TaskRepository.findById(result.taskId!!)!!
        assertEquals("EMP0002", card.assignee, "management's choice of who owns the card was dropped")
        assertEquals("In progress", card.status, "management's choice of status was dropped")
        // A backlog card may be written with nobody on it at all: it is filed under a column, and the person
        // who picks it up puts their own name on it later.
        val blank = TaskService.createBacklog(backlogDraft(column = site), "EMP0001", canManage = true)
        assertNotNull(blank.taskId, "an unassigned backlog card was refused: ${blank.errors}")
        assertEquals("", TaskRepository.findById(blank.taskId!!)!!.assignee)
    }

    @Test
    fun `a backlog create is refused when the column it names is not on the board`() {
        val site = backlog("Site works")
        val gone = backlog("Depot")
        assertNull(TaskService.deleteBucket(gone, site, "EMP0001", canManage = true))
        val wrong = TaskService.createBacklog(backlogDraft(column = gone), "EMP0002", canManage = false)
        assertNull(wrong.taskId, "a card was filed in a column that has been taken off the board")
        assertEquals("Choose which column this card sits in.", wrong.error(TaskField.COLUMN))
        val blank = TaskService.createBacklog(backlogDraft(column = site, title = ""), "EMP0002", canManage = false)
        assertEquals("Give the task a title.", blank.error(TaskField.TITLE))
        assertNull(blank.taskId, "a card with no title was written")
        assertTrue(TaskService.other().isEmpty(), "a refused create still wrote a card")
    }

    // --- Stage 50: the Team panel's card, which posts the half it is not standing on ------------

    /**
     * The POST the Team sheet's + Assign a task panel makes when its *Where it lives* picker says *Other
     * tasks*. The other half's two fields are still in the form — a field put away by `hidden` posts, which is
     * what keeps the two halves one change of the picker apart and not one reload — and both of them name real
     * things. What this pins is that the card belongs to neither: the route's answer to that picker is the only
     * thing that decides which of the two writes runs, and the backlog write files the card by column alone.
     */
    @Test
    fun `a no-project assign files the card by its column and drops the project fields it also posted`() {
        val site = backlog("Site works")
        val result = TaskService.createBacklog(
            TaskDraft(
                title = "Audit the fuel store", projectName = haulRoad, assigneeEmpNo = "EMP0002",
                started = "2026-11-01", due = "2026-11-20", priority = "High", status = "Not started",
                projectLane = "Site projects", columnId = site, board = BOARD_OTHER,
            ),
            "EMP0001", canManage = true,
        )
        val id = result.taskId
        assertNotNull(id, "the panel's own post was refused: ${result.errors}")
        val card = TaskRepository.findById(id!!)!!
        assertTrue(card.isBacklog, "a card the panel said was an Other task was written onto a project")
        assertEquals("", card.project, "the project the hidden half posted was put on the card")
        assertEquals("", card.projectBucket, "the lane the hidden half posted filed the card on a project board")
        assertEquals(site, card.bucketId, "the card is not under the column management chose")
        assertEquals("EMP0002", card.assignee, "the person picked from the roster was not given the card")
    }

    /**
     * The mirror, and the half that has been here all along: the panel posts a **column** with every project
     * card too, because its own picker is only put away rather than taken out. An assigned row is filed on a
     * lane and in no column at all — a card pointing at both would be on two boards at once.
     */
    @Test
    fun `a project assign keeps its lane and takes no column from the panel`() {
        val site = backlog("Site works")
        val result = TaskService.create(
            TaskDraft(
                title = "Re-line the crush bin", projectName = haulRoad, assigneeEmpNo = "EMP0002",
                started = "2026-11-01", due = "2026-11-20", priority = "High", status = "Not started",
                projectLane = "Repair/Maintenance", columnId = site, board = BOARD_PROJECT,
            ),
            "EMP0001", canManage = true,
        )
        val id = result.taskId
        assertNotNull(id, "the panel's project half was refused: ${result.errors}")
        val card = TaskRepository.findById(id!!)!!
        assertFalse(card.isBacklog, "a card the panel filed on a project landed on the backlog")
        assertEquals(haulRoad, card.project)
        assertEquals("Repair/Maintenance", card.projectBucket, "the lane named by the panel was not the one kept")
        assertEquals(0, card.bucketId, "the column the hidden half posted was filed on the card")
    }

    @Test
    fun `a create is refused on a board with no columns while a save still goes through`() {
        // A board nobody has named a column on yet: there is nowhere to file the card, and an OTHER row with
        // no column is a card no board prints — so the write says so under the picker that has nothing in it
        // rather than answering with a card nobody can find.
        val orphan = TaskService.createBacklog(backlogDraft(column = 1), "EMP0002", canManage = false)
        assertNull(orphan.taskId, "a card was written onto a board that has no columns")
        assertEquals("The Other tasks board has no column to put this card in yet.", orphan.error(TaskField.COLUMN))
        // The other half: a card that has lost its column still saves. [TaskService.deleteBucket] refuses to
        // leave one in this state, so the column is dropped underneath it directly — which is exactly what
        // `tasks.bucket_id`'s ON DELETE SET NULL does, and what a row imported that way would already look like.
        val site = backlog("Site works")
        val card = otherCard("Grade the haul road", site)
        BucketRepository.delete(site)
        val saved = TaskService.updateAssigned(
            card, draft(project = "", title = "Grade the haul road", due = "2026-12-05"), "EMP0001",
            canManage = true)
        assertEquals(card, saved.taskId, "a save was refused by a board with no columns: ${saved.errors}")
        assertEquals("2026-12-05", TaskRepository.findById(card)!!.due, "the date did not land")
        assertEquals(0, TaskRepository.findById(card)!!.bucketId, "the save invented a column")
    }

    @Test
    fun `the owner of a backlog card writes their own fields but not its column`() {
        val site = backlog("Site works")
        val depot = backlog("Depot")
        val card = otherCard("Grade the haul road", site)
        assertNotNull(
            TaskService.updateAssigned(
                card, draft(project = "", assignee = "EMP0002", title = "Grade the haul road"), "EMP0001",
                canManage = true).taskId,
            "management could not put somebody on a backlog card")
        // The owner posts the whole form, as the popup does, and the column travels on it — to be put down
        // again (Stage 49). Which column a card sits in is management's answer on this board as on a project's:
        // the greyed picker the popup draws is the same refusal in markup, and the saved row reads the column
        // it was standing on, exactly as an assigned card's save keeps its lane. What the owner may still set is
        // their own day on the card, and everything they leave blank comes back out of the row.
        val kept = TaskService.updateAssigned(
            card,
            draft(project = "", assignee = "EMP0001", title = "Retitled", status = "Completed", priority = "Low")
                .copy(columnId = depot),
            "EMP0002", canManage = false)
        assertNotNull(kept.taskId, "the owner could not save their own card: ${kept.errors}")
        val saved = TaskRepository.findById(card)!!
        assertEquals("Site works", saved.bucket, "an ordinary account moved a card between the columns")
        assertEquals(site, saved.bucketId, "the column nobody is allowed to choose was written to the row")
        assertEquals("Low", saved.priority, "the owner's own priority was thrown away")
        assertEquals("Grade the haul road", saved.title, "an owner retitled a card whose title is management's")
        assertEquals("Not started", saved.status, "an owner moved their card along the status line")
        assertEquals("EMP0002", saved.assignee, "an owner handed their own card to somebody else")
        assertEquals(1, TaskService.bucketCardCount(site), "the card left its column without anybody moving it")
        assertEquals(0, TaskService.bucketCardCount(depot), "the column nobody chose gained a card")
        // Management posts the same form and the card goes where they say — the only difference between the two
        // readers of this board, and the same difference the project board draws with a drag.
        val moved = TaskService.updateAssigned(
            card,
            draft(project = "", assignee = "EMP0002", title = "Grade the haul road").copy(columnId = depot),
            "EMP0001", canManage = true)
        assertEquals(card, moved.taskId, "management could not move the card: ${moved.errors}")
        assertEquals(depot, TaskRepository.findById(card)!!.bucketId, "management's column did not land")
        // And the same posted column at a card that is nobody else's is refused whole, board left as it was.
        val stranger = TaskService.updateAssigned(
            card, draft(project = "", assignee = "EMP0002").copy(columnId = site), "EMP0001", canManage = false)
        assertEquals("Only management or the person this task is assigned to can change it.",
            stranger.error(TaskField.PERMISSION))
        assertNull(stranger.taskId, "a reader who does not own the card moved it")
        assertEquals(depot, TaskRepository.findById(card)!!.bucketId, "the refused move changed the column")
    }

    @Test
    fun `a save that posts no column leaves the card on the column it is standing on`() {
        val site = backlog("Site works")
        val card = otherCard("Grade the haul road", site)
        // The general board's popup has no Column field to post from, exactly as it has no Lane field, and the
        // answer is the same one: the card stays where it is rather than being un-filed.
        val result = TaskService.updateAssigned(
            card, draft(project = "", due = "2026-12-20"), "EMP0001", canManage = true)
        assertEquals(card, result.taskId, "the save was refused: ${result.errors}")
        assertEquals("Site works", TaskRepository.findById(card)!!.bucket,
            "a save with no column field moved the card off its column")
        // A lane posted at a backlog card is neither refused nor written: that card is on no project's board,
        // so there is no lane for it to be wrong about, and a message under a field the popup never drew would
        // be the reader's problem rather than theirs.
        val filed = TaskService.updateAssigned(
            card, draft(project = "", assignee = "EMP0002").copy(projectLane = "Site projects"), "EMP0001",
            canManage = true)
        assertEquals(card, filed.taskId, "a hand-posted lane was refused at a backlog card: ${filed.errors}")
        assertNull(filed.error(TaskField.LANE), "a lane refusal printed on a popup with no lane field")
        assertEquals(0, TaskRepository.findById(card)!!.projectBucketId, "a posted lane filed the backlog card")
        assertEquals("Site works", TaskRepository.findById(card)!!.bucket, "the lane write reached the column")
    }

    @Test
    fun `an assigned card's save is untouched by the backlog's column field`() {
        val site = backlog("Site works")
        val id = TaskService.create(draft(), "EMP0001", true).taskId!!
        // The row's `kind` decides which board a card moves on, never the form: a column posted at an
        // assigned card is not a place on the board it lives on, so it is dropped like a lane is on the
        // general board — and the card keeps the project it was written with.
        val result = TaskService.updateAssigned(
            id, draft().copy(columnId = site), "EMP0001", canManage = true)
        assertEquals(id, result.taskId, "the save was refused: ${result.errors}")
        assertEquals(0, TaskRepository.findById(id)!!.bucketId, "an assigned card was filed into a backlog column")
        assertEquals(haulRoad, TaskRepository.findById(id)!!.project, "the save took the card off its project")
    }

    // --- Stage 48: one card, two worlds, and the move between them --------------------------------

    @Test
    fun `management sends a project card to the backlog and its work rides along`() {
        val site = backlog("Site works")
        val repair = lanes().first { it.name == "Repair/Maintenance" }
        val id = TaskService.create(
            draft(lane = repair.name, status = "In progress"), "EMP0001", canManage = true,
        ).taskId!!
        val step = TaskService.addChecklistItem(id, "Order the mesh", "EMP0001", canManage = true)
        assertNotNull(step, "the plan was not written")

        // The popup's "where it lives" picker answering *Other tasks*, with a column named for it to land in.
        val moved = TaskService.updateAssigned(
            id, draft(lane = repair.name, status = "In progress")
                .copy(board = BOARD_OTHER, columnId = site),
            "EMP0001", canManage = true,
        )
        assertEquals(id, moved.taskId, "the crossing was refused: ${moved.errors}")
        val card = TaskRepository.findById(id)!!
        assertTrue(card.isBacklog, "the card is still on a project board")
        assertEquals("", card.project, "a backlog card kept the project it left")
        assertEquals(0, card.projectBucketId, "a backlog card kept a lane of that project")
        assertEquals("Site works", card.bucket, "the card did not land in the column named")
        // Everything that is not the board is carried: the plan, the status, the person, the dates.
        assertEquals("In progress", card.status, "the crossing reset the card's status")
        assertEquals("EMP0002", card.assignee, "the crossing took the card off its owner")
        assertEquals("2026-11-01", card.started)
        assertEquals("2026-11-12", card.due)
        assertEquals(
            listOf("Order the mesh"),
            TaskRepository.findById(id)!!.checklist.map { it.label },
            "the crossing left the plan behind",
        )
        // And it moved between the two lists the pages read from, which is what the sidebar counts.
        assertTrue(TaskService.other().any { it.id == id }, "the card is not on the backlog it was sent to")
        assertFalse(TaskService.assigned().any { it.id == id }, "the card is still on the assigned board")
    }

    @Test
    fun `management brings a backlog card onto a project and files it in a lane`() {
        val site = backlog("Site works")
        val id = TaskService.createBacklog(
            backlogDraft(column = site, assignee = "EMP0002", status = "In progress"), "EMP0001", canManage = true,
        ).taskId!!
        val moved = TaskService.updateAssigned(
            id,
            draft(project = haulRoad, lane = "Goods sales", assignee = "EMP0002", status = "In progress")
                .copy(board = BOARD_PROJECT),
            "EMP0001", canManage = true,
        )
        assertEquals(id, moved.taskId, "the crossing back was refused: ${moved.errors}")
        val card = TaskRepository.findById(id)!!
        assertFalse(card.isBacklog, "the card is still a backlog row")
        assertEquals(haulRoad, card.project)
        assertEquals("Goods sales", card.projectBucket, "the card did not land in the lane named")
        assertEquals(0, card.bucketId, "a project card still points at a backlog column")
        assertEquals("In progress", card.status, "the crossing reset the card's status")
        // The column it came from stops counting it: a card on a project's board is on no backlog column, and
        // the same read the Other tasks page uses to fill its boxes is the one that has to say so.
        assertEquals(0, TaskService.bucketCardCount(site), "the column still counts the card that left")
    }

    @Test
    fun `a crossing that names nowhere to land is refused and leaves the card where it was`() {
        val site = backlog("Site works")
        val repair = lanes().first { it.name == "Repair/Maintenance" }
        val id = TaskService.create(draft(lane = repair.name), "EMP0001", canManage = true).taskId!!

        // Onto the backlog with no column: an OTHER row pointing at no column is printed by no board at all.
        val noColumn = TaskService.updateAssigned(
            id, draft().copy(board = BOARD_OTHER), "EMP0001", canManage = true,
        )
        assertNull(noColumn.taskId, "a card was sent to the backlog with nowhere in it")
        assertEquals("Choose which column this card sits in.", noColumn.error(TaskField.COLUMN))
        assertFalse(TaskRepository.findById(id)!!.isBacklog, "the refusal moved the card anyway")

        // Onto a project with no lane: the card has left its board, so its old column is not somewhere to keep.
        val backlogCard = TaskService.createBacklog(backlogDraft(column = site), "EMP0001", true).taskId!!
        val noLane = TaskService.updateAssigned(
            backlogCard,
            draft(project = haulRoad).copy(board = BOARD_PROJECT, projectLane = null),
            "EMP0001", canManage = true,
        )
        assertNull(noLane.taskId, "a card was put on a project with no column on it")
        assertEquals(
            "Choose which part of this project the card is on.", noLane.error(TaskField.LANE),
            "the crossing was refused for something other than its lane: ${noLane.errors}",
        )
        assertTrue(TaskRepository.findById(backlogCard)!!.isBacklog, "the refusal moved the card anyway")
    }

    @Test
    fun `an account that does not manage cannot send a card to the other board`() {
        val site = backlog("Site works")
        val fabrication = lanes().first()
        val id = TaskService.create(draft(lane = fabrication.name), "EMP0001", canManage = true).taskId!!
        // The card belongs to EMP0002, who posts the whole popup form with the board field set, exactly as a
        // hand-built POST does. Their own three fields land; the board answer is read from the row, not them.
        val own = TaskService.updateAssigned(
            id,
            draft(priority = "Low", status = "Completed", assignee = "EMP0001")
                .copy(board = BOARD_OTHER, columnId = site),
            "EMP0002", canManage = false,
        )
        assertEquals(id, own.taskId, "their own edit was refused: ${own.errors}")
        val card = TaskRepository.findById(id)!!
        assertFalse(card.isBacklog, "an assignee moved their card onto the backlog")
        assertEquals(0, card.bucketId, "the refused crossing wrote a column")
        assertEquals(fabrication.id, card.projectBucketId, "the crossing un-filed the card")
        assertEquals("Low", card.priority, "the owner's own priority was thrown away")
        assertEquals("Not started", card.status, "an owner completed their own card from the popup")
        assertEquals("EMP0002", card.assignee, "an owner handed the card to somebody else")
    }

    @Test
    fun `a card that arrived on no project is management's to place`() {
        // The state Stage 48 stops creating but does not destroy: an ASSIGNED row with no project behind it,
        // which prints on no board. It stays where it is until somebody with the right chooses a world for it.
        val legacy = TaskRepository.insert(
            kind = "ASSIGNED", title = "Grade the haul road", projectId = null, due = "2026-11-12",
            priority = "Medium", assigneeEmpNo = "EMP0002", status = "Not started",
            bucketId = null, started = "2026-11-01",
        )
        val site = backlog("Site works")
        // Its owner cannot be told to fix a field they may not write, so the save stops with the sentence that
        // says who can, rather than with a greyed picker and no reason.
        val theirs = TaskService.updateAssigned(
            legacy, draft(project = "", due = "2026-12-01", lane = null), "EMP0002", canManage = false,
        )
        assertNull(theirs.taskId, "an owner was allowed to leave the card on no board")
        assertEquals("This card is on no project yet — ask management to choose one.", theirs.error(TaskField.PROJECT))
        assertEquals("2026-11-12", TaskRepository.findById(legacy)!!.due, "the refusal wrote the date")
        // Management naming no project is the same refusal the create gives: neither world, so neither board.
        val absent = TaskService.updateAssigned(
            legacy, draft(project = "", lane = null), "EMP0001", canManage = true,
        )
        assertNull(absent.taskId, "a card belonging to neither world was saved as it was")
        assertEquals("Choose which project this card is on.", absent.error(TaskField.PROJECT))
        // And either choice they do make lands it whole.
        val toBacklog = TaskService.updateAssigned(
            legacy, draft(project = "").copy(board = BOARD_OTHER, columnId = site), "EMP0001", canManage = true,
        )
        assertEquals(legacy, toBacklog.taskId, "the card could not be sent to the backlog: ${toBacklog.errors}")
        val card = TaskRepository.findById(legacy)!!
        assertTrue(card.isBacklog, "the crossing did not rewrite the board")
        assertEquals("Site works", card.bucket)
    }
}
