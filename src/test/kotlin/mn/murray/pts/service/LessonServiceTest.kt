package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Lessons
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.LESSON_TEXT_MAX
import mn.murray.pts.domain.LessonEntry
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.LessonRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The rules behind the Lesson learnt sheet (Stage 44), against a real SQLite file carrying the real
 * Flyway schema through V9 — the same harness [ForecastServiceTest] uses, because what this sheet is
 * made of is a row with three foreign keys and a right attached to it, and neither is worth testing
 * anywhere the keys are not actually enforced.
 *
 * Three of those rules are the ones the page cannot be trusted for, and each is asserted from both
 * sides — what lands and what is refused:
 *
 *  * **The author is the session.** A form has no Employee field, so the only way to get this wrong is
 *    to write what the route was *told*; these cases check the stored row, not the draft.
 *  * **A card pins its lesson to its project.** The two pickers can name two different jobs, and the
 *    answer says which of them has to move rather than storing a row the sheet cannot read.
 *  * **Only its author and management may write a row.** The buttons are presentation of the right and
 *    every single-field write goes through the same guard, so the refusals are tested on the service
 *    that decides them.
 *
 * The rest is the sheet's own arithmetic: which rows the two pickers leave, where an undated
 * carry-over sorts to, and that a row put away before the archive was retired (Stage 116) is not
 * a lesson the sheet prints.
 */
class LessonServiceTest {

    private lateinit var ds: HikariDataSource

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "lessons.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Myagmartsood Sanjaa", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Jambalsuren Batbayar", "Workshop", "Engineer"))
        TeamRepository.upsert(Member("EMP0003", "Chinbat Bayarsaikhan", "Survey", "Engineer"))

        crusher = project("M-01250", "Erdenet Crusher Modernisation")
        haulRoad = project("S-00005", "Oyu Tolgoi haul road")
        plateCard = card(crusher, "Detail plate drawings - Rev A")
        rfqCard = card(crusher, "RFQ - 20mm steel plate, grade 250")
        otherCard = card(haulRoad, "Survey the north ramp")
        backlogCard = card(null, "Update HSE induction deck")
    }

    @AfterEach
    fun tearDown() = ds.close()

    private var crusher = 0
    private var haulRoad = 0
    private var plateCard = 0
    private var rfqCard = 0
    private var otherCard = 0
    private var backlogCard = 0

    private fun project(code: String, name: String): Int = ProjectRepository.insert(
        code = code, name = name, status = "In progress", progress = 40, managerEmpNo = "EMP0001",
        due = "2026-12-01", type = "Fabrication/Manufacturing", client = "Erdenet Mining Corporation",
        revenue = 2_340_000_000.0,
    )

    private fun card(projectId: Int?, title: String): Int = TaskRepository.insert(
        kind = if (projectId == null) "OTHER" else "ASSIGNED", title = title, projectId = projectId,
        due = "", priority = "Medium", assigneeEmpNo = "EMP0002", status = "In progress",
        bucketId = null, started = "",
    )

    /** The popup's four fields, as one draft. */
    private fun draft(
        text: String = "Order the liner six months early.",
        projectId: Int = 0,
        taskId: Int = 0,
        on: String = "2026-07-30",
        id: Int = 0,
    ) = LessonDraft(id = id, text = text, projectId = projectId, taskId = taskId, loggedOn = on)

    /** Write a lesson the way the sheet would: as [who], with management's right only when told. */
    private fun log(
        who: String = "EMP0002",
        canManage: Boolean = false,
        text: String = "Order the liner six months early.",
        projectId: Int = 0,
        taskId: Int = 0,
        on: String = "2026-07-30",
        id: Int = 0,
    ): LessonResult = LessonService.save(
        draft(text = text, projectId = projectId, taskId = taskId, on = on, id = id), who, canManage,
    )

    private fun rows(): List<LessonEntry> = LessonRepository.all()

    private fun one(): LessonEntry = rows().single()

    private fun field(result: LessonResult): LessonField? = result.errors.keys.firstOrNull()

    private val today: String get() = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    // --- who a lesson belongs to --------------------------------------------------------------------

    @Test
    fun `a new lesson is logged under the account that wrote it`() {
        log(who = "EMP0003", text = "Keep ten percent spare beds.")
        assertEquals("EMP0003", one().employeeNumber, "the author came from somewhere other than the session")
        assertEquals("Chinbat Bayarsaikhan", one().employeeName, "the sheet prints the roster's name, not the number")
    }

    @Test
    fun `editing a lesson leaves it under the name it was logged with`() {
        val id = log(who = "EMP0002").let { one().id }
        // Management correcting somebody else's words is the one edit that changes a row and not its owner.
        val again = LessonService.save(
            draft(text = "Order the liner six months early — it saved five weeks.", id = id),
            "EMP0001", canManage = true,
        )
        assertTrue(again.saved)
        assertEquals(id, one().id)
        assertEquals("EMP0002", one().employeeNumber, "an edit moved the lesson to whoever typed it")
    }

    @Test
    fun `an ordinary account may write its own row and nobody else's`() {
        val id = log(who = "EMP0002").let { one().id }
        val entry = LessonRepository.find(id)!!
        assertTrue(LessonService.mayWrite(entry, "EMP0002", canManage = false), "its author was refused its own row")
        assertTrue(LessonService.mayWrite(entry, "EMP0003", canManage = true), "management was refused a peer's row")
        assertFalse(LessonService.mayWrite(entry, "EMP0003", canManage = false), "a colleague could write it")
    }

    @Test
    fun `a row nobody owns belongs to management alone`() {
        // V9 carried the old notes over with no author, and an empty owner must never read as "everyone's".
        val id = log(who = "").let { one().id }
        val entry = LessonRepository.find(id)!!
        assertTrue(entry.employeeNumber.isEmpty())
        assertFalse(LessonService.mayWrite(entry, "", canManage = false), "an unowned row was open to any account")
        assertTrue(LessonService.mayWrite(entry, "", canManage = true))
    }

    @Test
    fun `a write on somebody else's row is refused where it stands`() {
        log(who = "EMP0002")
        val id = one().id
        assertEquals(LessonWrite.DENIED, LessonService.setDiscussed(id, true, "EMP0003", canManage = false))
        assertEquals(LessonWrite.DENIED, LessonService.remove(id, "EMP0003", canManage = false))
        assertFalse(LessonRepository.find(id)!!.discussed, "a refused write still landed")
        // The edit popup is the same guard, and it answers under the field the reader cannot fix.
        val refused = LessonService.save(draft(text = "Rewritten by a colleague.", id = id), "EMP0003", false)
        assertEquals(LessonField.PERMISSION, field(refused))
    }

    @Test
    fun `a write on a row that has gone says so instead of inventing one`() {
        log(who = "EMP0002")
        val id = one().id
        LessonService.remove(id, "EMP0002", canManage = false)
        assertEquals(LessonWrite.MISSING, LessonService.setDiscussed(id, true, "EMP0002", false))
        assertEquals(LessonWrite.MISSING, LessonService.remove(id, "EMP0002", false))
        assertEquals(LessonField.ROW, field(LessonService.save(draft(text = "Back again.", id = id), "EMP0002", false)))
        assertTrue(rows().isEmpty())
    }

    // --- what the sheet will hold -------------------------------------------------------------------

    @Test
    fun `a lesson needs words`() {
        assertEquals(LessonField.TEXT, field(log(text = "")))
        assertEquals(LessonField.TEXT, field(log(text = "     ")), "spaces are not a lesson")
        assertTrue(rows().isEmpty(), "a refused blank row was stored anyway")
    }

    @Test
    fun `a lesson is one cell of text and no longer`() {
        assertEquals(LessonField.TEXT, field(log(text = "x".repeat(LESSON_TEXT_MAX + 1))))
        assertTrue(log(text = "x".repeat(LESSON_TEXT_MAX)).saved, "the limit itself was refused")
        // The row the sheet prints is what was typed with its edges off, not what the field held.
        assertEquals("x".repeat(LESSON_TEXT_MAX), one().text)
        val id = one().id
        LessonService.save(draft(text = "  Padded on both sides.  ", id = id), "EMP0002", false)
        assertEquals("Padded on both sides.", LessonRepository.find(id)!!.text, "the stored text kept its padding")
    }

    @Test
    fun `a lesson is dated today unless the popup says otherwise`() {
        log(on = "")
        assertEquals(today, one().loggedOn)
        log(id = one().id, on = "2026-01-05")
        assertEquals("2026-01-05", one().loggedOn)
        assertEquals(LessonField.DATE, field(log(on = "30/07/2026")), "a date the sheet cannot sort by")
        assertEquals(LessonField.DATE, field(log(on = "yesterday")))
    }

    @Test
    fun `a lesson may point at nothing, at a project, or at a card on that project`() {
        assertTrue(log(projectId = crusher).saved)
        assertTrue(log(projectId = crusher, taskId = plateCard).saved)
        assertTrue(log(taskId = backlogCard).saved, "a backlog card belongs to no project, and neither does the row")
        assertEquals(3, rows().size)
        val carded = rows().first { it.taskId == plateCard }
        assertEquals(crusher, carded.projectId)
        assertEquals("Detail plate drawings - Rev A", carded.taskTitle)
        assertEquals("M-01250", carded.projectCode)
    }

    @Test
    fun `a row that names something which is no longer there is refused by that field`() {
        assertEquals(LessonField.PROJECT, field(log(projectId = 999_999)))
        assertEquals(LessonField.TASK, field(log(taskId = 999_999)))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a card cannot be filed under another project`() {
        // The two pickers disagree, and the card is the one that is right: the answer names the field the
        // reader has to move and leaves the pairing out of the database.
        val refused = log(projectId = crusher, taskId = otherCard)
        assertEquals(LessonField.TASK, field(refused))
        assertTrue(rows().isEmpty(), "a lesson was stored under two projects at once")
        // The same pairing read back from the row the popup is editing is not a refusal.
        assertTrue(log(projectId = crusher, taskId = plateCard).saved)
    }

    // --- what the sheet prints ----------------------------------------------------------------------

    @Test
    fun `the two pickers cut the sheet and nothing else`() {
        log(who = "EMP0002", projectId = crusher, text = "Crusher liner: order early.")
        log(who = "EMP0003", projectId = crusher, taskId = rfqCard, text = "Quote in the client's currency.")
        log(who = "EMP0002", projectId = haulRoad, text = "Night shift needs its own supervision.")

        val crusherBoard = LessonService.board(LessonFilter(project = crusher), "EMP0001", canManage = true)
        assertEquals(2, crusherBoard.activeCount)
        val mine = LessonService.board(LessonFilter(employee = "EMP0002"), "EMP0002", canManage = false)
        assertEquals(
            // Both of the author's rows carry the same date, so this is the tie-break made visible: the
            // newer row leads, and a reader's own lessons never swap places under them.
            listOf("Night shift needs its own supervision.", "Crusher liner: order early."),
            mine.active.map { it.text },
        )
        val both = LessonService.board(LessonFilter(project = crusher, employee = "EMP0003"), "EMP0003", false)
        assertEquals(1, both.activeCount)
        assertTrue(LessonService.board(LessonFilter(project = haulRoad, employee = "EMP0003"), "EMP0003", false).isEmpty)
    }

    @Test
    fun `the sheet is newest first, with the undated carry-overs at the end of both orders`() {
        log(on = "2026-07-26", text = "oldest")
        log(on = "2026-07-30", text = "newest")
        log(on = "2026-07-28", text = "middle")
        // A popup cannot produce an undated row — an empty date is today — so the only undated rows on
        // the sheet are V9's carry-overs, and those are what the sort has to make room for.
        undated("carried over")
        val board = LessonService.board()
        assertEquals(listOf("newest", "middle", "oldest", "carried over"), board.active.map { it.text })
        val oldest = LessonService.board(LessonFilter(oldestFirst = true))
        assertEquals(listOf("oldest", "middle", "newest", "carried over"), oldest.active.map { it.text })
    }

    /** A row in the shape V9's backfill left it: one text, no author, no date. */
    private fun undated(text: String): Int = transaction {
        Lessons.insert {
            it[title] = text
            it[summary] = ""
            it[Lessons.lesson] = text
            it[loggedOn] = ""
            it[discussed] = false
            it[archived] = false
        } get Lessons.id
    }

    @Test
    fun `a row put away before the archive was retired stays off the sheet`() {
        // Stage 116 took the archive out of the sheet; the flag stays on the rows put away before then.
        // It is data, not a lesson: nothing prints it, nothing counts it, and the service offers no
        // write that could bring it back.
        log(who = "EMP0002", text = "Camp beds: keep a buffer.")
        val id = one().id
        assertEquals(1, LessonService.board().activeCount)
        assertEquals(1, LessonService.activeCount())

        transaction { Lessons.update({ Lessons.id eq id }) { it[archived] = true } }
        assertTrue(LessonService.board(employeeNumber = "EMP0002").isEmpty, "an archived row is still on the sheet")
        assertEquals(0, LessonService.board().activeCount)
        assertEquals(0, LessonService.activeCount(), "the sidebar pill counted a lesson somebody put away")
        // The row itself is untouched: the flag is the only thing that changed, and its text, its author
        // and its date are still there for whoever reads the database.
        val stored = LessonRepository.find(id)!!
        assertTrue(stored.archived)
        assertEquals("Camp beds: keep a buffer.", stored.text)
        assertEquals("EMP0002", stored.employeeNumber)
        assertEquals("2026-07-30", stored.loggedOn)
    }

    @Test
    fun `the discussed pill is a state and not a judgement on the text`() {
        log(who = "EMP0002")
        val id = one().id
        assertFalse(LessonRepository.find(id)!!.discussed)
        LessonService.setDiscussed(id, true, "EMP0002", false)
        assertTrue(LessonRepository.find(id)!!.discussed)
        LessonService.setDiscussed(id, false, "EMP0002", false)
        assertFalse(LessonRepository.find(id)!!.discussed)
        assertEquals("Not discussed", LessonRepository.find(id)!!.status)
    }

    @Test
    fun `each row says whether the reader looking at it may write it`() {
        log(who = "EMP0002", text = "mine")
        log(who = "EMP0003", text = "theirs")
        val asAuthor = LessonService.board(employeeNumber = "EMP0002", canManage = false)
        assertEquals(mapOf("mine" to true, "theirs" to false), asAuthor.active.associate { it.text to it.mayEdit })
        val asManagement = LessonService.board(employeeNumber = "EMP0001", canManage = true)
        assertTrue(asManagement.active.all { it.mayEdit })
    }

    @Test
    fun `a carried-over note reads as one lesson and keeps its place on the sheet`() {
        // V9's backfill joins title and summary in SQL; a row written before it by the importer or a legacy
        // path has an empty `lesson`, and the sheet still has to print one sentence rather than two cells.
        val id = transaction {
            Lessons.insert {
                it[title] = "Monsoon drainage planning"
                it[summary] = "Undersized culverts caused rework."
                it[lesson] = ""
                it[loggedOn] = ""
                it[discussed] = false
                it[archived] = false
            } get Lessons.id
        }
        val entry = LessonRepository.find(id)!!
        assertEquals("Monsoon drainage planning — Undersized culverts caused rework.", entry.text)
        assertEquals("", entry.employeeNumber, "an unowned carry-over was given an author")
        assertEquals("", entry.loggedOn)
        val board = LessonService.board(employeeNumber = "EMP0002", canManage = false)
        assertEquals(1, board.activeCount)
        assertFalse(board.active.single().mayEdit, "an unowned carry-over was offered for editing")
        // Deleting the project a lesson was about leaves the lesson: the key is SET NULL, not cascading.
        log(who = "EMP0002", projectId = crusher, text = "About the crusher.")
        transaction { ProjectRepository.delete(crusher) }
        val orphan = rows().first { it.text == "About the crusher." }
        assertEquals(0, orphan.projectId)
        assertEquals("About the crusher.", orphan.text, "losing a project took its lesson with it")
    }

    // --- the view's own keys ------------------------------------------------------------------------

    @Test
    fun `the sheet is the address it states`() {
        val blank = LessonFilter()
        assertEquals("/app/lessons", blank.queryFor())
        assertFalse(blank.isActive)
        assertEquals("old", blank.sortKey, "the control offers the order the page does not have")

        val view = LessonFilter(project = 7, employee = "EMP 0002", oldestFirst = true)
        assertTrue(view.isActive)
        assertEquals("new", view.sortKey)
        assertEquals("/app/lessons?project=7&employee=EMP+0002&sort=old", view.queryFor())
        // The carried view is the same view: a row write that lost a key would answer on another sheet.
        val back = LessonFilter.of(mapOf("project" to " 7 ", "employee" to "EMP 0002", "sort" to "old"))
        assertEquals(view, back)
        // The retired archive's key is not one the sheet reads any more (Stage 116).
        assertEquals(LessonFilter(), LessonFilter.of(mapOf("sort" to "sideways", "archived" to "1")))
    }

    @Test
    fun `the popup's fields are the form it posted`() {
        val posted = LessonDraft.of(
            mapOf("id" to " 12 ", "text" to " words ", "project" to "3", "task" to "4", "on" to "2026-07-30"),
        )
        assertEquals(LessonDraft(id = 12, text = " words ", projectId = 3, taskId = 4, loggedOn = "2026-07-30"), posted)
        // A form with nothing in it is a new row with nothing typed, which is what the service refuses.
        assertEquals(LessonDraft(), LessonDraft.of(emptyMap()))
    }

    @Test
    fun `the card picker offers both boards and names the project each card belongs to`() {
        val choices = LessonService.taskChoices()
        assertEquals(
            listOf("Detail plate drawings - Rev A", "RFQ - 20mm steel plate, grade 250"),
            choices.filter { it.projectId == crusher }.map { it.title }.sorted(),
        )
        assertEquals("M-01250", choices.first { it.id == plateCard }.projectCode)
        assertEquals("S-00005", choices.first { it.id == otherCard }.projectCode)
        assertEquals(0, choices.first { it.id == backlogCard }.projectId, "a backlog card was given a project")
        assertEquals(4, choices.size, "a card the sheet can offer is missing from the picker")
    }
}
